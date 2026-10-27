package com.drdisagree.pixellauncherenhanced.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.graphics.shapes.RoundedPolygon
import com.drdisagree.pixellauncherenhanced.data.enums.TilePalette

data class ScreenStyle(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val palette: TilePalette,
    val restShape: RoundedPolygon,
    val pressedShape: RoundedPolygon
)
