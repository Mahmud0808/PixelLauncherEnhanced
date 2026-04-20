package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Color
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_DARK_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class DarkPageIndicator (context: Context) : ModPack(context) {

    private var darkPageIndicatorEnabled = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            darkPageIndicatorEnabled = getBoolean(LAUNCHER_DARK_PAGE_INDICATOR, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_DARK_PAGE_INDICATOR -> restartLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherClass = findClass("com.android.launcher3.Launcher")

        try {
            launcherClass
                .hookMethod("setupViews")
                .throwError()
                .runAfter { param ->
                    if (!darkPageIndicatorEnabled) return@runAfter

                    param.thisObject.getField("mWorkspace")
                        .callMethod("getPageIndicator")
                        .callMethod("setPaintColor", Color.BLACK)
                }
        } catch (_: Throwable) {
            launcherClass
                .hookMethod("onCreate")
                .runAfter { param ->
                    if (!darkPageIndicatorEnabled) return@runAfter

                    param.thisObject.getField("mWorkspace")
                        .callMethod("getPageIndicator")
                        .callMethod("setPaintColor", Color.BLACK)
                }
        }
    }
}