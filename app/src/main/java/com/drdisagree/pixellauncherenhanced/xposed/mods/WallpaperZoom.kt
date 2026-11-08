package com.drdisagree.pixellauncherenhanced.xposed.mods

import com.drdisagree.pixellauncherenhanced.xposed.reflection.getFieldSilently
import android.app.WallpaperManager
import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ALLOW_WALLPAPER_ZOOMING
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.WallpaperGlass
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage
import java.util.Collections
import java.util.WeakHashMap

class WallpaperZoom(context: Context) : ModPack(context) {

    private var mWallpaperZooming = true
    private val depthWallpaperManagers: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            mWallpaperZooming = getBoolean(ALLOW_WALLPAPER_ZOOMING, true)
        }

        if (WallpaperGlass.zoomEnabled != mWallpaperZooming) {
            WallpaperGlass.zoomEnabled = mWallpaperZooming
            WallpaperGlass.onOffsetsChanged()
        }
    }

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
        val baseDepthControllerClass = findClass(
            "com.android.quickstep.util.BaseDepthController",
            "com.android.quickstep.util.BaseDepthControllerImpl",
        )

        baseDepthControllerClass
            .hookConstructor()
            .runAfter { param ->
                param.thisObject.getFieldSilently("mWallpaperManager")?.let { depthWallpaperManagers.add(it) }
            }

        WallpaperManager::class.java
            .hookMethod("setWallpaperZoomOut")
            .runBefore { param ->
                if (!mWallpaperZooming && param.thisObject in depthWallpaperManagers) {
                    param.args[1] = 1f
                }
            }
    }
}