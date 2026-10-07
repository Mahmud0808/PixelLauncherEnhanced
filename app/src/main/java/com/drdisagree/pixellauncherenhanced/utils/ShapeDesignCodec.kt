package com.drdisagree.pixellauncherenhanced.utils

import android.graphics.PointF
import android.graphics.RectF
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeSymmetry
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeTool
import com.drdisagree.pixellauncherenhanced.data.model.ShapeDesign
import com.drdisagree.pixellauncherenhanced.data.model.ShapeOp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object ShapeDesignCodec {

    private const val KEY_SYMMETRY = "symmetry"
    private const val KEY_OPS = "ops"
    private const val KEY_TYPE = "type"
    private const val KEY_ADD = "add"
    private const val KEY_POINTS = "points"
    private const val KEY_TOOL = "tool"
    private const val KEY_BOUNDS = "bounds"
    private const val TYPE_OUTLINE = "outline"
    private const val TYPE_STAMP = "stamp"

    fun encode(design: ShapeDesign): String {
        val ops = JSONArray()
        design.ops.forEach { op ->
            ops.put(
                JSONObject().apply {
                    put(KEY_ADD, op.add)
                    when (op) {
                        is ShapeOp.Outline -> {
                            put(KEY_TYPE, TYPE_OUTLINE)
                            put(KEY_POINTS, JSONArray().apply {
                                op.points.forEach { point ->
                                    put(round(point.x))
                                    put(round(point.y))
                                }
                            })
                        }

                        is ShapeOp.Stamp -> {
                            put(KEY_TYPE, TYPE_STAMP)
                            put(KEY_TOOL, op.tool.key)
                            put(KEY_BOUNDS, JSONArray().apply {
                                put(round(op.bounds.left))
                                put(round(op.bounds.top))
                                put(round(op.bounds.right))
                                put(round(op.bounds.bottom))
                            })
                        }
                    }
                }
            )
        }
        return JSONObject().apply {
            put(KEY_SYMMETRY, design.symmetry.value)
            put(KEY_OPS, ops)
        }.toString()
    }

    fun decode(serialized: String?): ShapeDesign {
        if (serialized.isNullOrBlank()) return ShapeDesign(emptyList(), ShapeSymmetry.NONE)

        return runCatching {
            val root = JSONObject(serialized)
            val array = root.optJSONArray(KEY_OPS) ?: JSONArray()
            val ops = (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val add = item.optBoolean(KEY_ADD, true)
                when (item.optString(KEY_TYPE)) {
                    TYPE_OUTLINE -> {
                        val values = item.getJSONArray(KEY_POINTS)
                        val points = (0 until values.length() / 2).map {
                            PointF(values.getDouble(it * 2).toFloat(), values.getDouble(it * 2 + 1).toFloat())
                        }
                        ShapeOp.Outline(points, add).takeIf { points.size >= 3 }
                    }

                    TYPE_STAMP -> {
                        val values = item.getJSONArray(KEY_BOUNDS)
                        ShapeOp.Stamp(
                            ShapeTool.fromKey(item.optString(KEY_TOOL)),
                            RectF(
                                values.getDouble(0).toFloat(),
                                values.getDouble(1).toFloat(),
                                values.getDouble(2).toFloat(),
                                values.getDouble(3).toFloat()
                            ),
                            add
                        )
                    }

                    else -> null
                }
            }
            ShapeDesign(ops, ShapeSymmetry.fromValue(root.optInt(KEY_SYMMETRY, 0)))
        }.getOrDefault(ShapeDesign(emptyList(), ShapeSymmetry.NONE))
    }

    private fun round(value: Float): Double = (value * 100).roundToInt() / 100.0
}
