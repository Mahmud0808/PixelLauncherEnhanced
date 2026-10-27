package com.drdisagree.pixellauncherenhanced.data.enums

import androidx.annotation.StringRes
import com.drdisagree.pixellauncherenhanced.R

enum class ShapeSketchError(@param:StringRes val message: Int) {
    TOO_SHORT(R.string.icon_shape_error_too_short),
    NOT_CLOSED(R.string.icon_shape_error_not_closed),
    TOO_SMALL(R.string.icon_shape_error_too_small)
}
