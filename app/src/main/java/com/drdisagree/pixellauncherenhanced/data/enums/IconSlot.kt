package com.drdisagree.pixellauncherenhanced.data.enums

import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_OVERRIDES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_OVERRIDES_HOME
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_THEMED_OVERRIDES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_THEMED_OVERRIDES_HOME

enum class IconSlot(val prefKey: String, private val customPrefix: String, val isHome: Boolean, val isThemed: Boolean) {
    DRAWER(ICON_OVERRIDES, "", false, false),
    HOME(ICON_OVERRIDES_HOME, "home|", true, false),
    THEMED(ICON_THEMED_OVERRIDES, "themed|", false, true),
    THEMED_HOME(ICON_THEMED_OVERRIDES_HOME, "themedhome|", true, true);

    fun customKey(component: String) = customPrefix + component
}
