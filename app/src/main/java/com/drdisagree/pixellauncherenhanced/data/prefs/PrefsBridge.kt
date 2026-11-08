package com.drdisagree.pixellauncherenhanced.data.prefs

import android.net.Uri
import android.os.Bundle
import com.drdisagree.pixellauncherenhanced.BuildConfig

object PrefsBridge {

    const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.prefs"
    const val METHOD_WRITE = "write"
    const val METHOD_SYNC = "sync"

    private const val KEY_VALUES = "values"
    private const val KEY_REMOVED = "removed"

    val uri: Uri = Uri.parse("content://$AUTHORITY")

    fun encode(values: Map<String, Any?>, removed: Collection<String>): Bundle {
        val bundle = Bundle()
        bundle.putBundle(KEY_VALUES, Bundle().apply {
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
                }
            }
        })
        bundle.putStringArrayList(KEY_REMOVED, ArrayList(removed))
        return bundle
    }

    fun decodeValues(bundle: Bundle): Map<String, Any?> {
        val values = bundle.getBundle(KEY_VALUES) ?: return emptyMap()
        return values.keySet().associateWith { key ->
            @Suppress("DEPRECATION")
            when (val value = values.get(key)) {
                is ArrayList<*> -> value.filterIsInstance<String>().toSet()
                else -> value
            }
        }
    }

    fun decodeRemoved(bundle: Bundle): List<String> = bundle.getStringArrayList(KEY_REMOVED).orEmpty()
}
