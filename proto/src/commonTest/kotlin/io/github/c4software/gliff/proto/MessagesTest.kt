package io.github.c4software.gliff.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Ported from the tests of `crates/gliff-proto/src/msg.rs`. */
class MessagesTest {
    private val hello = ClientMsg.Hello(
        version = 1,
        keymap = "k",
        caps = ClientCaps(
            codecs = listOf(Codec.H264),
            maxWidth = 3840,
            maxHeight = 2160,
            chroma = listOf(ChromaMode.Dual420, ChromaMode.Single420),
            features = listOf("f"),
        ),
    )

    private val streamConfig = ServerMsg.StreamConfig(
        codec = Codec.H264,
        chroma = ChromaMode.Dual420,
        pipeline = VideoPipeline.Gpu,
        width = 1920,
        height = 1080,
        scaleMilli = 1000,
        extradata = ByteArray(0),
        auxExtradata = null,
        viewWidth = 1920,
        viewHeight = 1080,
        fpsCap = 60,
    )

    /** The vectors of `encodings_are_stable()`: these bytes are the protocol. */
    private val clientVectors: List<Pair<ClientMsg, String>> = listOf(
        hello to "82008301616b858101190f00190870820102816166",
        ClientMsg.Key(keycode = 30, pressed = true) to "820282181ef5",
        ClientMsg.PointerMotion(12.5, 7.25) to "820382fb4029000000000000fb401d000000000000",
        ClientMsg.FrameAck(frameId = 42, decodedAtMs = 1000) to "820682182a1903e8",
        ClientMsg.RequestKeyframe to "820780",
    )

    private val serverVectors: List<Pair<ServerMsg, String>> = listOf(
        ServerMsg.HelloAck(
            version = 1,
            session = SessionInfo(headless = true, output = "o"),
            outputs = listOf(OutputInfo("o", 1920, 1080, 1000)),
            features = emptyList(),
        ) to "8200840182f5616f8184616f1907801904381903e880",
        streamConfig to "82018b0101001907801904381903e840f6190780190438183c",
        ServerMsg.VideoFrame(
            frameId = 1,
            ptsUs = 2,
            keyframe = true,
            damage = listOf(Rect(0, 0, 10, 10)),
            dataLen = 100,
            auxLen = 50,
        ) to "8202860102f5818400000a0a18641832",
        ServerMsg.Error(ERROR_VERSION, "m") to "82098301616d01",
    )

    @Test
    fun encodingsAreStable() {
        for ((msg, hex) in clientVectors) assertEquals(hex, msg.encode().hex(), "$msg")
        for ((msg, hex) in serverVectors) assertEquals(hex, msg.encode().hex(), "$msg")
    }

    @Test
    fun stableEncodingsDecode() {
        for ((msg, hex) in clientVectors) assertEquals(msg, ClientMsg.decode(hex.unhex()), hex)
        for ((msg, hex) in serverVectors) assertEquals(msg, ServerMsg.decode(hex.unhex()), hex)
    }

    @Test
    fun roundTripsEveryMessage() {
        val clipboard = listOf(
            ClipboardMsg.Offer(4, listOf("image/png"), listOf(ClipboardFile("a/b.txt", 3, false))),
            ClipboardMsg.Offer(-1, emptyList(), emptyList()),
            ClipboardMsg.Request(7, 4, ClipboardItem.File(0)),
            ClipboardMsg.Request(7, 4, ClipboardItem.Mime("text/plain")),
            ClipboardMsg.Data(7, 10, 3, true),
            ClipboardMsg.Ack(7, 13),
            ClipboardMsg.Abort(7),
        )
        val client = listOf(
            hello,
            ClientMsg.Resize(1280, 720, 1.5f),
            ClientMsg.Key(30, true),
            ClientMsg.PointerMotion(-1.5, 1e9),
            ClientMsg.PointerButton(0x110, false),
            ClientMsg.PointerAxis(Axis.Vertical, 1.5, 1, false),
            ClientMsg.PointerAxis(Axis.Horizontal, -3.0, null, true),
            ClientMsg.PointerAxis(Axis.Vertical, 15.0, -1, false),
            ClientMsg.FrameAck(42, 1000),
            ClientMsg.FrameAck(-1, Long.MIN_VALUE),
            ClientMsg.RequestKeyframe,
            ClientMsg.Ping(1_700_000_000_000),
            ClientMsg.Bye,
            ClientMsg.Keymap("xkb"),
            ClientMsg.Pong(5),
        ) + clipboard.map { ClientMsg.Clipboard(it) }
        for (m in client) assertEquals(m, ClientMsg.decode(m.encode()), "$m")

        val server = serverVectors.map { it.first } + listOf(
            streamConfig.copy(pipeline = null, extradata = byteArrayOf(1, 2), auxExtradata = byteArrayOf(3)),
            ServerMsg.CursorShape(1, 24, 24, -2, 3, 2304),
            ServerMsg.CursorPos(10.5, 20.25, 1, true),
            ServerMsg.Pong(1, 2),
            ServerMsg.Error(300, "later", null),
            ServerMsg.Ping(9),
        ) + clipboard.map { ServerMsg.Clipboard(it) }
        for (m in server) assertEquals(m, ServerMsg.decode(m.encode()), "$m")
    }

    @Test
    fun wireShapesNotCoveredByTheReferenceVectors() {
        // f32 scale, an absent optional in the middle, and an empty variant.
        assertEquals("820183190500 1902d0 fa3fc00000".replace(" ", ""), ClientMsg.Resize(1280, 720, 1.5f).encode().hex())
        assertEquals(
            "82058400fb3ff8000000000000f6f4",
            ClientMsg.PointerAxis(Axis.Vertical, 1.5, null, false).encode().hex(),
        )
        assertEquals(
            "82058401fbc00800000000000020f5",
            ClientMsg.PointerAxis(Axis.Horizontal, -3.0, -1, true).encode().hex(),
        )
        assertEquals("820c80", ClientMsg.Bye.encode().hex())
        assertEquals("8210811903e8", ClientMsg.Pong(1000).encode().hex())
        // A trailing absent field is left out, not sent as null.
        assertEquals("82098201616d", ServerMsg.Error(1, "m", null).encode().hex())
    }

    /** A newer client's `Hello`: extra fields, and enum values this build does not know. */
    @Test
    fun helloFromANewerClientDecodes() {
        val w = CborWriter()
        w.array(2)
        w.uint(0)
        w.array(4)
        w.uint(1)
        w.text("k")
        w.array(6)
        w.list(listOf(9L, 1L)) { uint(it) }
        w.uint(3840)
        w.uint(2160)
        w.list(listOf(1L, 9L)) { uint(it) }
        w.list(listOf("future")) { text(it) }
        w.uint(120)
        w.text("us")
        assertEquals(
            ClientMsg.Hello(
                1,
                "k",
                ClientCaps(listOf(Codec.H264), 3840, 2160, listOf(ChromaMode.Dual420), listOf("future")),
            ),
            ClientMsg.decode(w.toByteArray()),
        )
    }

    /** A newer server's `StreamConfig`: an unknown pipeline and an extra field. */
    @Test
    fun streamConfigFromANewerServerDecodes() {
        val newer = "82018c0101091907801904381903e840f6190780190438183cf5".unhex()
        assertEquals(streamConfig.copy(pipeline = null), ServerMsg.decode(newer))
    }

    @Test
    fun anOptionalFieldMayBeAbsentOrNull() {
        // Error without its third field, as an older server sends it.
        assertEquals(ServerMsg.Error(1, "m", null), ServerMsg.decode("82098201616d".unhex()))
        assertEquals(ServerMsg.Error(1, "m", null), ServerMsg.decode("82098301616df6".unhex()))
    }

    @Test
    fun anUnknownMessageDecodesAsNull() {
        assertNull(ServerMsg.decode("8218638118 2a".replace(" ", "").unhex()))
        assertNull(ClientMsg.decode("82186380".unhex()))
    }

    @Test
    fun anUnknownEnumValueInsideAKnownMessageFails() {
        // PointerAxis with axis 7.
        assertFailsWith<UnknownVariantException> {
            ClientMsg.decode("82058407fb3ff0000000000000f6f4".unhex())
        }
        // StreamConfig with codec 9: required enums are not lenient.
        assertFailsWith<UnknownVariantException> {
            ServerMsg.decode("82018b0901001907801904381903e840f6190780190438183c".unhex())
        }
    }

    @Test
    fun malformedMessagesFail() {
        assertFailsWith<CborException> { ServerMsg.decode("820c80".unhex()) } // Ping without t
        assertFailsWith<CborException> { ServerMsg.decode("820c81".unhex()) } // truncated
        assertFailsWith<CborException> { ServerMsg.decode("820c816178".unhex()) } // t is text
        assertFailsWith<CborException> { ServerMsg.decode(ByteArray(0)) }
    }

    @Test
    fun knownReadsIndefiniteListsAndKeepsOtherErrors() {
        val r = CborReader("9f010902ff".unhex())
        assertEquals(listOf(Codec.H264, Codec.H265), r.knownList { Codec.decode(r) })
        val bad = CborReader("816178".unhex())
        assertFailsWith<CborException> { bad.knownList { Codec.decode(bad) } }
    }

    @Test
    fun indefiniteArraysDecodeLikeDefiniteOnes() {
        // [2, [_ 30, true]] and [_ 2, [30, true, "extra"]]
        assertEquals(ClientMsg.Key(30, true), ClientMsg.decode("82029f181ef5ff".unhex()))
        assertEquals(ClientMsg.Key(30, true), ClientMsg.decode("9f0283181ef5656578747261ff".unhex()))
    }

    @Test
    fun skipHandlesEveryShape() {
        // map, tag, nested indefinite containers, chunked strings, floats.
        val value = "a2616101bf6162c1fb0000000000000000ff9f5f4101ff7f6161ff82f9000080ff"
        val r = CborReader((value + "07").unhex())
        r.skip()
        assertEquals(7, r.u32())
    }

    @Test
    fun theLargestFileOfferFitsInAFrame() {
        val entries = 10_000
        val pathLen = 512 * 1024 / entries
        val offer = ClientMsg.Clipboard(
            ClipboardMsg.Offer(
                serial = -1,
                mimeTypes = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT"),
                files = (0 until entries).map { ClipboardFile(it.toString().padStart(pathLen, '0'), -1, false) },
            )
        )
        val bytes = offer.encode()
        assertTrue(bytes.size <= Frame.MAX_BODY)
        assertEquals(offer, ClientMsg.decode(bytes))
    }

    @Test
    fun incompatibleCodes() {
        assertTrue(isIncompatible(ERROR_VERSION))
        assertTrue(isIncompatible(255))
        assertTrue(!isIncompatible(0) && !isIncompatible(256))
    }
}
