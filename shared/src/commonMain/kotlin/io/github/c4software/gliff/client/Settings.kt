package io.github.c4software.gliff.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the user chose for every machine. */
@Serializable
data class Settings(
    /** The remote's display scale; 0 follows this device's own. */
    val scale: Float = 0f,
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
    /** The secret part of the mirror's address. */
    val mirrorToken: String = "",
) {
    companion object {
        /** The scales offered; anything else the compositor would round anyway. */
        val SCALES = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
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
