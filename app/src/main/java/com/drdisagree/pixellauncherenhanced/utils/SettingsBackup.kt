package com.drdisagree.pixellauncherenhanced.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_AUTO_SCREENS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_FIRST_SCREEN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.XPOSED_HOOK_CHECK
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.LOAD_TIME_KEY_KEY
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.PACKAGE_STRIKE_KEY_KEY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SettingsBackup {

    val defaultFileName: String
        get() = "PLE_Settings_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                ".plebackup"

    suspend fun backup(
        context: Context,
        uri: Uri,
        includeHiddenApps: Boolean
    ) = withContext(Dispatchers.IO) {
        try {
            val prefs = JSONObject()

            RPrefs.getPrefs.all.forEach { (key, value) ->
                if (key == null || value == null || isDeviceSpecific(key)) return@forEach
                if (!includeHiddenApps && key in HIDDEN_APPS_KEYS) return@forEach

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

            val root = JSONObject()
                .put(KEY_BACKUP_TYPE, BACKUP_TYPE)
                .put(KEY_FORMAT_VERSION, FORMAT_VERSION)
                .put(KEY_APP_VERSION, BuildConfig.VERSION_NAME)
                .put(KEY_INCLUDES_HIDDEN_APPS, includeHiddenApps)
                .put(KEY_PREFS, prefs)

            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw BackupException(R.string.backup_failed)

            output.use { it.write(encrypt(root.toString().toByteArray())) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
    }

    suspend fun restore(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException(R.string.settings_invalid_file)

        if (bytes.isZip()) throw BackupException(R.string.settings_restore_got_layout_backup)

        val root = parseBackup(bytes) ?: throw BackupException(R.string.settings_invalid_file)
        val prefs = root.optJSONObject(KEY_PREFS)
            ?: throw BackupException(R.string.settings_invalid_file)
        val keepCurrentHiddenApps = !root.optBoolean(KEY_INCLUDES_HIDDEN_APPS, true)

        fun isKept(key: String) = isDeviceSpecific(key) || (keepCurrentHiddenApps && key in HIDDEN_APPS_KEYS)

        val editor = RPrefs.getPrefs.edit()

        RPrefs.getPrefs.all.keys
            .filterNot { isKept(it) }
            .forEach { editor.remove(it) }

        prefs.keys().forEach { key ->
            if (isKept(key)) return@forEach

            val entry = prefs.optJSONObject(key) ?: return@forEach

            runCatching {
                when (entry.getString(KEY_TYPE)) {
                    TYPE_BOOLEAN -> editor.putBoolean(key, entry.getBoolean(KEY_VALUE))
                    TYPE_INT -> editor.putInt(key, entry.getInt(KEY_VALUE))
                    TYPE_LONG -> editor.putLong(key, entry.getLong(KEY_VALUE))
                    TYPE_FLOAT -> editor.putFloat(key, entry.getDouble(KEY_VALUE).toFloat())
                    TYPE_STRING -> editor.putString(key, entry.getString(KEY_VALUE))
                    TYPE_STRING_SET -> {
                        val array = entry.getJSONArray(KEY_VALUE)
                        editor.putStringSet(key, (0 until array.length()).mapTo(HashSet()) { array.getString(it) })
                    }
                }
            }
        }

        if (!editor.commit()) throw BackupException(R.string.backup_failed)
    }

    fun isSettingsBackup(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { parseBackup(it.readBytes()) } != null
        }.getOrDefault(false)
    }

    private fun parseBackup(bytes: ByteArray): JSONObject? {
        val plain = decrypt(bytes) ?: return null
        val root = runCatching { JSONObject(plain.decodeToString()) }.getOrNull() ?: return null

        return root.takeIf {
            it.optString(KEY_BACKUP_TYPE) == BACKUP_TYPE &&
                    it.optInt(KEY_FORMAT_VERSION) in 1..FORMAT_VERSION
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(salt), GCMParameterSpec(TAG_BITS, iv))
        }

        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray? {
        val headerSize = MAGIC.size + SALT_SIZE + IV_SIZE
        if (data.size <= headerSize || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null

        return runCatching {
            val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_SIZE)
            val iv = data.copyOfRange(MAGIC.size + SALT_SIZE, headerSize)
            Cipher.getInstance(CIPHER).run {
                init(Cipher.DECRYPT_MODE, deriveKey(salt), GCMParameterSpec(TAG_BITS, iv))
                doFinal(data, headerSize, data.size - headerSize)
            }
        }.getOrNull()
    }

    private fun deriveKey(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(PASSPHRASE.toCharArray(), salt, KEY_ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    private fun ByteArray.isZip() = size >= 2 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()

    private fun isDeviceSpecific(key: String): Boolean {
        return key in DEVICE_SPECIFIC_KEYS ||
                key.startsWith(LOAD_TIME_KEY_KEY) ||
                key.startsWith(PACKAGE_STRIKE_KEY_KEY)
    }

    private val MAGIC = "PLESET1".toByteArray()
    private const val PASSPHRASE = "com.drdisagree.pixellauncherenhanced.settings"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val KEY_ITERATIONS = 10000

    private const val BACKUP_TYPE = "ple_settings"
    private const val FORMAT_VERSION = 1

    private const val KEY_BACKUP_TYPE = "backupType"
    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_PREFS = "prefs"
    private const val KEY_INCLUDES_HIDDEN_APPS = "includesHiddenApps"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"

    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "stringSet"

    private val HIDDEN_APPS_KEYS = setOf(APP_BLOCK_LIST)

    private val DEVICE_SPECIFIC_KEYS = setOf(
        XPOSED_HOOK_CHECK,
        NO_DRAWER_FIRST_SCREEN,
        NO_DRAWER_AUTO_SCREENS
    )
}
