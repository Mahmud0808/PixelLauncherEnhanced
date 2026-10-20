package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.withSave
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.google.android.material.shape.MaterialShapes

class ShapeIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val scaleMatrix = Matrix()
    private var morph: Morph? = null
    private var progress = 0f
    private var spin = 0f
    private var animator: ValueAnimator? = null
    private var icon: Drawable? = null
    private var adaptiveBackground: Drawable? = null
    private var adaptiveForeground: Drawable? = null

    var shapeRotation = 0f
        set(value) {
            field = value
            invalidate()
        }

    fun setShapes(rest: RoundedPolygon, pressed: RoundedPolygon) {
        morph = Morph(MaterialShapes.normalize(rest, true), MaterialShapes.normalize(pressed, true))
        invalidate()
    }

    fun setColors(container: Int, content: Int) {
        paint.color = container
        icon?.setTint(content)
        invalidate()
    }

    fun setOutline(color: Int, width: Float) {
        outlinePaint.color = color
        outlinePaint.strokeWidth = width
        invalidate()
    }

    fun setCheckerboard(cell: Int) {
        checkerPaint.shader = if (cell > 0) Checkerboard.shader(cell) else null
        invalidate()
    }

    fun setIcon(drawable: Drawable?, tint: Int) {
        icon = drawable?.mutate()?.apply { setTint(tint) }
        invalidate()
    }

    fun setAdaptiveIcon(drawable: AdaptiveIconDrawable) {
        adaptiveBackground = drawable.background?.mutate()
        adaptiveForeground = drawable.foreground?.mutate()
        invalidate()
    }

    fun setPressedState(pressed: Boolean) {
        val target = if (pressed) 1f else 0f
        if (progress == target && animator?.isRunning != true) return

        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = if (pressed) 220 else 520
            interpolator = OvershootInterpolator(if (pressed) 1.2f else 2.4f)
            addUpdateListener {
                progress = it.animatedValue as Float
                spin = progress * SPIN_DEGREES
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val currentMorph = morph ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        val inset = outlinePaint.strokeWidth
        path.rewind()
        currentMorph.toPath(progress.coerceIn(0f, 1f), path)
        scaleMatrix.setScale(w - inset, h - inset)
        scaleMatrix.postTranslate(inset / 2f, inset / 2f)
        path.transform(scaleMatrix)

        if (adaptiveBackground != null || adaptiveForeground != null) {
            scaleMatrix.setRotate(spin + shapeRotation, w / 2f, h / 2f)
            path.transform(scaleMatrix)
            val layerSize = (minOf(w, h) * ADAPTIVE_LAYER_SCALE).toInt()
            val left = ((w - layerSize) / 2f).toInt()
            val top = ((h - layerSize) / 2f).toInt()
            val mask = canvas.saveLayer(0f, 0f, w, h, null)
            canvas.drawPath(path, paint)
            canvas.saveLayer(0f, 0f, w, h, maskPaint)
            adaptiveBackground?.setBounds(left, top, left + layerSize, top + layerSize)
            adaptiveBackground?.draw(canvas)
            canvas.restoreToCount(mask)
            val foregroundSize = (minOf(w, h) * ADAPTIVE_FOREGROUND_SCALE).toInt()
            val foregroundLeft = ((w - foregroundSize) / 2f).toInt()
            val foregroundTop = ((h - foregroundSize) / 2f).toInt()
            adaptiveForeground?.setBounds(foregroundLeft, foregroundTop, foregroundLeft + foregroundSize, foregroundTop + foregroundSize)
            adaptiveForeground?.draw(canvas)
            return
        }

        canvas.withSave {
            rotate(spin, w / 2f, h / 2f)
            if (checkerPaint.shader != null) drawPath(path, checkerPaint)
            drawPath(path, paint)
            if (inset > 0f) drawPath(path, outlinePaint)
        }

        icon?.let { drawable ->
            val size = (minOf(w, h) * ICON_FRACTION).toInt()
            val left = ((w - size) / 2f).toInt()
            val top = ((h - size) / 2f).toInt()
            drawable.setBounds(left, top, left + size, top + size)
            drawable.draw(canvas)
        }
    }

    companion object {
        private const val ICON_FRACTION = 0.46f
        private const val ADAPTIVE_LAYER_SCALE = 1.5f
        private const val ADAPTIVE_FOREGROUND_SCALE = 1.5f
        private const val SPIN_DEGREES = 30f
    }
}
