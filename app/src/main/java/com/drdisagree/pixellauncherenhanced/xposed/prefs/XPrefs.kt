package com.drdisagree.pixellauncherenhanced.xposed.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.SystemClock
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PREF_UPDATE_EXCLUSIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHARED_PREFERENCES
import com.drdisagree.pixellauncherenhanced.xposed.core.ModLoader
import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder

object XPrefs {

    lateinit var Xprefs: ModulePreferences
        private set

    private lateinit var writer: PreferenceWriter

    private val listener = OnSharedPreferenceChangeListener { _, key -> onChanged(key) }

    val isInitialized: Boolean
        get() = ::Xprefs.isInitialized

    fun init(context: Context) {
        if (isInitialized) return
        writer = PreferenceWriter(context)
        Xprefs = ModulePreferences(ModuleRuntime.module.getRemotePreferences(SHARED_PREFERENCES), writer)
        Xprefs.registerOnSharedPreferenceChangeListener(listener)
        ModuleRuntime.onDispose { Xprefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun awaitSeed() {
        if (Xprefs.all.isNotEmpty()) return
        writer.requestSync()
        val deadline = SystemClock.elapsedRealtime() + SEED_TIMEOUT_MS
        while (Xprefs.all.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SEED_POLL_MS)
        }
    }

    private fun onChanged(key: String?) {
        if (key == DEBUG_REPORT_REQUEST) {
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
