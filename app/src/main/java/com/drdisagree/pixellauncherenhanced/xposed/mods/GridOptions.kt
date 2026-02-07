package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
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

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            homeScreenGridRows = getSliderInt(DESKTOP_GRID_ROWS, 0)
            homeScreenGridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 0)
            appDrawerGridColumns = getSliderInt(APP_DRAWER_GRID_COLUMNS, 0)
            appDrawerGridRowHeightMultiplier =
                getSliderFloat(APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER, 10f) / 10f
        }

        when (key.firstOrNull()) {
            DESKTOP_GRID_ROWS,
            DESKTOP_GRID_COLUMNS,
            APP_DRAWER_GRID_COLUMNS,
            APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")
        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")

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
                }
            }

        invariantDeviceProfileClass
            .hookMethod("initGrid")
            .runBefore { param ->
                if (homeScreenGridColumns != 0 && param.args.size >= 3) {
                    val displayOption = param.args[2]
                    val closestProfile = displayOption.getField("grid")
                    closestProfile.setField("numHotseatIcons", homeScreenGridColumns)
                }
            }
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
            .suppressError()
            .runAfter { param ->
                if (appDrawerGridRowHeightMultiplier == 1f) return@runAfter

                val allAppsCellHeightPx =
                    param.thisObject.getFieldSilently("allAppsCellHeightPx") as? Int
                val allAppsIconDrawablePaddingPx = 0

                if (allAppsCellHeightPx != null) {
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

        val allAppsProfileClass = findClass(
            "com.android.launcher3.deviceprofile.AllAppsProfile",
            suppressError = Build.VERSION.SDK_INT <= Build.VERSION_CODES.VANILLA_ICE_CREAM
        )

        allAppsProfileClass
            .hookConstructor()
            .runAfter { param ->
                if (appDrawerGridRowHeightMultiplier == 1f) return@runAfter

                val cellHeightPx = param.thisObject.getField("cellHeightPx") as Int
                val iconDrawablePaddingPx = 0

                param.thisObject.setField(
                    "cellHeightPx",
                    (cellHeightPx * appDrawerGridRowHeightMultiplier).roundToInt()
                )
                param.thisObject.setField(
                    "iconDrawablePaddingPx",
                    iconDrawablePaddingPx
                )
            }
    }
}