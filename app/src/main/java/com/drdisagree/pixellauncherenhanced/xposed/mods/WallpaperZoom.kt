package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.app.WallpaperManager
import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ALLOW_WALLPAPER_ZOOMING
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.WallpaperGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections
import java.util.WeakHashMap

class WallpaperZoom(context: Context) : ModPack(context) {

    private var mWallpaperZooming = true
    private val depthWallpaperManagers: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            mWallpaperZooming = getBoolean(ALLOW_WALLPAPER_ZOOMING, true)
        }

        if (!mWallpaperZooming) {
            WallpaperGlass.zoom = 1f
            WallpaperGlass.onOffsetsChanged()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val baseDepthControllerClass = findClass(
            "com.android.quickstep.util.BaseDepthController",
            "com.android.quickstep.util.BaseDepthControllerImpl",
        )

        baseDepthControllerClass
            .hookConstructor()
            .runAfter { param ->
                param.thisObject.getField("mWallpaperManager")?.let { depthWallpaperManagers.add(it) }
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