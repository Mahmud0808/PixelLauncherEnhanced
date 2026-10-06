package com.drdisagree.pixellauncherenhanced.xposed

import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.xposed.mods.AppDrawerMode
import com.drdisagree.pixellauncherenhanced.xposed.mods.AppDrawerTabs
import com.drdisagree.pixellauncherenhanced.xposed.mods.DisableDock
import com.drdisagree.pixellauncherenhanced.xposed.mods.IconPacks
import com.drdisagree.pixellauncherenhanced.xposed.mods.AppShortcuts
import com.drdisagree.pixellauncherenhanced.xposed.mods.RecentsMemInfo
import com.drdisagree.pixellauncherenhanced.xposed.mods.WallpaperDim
import com.drdisagree.pixellauncherenhanced.xposed.mods.WidgetResize
import com.drdisagree.pixellauncherenhanced.xposed.mods.ClearAllButton
import com.drdisagree.pixellauncherenhanced.xposed.mods.PageIndicator
import com.drdisagree.pixellauncherenhanced.xposed.mods.DarkStatusbar
import com.drdisagree.pixellauncherenhanced.xposed.mods.DrawerSearchbar
import com.drdisagree.pixellauncherenhanced.xposed.mods.FreeformMod
import com.drdisagree.pixellauncherenhanced.xposed.mods.GestureMod
import com.drdisagree.pixellauncherenhanced.xposed.mods.GridOptions
import com.drdisagree.pixellauncherenhanced.xposed.mods.HideApps
import com.drdisagree.pixellauncherenhanced.xposed.mods.HideStatusbar
import com.drdisagree.pixellauncherenhanced.xposed.mods.HomePreviewAccess
import com.drdisagree.pixellauncherenhanced.xposed.mods.HotseatMod
import com.drdisagree.pixellauncherenhanced.xposed.mods.IconLabels
import com.drdisagree.pixellauncherenhanced.xposed.mods.IconTextSize
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
        HomePreviewAccess::class.java,
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
        WidgetResize::class.java,
        DrawerSearchbar::class.java,
        ClearAllButton::class.java,
        RecentsMemInfo::class.java,
        GridOptions::class.java,
        HideApps::class.java,
        ShortcutBadge::class.java,
        WallpaperZoom::class.java,
        QuickLaunch::class.java,
        TaskbarHandle::class.java,
        DarkStatusbar::class.java,
        PageIndicator::class.java,
        WallpaperDim::class.java,
        AppShortcuts::class.java,
        AppDrawerMode::class.java,
        AppDrawerTabs::class.java,
        DisableDock::class.java,
        IconPacks::class.java,
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
