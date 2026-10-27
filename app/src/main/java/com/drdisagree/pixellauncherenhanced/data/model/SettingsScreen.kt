package com.drdisagree.pixellauncherenhanced.data.model

import androidx.annotation.StringRes
import androidx.annotation.XmlRes

class SettingsScreen(
    @XmlRes val xml: Int,
    @StringRes val title: Int,
    val fragment: Class<*>
)
