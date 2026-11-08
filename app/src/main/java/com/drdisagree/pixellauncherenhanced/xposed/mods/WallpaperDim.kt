package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PREVENT_WALLPAPER_DIMMING_RESTART
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage

class WallpaperDim(context: Context) : ModPack(context) {

    private var preventWallpaperDimmingRestart = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            preventWallpaperDimmingRestart = getBoolean(PREVENT_WALLPAPER_DIMMING_RESTART, false)
        }
    }

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
        val wallpaperColorChangedListener = findClass(
            $$"com.android.launcher3.util.WallpaperColorHints$onColorsChangedListener$1",
            suppressError = true
        )

        wallpaperColorChangedListener
            .hookMethod("onColorsChanged")
            .suppressError() // may not be available on a15
            .runBefore { param ->
                if (preventWallpaperDimmingRestart) {
                    param.setResult(null)
                }
            }
    }
}