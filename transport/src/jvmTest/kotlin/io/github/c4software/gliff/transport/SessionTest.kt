package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientCaps
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.ClipboardMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.CborWriter
import io.github.c4software.gliff.proto.FrameReader
import io.github.c4software.gliff.proto.FrameWriter
import io.github.c4software.gliff.proto.OutputInfo
import io.github.c4software.gliff.proto.ProtocolException
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.SessionInfo
import io.github.c4software.gliff.proto.VideoPipeline
import io.github.c4software.gliff.proto.ByteSink
import io.github.c4software.gliff.proto.ByteSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A session wired to an in-process stand-in for `gliff-server --stdio`. */
class SessionTest {
    private val caps = ClientCaps(listOf(Codec.H264), 3840, 2160, listOf(ChromaMode.Single420))

    private val helloAck = ServerMsg.HelloAck(
        1, SessionInfo(true, "HEADLESS-2"), listOf(OutputInfo("HEADLESS-2", 1920, 1080, 1000)), emptyList(),
    )

    private val config = ServerMsg.StreamConfig(
        Codec.H264, ChromaMode.Single420, VideoPipeline.Gpu, 1920, 1080, 1000, ByteArray(0), null, 1920, 1080, 60,
    )

    private class Events : SessionHandler {
        val all = LinkedBlockingQueue<Any>()
        var session: Session? = null

        fun next(): Any = all.poll(5, TimeUnit.SECONDS) ?: error("no event within 5 s")

        override fun onHello(ack: ServerMsg.HelloAck) { all.add(ack) }
        override fun onStreamConfig(config: ServerMsg.StreamConfig) { all.add(config) }
        override fun onVideoFrame(frame: ServerMsg.VideoFrame, main: ByteArray, aux: ByteArray?) {
            all.add(Triple(frame, main, aux))
            session?.send(ClientMsg.FrameAck(frame.frameId, 1234))
        }
        override fun onCursorShape(shape: ServerMsg.CursorShape, argb: ByteArray) { all.add(shape to argb) }
        override fun onCursorPos(pos: ServerMsg.CursorPos) { all.add(pos) }
        override fun onClipboard(msg: ClipboardMsg, payload: ByteArray?) { all.add(msg to payload) }
        override fun onPong(pong: ServerMsg.Pong) { all.add(pong) }
    }

    /** The server's end of the pipe. */
    private class Peer(val reader: FrameReader<ClientMsg>, val writer: FrameWriter, val hangUp: () -> Unit) {
        fun expect(): ClientMsg = reader.readMsg()
    }

    private class Rig(val session: Session, val events: Events, val peer: Peer, val running: Deferred<Unit>)

    /** One direction of an in-memory connection; closing it ends both halves. */
    private class Pipe : ByteSource, ByteSink {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var pos = 0

        @Volatile
        private var closed = false

        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            while (pos == current.size) {
                current = chunks.take()
                pos = 0
                if (current === END) {
                    chunks.add(END)
                    return -1
                }
            }
            val n = minOf(length, current.size - pos)
            current.copyInto(dst, offset, pos, pos + n)
            pos += n
            return n
        }

        override fun write(src: ByteArray, offset: Int, length: Int) {
            if (closed) throw TransportException("pipe closed")
            if (length > 0) chunks.add(src.copyOfRange(offset, offset + length))
        }

        override fun flush() {}

        fun close() {
            closed = true
            chunks.add(END)
        }

        private companion object {
            val END = ByteArray(0)
        }
    }

    private fun rig(): Rig {
        val toClient = Pipe()
        val toServer = Pipe()
        val transport = object : Transport {
            override val source: ByteSource = toClient
            override val sink: ByteSink = toServer
            override fun close() {
                toClient.close()
                toServer.close()
            }
        }
        val events = Events()
        val session = Session(transport, caps, events)
        events.session = session
        val peer = Peer(FrameReader(toServer, ClientMsg::decode), FrameWriter(toClient)) { toClient.close() }
        // Its own scope: a session that fails must not cancel the test with it.
        return Rig(session, events, peer, CoroutineScope(SupervisorJob() + Dispatchers.IO).async { session.run() })
    }

    private fun Rig.handshake() {
        assertEquals(ClientMsg.Hello(1, "", caps), peer.expect())
        peer.writer.write(helloAck)
        peer.writer.write(ServerMsg.Ping(77))
        peer.writer.write(config)
        assertEquals(ClientMsg.Pong(77), peer.expect())
        assertEquals(helloAck, events.next())
        assertEquals(config, events.next())
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { body() } }

    @Test
    fun pongLeavesBeforeAnythingQueuedEarlier() = test {
        val r = rig()
        // Queued before the handshake, as the app does with its window size.
        r.session.send(ClientMsg.Resize(1280, 720, 2f))
        r.handshake()
        assertEquals(ClientMsg.Resize(1280, 720, 2f), r.peer.expect())
        r.session.close()
        assertEquals(ClientMsg.Bye, r.peer.expect())
        r.running.await()
    }

    @Test
    fun deliversFramesInOrderAndCarriesTheAcks() = test {
        val r = rig()
        r.handshake()
        val main = ByteArray(70_000) { it.toByte() }
        val aux = ByteArray(300) { 9 }
        r.peer.writer.write(ServerMsg.VideoFrame(1, 10, true, emptyList(), main.size, 0), main)
        r.peer.writer.write(ServerMsg.VideoFrame(2, 20, false, emptyList(), 5, aux.size), main.copyOf(5), aux)
        val (f1, m1, a1) = assertIs<Triple<ServerMsg.VideoFrame, ByteArray, ByteArray?>>(r.events.next())
        assertEquals(1, f1.frameId)
        assertContentEquals(main, m1)
        assertNull(a1)
        val (f2, m2, a2) = assertIs<Triple<ServerMsg.VideoFrame, ByteArray, ByteArray?>>(r.events.next())
        assertEquals(2, f2.frameId)
        assertContentEquals(main.copyOf(5), m2)
        assertContentEquals(aux, a2)
        assertEquals(ClientMsg.FrameAck(1, 1234), r.peer.expect())
        assertEquals(ClientMsg.FrameAck(2, 1234), r.peer.expect())
        r.running.cancelAndJoin()
    }

    @Test
    fun routesTheOtherMessagesAndSkipsUnknownOnes() = test {
        val r = rig()
        r.handshake()
        val w = r.peer.writer
        // A message from a newer server, with a payload this build cannot size.
        w.write(CborWriter().apply { array(2); uint(99); array(1); uint(3) }.toByteArray(), ByteArray(3))
        w.write(ServerMsg.CursorShape(1, 1, 1, 0, 0, 4), byteArrayOf(1, 2, 3, 4))
        w.write(ServerMsg.CursorPos(1.0, 2.0, 1, true))
        w.write(ServerMsg.Ping(5))
        w.write(ServerMsg.Pong(6, 7))
        w.write(ServerMsg.Clipboard(ClipboardMsg.Data(2, 0, 2, true)), byteArrayOf(8, 9))
        w.write(ServerMsg.Clipboard(ClipboardMsg.Ack(1, 2)))
        val changed = config.copy(width = 1280, height = 720, viewWidth = 1280, viewHeight = 720)
        w.write(changed)

        val (shape, argb) = assertIs<Pair<ServerMsg.CursorShape, ByteArray>>(r.events.next())
        assertEquals(4, shape.argbLen)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), argb)
        assertEquals(ServerMsg.CursorPos(1.0, 2.0, 1, true), r.events.next())
        assertEquals(ServerMsg.Pong(6, 7), r.events.next())
        val (data, chunk) = assertIs<Pair<ClipboardMsg, ByteArray?>>(r.events.next())
        assertEquals(ClipboardMsg.Data(2, 0, 2, true), data)
        assertContentEquals(byteArrayOf(8, 9), chunk)
        assertEquals(ClipboardMsg.Ack(1, 2) to null, r.events.next())
        assertEquals(changed, r.events.next())
        assertEquals(ClientMsg.Pong(5), r.peer.expect())
        r.running.cancelAndJoin()
    }

    @Test
    fun aServerThatHangsUpEndsTheSessionNormally() = test {
        val r = rig()
        r.handshake()
        r.peer.hangUp()
        r.running.await()
    }

    @Test
    fun anIncompatibleErrorIsNotASimpleFailure() = test {
        val r = rig()
        r.peer.expect()
        r.peer.writer.write(ServerMsg.Error(2, "No chroma mode in common"))
        val e = assertFailsWith<IncompatibleException> { r.running.await() }
        assertEquals("No chroma mode in common", e.message)
    }

    @Test
    fun aVersionMismatchNamesTheEndToUpdate() = test {
        val r = rig()
        r.peer.expect()
        r.peer.writer.write(helloAck.copy(version = 2))
        val e = assertFailsWith<IncompatibleException> { r.running.await() }
        assertEquals("Client version is too old", e.message)
    }

    @Test
    fun aLaterServerErrorCarriesItsCode() = test {
        val r = rig()
        r.handshake()
        r.peer.writer.write(ServerMsg.Error(300, "capture stopped"))
        val e = assertFailsWith<ServerErrorException> { r.running.await() }
        assertEquals(300, e.code)
    }

    @Test
    fun aStreamConfigBeforeHelloAckIsRefused() = test {
        val r = rig()
        r.peer.expect()
        r.peer.writer.write(config)
        assertFailsWith<ProtocolException> { r.running.await() }
    }

    @Test
    fun anOversizedClipboardChunkIsRefused() = test {
        val r = rig()
        r.handshake()
        r.peer.writer.write(ServerMsg.Clipboard(ClipboardMsg.Data(2, 0, CLIPBOARD_CHUNK + 1, false)))
        assertFailsWith<ProtocolException> { r.running.await() }
    }

    @Test
    fun cancellingTheCallerUnblocksTheReader() = test {
        val r = rig()
        r.handshake()
        r.running.cancelAndJoin()
        assertTrue(r.running.isCancelled)
    }

    @Test
    fun motionsCoalesceButNeverAcrossAnotherMessage() {
        val box = Outbox()
        val sent = CopyOnWriteArrayList<ClientMsg>()
        box.offer(ClientMsg.PointerMotion(1.0, 1.0))
        box.offer(ClientMsg.PointerMotion(2.0, 2.0))
        box.offer(ClientMsg.PointerMotion(3.0, 3.0))
        box.offer(ClientMsg.PointerButton(0x110, true))
        box.offer(ClientMsg.PointerMotion(4.0, 4.0))
        box.offer(ClientMsg.PointerButton(0x110, false))
        box.offer(ClientMsg.Key(30, true))
        box.offer(ClientMsg.Key(30, false))
        while (true) sent.add(box.poll()?.msg ?: break)
        assertEquals(
            listOf(
                ClientMsg.PointerMotion(3.0, 3.0),
                ClientMsg.PointerButton(0x110, true),
                ClientMsg.PointerMotion(4.0, 4.0),
                ClientMsg.PointerButton(0x110, false),
                ClientMsg.Key(30, true),
                ClientMsg.Key(30, false),
            ),
            sent,
        )
    }

    @Test
    fun targetsParseAndQuote() {
        assertEquals(SshTarget("box", "me"), SshTarget.parse("box", "me"))
        assertEquals(SshTarget("10.0.0.2", "val", 2222), SshTarget.parse("val@10.0.0.2:2222", "me"))
        val env = "export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\""
        assertEquals(
            "exec sh -c '$env; exec gliff-server --stdio --headless'",
            SshTarget("h", "u", serverArgs = listOf("--headless")).command,
        )
        // The binary is a shell fragment, as in the reference client; arguments are not.
        val t = SshTarget("h", "u", serverBin = "env A=1 gliff-server", serverArgs = listOf("--output", "DP-1; rm"))
        assertEquals(
            "exec sh -c '$env; exec env A=1 gliff-server --stdio --output '\\''DP-1; rm'\\'''",
            t.command,
        )
    }
}
