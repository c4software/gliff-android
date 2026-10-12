package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.Axis
import io.github.c4software.gliff.proto.ClientMsg
import kotlin.math.sign

/**
 * What the web mirror's page sends when it is allowed to control the
 * session: one event per line.
 *
 *     m X Y         the pointer, as fractions of the picture
 *     b N 0|1       button N (0 left, 1 middle, 2 right) released or pressed
 *     w DX DY       the wheel, in notches, positive right and down
 *     t MODS TEXT   typed text, percent-encoded, with modifiers held
 *     s MODS NAME   a key without a character, by its DOM name
 *
 * `MODS` adds up 1 for Ctrl, 2 for Alt, 4 for Super and 8 for Shift. The page
 * sends characters, not key positions: the browser's keyboard layout has
 * already been applied, and the remote types them on the session's layout.
 *
 * Lines that make no sense are skipped; a browser is not to be trusted with
 * more than these.
 */
class BrowserInput(layout: () -> KeyLayout = { KeyLayout.US }, private val send: (ClientMsg) -> Unit) {
    private val keys = Keyboard(layout, send)

    /** Apply `text` to a remote of `logicalWidth x logicalHeight` logical pixels. */
    fun apply(text: String, logicalWidth: Double, logicalHeight: Double) {
        for (line in text.lineSequence().take(MAX_LINES)) {
            val parts = line.split(' ', limit = 3)
            when (parts[0]) {
                "m" -> {
                    val x = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
                    val y = parts.getOrNull(2)?.toDoubleOrNull() ?: continue
                    if (x.isNaN() || y.isNaN()) continue
                    send(ClientMsg.PointerMotion(x.coerceIn(0.0, 1.0) * logicalWidth, y.coerceIn(0.0, 1.0) * logicalHeight))
                }
                "b" -> {
                    val button = BUTTONS.getOrNull(parts.getOrNull(1)?.toIntOrNull() ?: continue) ?: continue
                    send(ClientMsg.PointerButton(button, parts.getOrNull(2) == "1"))
                }
                "w" -> {
                    val dx = parts.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                    val dy = parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                    wheel(Axis.Vertical, dy)
                    wheel(Axis.Horizontal, dx)
                }
                "t" -> {
                    val mods = modifiers(parts.getOrNull(1)) ?: continue
                    keys.type(decode(parts.getOrNull(2) ?: continue), mods - Keys.LEFT_SHIFT)
                }
                "s" -> {
                    val mods = modifiers(parts.getOrNull(1)) ?: continue
                    keys.tap(SPECIAL[parts.getOrNull(2)] ?: continue, mods)
                }
            }
        }
    }

    private fun wheel(axis: Axis, notches: Double) {
        val n = notches.coerceIn(-MAX_NOTCHES, MAX_NOTCHES)
        if (n != 0.0) send(ClientMsg.PointerAxis(axis, n * Trackpad.WHEEL_NOTCH, sign(n).toInt(), false))
    }

    private fun modifiers(text: String?): List<Int>? {
        val bits = text?.toIntOrNull() ?: return null
        return MODIFIERS.filter { bits and it.first != 0 }.map { it.second }
    }

    /** Undo `encodeURIComponent`. */
    private fun decode(text: String): String {
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val code = if (text[i] == '%' && i + 2 < text.length + 0) text.substring(i + 1, minOf(i + 3, text.length)).toIntOrNull(16) else null
            if (code != null) {
                bytes.add(code.toByte())
                i += 3
            } else {
                text[i].toString().encodeToByteArray().forEach { bytes.add(it) }
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private companion object {
        const val MAX_LINES = 256
        const val MAX_NOTCHES = 20.0
        val BUTTONS = listOf(Keys.BTN_LEFT, Keys.BTN_MIDDLE, Keys.BTN_RIGHT)
        val MODIFIERS = listOf(1 to Keys.LEFT_CTRL, 2 to Keys.LEFT_ALT, 4 to Keys.LEFT_META, 8 to Keys.LEFT_SHIFT)
        val SPECIAL = mapOf(
            "Enter" to Keys.ENTER, "Backspace" to Keys.BACKSPACE, "Tab" to Keys.TAB, "Escape" to Keys.ESC,
            "ArrowUp" to Keys.UP, "ArrowDown" to Keys.DOWN, "ArrowLeft" to Keys.LEFT, "ArrowRight" to Keys.RIGHT,
            "Home" to Keys.HOME, "End" to Keys.END, "PageUp" to Keys.PAGE_UP, "PageDown" to Keys.PAGE_DOWN,
            "Delete" to Keys.DELETE, "Insert" to Keys.INSERT,
            "F1" to 59, "F2" to 60, "F3" to 61, "F4" to 62, "F5" to 63, "F6" to 64,
            "F7" to 65, "F8" to 66, "F9" to 67, "F10" to 68, "F11" to 87, "F12" to 88,
        )
    }
}
