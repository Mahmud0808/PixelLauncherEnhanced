package com.drdisagree.pixellauncherenhanced.data.model

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import com.drdisagree.pixellauncherenhanced.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DrawerTab(
    val id: String,
    val type: Type,
    val name: String = "",
    val hidden: Boolean = false,
    val apps: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet()
) {

    enum class Type(val key: String) {
        ALL("all"),
        WORK("work"),
        GAMES("games"),
        SOCIAL("social"),
        MEDIA("media"),
        PRODUCTIVITY("productivity"),
        NEWS("news"),
        NAVIGATION("navigation"),
        CUSTOM("custom");

        companion object {
            fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    val isBuiltIn: Boolean
        get() = type != Type.CUSTOM

    val filtersApps: Boolean
        get() = type != Type.ALL && type != Type.WORK

    val isCustomized: Boolean
        get() = isBuiltIn && (apps.isNotEmpty() || excluded.isNotEmpty())

    fun displayName(resources: Resources): String {
        return when (type) {
            Type.ALL -> resources.getString(R.string.drawer_tab_all)
            Type.WORK -> resources.getString(R.string.drawer_tab_work)
            Type.GAMES -> resources.getString(R.string.drawer_tab_games)
            Type.SOCIAL -> resources.getString(R.string.drawer_tab_social)
            Type.MEDIA -> resources.getString(R.string.drawer_tab_media)
            Type.PRODUCTIVITY -> resources.getString(R.string.drawer_tab_productivity)
            Type.NEWS -> resources.getString(R.string.drawer_tab_news)
            Type.NAVIGATION -> resources.getString(R.string.drawer_tab_navigation)
            Type.CUSTOM -> name
        }
    }

    fun matches(packageName: String, appInfo: ApplicationInfo?): Boolean {
        return when (type) {
            Type.ALL, Type.WORK -> true
            Type.CUSTOM -> packageName in apps
            else -> packageName in apps || (packageName !in excluded && recommends(appInfo))
        }
    }

    @Suppress("DEPRECATION")
    fun recommends(appInfo: ApplicationInfo?): Boolean {
        val category = appInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED

        return when (type) {
            Type.ALL, Type.WORK, Type.CUSTOM -> false
            Type.GAMES -> category == ApplicationInfo.CATEGORY_GAME ||
                    (appInfo != null && appInfo.flags and ApplicationInfo.FLAG_IS_GAME != 0)

            Type.SOCIAL -> category == ApplicationInfo.CATEGORY_SOCIAL
            Type.MEDIA -> category == ApplicationInfo.CATEGORY_AUDIO ||
                    category == ApplicationInfo.CATEGORY_VIDEO ||
                    category == ApplicationInfo.CATEGORY_IMAGE

            Type.PRODUCTIVITY -> category == ApplicationInfo.CATEGORY_PRODUCTIVITY
            Type.NEWS -> category == ApplicationInfo.CATEGORY_NEWS
            Type.NAVIGATION -> category == ApplicationInfo.CATEGORY_MAPS
        }
    }

    private fun toJson(): JSONObject {
        return JSONObject()
            .put(KEY_ID, id)
            .put(KEY_TYPE, type.key)
            .put(KEY_NAME, name)
            .put(KEY_HIDDEN, hidden)
            .put(KEY_APPS, JSONArray(apps.sorted()))
            .put(KEY_EXCLUDED, JSONArray(excluded.sorted()))
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_TYPE = "type"
        private const val KEY_NAME = "name"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_APPS = "apps"
        private const val KEY_EXCLUDED = "excluded"

        private val BUILT_IN_ORDER = listOf(
            Type.ALL,
            Type.WORK,
            Type.GAMES,
            Type.SOCIAL,
            Type.MEDIA,
            Type.PRODUCTIVITY,
            Type.NEWS,
            Type.NAVIGATION
        )

        fun newCustom(name: String) = DrawerTab(
            id = "custom_${UUID.randomUUID()}",
            type = Type.CUSTOM,
            name = name
        )

        fun parse(json: String?): List<DrawerTab> {
            val tabs = runCatching {
                val array = JSONArray(json ?: "[]")

                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val type = Type.fromKey(item.optString(KEY_TYPE)) ?: return@mapNotNull null
                    val apps = item.optJSONArray(KEY_APPS)
                    val excluded = item.optJSONArray(KEY_EXCLUDED)

                    DrawerTab(
                        id = if (type == Type.CUSTOM) item.optString(KEY_ID) else type.key,
                        type = type,
                        name = item.optString(KEY_NAME),
                        hidden = type != Type.CUSTOM && item.optBoolean(KEY_HIDDEN),
                        apps = (0 until (apps?.length() ?: 0)).mapTo(HashSet()) { apps!!.getString(it) },
                        excluded = if (type == Type.CUSTOM) emptySet() else {
                            (0 until (excluded?.length() ?: 0)).mapTo(HashSet()) { excluded!!.getString(it) }
                        }
                    )
                }
            }.getOrDefault(emptyList())
                .filter { it.id.isNotEmpty() }
                .distinctBy { it.id }

            val missing = BUILT_IN_ORDER
                .filter { type -> tabs.none { it.type == type } }
                .map { DrawerTab(id = it.key, type = it) }

            return tabs + missing
        }

        fun serialize(tabs: List<DrawerTab>): String {
            return JSONArray().apply { tabs.forEach { put(it.toJson()) } }.toString()
        }
    }
}
