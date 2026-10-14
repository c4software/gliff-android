package io.github.c4software.gliff.data.security

import io.github.c4software.gliff.data.session.SshBridge
import io.github.c4software.gliff.domain.identity.Identity
import io.github.c4software.gliff.ios.toByteArray
import io.github.c4software.gliff.transport.SshWire
import platform.UIKit.UIDevice

/**
 * The device's Ed25519 key. The Swift side makes it and keeps it in the
 * keychain, for this device only: a restored backup must not carry it to
 * another device.
 */
class IosIdentity(private val bridge: SshBridge) : Identity {
    override fun authorizedKey(): String {
        val key = bridge.publicKey().toByteArray()
        if (key.size != 32) return "The keychain did not give this device's ssh key."
        return SshWire.authorizedKey(key, "gliff@" + UIDevice.currentDevice.model.replace(Regex("\\s+"), "-"))
    }
}
