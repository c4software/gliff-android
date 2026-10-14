package io.github.c4software.gliff.domain.session

import io.github.c4software.gliff.proto.VideoPipeline

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

/** A host key as the user is shown it. */
data class HostKeyInfo(val algorithm: String, val fingerprint: String)

sealed interface SessionState {
    data object Connecting : SessionState

    /**
     * The user has to confirm the server's key. `previous` is the fingerprint
     * trusted until now when the key changed, which deserves a warning.
     */
    data class HostKeyPrompt(val key: HostKeyInfo, val previous: String?) : SessionState

    data class Streaming(val stream: StreamInfo) : SessionState

    data class Failed(val error: SessionError) : SessionState

    /** Ended by the user. */
    data object Closed : SessionState
}

/**
 * Why a session stopped. Each cause has its own message on screen; `detail`
 * is what the other end or the network said, shown as it is for diagnosis.
 */
sealed interface SessionError {
    /** Retrying is pointless: nothing on this side will change the outcome. */
    val incompatible: Boolean

    /** The machine was deleted while its session was starting. */
    data object MachineRemoved : SessionError {
        override val incompatible get() = true
    }

    /** The server ended the session by itself. */
    data object ServerClosed : SessionError {
        override val incompatible get() = false
    }

    /** The two ends do not speak the same version of the protocol. */
    data class Incompatible(val detail: String) : SessionError {
        override val incompatible get() = true
    }

    /** The connection could not be made, or broke. */
    data class Connection(val detail: String) : SessionError {
        override val incompatible get() = false
    }
}
