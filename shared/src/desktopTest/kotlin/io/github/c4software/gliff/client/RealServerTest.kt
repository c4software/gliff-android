package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientCaps
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.ClipboardMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.transport.Session
import io.github.c4software.gliff.transport.SessionHandler
import io.github.c4software.gliff.transport.ProcessTransport
import io.github.c4software.gliff.transport.SshTarget
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assume

/**
 * Against a real gliff-server, when one is named:
 *
 *     GLIFF_REAL=user@host ./gradlew :shared:desktopTest --tests '*RealServerTest*'
 *
 * Everything goes through the system's `ssh`, the session as well as the
 * `wl-copy` and `wl-paste` it runs on the host, so the host has to accept it
 * without a prompt. The host's clipboard is put back as it was.
 */
class RealServerTest {
    private val host = System.getenv("GLIFF_REAL")

    /** Run `command` on the host. A `wl-copy` stays behind to serve its selection, so it must not keep the output. */
    private fun remote(command: String, input: String? = null): String {
        val env = "export XDG_RUNTIME_DIR=/run/user/\$(id -u); export WAYLAND_DISPLAY=\$(ls \$XDG_RUNTIME_DIR | grep -m1 '^wayland-[0-9]*\$'); "
        val process = ProcessBuilder("ssh", "-o", "BatchMode=yes", host, env + command).redirectErrorStream(true).start()
        process.outputStream.use { out -> input?.let { out.write(it.toByteArray()) } }
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        return text
    }

    @Test
    fun textGoesBothWaysThroughTheClipboard() {
        Assume.assumeTrue("no real server named", host != null)
        val before = remote("wl-paste -n -t text/plain 2>/dev/null")
        val command = SshTarget.parse(host, System.getProperty("user.name")).copy(serverArgs = listOf("--output", "auto")).command
        val transport = ProcessTransport(listOf("ssh", "-T", "-o", "BatchMode=yes", host, command))
        val arrived = LinkedBlockingQueue<String>()
        lateinit var session: Session
        val lock = Any()
        val sync = ClipboardSync({ msg, payload -> session.send(ClientMsg.Clipboard(msg), payload) }, { arrived.add(it) })
        session = Session(
            transport,
            ClientCaps(listOf(Codec.H264), 3840, 2160, listOf(ChromaMode.Single420)),
            object : SessionHandler {
                override fun onClipboard(msg: ClipboardMsg, payload: ByteArray?) = synchronized(lock) { sync.onMessage(msg, payload) }
                override fun onHello(ack: io.github.c4software.gliff.proto.ServerMsg.HelloAck) {}
                override fun onStreamConfig(config: io.github.c4software.gliff.proto.ServerMsg.StreamConfig) {}
                override fun onVideoFrame(frame: io.github.c4software.gliff.proto.ServerMsg.VideoFrame, main: ByteArray, aux: ByteArray?) {
                    session.send(ClientMsg.FrameAck(frame.frameId, System.currentTimeMillis()))
                }
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { runCatching { session.run() } }
        try {
            Thread.sleep(1500)
            // Copied on the remote: it arrives here by itself.
            val there = "gliff, copié là-bas ${System.nanoTime()}"
            remote("wl-copy >/dev/null 2>&1", there)
            assertEquals(there, generateSequence { arrived.poll(8, TimeUnit.SECONDS) }.firstOrNull { it == there })
            // Copied here: the remote gets it when something there pastes.
            val here = "gliff, copié ici ${System.nanoTime()} " + "é".repeat(300_000)
            synchronized(lock) { sync.localText(here) }
            Thread.sleep(800)
            assertEquals(here, remote("wl-paste -n -t 'text/plain;charset=utf-8'"))
        } finally {
            session.close()
            Thread.sleep(300)
            scope.cancel()
            if (before.isNotEmpty()) remote("wl-copy >/dev/null 2>&1", before) else remote("wl-copy --clear >/dev/null 2>&1")
        }
    }
}
