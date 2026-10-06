package com.drdisagree.pixellauncherenhanced.ui.drawables

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.animation.PathInterpolator
import com.drdisagree.pixellauncherenhanced.data.enums.SegmentPosition
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

class SegmentedRowDrawable(context: Context) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)
    private val outerRadius = dpToPx(OUTER_RADIUS_DP).toFloat()
    private val innerRadius = dpToPx(INNER_RADIUS_DP).toFloat()
    private val pressedRadius = dpToPx(PRESSED_RADIUS_DP).toFloat()
    private val baseColor = MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainer, 0)

    private var position = SegmentPosition.SINGLE
    private var pressProgress = 0f
    private var pressed = false
    private var pressAnimator: ValueAnimator? = null

    fun setPosition(value: SegmentPosition) {
        if (position == value) return
        position = value
        invalidateSelf()
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val isPressed = state.contains(android.R.attr.state_pressed)
        if (isPressed == pressed) return false
        pressed = isPressed

        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressProgress, if (isPressed) 1f else 0f).apply {
            duration = if (isPressed) PRESS_IN_MS else PRESS_OUT_MS
            interpolator = EMPHASIZED
            addUpdateListener {
                pressProgress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
        return true
    }

    override fun draw(canvas: Canvas) {
        buildPath()
        paint.color = baseColor
        canvas.drawPath(path, paint)
    }

    override fun getOutline(outline: Outline) {
        buildPath()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(path)
        } else {
            @Suppress("DEPRECATION")
            outline.setConvexPath(path)
        }
    }

    private fun buildPath() {
        val inner = innerRadius + (pressedRadius - innerRadius) * pressProgress
        val top = if (position == SegmentPosition.SINGLE || position == SegmentPosition.TOP) outerRadius else inner
        val bottom = if (position == SegmentPosition.SINGLE || position == SegmentPosition.BOTTOM) outerRadius else inner

        radii[0] = top; radii[1] = top; radii[2] = top; radii[3] = top
        radii[4] = bottom; radii[5] = bottom; radii[6] = bottom; radii[7] = bottom

        rect.set(bounds)
        path.rewind()
        path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val OUTER_RADIUS_DP = 24
        private const val INNER_RADIUS_DP = 6
        private const val PRESSED_RADIUS_DP = 20
        private const val PRESS_IN_MS = 160L
        private const val PRESS_OUT_MS = 420L
        private val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
