package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ByteSink
import io.github.c4software.gliff.proto.ByteSource
import io.github.c4software.gliff.proto.GliffException

/**
 * The byte pipe a session runs over: the stdio of `gliff-server --stdio`,
 * reached over ssh. Each platform supplies its own.
 *
 * Reads and writes block. [close] may be called from any thread, more than
 * once, and must make a read or write in progress fail or return the end of
 * the stream. An I/O failure is a [TransportException].
 */
interface Transport {
    val source: ByteSource
    val sink: ByteSink

    /**
     * The remote compositor's own keymap (XKB text), when the transport
     * could ask for it; empty otherwise. Sent back as the session's keymap,
     * so that keys mean the same to the client and to the remote.
     */
    val keymap: String get() = ""

    fun close()
}

/** The connection failed underneath the protocol. */
open class TransportException(message: String, cause: Throwable? = null) : GliffException(message, cause)

/** A server's host key as the user compares it: `SHA256:...`, as OpenSSH prints it. */
data class HostKeyInfo(val algorithm: String, val fingerprint: String)

/** First contact: the user has to confirm this key before it is trusted. */
class UnknownHostKeyException(val key: HostKeyInfo) :
    TransportException("unknown host key ${key.algorithm} ${key.fingerprint}")

/**
 * The server shows another key than the one trusted earlier: a reinstalled
 * machine, or someone in the middle.
 */
class ChangedHostKeyException(val expected: String, val key: HostKeyInfo) :
    TransportException("host key changed: expected $expected, got ${key.fingerprint}")
