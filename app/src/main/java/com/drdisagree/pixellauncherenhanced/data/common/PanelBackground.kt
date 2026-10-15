package com.drdisagree.pixellauncherenhanced.data.common

import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND

object PanelBackground {

    const val OFF = "off"
    const val FROSTED = "frosted"
    const val LIQUID = "liquid"

    val KEYS = setOf(DOCK_BACKGROUND, WIDGET_BACKGROUND, FOLDER_BACKGROUND)

    val isBlurSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val isLiquidSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun supported(value: String?): String {
        return when {
            value == null || !isBlurSupported -> OFF
            value == LIQUID && !isLiquidSupported -> FROSTED
            value == FROSTED || value == LIQUID -> value
            else -> OFF
        }
    }
}
