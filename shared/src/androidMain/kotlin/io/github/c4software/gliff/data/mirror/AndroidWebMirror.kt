package io.github.c4software.gliff.data.mirror

import io.github.c4software.gliff.data.Logger
import io.github.c4software.gliff.data.session.MirrorSink
import io.github.c4software.gliff.data.session.SessionController
import io.github.c4software.gliff.data.session.Sessions
import io.github.c4software.gliff.domain.session.Viewport
import io.github.c4software.gliff.domain.settings.SettingsRepository
import io.github.c4software.gliff.mirror.MirrorServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The web mirror, running while the option is on. Made with the process, not
 * with the first screen: it has to answer whenever the app is alive.
 */
class AndroidWebMirror(
    private val sessions: Sessions,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
) : WebMirror {
    @Volatile
    private var mirror: MirrorServer? = null

    private val mutableWatched = MutableStateFlow(false)

    override val watched: StateFlow<Boolean> get() = mutableWatched

    init {
        scope.launch {
            settings.settings.map { Triple(it.httpMirror, it.mirrorToken, it.mirrorControl) }.distinctUntilChanged()
                .collect { (on, token, control) ->
                    if (on && token.isEmpty()) {
                        // Comes back here with the token set.
                        settings.update { it.copy(mirrorToken = MirrorServer.newToken()) }
                    } else {
                        // The viewers go with the server that had them.
                        viewed(null)
                        withContext(io) { run(if (on) token else null, control) }
                    }
                }
        }
    }

    override fun urls(token: String): List<String> =
        MirrorServer.addresses().map { "http://$it:${MirrorServer.DEFAULT_PORT}/$token/" }

    private fun run(token: String?, control: Boolean) {
        sessions.mirror = null
        mirror?.stop()
        mirror = null
        if (token == null) return
        val input: ((String) -> Unit)? = if (control) {
            { text -> sessions.frontSession?.browserInput(text) }
        } else {
            null
        }
        lateinit var server: MirrorServer
        server = MirrorServer(
            MirrorServer.DEFAULT_PORT, token,
            onViewer = ::keyframeForViewer,
            onInput = input,
            // A server stopped meanwhile has nothing more to say.
            onView = { view -> scope.launch { if (mirror === server) viewed(view) } },
        ) {
            android.util.Log.i("gliff", it)
        }
        // Before it listens: a page left open says its window the moment it can.
        mirror = server
        try {
            server.start()
        } catch (e: java.io.IOException) {
            logger.log("cannot start the web mirror", e)
            mirror = null
            return
        }
        sessions.mirror = object : MirrorSink {
            override fun configure(width: Int, height: Int) = server.configure(width, height)
            override fun frame(data: ByteArray, keyframe: Boolean) = server.frame(data, keyframe)
            override fun reset() = server.reset()
        }
    }

    /** A browser watches with a window of this size, or none does any more. Main thread. */
    private fun viewed(view: Viewport?) {
        sessions.viewport = view
        mutableWatched.value = view != null
    }

    /** A viewer starts on a keyframe; asked twice, as requests too close together are merged. */
    private fun keyframeForViewer() {
        scope.launch {
            repeat(2) {
                sessions.frontSession?.requestKeyframe()
                delay(SessionController.KEYFRAME_REQUEST_GAP_MS + 50)
            }
        }
    }
}
