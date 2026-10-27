package com.drdisagree.pixellauncherenhanced.data.enums

import androidx.annotation.StringRes
import com.drdisagree.pixellauncherenhanced.R

enum class ShapeSymmetry(val value: Int, @param:StringRes val label: Int) {
    NONE(0, R.string.icon_shape_symmetry_off),
    MIRROR(1, R.string.icon_shape_symmetry_mirror),
    QUAD(2, R.string.icon_shape_symmetry_quad);

    companion object {
        fun fromValue(value: Int): ShapeSymmetry = entries.firstOrNull { it.value == value } ?: NONE
    }
}
