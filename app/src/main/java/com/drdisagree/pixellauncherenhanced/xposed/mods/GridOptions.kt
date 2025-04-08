package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt

class GridOptions (context: Context) : ModPack(context) {

    private var homeScreenGridRows = 0
    private var homeScreenGridColumns = 0
    private var appDrawerGridColumns = 0
    private var appDrawerGridRowHeightMultiplier = 1f
    private var invariantDeviceProfileInstance: Any? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            homeScreenGridRows = getSliderInt(DESKTOP_GRID_ROWS, 0)
            homeScreenGridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 0)
            appDrawerGridColumns = getSliderInt(APP_DRAWER_GRID_COLUMNS, 0)
            appDrawerGridRowHeightMultiplier =
                getSliderInt(APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER, 10) / 10f
        }

        when (key.firstOrNull()) {
            DESKTOP_GRID_ROWS,
            DESKTOP_GRID_COLUMNS,
            APP_DRAWER_GRID_COLUMNS,
            APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER,
                -> invariantDeviceProfileInstance.callMethod(
                "onConfigChanged",
                mContext
            )
        }
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")
        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")

        invariantDeviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                invariantDeviceProfileInstance = param.thisObject
            }

        deviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                param.thisObject.apply {
                    if (homeScreenGridColumns != 0) {
                        setField("numShownHotseatIcons", homeScreenGridColumns)
                    }
                    if (appDrawerGridColumns != 0) {
                        setField("numShownAllAppsColumns", appDrawerGridColumns)
                    }
                    if (homeScreenGridColumns != 0) {
                        setField("numShownHotseatIcons", homeScreenGridColumns)
                    }
                }
            }

        invariantDeviceProfileClass
            .hookMethod("initGrid")
            .runAfter { param ->
                param.thisObject.apply {
                    if (homeScreenGridRows != 0) {
                        setField("numRows", homeScreenGridRows)
                    }
                    if (homeScreenGridColumns != 0) {
                        setField("numColumns", homeScreenGridColumns)
                        setField("numShownHotseatIcons", homeScreenGridColumns)
                    }
                }
            }

        deviceProfileClass
            .hookMethod(
                "updateIconSize",
                "autoResizeAllAppsCells"
            )
            .runAfter { param ->
                if (appDrawerGridRowHeightMultiplier == 1f) return@runAfter

                val allAppsCellHeightPx = param.thisObject.getField("allAppsCellHeightPx") as Int
                val allAppsIconDrawablePaddingPx = 0

                param.thisObject.setField(
                    "allAppsCellHeightPx",
                    (allAppsCellHeightPx * appDrawerGridRowHeightMultiplier).roundToInt()
                )
                param.thisObject.setField(
                    "allAppsIconDrawablePaddingPx",
                    allAppsIconDrawablePaddingPx
                )
            }
    }
}