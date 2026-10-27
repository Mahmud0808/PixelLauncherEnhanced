package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

class ExpressiveColorPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    var showAlpha: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private val hsv = floatArrayOf(0f, 1f, 1f)
    private var alpha = 255

    private val panelRect = RectF()
    private val thumbRect = RectF()
    private val panelClip = Path()
    private val hueRect = RectF()
    private val alphaRect = RectF()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val alphaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleGap = Paint(Paint.ANTI_ALIAS_FLAG)
    private var panelHue = -1f
    private var dragging = Target.NONE

    private val panelHeight = dpToPx(PANEL_HEIGHT).toFloat()
    private val trackHeight = dpToPx(TRACK_HEIGHT).toFloat()
    private val gap = dpToPx(GAP).toFloat()
    private val panelRadius = dpToPx(PANEL_RADIUS).toFloat()
    private val thumbRadius = dpToPx(THUMB_RADIUS).toFloat()
    private val handleWidth = dpToPx(HANDLE_WIDTH).toFloat()
    private val handleHeight = dpToPx(HANDLE_HEIGHT).toFloat()
    private val handleInset = dpToPx(HANDLE_GAP).toFloat()

    private enum class Target { NONE, PANEL, HUE, ALPHA }

    init {
        thumbRing.strokeWidth = dpToPx(THUMB_STROKE).toFloat()
        thumbRing.color = Color.WHITE
        handlePaint.color = MaterialColors.getColor(this, MaterialR.attr.colorOnSurface)
        handleGap.color = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerHigh)
        checkerPaint.shader = Checkerboard.shader(dpToPx(CHECKER_CELL))
    }

    @get:ColorInt
    var color: Int
        get() = ColorUtils.setAlphaComponent(Color.HSVToColor(hsv), alpha)
        set(value) {
            Color.colorToHSV(value, hsv)
            alpha = if (showAlpha) Color.alpha(value) else 255
            updateAlphaShader()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val tracks = if (showAlpha) 2 else 1
        val height = panelHeight + (gap + trackHeight) * tracks + handleOverflow() * 2
        setMeasuredDimension(width, height.toInt())
    }

    private fun handleOverflow() = ((handleHeight - trackHeight) / 2f).coerceAtLeast(0f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val top = handleOverflow()
        panelRect.set(0f, top, w.toFloat(), top + panelHeight)
        thumbRect.set(panelRect)
        panelClip.rewind()
        panelClip.addRoundRect(panelRect, panelRadius, panelRadius, Path.Direction.CW)
        hueRect.set(0f, panelRect.bottom + gap, w.toFloat(), panelRect.bottom + gap + trackHeight)
        alphaRect.set(0f, hueRect.bottom + gap, w.toFloat(), hueRect.bottom + gap + trackHeight)
        huePaint.shader = LinearGradient(
            hueRect.left, 0f, hueRect.right, 0f,
            IntArray(HUE_STOPS + 1) { Color.HSVToColor(floatArrayOf(it * 360f / HUE_STOPS, 1f, 1f)) },
            null, Shader.TileMode.CLAMP
        )
        panelHue = -1f
        updateAlphaShader()
    }

    private fun updatePanelShader() {
        if (panelHue == hsv[0] || panelRect.isEmpty) return
        panelHue = hsv[0]
        val saturation = LinearGradient(
            panelRect.left, 0f, panelRect.right, 0f,
            Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP
        )
        val value = LinearGradient(
            0f, panelRect.top, 0f, panelRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        panelPaint.shader = ComposeShader(saturation, value, PorterDuff.Mode.SRC_OVER)
    }

    private fun updateAlphaShader() {
        if (alphaRect.isEmpty) return
        val opaque = Color.HSVToColor(hsv)
        alphaPaint.shader = LinearGradient(
            alphaRect.left, 0f, alphaRect.right, 0f,
            ColorUtils.setAlphaComponent(opaque, 0), opaque, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        updatePanelShader()
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, panelPaint)

        val trackRadius = trackHeight / 2f
        canvas.drawRoundRect(hueRect, trackRadius, trackRadius, huePaint)
        if (showAlpha) {
            canvas.drawRoundRect(alphaRect, trackRadius, trackRadius, checkerPaint)
            canvas.drawRoundRect(alphaRect, trackRadius, trackRadius, alphaPaint)
        }

        val x = thumbRect.left + hsv[1] * thumbRect.width()
        val y = thumbRect.top + (1f - hsv[2]) * thumbRect.height()
        thumbFill.color = Color.HSVToColor(hsv)
        thumbRing.color = if (ColorUtils.calculateLuminance(thumbFill.color) > LIGHT_THRESHOLD) Color.BLACK else Color.WHITE
        canvas.withClip(panelClip) {
            drawCircle(x, y, thumbRadius, thumbFill)
            drawCircle(x, y, thumbRadius, thumbRing)
        }

        drawHandle(canvas, hueRect, hsv[0] / 360f)
        if (showAlpha) drawHandle(canvas, alphaRect, alpha / 255f)
    }

    private fun drawHandle(canvas: Canvas, track: RectF, fraction: Float) {
        val inset = handleWidth / 2f + handleInset
        val x = track.left + inset + fraction * (track.width() - inset * 2)
        val top = track.centerY() - handleHeight / 2f
        val radius = handleWidth / 2f
        canvas.drawRoundRect(x - radius - handleInset, top, x + radius + handleInset, top + handleHeight, radius + handleInset, radius + handleInset, handleGap)
        canvas.drawRoundRect(x - radius, top, x + radius, top + handleHeight, radius, radius, handlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    panelRect.contains(event.x, event.y) -> Target.PANEL
                    event.y in hueRect.top - handleOverflow()..hueRect.bottom + gap / 2f -> Target.HUE
                    showAlpha && event.y in alphaRect.top - gap / 2f..alphaRect.bottom + handleOverflow() -> Target.ALPHA
                    else -> Target.NONE
                }
                if (dragging == Target.NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                update(event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> if (dragging != Target.NONE) update(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = Target.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        when (dragging) {
            Target.PANEL -> {
                hsv[1] = ((x - thumbRect.left) / thumbRect.width()).coerceIn(0f, 1f)
                hsv[2] = 1f - ((y - thumbRect.top) / thumbRect.height()).coerceIn(0f, 1f)
                updateAlphaShader()
            }

            Target.HUE -> {
                hsv[0] = (((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 360f).coerceAtMost(359.9f)
                updateAlphaShader()
            }

            Target.ALPHA -> alpha = (((x - alphaRect.left) / alphaRect.width()).coerceIn(0f, 1f) * 255).toInt()
            Target.NONE -> return
        }
        invalidate()
        onColorChanged?.invoke(color)
    }

    companion object {
        private const val PANEL_HEIGHT = 150
        private const val TRACK_HEIGHT = 22
        private const val GAP = 14
        private const val PANEL_RADIUS = 10
        private const val THUMB_RADIUS = 10
        private const val THUMB_STROKE = 3
        private const val HANDLE_WIDTH = 4
        private const val HANDLE_HEIGHT = 32
        private const val HANDLE_GAP = 4
        private const val CHECKER_CELL = 6
        private const val HUE_STOPS = 6
        private const val LIGHT_THRESHOLD = 0.5
    }
}
