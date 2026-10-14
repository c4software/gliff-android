package io.github.c4software.gliff.data.session

import platform.Foundation.NSData

/**
 * The ssh client, written in Swift over swift-nio-ssh and handed to the
 * shared code by the host app. Nothing here blocks: a connection reports
 * through its [SshListener], from any thread.
 */
interface SshBridge {
    /**
     * The 32 bytes of this device's Ed25519 public key, made on first use and
     * kept in the keychain; empty when the keychain refused. Blocking.
     */
    fun publicKey(): NSData

    /**
     * Connect to `host`, authenticate as `user` with the device's key, read
     * the remote keymap with `keymapCommand` on a channel of its own (giving
     * up past `keymapTimeoutMs` or `keymapMaxBytes`), then run `command` and
     * carry its standard streams.
     */
    fun open(
        host: String,
        port: Int,
        user: String,
        command: String,
        keymapCommand: String,
        keymapTimeoutMs: Long,
        keymapMaxBytes: Int,
        listener: SshListener,
    ): SshChannel
}

/** What a connection tells the shared code. Called from the connection's own thread. */
interface SshListener {
    /**
     * The server's host key, as its ssh public key blob. Answered at once:
     * false ends the connection before anything is sent to the server.
     */
    fun hostKey(blob: NSData): Boolean

    /** `command` is running. `keymap` is the remote's, or empty when it could not be read. */
    fun opened(keymap: String)

    /**
     * Bytes of the command's standard output, in order. False asks for a
     * pause: nothing more comes until [SshChannel.resumeReading].
     */
    fun stdout(data: NSData): Boolean

    /** One line the command wrote to its standard error. */
    fun stderr(line: String)

    /** The end, once and last: `error` says why, or is null after [SshChannel.close]. */
    fun closed(error: String?)
}

/** The shared code's hold on a connection. Any thread. */
interface SshChannel {
    /** Send to the command's standard input. Never blocks. */
    fun write(data: NSData)

    /** Go on after an [SshListener.stdout] that answered false. */
    fun resumeReading()

    /** End the connection; safe to call more than once. */
    fun close()
}
