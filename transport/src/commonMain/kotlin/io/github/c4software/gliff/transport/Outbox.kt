package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ClientMsg
import kotlinx.coroutines.channels.Channel

internal class Outgoing(val msg: ClientMsg, val payload: ByteArray?)

/**
 * Messages waiting for the writer, in order. Any thread may [offer]; one
 * writer takes. A run of pointer motions leaves as its last one, so a slow
 * link carries the newest position rather than a backlog; nothing else is
 * ever dropped or reordered.
 */
internal class Outbox {
    private val queue = Channel<Outgoing>(Channel.UNLIMITED)

    /** Taken while looking past a motion; it leaves next. Writer only. */
    private var held: Outgoing? = null

    fun offer(msg: ClientMsg, payload: ByteArray? = null) {
        queue.trySend(Outgoing(msg, payload))
    }

    fun poll(): Outgoing? {
        var current = held ?: queue.tryReceive().getOrNull() ?: return null
        held = null
        while (current.msg is ClientMsg.PointerMotion) {
            val next = queue.tryReceive().getOrNull() ?: break
            if (next.msg is ClientMsg.PointerMotion) {
                current = next
            } else {
                held = next
                break
            }
        }
        return current
    }

    suspend fun take(): Outgoing {
        poll()?.let { return it }
        held = queue.receive()
        return poll()!!
    }
}
