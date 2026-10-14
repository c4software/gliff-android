package io.github.c4software.gliff.domain.identity

/** The app's own ssh key. */
fun interface Identity {
    /** The line for the server's `~/.ssh/authorized_keys`. Blocking: the key is made on first use. */
    fun authorizedKey(): String
}
