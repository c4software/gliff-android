package io.github.c4software.gliff.data.video

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

/** Makes the decoder of a new session; the session closes it. */
fun interface VideoDecoderFactory {
    fun create(): VideoDecoder
}

/** Somewhere else the session's video goes besides the screen: the mirror for web browsers. */
interface FrameSink {
    /** The coded size of the frames that follow. */
    fun configure(width: Int, height: Int)

    /** One Annex B access unit, in decoding order. Must not block. */
    fun frame(data: ByteArray, keyframe: Boolean)
}
