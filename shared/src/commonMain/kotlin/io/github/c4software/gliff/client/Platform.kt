package io.github.c4software.gliff.client

import io.github.c4software.gliff.transport.Transport

/**
 * Opens the ssh connection to a machine and starts `gliff-server --stdio`
 * there. Blocking.
 *
 * Trust is on first use: with no `Machine.hostKey` it throws
 * `UnknownHostKeyException`, with another key than the stored one
 * `ChangedHostKeyException`; any other failure is a `TransportException`.
 */
fun interface Connector {
    fun connect(machine: Machine): Transport
}

/** The app's own ssh key. */
fun interface Identity {
    /** The line for the server's `~/.ssh/authorized_keys`. Blocking: the key is made on first use. */
    fun authorizedKey(): String
}

/**
 * The platform's H.264 decoder, drawing on the session's video surface. One
 * 4:2:0 stream, Annex B, parameter sets in front of every keyframe.
 */
interface VideoDecoder {
    /** The largest picture the device decodes; advertised to the server. */
    val maxWidth: Int
    val maxHeight: Int

    var listener: Listener?

    /** The coded size changed: start over, the next frame is a keyframe. */
    fun configure(width: Int, height: Int)

    /**
     * Take one access unit. Never blocks for long, and always ends in
     * [Listener.onFrameDone], shown or not: the server waits for it.
     */
    fun decode(frameId: Long, data: ByteArray, keyframe: Boolean)

    fun close()

    interface Listener {
        /** The frame left the decoder, or was dropped. */
        fun onFrameDone(frameId: Long)

        /** The decoder cannot go on from the frames it has. */
        fun onNeedKeyframe()
    }
}

/** Somewhere else the session's video goes besides the screen: the mirror for web browsers. */
interface FrameSink {
    /** The coded size of the frames that follow. */
    fun configure(width: Int, height: Int)

    /** One Annex B access unit, in decoding order. Must not block. */
    fun frame(data: ByteArray, keyframe: Boolean)
}
