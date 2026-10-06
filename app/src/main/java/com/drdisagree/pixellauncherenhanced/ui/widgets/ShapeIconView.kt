package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
    private val path = Path()
    private val scaleMatrix = Matrix()
    private var morph: Morph? = null
    private var progress = 0f
    private var spin = 0f
    private var animator: ValueAnimator? = null
    private var icon: Drawable? = null

    fun setShapes(rest: RoundedPolygon, pressed: RoundedPolygon) {
        morph = Morph(MaterialShapes.normalize(rest, true), MaterialShapes.normalize(pressed, true))
        invalidate()
    }

    fun setColors(container: Int, content: Int) {
        paint.color = container
        icon?.setTint(content)
        invalidate()
    }

    fun setIcon(drawable: Drawable?, tint: Int) {
        icon = drawable?.mutate()?.apply { setTint(tint) }
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

        path.rewind()
        currentMorph.toPath(progress.coerceIn(0f, 1f), path)
        scaleMatrix.setScale(w, h)
        path.transform(scaleMatrix)

        canvas.withSave {
            rotate(spin, w / 2f, h / 2f)
            drawPath(path, paint)
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
        private const val SPIN_DEGREES = 30f
    }
}
