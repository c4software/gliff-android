package io.github.c4software.gliff.data.local

/** Where small settings live: preferences on Android, user defaults on iOS. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}
