package io.github.c4software.gliff.data.local

import io.github.c4software.gliff.domain.settings.Settings
import io.github.c4software.gliff.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The options, as one JSON object in the store. */
class StoredSettingsRepository(private val store: KeyValueStore) : SettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(
        store.get(KEY)?.let { runCatching { json.decodeFromString<SettingsRecord>(it).toSettings() }.getOrNull() }
            ?: Settings()
    )

    override val settings: StateFlow<Settings> get() = state

    override fun update(edit: (Settings) -> Settings) {
        state.update(edit)
        store.set(KEY, json.encodeToString(SettingsRecord(state.value)))
    }

    private companion object {
        const val KEY = "settings"
    }
}

/** The options as they are stored; the same names and defaults as earlier versions, see [MachineRecord]. */
@Serializable
internal data class SettingsRecord(
    val forcedWidth: Int = 0,
    val forcedHeight: Int = 0,
    val keyboardZoom: Boolean = true,
    val directTouch: Boolean = true,
    val clipboard: Boolean = true,
    val keepAwake: Boolean = true,
    val httpMirror: Boolean = false,
    val mirrorControl: Boolean = false,
    val mirrorDark: Boolean = false,
    val mirrorToken: String = "",
    val desktopFullScreen: Boolean = false,
) {
    constructor(settings: Settings) : this(
        settings.forcedWidth, settings.forcedHeight, settings.keyboardZoom, settings.directTouch, settings.clipboard,
        settings.keepAwake, settings.httpMirror, settings.mirrorControl, settings.mirrorDark, settings.mirrorToken,
        settings.desktopFullScreen,
    )

    fun toSettings() = Settings(
        forcedWidth, forcedHeight, keyboardZoom, directTouch, clipboard,
        keepAwake, httpMirror, mirrorControl, mirrorDark, mirrorToken,
        desktopFullScreen,
    )
}
