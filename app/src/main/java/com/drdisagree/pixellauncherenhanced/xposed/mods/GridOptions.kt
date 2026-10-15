package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Point
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_DATABASE_ICON_COUNT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadModel
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt

class GridOptions(context: Context) : ModPack(context) {

    private var homeScreenGridRows = 0
    private var homeScreenGridColumns = 0
    private var appDrawerGridColumns = 0
    private var appDrawerGridRowHeightMultiplier = 1f
    private var dockDatabaseIconCount = 0
    private var folderGridColumns = 0
    private var folderGridRows = 0

    private val hotseatIcons: Int
        get() = homeScreenGridColumns

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            homeScreenGridRows = getSliderInt(DESKTOP_GRID_ROWS, 0)
            homeScreenGridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 0)
            appDrawerGridColumns = getSliderInt(APP_DRAWER_GRID_COLUMNS, 0)
            appDrawerGridRowHeightMultiplier =
                getSliderFloat(APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER, 10f) / 10f
            dockDatabaseIconCount = getInt(DOCK_DATABASE_ICON_COUNT, 0)
            folderGridColumns = getSliderInt(FOLDER_GRID_COLUMNS, 0)
            folderGridRows = getSliderInt(FOLDER_GRID_ROWS, 0)
        }

        when (key.firstOrNull()) {
            DESKTOP_GRID_ROWS,
            DESKTOP_GRID_COLUMNS,
            APP_DRAWER_GRID_COLUMNS,
            APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER -> reloadLauncher(mContext)

            FOLDER_GRID_COLUMNS,
            FOLDER_GRID_ROWS -> {
                reloadLauncher(mContext)
                reloadModel()
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")
        val deviceProfileBuilderClass = findClass(
            $$"com.android.launcher3.DeviceProfile$Builder",
            "com.android.launcher3.deviceprofile.DeviceProfileBuilder"
        )
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")

        fun Any.hookDeviceProfile() {
            val temp = getFieldSilently("iconSizePx") as? Int
            val mHotseatProfile = getAnyFieldSilently("mHotseatProfile", "hotseatProfile")
            val mDisplayOptionSpec = getAnyFieldSilently("mDisplayOptionSpec", "displayOptionSpec")

            if (hotseatIcons != 0) {
                setFieldSilently("numShownHotseatIcons", hotseatIcons)
                mHotseatProfile?.setField("numShownIcons", hotseatIcons)
            }

            if (appDrawerGridColumns != 0) {
                setFieldSilently("numShownAllAppsColumns", appDrawerGridColumns)

                if (temp == null) {
                    val mAllAppsProfile = getAnyFieldSilently("mAllAppsProfile", "allAppsProfile")
                    mAllAppsProfile.setFieldSilently("numShownAllAppsColumns", appDrawerGridColumns)
                }
            }

            mDisplayOptionSpec?.apply {
                if (hotseatIcons != 0) {
                    setField("numShownHotseatIcons", hotseatIcons)
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
                if (param.args.size >= 3) {
                    val closestProfile = param.args[2].getFieldSilently("grid") ?: return@runBefore

                    if (hotseatIcons != 0) {
                        closestProfile.setFieldSilently("numHotseatIcons", hotseatIcons)
                    }
                    closestProfile.patchDatabaseHotseatIcons()
                    closestProfile.patchFolderGrid()
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
                    if (hotseatIcons != 0) {
                        setFieldSilently("numShownHotseatIcons", hotseatIcons)
                    }
                    patchDatabaseHotseatIcons()
                    patchFolderGrid()
                }
            }

        fun Any?.patchGrid() {
            val closestProfile = this?.getFieldSilently("grid") ?: return
            val displayOptionSpecs = listOfNotNull(
                closestProfile.getFieldSilently("displayOptionSpec"),
                closestProfile.getFieldSilently("twoPanelDisplayOptionSpec")
            )

            if (homeScreenGridRows != 0) {
                closestProfile.setFieldSilently("numRows", homeScreenGridRows)
            }
            if (homeScreenGridColumns != 0) {
                closestProfile.setFieldSilently("numColumns", homeScreenGridColumns)
            }
            if (hotseatIcons != 0) {
                closestProfile.setFieldSilently("numHotseatIcons", hotseatIcons)
                displayOptionSpecs.forEach { it.setFieldSilently("numShownHotseatIcons", hotseatIcons) }
            }
            closestProfile.patchDatabaseHotseatIcons()
            closestProfile.patchFolderGrid()
            if (appDrawerGridColumns != 0) {
                closestProfile.setFieldSilently("numAllAppsColumns", appDrawerGridColumns)
                closestProfile.setFieldSilently("numDatabaseAllAppsColumns", appDrawerGridColumns)
                displayOptionSpecs.forEach { it.setFieldSilently("numAllAppsColumns", appDrawerGridColumns) }
            }
        }

        invariantDeviceProfileClass
            .hookMethod("invDistWeightedInterpolate")
            .suppressError()
            .runAfter { param -> param.result.patchGrid() }

        findClass("com.android.launcher3.deviceprofile.parser.DisplayOption", suppressError = true)
            .hookMethod("parseWeightedPredefinedDisplayOption")
            .suppressError()
            .runAfter { param -> param.result.patchGrid() }

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

        listOf(
            $$"com.android.launcher3.DeviceProfile$Builder",
            "com.android.launcher3.deviceprofile.DeviceProfileBuilder"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("build", "createFolderProfile")
                .suppressError()
                .runBefore { param ->
                    param.thisObject.getAnyFieldSilently("mInv", "inv")?.patchFolderGrid()
                }
        }

        deviceProfileClass
            .hookConstructor()
            .runBefore { param ->
                param.args.firstOrNull { invariantDeviceProfileClass?.isInstance(it) == true }?.patchFolderGrid()
            }
            .runAfter { param ->
                param.thisObject.fitFolderToScreen(LEGACY_FOLDER_FIELDS)
                param.thisObject.getAnyFieldSilently("mFolderProfile", "folderProfile")?.fitFolderToScreen(FOLDER_PROFILE_FIELDS)
            }

        findClass("com.android.launcher3.folder.FolderGridOrganizer", suppressError = true)
            .hookMethod("setContentSize")
            .suppressError()
            .runAfter { param ->
                if (folderGridColumns == 0) return@runAfter

                val count = param.args.getOrNull(0) as? Int ?: return@runAfter
                if (count <= 0) return@runAfter

                val organizer = param.thisObject
                val maxColumns = organizer.getFieldSilently("mMaxCountX") as? Int ?: return@runAfter
                val maxRows = organizer.getFieldSilently("mMaxCountY") as? Int ?: return@runAfter
                val columns = minOf(count, maxColumns).coerceAtLeast(1)
                val rows = minOf((count + columns - 1) / columns, maxRows).coerceAtLeast(1)

                organizer.setFieldSilently("mCountX", columns)
                organizer.setFieldSilently("mCountY", rows)
            }

        findClass("com.android.launcher3.folder.FolderGridOrganizer", suppressError = true)
            .hookMethod("isItemInPreview")
            .suppressError()
            .runBefore { param ->
                if (folderGridColumns == 0) return@runBefore

                val page = if (param.args.size >= 2) param.args[0] as? Int ?: return@runBefore else 0
                val rank = param.args.lastOrNull() as? Int ?: return@runBefore
                param.result = page == 0 && rank < FOLDER_PREVIEW_ITEMS
            }

        findClass("com.android.launcher3.deviceprofile.FolderProfile", suppressError = true)
            .hookConstructor()
            .suppressError()
            .runAfter { param -> param.thisObject.fitFolderToScreen(FOLDER_PROFILE_FIELDS) }

        deviceProfileClass
            .hookMethod("updateFolderCellSize", "updateAvailableFolderCellDimensions")
            .suppressError()
            .runAfter { param ->
                param.thisObject.fitFolderToScreen(LEGACY_FOLDER_FIELDS)
                param.result?.fitFolderToScreen(FOLDER_PROFILE_FIELDS)
            }

        findClass("com.android.launcher3.responsive.CalculatedResponsiveSpec", suppressError = true)
            .apply {
                hookConstructor()
                    .suppressError()
                    .runAfter { param -> param.thisObject.fitResponsiveSpec() }
                hookMethod("updateRemainderSpaces")
                    .suppressError()
                    .runAfter { param -> param.thisObject.fitResponsiveSpec() }
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

    private fun Any.patchDatabaseHotseatIcons() {
        if (hotseatIcons == 0 && dockDatabaseIconCount == 0) return

        val current = getFieldSilently("numDatabaseHotseatIcons") as? Int ?: return
        val required = maxOf(current, hotseatIcons, dockDatabaseIconCount)

        if (required != current) setFieldSilently("numDatabaseHotseatIcons", required)

        if (required > dockDatabaseIconCount) {
            dockDatabaseIconCount = required
            runCatching { Xprefs.edit().putInt(DOCK_DATABASE_ICON_COUNT, required).apply() }
        }
    }

    private fun Any.fitResponsiveSpec() {
        if (homeScreenGridColumns == 0 && homeScreenGridRows == 0 && appDrawerGridColumns == 0 &&
            folderGridColumns == 0 && folderGridRows == 0
        ) return

        val available = getFieldSilently("availableSpace") as? Int ?: return
        val cells = getFieldSilently("cells") as? Int ?: return
        val startPadding = getFieldSilently("startPaddingPx") as? Int ?: return
        val endPadding = getFieldSilently("endPaddingPx") as? Int ?: return
        val gutter = getFieldSilently("gutterPx") as? Int ?: return
        if (cells <= 0 || available <= 0 || (startPadding >= 0 && endPadding >= 0)) return

        val padding = gutter.coerceAtLeast(0)
        val cellSize = (available - padding * 2 - gutter * (cells - 1)) / cells
        if (cellSize <= 0) return

        val used = cellSize * cells + gutter * (cells - 1)
        setFieldSilently("cellSizePx", cellSize)
        setFieldSilently("startPaddingPx", (available - used) / 2)
        setFieldSilently("endPaddingPx", available - used - (available - used) / 2)
    }

    private fun Any.patchFolderGrid() {
        listOf(
            "numFolderColumns" to folderGridColumns,
            "numFolderRows" to folderGridRows
        ).forEach { (field, value) ->
            if (value == 0) return@forEach

            when (val current = getFieldSilently(field)) {
                is Int -> setFieldSilently(field, value)
                is IntArray -> setFieldSilently(field, IntArray(current.size) { value })
            }
        }
    }

    private fun Any.fitFolderToScreen(fields: FolderFields) {
        if (folderGridColumns == 0 && folderGridRows == 0) return

        val cellWidth = getFieldSilently(fields.cellWidth) as? Int ?: return
        val cellHeight = getFieldSilently(fields.cellHeight) as? Int ?: return
        val border = getFieldSilently(fields.borderSpace)
        val borderX = (border as? Point)?.x ?: border as? Int ?: 0
        val borderY = (border as? Point)?.y ?: border as? Int ?: 0
        val paddingSide = getFieldSilently(fields.paddingSide) as? Int ?: 0
        val paddingTop = getFieldSilently(fields.paddingTop) as? Int ?: 0
        val footer = getFieldSilently(fields.footer) as? Int ?: 0

        val columns = (getFieldSilently("numColumns") as? Int)?.takeIf { it > 0 } ?: folderGridColumns
        val rows = (getFieldSilently("numRows") as? Int)?.takeIf { it > 0 } ?: folderGridRows
        val metrics = mContext.resources.displayMetrics
        val margin = FOLDER_SCREEN_MARGIN_DP * metrics.density

        var scale = 1f
        if (columns > 0) {
            val required = columns * cellWidth + (columns - 1) * borderX + paddingSide * 2
            val available = metrics.widthPixels - margin * 2
            if (required > available) scale = minOf(scale, available / required)
        }
        if (rows > 0) {
            val required = rows * cellHeight + (rows - 1) * borderY + paddingTop + footer
            val available = metrics.heightPixels * FOLDER_MAX_HEIGHT_RATIO
            if (required > available) scale = minOf(scale, available / required)
        }
        if (scale >= 1f) return

        fun scaled(value: Int) = (value * scale).roundToInt()

        setFieldSilently(fields.cellWidth, scaled(cellWidth))
        setFieldSilently(fields.cellHeight, scaled(cellHeight))
        listOf(fields.iconSize, fields.textSize, fields.drawablePadding).forEach { field ->
            (getFieldSilently(field) as? Int)?.let { setFieldSilently(field, scaled(it)) }
        }
        when (border) {
            is Point -> setFieldSilently(fields.borderSpace, Point(scaled(border.x), scaled(border.y)))
            is Int -> setFieldSilently(fields.borderSpace, scaled(border))
        }
    }

    private class FolderFields(
        val cellWidth: String,
        val cellHeight: String,
        val iconSize: String,
        val textSize: String,
        val drawablePadding: String,
        val borderSpace: String,
        val paddingSide: String,
        val paddingTop: String,
        val footer: String
    )

    companion object {
        private const val FOLDER_PREVIEW_ITEMS = 4
        private const val FOLDER_SCREEN_MARGIN_DP = 16
        private const val FOLDER_MAX_HEIGHT_RATIO = 0.8f
        private val FOLDER_PROFILE_FIELDS = FolderFields(
            cellWidth = "cellWidthPx",
            cellHeight = "cellHeightPx",
            iconSize = "childIconSizePx",
            textSize = "childTextSizePx",
            drawablePadding = "childDrawablePaddingPx",
            borderSpace = "cellLayoutBorderSpacePx",
            paddingSide = "contentPaddingLeftRight",
            paddingTop = "contentPaddingTop",
            footer = "footerHeightPx"
        )

        private val LEGACY_FOLDER_FIELDS = FolderFields(
            cellWidth = "folderCellWidthPx",
            cellHeight = "folderCellHeightPx",
            iconSize = "folderChildIconSizePx",
            textSize = "folderChildTextSizePx",
            drawablePadding = "folderChildDrawablePaddingPx",
            borderSpace = "folderCellLayoutBorderSpacePx",
            paddingSide = "folderContentPaddingLeftRight",
            paddingTop = "folderContentPaddingTop",
            footer = "folderFooterHeightPx"
        )
    }
}
