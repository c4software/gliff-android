package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.ClientMsg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Against the keymap of a real remote: bépo, with AltGr also on Caps Lock. */
class KeyLayoutTest {
    private val text = javaClass.getResource("/bepo.xkb")!!.readText()
    private val bepo = assertNotNull(KeyLayout.parse(text))

    private fun stroke(char: Char) = bepo.stroke(char.code)

    @Test
    fun charactersAreWhereTheRemotePutThem() {
        assertEquals(text, bepo.text)
        // The top row reads b é p o è where a US keyboard reads q w e r t.
        assertEquals(KeyStroke(16, false), stroke('b'))
        assertEquals(KeyStroke(17, false), stroke('é'))
        assertEquals(KeyStroke(17, true), stroke('É'))
        // W is on the key a US keyboard calls backslash, which is why Super+W closed nothing.
        assertEquals(KeyStroke(43, false), stroke('w'))
        assertEquals(KeyStroke(30, false), stroke('a'))
        // Digits are shifted, and the pipe is AltGr on the b key.
        assertEquals(KeyStroke(2, true), stroke('1'))
        assertEquals(KeyStroke(2, false), stroke('"'))
        assertEquals(KeyStroke(16, false, altGr = true), stroke('|'))
        assertEquals(KeyStroke(57, false), stroke(' '))
        assertEquals(KeyStroke(57, false, altGr = true), stroke('_'))
        assertEquals(100, bepo.altGrKey)
        // Dead keys and the layout's private markers type nothing.
        assertNull(bepo.stroke(0xfdd3))
    }

    @Test
    fun typingFollowsTheLayout() {
        val sent = ArrayList<ClientMsg>()
        val keys = Keyboard({ bepo }) { sent.add(it) }
        assertEquals(0, keys.type("w", listOf(Keys.LEFT_META)))
        assertEquals(
            listOf(ClientMsg.Key(Keys.LEFT_META, true), ClientMsg.Key(43, true), ClientMsg.Key(43, false), ClientMsg.Key(Keys.LEFT_META, false)),
            sent.toList(),
        )
        sent.clear()
        assertEquals(0, keys.type("é|\n"))
        assertEquals(
            listOf(
                ClientMsg.Key(17, true), ClientMsg.Key(17, false),
                ClientMsg.Key(100, true), ClientMsg.Key(16, true), ClientMsg.Key(16, false), ClientMsg.Key(100, false),
                ClientMsg.Key(Keys.ENTER, true), ClientMsg.Key(Keys.ENTER, false),
            ),
            sent.toList(),
        )
        // Every letter, digit and common sign of a French text has a key.
        assertEquals(0, keys.type("Voilà : ça vaut 12,50 € — « déjà » ? (oui) où œuf l’été #@{}[]<>/\\~&%*+=!;"))
        // A circumflex is a dead key on this layout: typing û needs two keys, which is not done yet.
        assertEquals(1, keys.type("û"))
    }

    @Test
    fun whatIsNotAKeymapIsRefused() {
        assertNull(KeyLayout.parse(""))
        assertNull(KeyLayout.parse("hyprctl: command not found"))
        assertNull(KeyLayout.parse("xkb_keymap { xkb_keycodes { <AD01> = 24; }; xkb_symbols { key <AD01> { [ q, Q ] }; }; };"))
        assertEquals(KeyStroke(17, false), KeyLayout.US.stroke('w'.code))
        assertEquals("", KeyLayout.US.text)
    }

    @Test
    fun keysymNamesBecomeCharacters() {
        assertEquals('é'.code, KeyLayout.keysym("eacute"))
        assertEquals('ÿ'.code, KeyLayout.keysym("ydiaeresis"))
        assertEquals(0xa0, KeyLayout.keysym("nobreakspace"))
        assertEquals('€'.code, KeyLayout.keysym("EuroSign"))
        assertEquals(0x20ac, KeyLayout.keysym("U20AC"))
        assertEquals(0x20ac, KeyLayout.keysym("0x10020ac"))
        assertEquals('/'.code, KeyLayout.keysym("slash"))
        assertEquals('7'.code, KeyLayout.keysym("7"))
        for (none in listOf("dead_acute", "ISO_Level3_Shift", "NoSymbol", "Return", "UFDD3", "VoidSymbol", "F1")) {
            assertNull(KeyLayout.keysym(none), none)
        }
    }
}
