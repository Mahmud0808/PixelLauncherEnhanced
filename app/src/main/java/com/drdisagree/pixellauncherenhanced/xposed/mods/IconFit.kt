package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import kotlin.math.roundToInt

object IconFit {

    private const val MIN_GAP_DP = 8

    private val WORKSPACE_FIELDS = IconFields(
        iconSize = "iconSizePx",
        cellWidth = "cellWidthPx",
        borderSpace = "cellLayoutBorderSpacePx"
    )

    private val ALL_APPS_PROFILE_FIELDS = IconFields(
        iconSize = "iconSizePx",
        cellWidth = "cellWidthPx",
        borderSpace = "borderSpacePx"
    )

    private val FOLDER_PROFILE_FIELDS = IconFields(
        iconSize = "childIconSizePx",
        cellWidth = "cellWidthPx",
        borderSpace = "cellLayoutBorderSpacePx"
    )

    private val LEGACY_FOLDER_FIELDS = IconFields(
        iconSize = "folderChildIconSizePx",
        cellWidth = "folderCellWidthPx",
        borderSpace = "folderCellLayoutBorderSpacePx"
    )

    private val LEGACY_ALL_APPS_FIELDS = IconFields(
        iconSize = "allAppsIconSizePx",
        cellWidth = "allAppsCellWidthPx",
        borderSpace = "allAppsBorderSpacePx"
    )

    fun fitIconsToCells(deviceProfile: Any, context: Context) {
        val minGap = MIN_GAP_DP * context.resources.displayMetrics.density

        deviceProfile.apply {
            val workspace = getAnyFieldSilently("mWorkspaceProfile", "workspaceProfile")
            val hotseat = getAnyFieldSilently("mHotseatProfile", "hotseatProfile")
            val hotseatWidth = hotseat?.getFieldSilently("widthPx") as? Int
                ?: ((getFieldSilently("availableWidthPx") as? Int ?: 0) -
                        (getFieldSilently("hotseatBarSidePaddingStartPx") as? Int ?: 0) -
                        (getFieldSilently("hotseatBarSidePaddingEndPx") as? Int ?: 0))
            val hotseatCount = hotseat?.getFieldSilently("numShownIcons") as? Int
                ?: getFieldSilently("numShownHotseatIcons") as? Int ?: 0
            val hotseatPitch = if (hotseatWidth > 0 && hotseatCount > 0) hotseatWidth / hotseatCount else null

            val folderProfile = getAnyFieldSilently("mFolderProfile", "folderProfile")
            val folder = folderProfile ?: this

            val scale = (workspace ?: this).fitIconSize(WORKSPACE_FIELDS, minGap, hotseatPitch)
            if (scale < 1f) {
                listOf("folderIconSizePx", "folderIconOffsetYPx").forEach { field ->
                    val owner = if (folder.getFieldSilently(field) is Int) folder else this
                    owner.scaleIntField(field, scale)
                }
            }

            folder.fitIconSize(if (folderProfile != null) FOLDER_PROFILE_FIELDS else LEGACY_FOLDER_FIELDS, minGap, null)

            val allApps = getAnyFieldSilently("mAllAppsProfile", "allAppsProfile")
            val allAppsFields = if (allApps != null) ALL_APPS_PROFILE_FIELDS else LEGACY_ALL_APPS_FIELDS
            (allApps ?: this).fitIconSize(allAppsFields, minGap, allAppsPitch(allApps, context))
        }
    }

    private fun Any.allAppsPitch(allApps: Any?, context: Context): Int? {
        val profile = allApps ?: this
        val columns = profile.getFieldSilently("numShownAllAppsColumns") as? Int
            ?: getFieldSilently("numShownAllAppsColumns") as? Int ?: return null
        if (columns <= 0) return null

        val properties = getAnyFieldSilently("mDeviceProperties", "deviceProperties") ?: this
        val width = (properties.getAnyFieldSilently("availableWidthPx", "widthPx") as? Int)
            ?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels

        val padding = (profile.getFieldSilently("padding") as? Rect)?.let { it.left + it.right }
            ?: ((getFieldSilently("allAppsLeftRightPadding") as? Int ?: 0) * 2)
        val margin = (profile.getAnyFieldSilently("leftRightMargin", "allAppsLeftRightMargin") as? Int ?: 0) * 2

        return ((width - padding - margin) / columns).takeIf { it > 0 }
    }

    private fun Any.fitIconSize(fields: IconFields, minGap: Float, extraPitch: Int?): Float {
        val iconSize = getFieldSilently(fields.iconSize) as? Int ?: return 1f
        val cellWidth = getFieldSilently(fields.cellWidth) as? Int ?: return 1f
        if (iconSize <= 0 || cellWidth <= 0) return 1f

        val border = getFieldSilently(fields.borderSpace)
        val borderX = (border as? Point)?.x ?: border as? Int ?: 0
        val pitch = listOfNotNull(cellWidth + borderX, extraPitch).min()
        val maxIconSize = (pitch - minGap).roundToInt()
        if (maxIconSize <= 0 || iconSize <= maxIconSize) return 1f

        setFieldSilently(fields.iconSize, maxIconSize)
        return maxIconSize.toFloat() / iconSize
    }

    private fun Any.scaleIntField(field: String, scale: Float) {
        (getFieldSilently(field) as? Int)?.let { setFieldSilently(field, (it * scale).roundToInt()) }
    }

    private class IconFields(
        val iconSize: String,
        val cellWidth: String,
        val borderSpace: String
    )
}
