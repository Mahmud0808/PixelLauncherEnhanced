package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_DARK_STATUSBAR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class DarkStatusbar (context: Context) : ModPack(context) {

    private var darkStatusbarEnabled = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            darkStatusbarEnabled = getBoolean(LAUNCHER_DARK_STATUSBAR, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_DARK_STATUSBAR -> restartLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        fun forceDark(param: XC_MethodHook.MethodHookParam) {
            if (!darkStatusbarEnabled) return

            param.thisObject
                .callMethod("getSystemUiController")
                .callMethod("updateUiState", UI_STATE_BASE_WINDOW, FLAG_LIGHT_STATUS)
        }

        val launcherClass = findClass("com.android.launcher3.Launcher")

        launcherClass
            .hookMethod("onCreate")
            .runAfter(::forceDark)

        val quickstepLauncherClass =
            findClass("com.android.launcher3.uioverrides.QuickstepLauncher")

        quickstepLauncherClass
            .hookMethod("onCreate")
            .runAfter(::forceDark)

        val recentsActivityClass = findClass("com.android.quickstep.RecentsActivity")

        recentsActivityClass
            .hookMethod("onCreate")
            .runAfter(::forceDark)
    }

    companion object {
        const val UI_STATE_BASE_WINDOW = 0
        const val FLAG_LIGHT_STATUS: Int = 1 shl 2
    }
}