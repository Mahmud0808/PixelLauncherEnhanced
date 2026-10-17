package com.drdisagree.pixellauncherenhanced.data.common

import org.json.JSONArray
import org.json.JSONObject

object ShortcutCatalog {

    data class Request(val token: Long, val packageName: String?)

    data class Entry(val id: String, val label: String, val icon: String?)

    sealed class Response {
        abstract val token: Long

        data class Counts(override val token: Long, val counts: Map<String, Int>) : Response()
        data class Shortcuts(override val token: Long, val packageName: String, val entries: List<Entry>) : Response()
    }

    data class Target(val packageName: String, val id: String) {
        fun encode(): String = "$packageName$SEPARATOR$id"
    }

    private const val SEPARATOR = "/"
    private const val INTENT_PREFIX = "intent:"

    fun encodeIntent(intent: android.content.Intent): String = INTENT_PREFIX + intent.toUri(android.content.Intent.URI_INTENT_SCHEME)

    fun decodeIntent(value: String?): android.content.Intent? {
        if (value == null || !value.startsWith(INTENT_PREFIX)) return null
        return runCatching {
            android.content.Intent.parseUri(value.removePrefix(INTENT_PREFIX), android.content.Intent.URI_INTENT_SCHEME)
        }.getOrNull()
    }

    fun isValidTarget(value: String?): Boolean = decodeIntent(value) != null || decodeTarget(value) != null

    fun targetPackage(value: String?): String? =
        decodeIntent(value)?.let { it.`package` ?: it.component?.packageName } ?: decodeTarget(value)?.packageName

    fun decodeTarget(value: String?): Target? {
        if (value?.startsWith(INTENT_PREFIX) == true) return null
        val index = value?.indexOf(SEPARATOR) ?: return null
        if (index <= 0 || index == value.length - 1) return null
        return Target(value.substring(0, index), value.substring(index + 1))
    }

    fun encodeRequest(request: Request): String = JSONObject()
        .put("token", request.token)
        .put("package", request.packageName.orEmpty())
        .toString()

    fun decodeRequest(json: String?): Request? = runCatching {
        val root = JSONObject(json ?: return null)
        Request(root.getLong("token"), root.optString("package").takeIf { it.isNotEmpty() })
    }.getOrNull()

    fun encodeCounts(token: Long, counts: Map<String, Int>): String = JSONObject()
        .put("token", token)
        .put("counts", JSONObject().apply { counts.forEach { (pkg, count) -> put(pkg, count) } })
        .toString()

    fun encodeShortcuts(token: Long, packageName: String, entries: List<Entry>): String = JSONObject()
        .put("token", token)
        .put("package", packageName)
        .put("items", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().put("id", entry.id).put("label", entry.label).apply { entry.icon?.let { put("icon", it) } })
            }
        })
        .toString()

    fun decodeResponse(json: String?): Response? = runCatching {
        val root = JSONObject(json ?: return null)
        val token = root.getLong("token")
        root.optJSONObject("counts")?.let { counts ->
            return Response.Counts(token, counts.keys().asSequence().associateWith { counts.getInt(it) })
        }
        val items = root.getJSONArray("items")
        Response.Shortcuts(
            token = token,
            packageName = root.getString("package"),
            entries = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                Entry(item.getString("id"), item.optString("label"), item.optString("icon").takeIf { it.isNotEmpty() })
            }
        )
    }.getOrNull()

    fun parseLabels(json: String?): Map<String, String> = runCatching {
        val root = JSONObject(json ?: return emptyMap())
        root.keys().asSequence().associateWith { root.getString(it) }
    }.getOrDefault(emptyMap())

    fun serializeLabels(labels: Map<String, String>): String =
        JSONObject().apply { labels.forEach { (key, value) -> put(key, value) } }.toString()
}
