package io.github.c4software.gliff.proto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ported from the tests of `crates/gliff-transport/src/lib.rs`. */
class FrameTest {
    private val key = ClientMsg.Key(30, true)

    /** A source that hands out one byte per read, like a slow link. */
    private class Trickle(private val bytes: ByteArray) : ByteSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos == bytes.size) return -1
            dst[offset] = bytes[pos++]
            return 1
        }
    }

    private class Collect : ByteSink {
        val bytes = ByteBuilder()
        var size = 0
        override fun write(src: ByteArray, offset: Int, length: Int) {
            bytes.write(src, offset, length)
            size += length
        }
        override fun flush() {}
    }

    private fun frames(write: FrameWriter.() -> Unit): ByteArray =
        Collect().also { FrameWriter(it).write() }.bytes.toByteArray()

    private fun clientReader(bytes: ByteArray) = FrameReader(Trickle(bytes), ClientMsg::decode)

    /** `[99, [data_len]]`: a message this build has no variant for. */
    private fun future(dataLen: Long): ByteArray =
        CborWriter().apply { array(2); uint(99); array(1); uint(dataLen) }.toByteArray()

    @Test
    fun frameLayoutIsStable() {
        val bytes = frames { write(key, "ab".encodeToByteArray()) }
        assertEquals("0600000002000000820282181ef56162", bytes.hex())
    }

    @Test
    fun roundTripsMessageAndPayloads() {
        val msg = ServerMsg.VideoFrame(1, 2, true, emptyList(), 5000, 100)
        val main = ByteArray(5000) { 7 }
        val aux = ByteArray(100) { 9 }
        val rd = FrameReader(Trickle(frames { write(msg, main, aux) }), ServerMsg::decode)
        assertEquals(msg, rd.readMsg())
        assertContentEquals(main, rd.readPayload(5000))
        val into = ByteArray(110)
        rd.readPayload(into, 10, 100)
        assertContentEquals(aux, into.copyOfRange(10, 110))
        assertFailsWith<EndOfStreamException> { rd.readMsg() }
    }

    @Test
    fun skipsAnUnknownMessageAndItsPayload() {
        val rd = clientReader(frames {
            write(future(40_000), ByteArray(40_000) { 1 })
            write(key)
        })
        assertEquals(key, rd.readMsg())
    }

    @Test
    fun discardsPayloadTheCallerLeavesUnread() {
        val chunk = ClientMsg.Clipboard(ClipboardMsg.Data(1, 0, 3, true))
        val rd = clientReader(frames {
            write(chunk, "abc".encodeToByteArray())
            write(key)
        })
        assertEquals(chunk, rd.readMsg())
        val e = assertFailsWith<PayloadOverrunException> { rd.readPayload(4) }
        assertEquals(4 to 3, e.asked to e.left)
        assertEquals(key, rd.readMsg())
    }

    @Test
    fun failsOnAnUnknownValueInsideAKnownMessage() {
        val axis = "82058407fb3ff0000000000000f6f4".unhex()
        val rd = clientReader(frames { write(axis) })
        assertFailsWith<UnknownVariantException> { rd.readMsg() }
    }

    @Test
    fun writerRefusesFramesTheReaderWouldReject() {
        val out = Collect()
        val wr = FrameWriter(out)
        assertFailsWith<BodyTooLargeException> { wr.write(ClientMsg.Keymap("x".repeat(Frame.MAX_BODY))) }
        assertFailsWith<PayloadTooLargeException> { wr.write(key, ByteArray(Frame.MAX_PAYLOAD + 1)) }
        assertTrue(out.size == 0)
    }

    @Test
    fun readerRefusesOversizedHeaders() {
        fun header(body: Long, payload: Long) = ByteArray(8).also {
            for (i in 0..3) {
                it[i] = (body ushr (8 * i)).toByte()
                it[4 + i] = (payload ushr (8 * i)).toByte()
            }
        }
        assertFailsWith<PayloadTooLargeException> {
            clientReader(header(6, Frame.MAX_FRAME_PAYLOAD + 1L)).readMsg()
        }
        assertFailsWith<BodyTooLargeException> { clientReader(header(Frame.MAX_BODY + 1L, 0)).readMsg() }
        // Lengths past the sign bit are large, not negative.
        assertFailsWith<BodyTooLargeException> { clientReader(header(0xffff_ffffL, 0)).readMsg() }
    }

    @Test
    fun aStreamCutMidFrameIsEndOfStream() {
        val bytes = frames { write(key, "ab".encodeToByteArray()) }
        for (cut in 0 until bytes.size - 2) {
            assertFailsWith<EndOfStreamException>("cut at $cut") { clientReader(bytes.copyOf(cut)).readMsg() }
        }
    }
}
