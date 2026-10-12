package io.github.c4software.gliff.ios

import io.github.c4software.gliff.client.Identity
import io.github.c4software.gliff.client.KeyValueStore
import io.github.c4software.gliff.transport.SshWire
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard

/** Small settings, in the app's user defaults. */
internal class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(PREFIX + key)

    override fun set(key: String, value: String) = defaults.setObject(value, forKey = PREFIX + key)

    private companion object {
        const val PREFIX = "gliff."
    }
}

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

/**
 * The device's clipboard, text only. Reading what another app copied makes
 * iOS ask the user, or tell them, each time: so only a change is read, and
 * only once it is known to be text. Main thread.
 */
class IosClipboard {
    private val pasteboard get() = UIPasteboard.generalPasteboard

    /** What the clipboard holds: its change count, and its text (null when it holds none). */
    class Content(val change: Long, val text: String?)

    /** The clipboard's content when it is not the one of `seen`; null when nothing changed. */
    fun read(seen: Long): Content? {
        val change = pasteboard.changeCount
        if (change == seen) return null
        if (!pasteboard.hasStrings) return Content(change, null)
        return Content(change, pasteboard.string)
    }

    /** Text copied on the remote. */
    fun write(text: String) {
        pasteboard.string = text
    }
}
