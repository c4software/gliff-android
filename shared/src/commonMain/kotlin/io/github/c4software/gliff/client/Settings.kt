package io.github.c4software.gliff.client

import io.github.c4software.gliff.mirror.Viewport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the user chose for every machine. */
@Serializable
data class Settings(
    /** The remote's display scale; 0 follows this device's own, or is 1 at an imposed resolution. */
    val scale: Float = 0f,
    /** The resolution imposed on the remote, long side first; 0 follows the window. */
    val forcedWidth: Int = 0,
    val forcedHeight: Int = 0,
    /** Draw the remote smaller while the keyboard is up, so it shows as much as without. */
    val keyboardZoom: Boolean = true,
    /** A tap clicks where it lands; off, the screen starts as a trackpad. */
    val directTouch: Boolean = true,
    /** Share the text clipboard with the remote, both ways. */
    val clipboard: Boolean = true,
    /** Keep the screen on while a session is shown. */
    val keepAwake: Boolean = true,
    /** Show the session to web browsers on the network. */
    val httpMirror: Boolean = false,
    /** Let those browsers use the mouse and the keyboard too. */
    val mirrorControl: Boolean = false,
    /** Black out this device's screen while a browser watches the mirror. */
    val mirrorDark: Boolean = false,
    /** The secret part of the mirror's address. */
    val mirrorToken: String = "",
) {
    /** The resolution and scale the remote is held to, null when it follows the window. */
    val forced: Viewport?
        get() = if (forcedWidth > 0 && forcedHeight > 0) Viewport(forcedWidth, forcedHeight, if (scale > 0f) scale else 1f) else null

    companion object {
        /** The scales offered; anything else the compositor would round anyway. */
        /** The resolutions offered besides the window's own. */
        val RESOLUTIONS = listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)

        val SCALES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    }
}

class SettingsRepository(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(
        store.get(KEY)?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
    )

    val settings: StateFlow<Settings> get() = state

    fun update(edit: (Settings) -> Settings) {
        state.update(edit)
        store.set(KEY, json.encodeToString(state.value))
    }

    private companion object {
        const val KEY = "settings"
    }
}
