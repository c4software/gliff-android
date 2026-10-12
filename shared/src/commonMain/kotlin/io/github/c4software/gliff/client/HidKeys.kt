package io.github.c4software.gliff.client

/**
 * Keys of a physical keyboard as USB names them (HID usage page 7, which is
 * what iOS reports), turned into the Linux evdev codes `ClientMsg.Key`
 * carries. The table is the kernel's own, from `hid-input.c`.
 */
object HidKeys {
    /** Usages 0x00 to 0x73; 0 where there is no key. */
    private val TABLE = intArrayOf(
        0, 0, 0, 0, 30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38,
        50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44, 2, 3,
        4, 5, 6, 7, 8, 9, 10, 11, 28, 1, 14, 15, 57, 12, 13, 26,
        27, 43, 43, 39, 40, 41, 51, 52, 53, 58, 59, 60, 61, 62, 63, 64,
        65, 66, 67, 68, 87, 88, 99, 70, 119, 110, 102, 104, 111, 107, 109, 106,
        105, 108, 103, 69, 98, 55, 74, 78, 96, 79, 80, 81, 75, 76, 77, 71,
        72, 73, 82, 83, 86, 127, 116, 117, 183, 184, 185, 186, 187, 188, 189, 190,
        191, 192, 193, 194,
    )

    /** Past the table: mute and volume, the keys of other alphabets, the modifiers. */
    private val OTHERS = mapOf(
        0x7f to 113, 0x80 to 115, 0x81 to 114,
        0x87 to 89, 0x88 to 93, 0x89 to 124, 0x8a to 92, 0x8b to 94,
        0x90 to 122, 0x91 to 123,
        0xe0 to 29, 0xe1 to 42, 0xe2 to 56, 0xe3 to 125,
        0xe4 to 97, 0xe5 to 54, 0xe6 to 100, 0xe7 to 126,
    )

    /** The evdev code of a HID keyboard usage; null when it names no key. */
    fun evdev(usage: Int): Int? {
        val code = if (usage in TABLE.indices) TABLE[usage] else OTHERS[usage] ?: 0
        return code.takeIf { it != 0 }
    }
}
