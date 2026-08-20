package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_COLUMNS
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
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt

class GridOptions (context: Context) : ModPack(context) {

    private var homeScreenGridRows = 0
    private var homeScreenGridColumns = 0
    private var dockColumns = 0
    private var appDrawerGridColumns = 0
    private var appDrawerGridRowHeightMultiplier = 1f

    // The dock (Hotseat) column count to apply. Falls back to the homescreen
    // column count when no explicit dock value is set, to preserve the
    // original coupled behavior. Returns 0 (no-op) when neither is set.
    private val effectiveDockColumns: Int
        get() = if (dockColumns != 0) dockColumns else homeScreenGridColumns

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            homeScreenGridRows = getSliderInt(DESKTOP_GRID_ROWS, 0)
            homeScreenGridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 0)
            dockColumns = getSliderInt(DESKTOP_DOCK_COLUMNS, 0)
            appDrawerGridColumns = getSliderInt(APP_DRAWER_GRID_COLUMNS, 0)
            appDrawerGridRowHeightMultiplier =
                getSliderFloat(APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER, 10f) / 10f
        }

        when (key.firstOrNull()) {
            DESKTOP_GRID_ROWS,
            DESKTOP_GRID_COLUMNS,
            DESKTOP_DOCK_COLUMNS,
            APP_DRAWER_GRID_COLUMNS,
            APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")
        val deviceProfileBuilderClass = findClass($$"com.android.launcher3.DeviceProfile$Builder")
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")

        fun Any.hookDeviceProfile() {
            val temp = getFieldSilently("iconSizePx") as? Int
            val mHotseatProfile = getFieldSilently("mHotseatProfile")
            val mDisplayOptionSpec = getFieldSilently("mDisplayOptionSpec")

            if (effectiveDockColumns != 0) {
                setFieldSilently("numShownHotseatIcons", effectiveDockColumns)
                mHotseatProfile?.setField("numShownIcons", effectiveDockColumns)
            }

            if (appDrawerGridColumns != 0) {
                setFieldSilently("numShownAllAppsColumns", appDrawerGridColumns)

                if (temp == null) {
                    val mAllAppsProfile = getField("mAllAppsProfile")
                    mAllAppsProfile.setFieldSilently("numShownAllAppsColumns", appDrawerGridColumns)
                }
            }

            mDisplayOptionSpec?.apply {
                if (effectiveDockColumns != 0) {
                    setField("numShownHotseatIcons", effectiveDockColumns)
                }
                if (appDrawerGridColumns != 0) {
                    setField("numAllAppsColumns", appDrawerGridColumns)
                }
            }
        }

        deviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                param.thisObject.hookDeviceProfile()
            }

        deviceProfileBuilderClass
            .hookMethod("build")
            .runAfter { param ->
                param.result.hookDeviceProfile()
            }

        invariantDeviceProfileClass
            .hookMethod("initGrid")
            .runBefore { param ->
                if (effectiveDockColumns != 0 && param.args.size >= 3) {
                    val displayOption = param.args[2]
                    val closestProfile = displayOption.getField("grid")

                    closestProfile.setField("numHotseatIcons", effectiveDockColumns)
                }
            }
            .runAfter { param ->
                param.thisObject.apply {
                    if (homeScreenGridRows != 0) {
                        setField("numRows", homeScreenGridRows)
                    }
                    if (homeScreenGridColumns != 0) {
                        setField("numColumns", homeScreenGridColumns)
                    }
                    if (effectiveDockColumns != 0) {
                        setField("numShownHotseatIcons", effectiveDockColumns)
                    }
                }
            }

        invariantDeviceProfileClass
            .hookMethod("invDistWeightedInterpolate")
            .runAfter { param ->
                val displayOption = param.result
                val closestProfile = displayOption.getField("grid")

                if (homeScreenGridRows != 0) {
                    closestProfile.setFieldSilently("numRows", homeScreenGridRows)
                }
                if (homeScreenGridColumns != 0) {
                    closestProfile.setFieldSilently("numColumns", homeScreenGridColumns)
                }
                if (effectiveDockColumns != 0) {
                    closestProfile.setFieldSilently("numHotseatIcons", effectiveDockColumns)
                    closestProfile.setFieldSilently("numDatabaseHotseatIcons", effectiveDockColumns)
                }
                if (appDrawerGridColumns != 0) {
                    closestProfile.setFieldSilently("numAllAppsColumns", homeScreenGridColumns)
                    closestProfile.setFieldSilently("numDatabaseAllAppsColumns", homeScreenGridColumns)
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