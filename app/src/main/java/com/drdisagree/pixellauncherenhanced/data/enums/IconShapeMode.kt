package com.drdisagree.pixellauncherenhanced.data.enums

import androidx.annotation.StringRes
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.ShapeParam
import java.util.Locale

enum class IconShapeMode(
    val value: Int,
    @param:StringRes val label: Int,
    @param:StringRes val summary: Int,
    val params: List<ShapeParam> = emptyList()
) {
    DEFAULT(0, R.string.icon_shape_mode_default, R.string.icon_shape_summary_default),
    ROUNDED_SQUARE(
        1, R.string.icon_shape_mode_rounded, R.string.icon_shape_summary_rounded,
        listOf(
            ShapeParam(
                "rounded.radius", R.string.icon_shape_corner_radius, 0, 50, 18,
                R.string.icon_shape_square, R.string.icon_shape_circle, ::percentOfHalf
            )
        )
    ),
    SQUIRCLE(
        2, R.string.icon_shape_mode_squircle, R.string.icon_shape_summary_squircle,
        listOf(
            ShapeParam(
                "squircle.curve", R.string.icon_shape_curve, 20, 100, 40,
                R.string.icon_shape_round, R.string.icon_shape_square
            ) { String.format(Locale.US, "%.1f", it / 10f) }
        )
    ),
    CORNERS(
        4, R.string.icon_shape_mode_corners, R.string.icon_shape_summary_corners,
        listOf(
            ShapeParam("corners.tl", R.string.icon_shape_top_left, 0, 50, 50, format = ::percentOfHalf),
            ShapeParam("corners.tr", R.string.icon_shape_top_right, 0, 50, 50, format = ::percentOfHalf),
            ShapeParam("corners.br", R.string.icon_shape_bottom_right, 0, 50, 12, format = ::percentOfHalf),
            ShapeParam("corners.bl", R.string.icon_shape_bottom_left, 0, 50, 50, format = ::percentOfHalf)
        )
    ),
    SMOOTH(
        5, R.string.icon_shape_mode_smooth, R.string.icon_shape_summary_smooth,
        listOf(
            ShapeParam(
                "smooth.size", R.string.icon_shape_corner_size, 0, 50, 24,
                R.string.icon_shape_sharp, R.string.icon_shape_round, ::percentOfHalf
            ),
            ShapeParam(
                "smooth.smoothing", R.string.icon_shape_smoothing, 0, 100, 60,
                R.string.icon_shape_none, R.string.icon_shape_full, ::percent
            )
        )
    ),
    POLYGON(
        6, R.string.icon_shape_mode_polygon, R.string.icon_shape_summary_polygon,
        listOf(
            ShapeParam("polygon.sides", R.string.icon_shape_sides, 5, 12, 6),
            ShapeParam(
                "polygon.roundness", R.string.icon_shape_corner_radius, 0, 100, 45,
                R.string.icon_shape_sharp, R.string.icon_shape_round, ::percent
            ),
            ShapeParam("polygon.rotation", R.string.icon_shape_rotation, 0, 100, 0, format = ::percent)
        )
    ),
    STAR(
        7, R.string.icon_shape_mode_star, R.string.icon_shape_summary_star,
        listOf(
            ShapeParam("star.points", R.string.icon_shape_points, 5, 16, 9),
            ShapeParam(
                "star.depth", R.string.icon_shape_depth, 0, 100, 55,
                R.string.icon_shape_shallow, R.string.icon_shape_deep, ::percent
            ),
            ShapeParam(
                "star.roundness", R.string.icon_shape_tip_roundness, 0, 100, 60,
                R.string.icon_shape_sharp, R.string.icon_shape_round, ::percent
            )
        )
    ),
    WAVY(
        8, R.string.icon_shape_mode_wavy, R.string.icon_shape_summary_wavy,
        listOf(
            ShapeParam("wavy.waves", R.string.icon_shape_waves, 4, 24, 10),
            ShapeParam(
                "wavy.height", R.string.icon_shape_wave_height, 0, 100, 50,
                R.string.icon_shape_subtle, R.string.icon_shape_strong, ::percent
            )
        )
    ),
    CUSTOM(3, R.string.icon_shape_mode_custom, R.string.icon_shape_summary_custom);

    companion object {
        val ordered = listOf(DEFAULT, ROUNDED_SQUARE, SQUIRCLE, CORNERS, SMOOTH, POLYGON, STAR, WAVY, CUSTOM)

        fun fromValue(value: Int): IconShapeMode = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

private fun percent(value: Int): String = "$value%"

private fun percentOfHalf(value: Int): String = "${value * 2}%"
