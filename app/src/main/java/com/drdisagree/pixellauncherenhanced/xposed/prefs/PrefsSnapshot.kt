package com.drdisagree.pixellauncherenhanced.xposed.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PrefsSnapshot(context: Context) {

    private val file = File(context.createDeviceProtectedStorageContext().filesDir, FILE_NAME)
    private val values = ConcurrentHashMap<String, Any>()

    fun load(): Map<String, Any> {
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return emptyMap()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        root.keys().forEach { key ->
            root.optJSONArray(key)?.let { decode(it) }?.let { values[key] = it }
        }
        return values.toMap()
    }

    fun replace(all: Map<String, Any?>) {
        values.clear()
        all.forEach { (key, value) -> if (value != null) values[key] = value }
        save()
    }

    fun update(changed: Map<String, Any?>, removed: Collection<String>) {
        changed.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        removed.forEach { values.remove(it) }
        save()
    }

    private fun save() {
        val root = JSONObject()
        values.forEach { (key, value) -> encode(value)?.let { root.put(key, it) } }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
        }
    }

    private fun encode(value: Any): JSONArray? = when (value) {
        is Boolean -> JSONArray().put(TYPE_BOOLEAN).put(value)
        is Int -> JSONArray().put(TYPE_INT).put(value)
        is Long -> JSONArray().put(TYPE_LONG).put(value)
        is Float -> JSONArray().put(TYPE_FLOAT).put(value.toDouble())
        is String -> JSONArray().put(TYPE_STRING).put(value)
        is Set<*> -> JSONArray().put(TYPE_SET).put(JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun decode(entry: JSONArray): Any? = runCatching {
        when (entry.getString(0)) {
            TYPE_BOOLEAN -> entry.getBoolean(1)
            TYPE_INT -> entry.getInt(1)
            TYPE_LONG -> entry.getLong(1)
            TYPE_FLOAT -> entry.getDouble(1).toFloat()
            TYPE_STRING -> entry.getString(1)
            TYPE_SET -> entry.getJSONArray(1).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            else -> null
        }
    }.getOrNull()

    private companion object {
        const val FILE_NAME = "ple_prefs_snapshot.json"
        const val TYPE_BOOLEAN = "b"
        const val TYPE_INT = "i"
        const val TYPE_LONG = "l"
        const val TYPE_FLOAT = "f"
        const val TYPE_STRING = "s"
        const val TYPE_SET = "S"
    }
}
