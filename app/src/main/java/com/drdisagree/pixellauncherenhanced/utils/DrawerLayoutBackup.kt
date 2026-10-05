package com.drdisagree.pixellauncherenhanced.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_APPS_FROM_APP_DRAWER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.DrawerTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DrawerLayoutBackup {

    val defaultFileName: String
        get() = "PLE_DrawerLayout_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                ".plebackup"

    suspend fun backup(context: Context, uri: Uri, includeHiddenApps: Boolean) = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
                .put(KEY_BACKUP_TYPE, BACKUP_TYPE)
                .put(KEY_FORMAT_VERSION, FORMAT_VERSION)
                .put(KEY_APP_VERSION, BuildConfig.VERSION_NAME)
                .put(KEY_TABS, JSONArray(DrawerTab.serialize(DrawerTabsStore.load())))

            if (includeHiddenApps) {
                root.put(KEY_HIDDEN_APPS, JSONArray(RPrefs.getStringSet(APP_BLOCK_LIST, emptySet()).orEmpty().sorted()))
                root.put(KEY_HIDE_APPS_ENABLED, RPrefs.getBoolean(HIDE_APPS_FROM_APP_DRAWER))
                root.put(KEY_SEARCH_HIDDEN_APPS, RPrefs.getBoolean(SEARCH_HIDDEN_APPS))
            }

            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw BackupException(R.string.backup_failed)

            output.use {
                it.write(BackupCrypto.encrypt(BackupCrypto.DRAWER_LAYOUT_MAGIC, root.toString().toByteArray()))
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
    }

    suspend fun restore(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException(R.string.drawer_layout_invalid_file)

        BackupCrypto.wrongTypeMessage(bytes, BackupCrypto.DRAWER_LAYOUT_MAGIC)?.let {
            throw BackupException(it)
        }

        val root = BackupCrypto.decrypt(BackupCrypto.DRAWER_LAYOUT_MAGIC, bytes)
            ?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() }
            ?.takeIf {
                it.optString(KEY_BACKUP_TYPE) == BACKUP_TYPE &&
                        it.optInt(KEY_FORMAT_VERSION) in 1..FORMAT_VERSION &&
                        it.has(KEY_TABS)
            }
            ?: throw BackupException(R.string.drawer_layout_invalid_file)

        val editor = RPrefs.getPrefs.edit()
        editor.putString(DRAWER_TABS, DrawerTab.serialize(DrawerTab.parse(root.getJSONArray(KEY_TABS).toString())))

        root.optJSONArray(KEY_HIDDEN_APPS)?.let { hiddenApps ->
            editor.putStringSet(APP_BLOCK_LIST, (0 until hiddenApps.length()).mapTo(HashSet()) { hiddenApps.getString(it) })
            editor.putBoolean(HIDE_APPS_FROM_APP_DRAWER, root.optBoolean(KEY_HIDE_APPS_ENABLED, true))
            editor.putBoolean(SEARCH_HIDDEN_APPS, root.optBoolean(KEY_SEARCH_HIDDEN_APPS, false))
        }

        if (!editor.commit()) throw BackupException(R.string.backup_failed)
    }

    private const val BACKUP_TYPE = "ple_drawer_layout"
    private const val FORMAT_VERSION = 1

    private const val KEY_BACKUP_TYPE = "backupType"
    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_TABS = "tabs"
    private const val KEY_HIDDEN_APPS = "hiddenApps"
    private const val KEY_HIDE_APPS_ENABLED = "hideAppsEnabled"
    private const val KEY_SEARCH_HIDDEN_APPS = "searchHiddenApps"
}
