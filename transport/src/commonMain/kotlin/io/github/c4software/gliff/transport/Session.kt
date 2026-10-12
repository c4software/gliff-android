package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ClientCaps
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.ClipboardMsg
import io.github.c4software.gliff.proto.EndOfStreamException
import io.github.c4software.gliff.proto.GliffException
import io.github.c4software.gliff.proto.FrameReader
import io.github.c4software.gliff.proto.FrameWriter
import io.github.c4software.gliff.proto.PROTOCOL_VERSION
import io.github.c4software.gliff.proto.ProtocolException
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.isIncompatible
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two ends cannot work together; reconnecting would fail the same way. */
class IncompatibleException(message: String) : GliffException(message)

/** The server reported an error that a later attempt may not hit. */
class ServerErrorException(val code: Int, message: String) : GliffException("server error $code: $message")

/** Largest `data_len` of one clipboard `Data` message. */
const val CLIPBOARD_CHUNK = 256 * 1024

/**
 * What a session reports. Every method is called on the session's reader
 * thread, in wire order, and must not block for long: video waits behind it.
 */
interface SessionHandler {
    fun onHello(ack: ServerMsg.HelloAck) {}

    /** The first one ends the handshake; later ones change the stream. */
    fun onStreamConfig(config: ServerMsg.StreamConfig)

    /**
     * One access unit. The handler owes the server a `FrameAck` for it,
     * through [Session.send], once it is decoded: the server bounds the bytes
     * in flight by the acks, so a stream nobody acks stalls.
     */
    fun onVideoFrame(frame: ServerMsg.VideoFrame, main: ByteArray, aux: ByteArray?)

    fun onCursorShape(shape: ServerMsg.CursorShape, argb: ByteArray) {}
    fun onCursorPos(pos: ServerMsg.CursorPos) {}
    fun onClipboard(msg: ClipboardMsg, payload: ByteArray?) {}

    /** Answer to a `ClientMsg.Ping`. */
    fun onPong(pong: ServerMsg.Pong) {}
}

/**
 * One connection to a gliff server over a [Transport]: the handshake, then a
 * reader and a writer. It follows the reference client,
 * `crates/gliff/src/net.rs`.
 */
class Session(
    private val transport: Transport,
    private val caps: ClientCaps,
    private val handler: SessionHandler,
    /** XKB keymap text, or empty for the server's default. */
    private val keymap: String = "",
) {
    private val outbox = Outbox()

    @Volatile
    private var closing = false

    /**
     * Queue a message for the server. Safe from any thread and at any time:
     * what is queued before the handshake ends leaves right after it, behind
     * the `Pong` the server wants first.
     */
    fun send(msg: ClientMsg, payload: ByteArray? = null) {
        if (!closing) outbox.offer(msg, payload)
    }

    /** Say `Bye`, then close the transport; [run] then returns normally. */
    fun close() {
        if (closing) return
        outbox.offer(ClientMsg.Bye)
        closing = true
    }

    /**
     * Run the session until the server closes the stream or [close] is
     * called. Cancelling the caller closes the transport.
     *
     * @throws IncompatibleException when retrying cannot help.
     * @throws GliffException on any other failure.
     */
    suspend fun run(): Unit = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                // A blocked read or write only ends when the stream closes.
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        transport.close()
                    }
                }
                val reader = FrameReader(transport.source, ServerMsg::decode)
                val writer = FrameWriter(transport.sink)
                handshake(reader, writer)
                launch { writeLoop(writer) }
                readLoop(reader)
                coroutineContext.cancelChildren()
            }
        } catch (e: GliffException) {
            coroutineContext.ensureActive()
            // After our own Bye the pipe is expected to die under us.
            if (!closing || e !is TransportException) throw e
        } finally {
            closing = true
            transport.close()
        }
    }

    private fun handshake(reader: FrameReader<ServerMsg>, writer: FrameWriter) {
        writer.write(ClientMsg.Hello(PROTOCOL_VERSION, keymap, caps))
        var acked = false
        while (true) {
            when (val msg = reader.readMsg()) {
                is ServerMsg.HelloAck -> {
                    if (msg.version != PROTOCOL_VERSION) {
                        throw IncompatibleException(versionMismatch(PROTOCOL_VERSION, msg.version))
                    }
                    acked = true
                    handler.onHello(msg)
                }
                // Answered ahead of anything else: it seeds the server's
                // round-trip estimate before the first frame ack.
                is ServerMsg.Ping -> writer.write(ClientMsg.Pong(msg.t))
                is ServerMsg.Error -> throw msg.toException()
                is ServerMsg.StreamConfig -> {
                    if (!acked) throw ProtocolException("StreamConfig before HelloAck")
                    handler.onStreamConfig(msg)
                    return
                }
                else -> throw ProtocolException("unexpected message before StreamConfig: $msg")
            }
        }
    }

    private fun readLoop(reader: FrameReader<ServerMsg>) {
        while (true) {
            val msg = try {
                reader.readMsg()
            } catch (_: EndOfStreamException) {
                return
            }
            when (msg) {
                is ServerMsg.VideoFrame -> {
                    val main = reader.readPayload(msg.dataLen)
                    val aux = if (msg.auxLen != 0) reader.readPayload(msg.auxLen) else null
                    handler.onVideoFrame(msg, main, aux)
                }
                is ServerMsg.StreamConfig -> handler.onStreamConfig(msg)
                is ServerMsg.CursorShape -> handler.onCursorShape(msg, reader.readPayload(msg.argbLen))
                is ServerMsg.CursorPos -> handler.onCursorPos(msg)
                is ServerMsg.Ping -> send(ClientMsg.Pong(msg.t))
                is ServerMsg.Pong -> handler.onPong(msg)
                is ServerMsg.Error -> throw msg.toException()
                is ServerMsg.HelloAck -> {}
                is ServerMsg.Clipboard -> {
                    val clip = msg.msg
                    val payload = if (clip is ClipboardMsg.Data) {
                        if (clip.dataLen < 0 || clip.dataLen > CLIPBOARD_CHUNK) {
                            throw ProtocolException("clipboard chunk of ${clip.dataLen.toUInt()} bytes exceeds the limit")
                        }
                        reader.readPayload(clip.dataLen)
                    } else {
                        null
                    }
                    handler.onClipboard(clip, payload)
                }
            }
        }
    }

    private suspend fun writeLoop(writer: FrameWriter) {
        while (true) {
            val out = outbox.take()
            try {
                if (out.payload == null) writer.write(out.msg) else writer.write(out.msg, out.payload)
            } catch (e: TransportException) {
                if (closing) throw CancellationException("closed")
                throw e
            }
            if (out.msg == ClientMsg.Bye) {
                transport.close()
                return
            }
        }
    }

    private fun ServerMsg.Error.toException(): GliffException =
        if (isIncompatible(code)) IncompatibleException(message) else ServerErrorException(code, message)

    companion object {
        /** Which end of a protocol version mismatch needs updating. */
        fun versionMismatch(client: Int, server: Int): String =
            if (client < server) "Client version is too old" else "Server version is too old"
    }
}
