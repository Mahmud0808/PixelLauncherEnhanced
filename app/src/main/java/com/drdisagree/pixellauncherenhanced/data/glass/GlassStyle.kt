package com.drdisagree.pixellauncherenhanced.data.glass

data class GlassStyle(
    val refraction: Float = 1f,
    val depth: Float = 1f,
    val dispersion: Float = 1f,
    val vibrancy: Float = 0f,
    val rim: Float = 1f,
    val shadow: Float = 1f,
    val outline: Boolean = true
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
        const val DEFAULT_OUTLINE = true

        val DEFAULT = GlassStyle()
    }
}
