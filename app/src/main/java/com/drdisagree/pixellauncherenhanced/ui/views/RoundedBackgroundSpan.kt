package com.drdisagree.pixellauncherenhanced.ui.views

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

class RoundedBackgroundSpan(
    private val backgroundColor: Int,
    private val textColor: Int,
    private val paddingH: Int,
    private val paddingV: Int,
    private val radius: Int,
    private val textScale: Float = 0.75f,
    private val yOffsetPx: Int = 0
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val scaledPaint = Paint(paint)
        scaledPaint.textSize = paint.textSize * textScale

        val width = scaledPaint.measureText(text, start, end)

        fm?.let {
            val original = paint.fontMetricsInt
            val scaled = scaledPaint.fontMetricsInt

            val height = scaled.descent - scaled.ascent + paddingV * 2
            val center = (original.ascent + original.descent) / 2

            it.ascent = center - height / 2
            it.descent = center + height / 2
            it.top = it.ascent
            it.bottom = it.descent
        }

        return (width + paddingH * 2).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val scaledPaint = Paint(paint)
        scaledPaint.textSize = paint.textSize * textScale

        val width = scaledPaint.measureText(text, start, end)
        val fm = scaledPaint.fontMetrics

        val centerY = y + (fm.ascent + fm.descent) / 2 - yOffsetPx

        val rect = RectF(
            x,
            centerY - (fm.descent - fm.ascent) / 2 - paddingV,
            x + width + paddingH * 2,
            centerY + (fm.descent - fm.ascent) / 2 + paddingV
        )

        scaledPaint.color = backgroundColor
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), scaledPaint)

        scaledPaint.color = textColor
        canvas.drawText(text, start, end, x + paddingH, y.toFloat() - yOffsetPx, scaledPaint)
    }
}