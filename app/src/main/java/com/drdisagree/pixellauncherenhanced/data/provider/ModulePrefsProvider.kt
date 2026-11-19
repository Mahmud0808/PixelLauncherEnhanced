package com.drdisagree.pixellauncherenhanced.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.iconpack.CustomIconStore
import com.drdisagree.pixellauncherenhanced.data.prefs.PreferenceValues
import com.drdisagree.pixellauncherenhanced.data.prefs.PrefsBridge
import com.drdisagree.pixellauncherenhanced.data.prefs.RemotePrefsSync
import java.io.FileNotFoundException

class ModulePrefsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isTrustedCaller()) throw SecurityException("Caller is not in module scope")

        return when (method) {
            PrefsBridge.METHOD_WRITE -> {
                extras?.let { write(it) }
                null
            }

            PrefsBridge.METHOD_SYNC -> {
                RemotePrefsSync.syncAll()
                null
            }

            PrefsBridge.METHOD_READ -> read(arg)
            else -> null
        }
    }

    private fun read(key: String?): Bundle {
        val all = RPrefs.getPrefs.all
        if (key == null) {
            return PrefsBridge.encode(all.filterKeys { !RemotePrefsSync.isLocalOnly(it) }, emptyList())
        }
        val value = all[key]?.takeUnless { RemotePrefsSync.isLocalOnly(key) }
        return if (value == null) {
            PrefsBridge.encode(emptyMap(), listOf(key))
        } else {
            PrefsBridge.encode(mapOf(key to value), emptyList())
        }
    }

    private fun write(extras: Bundle) {
        val editor = RPrefs.getPrefs.edit()
        PrefsBridge.decodeRemoved(extras).forEach { editor.remove(it) }
        PrefsBridge.decodeValues(extras).forEach { (key, value) -> PreferenceValues.put(editor, key, value) }
        editor.apply()
    }

    private fun isTrustedCaller(): Boolean {
        val context = context ?: return false
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val scope = context.resources.getStringArray(R.array.module_scope)
        return context.packageManager.getPackagesForUid(uid).orEmpty().any { it in scope }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!isTrustedCaller()) throw SecurityException("Caller is not in module scope")
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != PrefsBridge.CUSTOM_ICON_PATH) throw FileNotFoundException(uri.toString())
        val key = segments[1]
        if (!CustomIconStore.isValidKey(key)) throw FileNotFoundException(uri.toString())
        val file = CustomIconStore.file(context, key)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
