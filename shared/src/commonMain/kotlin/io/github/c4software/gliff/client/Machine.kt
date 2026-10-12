package io.github.c4software.gliff.client

import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A remote machine the user connects to. */
@Serializable
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

    companion object {
        const val DEFAULT_SERVER_BIN = "gliff-server"

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

/** Where small settings live: preferences on Android, user defaults on iOS. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/** The saved machines. */
class MachineRepository(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    val machines: StateFlow<List<Machine>> get() = state

    fun find(id: String): Machine? = state.value.firstOrNull { it.id == id }

    /** Add `machine`, or replace the one with its id. */
    fun save(machine: Machine) = change { all ->
        if (all.any { it.id == machine.id }) all.map { if (it.id == machine.id) machine else it } else all + machine
    }

    fun remove(id: String) = change { all -> all.filterNot { it.id == id } }

    private fun change(edit: (List<Machine>) -> List<Machine>) {
        state.update(edit)
        store.set(KEY, json.encodeToString(state.value))
    }

    private fun load(): List<Machine> =
        store.get(KEY)?.let { runCatching { json.decodeFromString<List<Machine>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val KEY = "machines"
    }
}
