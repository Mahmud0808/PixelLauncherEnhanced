package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
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
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.glass.GlassShader
import com.drdisagree.pixellauncherenhanced.data.glass.GlassStyle
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

class FolderFrost(private val host: View) {

    private val roundPath = Path()
    private val pathBounds = RectF()
    private val blurBounds = Rect()
    private val inverse = Matrix()
    private var snapshot: RenderNode? = null
    private var baked: Bitmap? = null
    private var frostScale = 1f
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
    private var panelStyle = GlassStyle.DEFAULT
    var style = GlassStyle.DEFAULT
        set(value) {
            field = value
            panelStyle = value.copy(outline = false)
        }
    var home = false
    var progress = 0f
        set(value) {
            field = value
            host.invalidate()
        }

    fun start(targets: List<View>, width: Int, height: Int) {
        snapshot?.discardDisplayList()
        releaseBaked()
        appliedRadius = -1f
        val content = RenderNode(CONTENT_NAME).apply {
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
        val smallWidth = (width * FROST_SCALE).roundToInt().coerceAtLeast(1)
        val smallHeight = (height * FROST_SCALE).roundToInt().coerceAtLeast(1)
        val scaled = RenderNode(SCALED_NAME).apply {
            setPosition(0, 0, smallWidth, smallHeight)
            val canvas = beginRecording(smallWidth, smallHeight)
            canvas.scale(smallWidth / width.toFloat(), smallHeight / height.toFloat())
            canvas.drawRenderNode(content)
            endRecording()
        }
        val image = bake(scaled, smallWidth, smallHeight)
        scaled.discardDisplayList()
        baked = image
        frostScale = if (image == null) 1f else smallWidth / width.toFloat()
        snapshot = if (image == null) content else RenderNode(NODE_NAME).apply {
            setPosition(0, 0, smallWidth, smallHeight)
            pivotX = 0f
            pivotY = 0f
            scaleX = width / smallWidth.toFloat()
            scaleY = height / smallHeight.toFloat()
            beginRecording(smallWidth, smallHeight).drawBitmap(image, 0f, 0f, null)
            endRecording()
            content.discardDisplayList()
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
        val scale = frostScale
        shader.setFloatUniform("origin", lensBounds.left * scale, lensBounds.top * scale)
        shader.setFloatUniform("size", lensBounds.width() * scale, lensBounds.height() * scale)
        shader.setFloatUniform("radius", corner * scale)
        val base = GlassShader.baseRefraction(lensBounds.width(), lensBounds.height()) * scale
        shader.setFloatUniform("refraction", base * style.refraction)
        shader.setFloatUniform("edge", (base * style.depth).coerceAtLeast(1f))
        return RenderEffect.createRuntimeShaderEffect(shader, "image")
    }

    fun stop() {
        snapshot?.discardDisplayList()
        snapshot = null
        releaseBaked()
        progress = 0f
    }

    private fun releaseBaked() {
        baked?.recycle()
        baked = null
    }

    private fun bake(node: RenderNode, width: Int, height: Int): Bitmap? = runCatching {
        if (width <= 0 || height <= 0) return@runCatching null
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer()
        try {
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val frame = reader.acquireNextImage() ?: return@runCatching null
            try {
                frame.hardwareBuffer?.use { buffer ->
                    Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                }
            } finally {
                frame.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }.getOrNull()

    fun drawOutline(canvas: Canvas, clipPath: Path?, backgroundBounds: Rect, cornerRadius: Float) {
        if (progress <= 0f || liquid) return
        val clip = resolveClip(clipPath, backgroundBounds, cornerRadius) ?: return
        GlassShader.drawOutline(canvas, clip, style, 1f, host.resources.displayMetrics.density)
    }

    private fun resolveClip(clipPath: Path?, backgroundBounds: Rect, cornerRadius: Float): Path? {
        val clip = if (clipPath != null && !clipPath.isEmpty) {
            clipPath.computeBounds(pathBounds, true)
            clipPath
        } else {
            if (backgroundBounds.isEmpty) return null
            pathBounds.set(backgroundBounds)
            roundPath.reset()
            roundPath.addRoundRect(pathBounds, cornerRadius, cornerRadius, Path.Direction.CW)
            roundPath
        }
        return clip.takeUnless { pathBounds.isEmpty }
    }

    fun draw(canvas: Canvas, clipPath: Path?, backgroundBounds: Rect, cornerRadius: Float) {
        val radius = maxRadius * progress
        if (progress <= 0f || !canvas.isHardwareAccelerated) return

        val clip = resolveClip(clipPath, backgroundBounds, cornerRadius) ?: return

        val corner = if (clip === clipPath) {
            WallpaperGlass.pathCorner(clip, pathBounds)
        } else {
            min(cornerRadius, min(pathBounds.width(), pathBounds.height()) / 2f)
        }
        val dim = scrimColor()?.takeIf { !home } ?: 0
        if (liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            WallpaperGlass.drawLiquidShadow(canvas, clip, host.resources.displayMetrics.density, progress, strength = style.shadow)
        }
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
            home = home,
            style = panelStyle
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
                val scaledRadius = radius * frostScale
                val blur = if (scaledRadius >= 0.5f) RenderEffect.createBlurEffect(scaledRadius, scaledRadius, Shader.TileMode.CLAMP) else null
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
            uniform float edge;

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
                float band = clamp(1.0 + d / max(edge, 1.0), 0.0, 1.0);
                float bend = band * band * band;
                return image.eval(coord - normal * refraction * bend);
            }
        """
        private const val NODE_NAME = "pleFolderFrost"
        private const val CONTENT_NAME = "pleFolderFrostContent"
        private const val SCALED_NAME = "pleFolderFrostScaled"
        private const val FROST_SCALE = 0.25f
    }
}
