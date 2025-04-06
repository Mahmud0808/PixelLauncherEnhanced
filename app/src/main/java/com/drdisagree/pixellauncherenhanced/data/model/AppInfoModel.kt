package com.drdisagree.pixellauncherenhanced.data.model

import android.graphics.drawable.Drawable

data class AppInfoModel(
    val appName: String,
    val packageName: String,
    val appIcon: Drawable,
    var isSelected: Boolean
)