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
    /**
     * The resolution the remote is held to, null when it follows the window.
     * A pixel of it is a pixel of the remote: how much shows is the
     * resolution's to say, not a display scale's.
     */
    val forced: Viewport? get() = forced(forcedWidth, forcedHeight)

    /** The same for `machine`, which may have a resolution of its own, or none where the options have one. */
    fun forcedFor(machine: Machine?): Viewport? =
        if (machine == null || machine.forcedWidth < 0 || machine.forcedHeight < 0) forced
        else forced(machine.forcedWidth, machine.forcedHeight)

    private fun forced(width: Int, height: Int): Viewport? =
        if (width > 0 && height > 0) Viewport(width, height, 1f) else null

    companion object {
        /** The resolutions offered besides the window's own. */
        val RESOLUTIONS = listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)

        /** The sides a resolution typed by hand may have. */
        val SIDES = 240..8192
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
