package io.github.c4software.gliff.android

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.content.ClipboardManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.c4software.gliff.client.SessionController
import io.github.c4software.gliff.ui.GliffTheme
import io.github.c4software.gliff.ui.SessionScreen
import kotlinx.coroutines.delay

/**
 * One machine's remote screen, in a task of its own so several machines can
 * sit side by side in desktop mode. The manifest keeps this activity alive
 * across size, rotation and display changes; the session itself belongs to
 * the application, so even a recreated activity finds it running.
 */
class SessionActivity : ComponentActivity() {
    private var machineId: String? = null
    private var controller: SessionController? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.data?.lastPathSegment
        if (id == null) {
            finish()
            return
        }
        machineId = id
        val graph = (application as GliffApplication).graph
        val controller = graph.session(id)
        this.controller = controller
        title = controller.machine?.address ?: title
        applyImmersion()
        applyOrientation()
        setContent {
            val settings by graph.settings.settings.collectAsState()
            LaunchedEffect(settings.keepAwake) {
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (settings.keepAwake) window.addFlags(flag) else window.clearFlags(flag)
            }
            val watched by graph.mirrorWatched.collectAsState()
            val darkens = settings.httpMirror && settings.mirrorDark && watched
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
                SessionScreen(
                    controller,
                    onBack = ::finish,
                    turns = turns,
                    settings = settings,
                    onLandscape = {
                        landscape = it
                        applyOrientation()
                    },
                )
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
        machineId?.let { (application as GliffApplication).graph.front(it) }
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
        if (!hasWindowFocus()) return
        val graph = (application as GliffApplication).graph
        if (!graph.settings.settings.value.clipboard) return
        val content = graph.clipboard.read(clipboardSeen) ?: return
        clipboardSeen = content.timestamp
        controller?.localClipboard(content.text)
    }

    override fun onStart() {
        super.onStart()
        (application as GliffApplication).graph.clipboard.onChange(clipboardChanged)
    }

    override fun onStop() {
        (application as GliffApplication).graph.clipboard.removeOnChange(clipboardChanged)
        super.onStop()
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

    override fun onDestroy() {
        // Finishing is the user closing the window; anything else is the
        // system recreating it, and the session must survive that.
        if (isFinishing) machineId?.let { (application as GliffApplication).graph.endSession(it) }
        super.onDestroy()
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
