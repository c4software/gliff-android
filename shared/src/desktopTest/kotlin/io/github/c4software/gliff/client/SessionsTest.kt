package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.VideoPipeline
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The registry of sessions: one per machine, and the mirror following the one in front. */
class SessionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryStore()
    private val machines = MachineRepository(store)
    private val settings = SettingsRepository(store)
    private val a = Machine(user = "val", host = "a", hostKey = "k").also { machines.save(it) }
    private val b = Machine(user = "val", host = "b", hostKey = "k").also { machines.save(it) }

    /** The server ends, by host. */
    private val peers = mapOf("a" to LinkedBlockingQueue<Peer>(), "b" to LinkedBlockingQueue<Peer>())
    private val connector = Connector { m -> Peer().also { peers.getValue(m.host).add(it) }.transport }
    private val decoders = CopyOnWriteArrayList<CountingDecoder>()
    private val copied = LinkedBlockingQueue<String>()
    private val sessions = Sessions(
        machines, settings, connector, scope,
        nowMs = { 4242 },
        clipboardOut = { copied.add(it) },
        decoder = { CountingDecoder().also { decoders.add(it) } },
    )

    private class CountingDecoder : VideoDecoder by FakeDecoder() {
        @Volatile
        var closed = false

        override fun close() {
            closed = true
        }
    }

    /** What reached the mirror, in order. */
    private val mirrored = LinkedBlockingQueue<String>()
    private val mirror = object : MirrorSink {
        override fun configure(width: Int, height: Int) {
            mirrored.add("configure ${width}x$height")
        }

        override fun frame(data: ByteArray, keyframe: Boolean) {
            mirrored.add("frame ${data.size}")
        }

        override fun reset() {
            mirrored.add("reset")
        }
    }

    private fun config(width: Int, height: Int) = ServerMsg.StreamConfig(
        Codec.H264, ChromaMode.Single420, VideoPipeline.Gpu, width, height, 1000, ByteArray(0), null, width, height, 60,
    )

    private fun peer(host: String): Peer = peers.getValue(host).poll(5, TimeUnit.SECONDS) ?: error("no connection to $host")

    private fun Peer.frame(id: Long, size: Int) =
        writer.write(ServerMsg.VideoFrame(id, 0, true, emptyList(), size, 0), ByteArray(size))

    private fun next(): String = mirrored.poll(5, TimeUnit.SECONDS) ?: error("nothing reached the mirror")

    @AfterTest
    fun tearDown() {
        sessions.endSession(a.id)
        sessions.endSession(b.id)
        scope.cancel()
    }

    @Test
    fun aMachineHasOneSessionUntilItIsEnded() {
        val first = sessions.session(a.id)
        assertSame(first, sessions.session(a.id))
        assertNotSame(first, sessions.session(b.id))
        peer("a").handshake(config(1920, 1080))
        assertNull(peers.getValue("a").poll(200, TimeUnit.MILLISECONDS), "connected twice")

        sessions.endSession(a.id)
        assertEquals(SessionState.Closed, first.state.value)
        assertTrue(decoders[0].closed)
        assertTrue(!decoders[1].closed)
        // Opened again, it is a new session on a new connection.
        assertNotSame(first, sessions.session(a.id))
        peer("a")
    }

    @Test
    fun theFirstSessionIsInFrontAndFrontFollowsTheWindow() {
        assertNull(sessions.frontSession)
        val first = sessions.session(a.id)
        val second = sessions.session(b.id)
        assertSame(first, sessions.frontSession)
        sessions.front(b.id)
        assertSame(second, sessions.frontSession)
        sessions.endSession(b.id)
        assertNull(sessions.frontSession)
    }

    @Test
    fun theMirrorShowsTheSessionInFront() {
        sessions.mirror = mirror
        sessions.session(a.id)
        sessions.session(b.id)
        val pa = peer("a").also { it.handshake(config(1920, 1080)) }
        val pb = peer("b").also { it.handshake(config(1280, 720)) }
        assertEquals("configure 1920x1080", next())

        pa.frame(1, 100)
        assertEquals("frame 100", next())
        // The other session decodes for its own window and stays out of the mirror.
        pb.frame(1, 200)
        assertEquals(ClientMsg.FrameAck(1, 4242), pb.expect())

        sessions.front(b.id)
        assertEquals(listOf("reset", "configure 1280x720"), listOf(next(), next()))
        // A viewer can only start on a keyframe.
        assertEquals(ClientMsg.RequestKeyframe, pb.expect())
        pb.frame(2, 300)
        assertEquals("frame 300", next())
        pa.frame(2, 400)
        assertEquals(ClientMsg.FrameAck(1, 4242), pa.expect())
        assertEquals(ClientMsg.FrameAck(2, 4242), pa.expect())
        assertNull(mirrored.poll(200, TimeUnit.MILLISECONDS))

        sessions.endSession(b.id)
        assertEquals("reset", next())
    }

    @Test
    fun aMirrorStartedLateLearnsTheSizeOfTheSessionInFront() {
        sessions.session(a.id)
        val pa = peer("a").also { it.handshake(config(1920, 1080)) }
        pa.frame(1, 100)
        assertEquals(ClientMsg.FrameAck(1, 4242), pa.expect())

        sessions.mirror = mirror
        assertEquals("configure 1920x1080", next())
        pa.frame(2, 100)
        assertEquals("frame 100", next())
        sessions.mirror = null
        pa.frame(3, 100)
        assertEquals(ClientMsg.FrameAck(2, 4242), pa.expect())
        assertEquals(ClientMsg.FrameAck(3, 4242), pa.expect())
        assertNull(mirrored.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun theClipboardOptionIsAskedEachTime() {
        val session = sessions.session(a.id)
        val pa = peer("a").also { it.handshake(config(1920, 1080)) }
        settings.update { it.copy(clipboard = false) }
        session.localClipboard("kept here")
        settings.update { it.copy(clipboard = true) }
        session.localClipboard("shared")
        val offer = pa.expect() as ClientMsg.Clipboard
        assertTrue(offer.msg is io.github.c4software.gliff.proto.ClipboardMsg.Offer)
    }
}
