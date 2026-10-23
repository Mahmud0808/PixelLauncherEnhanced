package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.drdisagree.pixellauncherenhanced.data.glass.GlassShader
import com.drdisagree.pixellauncherenhanced.data.glass.GlassStyle
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class BlurPanel(private val host: View) {

    private val blurBounds = Rect()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPath = Path()
    private val radii = FloatArray(8)
    private var wallpaperBlur: Drawable? = null

    init {
        panels.add(this)
    }

    private var nudge = false
    private var hidden = false
    private var hiddenSince = 0L
    private var recheckPending = false
    private var fade = 1f
    private var fadeAnimator: ValueAnimator? = null

    fun markHidden() {
        if (!hidden) hiddenSince = SystemClock.uptimeMillis()
        hidden = true
    }

    fun waitForContent() {
        markHidden()
        recheckWhenLayerGone()
    }

    fun refresh() {
        nudge = true
        host.background?.invalidateSelf()
        host.invalidate()
    }

    private fun recheckWhenLayerGone(attempt: Int = 0) {
        if (recheckPending) return
        recheckPending = true
        host.postDelayed({
            recheckPending = false
            if (!host.isAttachedToWindow) return@postDelayed
            if (host.isSettling() || host.effectiveAlpha() <= 0f) {
                if (attempt < MAX_RECHECKS) recheckWhenLayerGone(attempt + 1)
            } else {
                refresh()
            }
        }, LAYER_RECHECK_DELAY)
    }

    private fun fadeIn() {
        fadeAnimator?.cancel()
        fade = 0f
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_IN_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fade = it.animatedValue as Float
                host.background?.invalidateSelf()
                host.invalidate()
            }
            start()
        }
    }

    fun refreshSoon() {
        host.post { refresh() }
        host.postDelayed({ refresh() }, REFRESH_DELAY)
    }

    fun draw(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        blurRadius: Float,
        tintColor: Int,
        roundBottom: Boolean = true,
        outline: GlassStyle? = null
    ) {
        if (rect.isEmpty) return

        val corner = min(cornerRadius, min(rect.width(), rect.height()) / 2f)
        val bottomCorner = if (roundBottom) corner else 0f
        val density = host.resources.displayMetrics.density

        if (blurRadius < 0.5f) {
            drawTint(canvas, rect, corner, bottomCorner, tintColor, 1f)
            GlassShader.drawOutline(canvas, rect, corner, bottomCorner, outline, 1f, density)
            return
        }

        if (!canvas.isHardwareAccelerated) return

        val alpha = host.effectiveAlpha()
        if (alpha <= 0f) {
            markHidden()
            recheckWhenLayerGone()
            return
        }

        if (host.isSettling()) {
            markHidden()
            recheckWhenLayerGone()
            return
        }

        if (hidden) {
            hidden = false
            nudge = true
            if (SystemClock.uptimeMillis() - hiddenSince > LONG_HIDE) fadeIn()
            refreshSoon()
        }

        val visibleAlpha = alpha * fade
        val visibleBlurRadius = blurRadius * fade

        val regionAlpha = visibleAlpha * (1f - launcherBlur)

        if (visibleBlurRadius >= 0.5f && regionAlpha > 0f) {
            val blur = wallpaperBlur ?: (host.callMethodSilently("getViewRootImpl")
                .callMethodSilently("createBackgroundBlurDrawable") as? Drawable)
                ?.also { wallpaperBlur = it }

            blur?.apply {
                blurBounds.set(
                    rect.left.roundToInt(),
                    rect.top.roundToInt(),
                    rect.right.roundToInt(),
                    rect.bottom.roundToInt()
                )
                if (hasPerCornerRadius()) {
                    callMethodSilently(
                        "setCornerRadius",
                        corner,
                        corner,
                        bottomCorner,
                        bottomCorner
                    )
                } else {
                    if (!roundBottom) blurBounds.bottom += corner.roundToInt()
                    callMethodSilently("setCornerRadius", corner)
                }
                setBounds(blurBounds)
                callMethodSilently("setBlurRadius", visibleBlurRadius.roundToInt() + if (nudge) 1 else 0)
                if (nudge) {
                    nudge = false
                    host.postInvalidateOnAnimation()
                }
                this.alpha = (regionAlpha * 255).roundToInt()
                draw(canvas)
            }
        }

        drawTint(canvas, rect, corner, bottomCorner, tintColor, fade)
        GlassShader.drawOutline(canvas, rect, corner, bottomCorner, outline, fade, density)
    }

    private fun drawTint(
        canvas: Canvas,
        rect: RectF,
        corner: Float,
        bottomCorner: Float,
        tintColor: Int,
        tintFade: Float
    ) {
        if (tintColor ushr 24 == 0) return

        tintPaint.color = tintColor
        tintPaint.alpha = ((tintColor ushr 24) * tintFade).roundToInt()
        radii.fill(corner, 0, 4)
        radii.fill(bottomCorner, 4, 8)
        tintPath.reset()
        tintPath.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(tintPath, tintPaint)
    }

    private fun Drawable.hasPerCornerRadius(): Boolean {
        return javaClass.methods.any { it.name == "setCornerRadius" && it.parameterTypes.size == 4 }
    }

    companion object {
        private val panels: MutableSet<BlurPanel> = Collections.newSetFromMap(WeakHashMap())

        private const val REFRESH_DELAY = 300L
        private const val LAYER_RECHECK_DELAY = 50L
        private const val MAX_RECHECKS = 100
        private const val FADE_IN_DURATION = 250L
        private const val LONG_HIDE = 1000L
        private const val BLUR_STEP = 0.01f

        @Volatile
        private var launcherBlur = 0f

        fun setLauncherBlur(fraction: Float) {
            val value = fraction.coerceIn(0f, 1f)
            if (abs(value - launcherBlur) < BLUR_STEP && value != 0f && value != 1f) return
            if (value == launcherBlur) return
            launcherBlur = value
            refreshAll()
        }

        fun refreshAll() {
            panels.toList().forEach { it.refresh() }
        }

        fun refreshAllSoon() {
            panels.toList().forEach { it.refreshSoon() }
        }
    }

    private fun View.isSettling(): Boolean {
        var current: View? = this
        while (current != null) {
            if (current.layerType != View.LAYER_TYPE_NONE) return true
            current = current.parent as? View
        }
        return false
    }

    private fun View.effectiveAlpha(): Float {
        var result = if (isShown) alpha else 0f
        var current = parent
        while (current is View && result > 0f) {
            result *= current.alpha
            current = current.parent
        }
        return result
    }
}

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
