package io.github.c4software.gliff.data.local

import platform.Foundation.NSUserDefaults

/** Small settings, in the app's user defaults. */
internal class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(PREFIX + key)

    override fun set(key: String, value: String) = defaults.setObject(value, forKey = PREFIX + key)

    private companion object {
        const val PREFIX = "gliff."
    }
}
