package com.drdisagree.pixellauncherenhanced.data.iconpack

import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper

class ThemedPackIconDrawable(val regular: Drawable, val themed: Drawable) : DrawableWrapper(regular)
