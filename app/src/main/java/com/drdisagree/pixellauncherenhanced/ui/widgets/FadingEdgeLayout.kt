package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

class FadingEdgeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val fadeLength = FADE_DP * resources.displayMetrics.density
    private val topPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val bottomPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var scrollingChild: View? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = invalidate()
    }

    init {
        setWillNotDraw(false)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (scrollingChild != null) return
        when (child) {
            is RecyclerView -> child.addOnScrollListener(scrollListener)
            is ScrollView, is NestedScrollView -> child.setOnScrollChangeListener { _, _, _, _, _ -> invalidate() }
            else -> return
        }
        scrollingChild = child
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        if (child === scrollingChild) {
            (child as? RecyclerView)?.removeOnScrollListener(scrollListener)
            if (child !is RecyclerView) child.setOnScrollChangeListener(null)
            scrollingChild = null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        topPaint.shader = LinearGradient(0f, 0f, 0f, fadeLength, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bottomPaint.shader = LinearGradient(0f, h - fadeLength, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = scrollingChild
        val fadeTop = child?.isShown == true && child.canScrollVertically(-1)
        val fadeBottom = child?.isShown == true && child.canScrollVertically(1)
        if (!fadeTop && !fadeBottom) {
            super.dispatchDraw(canvas)
            return
        }

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        if (fadeTop) canvas.drawRect(0f, 0f, width.toFloat(), fadeLength, topPaint)
        if (fadeBottom) canvas.drawRect(0f, height - fadeLength, width.toFloat(), height.toFloat(), bottomPaint)
        canvas.restoreToCount(layer)
    }

    companion object {
        private const val FADE_DP = 32f
    }
}
