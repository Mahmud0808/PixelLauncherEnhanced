package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.view.Display
import android.view.WindowManager
import android.view.WindowMetrics
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callMethodSilently

class WallpaperDisplayInfo(
    val displaySizes: Map<Int, Point>,
    val isFoldable: Boolean,
    val isLargeScreen: Boolean,
    private val foldableOrientations: List<Pair<Int, Int>>
) {

    fun foldedOrientation(orientation: Int): Int =
        foldableOrientations.firstOrNull { it.second == orientation }?.first ?: ORIENTATION_UNKNOWN

    fun unfoldedOrientation(orientation: Int): Int =
        foldableOrientations.firstOrNull { it.first == orientation }?.second ?: ORIENTATION_UNKNOWN

    companion object {

        const val ORIENTATION_UNKNOWN = -1
        const val PORTRAIT = 0
        const val LANDSCAPE = 1
        const val SQUARE_PORTRAIT = 2
        const val SQUARE_LANDSCAPE = 3

        private const val LARGE_SCREEN_DP = 600f

        fun orientation(size: Point): Int {
            val ratio = size.x.toFloat() / size.y
            return when {
                ratio >= 4f / 3f -> LANDSCAPE
                ratio > 1f -> SQUARE_LANDSCAPE
                ratio > 0.75f -> SQUARE_PORTRAIT
                else -> PORTRAIT
            }
        }

        fun rotatedOrientation(orientation: Int): Int = when (orientation) {
            PORTRAIT -> LANDSCAPE
            LANDSCAPE -> PORTRAIT
            SQUARE_PORTRAIT -> SQUARE_LANDSCAPE
            SQUARE_LANDSCAPE -> SQUARE_PORTRAIT
            else -> ORIENTATION_UNKNOWN
        }

        fun from(context: Context): WallpaperDisplayInfo {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val metrics = (windowManager.callMethodSilently("getPossibleMaximumWindowMetrics", Display.DEFAULT_DISPLAY) as? Set<*>)
                ?.filterIsInstance<WindowMetrics>()
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: setOf(windowManager.maximumWindowMetrics)
            val foldable = foldedDeviceStates() > 0
            return WallpaperDisplayInfo(
                displaySizes(metrics),
                foldable,
                isLargeScreen(metrics),
                if (foldable) foldableOrientations(metrics) else emptyList()
            )
        }

        private fun displaySizes(metrics: Set<WindowMetrics>): Map<Int, Point> {
            val sizes = HashMap<Int, Point>()
            metrics.forEach { metric ->
                val size = Point(metric.bounds.width(), metric.bounds.height())
                listOf(size, Point(size.y, size.x)).forEach { candidate ->
                    val orientation = orientation(candidate)
                    val current = sizes[orientation]
                    if (current == null || current.x * current.y < candidate.x * candidate.y) {
                        sizes[orientation] = candidate
                    }
                }
            }
            return sizes
        }

        private fun foldableOrientations(metrics: Set<WindowMetrics>): List<Pair<Int, Int>> {
            if (metrics.size != 2) return emptyList()
            val result = ArrayList<Pair<Int, Int>>()
            var previousOrientation = ORIENTATION_UNKNOWN
            var previousArea = 0f
            metrics.forEach { metric ->
                val size = Point(metric.bounds.width(), metric.bounds.height())
                val orientation = orientation(size)
                val area = size.x * size.y / (metric.density * metric.density)
                if (previousArea <= 0f) {
                    previousOrientation = orientation
                    previousArea = area
                    return@forEach
                }
                val pair = if (area > previousArea) previousOrientation to orientation else orientation to previousOrientation
                result.add(pair)
                result.add(rotatedOrientation(pair.first) to rotatedOrientation(pair.second))
            }
            return result
        }

        private fun isLargeScreen(metrics: Set<WindowMetrics>): Boolean =
            metrics.minOf { it.bounds.width() / it.density } >= LARGE_SCREEN_DP

        @SuppressLint("DiscouragedApi")
        private fun foldedDeviceStates(): Int {
            val system = Resources.getSystem()
            val id = system.getIdentifier("config_foldedDeviceStates", "array", "android")
            return if (id == 0) 0 else runCatching { system.getIntArray(id).size }.getOrDefault(0)
        }
    }
}
