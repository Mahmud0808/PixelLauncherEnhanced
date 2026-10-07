package com.drdisagree.pixellauncherenhanced.data.model

import android.graphics.PointF
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeSketchError

sealed class ShapeSketchResult {
    class Success(val outline: List<PointF>) : ShapeSketchResult()
    class Failure(val error: ShapeSketchError) : ShapeSketchResult()
}
