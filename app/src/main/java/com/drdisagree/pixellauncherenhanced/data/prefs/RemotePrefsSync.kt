package com.drdisagree.pixellauncherenhanced.data.prefs

import android.content.SharedPreferences
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_RESPONSE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHARED_PREFERENCES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHORTCUTS_RESPONSE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_AUTO_CHECK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_CACHE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_LAST_CHECK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_NOTIFICATION_ASKED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_NOTIFIED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_NOTIFY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_SKIPPED
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import io.github.libxposed.service.XposedService
import java.util.concurrent.Executors

object RemotePrefsSync {

    private const val CHUNK_SIZE = 25

    private val localOnlyKeys = setOf(
        DEBUG_REPORT_RESPONSE,
        SHORTCUTS_RESPONSE,
        UPDATE_AUTO_CHECK,
        UPDATE_CACHE,
        UPDATE_LAST_CHECK,
        UPDATE_SKIPPED,
        UPDATE_NOTIFIED,
        UPDATE_NOTIFICATION_ASKED,
        UPDATE_NOTIFY
    )

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var remote: SharedPreferences? = null

    private val confirmed = HashMap<String, Any?>()

    private val localListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) syncAll() else if (key !in localOnlyKeys) executor.execute { push(listOf(key)) }
    }

    fun attach(service: XposedService) {
        executor.execute {
            val target = service.getRemotePreferences(SHARED_PREFERENCES)
            remote = target
            confirmed.clear()
            confirmed.putAll(target.all)
            pushAll()
        }
        RPrefs.getPrefs.registerOnSharedPreferenceChangeListener(localListener)
    }

    fun detach() {
        RPrefs.getPrefs.unregisterOnSharedPreferenceChangeListener(localListener)
        executor.execute {
            remote = null
            confirmed.clear()
        }
    }

    fun syncAll() {
        executor.execute { pushAll() }
    }

    private fun pushAll() {
        val local = RPrefs.getPrefs.all.keys.filterNot { it in localOnlyKeys }
        push((confirmed.keys + local).distinct())
    }

    private fun push(keys: List<String>) {
        val target = remote ?: return
        val local = RPrefs.getPrefs.all
        val changes = keys
            .map { key -> key to local[key]?.takeUnless { key in localOnlyKeys } }
            .filter { (key, value) -> confirmed[key] != value }
        changes.chunked(CHUNK_SIZE).forEach { chunk ->
            if (!commit(target, chunk) && chunk.size > 1) chunk.forEach { commit(target, listOf(it)) }
        }
    }

    private fun commit(target: SharedPreferences, entries: List<Pair<String, Any?>>): Boolean {
        val editor = target.edit()
        entries.forEach { (key, value) -> PreferenceValues.put(editor, key, value) }
        if (!runCatching { editor.commit() }.getOrDefault(false)) return false
        entries.forEach { (key, value) -> if (value == null) confirmed.remove(key) else confirmed[key] = value }
        return true
    }
}
