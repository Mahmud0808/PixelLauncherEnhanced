package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_DARK_STATUSBAR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.MethodHookHelper
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections
import java.util.WeakHashMap

class DarkStatusbar(context: Context) : ModPack(context) {

    private var darkStatusbarEnabled = false
    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            darkStatusbarEnabled = getBoolean(LAUNCHER_DARK_STATUSBAR, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_DARK_STATUSBAR -> Handler(Looper.getMainLooper()).post { reapplyAll() }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        findClass("com.android.launcher3.util.SystemUiController", suppressError = true)
            ?.let {
                XposedHelpers.findMethodExactIfExists(
                    it,
                    "updateUiState",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            }
            ?.let { MethodHookHelper(it) }
            ?.runBefore { param ->
                if (!darkStatusbarEnabled || param.args[0] != UI_STATE_BASE_WINDOW) return@runBefore
                val flags = param.args[1] as? Int ?: return@runBefore
                param.args[1] = (flags and NAV_FLAGS) or FLAG_LIGHT_STATUS
            }

        listOf(
            "com.android.launcher3.Launcher",
            "com.android.launcher3.uioverrides.QuickstepLauncher",
            "com.android.quickstep.RecentsActivity"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("onCreate")
                .suppressError()
                .runAfter { param ->
                    val activity = param.thisObject as? Activity ?: return@runAfter
                    activities.add(activity)
                    if (darkStatusbarEnabled) reapply(activity)
                }
        }
    }

    private fun reapplyAll() {
        activities.toList().forEach { reapply(it) }
    }

    private fun reapply(activity: Activity) {
        if (activity.isDestroyed) return
        val stockFlags = if (isWorkspaceDarkText(activity)) STOCK_LIGHT_FLAGS else STOCK_DARK_FLAGS
        activity.callMethodSilently("getSystemUiController")
            ?.callMethodSilently("updateUiState", UI_STATE_BASE_WINDOW, stockFlags)
    }

    @SuppressLint("DiscouragedApi")
    private fun isWorkspaceDarkText(activity: Activity): Boolean {
        val attr = activity.resources.getIdentifier("isWorkspaceDarkText", "attr", activity.packageName)
        if (attr == 0) return false
        val value = TypedValue()
        return activity.theme.resolveAttribute(attr, value, true) && value.data != 0
    }

    companion object {
        private const val UI_STATE_BASE_WINDOW = 0
        private const val FLAG_LIGHT_NAV = 1 shl 0
        private const val FLAG_DARK_NAV = 1 shl 1
        private const val FLAG_LIGHT_STATUS = 1 shl 2
        private const val FLAG_DARK_STATUS = 1 shl 3
        private const val NAV_FLAGS = FLAG_LIGHT_NAV or FLAG_DARK_NAV
        private const val STOCK_LIGHT_FLAGS = FLAG_LIGHT_STATUS or FLAG_LIGHT_NAV
        private const val STOCK_DARK_FLAGS = FLAG_DARK_STATUS or FLAG_DARK_NAV
    }
}
