package com.drdisagree.pixellauncherenhanced.data.enums

import androidx.annotation.AttrRes
import com.google.android.material.R as MaterialR

enum class TilePalette(
    @AttrRes val card: Int,
    @AttrRes val shape: Int,
    @AttrRes val icon: Int,
    @AttrRes val text: Int
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
        MaterialR.attr.colorPrimaryContainer,
        MaterialR.attr.colorOnPrimaryContainer,
        MaterialR.attr.colorOnSurface
    )
}
