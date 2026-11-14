package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.reflection.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.reflection.setFieldSilently
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object FolderShape {

    private const val PROBE_SCALE = 0.5f
    private const val CACHE_SIZE = 8
    private val CORNER_AREA = 1f - (PI / 4).toFloat()

    var isDrawing = false
        private set

    private val probePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val cache = LinkedHashMap<List<Any?>, FloatArray>()

    fun isEnlarged(folderIcon: View): Boolean {
        val info = folderIcon.tag ?: return false
        val spanX = info.getFieldSilently("spanX") as? Int ?: 1
        val spanY = info.getFieldSilently("spanY") as? Int ?: 1
        return spanX > 1 || spanY > 1
    }

    fun draw(preview: Any, canvas: Canvas, paint: Paint) {
        val originalPaint = preview.getFieldSilently("mPaint") as? Paint ?: return
        val originalColor = preview.getFieldSilently("mBgColor") as? Int ?: return
        preview.setFieldSilently("mPaint", paint)
        preview.setFieldSilently("mBgColor", paint.color)
        isDrawing = true
        try {
            preview.callMethodSilently("drawBackground", canvas)
        } finally {
            isDrawing = false
            preview.setFieldSilently("mPaint", originalPaint)
            preview.setFieldSilently("mBgColor", originalColor)
        }
    }

    fun measure(folderIcon: View, preview: Any, shapeKey: Any?, out: RectF): Float? {
        val width = folderIcon.width
        val height = folderIcon.height
        if (width <= 0 || height <= 0) return null
        val info = folderIcon.tag
        val key = listOf(
            width,
            height,
            info.getFieldSilently("spanX"),
            info.getFieldSilently("spanY"),
            preview.getFieldSilently("mScale"),
            preview.getFieldSilently("previewSize"),
            preview.getFieldSilently("basePreviewOffsetX"),
            preview.getFieldSilently("basePreviewOffsetY"),
            preview.getFieldSilently("mFolderStyle"),
            shapeKey
        )
        val bounds = cache[key] ?: probe(preview, width, height)?.also {
            if (cache.size >= CACHE_SIZE) cache.remove(cache.keys.first())
            cache[key] = it
        } ?: return null
        out.set(bounds[0], bounds[1], bounds[2], bounds[3])
        return bounds[4]
    }

    private fun probe(preview: Any, width: Int, height: Int): FloatArray? {
        val probeWidth = ceil(width * PROBE_SCALE).toInt()
        val probeHeight = ceil(height * PROBE_SCALE).toInt()
        val bitmap = Bitmap.createBitmap(probeWidth, probeHeight, Bitmap.Config.ALPHA_8)
        Canvas(bitmap).apply {
            scale(PROBE_SCALE, PROBE_SCALE)
            draw(preview, this, probePaint)
        }
        val stride = bitmap.rowBytes
        val pixels = ByteArray(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
        bitmap.recycle()

        fun coverage(x: Int, y: Int): Float = (pixels[y * stride + x].toInt() and 0xFF) / 255f

        var minX = probeWidth
        var minY = probeHeight
        var maxX = -1
        var maxY = -1
        for (y in 0 until probeHeight) {
            for (x in 0 until probeWidth) {
                if (pixels[y * stride + x].toInt() == 0) continue
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }
        if (maxX < minX || maxY < minY) return null

        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        val left = (minX..maxX).first { coverage(it, centerY) > 0f }.let { it + 1f - coverage(it, centerY) }
        val right = (maxX downTo minX).first { coverage(it, centerY) > 0f }.let { it + coverage(it, centerY) }
        val top = (minY..maxY).first { coverage(centerX, it) > 0f }.let { it + 1f - coverage(centerX, it) }
        val bottom = (maxY downTo minY).first { coverage(centerX, it) > 0f }.let { it + coverage(centerX, it) }

        val half = min(right - left, bottom - top) / 2f
        val endX = (left + half).roundToInt()
        val endY = (top + half).roundToInt()
        var covered = 0f
        for (y in floor(top).toInt() until endY) {
            for (x in floor(left).toInt() until endX) covered += coverage(x, y)
        }
        val missing = max(0f, (endX - left) * (endY - top) - covered)
        val corner = min(half, sqrt(missing / CORNER_AREA))

        return floatArrayOf(
            left / PROBE_SCALE,
            top / PROBE_SCALE,
            right / PROBE_SCALE,
            bottom / PROBE_SCALE,
            corner / PROBE_SCALE
        )
    }
}
