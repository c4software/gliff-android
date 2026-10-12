package io.github.c4software.gliff.proto

/** Any failure of a gliff connection. */
open class GliffException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A message that does not follow the protocol; the connection cannot go on. */
open class ProtocolException(message: String) : GliffException(message)

/** Malformed CBOR, or a value of the wrong type or range. */
class CborException(message: String) : ProtocolException(message)

/**
 * An enum value this build does not know. Fails the message that carries it,
 * except where the decoder reads it leniently ([CborReader.knownList],
 * [CborReader.optionalEnum]).
 */
class UnknownVariantException(val value: Long) : ProtocolException("unknown variant $value")

/**
 * The subset of CBOR gliff uses, written the way minicbor writes it: integers
 * in their shortest form, floats at their declared width, definite lengths.
 */
class CborWriter {
    private val out = ByteBuilder()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value in 0..23 -> out.write(m or value.toInt())
            value in 0..0xff -> {
                out.write(m or 24)
                out.write(value.toInt())
            }
            value in 0..0xffff -> {
                out.write(m or 25)
                be(value, 2)
            }
            value in 0..0xffff_ffffL -> {
                out.write(m or 26)
                be(value, 4)
            }
            // Negative here means the top bit of an unsigned 64-bit value.
            else -> {
                out.write(m or 27)
                be(value, 8)
            }
        }
    }

    private fun be(value: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write((value ushr (8 * i)).toInt() and 0xff)
    }

    /** An unsigned integer; `value` holds the raw 64 bits. */
    fun uint(value: Long) = head(0, value)

    /** A `u32` kept in an `Int` as raw bits. */
    fun u32(value: Int) = head(0, value.toLong() and 0xffff_ffffL)

    fun int(value: Long) = if (value >= 0) head(0, value) else head(1, -1 - value)

    fun bool(value: Boolean) = out.write(if (value) 0xf5 else 0xf4)

    fun nil() = out.write(0xf6)

    fun f32(value: Float) {
        out.write(0xfa)
        be(value.toRawBits().toLong() and 0xffff_ffffL, 4)
    }

    fun f64(value: Double) {
        out.write(0xfb)
        be(value.toRawBits(), 8)
    }

    fun text(value: String) {
        val bytes = value.encodeToByteArray()
        head(3, bytes.size.toLong())
        out.write(bytes)
    }

    fun bytes(value: ByteArray) {
        head(2, value.size.toLong())
        out.write(value)
    }

    fun array(len: Int) = head(4, len.toLong())

    fun <T> list(items: List<T>, item: CborWriter.(T) -> Unit) {
        array(items.size)
        for (i in items) item(i)
    }
}

class CborReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    val position: Int get() = pos

    private fun fail(what: String): Nothing = throw CborException("$what at byte $pos")

    private fun peek(): Int {
        if (pos >= end) fail("unexpected end of message")
        return buf[pos].toInt() and 0xff
    }

    private fun byte(): Int = peek().also { pos++ }

    private fun be(bytes: Int): Long {
        if (end - pos < bytes) fail("unexpected end of message")
        var v = 0L
        repeat(bytes) { v = (v shl 8) or (buf[pos++].toLong() and 0xff) }
        return v
    }

    /** The argument of a head byte; -1 for an indefinite length. */
    private fun argument(info: Int): Long = when (info) {
        in 0..23 -> info.toLong()
        24 -> be(1)
        25 -> be(2)
        26 -> be(4)
        27 -> be(8)
        31 -> -1
        else -> fail("reserved additional info $info")
    }

    private fun length(info: Int): Int {
        val n = argument(info)
        if (n < 0 || n > end - pos) fail("length $n exceeds the message")
        return n.toInt()
    }

    val atNull: Boolean get() = peek() == 0xf6 || peek() == 0xf7
    val atBreak: Boolean get() = peek() == 0xff

    /** An unsigned integer as its raw 64 bits. */
    fun u64(): Long {
        val b = byte()
        if (b shr 5 != 0) {
            pos--
            fail("expected an unsigned integer")
        }
        if ((b and 31) == 31) fail("expected an unsigned integer")
        return argument(b and 31)
    }

    private fun unsigned(max: Long, what: String): Long {
        val v = u64()
        if (v < 0 || v > max) fail("$what out of range")
        return v
    }

    /** A `u32` as raw bits in an `Int`. */
    fun u32(): Int = unsigned(0xffff_ffffL, "u32").toInt()

    fun u16(): Int = unsigned(0xffff, "u16").toInt()

    fun i32(): Int {
        val b = peek()
        val v = when (b shr 5) {
            0 -> unsigned(Int.MAX_VALUE.toLong(), "i32")
            1 -> {
                pos++
                val n = argument(b and 31)
                if ((b and 31) == 31 || n < 0 || n > Int.MAX_VALUE) fail("i32 out of range")
                -1 - n
            }
            else -> fail("expected an integer")
        }
        return v.toInt()
    }

    fun bool(): Boolean = when (byte()) {
        0xf4 -> false
        0xf5 -> true
        else -> {
            pos--
            fail("expected a bool")
        }
    }

    fun f64(): Double = when (byte()) {
        0xf9 -> halfToFloat(be(2).toInt()).toDouble()
        0xfa -> Float.fromBits(be(4).toInt()).toDouble()
        0xfb -> Double.fromBits(be(8))
        else -> {
            pos--
            fail("expected a float")
        }
    }

    fun f32(): Float = when (byte()) {
        0xf9 -> halfToFloat(be(2).toInt())
        0xfa -> Float.fromBits(be(4).toInt())
        else -> {
            pos--
            fail("expected an f32")
        }
    }

    fun text(): String = chunks(3, "a text string").decodeToString()

    fun bytes(): ByteArray = chunks(2, "a byte string")

    private fun chunks(major: Int, what: String): ByteArray {
        val b = byte()
        if (b shr 5 != major) {
            pos--
            fail("expected $what")
        }
        if ((b and 31) != 31) {
            val n = length(b and 31)
            return buf.copyOfRange(pos, pos + n).also { pos += n }
        }
        val out = ByteBuilder()
        while (!atBreak) {
            val c = byte()
            if (c shr 5 != major || (c and 31) == 31) fail("bad chunk in $what")
            val n = length(c and 31)
            out.write(buf, pos, n)
            pos += n
        }
        pos++
        return out.toByteArray()
    }

    /** Enter an array; returns its length, or -1 when indefinite. */
    fun array(): Int {
        val b = byte()
        if (b shr 5 != 4) {
            pos--
            fail("expected an array")
        }
        val n = argument(b and 31)
        if (n > end - pos) fail("array length $n exceeds the message")
        return n.toInt()
    }

    /** Skip one complete value, whatever it is. */
    fun skip() = skip(0)

    private fun skip(depth: Int) {
        if (depth > MAX_DEPTH) fail("nesting too deep")
        val b = byte()
        val major = b shr 5
        val info = b and 31
        when (major) {
            0, 1 -> if (info == 31) fail("bad integer") else argument(info)
            2, 3 -> if (info == 31) {
                while (!atBreak) {
                    val c = byte()
                    if (c shr 5 != major || (c and 31) == 31) fail("bad string chunk")
                    pos += length(c and 31)
                }
                pos++
            } else {
                pos += length(info)
            }
            4, 5 -> {
                val per = if (major == 5) 2 else 1
                val n = argument(info)
                if (n < 0) {
                    while (!atBreak) skip(depth + 1)
                    pos++
                } else {
                    if (n > (end - pos) / per) fail("container length $n exceeds the message")
                    repeat(n.toInt() * per) { skip(depth + 1) }
                }
            }
            6 -> {
                argument(info)
                skip(depth + 1)
            }
            else -> when (info) {
                in 0..23 -> {}
                24 -> be(1)
                25 -> be(2)
                26 -> be(4)
                27 -> be(8)
                else -> {
                    pos--
                    fail("unexpected break")
                }
            }
        }
    }

    /** The fields of a struct or variant, which travel as an array. */
    fun fields(): Fields = Fields(array())

    /**
     * Positional fields. A peer may send fewer (a field added since, absent
     * means `null`) or more (a field this build does not know, skipped by
     * [end]).
     */
    inner class Fields(private val len: Int) {
        private var read = 0

        private fun more(): Boolean = if (len < 0) !atBreak else read < len

        /** The next field, failing when the peer did not send it. */
        fun <T> req(name: String, item: () -> T): T {
            if (!more()) throw CborException("missing field $name")
            read++
            return item()
        }

        /** The next field, or `null` when it is absent or nil. */
        fun <T> opt(item: () -> T): T? {
            if (!more()) return null
            read++
            if (atNull) {
                skip()
                return null
            }
            return item()
        }

        fun end() {
            while (more()) {
                read++
                skip()
            }
            if (len < 0) pos++
        }
    }

    fun <T> list(item: () -> T): List<T> {
        val len = array()
        val out = ArrayList<T>(if (len < 0) 4 else minOf(len, 1024))
        var seen = 0
        while (if (len < 0) !atBreak else seen < len) {
            out.add(item())
            seen++
        }
        if (len < 0) pos++
        return out
    }

    /**
     * A list of enum values, dropping the ones this build does not know. Used
     * where a peer describes itself before any feature is agreed.
     */
    fun <T> knownList(item: () -> T): List<T> {
        val len = array()
        val out = ArrayList<T>()
        var seen = 0
        while (if (len < 0) !atBreak else seen < len) {
            val start = pos
            try {
                out.add(item())
            } catch (_: UnknownVariantException) {
                pos = start
                skip()
            }
            seen++
        }
        if (len < 0) pos++
        return out
    }

    /** An enum value, reading one this build does not know as `null`. */
    fun <T> optionalEnum(item: () -> T): T? {
        val start = pos
        return try {
            item()
        } catch (_: UnknownVariantException) {
            pos = start
            skip()
            null
        }
    }

    private companion object {
        const val MAX_DEPTH = 64
    }

    private fun halfToFloat(h: Int): Float {
        val sign = (h and 0x8000) shl 16
        val exp = (h shr 10) and 0x1f
        val frac = h and 0x3ff
        return when (exp) {
            0 -> (if (sign != 0) -1f else 1f) * frac / 16_777_216f
            31 -> Float.fromBits(sign or 0x7f80_0000 or (frac shl 13))
            else -> Float.fromBits(sign or ((exp + 112) shl 23) or (frac shl 13))
        }
    }
}

/** A growable byte buffer. */
internal class ByteBuilder(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    private var len = 0

    fun write(b: Int) {
        grow(1)
        buf[len++] = b.toByte()
    }

    fun write(src: ByteArray, offset: Int = 0, length: Int = src.size) {
        grow(length)
        src.copyInto(buf, len, offset, offset + length)
        len += length
    }

    private fun grow(more: Int) {
        if (len + more > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + more))
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)
}
