package io.github.c4software.gliff.data.session

import io.github.c4software.gliff.data.Logger
import io.github.c4software.gliff.data.clipboard.ClipboardWriter
import io.github.c4software.gliff.data.video.FrameSink
import io.github.c4software.gliff.data.video.VideoDecoderFactory
import io.github.c4software.gliff.domain.machine.MachineRepository
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.domain.session.Viewport
import io.github.c4software.gliff.domain.settings.SettingsRepository
import io.github.c4software.gliff.domain.time.Clock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** The web mirror, as the sessions feed it. */
interface MirrorSink : FrameSink {
    /** Another stream from here on: another session, or none. */
    fun reset()
}

/**
 * The open sessions, one per machine. They are kept here and not in a screen,
 * so a window being resized, rotated or recreated never cuts a connection.
 * `scope` is the application's.
 *
 * [session], [endSession], [front] and [viewport] are for the main thread;
 * [frontSession] and [mirror] may be used from any.
 */
class Sessions(
    private val machines: MachineRepository,
    private val settings: SettingsRepository,
    private val connector: Connector,
    private val scope: CoroutineScope,
    /** Where the sessions connect, which blocks. */
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    /** Where text copied on a remote goes. Called in `scope`. */
    private val clipboard: ClipboardWriter = ClipboardWriter {},
    private val logger: Logger = Logger { _, _ -> },
    /** A decoder for a new session; closed with it. */
    private val decoders: VideoDecoderFactory,
) {
    private class Entry(val controller: SessionController, val tap: Tap)

    /** Replaced whole on each change, so that other threads read it as it is. */
    @Volatile
    private var entries: Map<String, Entry> = emptyMap()

    /** The machine whose window was last in front: the one the mirror shows. */
    @Volatile
    private var frontId: String? = null

    /** Where the video of the session in front also goes, while there is a mirror. */
    @Volatile
    var mirror: MirrorSink? = null
        set(value) {
            frontId?.let { entries[it] }?.tap?.let { value?.configure(it.width, it.height) }
            field = value
        }

    /**
     * The window of the browser watching the mirror, null while none does.
     * The session in front is sized for it instead of for its own window.
     */
    var viewport: Viewport? = null
        set(value) {
            field = value
            frontSession?.setViewer(value)
        }

    init {
        // A resolution imposed in the options, or on a machine, holds for the
        // sessions open and to come. Below what it reads: an immediate
        // dispatcher runs it here.
        scope.launch {
            combine(settings.settings, machines.machines) { options, _ -> options }.collect { options ->
                entries.values.forEach { it.controller.setForced(options.forcedFor(it.controller.machine)) }
            }
        }
    }

    /** The session in front, for what comes from outside its window. */
    val frontSession: SessionController? get() = frontId?.let { entries[it] }?.controller

    /** `machineId`'s window is the one in front. */
    fun front(machineId: String) {
        if (frontId == machineId) return
        frontSession?.setViewer(null)
        frontId = machineId
        val entry = entries[machineId]
        entry?.controller?.setViewer(viewport)
        mirror?.let { mirror ->
            mirror.reset()
            entry?.tap?.let { mirror.configure(it.width, it.height) }
        }
        entry?.controller?.requestKeyframe()
    }

    /** The session of a machine, started on first use. */
    fun session(machineId: String): SessionController {
        entries[machineId]?.let { return it.controller }
        val tap = Tap(machineId)
        if (frontId == null) frontId = machineId
        val controller = SessionController(
            machineId, machines, connector, decoders.create(), scope, io, clock, tap,
            onFailure = { logger.log("session failed", it) },
            clipboardOut = clipboard::write,
            clipboardOn = { settings.settings.value.clipboard },
        )
        entries = entries + (machineId to Entry(controller, tap))
        controller.setForced(settings.settings.value.forcedFor(controller.machine))
        if (frontId == machineId) controller.setViewer(viewport)
        controller.start()
        scope.launch {
            // Closed is its last state.
            controller.state.takeWhile { it != SessionState.Closed }.collect { state ->
                if (state is SessionState.Failed) logger.log("session failed: ${state.error}", null)
            }
        }
        return controller
    }

    /** The machine's window is gone for good. */
    fun endSession(machineId: String) {
        val entry = entries[machineId]
        entries = entries - machineId
        if (frontId == machineId) {
            frontId = null
            mirror?.reset()
        }
        entry?.controller?.let {
            it.close()
            it.decoder.close()
        }
    }

    /** One session's video, passed on to the mirror while that session is in front. */
    private inner class Tap(private val machineId: String) : FrameSink {
        @Volatile
        var width = 0

        @Volatile
        var height = 0

        override fun configure(width: Int, height: Int) {
            this.width = width
            this.height = height
            if (frontId == machineId) mirror?.configure(width, height)
        }

        override fun frame(data: ByteArray, keyframe: Boolean) {
            if (frontId == machineId) mirror?.frame(data, keyframe)
        }
    }
}
