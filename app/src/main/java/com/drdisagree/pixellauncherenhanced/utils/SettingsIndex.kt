package com.drdisagree.pixellauncherenhanced.utils

import android.content.Context
import android.content.res.XmlResourceParser
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.data.model.SettingsEntry
import com.drdisagree.pixellauncherenhanced.data.model.SettingsScreen
import com.drdisagree.pixellauncherenhanced.ui.fragments.AppDrawerMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.BackupRestore
import com.drdisagree.pixellauncherenhanced.ui.fragments.GesturesMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.HomeScreenMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.IconsMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.MiscellaneousMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.RecentsMods
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

object SettingsIndex {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val screens = listOf(
        SettingsScreen(R.xml.home_screen_mods, R.string.fragment_home_screen_title, HomeScreenMods::class.java),
        SettingsScreen(R.xml.app_drawer_mods, R.string.fragment_app_drawer_title, AppDrawerMods::class.java),
        SettingsScreen(R.xml.icons_mods, R.string.fragment_icons_title, IconsMods::class.java),
        SettingsScreen(R.xml.gestures_mods, R.string.fragment_gestures_title, GesturesMods::class.java),
        SettingsScreen(R.xml.recents_mods, R.string.fragment_recents_title, RecentsMods::class.java),
        SettingsScreen(R.xml.backup_restore, R.string.fragment_backup_title, BackupRestore::class.java),
        SettingsScreen(R.xml.miscellaneous_mods, R.string.fragment_advanced_title, MiscellaneousMods::class.java)
    )

    @Volatile
    private var entries: List<SettingsEntry>? = null

    fun search(context: Context, query: String): List<SettingsEntry> {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.isEmpty()) return emptyList()

        return all(context)
            .filter { PrefsHelper.isVisible(it.key) }
            .mapNotNull { entry ->
                val title = entry.title.lowercase(Locale.getDefault())
                val rank = when {
                    title.startsWith(needle) -> 0
                    title.split(' ').any { it.startsWith(needle) } -> 1
                    title.contains(needle) -> 2
                    entry.summary.lowercase(Locale.getDefault()).contains(needle) -> 3
                    entry.categoryTitle?.lowercase(Locale.getDefault())?.contains(needle) == true -> 4
                    entry.screenTitle.lowercase(Locale.getDefault()).contains(needle) -> 5
                    else -> return@mapNotNull null
                }
                rank to entry
            }
            .sortedWith(compareBy({ it.first }, { it.second.title }))
            .map { it.second }
    }

    private fun all(context: Context): List<SettingsEntry> {
        entries?.let { return it }
        val result = screens.flatMap { parse(context, it) }
        entries = result
        return result
    }

    private fun parse(context: Context, screen: SettingsScreen): List<SettingsEntry> {
        val result = ArrayList<SettingsEntry>()
        val screenTitle = context.getString(screen.title)
        var categoryTitle: String? = null

        runCatching {
            context.resources.getXml(screen.xml).use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue

                    if (parser.name.endsWith("PreferenceCategory")) {
                        categoryTitle = parser.text(context, "title")
                        continue
                    }

                    val key = parser.getAttributeValue(ANDROID_NS, "key") ?: continue
                    val title = parser.text(context, "title") ?: continue

                    result.add(
                        SettingsEntry(
                            key = key,
                            title = title,
                            summary = parser.text(context, "summary").orEmpty(),
                            screenTitle = screenTitle,
                            categoryTitle = categoryTitle,
                            fragment = screen.fragment
                        )
                    )
                }
            }
        }

        return result
    }

    private fun XmlResourceParser.text(context: Context, attribute: String): String? {
        val resId = getAttributeResourceValue(ANDROID_NS, attribute, 0)
        if (resId != 0) return runCatching { context.getString(resId) }.getOrNull()
        return getAttributeValue(ANDROID_NS, attribute)?.takeIf { it.isNotBlank() }
    }
}
