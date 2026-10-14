package io.github.c4software.gliff.data.clipboard

import platform.UIKit.UIPasteboard

/**
 * The device's clipboard, text only. Reading what another app copied makes
 * iOS ask the user, or tell them, each time: so only a change is read, and
 * only once it is known to be text. Main thread.
 */
class IosClipboard : ClipboardWriter {
    private val pasteboard get() = UIPasteboard.generalPasteboard

    /** What the clipboard holds: its change count, and its text (null when it holds none). */
    class Content(val change: Long, val text: String?)

    /** The clipboard's content when it is not the one of `seen`; null when nothing changed. */
    fun read(seen: Long): Content? {
        val change = pasteboard.changeCount
        if (change == seen) return null
        if (!pasteboard.hasStrings) return Content(change, null)
        return Content(change, pasteboard.string)
    }

    /** Text copied on the remote. */
    override fun write(text: String) {
        pasteboard.string = text
    }
}
