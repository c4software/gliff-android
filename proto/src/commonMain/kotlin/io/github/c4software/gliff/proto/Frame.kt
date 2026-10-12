package io.github.c4software.gliff.proto

/**
 * A blocking source of bytes: the read half of a connection. Platform code
 * adapts its own stream type to it.
 */
fun interface ByteSource {
    /**
     * Read up to `length` bytes into `dst` at `offset`, blocking until at
     * least one is there. Returns the count, or -1 at the end of the stream.
     */
    fun read(dst: ByteArray, offset: Int, length: Int): Int
}

/** A blocking sink of bytes: the write half of a connection. */
interface ByteSink {
    fun write(src: ByteArray, offset: Int, length: Int)
    fun flush()
}

/** The peer closed the stream. */
class EndOfStreamException : GliffException("connection closed")

/**
 * Wire framing, little-endian:
 * ```text
 * u32 body_len | u32 payload_len | body_len bytes of CBOR | payload_len bytes
 * ```
 * The payload is the video data, the cursor pixels or a clipboard chunk; the
 * message declares how it splits. The header covers it, so a message this
 * build does not know is skipped whole.
 */
object Frame {
    const val HEADER_LEN = 8

    /** Largest CBOR body either end sends or accepts. */
    const val MAX_BODY = 1 shl 20

    /** Largest single payload a message may declare (a keyframe). */
    const val MAX_PAYLOAD = 64 * 1024 * 1024

    /** Largest total payload of one frame: a keyframe's main and aux streams. */
    const val MAX_FRAME_PAYLOAD = 2 * MAX_PAYLOAD
}

class BodyTooLargeException(val size: Long) : ProtocolException("frame body of $size bytes exceeds the limit")

class PayloadTooLargeException(val size: Long) : ProtocolException("payload of $size bytes exceeds the limit")

class PayloadOverrunException(val asked: Int, val left: Int) :
    ProtocolException("read $asked payload bytes, but the frame has $left left")

/** Writes frames to a blocking stream. Not thread-safe: one writer owns it. */
class FrameWriter(private val out: ByteSink) {
    /** Write an encoded message body, then its payload slices in order. */
    fun write(body: ByteArray, vararg payloads: ByteArray) {
        if (body.size > Frame.MAX_BODY) throw BodyTooLargeException(body.size.toLong())
        payloads.firstOrNull { it.size > Frame.MAX_PAYLOAD }?.let { throw PayloadTooLargeException(it.size.toLong()) }
        val payloadLen = payloads.sumOf { it.size.toLong() }
        if (payloadLen > Frame.MAX_FRAME_PAYLOAD) throw PayloadTooLargeException(payloadLen)
        // Header and body leave in one write so a small message is one packet.
        val head = ByteArray(Frame.HEADER_LEN + body.size)
        le32(head, 0, body.size)
        le32(head, 4, payloadLen.toInt())
        body.copyInto(head, Frame.HEADER_LEN)
        out.write(head, 0, head.size)
        for (p in payloads) out.write(p, 0, p.size)
        out.flush()
    }

    fun write(msg: ClientMsg, vararg payloads: ByteArray) = write(msg.encode(), *payloads)

    fun write(msg: ServerMsg, vararg payloads: ByteArray) = write(msg.encode(), *payloads)

    private fun le32(dst: ByteArray, at: Int, v: Int) {
        for (i in 0..3) dst[at + i] = (v ushr (8 * i)).toByte()
    }
}

/**
 * Reads frames from a blocking stream. `decode` returns `null` for a message
 * this build does not know, which is skipped with its payload. Not
 * thread-safe: one reader owns it.
 */
class FrameReader<M : Any>(private val input: ByteSource, private val decode: (ByteArray) -> M?) {
    private val header = ByteArray(Frame.HEADER_LEN)

    /** Payload bytes of the last message the caller has not read yet. */
    private var payloadLeft = 0

    /**
     * Read one message. Its payload, if any, must then be read with
     * [readPayload] according to the lengths the message declares; payload
     * left unread is discarded before the next message.
     *
     * @throws EndOfStreamException when the peer closed the stream.
     */
    fun readMsg(): M {
        while (true) {
            discard(payloadLeft.toLong())
            payloadLeft = 0
            readFully(header, 0, Frame.HEADER_LEN)
            val bodyLen = le32(0)
            val payloadLen = le32(4)
            if (bodyLen > Frame.MAX_BODY) throw BodyTooLargeException(bodyLen)
            if (payloadLen > Frame.MAX_FRAME_PAYLOAD) throw PayloadTooLargeException(payloadLen)
            val body = ByteArray(bodyLen.toInt())
            readFully(body, 0, body.size)
            val msg = decode(body)
            if (msg != null) {
                payloadLeft = payloadLen.toInt()
                return msg
            }
            discard(payloadLen)
        }
    }

    /** Read exactly `len` payload bytes that follow the last message. */
    fun readPayload(len: Int): ByteArray {
        val bytes = ByteArray(checked(len))
        readFully(bytes, 0, len)
        payloadLeft -= len
        return bytes
    }

    /** Read `len` payload bytes into `dst` at `offset`. */
    fun readPayload(dst: ByteArray, offset: Int, len: Int) {
        checked(len)
        readFully(dst, offset, len)
        payloadLeft -= len
    }

    private fun checked(len: Int): Int {
        // A length past the sign bit is a u32 above the limit.
        if (len < 0 || len > Frame.MAX_PAYLOAD) throw PayloadTooLargeException(len.toLong() and 0xffff_ffffL)
        if (len > payloadLeft) throw PayloadOverrunException(len, payloadLeft)
        return len
    }

    private fun le32(at: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (header[at + i].toLong() and 0xff)
        return v
    }

    private fun readFully(dst: ByteArray, offset: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = input.read(dst, offset + done, len - done)
            if (n < 0) throw EndOfStreamException()
            done += n
        }
    }

    private fun discard(count: Long) {
        var left = count
        if (left == 0L) return
        val scratch = ByteArray(minOf(left, 64 * 1024L).toInt())
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EndOfStreamException()
            left -= n
        }
    }
}
