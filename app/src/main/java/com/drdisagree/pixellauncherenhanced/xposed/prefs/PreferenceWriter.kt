package com.drdisagree.pixellauncherenhanced.xposed.prefs

import android.content.Context
import android.os.Bundle
import com.drdisagree.pixellauncherenhanced.data.prefs.PrefsBridge
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import java.util.concurrent.Executors

class PreferenceWriter(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    fun write(values: Map<String, Any?>, removed: Set<String>, async: Boolean): Boolean {
        if (values.isEmpty() && removed.isEmpty()) return true
        val extras = PrefsBridge.encode(values, removed)
        if (async) {
            executor.execute { call(PrefsBridge.METHOD_WRITE, extras) }
            return true
        }
        return call(PrefsBridge.METHOD_WRITE, extras)
    }

    fun requestSync(): Boolean = call(PrefsBridge.METHOD_SYNC, null)

    private fun call(method: String, extras: Bundle?): Boolean = try {
        context.contentResolver.call(PrefsBridge.uri, method, null, extras)
        true
    } catch (throwable: Throwable) {
        log(this, throwable)
        false
    }
}
