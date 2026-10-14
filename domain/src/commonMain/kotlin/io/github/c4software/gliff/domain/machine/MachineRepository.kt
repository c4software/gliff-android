package io.github.c4software.gliff.domain.machine

import kotlinx.coroutines.flow.StateFlow

/** The saved machines. */
interface MachineRepository {
    val machines: StateFlow<List<Machine>>

    fun find(id: String): Machine?

    /** Add `machine`, or replace the one with its id. */
    fun save(machine: Machine)

    fun remove(id: String)
}
