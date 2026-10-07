package com.drdisagree.pixellauncherenhanced.utils

import android.graphics.Path
import android.graphics.PointF
import androidx.core.graphics.PathParser
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object IconShapePaths {

    const val SIZE = 100f
    const val CIRCLE = "M50 0A50 50,0,1,1,50 100A50 50,0,1,1,50 0Z"

    private const val SQUIRCLE_POINTS = 180
    private const val MIN_STAR_INNER = 0.66f
    private const val MAX_WAVE_AMPLITUDE = 8.5f
    private const val WAVE_SAMPLES = 6
    private const val BOUNDS_STEPS = 12
    private const val SQUARE = "M0 0H100V100H0Z"

    fun roundedSquare(radius: Float): String {
        val r = radius.coerceIn(0f, SIZE / 2)
        if (r >= SIZE / 2) return CIRCLE
        if (r <= 0f) return SQUARE
        return corners(r, r, r, r)
    }

    fun corners(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float): String {
        val tl = topLeft.coerceIn(0f, SIZE / 2)
        val tr = topRight.coerceIn(0f, SIZE / 2)
        val br = bottomRight.coerceIn(0f, SIZE / 2)
        val bl = bottomLeft.coerceIn(0f, SIZE / 2)

        return buildString {
            append("M${f(tl)} 0")
            append("H${f(SIZE - tr)}")
            if (tr > 0f) append("A${f(tr)} ${f(tr)} 0 0 1 100 ${f(tr)}")
            append("V${f(SIZE - br)}")
            if (br > 0f) append("A${f(br)} ${f(br)} 0 0 1 ${f(SIZE - br)} 100")
            append("H${f(bl)}")
            if (bl > 0f) append("A${f(bl)} ${f(bl)} 0 0 1 0 ${f(SIZE - bl)}")
            append("V${f(tl)}")
            if (tl > 0f) append("A${f(tl)} ${f(tl)} 0 0 1 ${f(tl)} 0")
            append("Z")
        }
    }

    fun smoothCorners(radius: Float, smoothing: Float): String {
        val r = radius.coerceIn(0f, SIZE / 2)
        if (r <= 0f) return SQUARE

        val xi = smoothing.coerceIn(0f, 1f).coerceAtMost(SIZE / 2 / r - 1f).coerceAtLeast(0f)
        val p = (1 + xi) * r
        val arcMeasure = 90f * (1 - xi)
        val arcLength = (sin(Math.toRadians(arcMeasure / 2.0)) * r * sqrt(2.0)).toFloat()
        val alpha = (90f - arcMeasure) / 2
        val p3ToP4 = (r * tan(Math.toRadians(alpha / 2.0))).toFloat()
        val beta = 45.0 * xi
        val c = (p3ToP4 * cos(Math.toRadians(beta))).toFloat()
        val d = (c * tan(Math.toRadians(beta))).toFloat()
        val b = (p - arcLength - c - d) / 3
        val a = 2 * b

        val start = PointF(SIZE - p, 0f)
        val e1 = PointF(start.x + a + b + c, d)
        val e2 = PointF(e1.x + arcLength, e1.y + arcLength)
        val corner = listOf(
            PointF(start.x + a, 0f),
            PointF(start.x + a + b, 0f),
            e1,
            e2,
            PointF(e2.x + d, e2.y + c),
            PointF(e2.x + d, e2.y + b + c),
            PointF(SIZE, p)
        )

        return buildString {
            append("M${f(p)} 0")
            repeat(4) { turns ->
                val lineTo = rotate(start, turns)
                val pts = corner.map { rotate(it, turns) }
                append("L${f(lineTo.x)} ${f(lineTo.y)}")
                append("C${f(pts[0].x)} ${f(pts[0].y)} ${f(pts[1].x)} ${f(pts[1].y)} ${f(pts[2].x)} ${f(pts[2].y)}")
                append("A${f(r)} ${f(r)} 0 0 1 ${f(pts[3].x)} ${f(pts[3].y)}")
                append("C${f(pts[4].x)} ${f(pts[4].y)} ${f(pts[5].x)} ${f(pts[5].y)} ${f(pts[6].x)} ${f(pts[6].y)}")
            }
            append("Z")
        }
    }

    fun roundedPolygon(sides: Int, roundness: Float, rotation: Float): String {
        val count = sides.coerceAtLeast(3)
        val polygon = RoundedPolygon(
            numVertices = count,
            rounding = CornerRounding(roundness.coerceIn(0f, 1f) * 0.5f)
        )
        val angle = -90.0 + rotation.coerceIn(0f, 1f) * 360.0 / count
        return fitCubics(polygon.cubics, angle)
    }

    fun softStar(points: Int, depth: Float, roundness: Float): String {
        val inner = 1f - depth.coerceIn(0f, 1f) * (1f - MIN_STAR_INNER)
        val star = RoundedPolygon.star(
            numVerticesPerRadius = points.coerceAtLeast(3),
            innerRadius = inner,
            rounding = CornerRounding(roundness.coerceIn(0f, 1f) * 0.35f),
            innerRounding = CornerRounding(0.2f)
        )
        return fitCubics(star.cubics, -90.0)
    }

    fun wavy(waves: Int, height: Float): String {
        val waveCount = waves.coerceAtLeast(2)
        val amplitude = height.coerceIn(0f, 1f) * MAX_WAVE_AMPLITUDE
        val base = SIZE / 2 - amplitude
        val count = waveCount * WAVE_SAMPLES
        val points = (0 until count).map { index ->
            val t = 2 * Math.PI * index / count
            val radius = base + amplitude * cos(waveCount * t).toFloat()
            PointF(
                (SIZE / 2 + radius * cos(t - Math.PI / 2)).toFloat(),
                (SIZE / 2 + radius * sin(t - Math.PI / 2)).toFloat()
            )
        }
        return smoothClosed(points)
    }

    fun squircle(exponent: Float): String {
        val n = exponent.coerceAtLeast(2f)
        val points = (0 until SQUIRCLE_POINTS).map { index ->
            val t = 2 * Math.PI * index / SQUIRCLE_POINTS
            val c = cos(t)
            val s = sin(t)
            PointF(
                (50 + 50 * sign(c) * abs(c).pow(2.0 / n)).toFloat(),
                (50 + 50 * sign(s) * abs(s).pow(2.0 / n)).toFloat()
            )
        }
        return polygon(points)
    }

    fun polygon(points: List<PointF>): String = buildString {
        points.forEachIndexed { index, point ->
            append(if (index == 0) "M" else "L")
            append("${f(point.x)} ${f(point.y)}")
        }
        append("Z")
    }

    fun smoothClosed(points: List<PointF>): String {
        if (points.size < 3) return polygon(points)

        return buildString {
            append("M${f(points[0].x)} ${f(points[0].y)}")
            for (i in points.indices) {
                val p0 = points[(i - 1 + points.size) % points.size]
                val p1 = points[i]
                val p2 = points[(i + 1) % points.size]
                val p3 = points[(i + 2) % points.size]
                val c1x = p1.x + (p2.x - p0.x) / 6f
                val c1y = p1.y + (p2.y - p0.y) / 6f
                val c2x = p2.x - (p3.x - p1.x) / 6f
                val c2y = p2.y - (p3.y - p1.y) / 6f
                append("C${f(c1x)} ${f(c1y)} ${f(c2x)} ${f(c2y)} ${f(p2.x)} ${f(p2.y)}")
            }
            append("Z")
        }
    }

    fun toPath(pathData: String): Path? = runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull()

    private fun fitCubics(cubics: List<Cubic>, rotationDegrees: Double): String {
        if (cubics.isEmpty()) return CIRCLE
        val rad = Math.toRadians(rotationDegrees)
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        fun turn(x: Float, y: Float) = PointF(x * cosA - y * sinA, x * sinA + y * cosA)

        val segments = cubics.map { cubic ->
            listOf(
                turn(cubic.anchor0X, cubic.anchor0Y),
                turn(cubic.control0X, cubic.control0Y),
                turn(cubic.control1X, cubic.control1Y),
                turn(cubic.anchor1X, cubic.anchor1Y)
            )
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        segments.forEach { (p0, p1, p2, p3) ->
            for (step in 0..BOUNDS_STEPS) {
                val t = step.toFloat() / BOUNDS_STEPS
                val u = 1 - t
                val x = u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x
                val y = u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }

        val extent = maxOf(maxX - minX, maxY - minY).takeIf { it > 0f } ?: return CIRCLE
        val scale = SIZE / extent
        val offsetX = (SIZE - (maxX - minX) * scale) / 2 - minX * scale
        val offsetY = (SIZE - (maxY - minY) * scale) / 2 - minY * scale
        fun map(point: PointF) = "${f(point.x * scale + offsetX)} ${f(point.y * scale + offsetY)}"

        return buildString {
            append("M${map(segments.first()[0])}")
            segments.forEach { segment ->
                append("C${map(segment[1])} ${map(segment[2])} ${map(segment[3])}")
            }
            append("Z")
        }
    }

    private fun rotate(point: PointF, turns: Int): PointF {
        var x = point.x
        var y = point.y
        repeat(turns) {
            val nextX = SIZE - y
            y = x
            x = nextX
        }
        return PointF(x, y)
    }

    private fun f(value: Float): String {
        val rounded = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        return if (rounded == "-0") "0" else rounded
    }
}
