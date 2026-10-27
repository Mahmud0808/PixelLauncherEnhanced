package com.drdisagree.pixellauncherenhanced.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.drdisagree.pixellauncherenhanced.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FullBackup {

    val defaultFileName: String
        get() = "PLE_Full_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                ".plebackup"

    suspend fun backup(context: Context, uri: Uri, includeHiddenApps: Boolean) = withContext(Dispatchers.IO) {
        try {
            val archive = ByteArrayOutputStream()
            ZipOutputStream(archive).use { zip ->
                zip.putEntry(SETTINGS_ENTRY, SettingsBackup.backupBytes())
                zip.putEntry(DRAWER_ENTRY, DrawerLayoutBackup.backupBytes(includeHiddenApps))
                zip.putEntry(HOME_ENTRY, HomeLayoutBackup.backupBytes(context))
            }

            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw BackupException(R.string.backup_failed)

            output.use { it.write(BackupCrypto.encrypt(BackupCrypto.FULL_MAGIC, archive.toByteArray())) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
    }

    suspend fun restore(context: Context, uri: Uri): BackupException? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException(R.string.full_invalid_file)

        BackupCrypto.wrongTypeMessage(bytes, BackupCrypto.FULL_MAGIC)?.let {
            throw BackupException(it)
        }

        val archive = BackupCrypto.decrypt(BackupCrypto.FULL_MAGIC, bytes)
            ?: throw BackupException(R.string.full_invalid_file)

        val parts = HashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(archive.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry -> parts[entry.name] = zip.readBytes() }
            }
        }

        val settings = parts[SETTINGS_ENTRY]
        val drawer = parts[DRAWER_ENTRY]
        val home = parts[HOME_ENTRY]
        if (settings == null || drawer == null || home == null) {
            throw BackupException(R.string.full_invalid_file)
        }

        HomeLayoutBackup.stopLauncher()
        SettingsBackup.restoreBytes(settings)
        DrawerLayoutBackup.restoreBytes(drawer)

        try {
            HomeLayoutBackup.restoreBytes(context, home)
            null
        } catch (e: BackupException) {
            e
        }
    }

    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private const val SETTINGS_ENTRY = "settings.plebackup"
    private const val DRAWER_ENTRY = "drawer.plebackup"
    private const val HOME_ENTRY = "home.plebackup"
}
