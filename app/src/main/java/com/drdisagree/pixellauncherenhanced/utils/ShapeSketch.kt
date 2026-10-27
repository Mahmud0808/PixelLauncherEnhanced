package com.drdisagree.pixellauncherenhanced.utils

import android.graphics.PointF
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeSketchError
import com.drdisagree.pixellauncherenhanced.data.model.ShapeSketchResult
import kotlin.math.abs
import kotlin.math.hypot

object ShapeSketch {

    private const val MIN_POINTS = 8
    private const val MIN_STEP = 0.6f
    private const val CLOSE_GAP = 15f
    private const val TAIL_SKIP = 6
    private const val SIMPLIFY_EPSILON = 0.4f

    fun close(raw: List<PointF>): ShapeSketchResult {
        val points = dedupe(raw)
        if (points.size < MIN_POINTS) return ShapeSketchResult.Failure(ShapeSketchError.TOO_SHORT)

        val loop = closeLoop(points) ?: return ShapeSketchResult.Failure(ShapeSketchError.NOT_CLOSED)
        return ShapeSketchResult.Success(simplify(loop))
    }

    fun simplify(loop: List<PointF>, epsilon: Float = SIMPLIFY_EPSILON): List<PointF> {
        if (loop.size < 4) return loop
        val keep = BooleanArray(loop.size)
        keep[0] = true
        keep[loop.size - 1] = true
        douglasPeucker(loop, 0, loop.size - 1, epsilon, keep)
        return loop.filterIndexed { index, _ -> keep[index] }.takeIf { it.size >= 3 } ?: loop
    }

    fun area(loop: List<PointF>): Float {
        var sum = 0f
        for (i in loop.indices) {
            val a = loop[i]
            val b = loop[(i + 1) % loop.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2
    }

    fun contains(loop: List<PointF>, point: PointF): Boolean {
        var inside = false
        var j = loop.size - 1
        for (i in loop.indices) {
            val a = loop[i]
            val b = loop[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
            ) inside = !inside
            j = i
        }
        return inside
    }

    private fun dedupe(raw: List<PointF>): List<PointF> {
        val result = ArrayList<PointF>(raw.size)
        raw.forEach { point ->
            val last = result.lastOrNull()
            if (last == null || distance(last, point) >= MIN_STEP) result.add(PointF(point.x, point.y))
        }
        return result
    }

    private fun closeLoop(points: List<PointF>): List<PointF>? {
        val candidates = ArrayList<List<PointF>>()

        for (i in 0 until points.size - 1) {
            for (j in i + 2 until points.size - 1) {
                val hit = intersection(points[i], points[i + 1], points[j], points[j + 1]) ?: continue
                candidates.add(listOf(hit) + points.subList(i + 1, j + 1))
            }
        }

        if (candidates.isEmpty()) {
            val first = points.first()
            val last = points.last()

            if (distance(first, last) <= CLOSE_GAP) {
                candidates.add(points)
            } else {
                nearestIndex(points, last, 0 until points.size - TAIL_SKIP)
                    ?.let { candidates.add(points.subList(it, points.size)) }
                nearestIndex(points, first, TAIL_SKIP until points.size)
                    ?.let { candidates.add(points.subList(0, it + 1)) }
            }
        }

        return candidates
            .filter { it.size >= 3 }
            .maxByOrNull { abs(area(it)) }
    }

    private fun nearestIndex(points: List<PointF>, target: PointF, range: IntRange): Int? {
        if (range.isEmpty()) return null
        val best = range.minByOrNull { distance(points[it], target) } ?: return null
        return best.takeIf { distance(points[it], target) <= CLOSE_GAP }
    }

    private fun douglasPeucker(points: List<PointF>, start: Int, end: Int, epsilon: Float, keep: BooleanArray) {
        if (end <= start + 1) return
        var maxDistance = 0f
        var index = start
        for (i in start + 1 until end) {
            val d = segmentDistance(points[i], points[start], points[end])
            if (d > maxDistance) {
                maxDistance = d
                index = i
            }
        }
        if (maxDistance > epsilon) {
            keep[index] = true
            douglasPeucker(points, start, index, epsilon, keep)
            douglasPeucker(points, index, end, epsilon, keep)
        }
    }

    private fun intersection(p1: PointF, p2: PointF, p3: PointF, p4: PointF): PointF? {
        val d = (p2.x - p1.x) * (p4.y - p3.y) - (p2.y - p1.y) * (p4.x - p3.x)
        if (abs(d) < 1e-6f) return null
        val t = ((p3.x - p1.x) * (p4.y - p3.y) - (p3.y - p1.y) * (p4.x - p3.x)) / d
        val u = ((p3.x - p1.x) * (p2.y - p1.y) - (p3.y - p1.y) * (p2.x - p1.x)) / d
        if (t <= 0f || t >= 1f || u <= 0f || u >= 1f) return null
        return PointF(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
    }

    private fun segmentDistance(point: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return distance(point, a)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy))
    }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}
