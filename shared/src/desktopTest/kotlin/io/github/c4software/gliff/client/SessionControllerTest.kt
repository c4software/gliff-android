package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.ByteSink
import io.github.c4software.gliff.proto.ByteSource
import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.FrameReader
import io.github.c4software.gliff.proto.FrameWriter
import io.github.c4software.gliff.proto.OutputInfo
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.SessionInfo
import io.github.c4software.gliff.proto.VideoPipeline
import io.github.c4software.gliff.transport.ChangedHostKeyException
import io.github.c4software.gliff.transport.HostKeyInfo
import io.github.c4software.gliff.transport.Transport
import io.github.c4software.gliff.transport.TransportException
import io.github.c4software.gliff.transport.UnknownHostKeyException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The controller against a scripted server, over an in-memory connection. */
class SessionControllerTest {
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

    /** The server's end of one connection. */
    private class Peer {
        val toClient = Pipe()
        val toServer = Pipe()
        val reader = FrameReader(toServer, ClientMsg::decode)
        val writer = FrameWriter(toClient)
        val transport = object : Transport {
            override val source: ByteSource = toClient
            override val sink: ByteSink = toServer
            override fun close() {
                toClient.close()
                toServer.close()
            }
        }

        fun expect(): ClientMsg = reader.readMsg()

        fun handshake(config: ServerMsg.StreamConfig): ClientMsg.Hello {
            val hello = assertIs<ClientMsg.Hello>(expect())
            writer.write(
                ServerMsg.HelloAck(
                    1, SessionInfo(true, "HEADLESS-2"), listOf(OutputInfo("HEADLESS-2", 1920, 1080, 1000)), emptyList(),
                )
            )
            writer.write(ServerMsg.Ping(1))
            writer.write(config)
            assertEquals(ClientMsg.Pong(1), expect())
            return hello
        }
    }

    private class FakeDecoder : VideoDecoder {
        override val maxWidth = 4096
        override val maxHeight = 4096
        override var listener: VideoDecoder.Listener? = null
        val configured = CopyOnWriteArrayList<Pair<Int, Int>>()
        val decoded = LinkedBlockingQueue<Triple<Long, Int, Boolean>>()

        override fun configure(width: Int, height: Int) {
            configured.add(width to height)
        }

        override fun decode(frameId: Long, data: ByteArray, keyframe: Boolean) {
            decoded.add(Triple(frameId, data.size, keyframe))
            listener?.onFrameDone(frameId)
        }

        override fun close() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryStore()
    private val machines = MachineRepository(store)
    private val machine = Machine(user = "val", host = "box").also { machines.save(it) }
    private val decoder = FakeDecoder()
    private val peers = LinkedBlockingQueue<Peer>()
    private val serverKey = HostKeyInfo("Ed25519", "SHA256:server")

    /** Trust on first use, as the real connector does it. */
    private val connector = Connector { m ->
        when (m.hostKey) {
            null -> throw UnknownHostKeyException(serverKey)
            serverKey.fingerprint -> Peer().also { peers.add(it) }.transport
            else -> throw ChangedHostKeyException(m.hostKey!!, serverKey)
        }
    }

    private val controller = SessionController(machine.id, machines, connector, decoder, scope) { 4242 }

    private fun config(width: Int = 1920, height: Int = 1080, fps: Int = 60) = ServerMsg.StreamConfig(
        Codec.H264, ChromaMode.Single420, VideoPipeline.Gpu, width, height, 1000, ByteArray(0), null, width, height, fps,
    )

    private fun peer(): Peer = peers.poll(5, TimeUnit.SECONDS) ?: error("no connection within 5 s")

    private inline fun <reified T : SessionState> awaitState(): T = runBlocking {
        withTimeout(5000) { controller.state.first { it is T } as T }
    }

    private fun trusted() = machines.save(machine.copy(hostKey = serverKey.fingerprint))

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test
    fun aFirstConnectionAsksBeforeTrustingTheHostKey() {
        controller.start()
        val prompt = awaitState<SessionState.HostKeyPrompt>()
        assertEquals(serverKey, prompt.key)
        assertNull(prompt.previous)
        assertNull(machines.find(machine.id)!!.hostKey)

        controller.acceptHostKey()
        val hello = peer().handshake(config())
        assertEquals("SHA256:server", machines.find(machine.id)!!.hostKey)
        // Stored, so the next launch does not ask again.
        assertEquals("SHA256:server", MachineRepository(store).find(machine.id)!!.hostKey)
        assertEquals(listOf(ChromaMode.Single420), hello.caps.chroma)
        assertEquals(4096 to 4096, hello.caps.maxWidth to hello.caps.maxHeight)
        val streaming = awaitState<SessionState.Streaming>()
        assertEquals(1920 to 1080, streaming.stream.width to streaming.stream.height)
        assertTrue(streaming.stream.headless)
        assertEquals("HEADLESS-2", streaming.stream.output)
    }

    @Test
    fun aChangedHostKeyShowsTheOneTrustedBefore() {
        machines.save(machine.copy(hostKey = "SHA256:old"))
        controller.start()
        val prompt = awaitState<SessionState.HostKeyPrompt>()
        assertEquals("SHA256:old", prompt.previous)
        assertEquals(serverKey, prompt.key)
        // Nothing is replaced until the user says so.
        assertEquals("SHA256:old", machines.find(machine.id)!!.hostKey)
    }

    @Test
    fun framesReachTheDecoderAndAreAcked() {
        trusted()
        controller.start()
        val p = peer()
        p.handshake(config())
        p.writer.write(ServerMsg.VideoFrame(7, 0, true, emptyList(), 1000, 0), ByteArray(1000))
        p.writer.write(ServerMsg.VideoFrame(8, 0, false, emptyList(), 10, 0), ByteArray(10))
        assertEquals(Triple(7L, 1000, true), decoder.decoded.poll(5, TimeUnit.SECONDS))
        assertEquals(Triple(8L, 10, false), decoder.decoded.poll(5, TimeUnit.SECONDS))
        assertEquals(ClientMsg.FrameAck(7, 4242), p.expect())
        assertEquals(ClientMsg.FrameAck(8, 4242), p.expect())
    }

    @Test
    fun theWindowSizeGoesRightBehindTheHandshake() {
        trusted()
        // The view is laid out before the connection is up.
        controller.setWindowSize(1080, 2200, 2.5f)
        controller.start()
        val p = peer()
        p.handshake(config())
        assertEquals(ClientMsg.Resize(1080, 2200, 2.5f), p.expect())
    }

    @Test
    fun aDraggedWindowEdgeSendsOnlyWhereItStops() {
        trusted()
        controller.setWindowSize(1000, 800, 1f)
        controller.start()
        val p = peer()
        p.handshake(config())
        assertEquals(ClientMsg.Resize(1000, 800, 1f), p.expect())
        for (w in 1001..1040) {
            controller.setWindowSize(w, 800, 1f)
            Thread.sleep(5)
        }
        assertEquals(ClientMsg.Resize(1040, 800, 1f), p.expect())
        // The same size again, and one too small to be a window, say nothing.
        controller.setWindowSize(1040, 800, 1f)
        controller.setWindowSize(40, 30, 1f)
        Thread.sleep(SessionController.RESIZE_DEBOUNCE_MS * 2)
        controller.requestKeyframe()
        assertEquals(ClientMsg.RequestKeyframe, p.expect())
    }

    @Test
    fun onlyANewCodedSizeResetsTheDecoder() {
        trusted()
        controller.start()
        val p = peer()
        p.handshake(config())
        awaitState<SessionState.Streaming>()
        p.writer.write(config(fps = 30))
        p.writer.write(config(1280, 720))
        p.writer.write(ServerMsg.VideoFrame(1, 0, true, emptyList(), 1, 0), ByteArray(1))
        decoder.decoded.poll(5, TimeUnit.SECONDS)
        assertEquals(listOf(1920 to 1080, 1280 to 720), decoder.configured.toList())
        val stream = (controller.state.value as SessionState.Streaming).stream
        assertEquals(Triple(1280, 720, 60), Triple(stream.viewWidth, stream.viewHeight, stream.fpsCap))
    }

    @Test
    fun keyframeRequestsAreSpacedOut() {
        var now = 10_000L
        val c = SessionController(machine.id, machines, connector, decoder, scope) { now }
        trusted()
        c.start()
        val p = peer()
        p.handshake(config())
        repeat(5) { c.requestKeyframe() }
        now += SessionController.KEYFRAME_REQUEST_GAP_MS
        c.requestKeyframe()
        c.close()
        assertEquals(
            listOf(ClientMsg.RequestKeyframe, ClientMsg.RequestKeyframe, ClientMsg.Bye),
            listOf(p.expect(), p.expect(), p.expect()),
        )
    }

    @Test
    fun anIncompatibleServerIsNotWorthRetrying() {
        trusted()
        controller.start()
        val p = peer()
        p.expect()
        p.writer.write(ServerMsg.Error(1, "Client version is too old"))
        val failed = awaitState<SessionState.Failed>()
        assertTrue(failed.incompatible)
        assertEquals("Client version is too old", failed.message)
    }

    @Test
    fun aDroppedConnectionCanBeRetried() {
        trusted()
        controller.start()
        val first = peer()
        first.handshake(config())
        awaitState<SessionState.Streaming>()
        first.toClient.close()
        val failed = awaitState<SessionState.Failed>()
        assertTrue(!failed.incompatible)

        controller.setWindowSize(800, 600, 1f)
        controller.start()
        val second = peer()
        second.handshake(config())
        // A new server knows nothing of the old one's size.
        assertEquals(ClientMsg.Resize(800, 600, 1f), second.expect())
        awaitState<SessionState.Streaming>()
    }

    @Test
    fun closingSaysByeAndStaysClosed() {
        trusted()
        controller.start()
        val p = peer()
        p.handshake(config())
        awaitState<SessionState.Streaming>()
        controller.close()
        assertEquals(ClientMsg.Bye, p.expect())
        assertEquals(SessionState.Closed, controller.state.value)
        controller.start()
        Thread.sleep(100)
        assertEquals(SessionState.Closed, controller.state.value)
        assertNull(peers.poll())
    }
}
