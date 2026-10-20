package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap

object Checkerboard {

    private const val GREY = 0xFFCCCCCC.toInt()

    fun shader(cell: Int): BitmapShader {
        val bitmap = createBitmap(cell * 2, cell * 2).apply {
            eraseColor(Color.WHITE)
            Canvas(this).apply {
                val grey = Paint().apply { color = GREY }
                drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), grey)
                drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, grey)
            }
        }
        return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
