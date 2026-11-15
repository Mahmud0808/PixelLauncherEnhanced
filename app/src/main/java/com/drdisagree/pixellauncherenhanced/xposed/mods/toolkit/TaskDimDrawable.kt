package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

class TaskDimDrawable(private val taskView: ViewGroup, private val cornerRadius: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rect = RectF()

    var amount = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (amount <= 0f) return
        val content = contentView() ?: return
        rect.set(
            content.left.toFloat(),
            content.top.toFloat(),
            content.right.toFloat(),
            content.bottom.toFloat()
        )
        paint.alpha = (amount.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }

    private fun contentView(): View? {
        var largest: View? = null
        var largestArea = 0
        for (index in 0 until taskView.childCount) {
            val child = taskView.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            val area = child.width * child.height
            if (area > largestArea) {
                largest = child
                largestArea = area
            }
        }
        return largest
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
