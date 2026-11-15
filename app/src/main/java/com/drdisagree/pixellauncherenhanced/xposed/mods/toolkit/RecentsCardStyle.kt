package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

class RecentsCardStyle {

    var scale = 1f
        private set
    var translation = 0f
        private set
    var elevation = 0f
        private set
    var dim = 0f
        private set

    fun update(
        style: String,
        distance: Float,
        taskSize: Float,
        pageSpacing: Float,
        progress: Float,
        recentsRtl: Boolean
    ) {
        scale = 1f
        translation = 0f
        elevation = 0f
        dim = 0f
        if (taskSize <= 0f || progress <= 0f || style == STYLE_DEFAULT) return

        val amount = min(1f, progress)
        val dist = if (recentsRtl) distance else -distance
        val absDist = abs(dist)
        val minScale = if (style == STYLE_OXYGEN) 0.92f else 0.85f
        val scaleProgress = min(1f, absDist / max(1f, taskSize + pageSpacing))
        var targetScale = 1f - (1f - minScale) * scaleProgress

        when {
            style == STYLE_STAPLE -> {
                val excess = max(0f, absDist - taskSize * 0.60f)
                val squish = min(excess, log10(1f + excess) * 12f)
                translation = -sign(dist) * (excess - squish)
                elevation = -(excess / taskSize) * 20f
                targetScale *= max(0.75f, 1f - excess / (taskSize * 2.5f) * 0.15f)
            }

            style == STYLE_IOS && dist < 0f -> {
                val excess = max(0f, absDist - taskSize * 0.1f)
                val squish = min(excess, log10(1f + excess) * 45f)
                translation = excess - squish
                elevation = -(excess / taskSize) * 24f
                val depth = min(1f, excess / (taskSize * 1.5f))
                dim = 0.3f * depth
                targetScale *= 1f - depth * 0.15f
            }

            style == STYLE_OXYGEN && dist < 0f -> {
                val bend = taskSize * 0.02f
                val visualDistance = 2f * absDist / (sqrt(1f + absDist / bend) + 1f)
                translation = absDist - visualDistance
                elevation = -(absDist / taskSize) * 12f
                targetScale *= max(0.85f, 1f - absDist / (taskSize * 3f) * 0.1f)
            }
        }

        scale = 1f + (targetScale - 1f) * amount
        translation *= (if (recentsRtl) 1f else -1f) * amount
        elevation *= amount
        dim *= amount
    }

    companion object {
        const val STYLE_DEFAULT = "default"
        const val STYLE_SCALE = "stock"
        const val STYLE_STAPLE = "staple"
        const val STYLE_IOS = "ios"
        const val STYLE_OXYGEN = "oxygen"

        fun isStack(style: String): Boolean =
            style == STYLE_STAPLE || style == STYLE_IOS || style == STYLE_OXYGEN
    }
}
