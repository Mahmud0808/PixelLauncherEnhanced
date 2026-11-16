package com.drdisagree.pixellauncherenhanced.xposed.prefs

import android.content.SharedPreferences
import com.drdisagree.pixellauncherenhanced.ui.preferences.SliderPreference
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class ModulePreferences(
    private val remote: SharedPreferences,
    private val writer: PreferenceWriter
) : SharedPreferences {

    private val pending = ConcurrentHashMap<String, Optional<Any>>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    private val remoteListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) pending.clear() else pending.remove(key)
        listeners.forEach { it.onSharedPreferenceChanged(this, key) }
    }

    init {
        remote.registerOnSharedPreferenceChangeListener(remoteListener)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String?, fallback: T, reader: (SharedPreferences) -> T): T {
        if (key == null) return fallback
        val override = pending[key] ?: return reader(remote)
        return (override.orElse(null) as? T) ?: fallback
    }

    override fun getAll(): Map<String, *> {
        val merged = HashMap<String, Any?>(remote.all)
        pending.forEach { (key, value) -> if (value.isPresent) merged[key] = value.get() else merged.remove(key) }
        return merged
    }

    override fun getString(key: String?, defValue: String?): String? = read(key, defValue) { it.getString(key, defValue) }

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        read(key, defValues) { it.getStringSet(key, defValues) }

    override fun getInt(key: String?, defValue: Int): Int = read(key, defValue) { it.getInt(key, defValue) }

    override fun getLong(key: String?, defValue: Long): Long = read(key, defValue) { it.getLong(key, defValue) }

    override fun getFloat(key: String?, defValue: Float): Float = read(key, defValue) { it.getFloat(key, defValue) }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read(key, defValue) { it.getBoolean(key, defValue) }

    override fun contains(key: String?): Boolean {
        if (key == null) return false
        return pending[key]?.isPresent ?: remote.contains(key)
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    fun getBoolean(key: String?): Boolean = getBoolean(key, false)

    fun getListString(key: String?, defaultValue: String): String? = getString(key, defaultValue)

    fun getSliderInt(key: String?, defaultVal: Int): Int = SliderPreference.getSingleIntValue(this, key, defaultVal)

    fun getSliderFloat(key: String?, defaultVal: Float): Float = SliderPreference.getSingleFloatValue(this, key, defaultVal)

    fun getSliderValues(key: String?, defaultValue: Float): List<Float> = SliderPreference.getValues(this, key, defaultValue)

    private inner class Editor : SharedPreferences.Editor {

        private val values = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        private fun set(key: String?, value: Any?): SharedPreferences.Editor {
            if (key == null) return this
            if (value == null) return remove(key)
            removed.remove(key)
            values[key] = value
            return this
        }

        override fun putString(key: String?, value: String?) = set(key, value)

        override fun putStringSet(key: String?, values: Set<String>?) = set(key, values?.toSet())

        override fun putInt(key: String?, value: Int) = set(key, value)

        override fun putLong(key: String?, value: Long) = set(key, value)

        override fun putFloat(key: String?, value: Float) = set(key, value)

        override fun putBoolean(key: String?, value: Boolean) = set(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key == null) return this
            values.remove(key)
            removed.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean = submit(async = false)

        override fun apply() {
            submit(async = true)
        }

        private fun submit(async: Boolean): Boolean {
            val removals = if (clear) removed + getAll().keys.filterNot { it in values } else removed
            removals.forEach { pending[it] = Optional.empty() }
            values.forEach { (key, value) -> pending[key] = Optional.ofNullable(value) }
            return writer.write(values.toMap(), removals.toSet(), async)
        }
    }
}
