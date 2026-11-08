package com.drdisagree.pixellauncherenhanced.data.prefs

import android.content.SharedPreferences
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHARED_PREFERENCES
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import io.github.libxposed.service.XposedService

object RemotePrefsSync {

    @Volatile
    private var remote: SharedPreferences? = null

    private val localListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null) syncAll() else mirror(prefs, key)
    }

    fun attach(service: XposedService) {
        remote = service.getRemotePreferences(SHARED_PREFERENCES)
        syncAll()
        RPrefs.getPrefs.registerOnSharedPreferenceChangeListener(localListener)
    }

    fun detach() {
        RPrefs.getPrefs.unregisterOnSharedPreferenceChangeListener(localListener)
        remote = null
    }

    @Synchronized
    fun syncAll() {
        val target = remote ?: return
        val local = RPrefs.getPrefs.all
        val current = target.all
        val editor = target.edit()
        current.keys.filterNot { it in local }.forEach { editor.remove(it) }
        local.forEach { (key, value) ->
            if (current[key] != value) PreferenceValues.put(editor, key, value)
        }
        editor.apply()
    }

    private fun mirror(prefs: SharedPreferences, key: String) {
        val target = remote ?: return
        val editor = target.edit()
        PreferenceValues.put(editor, key, prefs.all[key])
        editor.apply()
    }
}
