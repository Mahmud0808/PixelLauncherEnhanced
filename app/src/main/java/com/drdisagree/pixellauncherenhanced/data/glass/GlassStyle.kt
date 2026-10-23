package com.drdisagree.pixellauncherenhanced.data.glass

import android.graphics.Color

data class GlassStyle(
    val refraction: Float = 1f,
    val depth: Float = 1f,
    val dispersion: Float = 1f,
    val vibrancy: Float = 0f,
    val rim: Float = 1f,
    val shadow: Float = 1f,
    val outlineWidth: Float = 1f,
    val outlineOpacity: Float = 0.15f,
    val outlineColor: Int = Color.WHITE
) {
    val saturation: Float
        get() = (1f + vibrancy).coerceAtLeast(0f)

    companion object {
        const val DEFAULT_REFRACTION = 100
        const val DEFAULT_DEPTH = 100
        const val DEFAULT_DISPERSION = 100
        const val DEFAULT_VIBRANCY = 0
        const val DEFAULT_RIM = 100
        const val DEFAULT_SHADOW = 100
        const val DEFAULT_OUTLINE_WIDTH = 1
        const val DEFAULT_OUTLINE_OPACITY = 15
        const val DEFAULT_OUTLINE_COLOR = Color.WHITE

        val DEFAULT = GlassStyle()
    }
}
