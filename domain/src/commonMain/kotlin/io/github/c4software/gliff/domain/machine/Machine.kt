package io.github.c4software.gliff.domain.machine

import kotlin.random.Random

/** A remote machine the user connects to. */
data class Machine(
    val id: String = newId(),
    val user: String,
    val host: String,
    val port: Int = 22,
    /** A private screen sized to the window, rather than a mirror. */
    val headless: Boolean = true,
    /** The output to mirror; blank for the focused one. */
    val output: String = "",
    /** Remote `gliff-server` path; the default expects it on `PATH`. */
    val serverBin: String = DEFAULT_SERVER_BIN,
    /**
     * This machine's own resolution, long side first: 0 follows the window,
     * and [INHERIT] takes what the options say for every machine.
     */
    val forcedWidth: Int = INHERIT,
    val forcedHeight: Int = INHERIT,
    /** Fingerprint of the host key trusted on first use. */
    val hostKey: String? = null,
) {
    val address: String get() = "$user@$host" + if (port != 22) ":$port" else ""

    /** What follows `gliff-server --stdio`, as the reference client passes it. */
    val serverArgs: List<String>
        get() = when {
            headless -> listOf("--headless")
            output.isNotBlank() -> listOf("--output", output.trim())
            else -> listOf("--output", "auto")
        }

    /**
     * This machine as the user edited it. Another host is another machine:
     * the key trusted for this one has to be seen again.
     */
    fun edited(
        user: String,
        host: String,
        port: Int,
        headless: Boolean,
        output: String,
        serverBin: String,
        forcedWidth: Int,
        forcedHeight: Int,
    ): Machine = copy(
        user = user,
        host = host,
        port = port,
        headless = headless,
        output = output.trim(),
        serverBin = serverBin.trim().ifEmpty { DEFAULT_SERVER_BIN },
        forcedWidth = forcedWidth,
        forcedHeight = forcedHeight,
        hostKey = if (this.host == host && this.port == port) hostKey else null,
    )

    companion object {
        const val DEFAULT_SERVER_BIN = "gliff-server"
        const val INHERIT = -1

        fun newId(): String = Random.nextLong().toULong().toString(16)

        /** Parse `user@host[:port]`; `null` when it is not one. */
        fun parseAddress(text: String): Triple<String, String, Int>? {
            val spec = text.trim()
            val at = spec.indexOf('@')
            if (at <= 0 || spec.any { it.isWhitespace() }) return null
            val user = spec.substring(0, at)
            var host = spec.substring(at + 1)
            var port = 22
            val colon = host.lastIndexOf(':')
            // A second colon means a bare IPv6 address, which has no port part.
            if (colon >= 0 && host.indexOf(':') == colon) {
                port = host.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                host = host.substring(0, colon)
            }
            if (host.isEmpty() || '@' in host) return null
            return Triple(user, host, port)
        }
    }
}
