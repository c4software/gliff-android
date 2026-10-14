package io.github.c4software.gliff.data.local

import android.content.Context

/** Small settings, in the app's shared preferences. */
class PreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("gliff", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
}
