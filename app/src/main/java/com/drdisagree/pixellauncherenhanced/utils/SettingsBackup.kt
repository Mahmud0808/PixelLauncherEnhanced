package com.drdisagree.pixellauncherenhanced.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_DATABASE_ICON_COUNT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_FOLDERS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_APPS_FROM_APP_DRAWER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_AUTO_SCREENS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_FIRST_SCREEN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.XPOSED_HOOK_CHECK
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.LOAD_TIME_KEY_KEY
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.PACKAGE_STRIKE_KEY_KEY
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object SettingsBackup {

    val defaultFileName: String
        get() = "PLE_Settings_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                ".plebackup"

    suspend fun backup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val bytes = backupBytes()
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw BackupException(R.string.backup_failed)

            output.use { it.write(bytes) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
    }

    fun backupBytes(): ByteArray {
        val root = JSONObject()
            .put(KEY_BACKUP_TYPE, BACKUP_TYPE)
            .put(KEY_FORMAT_VERSION, FORMAT_VERSION)
            .put(KEY_APP_VERSION, BuildConfig.VERSION_NAME)
            .put(KEY_PREFS, PrefsSnapshot.capture { !isExcluded(it) })

        return BackupCrypto.encrypt(BackupCrypto.SETTINGS_MAGIC, root.toString().toByteArray())
    }

    suspend fun restore(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException(R.string.settings_invalid_file)
        restoreBytes(bytes)
    }

    fun restoreBytes(bytes: ByteArray) {
        BackupCrypto.wrongTypeMessage(bytes, BackupCrypto.SETTINGS_MAGIC)?.let {
            throw BackupException(it)
        }

        val root = parseBackup(bytes) ?: throw BackupException(R.string.settings_invalid_file)
        val prefs = root.optJSONObject(KEY_PREFS)
            ?: throw BackupException(R.string.settings_invalid_file)
        val editor = RPrefs.getPrefs.edit()
        PrefsSnapshot.apply(editor, prefs) { !isExcluded(it) }

        if (!editor.commit()) throw BackupException(R.string.backup_failed)
    }

    private fun parseBackup(bytes: ByteArray): JSONObject? {
        val plain = BackupCrypto.decrypt(BackupCrypto.SETTINGS_MAGIC, bytes) ?: return null
        val root = runCatching { JSONObject(plain.decodeToString()) }.getOrNull() ?: return null

        return root.takeIf {
            it.optString(KEY_BACKUP_TYPE) == BACKUP_TYPE &&
                    it.optInt(KEY_FORMAT_VERSION) in 1..FORMAT_VERSION
        }
    }

    private fun isExcluded(key: String): Boolean {
        return key in EXCLUDED_KEYS ||
                key.startsWith(LOAD_TIME_KEY_KEY) ||
                key.startsWith(PACKAGE_STRIKE_KEY_KEY)
    }

    private const val BACKUP_TYPE = "ple_settings"
    private const val FORMAT_VERSION = 1

    private const val KEY_BACKUP_TYPE = "backupType"
    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_PREFS = "prefs"

    private val EXCLUDED_KEYS = setOf(
        APP_BLOCK_LIST,
        DOCK_DATABASE_ICON_COUNT,
        HIDE_APPS_FROM_APP_DRAWER,
        SEARCH_HIDDEN_APPS,
        DRAWER_TABS,
        DRAWER_FOLDERS,
        XPOSED_HOOK_CHECK,
        NO_DRAWER_FIRST_SCREEN,
        NO_DRAWER_AUTO_SCREENS
    )
}
