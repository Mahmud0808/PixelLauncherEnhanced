package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

@RequiresApi(Build.VERSION_CODES.S)
class FolderFrost(private val host: View) {

    private val roundPath = Path()
    private val pathBounds = RectF()
    private val blurBounds = Rect()
    private val inverse = Matrix()
    private var snapshot: RenderNode? = null
    private var wallpaperBlur: Drawable? = null
    private var scrimView: WeakReference<View>? = null
    private val scrimPaint = Paint()

    var maxRadius = 0f
    var liquid = false
    var progress = 0f
        set(value) {
            field = value
            host.invalidate()
        }

    fun start(targets: List<View>, width: Int, height: Int) {
        snapshot?.discardDisplayList()
        snapshot = RenderNode(NODE_NAME).apply {
            setPosition(0, 0, width, height)
            val canvas = beginRecording(width, height)
            targets.filter { it.isShown && it.alpha > 0f }.forEach { target ->
                canvas.save()
                canvas.translate(
                    target.left + target.translationX - target.scrollX,
                    target.top + target.translationY - target.scrollY
                )
                target.draw(canvas)
                canvas.restore()
            }
            endRecording()
        }

        if (wallpaperBlur == null) {
            wallpaperBlur = host.callMethodSilently("getViewRootImpl")
                .callMethodSilently("createBackgroundBlurDrawable") as? Drawable
        }
    }

    fun stop() {
        snapshot?.discardDisplayList()
        snapshot = null
        progress = 0f
    }

    fun draw(canvas: Canvas, clipPath: Path?, backgroundBounds: Rect, cornerRadius: Float) {
        val radius = maxRadius * progress
        if (progress <= 0f || !canvas.isHardwareAccelerated) return

        val clip = if (clipPath != null && !clipPath.isEmpty) {
            clipPath.computeBounds(pathBounds, true)
            clipPath
        } else {
            if (backgroundBounds.isEmpty) return
            pathBounds.set(backgroundBounds)
            roundPath.reset()
            roundPath.addRoundRect(pathBounds, cornerRadius, cornerRadius, Path.Direction.CW)
            roundPath
        }
        if (pathBounds.isEmpty) return

        val corner = if (clip === clipPath) {
            WallpaperGlass.pathCorner(clip, pathBounds)
        } else {
            min(cornerRadius, min(pathBounds.width(), pathBounds.height()) / 2f)
        }
        val dim = scrimColor() ?: 0
        canvas.save()
        canvas.clipPath(clip)
        val glass = WallpaperGlass.draw(
            host = host,
            canvas = canvas,
            rect = pathBounds,
            corner = corner,
            bottomCorner = corner,
            blurRadius = maxRadius,
            tintColor = 0,
            liquid = liquid,
            dimColor = dim
        )
        canvas.restore()

        if (!glass) wallpaperBlur?.apply {
            pathBounds.roundOut(blurBounds)
            setBounds(blurBounds)
            callMethodSilently("setCornerRadius", corner)
            callMethodSilently("setBlurRadius", radius.roundToInt())
            draw(canvas)
        }

        snapshot?.let { node ->
            node.setRenderEffect(
                if (radius >= 0.5f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
            )
            canvas.save()
            canvas.clipPath(clip)
            canvas.saveLayer(pathBounds, null)
            if (host.matrix.invert(inverse)) canvas.concat(inverse)
            canvas.translate(-host.left.toFloat(), -host.top.toFloat())
            canvas.drawRenderNode(node)
            if (dim != 0) canvas.drawColor(dim, PorterDuff.Mode.SRC_ATOP)
            canvas.restore()
            canvas.restore()
        }

        if (!glass && dim != 0) {
            scrimPaint.color = dim
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawPaint(scrimPaint)
            canvas.restore()
        }
    }

    private fun scrimColor(): Int? {
        val scrim = scrimView?.get()?.takeIf { it.isAttachedToWindow && it.rootView === host.rootView }
            ?: host.rootView.findScrimView()?.also { scrimView = WeakReference(it) }
            ?: return null
        if (scrim.visibility != View.VISIBLE || scrim.alpha <= 0f) return null

        val color = scrim.getFieldSilently("mBackgroundColor") as? Int
            ?: (scrim.background as? ColorDrawable)?.color
            ?: return null
        val alpha = (Color.alpha(color) * scrim.alpha).roundToInt().coerceIn(0, 255)
        return if (alpha == 0) null else ColorUtils.setAlphaComponent(color, alpha)
    }

    private fun View.findScrimView(): View? {
        if (javaClass.name.endsWith(".ScrimView")) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) {
            getChildAt(i).findScrimView()?.let { return it }
        }
        return null
    }

    companion object {
        private const val NODE_NAME = "pleFolderFrost"
    }
}
