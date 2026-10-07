package com.drdisagree.pixellauncherenhanced.data.model

import android.graphics.PointF
import android.graphics.RectF
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeTool

sealed class ShapeOp(val add: Boolean) {

    abstract fun bounds(): RectF

    abstract fun transformed(dx: Float, dy: Float, scale: Float, pivotX: Float, pivotY: Float): ShapeOp

    class Outline(val points: List<PointF>, add: Boolean) : ShapeOp(add) {
        override fun bounds(): RectF = RectF(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y }
        )

        override fun transformed(dx: Float, dy: Float, scale: Float, pivotX: Float, pivotY: Float): ShapeOp =
            Outline(
                points.map { PointF(pivotX + (it.x - pivotX) * scale + dx, pivotY + (it.y - pivotY) * scale + dy) },
                add
            )
    }

    class Stamp(val tool: ShapeTool, val bounds: RectF, add: Boolean) : ShapeOp(add) {
        override fun bounds(): RectF = RectF(bounds)

        override fun transformed(dx: Float, dy: Float, scale: Float, pivotX: Float, pivotY: Float): ShapeOp =
            Stamp(
                tool,
                RectF(
                    pivotX + (bounds.left - pivotX) * scale + dx,
                    pivotY + (bounds.top - pivotY) * scale + dy,
                    pivotX + (bounds.right - pivotX) * scale + dx,
                    pivotY + (bounds.bottom - pivotY) * scale + dy
                ),
                add
            )
    }
}
