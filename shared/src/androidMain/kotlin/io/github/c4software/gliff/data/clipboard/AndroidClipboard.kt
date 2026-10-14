package io.github.c4software.gliff.data.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The device's clipboard, text only. Android lets an app read it only while
 * its window has the focus, so the sessions look at it when they get the
 * focus and when it changes under them; writing has no such limit.
 */
class AndroidClipboard(context: Context) : ClipboardWriter {
    private val app = context.applicationContext
    private val manager = app.getSystemService(ClipboardManager::class.java)

    /** What the clipboard holds: when it was set, and its text (null when it holds none). */
    class Content(val timestamp: Long, val text: String?)

    /**
     * The clipboard's content when it is not the one of `seen`; null when
     * nothing changed or it cannot be read now. Only a change is read, since
     * reading shows the user a notice. Main thread.
     */
    fun read(seen: Long): Content? {
        val description = try {
            manager.primaryClipDescription
        } catch (_: RuntimeException) {
            return null
        } ?: return null
        if (description.timestamp == seen) return null
        if (!description.hasMimeType("text/*")) return Content(description.timestamp, null)
        val clip = try {
            manager.primaryClip
        } catch (_: RuntimeException) {
            null
        } ?: return null
        if (clip.itemCount == 0) return Content(description.timestamp, null)
        return Content(description.timestamp, clip.getItemAt(0).coerceToText(app)?.toString())
    }

    /** Text copied on the remote. Main thread. */
    override fun write(text: String) {
        try {
            manager.setPrimaryClip(ClipData.newPlainText("gliff", text))
        } catch (e: RuntimeException) {
            // Larger than the system passes around, or refused: the remote keeps its copy.
            android.util.Log.w("gliff", "cannot set the clipboard", e)
        }
    }

    fun onChange(listener: ClipboardManager.OnPrimaryClipChangedListener) = manager.addPrimaryClipChangedListener(listener)

    fun removeOnChange(listener: ClipboardManager.OnPrimaryClipChangedListener) =
        manager.removePrimaryClipChangedListener(listener)
}
