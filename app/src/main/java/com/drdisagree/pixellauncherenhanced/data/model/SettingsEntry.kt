package com.drdisagree.pixellauncherenhanced.data.model

data class SettingsEntry(
    val key: String,
    val title: String,
    val summary: String,
    val screenTitle: String,
    val categoryTitle: String?,
    val fragment: Class<*>
)
