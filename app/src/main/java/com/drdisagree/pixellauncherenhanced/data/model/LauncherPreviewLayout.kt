package com.drdisagree.pixellauncherenhanced.data.model

data class LauncherPreviewLayout(
    val rows: Int,
    val columns: Int,
    val dock: Boolean,
    val searchBar: Boolean,
    val glance: Boolean,
    val pageIndicator: Boolean
)
