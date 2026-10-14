package io.github.c4software.gliff.data.mirror

import kotlinx.coroutines.flow.StateFlow

/**
 * The web mirror, which shows the session in front to browsers on the
 * network. Only some platforms have one: elsewhere there is none to inject.
 */
interface WebMirror {
    /** Whether a browser is watching. */
    val watched: StateFlow<Boolean>

    /** Where the mirror answers for this token. */
    fun urls(token: String): List<String>
}
