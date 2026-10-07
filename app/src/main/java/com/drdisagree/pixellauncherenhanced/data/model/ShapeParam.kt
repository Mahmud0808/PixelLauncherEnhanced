package com.drdisagree.pixellauncherenhanced.data.model

import androidx.annotation.StringRes

class ShapeParam(
    val key: String,
    @param:StringRes val title: Int,
    val from: Int,
    val to: Int,
    val default: Int,
    @param:StringRes val minLabel: Int? = null,
    @param:StringRes val maxLabel: Int? = null,
    val format: (Int) -> String = { it.toString() }
)
