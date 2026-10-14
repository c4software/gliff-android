package io.github.c4software.gliff.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isCaptionBarVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.c4software.gliff.data.clipboard.AndroidClipboard
import io.github.c4software.gliff.presentation.session.SessionRoute
import io.github.c4software.gliff.presentation.session.SessionViewModel
import io.github.c4software.gliff.presentation.theme.GliffTheme
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf

/**
 * One machine's remote screen, in a task of its own so several machines can
 * sit side by side in desktop mode. The manifest keeps this activity alive
 * across size, rotation and display changes; the session itself belongs to
 * the application, so even a recreated activity finds it running.
 */
class SessionActivity : ComponentActivity(), KoinComponent {
    private var machineId: String? = null
    private val clipboard: AndroidClipboard by inject()

    /** Kept with the activity across its recreations; cleared, ending the session, when it finishes. */
    private val viewModel: SessionViewModel by viewModels {
        viewModelFactory { initializer { get<SessionViewModel> { parametersOf(machineId) } } }
    }

    /** The window's position is ours to choose: a phone's whole screen, not a window among others. */
    private var turns by mutableStateOf(false)
    private var landscape = false

    /**
     * The screen is blacked out, a browser showing the session in its place.
     * `touches` counts the fingers put down, each one lighting it for a while.
     */
    private var dark by mutableStateOf(false)
    private var touches by mutableIntStateOf(0)
    private var waking = false

    /** No mouse and keyboard together: the screen keeps its floating buttons. */
    private var touchControls by mutableStateOf(true)
    private val inputDevices = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateTouchControls()
        override fun onInputDeviceRemoved(deviceId: Int) = updateTouchControls()
        override fun onInputDeviceChanged(deviceId: Int) = updateTouchControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        blendHeaderBar()
        val id = intent.data?.lastPathSegment
        if (id == null) {
            finish()
            return
        }
        machineId = id
        val viewModel = viewModel
        title = viewModel.uiState.value.address.ifEmpty { null } ?: title
        applyImmersion()
        applyOrientation()
        setContent {
            val ui by viewModel.uiState.collectAsState()
            LaunchedEffect(ui.keepAwake) {
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (ui.keepAwake) window.addFlags(flag) else window.clearFlags(flag)
            }
            val darkens = ui.darkens
            LaunchedEffect(darkens, touches) {
                dark = false
                if (darkens) {
                    delay(DARK_AFTER_MS)
                    dark = true
                }
            }
            LaunchedEffect(dark) {
                // As dim as the panel goes; on an OLED a black picture then lights nothing.
                window.attributes = window.attributes.apply {
                    screenBrightness = if (dark) {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                    } else {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
            GliffTheme {
                SessionRoute(
                    viewModel,
                    onBack = ::finish,
                    turns = turns,
                    onLandscape = {
                        landscape = it
                        applyOrientation()
                    },
                    touchControls = touchControls,
                )
                HeaderTitle(ui.address)
                if (dark) Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // The touch that lights the screen was not aimed at anything on it.
            waking = dark
            touches++
        }
        return if (waking) true else super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // The web mirror shows the session whose window was used last.
        if (machineId != null) viewModel.onShown()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyImmersion()
        applyOrientation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersion()
            offerClipboard()
        }
    }

    /** Timestamp of the clipboard content the session was last told of. */
    private var clipboardSeen = -1L
    private val clipboardChanged = ClipboardManager.OnPrimaryClipChangedListener { offerClipboard() }

    /**
     * Tell the session what the clipboard holds, when it changed. Only the
     * focused window may read it, hence here and not in the background.
     */
    private fun offerClipboard() {
        if (!hasWindowFocus() || machineId == null || !viewModel.sharesClipboard()) return
        val content = clipboard.read(clipboardSeen) ?: return
        clipboardSeen = content.timestamp
        viewModel.localClipboard(content.text)
    }

    override fun onStart() {
        super.onStart()
        clipboard.onChange(clipboardChanged)
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputDevices, null)
        updateTouchControls()
    }

    override fun onStop() {
        clipboard.removeOnChange(clipboardChanged)
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputDevices)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A keyboard plugged in or out is a configuration change, kept by configChanges.
        updateTouchControls()
    }

    private fun updateTouchControls() {
        touchControls = !hasMouseAndKeyboard()
    }

    /**
     * A phone holds the remote screen upright until its button lays it down,
     * and stands it back up for the keyboard, which would cover most of a
     * lying screen. A free-form or split window, and a tablet or a desktop
     * display, keep the shape the user gave them.
     */
    private fun applyOrientation() {
        turns = !isInMultiWindowMode &&
            resources.configuration.smallestScreenWidthDp < LARGE_SCREEN_DP
        requestedOrientation = when {
            !turns -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * Full screen hides the system bars; a free-form or split window keeps
     * them, and its caption bar, like any desktop window.
     */
    private fun applyImmersion() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isInMultiWindowMode) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** From this width up Android itself stops honouring orientation requests. */
        private const val LARGE_SCREEN_DP = 600

        /** How long the screen stays lit after a touch while a browser watches. */
        private const val DARK_AFTER_MS = 5000L

        /** One task per machine: opening a machine again brings its window back. */
        fun intent(context: Context, machineId: String): Intent =
            Intent(context, SessionActivity::class.java)
                .setData(Uri.Builder().scheme("gliff").authority("session").appendPath(machineId).build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}

/** The machine's name in the header bar of a desktop window, which shows through over the app's black. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderTitle(title: String) {
    if (!WindowInsets.isCaptionBarVisible || title.isEmpty()) return
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.captionBar), contentAlignment = Alignment.Center) {
        Text(
            title,
            // Clear of the app's chip on the left and the window's buttons on the right.
            Modifier.padding(horizontal = 160.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
