package com.drdisagree.pixellauncherenhanced.data.model

import org.json.JSONArray
import org.json.JSONObject

data class ReleaseInfo(
    val version: String,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    val pageUrl: String,
    val publishedAt: String
) {

    fun toJson(): String = JSONObject()
        .put(KEY_VERSION, version)
        .put(KEY_NAME, name)
        .put(KEY_NOTES, notes)
        .put(KEY_APK_URL, apkUrl)
        .put(KEY_APK_NAME, apkName)
        .put(KEY_SIZE, size)
        .put(KEY_PAGE_URL, pageUrl)
        .put(KEY_PUBLISHED_AT, publishedAt)
        .toString()

    companion object {
        private const val KEY_VERSION = "version"
        private const val KEY_NAME = "name"
        private const val KEY_NOTES = "notes"
        private const val KEY_APK_URL = "apkUrl"
        private const val KEY_APK_NAME = "apkName"
        private const val KEY_SIZE = "size"
        private const val KEY_PAGE_URL = "pageUrl"
        private const val KEY_PUBLISHED_AT = "publishedAt"

        fun fromJson(json: String?): ReleaseInfo? = runCatching {
            val obj = JSONObject(json ?: return null)
            ReleaseInfo(
                version = obj.getString(KEY_VERSION),
                name = obj.optString(KEY_NAME),
                notes = obj.optString(KEY_NOTES),
                apkUrl = obj.getString(KEY_APK_URL),
                apkName = obj.getString(KEY_APK_NAME),
                size = obj.optLong(KEY_SIZE),
                pageUrl = obj.optString(KEY_PAGE_URL),
                publishedAt = obj.optString(KEY_PUBLISHED_AT)
            )
        }.getOrNull()

        fun fromGitHub(json: String): ReleaseInfo? = runCatching { parse(JSONObject(json), requireApk = true) }.getOrNull()

        fun listFromGitHub(json: String): List<ReleaseInfo> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                runCatching { parse(array.getJSONObject(index), requireApk = false) }.getOrNull()
            }
        }.getOrDefault(emptyList())

        private fun parse(obj: JSONObject, requireApk: Boolean): ReleaseInfo? {
            if (obj.optBoolean("draft") || obj.optBoolean("prerelease")) return null
            val assets = obj.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
            if (apk == null && requireApk) return null
            val tag = obj.getString("tag_name")
            return ReleaseInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                name = obj.optString("name").ifBlank { tag },
                notes = obj.optString("body"),
                apkUrl = apk?.getString("browser_download_url").orEmpty(),
                apkName = apk?.getString("name").orEmpty(),
                size = apk?.optLong("size") ?: 0L,
                pageUrl = obj.optString("html_url"),
                publishedAt = obj.optString("published_at")
            )
        }
    }
}
