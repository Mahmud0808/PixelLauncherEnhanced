package com.drdisagree.pixellauncherenhanced.data.model

import androidx.annotation.StringRes

class DashboardTile(
    val key: String,
    val style: ScreenStyle,
    @StringRes val summary: Int,
    val fragment: Class<*>
)
