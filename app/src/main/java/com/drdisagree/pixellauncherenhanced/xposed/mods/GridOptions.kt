package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage

class GridOptions (context: Context) : ModPack(context) {

    private var gridRows = 4
    private var gridColumns = 4

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            gridRows = getSliderInt(DESKTOP_GRID_ROWS, 4)
            gridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 4)
        }
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")

        invariantDeviceProfileClass
            .hookMethod("initGrid")
            .runAfter { param ->
                val idp = param.thisObject

                if (idp.getField("numColumns") != gridColumns || idp.getField("numRows") != gridRows) {
                    idp.setField("numColumns", gridColumns)
                    idp.setField("numRows", gridRows)
                }
            }

        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")

        deviceProfileClass
            .hookMethod("updateIconSize")
            .runBefore { param ->
                val context = param.args[1] as Context
                val displayMetrics = context.resources.displayMetrics

                val iconSizePx = (displayMetrics.widthPixels / gridColumns) * 0.8f
                val iconSizeDp = iconSizePx / displayMetrics.density

                if (param.args[0] != iconSizeDp) {
                    param.args[0] = iconSizeDp
                }
            }
    }
}