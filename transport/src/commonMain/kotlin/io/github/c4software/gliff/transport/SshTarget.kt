package io.github.c4software.gliff.transport

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

/**
 * Asking the remote for its keymap, on a channel of its own before the
 * session's. Every platform runs the same command under the same limits.
 */
object RemoteKeymap {
    /**
     * Prints the keymap Hyprland is configured with: its `input:kb_*` options,
     * compiled by `xkbcli`, which comes with the libxkbcommon Hyprland needs.
     */
    private val SCRIPT = """
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}(id -u)}"
: "${'$'}{HYPRLAND_INSTANCE_SIGNATURE:=${'$'}(ls -t "${'$'}XDG_RUNTIME_DIR/hypr" 2>/dev/null | head -n 1)}"
export HYPRLAND_INSTANCE_SIGNATURE
o() { hyprctl getoption "input:kb_${'$'}1" 2>/dev/null | sed -n "s/^str: //p" | head -n 1; }
set --
for k in rules model layout variant options; do v=${'$'}(o ${'$'}k); if [ -n "${'$'}v" ] && [ "${'$'}v" != "[[EMPTY]]" ]; then set -- "${'$'}@" "--${'$'}k" "${'$'}v"; fi; done
exec xkbcli compile-keymap "${'$'}@"
""".trim()

    /** The remote command line; its output is the keymap, as XKB text. */
    val command: String = "exec sh -c " + SshTarget.shellQuote(SCRIPT)

    /** Past either limit there is no keymap: the session runs on the US default. */
    const val TIMEOUT_MS = 6_000L
    const val MAX_BYTES = 512 * 1024
}
