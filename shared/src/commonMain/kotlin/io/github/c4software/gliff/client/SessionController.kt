package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientCaps
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.ClipboardMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.GliffException
import io.github.c4software.gliff.proto.ProtocolException
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.VideoPipeline
import io.github.c4software.gliff.transport.ChangedHostKeyException
import io.github.c4software.gliff.transport.HostKeyInfo
import io.github.c4software.gliff.transport.IncompatibleException
import io.github.c4software.gliff.transport.Session
import io.github.c4software.gliff.transport.SessionHandler
import io.github.c4software.gliff.transport.UnknownHostKeyException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The stream as the server last configured it. */
data class StreamInfo(
    /** Coded size in physical pixels. */
    val width: Int,
    val height: Int,
    /** The box the stream is stretched to before fitting it to the window. */
    val viewWidth: Int,
    val viewHeight: Int,
    /** Stream pixels per logical pixel of the remote, x1000. */
    val scaleMilli: Int,
    val fpsCap: Int,
    val pipeline: VideoPipeline?,
    val headless: Boolean,
    val output: String,
)

sealed interface SessionState {
    data object Connecting : SessionState

    /**
     * The user has to confirm the server's key. `previous` is the fingerprint
     * trusted until now when the key changed, which deserves a warning.
     */
    data class HostKeyPrompt(val key: HostKeyInfo, val previous: String?) : SessionState

    data class Streaming(val stream: StreamInfo) : SessionState

    /** `incompatible`: the two ends cannot work together, retrying is pointless. */
    data class Failed(val message: String, val incompatible: Boolean) : SessionState

    /** Ended by the user. */
    data object Closed : SessionState
}

/**
 * One machine's session, from connecting to closing. It outlives the screen
 * that shows it: a rotation or a window resize must not cut the connection.
 * `scope` is therefore the application's, not a screen's.
 */
class SessionController(
    private val machineId: String,
    private val machines: MachineRepository,
    private val connector: Connector,
    val decoder: VideoDecoder,
    private val scope: CoroutineScope,
    private val mirror: FrameSink? = null,
    /** Told of every failure with its cause, for the platform's log. */
    private val onFailure: (Throwable) -> Unit = {},
    /** Put text copied on the remote on this device's clipboard. Called in `scope`. */
    private val clipboardOut: (String) -> Unit = {},
    /** Whether the clipboard is shared at all; asked each time. */
    private val clipboardOn: () -> Boolean = { true },
    /** Wall clock in milliseconds, for frame acks. */
    private val nowMs: () -> Long,
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> get() = mutableState

    val machine: Machine? get() = machines.find(machineId)

    private var job: Job? = null
    private var resizeJob: Job? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var closed = false

    /** The window as last reported, and what the server was last asked for. */
    @Volatile
    private var window: ClientMsg.Resize? = null

    @Volatile
    private var requested: ClientMsg.Resize? = null

    @Volatile
    private var lastKeyframeRequestMs = 0L

    /** Connect, or connect again after a failure. */
    fun start() {
        if (closed) return
        job?.cancel()
        mutableState.value = SessionState.Connecting
        job = scope.launch { mutableState.value = runOnce() }
    }

    /** Trust the key of the pending [SessionState.HostKeyPrompt] and connect. */
    fun acceptHostKey() {
        val prompt = mutableState.value as? SessionState.HostKeyPrompt ?: return
        val machine = machine ?: return
        machines.save(machine.copy(hostKey = prompt.key.fingerprint))
        start()
    }

    /** End the session for good. */
    fun close() {
        if (closed) return
        closed = true
        resizeJob?.cancel()
        // A live session says Bye and ends by itself; anything else is cut.
        session?.close() ?: job?.cancel()
        mutableState.value = SessionState.Closed
    }

    /**
     * The video area changed: `width x height` physical pixels at display
     * scale `scale`. The server is asked to follow, once the size has been
     * still for a moment: a window edge being dragged reports dozens of sizes.
     */
    fun setWindowSize(width: Int, height: Int, scale: Float) {
        // The reference client stays silent under 64 px, and so do we.
        if (width < MIN_WINDOW || height < MIN_WINDOW || closed) return
        val mode = remoteMode(width, height, scale)
        val size = ClientMsg.Resize(mode.width, mode.height, mode.scale)
        if (size == window) return
        window = size
        resizeJob?.cancel()
        resizeJob = scope.launch {
            // The first size goes at once, so the stream starts at the right one.
            if (requested != null) delay(RESIZE_DEBOUNCE_MS)
            sendResize()
        }
    }

    /** Pointer and key events; dropped while there is no session to take them. */
    fun send(msg: ClientMsg) {
        session?.send(msg)
    }

    /**
     * Clipboard work, done one piece at a time in `scope`: the engine is
     * fed from the session's reader thread and from the screen.
     */
    private val clipboardWork = Channel<() -> Unit>(Channel.UNLIMITED)
    private val clipboard = ClipboardSync(
        send = { msg, payload -> session?.send(ClientMsg.Clipboard(msg), payload) },
        onRemoteText = { if (clipboardOn()) clipboardOut(it) },
    )

    init {
        scope.launch { for (work in clipboardWork) work() }
    }

    /** This device's clipboard holds `text` (null: no text). Any thread. */
    fun localClipboard(text: String?) {
        if (clipboardOn()) clipboardWork.trySend { clipboard.localText(text) }
    }

    /** The layout the session types with: the remote's own when it could be read. */
    @Volatile
    var layout: KeyLayout = KeyLayout.US
        private set

    private val browser = BrowserInput({ layout }, ::send)

    /** Events from the web mirror's page, see [BrowserInput]. Any thread. */
    fun browserInput(text: String) {
        val stream = (mutableState.value as? SessionState.Streaming)?.stream ?: return
        val scale = maxOf(stream.scaleMilli, 10) / 1000.0
        browser.apply(text, stream.width / scale, stream.height / scale)
    }

    /** The video surface is back (or new): the decoder needs a keyframe to draw on it. */
    fun requestKeyframe() {
        val now = nowMs()
        if (now - lastKeyframeRequestMs < KEYFRAME_REQUEST_GAP_MS) return
        lastKeyframeRequestMs = now
        session?.send(ClientMsg.RequestKeyframe)
    }

    private fun sendResize() {
        val size = window ?: return
        val session = session ?: return
        if (size == requested) return
        requested = size
        session.send(size)
    }

    private suspend fun runOnce(): SessionState {
        val machine = machine ?: return SessionState.Failed("This machine was removed", incompatible = true)
        try {
            val transport = withContext(Dispatchers.IO) { connector.connect(machine) }
            val session = Session(
                transport,
                ClientCaps(
                    codecs = listOf(Codec.H264),
                    maxWidth = decoder.maxWidth,
                    maxHeight = decoder.maxHeight,
                    // One 4:2:0 stream; Dual420 waits for the two-decoder path.
                    chroma = listOf(ChromaMode.Single420),
                ),
                Handler(),
                keymap = KeyLayout.parse(transport.keymap).also { layout = it ?: KeyLayout.US }?.text.orEmpty(),
            )
            requested = null
            this.session = session
            // Queued now, the offer of what the clipboard holds leaves behind the handshake.
            clipboardWork.trySend { clipboard.connected() }
            decoder.listener = object : VideoDecoder.Listener {
                override fun onFrameDone(frameId: Long) = session.send(ClientMsg.FrameAck(frameId, nowMs()))
                override fun onNeedKeyframe() = requestKeyframe()
            }
            if (closed) session.close()
            // Queued now, it leaves right behind the handshake's Pong.
            sendResize()
            try {
                session.run()
            } finally {
                this.session = null
            }
            return if (closed) SessionState.Closed else SessionState.Failed("The server closed the connection", false)
        } catch (e: UnknownHostKeyException) {
            return SessionState.HostKeyPrompt(e.key, previous = null)
        } catch (e: ChangedHostKeyException) {
            return SessionState.HostKeyPrompt(e.key, previous = e.expected)
        } catch (e: IncompatibleException) {
            return SessionState.Failed(e.message ?: "Incompatible server", incompatible = true)
        } catch (e: GliffException) {
            if (!closed) onFailure(e)
            return if (closed) SessionState.Closed else SessionState.Failed(e.message ?: e.toString(), false)
        }
    }

    /** Runs on the session's reader thread. */
    private inner class Handler : SessionHandler {
        private var headless = false
        private var output = ""
        private var coded: Pair<Int, Int>? = null

        override fun onHello(ack: ServerMsg.HelloAck) {
            headless = ack.session.headless
            output = ack.session.output
        }

        override fun onStreamConfig(config: ServerMsg.StreamConfig) {
            if (config.codec != Codec.H264 || config.chroma != ChromaMode.Single420) {
                throw ProtocolException("the server chose ${config.codec} ${config.chroma}, which was not offered")
            }
            // Only a new coded size resets the decoder: a change of pace or
            // of view box must not cost a keyframe.
            val size = config.width to config.height
            if (size != coded) {
                coded = size
                decoder.configure(config.width, config.height)
                mirror?.configure(config.width, config.height)
            }
            mutableState.value = SessionState.Streaming(
                StreamInfo(
                    config.width, config.height, config.viewWidth, config.viewHeight,
                    config.scaleMilli, config.fpsCap, config.pipeline, headless, output,
                )
            )
        }

        override fun onClipboard(msg: ClipboardMsg, payload: ByteArray?) {
            if (clipboardOn()) clipboardWork.trySend { clipboard.onMessage(msg, payload) }
        }

        override fun onVideoFrame(frame: ServerMsg.VideoFrame, main: ByteArray, aux: ByteArray?) {
            // Without it a decoder holds frames back, waiting for a reordering that never comes.
            val unit = if (frame.keyframe) H264.withoutReordering(main) else main
            mirror?.frame(unit, frame.keyframe)
            decoder.decode(frame.frameId, unit, frame.keyframe)
        }
    }

    companion object {
        const val RESIZE_DEBOUNCE_MS = 150L
        const val MIN_WINDOW = 64
        const val KEYFRAME_REQUEST_GAP_MS = 250L
    }
}
