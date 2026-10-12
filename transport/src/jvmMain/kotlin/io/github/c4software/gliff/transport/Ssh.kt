package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ByteSink
import io.github.c4software.gliff.proto.ByteSource
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlin.concurrent.thread
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session as SshSession
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/** How to reach a gliff server over ssh. */
data class SshTarget(
    val host: String,
    val user: String,
    val port: Int = 22,
    /**
     * Remote `gliff-server`. A shell fragment, as in the reference client, so
     * `env WAYLAND_DISPLAY=wayland-1 gliff-server` works; the default expects
     * the binary on `PATH`.
     */
    val serverBin: String = "gliff-server",
    /** Arguments after `--stdio`: `--headless`, or `--output NAME`. */
    val serverArgs: List<String> = emptyList(),
) {
    /**
     * The remote command line. It runs under `sh` whatever the login shell,
     * and fills in `XDG_RUNTIME_DIR` when the ssh server left it out: without
     * a PAM session (Tailscale SSH) it is unset, and the server then cannot
     * find the running Hyprland.
     */
    val command: String
        get() {
            val server = (listOf(serverBin, "--stdio") + serverArgs.map { shellQuote(it) }).joinToString(" ")
            return "exec sh -c " + shellQuote("$RUNTIME_DIR_DEFAULT; exec $server")
        }

    companion object {
        /** Parse `[user@]host[:port]`. */
        fun parse(spec: String, defaultUser: String): SshTarget {
            val user = spec.substringBefore('@', defaultUser)
            val rest = spec.substringAfter('@')
            val port = rest.substringAfterLast(':', "").toIntOrNull()
            val host = if (port != null) rest.substringBeforeLast(':') else rest
            require(host.isNotEmpty() && user.isNotEmpty()) { "expected [user@]host[:port], got \"$spec\"" }
            return SshTarget(host = host, user = user, port = port ?: 22)
        }

        private const val RUNTIME_DIR_DEFAULT =
            "export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\""

        internal fun shellQuote(arg: String): String =
            if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "-_./=:@," }) {
                arg
            } else {
                "'" + arg.replace("'", "'\\''") + "'"
            }
    }
}

/** A server's host key; `fingerprint` is what the user compares. */
data class HostKey(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val publicKey: PublicKey,
)

/** Decides whether a host key is the expected one (trust on first use). */
fun interface HostKeyCheck {
    fun accept(key: HostKey): Boolean
}

class HostKeyRejectedException(val key: HostKey) :
    TransportException("host key of ${key.host} not accepted: ${key.algorithm} ${key.fingerprint}")

object Ssh {
    /** The `SHA256:...` fingerprint OpenSSH prints for a key. */
    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** [connect] with a key held in memory. */
    fun connect(
        target: SshTarget,
        key: java.security.KeyPair,
        hostKeys: HostKeyCheck,
        log: (String) -> Unit = {},
    ): Transport = connect(target, { it.loadKeys(key) }, hostKeys, log = log)

    /**
     * [connect], trusting only the host key with fingerprint `trusted`
     * (trust on first use: `null` trusts none yet).
     *
     * @throws UnknownHostKeyException when `trusted` is null; it carries the
     *   key to show the user.
     * @throws ChangedHostKeyException when the server shows another key.
     */
    fun connectPinned(
        target: SshTarget,
        key: java.security.KeyPair,
        trusted: String?,
        log: (String) -> Unit = {},
    ): Transport = try {
        connect(target, key, { it.fingerprint == trusted }, log)
    } catch (e: HostKeyRejectedException) {
        val seen = HostKeyInfo(e.key.algorithm, e.key.fingerprint)
        throw if (trusted == null) UnknownHostKeyException(seen) else ChangedHostKeyException(trusted, seen)
    }

    /**
     * Connect, authenticate with `keys` and start `gliff-server --stdio`.
     * Blocking. Lines the server writes to stderr go to `log`.
     *
     * @throws HostKeyRejectedException when `hostKeys` refuses the server.
     * @throws TransportException when the host is unreachable, refuses the
     *   key, or cannot start the server.
     */
    fun connect(
        target: SshTarget,
        keys: (SSHClient) -> KeyProvider,
        hostKeys: HostKeyCheck,
        connectTimeoutMs: Int = 15_000,
        log: (String) -> Unit = {},
    ): Transport {
        SshKeys.installProvider()
        val client = SSHClient(DefaultConfig())
        var rejected: HostKey? = null
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val seen = HostKey(hostname, port, key.algorithm, fingerprint(key), key)
                return hostKeys.accept(seen).also { if (!it) rejected = seen }
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        client.connectTimeout = connectTimeoutMs
        try {
            try {
                client.connect(target.host, target.port)
            } catch (e: IOException) {
                throw rejected?.let { HostKeyRejectedException(it) } ?: e
            }
            // Input events are tiny and must not wait for a full segment.
            client.socket.tcpNoDelay = true
            client.connection.keepAlive.keepAliveInterval = 15
            client.authPublickey(target.user, keys(client))
            val keymap = remoteKeymap(client, log)
            val session = client.startSession()
            val command = session.exec(target.command)
            return SshTransport(client, session, command, keymap, log)
        } catch (e: Throwable) {
            runCatching { client.close() }
            throw if (e is IOException) TransportException(e.message ?: e.toString(), e) else e
        }
    }
}

/**
 * Prints the keymap Hyprland is configured with: its `input:kb_*` options,
 * compiled by `xkbcli`, which comes with the libxkbcommon Hyprland needs.
 */
private val KEYMAP_SCRIPT = """
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}(id -u)}"
: "${'$'}{HYPRLAND_INSTANCE_SIGNATURE:=${'$'}(ls -t "${'$'}XDG_RUNTIME_DIR/hypr" 2>/dev/null | head -n 1)}"
export HYPRLAND_INSTANCE_SIGNATURE
o() { hyprctl getoption "input:kb_${'$'}1" 2>/dev/null | sed -n "s/^str: //p" | head -n 1; }
set --
for k in rules model layout variant options; do v=${'$'}(o ${'$'}k); if [ -n "${'$'}v" ] && [ "${'$'}v" != "[[EMPTY]]" ]; then set -- "${'$'}@" "--${'$'}k" "${'$'}v"; fi; done
exec xkbcli compile-keymap "${'$'}@"
""".trim()

private const val KEYMAP_TIMEOUT_S = 6L
private const val KEYMAP_MAX_BYTES = 512 * 1024

/** The remote's keymap, or empty: a session without it still works, on the US default. */
private fun remoteKeymap(client: SSHClient, log: (String) -> Unit): String = try {
    client.startSession().use { session ->
        val command = session.exec("exec sh -c " + SshTarget.shellQuote(KEYMAP_SCRIPT))
        val out = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                val chunk = ByteArray(8192)
                while (out.size() < KEYMAP_MAX_BYTES) {
                    val n = command.inputStream.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
            }
        }.apply { isDaemon = true; start() }
        reader.join(KEYMAP_TIMEOUT_S * 1000)
        runCatching { command.close() }
        if (reader.isAlive || out.size() >= KEYMAP_MAX_BYTES) "" else out.toString(Charsets.UTF_8)
    }
} catch (e: IOException) {
    log("cannot read the remote keymap: ${e.message}")
    ""
}

private class SshTransport(
    private val client: SSHClient,
    private val session: SshSession,
    private val command: SshSession.Command,
    override val keymap: String,
    log: (String) -> Unit,
) : Transport {
    override val source: ByteSource = command.inputStream.asByteSource()
    override val sink: ByteSink = command.outputStream.asByteSink()

    init {
        // stderr shares the channel window with stdout: left unread, it
        // would stall the video once the server has logged enough.
        thread(name = "gliff-ssh-stderr", isDaemon = true) {
            runCatching { command.errorStream.bufferedReader().forEachLine(log) }
        }
    }

    override fun close() {
        runCatching { command.close() }
        runCatching { session.close() }
        runCatching { client.close() }
    }
}
