package io.github.c4software.gliff.data.session

import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.session.HostKeyInfo
import io.github.c4software.gliff.ios.sha256
import io.github.c4software.gliff.ios.toByteArray
import io.github.c4software.gliff.ios.toNSData
import io.github.c4software.gliff.proto.ByteSink
import io.github.c4software.gliff.proto.ByteSource
import io.github.c4software.gliff.transport.ChangedHostKeyException
import io.github.c4software.gliff.transport.RemoteKeymap
import io.github.c4software.gliff.transport.SshTarget
import io.github.c4software.gliff.transport.SshWire
import io.github.c4software.gliff.transport.Transport
import io.github.c4software.gliff.transport.TransportException
import io.github.c4software.gliff.transport.UnknownHostKeyException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCondition
import platform.Foundation.NSData
import platform.posix.memcpy

/** Connects through the Swift ssh client; trust is on first use, as on Android. */
class IosConnector(private val bridge: SshBridge, private val log: (String) -> Unit) : Connector {
    override fun connect(machine: Machine): Transport {
        val target = SshTarget(machine.host, machine.user, machine.port, machine.serverBin, machine.serverArgs)
        val transport = IosTransport(machine.hostKey, log)
        transport.channel = bridge.open(
            target.host, target.port, target.user, target.command,
            RemoteKeymap.command, RemoteKeymap.TIMEOUT_MS, RemoteKeymap.MAX_BYTES,
            transport,
        )
        transport.awaitOpen()
        return transport
    }
}

/**
 * One ssh connection as the session reads and writes it. The Swift side
 * pushes what arrives; reads here block on a queue, which asks for a pause
 * when the session falls behind, so a stalled decoder holds the server back
 * instead of filling the memory.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosTransport(private val trusted: String?, private val log: (String) -> Unit) : Transport, SshListener {
    /** Guards everything below, and wakes the readers. */
    private val state = NSCondition()
    private val chunks = ArrayDeque<NSData>()

    /** Bytes of the first chunk already read. */
    private var consumed = 0
    private var queued = 0L
    private var paused = false
    private var opened = false
    private var ended = false
    private var closedHere = false
    private var error: String? = null
    private var refused: HostKeyInfo? = null

    @kotlin.concurrent.Volatile
    var channel: SshChannel? = null

    @kotlin.concurrent.Volatile
    override var keymap: String = ""
        private set

    private inline fun <T> locked(block: () -> T): T {
        state.lock()
        try {
            return block()
        } finally {
            state.unlock()
        }
    }

    /** Wait for the command to run, or for what stopped it. */
    fun awaitOpen() {
        val failure = locked {
            while (!opened && !ended) state.wait()
            if (opened) return
            refused?.let { if (trusted == null) UnknownHostKeyException(it) else ChangedHostKeyException(trusted, it) }
                ?: TransportException(error ?: "connection closed")
        }
        channel?.close()
        throw failure
    }

    override fun hostKey(blob: NSData): Boolean {
        val bytes = blob.toByteArray()
        val seen = HostKeyInfo(
            SshWire.algorithmName(SshWire.keyType(bytes) ?: "unknown"),
            SshWire.fingerprint(sha256(bytes)),
        )
        if (seen.fingerprint == trusted) return true
        locked { refused = seen }
        return false
    }

    override fun opened(keymap: String) {
        this.keymap = keymap
        locked {
            opened = true
            state.broadcast()
        }
    }

    override fun stdout(data: NSData): Boolean = locked {
        if (data.length > 0u) {
            chunks.addLast(data)
            queued += data.length.toLong()
            state.broadcast()
        }
        if (queued > HIGH_WATER) paused = true
        !paused
    }

    override fun stderr(line: String) = log(line)

    override fun closed(error: String?) = locked {
        if (!ended) {
            ended = true
            this.error = error
        }
        state.broadcast()
    }

    override val source = ByteSource { dst, offset, length ->
        var resume = false
        val count = locked {
            while (chunks.isEmpty() && !ended) state.wait()
            val first = chunks.firstOrNull()
            if (first == null) {
                // What was received before the end has been read: now the end shows.
                val reason = error
                if (reason == null || closedHere) return@locked -1
                throw TransportException(reason)
            }
            val n = minOf(length, first.length.toInt() - consumed)
            if (n > 0) {
                dst.usePinned { memcpy(it.addressOf(offset), first.bytes!!.reinterpret<ByteVar>() + consumed, n.convert()) }
            }
            consumed += n
            queued -= n
            if (consumed == first.length.toInt()) {
                chunks.removeFirst()
                consumed = 0
            }
            if (paused && queued < LOW_WATER) {
                paused = false
                resume = true
            }
            n
        }
        if (resume) channel?.resumeReading()
        count
    }

    override val sink = object : ByteSink {
        /** One message is several writes, and leaves as one block. */
        private var pending = ByteArray(4096)
        private var size = 0

        override fun write(src: ByteArray, offset: Int, length: Int) {
            failIfEnded()
            if (size + length > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, size + length))
            src.copyInto(pending, size, offset, offset + length)
            size += length
        }

        override fun flush() {
            failIfEnded()
            if (size == 0) return
            val data = pending.toNSData(size)
            size = 0
            // A keyframe-sized clipboard chunk is no reason to keep that much around.
            if (pending.size > MAX_KEPT) pending = ByteArray(4096)
            channel?.write(data)
        }

        private fun failIfEnded() {
            val reason = locked { if (ended) error ?: "connection closed" else null }
            if (reason != null) throw TransportException(reason)
        }
    }

    override fun close() {
        locked {
            closedHere = true
            ended = true
            chunks.clear()
            consumed = 0
            queued = 0
            state.broadcast()
        }
        channel?.close()
    }

    private companion object {
        /** The video queued here before the server is held back, and where it is let go again. */
        const val HIGH_WATER = 8L * 1024 * 1024
        const val LOW_WATER = 2L * 1024 * 1024
        const val MAX_KEPT = 1024 * 1024
    }
}
