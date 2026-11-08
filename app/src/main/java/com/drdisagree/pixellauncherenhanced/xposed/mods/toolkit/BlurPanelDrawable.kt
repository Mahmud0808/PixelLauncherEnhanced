package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper

class BlurPanelDrawable(
    original: Drawable?,
    private val onShown: () -> Unit = {},
    private val onDrawPanel: (Canvas) -> Unit
) : DrawableWrapper(original ?: ColorDrawable(Color.TRANSPARENT)) {
    override fun draw(canvas: Canvas) {
        onDrawPanel(canvas)
        super.draw(canvas)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) onShown()
        return changed
    }
}
