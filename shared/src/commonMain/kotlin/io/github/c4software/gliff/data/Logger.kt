package io.github.c4software.gliff.data

/** The platform's log. */
fun interface Logger {
    fun log(message: String, cause: Throwable?)
}
