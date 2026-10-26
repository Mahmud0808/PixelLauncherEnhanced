package com.drdisagree.pixellauncherenhanced.data.enums

import android.view.View
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

enum class TilePalette(
    @AttrRes val card: Int,
    @AttrRes val shape: Int,
    @AttrRes val icon: Int,
    @AttrRes val text: Int,
    @AttrRes private val blendShape: Int = 0,
    @AttrRes private val blendIcon: Int = 0
) {
    PRIMARY(
        MaterialR.attr.colorPrimaryContainer,
        androidx.appcompat.R.attr.colorPrimary,
        MaterialR.attr.colorOnPrimary,
        MaterialR.attr.colorOnPrimaryContainer
    ),
    SECONDARY(
        MaterialR.attr.colorSecondaryContainer,
        MaterialR.attr.colorSecondary,
        MaterialR.attr.colorOnSecondary,
        MaterialR.attr.colorOnSecondaryContainer
    ),
    TERTIARY(
        MaterialR.attr.colorTertiaryContainer,
        MaterialR.attr.colorTertiary,
        MaterialR.attr.colorOnTertiary,
        MaterialR.attr.colorOnTertiaryContainer
    ),
    NEUTRAL(
        MaterialR.attr.colorSurfaceContainerHigh,
        androidx.appcompat.R.attr.colorPrimary,
        MaterialR.attr.colorOnPrimary,
        MaterialR.attr.colorOnSurface,
        MaterialR.attr.colorTertiary,
        MaterialR.attr.colorOnTertiary
    );

    fun shapeColor(view: View): Int = view.resolve(shape, blendShape)

    fun iconColor(view: View): Int = view.resolve(icon, blendIcon)

    private fun View.resolve(@AttrRes base: Int, @AttrRes blend: Int): Int {
        val color = MaterialColors.getColor(this, base)
        if (blend == 0) return color
        return ColorUtils.blendARGB(color, MaterialColors.getColor(this, blend), 0.5f)
    }
}
