package io.github.c4software.gliff.domain.input

import io.github.c4software.gliff.proto.Axis
import io.github.c4software.gliff.proto.ClientMsg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputTest {
    private val sent = ArrayList<ClientMsg>()

    /** A 500x1000 picture of a 1000x2000 remote, 10 px of slop. */
    private val pad = Trackpad(10f) { sent.add(it) }.apply {
        direct = false
        layout(0, 0, 500, 1000, 1000.0, 2000.0)
    }

    /** The same picture, 100 px down the touch area, touched directly. */
    private val screen = Trackpad(10f) { sent.add(it) }.apply { layout(0, 100, 500, 1000, 1000.0, 2000.0) }
    private val keys = Keyboard { sent.add(it) }

    private fun take(): List<ClientMsg> = sent.toList().also { sent.clear() }

    private fun click(button: Int) = listOf(ClientMsg.PointerButton(button, true), ClientMsg.PointerButton(button, false))

    @Test
    fun aTapClicksWhereThePointerIs() {
        pad.down(1, 40f, 40f, 0)
        pad.move(1, 43f, 41f)
        pad.up(1, 120)
        assertEquals(listOf(ClientMsg.PointerMotion(500.0, 1000.0)) + click(Keys.BTN_LEFT), take())
    }

    @Test
    fun aFingerMovesThePointerByItsTravel() {
        pad.down(1, 100f, 100f, 0)
        // Leaving the slop moves nothing.
        pad.move(1, 120f, 100f)
        assertEquals(emptyList(), take())
        pad.move(1, 170f, 150f)
        // 50 px of a 500 px picture is a tenth of the remote, times the gain.
        val motion = take().single() as ClientMsg.PointerMotion
        assertEquals(500.0 + 100 * Trackpad.POINTER_GAIN, motion.x, 1e-6)
        assertEquals(1000.0 + 100 * Trackpad.POINTER_GAIN, motion.y, 1e-6)
        pad.up(1, 2000)
        assertEquals(emptyList(), take())
        // The pointer stops at the picture's edge.
        pad.down(1, 0f, 0f, 3000)
        pad.move(1, 20f, 0f)
        pad.move(1, 5000f, -5000f)
        assertEquals(listOf<ClientMsg>(ClientMsg.PointerMotion(1000.0, 0.0)), take())
        assertEquals(1.0 to 0.0, pad.x to pad.y)
    }

    @Test
    fun twoFingersTapForTheRightButtonAndThreeForTheMiddle() {
        pad.down(1, 10f, 10f, 0)
        pad.down(2, 90f, 10f, 20)
        pad.up(1, 100)
        pad.up(2, 110)
        assertEquals(click(Keys.BTN_RIGHT), take().drop(1))
        pad.down(1, 10f, 10f, 1000)
        pad.down(2, 90f, 10f, 1010)
        pad.down(3, 170f, 10f, 1020)
        pad.up(3, 1100)
        pad.up(2, 1100)
        pad.up(1, 1100)
        assertEquals(click(Keys.BTN_MIDDLE), take().drop(1))
    }

    @Test
    fun twoFingersScrollAndSayWhenTheyStop() {
        pad.down(1, 100f, 500f, 0)
        pad.down(2, 200f, 500f, 10)
        pad.move(1, 100f, 480f)
        pad.move(1, 100f, 440f)
        pad.move(2, 200f, 460f)
        // Fingers going up pull the content up: the view scrolls down.
        assertEquals(
            listOf<ClientMsg>(
                ClientMsg.PointerAxis(Axis.Vertical, 40.0, null, false),
                ClientMsg.PointerAxis(Axis.Vertical, 40.0, null, false),
            ),
            take(),
        )
        pad.up(1, 900)
        assertEquals(emptyList(), take())
        pad.up(2, 910)
        assertEquals(listOf<ClientMsg>(ClientMsg.PointerAxis(Axis.Vertical, 0.0, null, true)), take())
    }

    @Test
    fun aHeldFingerDrags() {
        pad.down(1, 100f, 100f, 0)
        assertTrue(pad.awaitingLongPress)
        assertTrue(pad.longPress())
        assertFalse(pad.awaitingLongPress)
        assertEquals(listOf(ClientMsg.PointerMotion(500.0, 1000.0), ClientMsg.PointerButton(Keys.BTN_LEFT, true)), take())
        pad.move(1, 150f, 100f)
        val motion = take().single() as ClientMsg.PointerMotion
        assertEquals(500.0 + 100 * Trackpad.POINTER_GAIN, motion.x, 1e-6)
        assertEquals(1000.0, motion.y, 1e-6)
        pad.up(1, 5000)
        assertEquals(listOf<ClientMsg>(ClientMsg.PointerButton(Keys.BTN_LEFT, false)), take())
    }

    @Test
    fun aMovingFingerOrASecondOneIsNoLongPress() {
        pad.down(1, 100f, 100f, 0)
        pad.move(1, 150f, 100f)
        assertFalse(pad.longPress())
        pad.up(1, 600)
        pad.down(1, 100f, 100f, 1000)
        pad.down(2, 200f, 100f, 1010)
        assertFalse(pad.longPress())
        pad.cancel()
        // A slow tap is not a click either.
        pad.down(1, 100f, 100f, 2000)
        pad.up(1, 2000 + Trackpad.TAP_MS)
        assertEquals(emptyList(), take().filterIsInstance<ClientMsg.PointerButton>())
    }

    @Test
    fun aCancelledDragLetsGo() {
        pad.down(1, 100f, 100f, 0)
        pad.longPress()
        take()
        pad.cancel()
        assertEquals(listOf<ClientMsg>(ClientMsg.PointerButton(Keys.BTN_LEFT, false)), take())
    }

    @Test
    fun aDirectTapClicksWhereItLands() {
        screen.down(1, 125f, 350f, 0)
        screen.move(1, 127f, 352f)
        screen.up(1, 100)
        assertEquals(listOf(ClientMsg.PointerMotion(250.0, 500.0)) + click(Keys.BTN_LEFT), take())
        // Two fingers: the right button, under the first.
        screen.down(1, 250f, 600f, 1000)
        screen.down(2, 400f, 600f, 1010)
        screen.up(2, 1100)
        screen.up(1, 1110)
        assertEquals(listOf(ClientMsg.PointerMotion(500.0, 1000.0)) + click(Keys.BTN_RIGHT), take())
        // Outside the picture is its nearest edge.
        screen.down(1, 600f, 20f, 2000)
        screen.up(1, 2050)
        assertEquals(listOf(ClientMsg.PointerMotion(1000.0, 0.0)) + click(Keys.BTN_LEFT), take())
    }

    @Test
    fun aDirectFingerTakesThePointerAlongAndDragsWhenHeld() {
        screen.down(1, 100f, 200f, 0)
        screen.move(1, 150f, 300f)
        assertEquals(listOf<ClientMsg>(ClientMsg.PointerMotion(300.0, 400.0)), take())
        screen.up(1, 900)
        assertEquals(emptyList(), take())
        screen.down(1, 250f, 600f, 2000)
        assertTrue(screen.longPress())
        screen.move(1, 300f, 600f)
        screen.up(1, 4000)
        assertEquals(
            listOf(
                ClientMsg.PointerMotion(500.0, 1000.0), ClientMsg.PointerButton(Keys.BTN_LEFT, true),
                ClientMsg.PointerMotion(600.0, 1000.0), ClientMsg.PointerButton(Keys.BTN_LEFT, false),
            ),
            take(),
        )
    }

    @Test
    fun aMouseIsWhereItIsWithItsButtonsAndWheel() {
        screen.mouseMove(250f, 600f)
        screen.mouseMove(250f, 600f)
        screen.mouseButtons(left = true, right = false, middle = false)
        screen.mouseButtons(left = true, right = false, middle = false)
        screen.mouseMove(300f, 600f)
        screen.mouseButtons(left = false, right = true, middle = false)
        screen.mouseScroll(0f, 1f)
        screen.mouseScroll(0f, -2f)
        assertEquals(
            listOf(
                ClientMsg.PointerMotion(500.0, 1000.0),
                ClientMsg.PointerButton(Keys.BTN_LEFT, true),
                ClientMsg.PointerMotion(600.0, 1000.0),
                ClientMsg.PointerButton(Keys.BTN_LEFT, false),
                ClientMsg.PointerButton(Keys.BTN_RIGHT, true),
                ClientMsg.PointerAxis(Axis.Vertical, 15.0, 1, false),
                ClientMsg.PointerAxis(Axis.Vertical, -30.0, -1, false),
            ),
            take(),
        )
        // Losing the window lets go of what is held.
        screen.cancel()
        assertEquals(listOf<ClientMsg>(ClientMsg.PointerButton(Keys.BTN_RIGHT, false)), take())
    }

    @Test
    fun aBrowserDrivesThePointerAndTypes() {
        val browser = BrowserInput { sent.add(it) }
        browser.apply("m 0.25 0.5\nb 0 1\nb 0 0\nb 2 1\nw 0 1.5\nw -1 0", 1000.0, 2000.0)
        assertEquals(
            listOf(
                ClientMsg.PointerMotion(250.0, 1000.0),
                ClientMsg.PointerButton(Keys.BTN_LEFT, true), ClientMsg.PointerButton(Keys.BTN_LEFT, false),
                ClientMsg.PointerButton(Keys.BTN_RIGHT, true),
                ClientMsg.PointerAxis(Axis.Vertical, 22.5, 1, false),
                ClientMsg.PointerAxis(Axis.Horizontal, -15.0, -1, false),
            ),
            take(),
        )
        // Characters as the browser's layout made them; Ctrl+C; Shift+Left; a percent-encoded space and pipe.
        browser.apply("t 0 a\nt 1 c\ns 8 ArrowLeft\nt 0 %20\nt 0 %7C\ns 0 Enter", 1000.0, 2000.0)
        assertEquals(
            key(30) +
                listOf(ClientMsg.Key(Keys.LEFT_CTRL, true)) + key(46) + ClientMsg.Key(Keys.LEFT_CTRL, false) +
                listOf(ClientMsg.Key(Keys.LEFT_SHIFT, true)) + key(Keys.LEFT) + ClientMsg.Key(Keys.LEFT_SHIFT, false) +
                key(Keys.SPACE) + shifted(43) + key(Keys.ENTER),
            take(),
        )
    }

    @Test
    fun aBrowserCannotSendAnythingElse() {
        val browser = BrowserInput { sent.add(it) }
        browser.apply(
            "m 9 -3\nm x y\nm NaN 1\nb 7 1\nb\nw 1e9 0\ns 0 PowerOff\nt z a\nk 1 1\n\nt 0 %\nt 0 %zz", 100.0, 100.0,
        )
        assertEquals(
            listOf<ClientMsg>(
                // Outside the picture is its edge; a runaway wheel is capped.
                ClientMsg.PointerMotion(100.0, 0.0),
                ClientMsg.PointerAxis(Axis.Horizontal, 300.0, 1, false),
            ) + shifted(6) + shifted(6) + key(44) + key(44),
            take(),
        )
    }

    @Test
    fun twoFingersPinchToMagnifyAndThenMoveThePicture() {
        val seen = ArrayList<List<Float>>()
        screen.onMagnify = { factor, dx, dy, cx, cy -> seen.add(listOf(factor, dx, dy, cx, cy)) }
        // Apart: 200 px between them, then 300.
        screen.down(1, 200f, 500f, 0)
        screen.down(2, 400f, 500f, 10)
        screen.move(1, 150f, 500f)
        screen.move(2, 450f, 500f)
        screen.up(1, 900)
        screen.up(2, 910)
        // Each finger's move grows the picture around the point between them.
        assertEquals(listOf(listOf(250f / 200f, -25f, 0f, 300f, 500f), listOf(300f / 250f, 25f, 0f, 275f, 500f)), seen)
        // Nothing of it reaches the remote, not even the right click of two fingers.
        assertEquals(emptyList(), take())

        // Moving together still scrolls while the picture is not magnified...
        seen.clear()
        screen.down(1, 200f, 500f, 2000)
        screen.down(2, 400f, 500f, 2010)
        screen.move(1, 200f, 440f)
        screen.move(2, 400f, 440f)
        screen.up(1, 2900)
        screen.up(2, 2910)
        assertEquals(emptyList(), seen)
        assertEquals(
            listOf<ClientMsg>(
                ClientMsg.PointerAxis(Axis.Vertical, 60.0, null, false),
                ClientMsg.PointerAxis(Axis.Vertical, 60.0, null, false),
                ClientMsg.PointerAxis(Axis.Vertical, 0.0, null, true),
            ),
            take(),
        )

        // ...and moves the picture once it is.
        screen.magnified = true
        screen.down(1, 200f, 500f, 4000)
        screen.down(2, 400f, 500f, 4010)
        screen.move(1, 200f, 440f)
        screen.move(2, 400f, 440f)
        screen.up(1, 4900)
        screen.up(2, 4910)
        assertEquals(listOf(listOf(0f, -30f), listOf(0f, -30f)), seen.map { it.subList(1, 3) })
        // One finger then the other: the distance wobbles between the two, and ends as it began.
        assertEquals(1f, seen[0][0] * seen[1][0], 1e-4f)
        assertEquals(emptyList(), take())
    }

    private fun key(code: Int) = listOf(ClientMsg.Key(code, true), ClientMsg.Key(code, false))

    private fun shifted(code: Int) =
        listOf(ClientMsg.Key(Keys.LEFT_SHIFT, true)) + key(code) + ClientMsg.Key(Keys.LEFT_SHIFT, false)

    @Test
    fun textIsTypedOnAUsLayout() {
        assertEquals(0, keys.type("ls -la | Less\n"))
        assertEquals(
            key(38) + key(31) + key(57) + key(12) + key(38) + key(30) + key(57) + shifted(43) + key(57) +
                shifted(38) + key(18) + key(31) + key(31) + key(28),
            take(),
        )
        assertEquals(0, keys.type("1!0)=+[{;:'\"`~\\,<.>/?zZmM"))
        assertEquals(
            key(2) + shifted(2) + key(11) + shifted(11) + key(13) + shifted(13) + key(26) + shifted(26) +
                key(39) + shifted(39) + key(40) + shifted(40) + key(41) + shifted(41) + key(43) +
                key(51) + shifted(51) + key(52) + shifted(52) + key(53) + shifted(53) +
                key(44) + shifted(44) + key(50) + shifted(50),
            take(),
        )
    }

    @Test
    fun whatTheLayoutLacksIsCountedNotGuessed() {
        assertEquals(2, keys.type("é€a"))
        assertEquals(key(30), take())
        // A phone keyboard's curly apostrophe is the plain one.
        assertEquals(0, keys.type("’"))
        assertEquals(key(40), take())
    }

    @Test
    fun modifiersWrapEachKey() {
        keys.type("c", listOf(Keys.LEFT_CTRL))
        assertEquals(
            listOf(ClientMsg.Key(Keys.LEFT_CTRL, true)) + key(46) + ClientMsg.Key(Keys.LEFT_CTRL, false),
            take(),
        )
        keys.tap(Keys.DELETE, listOf(Keys.LEFT_CTRL, Keys.LEFT_ALT))
        assertEquals(
            listOf(ClientMsg.Key(Keys.LEFT_CTRL, true), ClientMsg.Key(Keys.LEFT_ALT, true)) + key(Keys.DELETE) +
                listOf(ClientMsg.Key(Keys.LEFT_ALT, false), ClientMsg.Key(Keys.LEFT_CTRL, false)),
            take(),
        )
    }
}
