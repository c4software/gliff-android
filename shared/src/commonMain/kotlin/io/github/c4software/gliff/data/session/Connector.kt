package io.github.c4software.gliff.data.session

import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.transport.Transport

/**
 * Opens the ssh connection to a machine and starts `gliff-server --stdio`
 * there. Blocking.
 *
 * Trust is on first use: with no `Machine.hostKey` it throws
 * `UnknownHostKeyException`, with another key than the stored one
 * `ChangedHostKeyException`; any other failure is a `TransportException`.
 */
fun interface Connector {
    fun connect(machine: Machine): Transport
}
