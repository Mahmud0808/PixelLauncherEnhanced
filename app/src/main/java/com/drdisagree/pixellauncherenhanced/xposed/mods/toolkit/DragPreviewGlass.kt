package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Collections
import java.util.WeakHashMap
import com.drdisagree.pixellauncherenhanced.data.glass.GlassStyle
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass

object DragPreviewGlass {

    private class Spec(
        val matrix: Matrix,
        val path: Path,
        val rect: RectF,
        val corner: Float,
        val blurRadius: Float,
        val alpha: Float,
        val liquid: Boolean,
        val style: GlassStyle
    )

    private const val MAX_CALLBACK_DEPTH = 6

    private val capturing = ThreadLocal<MutableList<Spec>?>()
    private val previews: MutableMap<Drawable, List<Spec>> = Collections.synchronizedMap(WeakHashMap())
    private var installed = false

    fun hasGlass(drawable: Drawable?): Boolean = drawable != null && previews.containsKey(drawable)

    fun isCapturing(canvas: Canvas): Boolean = capturing.get() != null && !canvas.isHardwareAccelerated

    fun capture(
        canvas: Canvas,
        path: Path,
        rect: RectF,
        corner: Float,
        blurRadius: Float,
        alpha: Float,
        liquid: Boolean,
        style: GlassStyle = GlassStyle.DEFAULT
    ) {
        capturing.get()?.add(Spec(Matrix(canvas.matrix), Path(path), RectF(rect), corner, blurRadius, alpha, liquid, style))
    }

    fun install() {
        if (installed) return
        installed = true

        findClass("com.android.launcher3.graphics.DragPreviewProvider", suppressError = true)
            .hookMethod("createDrawable")
            .suppressError()
            .runAround(before = {
                capturing.set(mutableListOf())
            }, after = { param ->
                val specs = capturing.get()
                capturing.remove()
                val drawable = param.result as? Drawable
                if (!specs.isNullOrEmpty() && drawable != null) previews[drawable] = specs
            })

        findClass("com.android.launcher3.icons.FastBitmapDrawable", suppressError = true)
            .hookMethod("draw")
            .suppressError()
            .runBefore { param ->
                if (previews.isEmpty()) return@runBefore
                val drawable = param.thisObject as? Drawable ?: return@runBefore
                val specs = previews[drawable] ?: return@runBefore
                val canvas = param.args.getOrNull(0) as? Canvas ?: return@runBefore
                val host = drawable.hostView() ?: return@runBefore
                drawLive(drawable, canvas, host, specs)
            }
    }

    private fun drawLive(drawable: Drawable, canvas: Canvas, host: View, specs: List<Spec>) {
        val bounds = drawable.bounds
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (bounds.isEmpty || width <= 0 || height <= 0) return

        specs.forEach { spec ->
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
            canvas.concat(spec.matrix)
            canvas.clipPath(spec.path)
            WallpaperGlass.canvasPaint(
                host = host,
                canvas = canvas,
                localRect = spec.rect,
                corner = spec.corner,
                blurRadius = spec.blurRadius,
                alpha = spec.alpha * drawable.alpha / 255f,
                liquid = spec.liquid,
                shapePath = spec.path,
                style = spec.style
            )?.let { canvas.drawPaint(it) }
            canvas.restoreToCount(save)
        }
    }

    private fun Drawable.hostView(): View? {
        var callback: Any? = callback
        repeat(MAX_CALLBACK_DEPTH) {
            when (callback) {
                is View -> return callback as View
                is Drawable -> callback = (callback as Drawable).callback
                else -> return null
            }
        }
        return null
    }
}
