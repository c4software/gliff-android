package io.github.c4software.gliff.data.local

import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.machine.MachineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The saved machines, as one JSON list in the store. */
class StoredMachineRepository(private val store: KeyValueStore) : MachineRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    override val machines: StateFlow<List<Machine>> get() = state

    override fun find(id: String): Machine? = state.value.firstOrNull { it.id == id }

    override fun save(machine: Machine) = change { all ->
        if (all.any { it.id == machine.id }) all.map { if (it.id == machine.id) machine else it } else all + machine
    }

    override fun remove(id: String) = change { all -> all.filterNot { it.id == id } }

    private fun change(edit: (List<Machine>) -> List<Machine>) {
        state.update(edit)
        store.set(KEY, json.encodeToString(state.value.map(::MachineRecord)))
    }

    private fun load(): List<Machine> =
        store.get(KEY)?.let { text ->
            runCatching { json.decodeFromString<List<MachineRecord>>(text).map(MachineRecord::toMachine) }.getOrNull()
        } ?: emptyList()

    private companion object {
        const val KEY = "machines"
    }
}

/**
 * A machine as it is stored. Its names and defaults are those of the
 * machines saved by earlier versions: changing one loses what they saved.
 */
@Serializable
internal data class MachineRecord(
    val id: String = Machine.newId(),
    val user: String,
    val host: String,
    val port: Int = 22,
    val headless: Boolean = true,
    val output: String = "",
    val serverBin: String = Machine.DEFAULT_SERVER_BIN,
    val forcedWidth: Int = Machine.INHERIT,
    val forcedHeight: Int = Machine.INHERIT,
    val hostKey: String? = null,
) {
    constructor(machine: Machine) : this(
        machine.id, machine.user, machine.host, machine.port, machine.headless, machine.output,
        machine.serverBin, machine.forcedWidth, machine.forcedHeight, machine.hostKey,
    )

    fun toMachine() = Machine(id, user, host, port, headless, output, serverBin, forcedWidth, forcedHeight, hostKey)
}
