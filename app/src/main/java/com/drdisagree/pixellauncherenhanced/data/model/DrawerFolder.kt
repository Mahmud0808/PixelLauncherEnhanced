package com.drdisagree.pixellauncherenhanced.data.model

import org.json.JSONArray
import org.json.JSONObject

data class DrawerFolder(
    val id: Int,
    val title: String,
    val apps: List<String> = emptyList(),
    val tabId: String? = null
) {

    fun belongsTo(tab: String?): Boolean = tabId == tab

    private fun toJson(): JSONObject = JSONObject()
        .put(KEY_ID, id)
        .put(KEY_TITLE, title)
        .put(KEY_APPS, JSONArray(apps))
        .put(KEY_TAB, tabId ?: JSONObject.NULL)

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_APPS = "apps"
        private const val KEY_TAB = "tab"

        fun parse(json: String?): List<DrawerFolder> {
            return runCatching {
                val array = JSONArray(json ?: "[]")
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val apps = item.optJSONArray(KEY_APPS)
                    DrawerFolder(
                        id = item.optInt(KEY_ID, index + 1),
                        title = item.optString(KEY_TITLE),
                        apps = (0 until (apps?.length() ?: 0)).mapNotNull { apps?.optString(it)?.takeIf(String::isNotBlank) }
                            .distinct(),
                        tabId = item.optString(KEY_TAB).takeIf { !item.isNull(KEY_TAB) && it.isNotBlank() }
                    )
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }

        fun serialize(folders: List<DrawerFolder>): String {
            return JSONArray().apply { folders.forEach { put(it.toJson()) } }.toString()
        }
    }
}
