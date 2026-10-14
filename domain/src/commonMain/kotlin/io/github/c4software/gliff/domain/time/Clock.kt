package io.github.c4software.gliff.domain.time

/** The wall clock. The only one: a test gives its own, which moves when told to. */
fun interface Clock {
    /** Milliseconds since the epoch. */
    fun nowMs(): Long
}
