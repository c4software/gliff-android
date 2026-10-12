package io.github.c4software.gliff.proto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bytes printed by the Rust reference (`minicbor::to_vec` over the types of
 * `gliff-proto`, vendored commit) for the messages its own
 * `encodings_are_stable()` does not cover. Regenerate with a small binary
 * that depends on `vendor/gliff/crates/gliff-proto`.
 */
class ReferenceVectorsTest {
    private val clipboard: List<Triple<ClipboardMsg, String, String>> = listOf(
        Triple(
            ClipboardMsg.Offer(4, listOf("image/png"), listOf(ClipboardFile("a/b.txt", 3, false))),
            "820883048169696d6167652f706e67818367612f622e74787403f4",
            "820583048169696d6167652f706e67818367612f622e74787403f4",
        ),
        Triple(ClipboardMsg.Offer(-1, emptyList(), emptyList()), "8208831affffffff8080", "8205831affffffff8080"),
        Triple(ClipboardMsg.Request(7, 4, ClipboardItem.File(0)), "820983070482018100", "820683070482018100"),
        Triple(
            ClipboardMsg.Request(7, 4, ClipboardItem.Mime("text/plain")),
            "82098307048200816a746578742f706c61696e",
            "82068307048200816a746578742f706c61696e",
        ),
        Triple(ClipboardMsg.Data(7, 10, 3, true), "820a84070a03f5", "820784070a03f5"),
        Triple(ClipboardMsg.Ack(7, 13), "820d82070d", "820a82070d"),
        Triple(ClipboardMsg.Abort(7), "820e8107", "820b8107"),
    )

    private val client: List<Pair<ClientMsg, String>> = listOf(
        ClientMsg.Resize(1280, 720, 1.5f) to "8201831905001902d0fa3fc00000",
        ClientMsg.PointerButton(0x110, false) to "820482190110f4",
        ClientMsg.PointerAxis(Axis.Vertical, 1.5, null, false) to "82058400fb3ff8000000000000f6f4",
        ClientMsg.PointerAxis(Axis.Horizontal, -3.0, -1, true) to "82058401fbc00800000000000020f5",
        ClientMsg.Ping(1_700_000_000_000) to "820b811b0000018bcfe56800",
        ClientMsg.Bye to "820c80",
        ClientMsg.Keymap("xkb") to "820f8163786b62",
        ClientMsg.Pong(1000) to "8210811903e8",
        ClientMsg.FrameAck(-1, Long.MIN_VALUE) to "8206821bffffffffffffffff1b8000000000000000",
    ) + clipboard.map { ClientMsg.Clipboard(it.first) to it.second }

    private val server: List<Pair<ServerMsg, String>> = listOf(
        ServerMsg.StreamConfig(
            Codec.H264, ChromaMode.Single420, null, 1920, 1080, 1500,
            byteArrayOf(1, 2), byteArrayOf(3), 1920, 1080, 30,
        ) to "82018b0102f61907801904381905dc4201024103190780190438181e",
        ServerMsg.CursorShape(1, 24, 24, -2, 3, 2304) to "82038601181818182103190900",
        ServerMsg.CursorPos(10.5, 20.25, 1, true) to "820484fb4025000000000000fb403440000000000001f5",
        ServerMsg.Pong(1, 2) to "8208820102",
        ServerMsg.Error(300, "later", null) to "82098219012c656c61746572",
        ServerMsg.Ping(9) to "820c8109",
    ) + clipboard.map { ServerMsg.Clipboard(it.first) to it.third }

    @Test
    fun clientMessagesMatchTheReference() {
        for ((msg, hex) in client) {
            assertEquals(hex, msg.encode().hex(), "$msg")
            assertEquals(msg, ClientMsg.decode(hex.unhex()), hex)
        }
    }

    @Test
    fun serverMessagesMatchTheReference() {
        for ((msg, hex) in server) {
            assertEquals(hex, msg.encode().hex(), "$msg")
            assertEquals(msg, ServerMsg.decode(hex.unhex()), hex)
        }
    }
}
