package com.drdisagree.pixellauncherenhanced.utils

import com.drdisagree.pixellauncherenhanced.data.enums.IconShapeMode

object IconShapeFactory {

    const val CORNERS_LINKED = "corners.linked"

    fun path(mode: IconShapeMode, params: Map<String, Int>, customPath: String): String {
        fun value(key: String): Int = params[key] ?: mode.params.first { it.key == key }.default

        return when (mode) {
            IconShapeMode.DEFAULT -> ""
            IconShapeMode.ROUNDED_SQUARE -> IconShapePaths.roundedSquare(value("rounded.radius").toFloat())
            IconShapeMode.SQUIRCLE -> IconShapePaths.squircle(value("squircle.curve") / 10f)
            IconShapeMode.CORNERS -> IconShapePaths.corners(
                value("corners.tl").toFloat(),
                value("corners.tr").toFloat(),
                value("corners.br").toFloat(),
                value("corners.bl").toFloat()
            )
            IconShapeMode.SMOOTH -> IconShapePaths.smoothCorners(
                value("smooth.size").toFloat(),
                value("smooth.smoothing") / 100f
            )
            IconShapeMode.POLYGON -> IconShapePaths.roundedPolygon(
                value("polygon.sides"),
                value("polygon.roundness") / 100f,
                value("polygon.rotation") / 100f
            )
            IconShapeMode.STAR -> IconShapePaths.softStar(
                value("star.points"),
                value("star.depth") / 100f,
                value("star.roundness") / 100f
            )
            IconShapeMode.WAVY -> IconShapePaths.wavy(value("wavy.waves"), value("wavy.height") / 100f)
            IconShapeMode.CUSTOM -> customPath
        }
    }

    fun decode(serialized: String?): MutableMap<String, Int> {
        val result = HashMap<String, Int>()
        serialized.orEmpty().split(',').forEach { entry ->
            val parts = entry.split('=')
            if (parts.size == 2) parts[1].toIntOrNull()?.let { result[parts[0]] = it }
        }
        return result
    }

    fun encode(params: Map<String, Int>): String = params.entries.joinToString(",") { "${it.key}=${it.value}" }
}
