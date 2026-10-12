package io.github.c4software.gliff.client

/** A key, and the modifiers that make it give one character. */
data class KeyStroke(val code: Int, val shift: Boolean, val altGr: Boolean = false)

/**
 * Which key types which character on the remote.
 *
 * Hyprland matches its own shortcuts against the layout of its configuration,
 * whatever keymap the key came with: on a remote set to bépo, the key a US
 * keyboard calls W is é, and Super with it closes nothing. So the session
 * asks the remote for its own keymap, hands it back as the session's, and
 * types with it: every character then means the same to the compositor, to
 * its shortcuts and to the applications. Characters the US layout lacks
 * (accents) come for free.
 *
 * Without a keymap from the remote, the server falls back to US and so does
 * this.
 */
class KeyLayout private constructor(
    /** The XKB text to send in `Hello`; empty for the server's default. */
    val text: String,
    private val strokes: Map<Int, KeyStroke>,
    /** The evdev code of the key that reaches the third level (AltGr). */
    val altGrKey: Int,
) {
    /** The stroke for a Unicode code point, or null when no key gives it. */
    fun stroke(codePoint: Int): KeyStroke? = strokes[codePoint]

    companion object {
        private const val RIGHT_ALT = 100

        /** The server's default: a plain US keyboard. */
        val US: KeyLayout = run {
            val rows = listOf(
                Triple("1234567890-=", "!@#$%^&*()_+", 2),
                Triple("qwertyuiop[]", "QWERTYUIOP{}", 16),
                Triple("asdfghjkl;'`", "ASDFGHJKL:\"~", 30),
                Triple("\\zxcvbnm,./", "|ZXCVBNM<>?", 43),
            )
            val map = HashMap<Int, KeyStroke>()
            for ((plain, shifted, first) in rows) {
                plain.forEachIndexed { i, c -> map[c.code] = KeyStroke(first + i, false) }
                shifted.forEachIndexed { i, c -> map[c.code] = KeyStroke(first + i, true) }
            }
            map[' '.code] = KeyStroke(Keys.SPACE, false)
            KeyLayout("", map, RIGHT_ALT)
        }

        private val KEYCODE = Regex("""<(\w+)>\s*=\s*(\d+)\s*;""")
        private val KEY = Regex("""key\s+<(\w+)>\s*\{([^}]*)\}""")
        private val GROUP_ONE = Regex("""symbols\[(?:Group)?1]\s*=\s*\[([^\]]*)]""")
        private val BARE = Regex("""^\s*\[([^\]]*)]""")

        /**
         * Read a compiled XKB keymap (text format v1, as `xkbcli
         * compile-keymap` prints it). Null when it is not one, or too odd to
         * type with: the session then stays on the default.
         */
        fun parse(text: String): KeyLayout? {
            val symbolsAt = text.indexOf("xkb_symbols")
            if (!text.contains("xkb_keymap") || symbolsAt < 0) return null
            val codes = HashMap<String, Int>()
            for (m in KEYCODE.findAll(text.substring(0, symbolsAt))) {
                // XKB codes are evdev codes plus 8.
                codes[m.groupValues[1]] = (m.groupValues[2].toIntOrNull() ?: continue) - 8
            }
            val strokes = HashMap<Int, KeyStroke>()
            var altGr = -1
            for (m in KEY.findAll(text, symbolsAt)) {
                val code = codes[m.groupValues[1]] ?: continue
                if (code <= 0) continue
                val body = m.groupValues[2]
                val list = (GROUP_ONE.find(body) ?: BARE.find(body))?.groupValues?.get(1) ?: continue
                val levels = list.split(',').map { it.trim() }
                if (levels.firstOrNull() == "ISO_Level3_Shift" && (altGr < 0 || code == RIGHT_ALT)) altGr = code
                // The first four levels are plain, Shift, AltGr and both, on every common key type.
                levels.take(4).forEachIndexed { level, name ->
                    val char = keysym(name) ?: return@forEachIndexed
                    val stroke = KeyStroke(code, shift = level % 2 == 1, altGr = level >= 2)
                    val known = strokes[char]
                    // The easiest way to a character wins: fewer modifiers, then the main block before the keypad.
                    if (known == null || effort(stroke) < effort(known)) strokes[char] = stroke
                }
            }
            // A keymap without the alphabet is not one to type with.
            if (('a'..'z').count { strokes.containsKey(it.code) } < 20) return null
            if (altGr < 0) strokes.values.removeAll { it.altGr }
            return KeyLayout(text, strokes, if (altGr < 0) RIGHT_ALT else altGr)
        }

        private fun effort(stroke: KeyStroke): Int =
            (if (stroke.shift) 1 else 0) + (if (stroke.altGr) 2 else 0) + (if (stroke.code >= 71) 4 else 0)

        /** The character an X keysym name stands for; null for keys that type none. */
        internal fun keysym(name: String): Int? {
            if (name.length == 1) return name[0].code
            NAMED[name]?.let { return it }
            // Unicode keysyms: U20AC, or 0x10020ac.
            if (name.length in 5..7 && name[0] == 'U') name.substring(1).toIntOrNull(16)?.let { return it.takeIf(::typeable) }
            if (name.startsWith("0x100") && name.length > 5) {
                name.substring(3).toIntOrNull(16)?.let { return (it and 0xffffff).takeIf(::typeable) }
            }
            return null
        }

        /** Private-use code points are a layout's own markers, not text. */
        private fun typeable(codePoint: Int) = codePoint in 0x20..0x10ffff && codePoint !in 0xe000..0xf8ff && codePoint !in 0xfdd0..0xfdef

        private val ASCII_NAMES = listOf(
            "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand", "apostrophe",
            "parenleft", "parenright", "asterisk", "plus", "comma", "minus", "period", "slash",
        )

        /** Latin-1 keysyms are their code points, in this order from U+00A0. */
        private val LATIN1_NAMES = listOf(
            "nobreakspace", "exclamdown", "cent", "sterling", "currency", "yen", "brokenbar", "section",
            "diaeresis", "copyright", "ordfeminine", "guillemotleft", "notsign", "hyphen", "registered", "macron",
            "degree", "plusminus", "twosuperior", "threesuperior", "acute", "mu", "paragraph", "periodcentered",
            "cedilla", "onesuperior", "masculine", "guillemotright", "onequarter", "onehalf", "threequarters", "questiondown",
            "Agrave", "Aacute", "Acircumflex", "Atilde", "Adiaeresis", "Aring", "AE", "Ccedilla",
            "Egrave", "Eacute", "Ecircumflex", "Ediaeresis", "Igrave", "Iacute", "Icircumflex", "Idiaeresis",
            "ETH", "Ntilde", "Ograve", "Oacute", "Ocircumflex", "Otilde", "Odiaeresis", "multiply",
            "Oslash", "Ugrave", "Uacute", "Ucircumflex", "Udiaeresis", "Yacute", "THORN", "ssharp",
            "agrave", "aacute", "acircumflex", "atilde", "adiaeresis", "aring", "ae", "ccedilla",
            "egrave", "eacute", "ecircumflex", "ediaeresis", "igrave", "iacute", "icircumflex", "idiaeresis",
            "eth", "ntilde", "ograve", "oacute", "ocircumflex", "otilde", "odiaeresis", "division",
            "oslash", "ugrave", "uacute", "ucircumflex", "udiaeresis", "yacute", "thorn", "ydiaeresis",
        )

        private val NAMED: Map<String, Int> = HashMap<String, Int>().apply {
            ASCII_NAMES.forEachIndexed { i, name -> put(name, 0x20 + i) }
            LATIN1_NAMES.forEachIndexed { i, name -> put(name, 0xa0 + i) }
            for ((name, char) in listOf(
                "colon" to ':', "semicolon" to ';', "less" to '<', "equal" to '=', "greater" to '>', "question" to '?',
                "at" to '@', "bracketleft" to '[', "backslash" to '\\', "bracketright" to ']', "asciicircum" to '^',
                "underscore" to '_', "grave" to '`', "braceleft" to '{', "bar" to '|', "braceright" to '}',
                "asciitilde" to '~', "quoteright" to '\'', "quoteleft" to '`',
                "guillemetleft" to '\u00ab', "guillemetright" to '\u00bb', "ordmasculine" to '\u00ba', "Ooblique" to '\u00d8',
                "ooblique" to '\u00f8', "EuroSign" to '\u20ac', "oe" to '\u0153', "OE" to '\u0152', "Ydiaeresis" to '\u0178',
                "ellipsis" to '\u2026', "endash" to '\u2013', "emdash" to '\u2014',
                "leftsinglequotemark" to '\u2018', "rightsinglequotemark" to '\u2019', "singlelowquotemark" to '\u201a',
                "leftdoublequotemark" to '\u201c', "rightdoublequotemark" to '\u201d', "doublelowquotemark" to '\u201e',
                "dagger" to '\u2020', "doubledagger" to '\u2021', "trademark" to '\u2122', "permille" to '\u2030',
                "minutes" to '\u2032', "seconds" to '\u2033', "enfilledcircbullet" to '\u2022', "infinity" to '\u221e',
                "notequal" to '\u2260', "lessthanequal" to '\u2264', "greaterthanequal" to '\u2265', "leftarrow" to '\u2190',
                "uparrow" to '\u2191', "rightarrow" to '\u2192', "downarrow" to '\u2193', "ssharp" to '\u00df',
                "figdash" to '\u2012', "nobreakspace" to '\u00a0',
            )) put(name, char.code)
        }
    }
}
