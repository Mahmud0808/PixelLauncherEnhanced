package com.drdisagree.pixellauncherenhanced.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.os.UserManager
import android.provider.DocumentsContract
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_AUTO_SCREENS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_FIRST_SCREEN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.utils.AppUtils.isLauncher3
import com.drdisagree.pixellauncherenhanced.utils.AppUtils.isPixelLauncher
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.resetBootloopProtectorForPackage
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object HomeLayoutBackup {

    private class LauncherTarget(val packageName: String) {
        val dataDir = "/data/user/${Process.myUid() / 100000}/$packageName"
        val databasesDir = "$dataDir/databases"
    }

    val defaultFileName: String
        get() = "PLE_HomeLayout_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                ".zip"

    suspend fun backup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val launcher = currentLauncher()
        val workDir = createWorkDir(context)

        try {
            val dbName = currentDbName(launcher)
            val database = pullDatabase(launcher, dbName, File(workDir, DB_ENTRY))
            val dbVersion = openDatabase(database).use { it.version }

            val metadata = JSONObject().apply {
                put(KEY_FORMAT_VERSION, FORMAT_VERSION)
                put(KEY_LAUNCHER_PACKAGE, launcher.packageName)
                put(KEY_DB_NAME, dbName)
                put(KEY_DB_VERSION, dbVersion)
                put(KEY_NO_DRAWER_MODE, RPrefs.getBoolean(NO_DRAWER_MODE))
                RPrefs.getInt(NO_DRAWER_FIRST_SCREEN, -1)
                    .takeIf { it > 0 && RPrefs.getBoolean(NO_DRAWER_MODE) }
                    ?.let { put(KEY_FIRST_MOD_SCREEN, it) }
            }

            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw BackupException(R.string.home_layout_failed)

            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(METADATA_ENTRY))
                zip.write(metadata.toString().toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(DB_ENTRY))
                database.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun restore(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val launcher = currentLauncher()
        val workDir = createWorkDir(context)

        try {
            val staged = File(workDir, DB_ENTRY)
            val metadata = extractBackup(context, uri, staged)
            val dbName = currentDbName(launcher)
            val backupDbName = metadata.getString(KEY_DB_NAME)

            if (backupDbName != dbName) {
                throw BackupException(
                    R.string.home_layout_grid_mismatch,
                    gridLabel(backupDbName),
                    gridLabel(dbName)
                )
            }

            val current = pullDatabase(launcher, dbName, File(workDir, CURRENT_DB))
            val (currentVersion, currentWidgetIds) = openDatabase(current).use { db ->
                db.version to db.queryInts(
                    "SELECT appWidgetId FROM favorites WHERE itemType = $ITEM_TYPE_WIDGET"
                )
            }

            val noDrawerMode = RPrefs.getBoolean(NO_DRAWER_MODE)
            val firstModScreen = if (metadata.has(KEY_FIRST_MOD_SCREEN)) {
                metadata.getInt(KEY_FIRST_MOD_SCREEN)
            } else {
                null
            }

            openDatabase(staged).use { db ->
                if (db.version > currentVersion) {
                    throw BackupException(R.string.home_layout_newer_launcher)
                }

                sanitizeLayout(
                    context = context,
                    db = db,
                    validWidgetIds = currentWidgetIds,
                    removeAutoAddedFrom = if (noDrawerMode) null else firstModScreen
                )
            }

            RPrefs.clearPrefs(NO_DRAWER_FIRST_SCREEN, NO_DRAWER_AUTO_SCREENS)
            if (noDrawerMode && firstModScreen != null) {
                RPrefs.putInt(NO_DRAWER_FIRST_SCREEN, firstModScreen)
            }

            resetBootloopProtectorForPackage(launcher.packageName)
            pushDatabase(launcher, dbName, staged)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun sanitizeLayout(
        context: Context,
        db: SQLiteDatabase,
        validWidgetIds: Set<Int>,
        removeAutoAddedFrom: Int?
    ) {
        val packageManager = context.packageManager
        val currentUserSerial = context.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        val installedCache = HashMap<String, Boolean>()
        val removedIds = ArrayList<Long>()
        val invalidWidgetIds = ArrayList<Long>()

        fun isInstalled(packageName: String) = installedCache.getOrPut(packageName) {
            runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        }

        db.rawQuery(
            "SELECT _id, intent, itemType, appWidgetProvider, appWidgetId, container, screen, profileId FROM favorites",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val itemType = cursor.getInt(2)
                val intent = cursor.getString(1)?.let {
                    runCatching { Intent.parseUri(it, 0) }.getOrNull()
                }

                if (removeAutoAddedFrom != null &&
                    intent?.getBooleanExtra(AUTO_ADDED_EXTRA, false) == true &&
                    cursor.getInt(5) == CONTAINER_DESKTOP &&
                    cursor.getInt(6) >= removeAutoAddedFrom
                ) {
                    removedIds.add(id)
                    continue
                }

                val packageName = when (itemType) {
                    ITEM_TYPE_WIDGET -> cursor.getString(3)
                        ?.let { ComponentName.unflattenFromString(it) }
                        ?.packageName

                    ITEM_TYPE_APPLICATION,
                    ITEM_TYPE_SHORTCUT,
                    ITEM_TYPE_DEEP_SHORTCUT -> intent?.component?.packageName ?: intent?.`package`

                    else -> null
                }

                if (packageName != null &&
                    cursor.getLong(7) == currentUserSerial &&
                    !isInstalled(packageName)
                ) {
                    removedIds.add(id)
                    continue
                }

                if (itemType == ITEM_TYPE_WIDGET && cursor.getInt(4) !in validWidgetIds) {
                    invalidWidgetIds.add(id)
                }
            }
        }

        db.beginTransaction()
        try {
            removedIds.chunked(500).forEach { ids ->
                db.execSQL("DELETE FROM favorites WHERE _id IN (${ids.joinToString()})")
            }
            invalidWidgetIds.chunked(500).forEach { ids ->
                db.execSQL(
                    "UPDATE favorites SET restored = restored | $FLAG_WIDGET_ID_NOT_VALID " +
                            "WHERE _id IN (${ids.joinToString()})"
                )
            }
            db.execSQL(
                "DELETE FROM favorites WHERE itemType IN ($ITEM_TYPE_FOLDER, $ITEM_TYPE_APP_PAIR) " +
                        "AND _id NOT IN (SELECT container FROM favorites)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun extractBackup(context: Context, uri: Uri, staged: File): JSONObject {
        var metadata: JSONObject? = null

        val input = context.contentResolver.openInputStream(uri)
            ?: throw BackupException(R.string.home_layout_invalid_file)

        runCatching {
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    when (entry.name) {
                        METADATA_ENTRY -> metadata = JSONObject(zip.readBytes().decodeToString())
                        DB_ENTRY -> staged.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }

        val result = metadata
        if (result == null && SettingsBackup.isSettingsBackup(context, uri)) {
            throw BackupException(R.string.home_layout_restore_got_settings_backup)
        }

        if (result == null ||
            !result.has(KEY_DB_NAME) ||
            result.optInt(KEY_FORMAT_VERSION) > FORMAT_VERSION ||
            !staged.isFile ||
            staged.length() == 0L
        ) {
            throw BackupException(R.string.home_layout_invalid_file)
        }

        return result
    }

    private fun currentLauncher(): LauncherTarget {
        return when {
            isPixelLauncher -> LauncherTarget(PIXEL_LAUNCHER_PACKAGE)
            isLauncher3 -> LauncherTarget(LAUNCHER3_PACKAGE)
            else -> throw BackupException(R.string.home_layout_no_launcher)
        }
    }

    private fun currentDbName(launcher: LauncherTarget): String {
        val prefs = Shell.cmd("cat '${launcher.dataDir}/shared_prefs/$LAUNCHER_PREFS_FILE'")
            .exec()
            .out
            .joinToString("\n")

        val fromPrefs = DB_FILE_PREF_REGEX.find(prefs)?.groupValues?.get(1)
        val databases = Shell.cmd("ls -t '${launcher.databasesDir}'").exec().out

        return fromPrefs?.takeIf { it in databases }
            ?: databases.firstOrNull { LAYOUT_DB_REGEX.matches(it) }
            ?: throw BackupException(R.string.home_layout_failed)
    }

    private fun pullDatabase(launcher: LauncherTarget, dbName: String, target: File): File {
        val wal = File(target.path + "-wal")
        target.createNewFile()
        wal.createNewFile()

        val source = "${launcher.databasesDir}/$dbName"
        Shell.cmd(
            "cat '$source' > '${target.path}'",
            "if [ -f '$source-wal' ]; then cat '$source-wal' > '${wal.path}'; fi"
        ).exec()

        if (wal.length() == 0L) wal.delete()
        if (target.length() == 0L) throw BackupException(R.string.home_layout_failed)

        return target
    }

    private fun pushDatabase(launcher: LauncherTarget, dbName: String, staged: File) {
        val target = "${launcher.databasesDir}/$dbName"
        val result = Shell.cmd(
            "am force-stop ${launcher.packageName}",
            "rm -f '$target-wal' '$target-shm' '$target-journal'",
            "cat '${staged.path}' > '$target'"
        ).exec()

        if (!result.isSuccess) throw BackupException(R.string.home_layout_failed)
    }

    private fun openDatabase(file: File): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
    }

    private fun SQLiteDatabase.queryInts(sql: String): Set<Int> {
        return rawQuery(sql, null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getInt(0))
            }
        }
    }

    private fun createWorkDir(context: Context): File {
        return File(context.cacheDir, "home_layout").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun gridLabel(dbName: String): String {
        if (dbName == DEFAULT_GRID_DB) return "5x5"

        return LAYOUT_DB_REGEX.matchEntire(dbName)
            ?.groupValues
            ?.takeIf { it[1].isNotEmpty() }
            ?.let { "${it[1]}x${it[2]}" }
            ?: dbName
    }

    private const val FORMAT_VERSION = 1
    private const val DB_ENTRY = "launcher.db"
    private const val CURRENT_DB = "current.db"
    private const val METADATA_ENTRY = "metadata.json"
    private const val LAUNCHER_PREFS_FILE = "com.android.launcher3.prefs.xml"
    private const val DEFAULT_GRID_DB = "launcher.db"

    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_LAUNCHER_PACKAGE = "launcherPackage"
    private const val KEY_DB_NAME = "dbName"
    private const val KEY_DB_VERSION = "dbVersion"
    private const val KEY_NO_DRAWER_MODE = "noDrawerMode"
    private const val KEY_FIRST_MOD_SCREEN = "firstModScreen"

    private const val AUTO_ADDED_EXTRA = "plenhanced_auto"
    private const val CONTAINER_DESKTOP = -100
    private const val ITEM_TYPE_APPLICATION = 0
    private const val ITEM_TYPE_SHORTCUT = 1
    private const val ITEM_TYPE_FOLDER = 2
    private const val ITEM_TYPE_WIDGET = 4
    private const val ITEM_TYPE_DEEP_SHORTCUT = 6
    private const val ITEM_TYPE_APP_PAIR = 10
    private const val FLAG_WIDGET_ID_NOT_VALID = 1

    private val DB_FILE_PREF_REGEX = Regex("<string name=\"migration_src_db_file\">([^<]+)</string>")
    private val LAYOUT_DB_REGEX = Regex("launcher(?:_(\\d+)_by_(\\d+))?\\.db")
}
