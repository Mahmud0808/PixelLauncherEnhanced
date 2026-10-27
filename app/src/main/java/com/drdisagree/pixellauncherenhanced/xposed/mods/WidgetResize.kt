package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREE_WIDGET_RESIZE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class WidgetResize(context: Context) : ModPack(context) {

    private var freeResize = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            freeResize = getBoolean(FREE_WIDGET_RESIZE, false)
        }

        when (key.firstOrNull()) {
            FREE_WIDGET_RESIZE -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        findClass("com.android.launcher3.widget.LauncherAppWidgetProviderInfo")
            .hookMethod("initSpans")
            .runAfter { param ->
                val info = param.thisObject as AppWidgetProviderInfo

                val originalMode = info.getExtraFieldSilently(ORIGINAL_RESIZE_MODE) as Int?
                    ?: info.resizeMode.also { info.setExtraField(ORIGINAL_RESIZE_MODE, it) }

                if (!freeResize) {
                    info.resizeMode = originalMode
                    return@runAfter
                }

                val idp = param.args[1]
                info.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
                info.setField("minSpanX", 1)
                info.setField("minSpanY", 1)
                info.setField("maxSpanX", idp.getField("numColumns"))
                info.setField("maxSpanY", idp.getField("numRows"))
            }
    }

    companion object {
        private const val ORIGINAL_RESIZE_MODE = "pleOriginalResizeMode"
    }
}
