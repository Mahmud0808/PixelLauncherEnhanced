package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

class IconPackPreviewView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.GRAY)
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val icons = ArrayList<ImageView>()
    private val labels = ArrayList<TextView>()
    private var shown: List<Drawable?> = emptyList()

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val iconSize = (ICON_DP * density).toInt()
        val labelColor = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY)
        repeat(ROWS) { row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            repeat(COLUMNS) {
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                cell.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
                val label = TextView(context).apply {
                    setTextAppearance(resolveAppearance(MaterialR.attr.textAppearanceLabelSmall))
                    setTextColor(labelColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                cell.addView(label, LinearLayout.LayoutParams((CELL_DP * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (6 * density).toInt()
                })
                line.addView(cell, LinearLayout.LayoutParams((CELL_DP * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                icons.add(icon)
                labels.add(label)
            }
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (row > 0) topMargin = (ROW_GAP_DP * density).toInt()
            })
        }
        addView(grid, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setApps(apps: List<Pair<CharSequence, Drawable>>) {
        val animate = shown.isNotEmpty() && ValueAnimator.areAnimatorsEnabled()
        icons.forEachIndexed { index, view ->
            val app = apps.getOrNull(index)
            labels[index].text = app?.first
            val next = app?.second
            val previous = shown.getOrNull(index)
            if (animate && previous != null && next != null && previous !== next) {
                val transition = TransitionDrawable(arrayOf(previous, next)).apply { isCrossFadeEnabled = true }
                view.setImageDrawable(transition)
                view.postDelayed({ transition.startTransition(CROSSFADE_MS) }, index * STAGGER_MS)
                view.animate().cancel()
                view.scaleX = 1f
                view.scaleY = 1f
                view.animate().setStartDelay(index * STAGGER_MS).scaleX(POP_SCALE).scaleY(POP_SCALE).setDuration(CROSSFADE_MS / 2L)
                    .withEndAction { view.animate().scaleX(1f).scaleY(1f).setDuration(CROSSFADE_MS / 2L).start() }
                    .start()
            } else {
                view.setImageDrawable(next)
            }
        }
        shown = icons.indices.map { apps.getOrNull(it)?.second }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glowPaint.shader = RadialGradient(
            w / 2f, h / 2f, minOf(w, h) * 0.6f,
            intArrayOf(ColorUtils.setAlphaComponent(primary, 0x38), ColorUtils.setAlphaComponent(primary, 0x14), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        super.onDraw(canvas)
    }

    private fun resolveAppearance(attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    companion object {
        const val COLUMNS = 4
        const val ROWS = 2
        private const val ICON_DP = 52
        private const val CELL_DP = 76
        private const val ROW_GAP_DP = 14
        private const val CROSSFADE_MS = 320
        private const val STAGGER_MS = 45L
        private const val POP_SCALE = 1.08f
    }
}
