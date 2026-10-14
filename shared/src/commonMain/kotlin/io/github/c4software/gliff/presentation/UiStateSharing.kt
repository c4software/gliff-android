package io.github.c4software.gliff.presentation

import kotlinx.coroutines.flow.SharingStarted

/**
 * How a ViewModel observing a source shares its state: the observation stops
 * with no one on screen, and the grace covers a rotation without starting
 * everything over.
 */
val UiStateSharing: SharingStarted = SharingStarted.WhileSubscribed(5_000)
