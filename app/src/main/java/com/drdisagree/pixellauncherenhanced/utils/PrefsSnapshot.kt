package com.drdisagree.pixellauncherenhanced.utils

import android.content.SharedPreferences
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import org.json.JSONArray
import org.json.JSONObject

object PrefsSnapshot {

    fun capture(include: (String) -> Boolean): JSONObject {
        val prefs = JSONObject()

        RPrefs.getPrefs.all.forEach { (key, value) ->
            if (key == null || value == null || !include(key)) return@forEach

            val (type, json) = when (value) {
                is Boolean -> TYPE_BOOLEAN to value
                is Int -> TYPE_INT to value
                is Long -> TYPE_LONG to value
                is Float -> TYPE_FLOAT to value.toDouble()
                is String -> TYPE_STRING to value
                is Set<*> -> TYPE_STRING_SET to JSONArray(value.filterIsInstance<String>())
                else -> return@forEach
            }

            prefs.put(key, JSONObject().put(KEY_TYPE, type).put(KEY_VALUE, json))
        }

        return prefs
    }

    fun apply(editor: SharedPreferences.Editor, prefs: JSONObject, include: (String) -> Boolean) {
        RPrefs.getPrefs.all.keys
            .filter { include(it) }
            .forEach { editor.remove(it) }

        prefs.keys().forEach { key ->
            if (!include(key)) return@forEach

            val entry = prefs.optJSONObject(key) ?: return@forEach

            runCatching {
                when (entry.getString(KEY_TYPE)) {
                    TYPE_BOOLEAN -> editor.putBoolean(key, entry.getBoolean(KEY_VALUE))
                    TYPE_INT -> editor.putInt(key, entry.getInt(KEY_VALUE))
                    TYPE_LONG -> editor.putLong(key, entry.getLong(KEY_VALUE))
                    TYPE_FLOAT -> editor.putFloat(key, entry.getDouble(KEY_VALUE).toFloat())
                    TYPE_STRING -> editor.putString(
                        key,
                        entry.getString(KEY_VALUE).let {
                            if (key in PanelBackground.KEYS) PanelBackground.supported(it) else it
                        }
                    )

                    TYPE_STRING_SET -> {
                        val array = entry.getJSONArray(KEY_VALUE)
                        editor.putStringSet(key, (0 until array.length()).mapTo(HashSet()) { array.getString(it) })
                    }
                }
            }
        }
    }

    fun boolean(prefs: JSONObject, key: String): Boolean? =
        prefs.optJSONObject(key)?.takeIf { it.optString(KEY_TYPE) == TYPE_BOOLEAN }?.optBoolean(KEY_VALUE)

    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"

    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "stringSet"
}
