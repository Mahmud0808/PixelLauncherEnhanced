package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PREVENT_WALLPAPER_DIMMING_RESTART
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam


class PreventWallpaperDimmingRestart (context: Context) : ModPack(context) {

    private var preventWallpaperDimmingRestartEnabled = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            preventWallpaperDimmingRestartEnabled = getBoolean(PREVENT_WALLPAPER_DIMMING_RESTART, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val wallpaperColorChangedListener = findClass("com.android.launcher3.util.WallpaperColorHints\$onColorsChangedListener$1")
        wallpaperColorChangedListener.hookMethod("onColorsChanged").runBefore { param ->
            if (preventWallpaperDimmingRestartEnabled) {
                param.setResult(null)
            }
        }
    }
}
