package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.wallpaper

import android.graphics.Point
import android.graphics.Rect
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.wallpaper.WallpaperDisplayInfo.Companion.ORIENTATION_UNKNOWN
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.wallpaper.WallpaperDisplayInfo.Companion.orientation
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.wallpaper.WallpaperDisplayInfo.Companion.rotatedOrientation
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object WallpaperCropper {

    private const val ADD = 1
    private const val REMOVE = 2
    private const val BALANCE = 3
    private const val MAX_PARALLAX = 1f

    fun getCrop(
        displaySize: Point,
        info: WallpaperDisplayInfo,
        bitmapSize: Point,
        suggestedCrops: Map<Int, Rect>,
        rtl: Boolean
    ): Rect {
        val orientation = orientation(displaySize)
        if (suggestedCrops.isEmpty()) return defaultCrop(displaySize, info, bitmapSize, rtl)

        val invalid = suggestedCrops.values.any {
            it.left < 0 || it.top < 0 || it.right > bitmapSize.x || it.bottom > bitmapSize.y
        }
        if (invalid) return defaultCrop(displaySize, info, bitmapSize, rtl)

        suggestedCrops[orientation]?.let { return getAdjustedCrop(it, bitmapSize, displaySize, true, rtl, ADD) }

        val rotated = rotatedOrientation(orientation)
        suggestedCrops[rotated]?.let { crop ->
            val visible = noParallax(crop, info.displaySizes[rotated], bitmapSize, rtl)
            return getAdjustedCrop(visible, bitmapSize, displaySize, false, rtl, BALANCE)
        }

        val unfolded = info.unfoldedOrientation(orientation)
        suggestedCrops[unfolded]?.let { crop ->
            val visible = noParallax(crop, info.displaySizes[unfolded], bitmapSize, rtl)
            val result = getAdjustedCrop(visible, bitmapSize, displaySize, false, rtl, REMOVE)
            if (result.width() >= visible.width()) return result
            if (rtl) result.left = min(result.left, visible.left) else result.right = max(result.right, visible.right)
            return getAdjustedCrop(result, bitmapSize, displaySize, true, rtl, ADD)
        }

        val folded = info.foldedOrientation(orientation)
        val skipFolded = folded == WallpaperDisplayInfo.LANDSCAPE && suggestedCrops.containsKey(WallpaperDisplayInfo.PORTRAIT)
        suggestedCrops[folded]?.takeUnless { skipFolded }?.let { crop ->
            val visible = noParallax(crop, info.displaySizes[folded], bitmapSize, rtl)
            return getAdjustedCrop(visible, bitmapSize, displaySize, false, rtl, ADD)
        }

        info.displaySizes[rotated]?.let { rotatedSize ->
            listOf(info.foldedOrientation(rotated), info.unfoldedOrientation(rotated)).forEach { candidate ->
                if (suggestedCrops[candidate] != null) {
                    val rotatedCrop = getCrop(rotatedSize, info, bitmapSize, suggestedCrops, rtl)
                    return getCrop(displaySize, info, bitmapSize, mapOf(rotated to rotatedCrop), rtl)
                }
            }
        }

        return defaultCrop(displaySize, info, bitmapSize, rtl)
    }

    private fun defaultCrop(displaySize: Point, info: WallpaperDisplayInfo, bitmapSize: Point, rtl: Boolean): Rect {
        val orientation = orientation(displaySize)
        var crop = Rect(0, 0, bitmapSize.x, bitmapSize.y)
        val unfolded = info.unfoldedOrientation(orientation)
        if (unfolded != ORIENTATION_UNKNOWN) {
            return getCrop(displaySize, info, bitmapSize, mapOf(unfolded to crop), rtl)
        }
        if (info.isLargeScreen && !info.isFoldable && displaySize.x < displaySize.y) {
            val landscapeSize = Point(displaySize.y, displaySize.x)
            val landscape = noParallax(getCrop(landscapeSize, info, bitmapSize, emptyMap(), rtl), landscapeSize, bitmapSize, rtl)
            crop = getAdjustedCrop(landscape, bitmapSize, displaySize, false, rtl, ADD)
            if (rtl) crop.left = landscape.left else crop.right = landscape.right
        }
        return getAdjustedCrop(crop, bitmapSize, displaySize, true, rtl, ADD)
    }

    private fun noParallax(crop: Rect, displaySize: Point?, bitmapSize: Point, rtl: Boolean): Rect {
        displaySize ?: return crop
        val adjusted = getAdjustedCrop(crop, bitmapSize, displaySize, true, rtl, ADD)
        val ratio = displaySize.x * 1f / displaySize.y
        val excess = max(0f, adjusted.width() - adjusted.height() * ratio + 0.5f).toInt()
        if (rtl) adjusted.left += excess else adjusted.right -= excess
        return adjusted
    }

    private fun getAdjustedCrop(
        crop: Rect,
        bitmapSize: Point,
        screenSize: Point,
        parallax: Boolean,
        rtl: Boolean,
        mode: Int
    ): Rect {
        val adjusted = Rect(crop)
        val cropRatio = crop.width().toFloat() / crop.height()
        val screenRatio = screenSize.x.toFloat() / screenSize.y
        if (cropRatio == screenRatio) return crop

        if (cropRatio > screenRatio) {
            if (!parallax) {
                val newLeft = bitmapSize.y - crop.bottom
                val rotatedCrop = Rect(newLeft, crop.left, newLeft + crop.height(), crop.left + crop.width())
                val rotatedBitmap = Point(bitmapSize.y, bitmapSize.x)
                val rotatedScreen = Point(screenSize.y, screenSize.x)
                val rect = getAdjustedCrop(rotatedCrop, rotatedBitmap, rotatedScreen, false, rtl, mode)
                val resultTop = rotatedBitmap.x - rect.right
                return Rect(rect.top, resultTop, rect.top + rect.height(), resultTop + rect.width())
            }
            val parallaxRatio = cropRatio / screenRatio - 1f
            if (parallaxRatio > MAX_PARALLAX) {
                val remove = ceil((parallaxRatio - MAX_PARALLAX) * screenRatio * crop.height()).toInt()
                if (rtl) adjusted.left += remove else adjusted.right -= remove
            }
            return adjusted
        }

        var widthToAdd = when (mode) {
            REMOVE -> 0
            ADD -> (crop.height() * screenRatio - crop.width()).toInt()
            else -> (-crop.width() + sqrt((crop.width() * crop.height() * screenRatio).toDouble())).toInt()
        }
        val available = bitmapSize.x - crop.width()
        if (available >= widthToAdd) {
            var widthToAddLeft = widthToAdd / 2
            widthToAdd = widthToAdd / 2 + widthToAdd % 2
            if (crop.left < widthToAddLeft) {
                widthToAdd += widthToAddLeft - crop.left
                widthToAddLeft = crop.left
            } else if (bitmapSize.x - crop.right < widthToAdd) {
                widthToAddLeft += widthToAdd - (bitmapSize.x - crop.right)
                widthToAdd = bitmapSize.x - crop.right
            }
            adjusted.left -= widthToAddLeft
            adjusted.right += widthToAdd
        } else {
            adjusted.left = 0
            adjusted.right = bitmapSize.x
        }
        val heightToRemove = (crop.height() - adjusted.width() / screenRatio).roundToInt()
        adjusted.top += heightToRemove / 2 + heightToRemove % 2
        adjusted.bottom -= heightToRemove / 2
        return adjusted
    }

    fun relativeHints(info: String): Map<Int, Rect> {
        fun value(name: String): Int? =
            Regex("""\b$name="(-?[0-9]+)"""").find(info)?.groupValues?.get(1)?.toIntOrNull()

        val sampleSize = Regex("""\bsampleSize="([0-9.]+)"""").find(info)?.groupValues?.get(1)?.toFloatOrNull()
            ?.takeIf { it > 0f } ?: 1f
        val hints = HashMap<Int, Rect>()
        SUFFIXES.forEach { (orientation, suffix) ->
            val rect = Rect(
                value("cropLeft$suffix") ?: 0,
                value("cropTop$suffix") ?: 0,
                value("cropRight$suffix") ?: 0,
                value("cropBottom$suffix") ?: 0
            )
            if (!rect.isEmpty) hints[orientation] = rect
        }
        if (hints.isEmpty()) return emptyMap()

        val originX = value("totalCropLeft") ?: 0
        val originY = value("totalCropTop") ?: 0
        return hints.mapValues { (_, hint) ->
            Rect(
                ((hint.left - originX) / sampleSize + 0.5f).toInt(),
                ((hint.top - originY) / sampleSize + 0.5f).toInt(),
                floor((hint.right - originX) / sampleSize).toInt(),
                floor((hint.bottom - originY) / sampleSize).toInt()
            )
        }
    }

    private val SUFFIXES = listOf(
        WallpaperDisplayInfo.PORTRAIT to "Portrait",
        WallpaperDisplayInfo.LANDSCAPE to "Landscape",
        WallpaperDisplayInfo.SQUARE_PORTRAIT to "SquarePortrait",
        WallpaperDisplayInfo.SQUARE_LANDSCAPE to "SquareLandscape"
    )
}
