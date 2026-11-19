package com.drdisagree.pixellauncherenhanced.xposed.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PREF_UPDATE_EXCLUSIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHARED_PREFERENCES
import com.drdisagree.pixellauncherenhanced.data.prefs.PrefsBridge
import com.drdisagree.pixellauncherenhanced.xposed.core.ModLoader
import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import java.util.concurrent.Executors

object XPrefs {

    lateinit var Xprefs: ModulePreferences
        private set

    private lateinit var writer: PreferenceWriter
    private lateinit var snapshot: PrefsSnapshot

    private val listener = OnSharedPreferenceChangeListener { _, key -> onChanged(key) }

    private val refreshExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var bulkRefreshing = false

    private val changeObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshExecutor.execute { refresh(uri) }
        }
    }

    val isInitialized: Boolean
        get() = ::Xprefs.isInitialized

    fun init(context: Context) {
        if (isInitialized) return
        writer = PreferenceWriter(context)
        snapshot = PrefsSnapshot(context)
        Xprefs = ModulePreferences(ModuleRuntime.module.getRemotePreferences(SHARED_PREFERENCES), writer)
        Xprefs.registerOnSharedPreferenceChangeListener(listener)
        ModuleRuntime.onDispose { Xprefs.unregisterOnSharedPreferenceChangeListener(listener) }
        observeChanges(context)
    }

    private fun observeChanges(context: Context) {
        val resolver = context.contentResolver
        runCatching {
            resolver.registerContentObserver(PrefsBridge.changesUri, true, changeObserver)
            ModuleRuntime.onDispose { runCatching { resolver.unregisterContentObserver(changeObserver) } }
        }.onFailure { log(this, it) }
    }

    private fun refresh(uri: Uri?) {
        val key = PrefsBridge.changedKey(uri)
        val result = writer.read(key) ?: return
        val values = PrefsBridge.decodeValues(result)
        if (key == null && values.isEmpty()) return
        val removed = if (key == null) Xprefs.all.keys - values.keys else PrefsBridge.decodeRemoved(result)
        bulkRefreshing = key == null
        try {
            Xprefs.applyExternal(values, removed)
        } finally {
            bulkRefreshing = false
        }
        if (key == null) snapshot.replace(values) else snapshot.update(values, removed)
    }

    fun awaitSeed() {
        val seeded = seedFromSnapshot()
        refreshExecutor.execute { refresh(null) }
        if (seeded || Xprefs.all.isNotEmpty()) return
        writer.requestSync()
        val deadline = SystemClock.elapsedRealtime() + SEED_TIMEOUT_MS
        while (Xprefs.all.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SEED_POLL_MS)
        }
    }

    private fun seedFromSnapshot(): Boolean {
        val values = snapshot.load()
        if (values.isEmpty()) return false
        Xprefs.seed(values, Xprefs.all.keys - values.keys)
        return true
    }

    private fun onChanged(key: String?) {
        if (key == DEBUG_REPORT_REQUEST) {
            if (bulkRefreshing) return
            Xprefs.getString(DEBUG_REPORT_REQUEST, null)?.let { DebugRecorder.respond(it) }
            return
        }
        if (key.isNullOrEmpty() ||
            PREF_UPDATE_EXCLUSIONS.any { it == key } ||
            key.startsWith(BootLoopProtector.LOAD_TIME_KEY_KEY) ||
            key.startsWith(BootLoopProtector.PACKAGE_STRIKE_KEY_KEY)
        ) return

        ModLoader.dispatchPrefsChanged(key)
    }

    private const val SEED_TIMEOUT_MS = 3000L
    private const val SEED_POLL_MS = 100L
}
