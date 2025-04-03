package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage

class GridOptions (context: Context) : ModPack(context) {

    private var gridRows = 4
    private var gridColumns = 4
    private var currentContext = context

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            gridRows = getSliderInt(DESKTOP_GRID_ROWS, 4)
            gridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 4)
        }
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val idpClass = XposedHook.findClass("com.android.launcher3.InvariantDeviceProfile")

        idpClass?.hookMethod("initGrid")?.runAfter { param ->

            val idp = param.thisObject
            val cols = Xprefs.getSliderInt(DESKTOP_GRID_COLUMNS, 4)
            val rows = Xprefs.getSliderInt(DESKTOP_GRID_ROWS, 4)

            if (idp.getField("numColumns") != cols || idp.getField("numRows") != rows) {
                idp.setField("numColumns", cols)
                idp.setField("numRows", rows)
            }

        }

        val dpClass = XposedHook.findClass("com.android.launcher3.DeviceProfile")

        dpClass?.hookMethod("updateIconSize")?.runAfter { param ->

            val dp = param.thisObject
            val displayMetrics = currentContext.resources.displayMetrics
            val cols = Xprefs.getSliderInt(DESKTOP_GRID_COLUMNS, 4)

            val iconSizePx = (displayMetrics.widthPixels / cols) * 0.8f
            val iconSizeDp = iconSizePx / displayMetrics.density

            dp.callMethod("updateIconSize", iconSizeDp, currentContext )
        }
    }
}