package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import com.drdisagree.pixellauncherenhanced.utils.IconShapePaths
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

class IconShapePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Sample(val icon: Drawable, val label: CharSequence)

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wallpaperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val shapePath = Path()
    private val scaledPath = Path()
    private val matrix = Matrix()
    private val bounds = RectF()
    private var samples: List<Sample> = emptyList()
    private var pop = 1f
    private var animator: ValueAnimator? = null

    init {
        shapePath.set(systemMask())
    }

    fun setSamples(list: List<Sample>) {
        samples = list.map { Sample(it.icon.mutate(), it.label) }
        invalidate()
    }

    fun setShape(pathData: String?, animate: Boolean = true) {
        val parsed = pathData?.takeIf { it.isNotBlank() }?.let { IconShapePaths.toPath(it) }
        shapePath.set(parsed ?: systemMask())

        animator?.cancel()
        if (!animate || !isLaidOut) {
            pop = 1f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(2.2f)
            addUpdateListener {
                pop = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        wallpaperPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            MaterialColors.getColor(this, MaterialR.attr.colorPrimaryContainer),
            MaterialColors.getColor(this, MaterialR.attr.colorTertiaryContainer),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 28f * density
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, corner, corner, wallpaperPaint)

        val count = if (samples.isEmpty()) PLACEHOLDER_COUNT else samples.size
        val slot = width.toFloat() / count
        val labelHeight = labelPaint.fontSpacing
        val gap = 8f * density
        val size = minOf(slot * 0.62f, height - labelHeight - gap - 32f * density, 60f * density)
        val blockHeight = size + gap + labelHeight
        val top = (height - blockHeight) / 2
        val scale = 0.82f + 0.18f * pop

        val onWallpaper = MaterialColors.getColor(this, MaterialR.attr.colorOnPrimaryContainer)
        labelPaint.color = ColorUtils.setAlphaComponent(onWallpaper, 220)
        shadowPaint.color = ColorUtils.setAlphaComponent(Color.BLACK, 28)

        val drawn = size * scale
        matrix.setScale(drawn / IconShapePaths.SIZE, drawn / IconShapePaths.SIZE)
        shapePath.transform(matrix, scaledPath)

        for (index in 0 until count) {
            val centerX = slot * index + slot / 2
            val left = centerX - drawn / 2
            val iconTop = top + (size - drawn) / 2
            val sample = samples.getOrNull(index)

            canvas.withSave {
                translate(left, iconTop + 2.5f * density)
                drawPath(scaledPath, shadowPaint)
            }
            canvas.withSave {
                translate(left, iconTop)
                clipPath(scaledPath)
                drawIcon(this, sample?.icon, drawn)
            }

            sample?.label?.let { label ->
                val text = TextUtils.ellipsize(label, labelPaint, slot - 6f * density, TextUtils.TruncateAt.END)
                canvas.drawText(text, 0, text.length, centerX, top + size + gap - labelPaint.ascent(), labelPaint)
            }
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable?, size: Float) {
        if (icon == null) {
            fillPaint.color = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerHighest)
            canvas.drawRect(0f, 0f, size, size, fillPaint)
            return
        }

        if (icon is AdaptiveIconDrawable) {
            val inset = (size * AdaptiveIconDrawable.getExtraInsetFraction()).toInt()
            val outer = size.toInt()
            listOfNotNull(icon.background, icon.foreground).forEach { layer ->
                layer.setBounds(-inset, -inset, outer + inset, outer + inset)
                layer.draw(canvas)
            }
            return
        }

        fillPaint.color = Color.WHITE
        canvas.drawRect(0f, 0f, size, size, fillPaint)
        val pad = (size * LEGACY_PADDING).toInt()
        icon.setBounds(pad, pad, size.toInt() - pad, size.toInt() - pad)
        icon.draw(canvas)
    }

    private fun systemMask(): Path {
        return AdaptiveIconDrawable(ColorDrawable(Color.BLACK), ColorDrawable(Color.BLACK)).run {
            setBounds(0, 0, IconShapePaths.SIZE.toInt(), IconShapePaths.SIZE.toInt())
            Path(iconMask)
        }
    }

    companion object {
        private const val PLACEHOLDER_COUNT = 4
        private const val LEGACY_PADDING = 0.18f
    }
}
