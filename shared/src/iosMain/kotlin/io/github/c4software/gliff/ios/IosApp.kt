package io.github.c4software.gliff.ios

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import io.github.c4software.gliff.client.Identity
import io.github.c4software.gliff.client.MachineRepository
import io.github.c4software.gliff.client.SessionController
import io.github.c4software.gliff.client.SessionState
import io.github.c4software.gliff.client.Sessions
import io.github.c4software.gliff.client.SettingsRepository
import io.github.c4software.gliff.ui.App
import io.github.c4software.gliff.ui.GliffTheme
import io.github.c4software.gliff.ui.SessionHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import platform.UIKit.UIInterfaceOrientationMaskAll
import platform.UIKit.UIInterfaceOrientationMaskAllButUpsideDown
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIPasteboardChangedNotification
import platform.UIKit.UIPointerInteraction
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.addInteraction
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations

/**
 * What lives as long as the process: the saved machines, the ssh key and the
 * open sessions. The host app makes one, with its ssh client, and a
 * [MainViewController] for each window.
 */
class IosGraph(bridge: SshBridge) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val store = UserDefaultsStore()
    val machines = MachineRepository(store)
    val settings = SettingsRepository(store)
    val identity: Identity = IosIdentity(bridge)
    private val clipboard = IosClipboard()

    val sessions = Sessions(
        machines, settings,
        IosConnector(bridge) { NSLog("gliff-server: %@", it) },
        scope,
        nowMs = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
        clipboardOut = clipboard::write,
        log = { message, cause -> NSLog("gliff: %@", if (cause == null) message else "$message: $cause") },
        decoder = ::VideoToolboxDecoder,
    )

    /** A phone: one window, the whole screen, which this app turns itself. */
    val phone: Boolean = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone

    /**
     * Which way the screen may turn, as a `UIInterfaceOrientationMask`: what
     * the app delegate answers when UIKit asks. A phone holds a session
     * upright until its button lays it down.
     */
    var orientations: ULong = if (phone) UIInterfaceOrientationMaskAllButUpsideDown else UIInterfaceOrientationMaskAll
        private set

    /** `landscape`: a session's wish on a phone; null when no session is shown. */
    internal fun turn(landscape: Boolean?, controller: UIViewController) {
        if (!phone) return
        orientations = when (landscape) {
            null -> UIInterfaceOrientationMaskAllButUpsideDown
            true -> UIInterfaceOrientationMaskLandscape
            false -> UIInterfaceOrientationMaskPortrait
        }
        controller.setNeedsUpdateOfSupportedInterfaceOrientations()
        controller.view.window?.windowScene?.requestGeometryUpdateWithPreferences(
            UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = orientations),
            errorHandler = { NSLog("gliff: the screen did not turn: %@", it?.localizedDescription ?: "") },
        )
    }

    /** The windows that want the screen kept on. */
    private val awake = HashSet<Any>()

    internal fun keepAwake(window: Any, on: Boolean) {
        if (on) awake.add(window) else awake.remove(window)
        UIApplication.sharedApplication.idleTimerDisabled = awake.isNotEmpty()
    }

    /** The clipboard content the session in front was last told of. */
    private var clipboardSeen = -1L
    private var clipboardFor: SessionController? = null

    /**
     * Tell the session in front what the clipboard holds, when it changed.
     * iOS only lets the app in front read it, hence here and not in the
     * background. Main thread.
     */
    internal fun offerClipboard() {
        if (!settings.settings.value.clipboard) return
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return
        val session = sessions.frontSession ?: return
        if (session !== clipboardFor) {
            clipboardFor = session
            clipboardSeen = -1
        }
        val content = clipboard.read(clipboardSeen) ?: return
        clipboardSeen = content.change
        session.localClipboard(content.text)
    }

    init {
        val center = NSNotificationCenter.defaultCenter
        for (name in listOf(UIApplicationDidBecomeActiveNotification, UIPasteboardChangedNotification)) {
            center.addObserverForName(name, null, NSOperationQueue.mainQueue) { offerClipboard() }
        }
    }
}

/**
 * One window of the app. `only`: the machine this window belongs to, when the
 * host gives each machine a window of its own (iPad); null for the window
 * that starts on the list. `openElsewhere` opens a machine in such a window
 * and says whether it could; `onLeave` closes this one.
 */
fun MainViewController(
    graph: IosGraph,
    only: String?,
    openElsewhere: (machineId: String) -> Boolean,
    onLeave: () -> Unit,
): UIViewController {
    val window = WindowState(graph)
    val controller = ComposeUIViewController {
        val settings by graph.settings.settings.collectAsState()
        val shown = window.session != null
        DisposableEffect(shown, settings.keepAwake) {
            graph.keepAwake(window, shown && settings.keepAwake)
            onDispose { graph.keepAwake(window, false) }
        }
        GliffTheme {
            App(
                graph.machines, graph.settings, graph.identity, graph.sessions,
                host = SessionHost(
                    turns = graph.phone,
                    onLandscape = window::landscape,
                    onSession = window::show,
                ),
                only = only,
                openElsewhere = { openElsewhere(it.id) },
                onLeave = onLeave,
            )
        }
    }
    window.attach(controller)
    return controller
}

/** What one window holds besides its screen: the session it shows, and what follows from it. */
internal class WindowState(private val graph: IosGraph) {
    /** The session on screen, if any. */
    var session by mutableStateOf<SessionController?>(null)
        private set

    private var controller: UIViewController? = null
    private var watching: Job? = null

    /** Hides the mouse pointer over a private screen, whose video has the remote's own in it. */
    private val pointer = PointerHider {
        (session?.state?.value as? SessionState.Streaming)?.stream?.headless == true
    }
    private val pointerInteraction = UIPointerInteraction(delegate = pointer)

    fun attach(controller: UIViewController) {
        this.controller = controller
        controller.view.addInteraction(pointerInteraction)
    }

    /** A session is on screen, or (null) none is any more. */
    fun show(next: SessionController?) {
        if (next === session) return
        session = next
        watching?.cancel()
        if (next == null) {
            controller?.let { graph.turn(null, it) }
            pointerInteraction.invalidate()
            return
        }
        // The pointer is asked for anew whenever the session changes state.
        watching = graph.scope.launch { next.state.collect { pointerInteraction.invalidate() } }
        graph.offerClipboard()
    }

    fun landscape(landscape: Boolean) {
        if (session != null) controller?.let { graph.turn(landscape, it) }
    }
}
