package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeSymmetry
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeTool
import com.drdisagree.pixellauncherenhanced.data.model.ShapeOp
import com.drdisagree.pixellauncherenhanced.utils.IconShapePaths
import com.drdisagree.pixellauncherenhanced.utils.ShapeComposer
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import com.google.android.material.R as MaterialR

class ShapeDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onOutlineFinished(points: List<PointF>)
        fun onStampFinished(tool: ShapeTool, bounds: RectF)
        fun onOpTransformed(index: Int, op: ShapeOp, finished: Boolean)
        fun onOpDeleted(index: Int)
    }

    private enum class Gesture { NONE, DRAW, STAMP, MOVE, RESIZE, DELETE }

    var listener: Listener? = null

    var tool = ShapeTool.OUTLINE
    var addMode = true
        set(value) {
            field = value
            invalidate()
        }
    var symmetry = ShapeSymmetry.NONE
        set(value) {
            field = value
            invalidate()
        }
    var ops: List<ShapeOp> = emptyList()
        set(value) {
            field = value
            if (selected !in value.indices) selected = -1
            invalidate()
        }
    var selected = -1
        set(value) {
            field = value
            invalidate()
        }

    private val points = ArrayList<PointF>()
    private var stampStart: PointF? = null
    private var stampRect: RectF? = null
    private var gesture = Gesture.NONE
    private var dragStart = PointF()
    private var dragOrigin: ShapeOp? = null

    private val composedPath = Path()
    private var hasComposed = false
    private val livePath = Path()
    private val mapped = Path()
    private val viewMatrix = Matrix()
    private val combined = Matrix()
    private val selectionRect = RectF()

    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3 * density, 5 * density), 0f)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shapeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    fun showComposed(pathData: String?) {
        composedPath.reset()
        val parsed = pathData?.takeIf { it.isNotBlank() }?.let { IconShapePaths.toPath(it) }
        hasComposed = parsed != null
        parsed?.let { composedPath.set(it) }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val size = minOf(width, (MAX_SIZE_DP * density).toInt())
        setMeasuredDimension(width, size)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val box = boxSize()
        val left = (width - box) / 2
        val point = PointF((event.x - left) / box * IconShapePaths.SIZE, event.y / box * IconShapePaths.SIZE)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                beginGesture(point)
            }

            MotionEvent.ACTION_MOVE -> when (gesture) {
                Gesture.DRAW -> {
                    for (i in 0 until event.historySize) {
                        points.add(
                            PointF(
                                (event.getHistoricalX(i) - left) / box * IconShapePaths.SIZE,
                                event.getHistoricalY(i) / box * IconShapePaths.SIZE
                            )
                        )
                    }
                    points.add(point)
                }

                Gesture.STAMP -> stampRect = stampBounds(point)
                Gesture.MOVE, Gesture.RESIZE -> transformSelection(point, finished = false)
                else -> Unit
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                when (gesture) {
                    Gesture.DRAW -> listener?.onOutlineFinished(points.toList())
                    Gesture.STAMP -> listener?.onStampFinished(tool, stampRect ?: stampBounds(stampStart ?: point))
                    Gesture.MOVE, Gesture.RESIZE -> transformSelection(point, finished = true)
                    Gesture.DELETE -> if (selected in ops.indices) {
                        val index = selected
                        selected = -1
                        listener?.onOpDeleted(index)
                    }

                    Gesture.NONE -> Unit
                }
                resetGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (gesture == Gesture.MOVE || gesture == Gesture.RESIZE) {
                    dragOrigin?.let { listener?.onOpTransformed(selected, it, finished = true) }
                }
                resetGesture()
            }
        }
        invalidate()
        return true
    }

    private fun beginGesture(point: PointF) {
        points.clear()
        stampRect = null
        dragStart = point

        ops.getOrNull(selected)?.let { op ->
            val bounds = op.bounds()
            when {
                distance(point, PointF(bounds.right, bounds.top)) <= HANDLE_HIT -> {
                    gesture = Gesture.DELETE
                    return
                }

                distance(point, PointF(bounds.right, bounds.bottom)) <= HANDLE_HIT -> {
                    gesture = Gesture.RESIZE
                    dragOrigin = op
                    return
                }

                bounds.contains(point.x, point.y) -> {
                    gesture = Gesture.MOVE
                    dragOrigin = op
                    return
                }
            }
        }

        if (tool == ShapeTool.SELECT) {
            val hit = ops.indices.lastOrNull { ops[it].bounds().contains(point.x, point.y) } ?: -1
            selected = hit
            if (hit >= 0) {
                gesture = Gesture.MOVE
                dragOrigin = ops[hit]
            } else {
                gesture = Gesture.NONE
            }
            return
        }

        selected = -1
        if (tool == ShapeTool.OUTLINE) {
            gesture = Gesture.DRAW
            points.add(point)
        } else {
            gesture = Gesture.STAMP
            stampStart = point
        }
    }

    private fun transformSelection(point: PointF, finished: Boolean) {
        val origin = dragOrigin ?: return
        val bounds = origin.bounds()
        val op = if (gesture == Gesture.MOVE) {
            origin.transformed(point.x - dragStart.x, point.y - dragStart.y, 1f, bounds.centerX(), bounds.centerY())
        } else {
            val startDistance = distance(dragStart, PointF(bounds.centerX(), bounds.centerY()))
            val currentDistance = distance(point, PointF(bounds.centerX(), bounds.centerY()))
            val scale = if (startDistance > 0f) (currentDistance / startDistance).coerceIn(MIN_SCALE, MAX_SCALE) else 1f
            origin.transformed(0f, 0f, scale, bounds.centerX(), bounds.centerY())
        }
        listener?.onOpTransformed(selected, op, finished)
    }

    private fun resetGesture() {
        gesture = Gesture.NONE
        points.clear()
        stampStart = null
        stampRect = null
        dragOrigin = null
    }

    private fun stampBounds(current: PointF): RectF {
        val center = stampStart ?: current
        val half = if (tool == ShapeTool.CIRCLE || tool == ShapeTool.RING) {
            hypot(current.x - center.x, current.y - center.y)
        } else {
            max(abs(current.x - center.x), abs(current.y - center.y))
        }.takeIf { it >= MIN_STAMP_HALF } ?: DEFAULT_STAMP_HALF
        return RectF(center.x - half, center.y - half, center.x + half, center.y + half)
    }

    override fun onDraw(canvas: Canvas) {
        val box = boxSize()
        val left = (width - box) / 2
        val corner = box * 0.08f
        val unit = box / IconShapePaths.SIZE
        val onSurface = MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(this, MaterialR.attr.colorOnPrimary)
        val error = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
        val onError = MaterialColors.getColor(this, MaterialR.attr.colorOnError)

        viewMatrix.setScale(unit, unit)
        viewMatrix.postTranslate(left, 0f)

        boxPaint.color = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerHigh)
        canvas.drawRoundRect(left, 0f, left + box, box, corner, corner, boxPaint)

        dotPaint.color = ColorUtils.setAlphaComponent(onSurface, 45)
        val step = box / GRID_DIVISIONS
        for (row in 1 until GRID_DIVISIONS) {
            for (column in 1 until GRID_DIVISIONS) {
                canvas.drawCircle(left + column * step, row * step, 1.4f * density, dotPaint)
            }
        }

        axisPaint.color = ColorUtils.setAlphaComponent(primary, 110)
        if (symmetry != ShapeSymmetry.NONE) canvas.drawLine(left + box / 2, 0f, left + box / 2, box, axisPaint)
        if (symmetry == ShapeSymmetry.QUAD) canvas.drawLine(left, box / 2, left + box, box / 2, axisPaint)

        if (hasComposed) {
            composedPath.transform(viewMatrix, mapped)
            shapeFill.color = ColorUtils.setAlphaComponent(primary, 70)
            shapeStroke.color = primary
            canvas.drawPath(mapped, shapeFill)
            canvas.drawPath(mapped, shapeStroke)
        }

        guidePaint.color = ColorUtils.setAlphaComponent(onSurface, 110)
        canvas.drawCircle(left + box / 2, box / 2, box * SAFE_ZONE_RATIO, guidePaint)

        livePath.reset()
        if (gesture == Gesture.DRAW && points.size > 1) {
            points.forEachIndexed { index, point ->
                if (index == 0) livePath.moveTo(point.x, point.y) else livePath.lineTo(point.x, point.y)
            }
        } else if (gesture == Gesture.STAMP) {
            stampRect?.let { livePath.set(ShapeComposer.stampPath(tool, it)) }
        }

        if (!livePath.isEmpty) {
            livePaint.color = if (addMode) primary else error
            ShapeComposer.mirrors(symmetry).forEach { mirror ->
                combined.set(mirror)
                combined.postConcat(viewMatrix)
                livePath.transform(combined, mapped)
                canvas.drawPath(mapped, livePaint)
            }
        }

        ops.getOrNull(selected)?.let { op ->
            val bounds = op.bounds()
            selectionRect.set(
                left + bounds.left * unit,
                bounds.top * unit,
                left + bounds.right * unit,
                bounds.bottom * unit
            )
            selectionPaint.color = if (op.add) primary else error
            canvas.drawRect(selectionRect, selectionPaint)

            val handleRadius = HANDLE_RADIUS_DP * density
            handlePaint.color = primary
            canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadius, handlePaint)
            handleIconPaint.color = onPrimary
            val arrow = handleRadius * 0.45f
            drawResizeArrow(canvas, selectionRect.right, selectionRect.bottom, arrow)

            handlePaint.color = error
            canvas.drawCircle(selectionRect.right, selectionRect.top, handleRadius, handlePaint)
            handleIconPaint.color = onError
            canvas.drawLine(
                selectionRect.right - arrow, selectionRect.top - arrow,
                selectionRect.right + arrow, selectionRect.top + arrow, handleIconPaint
            )
            canvas.drawLine(
                selectionRect.right - arrow, selectionRect.top + arrow,
                selectionRect.right + arrow, selectionRect.top - arrow, handleIconPaint
            )
        }
    }

    private fun drawResizeArrow(canvas: Canvas, cx: Float, cy: Float, half: Float) {
        val head = half * 0.65f
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, handleIconPaint)
        canvas.drawLine(cx - half, cy - half, cx - half + head, cy - half, handleIconPaint)
        canvas.drawLine(cx - half, cy - half, cx - half, cy - half + head, handleIconPaint)
        canvas.drawLine(cx + half, cy + half, cx + half - head, cy + half, handleIconPaint)
        canvas.drawLine(cx + half, cy + half, cx + half, cy + half - head, handleIconPaint)
    }

    private fun boxSize(): Float = minOf(width, height).toFloat()

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

    companion object {
        private const val MAX_SIZE_DP = 320
        private const val SAFE_ZONE_RATIO = 0.3f
        private const val GRID_DIVISIONS = 10
        private const val MIN_STAMP_HALF = 3f
        private const val DEFAULT_STAMP_HALF = 12f
        private const val HANDLE_RADIUS_DP = 11f
        private const val HANDLE_HIT = 7f
        private const val MIN_SCALE = 0.15f
        private const val MAX_SCALE = 6f
    }
}
