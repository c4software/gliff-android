package io.github.c4software.gliff.data.clipboard

/** This device's clipboard, as the sessions fill it with what the remotes copy. */
fun interface ClipboardWriter {
    fun write(text: String)
}
