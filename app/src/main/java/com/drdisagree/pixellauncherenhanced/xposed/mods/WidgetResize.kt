package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREE_WIDGET_RESIZE
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.reflection.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.reflection.getField
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.reflection.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.reflection.setField
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage

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

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
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
