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
import android.graphics.RuntimeShader
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

class FolderFrost(private val host: View) {

    private val roundPath = Path()
    private val pathBounds = RectF()
    private val blurBounds = Rect()
    private val inverse = Matrix()
    private var snapshot: RenderNode? = null
    private val lensBounds = RectF()
    private val appliedBounds = RectF()
    private var appliedRadius = -1f
    private var appliedLens = false
    private var appliedCorner = 0f
    private var lensShader: Any? = null
    private var wallpaperBlur: Drawable? = null
    private var scrimView: WeakReference<View>? = null
    private val scrimPaint = Paint()

    var maxRadius = 0f

    var backdropRadius: Float? = null
    var backdropTint = 0
    var liquid = false
    var home = false
    var progress = 0f
        set(value) {
            field = value
            host.invalidate()
        }

    fun start(targets: List<View>, width: Int, height: Int) {
        snapshot?.discardDisplayList()
        appliedRadius = -1f
        snapshot = RenderNode(NODE_NAME).apply {
            setPosition(0, 0, width, height)
            val canvas = beginRecording(width, height)
            val origin = IntArray(2).also { (host.parent as? View)?.getLocationInWindow(it) }
            val location = IntArray(2)
            targets.filter { it.isShown && it.alpha > 0f }.forEach { target ->
                target.getLocationInWindow(location)
                canvas.save()
                canvas.translate(
                    (location[0] - origin[0] - target.scrollX).toFloat(),
                    (location[1] - origin[1] - target.scrollY).toFloat()
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun updateLensBounds(corner: Float): Float {
        lensBounds.set(pathBounds)
        host.matrix.mapRect(lensBounds)
        lensBounds.offset(host.left.toFloat(), host.top.toFloat())
        val scale = if (pathBounds.width() > 0f) lensBounds.width() / pathBounds.width() else 1f
        return corner * scale
    }

    private fun lensEffect(corner: Float): RenderEffect {
        val shader = lensShader as? RuntimeShader ?: RuntimeShader(LENS_SHADER).also { lensShader = it }
        shader.setFloatUniform("origin", lensBounds.left, lensBounds.top)
        shader.setFloatUniform("size", lensBounds.width(), lensBounds.height())
        shader.setFloatUniform("radius", corner)
        shader.setFloatUniform(
            "refraction",
            min(lensBounds.width(), lensBounds.height()) * WallpaperGlass.LIQUID_REFRACTION
        )
        return RenderEffect.createRuntimeShaderEffect(shader, "image")
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
        val dim = scrimColor()?.takeIf { !home } ?: 0
        canvas.save()
        canvas.clipPath(clip)
        val glass = WallpaperGlass.draw(
            host = host,
            canvas = canvas,
            rect = pathBounds,
            corner = corner,
            bottomCorner = corner,
            blurRadius = backdropRadius ?: maxRadius,
            tintColor = if (home) 0 else backdropTint,
            liquid = liquid,
            dimColor = if (home) backdropTint else dim,
            home = home
        )
        canvas.restore()

        if (!glass) wallpaperBlur?.apply {
            pathBounds.roundOut(blurBounds)
            setBounds(blurBounds)
            callMethodSilently("setCornerRadius", corner)
            callMethodSilently("setBlurRadius", ((backdropRadius ?: maxRadius) * progress).roundToInt())
            draw(canvas)
        }

        snapshot?.let { node ->
            val useLens = liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val lensCorner = if (useLens) updateLensBounds(corner) else 0f
            if (radius != appliedRadius || useLens != appliedLens ||
                (useLens && (lensBounds != appliedBounds || lensCorner != appliedCorner))
            ) {
                appliedRadius = radius
                appliedLens = useLens
                appliedBounds.set(lensBounds)
                appliedCorner = lensCorner
                val blur = if (radius >= 0.5f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
                val lens = if (useLens && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lensEffect(lensCorner) else null
                node.setRenderEffect(
                    when {
                        lens != null && blur != null -> RenderEffect.createChainEffect(lens, blur)
                        else -> lens ?: blur
                    }
                )
            }
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

        if (!glass && backdropTint != 0) {
            scrimPaint.color = backdropTint
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawPaint(scrimPaint)
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

    val glassRadius: Float
        get() = backdropRadius ?: maxRadius

    fun backdropColors(): List<Int> = listOfNotNull(
        scrimColor()?.takeIf { !home },
        backdropTint.takeIf { Color.alpha(it) != 0 }
    )

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
        private const val LENS_SHADER = """
            uniform shader image;
            uniform float2 origin;
            uniform float2 size;
            uniform float radius;
            uniform float refraction;

            float roundedBox(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - halfSize + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 p = coord - origin - halfSize;
                float2 e = float2(1.0, 0.0);
                float d = roundedBox(p, halfSize, radius);
                float2 normal = float2(
                    roundedBox(p + e.xy, halfSize, radius) - roundedBox(p - e.xy, halfSize, radius),
                    roundedBox(p + e.yx, halfSize, radius) - roundedBox(p - e.yx, halfSize, radius)
                );
                normal = normal / max(length(normal), 0.0001);
                float band = clamp(1.0 + d / max(refraction, 1.0), 0.0, 1.0);
                float bend = band * band * band;
                return image.eval(coord - normal * refraction * bend);
            }
        """
        private const val NODE_NAME = "pleFolderFrost"
    }
}
