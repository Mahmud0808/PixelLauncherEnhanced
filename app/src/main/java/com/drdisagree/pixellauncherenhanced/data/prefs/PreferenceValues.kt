package com.drdisagree.pixellauncherenhanced.data.prefs

import android.content.SharedPreferences

object PreferenceValues {

    fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value as Set<String>)
        }
    }
}
