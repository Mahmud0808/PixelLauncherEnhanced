package com.drdisagree.pixellauncherenhanced.data.enums

import androidx.annotation.StringRes
import com.drdisagree.pixellauncherenhanced.R

enum class ShapeTool(val key: String, @param:StringRes val label: Int) {
    SELECT("select", R.string.icon_shape_tool_select),
    OUTLINE("outline", R.string.icon_shape_tool_draw),
    CIRCLE("circle", R.string.icon_shape_tool_circle),
    SQUARE("square", R.string.icon_shape_tool_square),
    RING("ring", R.string.icon_shape_tool_ring);

    companion object {
        fun fromKey(key: String?): ShapeTool = entries.firstOrNull { it.key == key } ?: OUTLINE
    }
}
