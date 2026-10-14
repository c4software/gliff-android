package io.github.c4software.gliff.domain.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HidKeysTest {
    @Test
    fun lettersAndDigitsFollowTheKeyNotTheAlphabet() {
        // HID counts a, b, c...; evdev counts along the rows of the keyboard.
        assertEquals(30, HidKeys.evdev(0x04)) // A
        assertEquals(16, HidKeys.evdev(0x14)) // Q
        assertEquals(44, HidKeys.evdev(0x1d)) // Z
        assertEquals(2, HidKeys.evdev(0x1e)) // 1
        assertEquals(11, HidKeys.evdev(0x27)) // 0
    }

    @Test
    fun theKeysTheScreenAlsoOffers() {
        assertEquals(Keys.ENTER, HidKeys.evdev(0x28))
        assertEquals(Keys.ESC, HidKeys.evdev(0x29))
        assertEquals(Keys.BACKSPACE, HidKeys.evdev(0x2a))
        assertEquals(Keys.TAB, HidKeys.evdev(0x2b))
        assertEquals(Keys.SPACE, HidKeys.evdev(0x2c))
        assertEquals(Keys.INSERT, HidKeys.evdev(0x49))
        assertEquals(Keys.HOME, HidKeys.evdev(0x4a))
        assertEquals(Keys.PAGE_UP, HidKeys.evdev(0x4b))
        assertEquals(Keys.DELETE, HidKeys.evdev(0x4c))
        assertEquals(Keys.END, HidKeys.evdev(0x4d))
        assertEquals(Keys.PAGE_DOWN, HidKeys.evdev(0x4e))
        assertEquals(Keys.RIGHT, HidKeys.evdev(0x4f))
        assertEquals(Keys.LEFT, HidKeys.evdev(0x50))
        assertEquals(Keys.DOWN, HidKeys.evdev(0x51))
        assertEquals(Keys.UP, HidKeys.evdev(0x52))
    }

    @Test
    fun modifiersKeepTheirSide() {
        assertEquals(Keys.LEFT_CTRL, HidKeys.evdev(0xe0))
        assertEquals(Keys.LEFT_SHIFT, HidKeys.evdev(0xe1))
        assertEquals(Keys.LEFT_ALT, HidKeys.evdev(0xe2))
        assertEquals(Keys.LEFT_META, HidKeys.evdev(0xe3))
        assertEquals(97, HidKeys.evdev(0xe4)) // right ctrl
        assertEquals(54, HidKeys.evdev(0xe5)) // right shift
        assertEquals(100, HidKeys.evdev(0xe6)) // right alt: AltGr
        assertEquals(126, HidKeys.evdev(0xe7)) // right meta
    }

    @Test
    fun functionKeysAndTheExtraKeyOfIsoKeyboards() {
        assertEquals(59, HidKeys.evdev(0x3a)) // F1
        assertEquals(68, HidKeys.evdev(0x43)) // F10
        assertEquals(87, HidKeys.evdev(0x44)) // F11
        assertEquals(88, HidKeys.evdev(0x45)) // F12
        assertEquals(86, HidKeys.evdev(0x64)) // the key next to the left shift
        assertEquals(194, HidKeys.evdev(0x73)) // F24
    }

    @Test
    fun whatIsNoKeyHasNoCode() {
        for (usage in listOf(-1, 0, 1, 2, 3, 0x74, 0xa0, 0xe8, 0xffff)) assertNull(HidKeys.evdev(usage), "usage $usage")
    }
}
