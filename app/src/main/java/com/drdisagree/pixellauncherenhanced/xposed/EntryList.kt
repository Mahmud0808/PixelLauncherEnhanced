package com.drdisagree.pixellauncherenhanced.xposed

import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.xposed.mods.ClearAllButton
import com.drdisagree.pixellauncherenhanced.xposed.mods.DrawerSearchbar
import com.drdisagree.pixellauncherenhanced.xposed.mods.FreeformMod
import com.drdisagree.pixellauncherenhanced.xposed.mods.GestureMod
import com.drdisagree.pixellauncherenhanced.xposed.mods.GridOptions
import com.drdisagree.pixellauncherenhanced.xposed.mods.HideApps
import com.drdisagree.pixellauncherenhanced.xposed.mods.HideStatusbar
import com.drdisagree.pixellauncherenhanced.xposed.mods.HotseatMod
import com.drdisagree.pixellauncherenhanced.xposed.mods.IconLabels
import com.drdisagree.pixellauncherenhanced.xposed.mods.IconTextSize
import com.drdisagree.pixellauncherenhanced.xposed.mods.IconUpdater
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherSettings
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils
import com.drdisagree.pixellauncherenhanced.xposed.mods.LockLayout
import com.drdisagree.pixellauncherenhanced.xposed.mods.OpacityModifier
import com.drdisagree.pixellauncherenhanced.xposed.mods.QuickLaunch
import com.drdisagree.pixellauncherenhanced.xposed.mods.ShortcutBadge
import com.drdisagree.pixellauncherenhanced.xposed.mods.SmartSpace
import com.drdisagree.pixellauncherenhanced.xposed.mods.TaskbarHandle
import com.drdisagree.pixellauncherenhanced.xposed.mods.ThemedIcons
import com.drdisagree.pixellauncherenhanced.xposed.mods.ThemedIconsColor
import com.drdisagree.pixellauncherenhanced.xposed.mods.TopShadow
import com.drdisagree.pixellauncherenhanced.xposed.mods.WallpaperZoom
import com.drdisagree.pixellauncherenhanced.xposed.utils.BroadcastHook

object EntryList {

    private val launcherModPacks: List<Class<out ModPack>> = listOf(
        BroadcastHook::class.java,
        LauncherUtils::class.java,
        IconUpdater::class.java,
        IconLabels::class.java,
        ThemedIcons::class.java,
        ThemedIconsColor::class.java,
        OpacityModifier::class.java,
        GestureMod::class.java,
        HotseatMod::class.java,
        FreeformMod::class.java,
        IconTextSize::class.java,
        SmartSpace::class.java,
        HideStatusbar::class.java,
        TopShadow::class.java,
        LauncherSettings::class.java,
        LockLayout::class.java,
        DrawerSearchbar::class.java,
        ClearAllButton::class.java,
        GridOptions::class.java,
        HideApps::class.java,
        ShortcutBadge::class.java,
        WallpaperZoom::class.java,
        QuickLaunch::class.java,
        TaskbarHandle::class.java
    )

    fun getEntries(packageName: String): ArrayList<Class<out ModPack>> {
        val modPacks = ArrayList<Class<out ModPack>>()

        when (packageName) {
            PIXEL_LAUNCHER_PACKAGE,
            LAUNCHER3_PACKAGE -> {
                modPacks.addAll(launcherModPacks)
            }
        }

        return modPacks
    }
}
