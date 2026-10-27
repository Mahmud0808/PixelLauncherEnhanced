package com.drdisagree.pixellauncherenhanced.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeSymmetry
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeTool
import com.drdisagree.pixellauncherenhanced.data.model.ComposedShape
import com.drdisagree.pixellauncherenhanced.data.model.ShapeDesign
import com.drdisagree.pixellauncherenhanced.data.model.ShapeOp
import kotlin.math.abs
import kotlin.math.hypot

object ShapeComposer {

    private const val RESOLUTION = 200
    private const val THRESHOLD = 128
    private const val MIN_FILL_RATIO = 0.08f
    private const val MIN_PIECE_AREA = 6f
    private const val SIMPLIFY_EPSILON = 0.45f
    private const val SAFE_RADIUS = 30f
    private const val SAFE_COVERAGE = 0.995f
    private const val RING_INNER_RATIO = 0.55f
    private const val SQUARE_CORNER_RATIO = 0.22f

    fun opPath(op: ShapeOp): Path = when (op) {
        is ShapeOp.Outline -> Path().apply {
            op.points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }

        is ShapeOp.Stamp -> stampPath(op.tool, op.bounds)
    }

    fun stampPath(tool: ShapeTool, bounds: RectF): Path = Path().apply {
        when (tool) {
            ShapeTool.CIRCLE -> addOval(bounds, Path.Direction.CW)
            ShapeTool.SQUARE -> {
                val corner = minOf(bounds.width(), bounds.height()) * SQUARE_CORNER_RATIO
                addRoundRect(bounds, corner, corner, Path.Direction.CW)
            }

            ShapeTool.RING -> {
                fillType = Path.FillType.EVEN_ODD
                addOval(bounds, Path.Direction.CW)
                val insetX = bounds.width() * (1 - RING_INNER_RATIO) / 2
                val insetY = bounds.height() * (1 - RING_INNER_RATIO) / 2
                addOval(
                    RectF(bounds.left + insetX, bounds.top + insetY, bounds.right - insetX, bounds.bottom - insetY),
                    Path.Direction.CW
                )
            }

            ShapeTool.OUTLINE, ShapeTool.SELECT -> Unit
        }
    }

    fun mirrors(symmetry: ShapeSymmetry): List<Matrix> {
        val center = IconShapePaths.SIZE / 2
        fun flip(sx: Float, sy: Float) = Matrix().apply { setScale(sx, sy, center, center) }

        return when (symmetry) {
            ShapeSymmetry.NONE -> listOf(Matrix())
            ShapeSymmetry.MIRROR -> listOf(Matrix(), flip(-1f, 1f))
            ShapeSymmetry.QUAD -> listOf(Matrix(), flip(-1f, 1f), flip(1f, -1f), flip(-1f, -1f))
        }
    }

    fun compose(design: ShapeDesign): ComposedShape? {
        if (design.ops.isEmpty()) return null

        val bitmap = render(design)
        val pixels = IntArray(RESOLUTION * RESOLUTION)
        bitmap.getPixels(pixels, 0, RESOLUTION, 0, 0, RESOLUTION, RESOLUTION)
        bitmap.recycle()

        val alpha = IntArray(pixels.size) { pixels[it] ushr 24 }
        val filled = alpha.count { it >= THRESHOLD }
        if (filled < RESOLUTION * RESOLUTION * MIN_FILL_RATIO) return null

        val contours = trace(alpha)
            .map { ShapeSketch.simplify(it, SIMPLIFY_EPSILON) }
            .filter { it.size >= 3 && abs(ShapeSketch.area(it)) >= MIN_PIECE_AREA }
        if (contours.isEmpty()) return null

        val oriented = contours.map { contour ->
            val depth = contours.count { other -> other !== contour && ShapeSketch.contains(other, contour[0]) }
            val wantPositive = depth % 2 == 0
            if ((ShapeSketch.area(contour) > 0) == wantPositive) contour else contour.reversed()
        }

        val pathData = oriented.joinToString("") { IconShapePaths.smoothClosed(it) }
        return ComposedShape(pathData, coversSafeZone(alpha))
    }

    private fun render(design: ShapeDesign): Bitmap {
        val bitmap = Bitmap.createBitmap(RESOLUTION, RESOLUTION, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(RESOLUTION / IconShapePaths.SIZE, RESOLUTION / IconShapePaths.SIZE)

        val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val transformed = Path()
        val matrices = mirrors(design.symmetry)

        design.ops.forEach { op ->
            val path = opPath(op)
            matrices.forEach { matrix ->
                path.transform(matrix, transformed)
                transformed.fillType = path.fillType
                canvas.drawPath(transformed, if (op.add) addPaint else cutPaint)
            }
        }
        return bitmap
    }

    private fun coversSafeZone(alpha: IntArray): Boolean {
        val scale = IconShapePaths.SIZE / RESOLUTION
        val center = IconShapePaths.SIZE / 2
        var inside = 0
        var covered = 0
        for (y in 0 until RESOLUTION) {
            for (x in 0 until RESOLUTION) {
                val px = (x + 0.5f) * scale
                val py = (y + 0.5f) * scale
                if (hypot(px - center, py - center) > SAFE_RADIUS) continue
                inside++
                if (alpha[y * RESOLUTION + x] >= THRESHOLD) covered++
            }
        }
        return inside == 0 || covered >= inside * SAFE_COVERAGE
    }

    private fun trace(alpha: IntArray): List<List<PointF>> {
        val size = RESOLUTION + 2
        val grid = FloatArray(size * size)
        for (y in 0 until RESOLUTION) {
            for (x in 0 until RESOLUTION) {
                grid[(y + 1) * size + (x + 1)] = alpha[y * RESOLUTION + x] / 255f
            }
        }
        val level = THRESHOLD / 255f
        val scale = IconShapePaths.SIZE / RESOLUTION

        fun value(x: Int, y: Int) = grid[y * size + x]
        fun toShape(gx: Float, gy: Float) = PointF((gx - 0.5f) * scale, (gy - 0.5f) * scale)

        val horizontalOffset = 0
        val verticalOffset = size * size
        fun horizontalEdge(x: Int, y: Int) = horizontalOffset + y * size + x
        fun verticalEdge(x: Int, y: Int) = verticalOffset + y * size + x

        val edgePoints = HashMap<Int, PointF>()
        fun pointOn(edge: Int, x: Int, y: Int, horizontal: Boolean): Int {
            edgePoints.getOrPut(edge) {
                if (horizontal) {
                    val a = value(x, y)
                    val b = value(x + 1, y)
                    val t = if (b != a) ((level - a) / (b - a)).coerceIn(0f, 1f) else 0.5f
                    toShape(x + t, y.toFloat())
                } else {
                    val a = value(x, y)
                    val b = value(x, y + 1)
                    val t = if (b != a) ((level - a) / (b - a)).coerceIn(0f, 1f) else 0.5f
                    toShape(x.toFloat(), y + t)
                }
            }
            return edge
        }

        val links = HashMap<Int, MutableList<Int>>()
        fun link(a: Int, b: Int) {
            links.getOrPut(a) { ArrayList(2) }.add(b)
            links.getOrPut(b) { ArrayList(2) }.add(a)
        }

        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val tl = value(x, y) >= level
                val tr = value(x + 1, y) >= level
                val br = value(x + 1, y + 1) >= level
                val bl = value(x, y + 1) >= level
                val index = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
                if (index == 0 || index == 15) continue

                val top by lazy { pointOn(horizontalEdge(x, y), x, y, true) }
                val bottom by lazy { pointOn(horizontalEdge(x, y + 1), x, y + 1, true) }
                val left by lazy { pointOn(verticalEdge(x, y), x, y, false) }
                val right by lazy { pointOn(verticalEdge(x + 1, y), x + 1, y, false) }
                val centerFilled = (value(x, y) + value(x + 1, y) + value(x + 1, y + 1) + value(x, y + 1)) / 4 >= level

                when (index) {
                    1, 14 -> link(left, bottom)
                    2, 13 -> link(bottom, right)
                    3, 12 -> link(left, right)
                    4, 11 -> link(top, right)
                    6, 9 -> link(top, bottom)
                    7, 8 -> link(left, top)
                    5 -> if (centerFilled) {
                        link(left, top)
                        link(bottom, right)
                    } else {
                        link(left, bottom)
                        link(top, right)
                    }

                    10 -> if (centerFilled) {
                        link(left, bottom)
                        link(top, right)
                    } else {
                        link(left, top)
                        link(bottom, right)
                    }
                }
            }
        }

        val visited = HashSet<Int>()
        val contours = ArrayList<List<PointF>>()
        links.keys.forEach { start ->
            if (start in visited) return@forEach
            val contour = ArrayList<PointF>()
            var previous = -1
            var current = start
            while (current !in visited) {
                visited.add(current)
                edgePoints[current]?.let { contour.add(it) }
                val next = links[current]?.firstOrNull { it != previous && it !in visited }
                    ?: links[current]?.firstOrNull { it != previous }
                    ?: break
                previous = current
                current = next
            }
            if (contour.size >= 3) contours.add(contour)
        }
        return contours
    }
}
