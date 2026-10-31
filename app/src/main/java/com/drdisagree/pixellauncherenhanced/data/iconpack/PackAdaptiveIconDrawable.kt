package com.drdisagree.pixellauncherenhanced.data.iconpack

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi

class PackAdaptiveIconDrawable : AdaptiveIconDrawable {

    private val layerRect = RectF()

    constructor(background: Drawable?, foreground: Drawable?) : super(background, foreground)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    constructor(background: Drawable?, foreground: Drawable?, monochrome: Drawable?) :
            super(background, foreground, monochrome)

    override fun draw(canvas: Canvas) {
        val layer = foreground ?: return
        val area = bounds
        if (area.isEmpty) return

        val save = if (alpha < 255) {
            layerRect.set(area)
            canvas.saveLayerAlpha(layerRect, alpha)
        } else {
            canvas.save()
        }
        canvas.translate(area.left.toFloat(), area.top.toFloat())
        layer.draw(canvas)
        canvas.restoreToCount(save)
    }
}
