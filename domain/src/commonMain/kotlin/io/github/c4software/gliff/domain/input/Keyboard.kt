package io.github.c4software.gliff.domain.input

import io.github.c4software.gliff.proto.ClientMsg

/** Linux evdev key codes, which is what `ClientMsg.Key` carries. */
object Keys {
    const val ESC = 1
    const val BACKSPACE = 14
    const val TAB = 15
    const val ENTER = 28
    const val LEFT_CTRL = 29
    const val LEFT_SHIFT = 42
    const val LEFT_ALT = 56
    const val SPACE = 57
    const val HOME = 102
    const val UP = 103
    const val PAGE_UP = 104
    const val LEFT = 105
    const val RIGHT = 106
    const val END = 107
    const val DOWN = 108
    const val PAGE_DOWN = 109
    const val INSERT = 110
    const val DELETE = 111
    const val LEFT_META = 125

    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
}

/**
 * Typed text as key presses, on the layout the session runs with: the
 * remote's own when it could be read, the server's US default otherwise.
 */
class Keyboard(private val layout: () -> KeyLayout = { KeyLayout.US }, private val send: (ClientMsg) -> Unit) {
    /**
     * Type `text` with `modifiers` (evdev codes) held around each character.
     * Returns how many characters had no key.
     */
    fun type(text: String, modifiers: Collection<Int> = emptyList()): Int {
        val keys = layout()
        var missing = 0
        var i = 0
        while (i < text.length) {
            // A pair of surrogates is one character.
            val high = text[i]
            val low = text.getOrNull(i + 1)
            val codePoint = if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                i++
                0x10000 + ((high.code - 0xd800) shl 10) + (low.code - 0xdc00)
            } else {
                high.code
            }
            i++
            when (codePoint) {
                '\n'.code, '\r'.code -> tap(Keys.ENTER, modifiers)
                '\t'.code -> tap(Keys.TAB, modifiers)
                else -> {
                    val stroke = keys.stroke(codePoint) ?: STAND_INS[codePoint]?.let { keys.stroke(it.code) }
                    if (stroke == null) {
                        missing++
                    } else {
                        var held = modifiers
                        if (stroke.shift) held = held + Keys.LEFT_SHIFT
                        if (stroke.altGr) held = held + keys.altGrKey
                        tap(stroke.code, held)
                    }
                }
            }
        }
        return missing
    }

    /** Press and release one key, with `modifiers` held around it. */
    fun tap(code: Int, modifiers: Collection<Int> = emptyList()) {
        modifiers.forEach { send(ClientMsg.Key(it, true)) }
        send(ClientMsg.Key(code, true))
        send(ClientMsg.Key(code, false))
        modifiers.reversed().forEach { send(ClientMsg.Key(it, false)) }
    }

    /** A key of a physical keyboard, passed through as it is. */
    fun raw(code: Int, pressed: Boolean) = send(ClientMsg.Key(code, pressed))

    private companion object {
        /** What to type for what a phone keyboard puts in place of a plain character, on a layout without it. */
        val STAND_INS = mapOf(
            0xa0 to ' ', 0x202f to ' ', 0x2018 to '\'', 0x2019 to '\'', 0x201c to '"', 0x201d to '"',
            0x2013 to '-', 0x2014 to '-',
        )
    }
}
