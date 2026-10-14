package io.github.c4software.gliff.domain.settings

import kotlinx.coroutines.flow.StateFlow

/** What the user chose for every machine, kept across launches. */
interface SettingsRepository {
    val settings: StateFlow<Settings>

    fun update(edit: (Settings) -> Settings)
}
