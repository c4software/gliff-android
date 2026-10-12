package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.Axis
import io.github.c4software.gliff.proto.ClientMsg
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/**
 * The remote pointer, driven by fingers or by a mouse.
 *
 * Fingers work in one of two ways. With [direct] on, the screen is the
 * remote screen: a tap clicks where it lands and a moving finger takes the
 * pointer along. With it off, the screen is a laptop's trackpad: a finger
 * moves the pointer by how far it travels, wherever it lands, and a tap
 * clicks where the pointer already is.
 *
 * Either way a tap with two fingers is the right button and with three the
 * middle one, and a finger held still takes hold of the left button and
 * drags until it lifts. Two fingers moving together scroll the remote; moving
 * apart or closer they magnify the picture on this screen, and once it is
 * magnified they move it around instead of scrolling.
 *
 * A mouse is simpler: the pointer is where it is, with its own buttons and
 * wheel.
 *
 * The pointer's place is kept here, as a fraction of the picture, because
 * the server takes absolute positions: the screen that draws the pointer
 * reads [x] and [y] after each event.
 */
class Trackpad(
    /** How far, in pixels, a finger may wander and still be tapping. */
    private val slop: Float,
    private val send: (ClientMsg) -> Unit,
) {
    var x = 0.5
        private set
    var y = 0.5
        private set

    /** Fingers point at the remote screen itself, not at a trackpad. */
    var direct = true

    /** The picture is magnified: two fingers move it rather than scroll the remote. */
    var magnified = false

    /**
     * Two fingers magnify or move the picture: grown by `factor` around
     * `cx, cy`, which moved by `dx, dy`. Nothing of it is sent to the remote.
     */
    var onMagnify: ((factor: Float, dx: Float, dy: Float, cx: Float, cy: Float) -> Unit)? = null

    /** True from a first finger landing until it has to be a move or a hold. */
    val awaitingLongPress: Boolean
        get() = fingers.size == 1 && most == 1 && !moved && !dragging

    private var pictureX = 0
    private var pictureY = 0
    private var pictureWidth = 0
    private var pictureHeight = 0
    private var logicalWidth = 0.0
    private var logicalHeight = 0.0

    private class Finger(val startX: Float, val startY: Float, var x: Float, var y: Float)

    private val fingers = HashMap<Long, Finger>()
    private var downAt = 0L
    private var firstX = 0f
    private var firstY = 0f
    private var most = 0
    private var moved = false
    private var dragging = false
    /** What two moving fingers are doing, settled when they leave the slop. */
    private var pinching = false
    private var startDistance = 0f
    private var startCentreX = 0f
    private var startCentreY = 0f
    private var scrolledVertical = false
    private var scrolledHorizontal = false

    /** The server has been told where the pointer is. */
    private var told = false

    /** Mouse buttons held, by evdev code. */
    private val mouseHeld = HashSet<Int>()

    /**
     * Where the picture is in the touch area, and the remote's size in its
     * logical pixels.
     */
    fun layout(
        pictureX: Int,
        pictureY: Int,
        pictureWidth: Int,
        pictureHeight: Int,
        logicalWidth: Double,
        logicalHeight: Double,
    ) {
        this.pictureX = pictureX
        this.pictureY = pictureY
        this.pictureWidth = pictureWidth
        this.pictureHeight = pictureHeight
        val resized = logicalWidth != this.logicalWidth || logicalHeight != this.logicalHeight
        val known = this.logicalWidth > 0
        this.logicalWidth = logicalWidth
        this.logicalHeight = logicalHeight
        // The remote kept the pointer at its old pixels; put it back at the same place of the picture.
        if (resized && known) sendMotion()
    }

    fun down(id: Long, x: Float, y: Float, timeMs: Long) {
        if (fingers.isEmpty()) {
            downAt = timeMs
            firstX = x
            firstY = y
            most = 0
            moved = false
        }
        fingers[id] = Finger(x, y, x, y)
        most = max(most, fingers.size)
        if (fingers.size == 2) {
            val (a, b) = fingers.values.toList()
            startDistance = hypot(a.x - b.x, a.y - b.y)
            startCentreX = (a.x + b.x) / 2
            startCentreY = (a.y + b.y) / 2
            pinching = false
        }
    }

    fun move(id: Long, x: Float, y: Float) {
        val finger = fingers[id] ?: return
        val dx = x - finger.x
        val dy = y - finger.y
        // The other of two fingers, and how far apart they were.
        val other = if (fingers.size == 2) fingers.values.first { it !== finger } else null
        val distanceBefore = other?.let { hypot(finger.x - it.x, finger.y - it.y) } ?: 0f
        finger.x = x
        finger.y = y
        if (!moved && !dragging) {
            if (max(abs(x - finger.startX), abs(y - finger.startY)) <= slop) return
            moved = true
            if (other != null) {
                // Fingers that changed their distance more than they travelled are pinching.
                val spread = abs(hypot(x - other.x, y - other.y) - startDistance)
                val travel = hypot((x + other.x) / 2 - startCentreX, (y + other.y) / 2 - startCentreY)
                pinching = onMagnify != null && (magnified || spread > travel)
            }
            // On a trackpad the way out of the slop is not travel: the pointer must not jump.
            if (!direct) return
        }
        if (pictureWidth <= 0 || pictureHeight <= 0) return
        if (most == 1) {
            if (direct) {
                pointAt(x, y)
            } else {
                this.x = (this.x + dx * POINTER_GAIN / pictureWidth).coerceIn(0.0, 1.0)
                this.y = (this.y + dy * POINTER_GAIN / pictureHeight).coerceIn(0.0, 1.0)
            }
            sendMotion()
        } else if (other != null && pinching) {
            val distance = hypot(x - other.x, y - other.y)
            val factor = if (distanceBefore > 1f && distance > 1f) distance / distanceBefore else 1f
            // The point between the fingers moved by half of what this one did.
            onMagnify?.invoke(factor, dx / 2, dy / 2, (x - dx + other.x) / 2, (y - dy + other.y) / 2)
        } else if (fingers.size >= 2) {
            // Each finger carries its share; the content follows the fingers.
            val vertical = -dy / fingers.size * logicalHeight / pictureHeight
            val horizontal = -dx / fingers.size * logicalWidth / pictureWidth
            if (vertical != 0.0) {
                scrolledVertical = true
                send(ClientMsg.PointerAxis(Axis.Vertical, vertical, null, false))
            }
            if (horizontal != 0.0) {
                scrolledHorizontal = true
                send(ClientMsg.PointerAxis(Axis.Horizontal, horizontal, null, false))
            }
        }
    }

    /** The finger has been still for [LONG_PRESS_MS]. True when it now holds the button. */
    fun longPress(): Boolean {
        if (!awaitingLongPress) return false
        dragging = true
        if (direct) pointAt(firstX, firstY)
        sendMotion()
        send(ClientMsg.PointerButton(Keys.BTN_LEFT, true))
        return true
    }

    fun up(id: Long, timeMs: Long) {
        if (fingers.remove(id) == null || fingers.isNotEmpty()) return
        if (dragging) {
            send(ClientMsg.PointerButton(Keys.BTN_LEFT, false))
        } else if (!moved && timeMs - downAt < TAP_MS) {
            val button = when (most) {
                1 -> Keys.BTN_LEFT
                2 -> Keys.BTN_RIGHT
                else -> Keys.BTN_MIDDLE
            }
            // Under the first finger; on a trackpad, where the pointer is,
            // which a fresh session has not been told yet.
            if (direct) pointAt(firstX, firstY)
            sendMotion()
            send(ClientMsg.PointerButton(button, true))
            send(ClientMsg.PointerButton(button, false))
        }
        endScroll()
        dragging = false
    }

    /** The gesture was taken away (the window lost the touch): let go of everything. */
    fun cancel() {
        mouseHeld.toList().forEach { send(ClientMsg.PointerButton(it, false)) }
        mouseHeld.clear()
        if (fingers.isEmpty()) return
        fingers.clear()
        if (dragging) send(ClientMsg.PointerButton(Keys.BTN_LEFT, false))
        endScroll()
        dragging = false
    }

    /** The mouse is at `x, y` of the touch area. */
    fun mouseMove(x: Float, y: Float) {
        if (pictureWidth <= 0 || pictureHeight <= 0) return
        val oldX = this.x
        val oldY = this.y
        pointAt(x, y)
        if (this.x != oldX || this.y != oldY || !told) sendMotion()
    }

    /** Which mouse buttons are down now; only the changes are sent. */
    fun mouseButtons(left: Boolean, right: Boolean, middle: Boolean) {
        for ((button, pressed) in listOf(Keys.BTN_LEFT to left, Keys.BTN_RIGHT to right, Keys.BTN_MIDDLE to middle)) {
            if (pressed == (button in mouseHeld)) continue
            if (pressed) mouseHeld.add(button) else mouseHeld.remove(button)
            send(ClientMsg.PointerButton(button, pressed))
        }
    }

    /** The wheel turned by `dx, dy` notches, positive down and right. */
    fun mouseScroll(dx: Float, dy: Float) {
        // What the reference client sends for a notch.
        if (dy != 0f) send(ClientMsg.PointerAxis(Axis.Vertical, dy * WHEEL_NOTCH, sign(dy).toInt(), false))
        if (dx != 0f) send(ClientMsg.PointerAxis(Axis.Horizontal, dx * WHEEL_NOTCH, sign(dx).toInt(), false))
    }

    /** Put the pointer under a point of the touch area, or at the picture's nearest edge. */
    private fun pointAt(x: Float, y: Float) {
        if (pictureWidth <= 0 || pictureHeight <= 0) return
        this.x = ((x - pictureX).toDouble() / pictureWidth).coerceIn(0.0, 1.0)
        this.y = ((y - pictureY).toDouble() / pictureHeight).coerceIn(0.0, 1.0)
    }

    private fun endScroll() {
        if (scrolledVertical) send(ClientMsg.PointerAxis(Axis.Vertical, 0.0, null, true))
        if (scrolledHorizontal) send(ClientMsg.PointerAxis(Axis.Horizontal, 0.0, null, true))
        scrolledVertical = false
        scrolledHorizontal = false
    }

    private fun sendMotion() {
        if (logicalWidth <= 0 || logicalHeight <= 0) return
        told = true
        send(ClientMsg.PointerMotion(x * logicalWidth, y * logicalHeight))
    }

    companion object {
        const val TAP_MS = 300L
        const val LONG_PRESS_MS = 450L

        /** Pointer travel per finger travel, both measured on the screen. */
        const val POINTER_GAIN = 1.4

        /** Scroll distance of one wheel notch, as the reference client sends it. */
        const val WHEEL_NOTCH = 15.0
    }
}
