package io.github.c4software.gliff.data.session

import io.github.c4software.gliff.data.security.AndroidIdentity
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.transport.Ssh
import io.github.c4software.gliff.transport.SshTarget
import io.github.c4software.gliff.transport.Transport

/** ssh with sshj, signing with the device's key. */
class SshConnector(private val identity: AndroidIdentity) : Connector {
    override fun connect(machine: Machine): Transport = Ssh.connectPinned(
        SshTarget(machine.host, machine.user, machine.port, machine.serverBin, machine.serverArgs),
        identity.keyPair(),
        machine.hostKey,
        log = { android.util.Log.i("gliff-server", it) },
    )
}
