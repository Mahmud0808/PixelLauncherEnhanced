package com.drdisagree.pixellauncherenhanced.data.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

object CustomIconStore {

    private const val DIRECTORY = "custom_icons"
    private const val STAMP_PREFIX = "file:"

    fun isStamp(value: String?): Boolean = value?.startsWith(STAMP_PREFIX) == true

    fun isValidKey(key: String): Boolean =
        IconPackManager.isCustomIconKey(key) && key.all { it.isLetterOrDigit() || it == '_' }

    fun file(context: Context, key: String): File = File(File(context.filesDir, DIRECTORY), "$key.png")

    fun save(context: Context, key: String, bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return write(context, key, output.toByteArray())
    }

    fun write(context: Context, key: String, bytes: ByteArray): String {
        val target = file(context, key)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$key.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        return STAMP_PREFIX + CRC32().apply { update(bytes) }.value.toString(16)
    }

    fun read(context: Context, key: String): ByteArray? =
        file(context, key).takeIf { it.isFile }?.readBytes()

    fun load(context: Context, key: String, value: String?): Bitmap? {
        if (!isStamp(value)) return IconPackManager.decodeBitmap(value)
        val file = file(context, key)
        return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    fun delete(context: Context, key: String) {
        file(context, key).delete()
    }

    fun migrate(context: Context) {
        val legacy = RPrefs.getPrefs.all.filter { (key, value) ->
            key != null && IconPackManager.isCustomIconKey(key) && value is String && !isStamp(value)
        }
        if (legacy.isEmpty()) return

        val editor = RPrefs.getPrefs.edit()
        legacy.forEach { (key, value) ->
            val bytes = runCatching { Base64.decode(value as String, Base64.NO_WRAP) }
                .getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                editor.remove(key)
            } else {
                editor.putString(key, write(context, key, bytes))
            }
        }
        editor.apply()
    }
}
