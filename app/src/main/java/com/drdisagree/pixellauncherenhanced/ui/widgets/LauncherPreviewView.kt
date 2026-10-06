package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_DOCK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_AT_A_GLANCE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.LauncherPreviewLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

class LauncherPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wallpaperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var layout = readLayout()
    private var reveal = 0f
    private var revealAnimator: ValueAnimator? = null

    private val iconColors by lazy {
        listOf(
            androidx.appcompat.R.attr.colorPrimary,
            MaterialR.attr.colorTertiary,
            MaterialR.attr.colorSecondary,
            MaterialR.attr.colorPrimaryFixedDim
        ).map { MaterialColors.getColor(this, it) }
    }
    private val onWallpaper by lazy { MaterialColors.getColor(this, MaterialR.attr.colorOnSurface) }

    private var revealed = false

    fun refresh() {
        val updated = readLayout()
        if (updated == layout && revealed) return
        layout = updated
        playReveal()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (revealed) return
        layout = readLayout()
        playReveal()
    }

    override fun onDetachedFromWindow() {
        if (revealAnimator?.isRunning == true) {
            revealAnimator?.end()
        }
        super.onDetachedFromWindow()
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

    private fun playReveal() {
        revealed = true
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = EMPHASIZED
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun readLayout(): LauncherPreviewLayout {
        val rows = RPrefs.getSliderInt(DESKTOP_GRID_ROWS, 0).takeIf { it > 0 } ?: DEFAULT_ROWS
        val columns = RPrefs.getSliderInt(DESKTOP_GRID_COLUMNS, 0).takeIf { it > 0 } ?: DEFAULT_COLUMNS
        val dock = !RPrefs.getBoolean(DISABLE_DOCK, false)

        return LauncherPreviewLayout(
            rows = rows,
            columns = columns,
            dock = dock,
            searchBar = dock && !RPrefs.getBoolean(DESKTOP_SEARCH_BAR, false),
            glance = !RPrefs.getBoolean(HIDE_AT_A_GLANCE, false),
            pageIndicator = !RPrefs.getBoolean(LAUNCHER_HIDE_PAGE_INDICATOR, false)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val corner = w * 0.16f
        val stroke = w * 0.025f
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        canvas.drawRoundRect(rect, corner, corner, wallpaperPaint)

        framePaint.strokeWidth = stroke
        framePaint.color = ColorUtils.setAlphaComponent(onWallpaper, 40)
        canvas.drawRoundRect(rect, corner, corner, framePaint)

        val padding = w * 0.09f
        val contentLeft = padding
        val contentRight = w - padding
        val contentWidth = contentRight - contentLeft
        var top = h * 0.07f

        fillPaint.color = ColorUtils.setAlphaComponent(onWallpaper, 90)
        val barHeight = h * 0.012f
        rect.set(contentLeft, top, contentLeft + contentWidth * 0.18f, top + barHeight)
        canvas.drawRoundRect(rect, barHeight, barHeight, fillPaint)
        rect.set(contentRight - contentWidth * 0.22f, top, contentRight, top + barHeight)
        canvas.drawRoundRect(rect, barHeight, barHeight, fillPaint)
        top += h * 0.05f

        if (layout.glance) {
            fillPaint.color = ColorUtils.setAlphaComponent(onWallpaper, (150 * reveal).toInt())
            val line = h * 0.022f
            rect.set(contentLeft, top, contentLeft + contentWidth * 0.62f * reveal, top + line)
            canvas.drawRoundRect(rect, line, line, fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(onWallpaper, (90 * reveal).toInt())
            rect.set(contentLeft, top + line * 1.7f, contentLeft + contentWidth * 0.4f * reveal, top + line * 2.5f)
            canvas.drawRoundRect(rect, line, line, fillPaint)
            top += h * 0.1f
        }

        val bottomReserved = h * (0.05f + (if (layout.searchBar) 0.07f else 0f) +
                (if (layout.dock) 0.1f else 0f) + (if (layout.pageIndicator) 0.04f else 0f))
        val gridBottom = h - bottomReserved
        val cellWidth = contentWidth / layout.columns
        val cellHeight = (gridBottom - top) / layout.rows
        val iconSize = minOf(cellWidth, cellHeight) * 0.62f
        val total = layout.rows * layout.columns

        for (row in 0 until layout.rows) {
            for (column in 0 until layout.columns) {
                val index = row * layout.columns + column
                if (!isOccupied(row, column)) continue
                drawIcon(
                    canvas,
                    cx = contentLeft + cellWidth * (column + 0.5f),
                    cy = top + cellHeight * (row + 0.5f),
                    size = iconSize,
                    colorIndex = index,
                    delay = index.toFloat() / total
                )
            }
        }

        var cursor = gridBottom
        if (layout.pageIndicator) {
            val dot = h * 0.011f
            val gap = dot * 2.6f
            for (i in 0 until 3) {
                fillPaint.color = ColorUtils.setAlphaComponent(onWallpaper, if (i == 0) 200 else 80)
                canvas.drawCircle(w / 2f + (i - 1) * gap, cursor + h * 0.02f, dot / 2f + (if (i == 0) dot * 0.2f else 0f), fillPaint)
            }
            cursor += h * 0.04f
        }

        if (layout.dock) {
            val dockCell = contentWidth / layout.columns
            val dockSize = minOf(dockCell, h * 0.1f) * 0.62f
            for (column in 0 until layout.columns) {
                drawIcon(
                    canvas,
                    cx = contentLeft + dockCell * (column + 0.5f),
                    cy = cursor + h * 0.05f,
                    size = dockSize,
                    colorIndex = column + 1,
                    delay = 0.6f + column * 0.05f
                )
            }
            cursor += h * 0.1f
        }

        if (layout.searchBar) {
            val searchHeight = h * 0.045f
            fillPaint.color = ColorUtils.setAlphaComponent(onWallpaper, (60 * reveal).toInt())
            val inset = contentWidth * 0.5f * (1f - reveal)
            rect.set(contentLeft + inset, cursor + h * 0.012f, contentRight - inset, cursor + h * 0.012f + searchHeight)
            canvas.drawRoundRect(rect, searchHeight, searchHeight, fillPaint)
        }
    }

    private fun isOccupied(row: Int, column: Int): Boolean {
        if (row >= layout.rows - 1) return true
        return (row * 7 + column * 3) % 5 < 2
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, colorIndex: Int, delay: Float) {
        val local = ((reveal - delay * 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (local <= 0f) return

        val scale = EMPHASIZED.getInterpolation(local)
        fillPaint.color = iconColors[colorIndex % iconColors.size]
        canvas.drawCircle(cx, cy, size / 2f * scale, fillPaint)
    }

    companion object {
        private const val DEFAULT_ROWS = 5
        private const val DEFAULT_COLUMNS = 4
        private val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
