package io.github.c4software.gliff.data.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264 through `MediaCodec`, straight onto a `Surface`.
 *
 * The session feeds it from its reader thread whether or not there is a
 * surface: with none (the app is in the background, the view is not laid out
 * yet) frames are dropped and acked. A surface that appears starts a codec
 * and asks for a keyframe, since a decoder can only start on one.
 *
 * A frame's id rides as its presentation timestamp, so the output side knows
 * which frame it is releasing and acks exactly that one.
 */
class MediaCodecDecoder : VideoDecoder {
    override val maxWidth: Int
    override val maxHeight: Int

    @Volatile
    override var listener: VideoDecoder.Listener? = null

    private val thread = HandlerThread("gliff-decoder").apply { start() }
    private val handler = Handler(thread.looper)

    /** Guards the codec's lifecycle; the frame path only reads `codec`. */
    private val lock = Any()
    private var surface: Surface? = null
    private var size: Pair<Int, Int>? = null

    @Volatile
    private var codec: MediaCodec? = null

    /** Input buffers the current codec has offered. */
    private val inputs = LinkedBlockingQueue<Int>()

    /** Frames inside the codec, still owed an ack. */
    private val pending: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var waitingForKeyframe = true

    init {
        // What the device's AVC decoders accept, within what H.264 encoders produce.
        var width = 1920
        var height = 1080
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || MIME !in info.supportedTypes) continue
                val video = info.getCapabilitiesForType(MIME).videoCapabilities ?: continue
                width = maxOf(width, video.supportedWidths.upper)
                height = maxOf(height, video.supportedHeights.upper)
            }
        }
        // A phone held upright wants the long side vertical: offer a square.
        val side = minOf(maxOf(width, height), 4096)
        maxWidth = side
        maxHeight = side
    }

    /** The view's surface appeared, changed or (null) went away. Any thread. */
    fun setSurface(next: Surface?) {
        synchronized(lock) {
            if (next === surface) return
            surface = next
            restart()
        }
        if (next != null) listener?.onNeedKeyframe()
    }

    override fun configure(width: Int, height: Int) {
        synchronized(lock) {
            size = width to height
            // The server opens a new coded size with a keyframe by itself.
            restart()
        }
    }

    override fun decode(frameId: Long, data: ByteArray, keyframe: Boolean) {
        val codec = codec
        if (codec == null || (waitingForKeyframe && !keyframe)) {
            listener?.onFrameDone(frameId)
            return
        }
        try {
            // No free input buffer for this long means the codec is stuck.
            val index = inputs.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
            val buffer = index?.let { codec.getInputBuffer(it) }
            if (index == null || buffer == null || buffer.capacity() < data.size) {
                Log.w(TAG, "dropping frame $frameId: ${if (index == null) "no input buffer" else "too large"}")
                index?.let { inputs.add(it) }
                resync(frameId)
                return
            }
            buffer.clear()
            buffer.put(data)
            pending.add(frameId)
            codec.queueInputBuffer(index, 0, data.size, frameId, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            waitingForKeyframe = false
        } catch (e: IllegalStateException) {
            // Also MediaCodec.CodecException; or the codec was released under us.
            Log.w(TAG, "decode failed", e)
            pending.remove(frameId)
            resync(frameId)
            recover(codec)
        }
    }

    override fun close() {
        synchronized(lock) {
            surface = null
            size = null
            restart()
        }
        thread.quitSafely()
    }

    /** Give up on `frameId` and everything until the next keyframe. */
    private fun resync(frameId: Long) {
        waitingForKeyframe = true
        listener?.onFrameDone(frameId)
        listener?.onNeedKeyframe()
    }

    /** Replace a codec that failed, unless it has been replaced already. */
    private fun recover(failed: MediaCodec) {
        synchronized(lock) {
            if (codec === failed) restart()
        }
        listener?.onNeedKeyframe()
    }

    /** Release the codec and, when there is something to draw on, make a new one. */
    private fun restart() {
        codec?.let { old ->
            codec = null
            runCatching { old.stop() }
            runCatching { old.release() }
        }
        inputs.clear()
        waitingForKeyframe = true
        // Frames the old codec swallowed will never come out.
        val owed = pending.toList()
        pending.removeAll(owed.toSet())
        owed.forEach { listener?.onFrameDone(it) }

        val surface = surface?.takeIf { it.isValid } ?: return
        val (width, height) = size ?: return
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                // A keyframe of a busy screen is far above the default guess.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height * 3 / 2, 1 shl 20))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val created = MediaCodec.createDecoderByType(MIME)
            created.setCallback(Callback(created), handler)
            // No csd: SPS and PPS arrive in front of every keyframe.
            created.configure(format, surface, null, 0)
            created.start()
            codec = created
            Log.i(TAG, "decoder ${created.name} at ${width}x$height")
        } catch (e: Exception) {
            Log.e(TAG, "cannot start a decoder at ${width}x$height", e)
        }
    }

    private inner class Callback(private val owner: MediaCodec) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (this@MediaCodecDecoder.codec === owner) inputs.add(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val frameId = info.presentationTimeUs
            try {
                // Shown as soon as possible: the newest frame is the right one.
                codec.releaseOutputBuffer(index, info.size > 0)
            } catch (_: IllegalStateException) {
            }
            if (pending.remove(frameId)) listener?.onFrameDone(frameId)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "decoder error", e)
            recover(owner)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    private companion object {
        const val TAG = "gliff"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val INPUT_WAIT_MS = 400L
    }
}
