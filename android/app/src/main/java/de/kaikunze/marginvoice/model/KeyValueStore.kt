package de.kaikunze.marginvoice.model

import android.content.SharedPreferences

/** The preference reads and writes the model needs; in-memory in tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getDouble(key: String, default: Double): Double
    fun putDouble(key: String, value: Double)
}

class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()
    override fun getString(key: String) = values[key] as? String
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getDouble(key: String, default: Double) = values[key] as? Double ?: default
    override fun putDouble(key: String, value: Double) { values[key] = value }
}

class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    // SharedPreferences has no double; the bits go in a long
    override fun getDouble(key: String, default: Double) =
        if (prefs.contains(key)) Double.fromBits(prefs.getLong(key, 0)) else default
    override fun putDouble(key: String, value: Double) = prefs.edit().putLong(key, value.toRawBits()).apply()
}
