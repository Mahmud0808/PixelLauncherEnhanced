package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.utils.IconShapePaths
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

class ShapeTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val shapePath = Path()
    private val scaledPath = Path()
    private val matrix = Matrix()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val dash = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
    private var hasShape = true
    private var selection = 0f
    private var animator: ValueAnimator? = null

    fun setShape(pathData: String?) {
        shapePath.reset()
        val parsed = when {
            pathData == null -> systemMask()
            pathData.isBlank() -> null
            else -> IconShapePaths.toPath(pathData)
        }
        hasShape = parsed != null
        parsed?.let { shapePath.set(it) }
        invalidate()
    }

    fun setChecked(checked: Boolean, animate: Boolean) {
        val target = if (checked) 1f else 0f
        animator?.cancel()
        if (!animate) {
            selection = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(selection, target).apply {
            duration = 360
            interpolator = OvershootInterpolator(2f)
            addUpdateListener {
                selection = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val shapeSize = size * MAX_FILL * (0.86f + 0.14f * selection.coerceIn(0f, 1.08f))
        val offsetX = (width - shapeSize) / 2
        val offsetY = (height - shapeSize) / 2

        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val idle = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerHighest)
        val onIdle = MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant)

        if (hasShape) {
            matrix.setScale(shapeSize / IconShapePaths.SIZE, shapeSize / IconShapePaths.SIZE)
            matrix.postTranslate(offsetX, offsetY)
            shapePath.transform(matrix, scaledPath)
            fillPaint.color = ColorUtils.blendARGB(idle, primary, selection.coerceIn(0f, 1f))
            canvas.drawPath(scaledPath, fillPaint)
        } else {
            glyphPaint.color = ColorUtils.blendARGB(onIdle, primary, selection.coerceIn(0f, 1f))
            val cx = width / 2f
            val cy = height / 2f
            val plus = shapeSize * 0.14f
            glyphPaint.pathEffect = dash
            canvas.drawCircle(cx, cy, shapeSize * 0.44f, glyphPaint)
            glyphPaint.pathEffect = null
            canvas.drawLine(cx - plus, cy, cx + plus, cy, glyphPaint)
            canvas.drawLine(cx, cy - plus, cx, cy + plus, glyphPaint)
        }
    }

    private fun systemMask(): Path {
        return AdaptiveIconDrawable(ColorDrawable(Color.BLACK), ColorDrawable(Color.BLACK)).run {
            setBounds(0, 0, IconShapePaths.SIZE.toInt(), IconShapePaths.SIZE.toInt())
            Path(iconMask)
        }
    }

    companion object {
        private const val MAX_FILL = 0.9f
    }
}
