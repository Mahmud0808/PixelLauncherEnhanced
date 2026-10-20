package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Region
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WallpaperGlass {

    private const val SAMPLE_WIDTH = 160
    private const val MAX_ZOOM_SCALE = 1.1f
    private const val HOME_SCALE = 0.25f
    private const val HOME_NODE = "pleHomeBackdrop"
    private const val WALLPAPER_COPY = "ple_wallpaper"
    private const val SHARP_WIDTH = 720
    const val LIQUID_REFRACTION = 0.18f
    private const val SQRT_HALF = 0.70710677f
    private const val MASK_BLUR = 0.3f
    private const val MASK_RAMP = 1.7f
    private const val MASK_PADDING = 8
    private const val MASK_CACHE_SIZE = 8
    private const val BLUR_CACHE_SIZE = 5
    private const val DEPTH_LEVELS = 3
    private const val LIQUID_SHADER = """
        uniform shader image;
        uniform float2 origin;
        uniform float2 size;
        uniform float radius;
        uniform float refraction;
        uniform shader mask;
        uniform float useMask;
        uniform float2 maskOrigin;
        uniform float maskScale;
        uniform float maskRange;
        uniform float4 dim;

        float roundedBox(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        float maskDistance(float2 coord) {
            float m = mask.eval((coord - maskOrigin) * maskScale).r;
            return (0.5 - m) * 2.0 * maskRange;
        }

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 p = coord - origin - halfSize;
            float2 e = float2(1.0, 0.0);
            float d;
            float2 normal;
            float edge = refraction;
            float strength = 1.0;

            if (useMask > 0.5) {
                d = maskDistance(coord);
                float2 m = float2(2.0, 0.0);
                normal = float2(
                    maskDistance(coord + m.xy) - maskDistance(coord - m.xy),
                    maskDistance(coord + m.yx) - maskDistance(coord - m.yx)
                );
                edge = min(refraction, maskRange * 0.9);
                strength = smoothstep(0.05, 0.4, length(normal));
            } else {
                d = roundedBox(p, halfSize, radius);
                normal = float2(
                    roundedBox(p + e.xy, halfSize, radius) - roundedBox(p - e.xy, halfSize, radius),
                    roundedBox(p + e.yx, halfSize, radius) - roundedBox(p - e.yx, halfSize, radius)
                );
            }
            normal = normal / max(length(normal), 0.0001);

            float band = clamp(1.0 + d / max(edge, 1.0), 0.0, 1.0);
            float bend = band * band * band * strength;
            float2 offset = -normal * refraction * bend;

            half4 color;
            color.r = image.eval(coord + offset * 1.06).r;
            color.g = image.eval(coord + offset).g;
            color.b = image.eval(coord + offset * 0.94).b;
            color.a = 1.0;
            color.rgb = mix(color.rgb, half3(dim.rgb), half(dim.a));

            float rim = smoothstep(-2.5, 0.0, d);
            float light = clamp(dot(normal, normalize(float2(-0.5, -0.85))), 0.0, 1.0);
            float shade = clamp(dot(normal, normalize(float2(0.5, 0.85))), 0.0, 1.0);
            color.rgb += half3(rim * (0.18 + 0.5 * light));
            color.rgb += half3(bend * 0.12 * light);
            color.rgb -= half3(bend * 0.06 * shade);
            color.rgb += half3(0.03);
            return color;
        }
    """

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val radii = FloatArray(8)
    private val source = Rect()
    private val target = RectF()
    private val location = IntArray(2)
    private val liquidMatrix = Matrix()
    private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val measureRegion = Region()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val canvasInverse = Matrix()
    private val canvasShaderMatrix = Matrix()
    private val canvasMatrix = Matrix()
    private val shaderSource = RectF()
    private val shaderTarget = RectF()
    private var iconLiquidShader: RuntimeShader? = null
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val maskCache = LinkedHashMap<Any, BitmapShader>()
    private val emptyMaskShader by lazy {
        BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private var liquidShader: RuntimeShader? = null

    @Volatile
    private var smallBase: Bitmap? = null

    @Volatile
    private var sharpBase: Bitmap? = null
    private val frostedCache = LinkedHashMap<Int, Bitmap>(BLUR_CACHE_SIZE, 0.75f, true)
    private val clearCache = LinkedHashMap<Int, Bitmap>(BLUR_CACHE_SIZE, 0.75f, true)
    private val pendingBlurs = HashSet<Pair<Boolean, Int>>()
    private var loading = false
    private var lastCopyOutput: String? = null
    private var lastDecode: String? = null
    private var lastCopyPath: String? = null
    private var liveWallpaper = false
    private var wallpaperChecked = false
    private val listeners: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val lastPositions = WeakHashMap<View, Long>()
    private val lastScales = WeakHashMap<View, Float>()
    private val lastScrims = WeakHashMap<View, Int>()
    private val rootListeners = WeakHashMap<View, MutableSet<View>>()
    private val refreshers = WeakHashMap<View, () -> Boolean>()
    private val trackLocation = IntArray(2)
    private val trackBuffer = ArrayList<View>()
    private val dirtyRoots: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    private fun track(host: View) {
        if (host in listeners || !host.isAttachedToWindow) return
        val root = host.rootView ?: return
        listeners.add(host)
        host.getLocationInWindow(trackLocation)
        lastPositions[host] = packPosition(trackLocation)
        lastScales[host] = host.cumulativeScale()
        lastScrims[host] = host.cellScrim()

        rootListeners.getOrPut(root) {
            root.viewTreeObserver.addOnPreDrawListener { checkMoved(root) }
            Collections.newSetFromMap(WeakHashMap())
        }.add(host)
    }

    fun setRefresher(host: View, refresher: () -> Boolean) {
        refreshers[host] = refresher
    }

    private fun refresh(view: View): Boolean {
        if (refreshers[view]?.invoke() == true) return true
        view.background?.invalidateSelf()
        view.invalidate()
        return false
    }

    private fun checkMoved(root: View): Boolean {
        val views = rootListeners[root] ?: return true
        val all = dirtyRoots.remove(root)
        var refreshed = false
        trackBuffer.clear()
        trackBuffer.addAll(views)
        for (view in trackBuffer) {
            if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE) continue
            view.getLocationInWindow(trackLocation)
            val position = packPosition(trackLocation)
            val scale = view.cumulativeScale()
            val scrim = view.cellScrim()
            if (all || lastPositions[view] != position || lastScales[view] != scale || lastScrims[view] != scrim) {
                lastPositions[view] = position
                lastScales[view] = scale
                lastScrims[view] = scrim
                if (refresh(view)) refreshed = true
            }
        }
        trackBuffer.clear()
        if (refreshed) root.invalidate()
        return true
    }

    private fun invalidateTracked() {
        for (root in rootListeners.keys) {
            if (root == null || !dirtyRoots.add(root)) continue
            root.invalidate()
        }
    }

    private fun View.cellScrim(): Int {
        var current: View? = this
        while (current != null) {
            if (current.javaClass.name.endsWith(".CellLayout")) {
                val background = current.getFieldSilently("mBackground") as? Drawable ?: return 0
                if (background.alpha == 0) return 0
                val color = (background.current as? GradientDrawable)?.color?.defaultColor ?: return 0
                return ColorUtils.setAlphaComponent(color, Color.alpha(color) * background.alpha / 255)
            }
            current = current.parent as? View
        }
        return 0
    }

    private fun backdropDim(host: View, dimColor: Int): Int {
        val scrim = host.cellScrim()
        if (Color.alpha(scrim) == 0) return dimColor
        if (Color.alpha(dimColor) == 0) return scrim
        return ColorUtils.compositeColors(dimColor, scrim)
    }

    private fun backdropFilter(color: Int): BlendModeColorFilter? {
        if (Color.alpha(color) == 0) return null
        return BlendModeColorFilter(color, BlendMode.SRC_OVER)
    }

    private fun packPosition(location: IntArray): Long {
        return (location[0].toLong() shl 32) or (location[1].toLong() and 0xFFFFFFFFL)
    }

    var offsetX = 0.5f
    var zoom = 0f
    private var depthBlur = 0f
    private var homeBitmap: Bitmap? = null
    private var homeWidth = 0f
    private var homeHeight = 0f
    private var homeToken = 0

    fun captureHome(targets: List<View>, blurRadius: Float) {
        val root = targets.firstOrNull()?.rootView ?: return
        checkWallpaper(root.context)
        if (liveWallpaper) return
        val base = sharpBase ?: smallBase ?: return
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return

        val screenWidth = root.resources.displayMetrics.widthPixels
        val scale = max(screenWidth.toFloat() / base.width, height.toFloat() / base.height) *
                (1f + (MAX_ZOOM_SCALE - 1f) * zoom.coerceIn(0f, 1f))
        val drawnWidth = base.width * scale
        val drawnHeight = base.height * scale
        val wallpaperLeft = -(drawnWidth - screenWidth) * offsetX.coerceIn(0f, 1f)
        val wallpaperTop = -(drawnHeight - height) / 2f

        val bitmapWidth = max(1, (width * HOME_SCALE).roundToInt())
        val bitmapHeight = max(1, (height * HOME_SCALE).roundToInt())
        val node = RenderNode(HOME_NODE).apply {
            setPosition(0, 0, bitmapWidth, bitmapHeight)
            val canvas = beginRecording(bitmapWidth, bitmapHeight)
            canvas.scale(HOME_SCALE, HOME_SCALE)
            canvas.drawBitmap(
                base,
                null,
                RectF(wallpaperLeft, wallpaperTop, wallpaperLeft + drawnWidth, wallpaperTop + drawnHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            val targetLocation = IntArray(2)
            targets.filter { it.isShown && it.alpha > 0f }.forEach { target ->
                target.getLocationInWindow(targetLocation)
                canvas.save()
                canvas.translate(targetLocation[0].toFloat(), targetLocation[1].toFloat())
                canvas.scale(target.scaleX, target.scaleY)
                canvas.translate(-target.scrollX.toFloat(), -target.scrollY.toFloat())
                target.draw(canvas)
                canvas.restore()
            }
            endRecording()
        }

        val token = ++homeToken
        val rendered = runCatching { renderNode(node, bitmapWidth, bitmapHeight) }
            .onFailure { log(this, it) }
            .getOrNull()
        node.discardDisplayList()
        rendered ?: return
        val radius = max(1, (blurRadius * HOME_SCALE).roundToInt())
        executor.execute {
            val blurred = runCatching { boxBlur(rendered, radius) }.onFailure { log(this, it) }.getOrNull()
            mainHandler.post {
                if (token != homeToken || blurred == null) return@post
                homeBitmap = blurred
                homeWidth = width.toFloat()
                homeHeight = height.toFloat()
                invalidateTracked()
            }
        }
    }

    private fun renderNode(node: RenderNode, width: Int, height: Int): Bitmap? {
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage()?.use { image ->
                image.hardwareBuffer?.use { buffer ->
                    return Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?.copy(Bitmap.Config.ARGB_8888, true)
                }
            }
            return null
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    fun clearHome() {
        homeToken++
        if (homeBitmap == null) return
        homeBitmap = null
        invalidateTracked()
    }

    fun setDepthBlur(radius: Float, maxRadius: Float) {
        val step = maxRadius / DEPTH_LEVELS
        val level = if (step > 0f) (radius / step).roundToInt() * step else radius
        if (level == depthBlur) return
        depthBlur = level
        onOffsetsChanged()
    }

    fun isAvailable(): Boolean = !liveWallpaper && smallBase != null

    val isDepthBlurred: Boolean
        get() = depthBlur > 0f

    fun debugState(): String = listOf(
        "available=${isAvailable()}",
        "checked=$wallpaperChecked",
        "liveWallpaper=$liveWallpaper",
        "loading=$loading",
        "copyPath=$lastCopyPath",
        "decode=$lastDecode",
        "frostedRadii=${frostedCache.keys.sorted()}",
        "clearRadii=${clearCache.keys.sorted()}",
        "trackedViews=${listeners.size}",
        "offsetX=$offsetX",
        "zoom=$zoom",
        "copyOutput=$lastCopyOutput"
    ).joinToString("; ")

    fun invalidateWallpaper(context: Context) {
        smallBase = null
        sharpBase = null
        frostedCache.clear()
        clearCache.clear()
        wallpaperChecked = false
        onOffsetsChanged()
    }

    fun onOffsetsChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onOffsetsChanged() }
            return
        }
        invalidateTracked()
    }

    private fun checkWallpaper(context: Context) {
        if (wallpaperChecked) return
        wallpaperChecked = true
        liveWallpaper = runCatching { WallpaperManager.getInstance(context).wallpaperInfo != null }
            .getOrDefault(false)
        if (!liveWallpaper) load(context)
    }

    private fun load(context: Context) {
        if (loading) return
        loading = true

        val copy = File(context.cacheDir, WALLPAPER_COPY)
        val userId = Process.myUserHandle().hashCode()
        val uid = Process.myUid()

        val path = copy.absolutePath
        val script = "rm -f $path; cp /data/system/users/$userId/wallpaper $path && chown $uid:$uid $path && " +
                "chmod 600 $path && chcon \"\$(stat -c %C ${copy.parent})\" $path"

        lastCopyPath = path
        enqueueProxyCommand { proxy ->
            lastCopyOutput = proxy.runCommand(
                "sh -c '$script' 2>&1; [ -f $path ] || nsenter -t 1 -m -- sh -c '$script' 2>&1; ls -lZ $path 2>&1"
            ).joinToString(" / ")
            decode(copy, context.applicationContext)
        }
    }

    private fun builtInWallpaper(context: Context): Bitmap? {
        val drawable = runCatching {
            WallpaperManager.getInstance(context).getBuiltInDrawable(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return null
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }
    }

    private fun decode(copy: File, context: Context) {
        executor.execute {
            val result = runCatching {
                val bitmap = BitmapFactory.decodeFile(copy.absolutePath)
                    ?: builtInWallpaper(context)
                    ?: return@runCatching null
                val aspect = bitmap.width.toFloat() / bitmap.height
                val small = Bitmap.createScaledBitmap(
                    bitmap, SAMPLE_WIDTH, max(1, (SAMPLE_WIDTH / aspect).roundToInt()), true
                ).copy(Bitmap.Config.ARGB_8888, false)
                val sharp = Bitmap.createScaledBitmap(
                    bitmap, SHARP_WIDTH, max(1, (SHARP_WIDTH / aspect).roundToInt()), true
                ).copy(Bitmap.Config.ARGB_8888, false)
                small to sharp
            }.onFailure { log(this, it) }.getOrNull()
            lastDecode = result?.let { "small=${it.first.width}x${it.first.height} sharp=${it.second.width}x${it.second.height}" }
                ?: "failed (copy exists=${copy.exists()} size=${copy.length()} readable=${copy.canRead()})"

            mainHandler.post {
                loading = false
                smallBase = result?.first
                sharpBase = result?.second
                frostedCache.clear()
                clearCache.clear()
                invalidateTracked()
            }
        }
    }

    private fun blurredBitmap(clear: Boolean, radius: Int): Bitmap? {
        val base = (if (clear) sharpBase else smallBase) ?: return null
        if (radius <= 0) return base
        val cache = if (clear) clearCache else frostedCache
        cache[radius]?.let { return it }

        val key = clear to radius
        if (pendingBlurs.add(key)) {
            executor.execute {
                val blurred = runCatching { boxBlur(base.copy(Bitmap.Config.ARGB_8888, true), radius) }
                    .onFailure { log(this, it) }.getOrNull()
                mainHandler.post {
                    pendingBlurs.remove(key)
                    if (blurred != null && base === (if (clear) sharpBase else smallBase)) {
                        cache[radius] = blurred
                        while (cache.size > BLUR_CACHE_SIZE) cache.remove(cache.keys.first())
                        invalidateTracked()
                    }
                }
            }
        }
        return cache.entries.minByOrNull { abs(it.key - radius) }?.value ?: base
    }

    fun draw(
        host: View,
        canvas: Canvas,
        rect: RectF,
        corner: Float,
        bottomCorner: Float,
        blurRadius: Float,
        tintColor: Int,
        alpha: Float = 1f,
        liquid: Boolean = false,
        shapeDrawer: ((Paint) -> Unit)? = null,
        dimColor: Int = 0,
        home: Boolean = false
    ): Boolean {
        track(host)
        checkWallpaper(host.context)
        if (!isAvailable()) return false

        val screenWidth = host.resources.displayMetrics.widthPixels
        val radius = max(blurRadius, depthBlur)
        val useLiquid = liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val homeLayer = homeBitmap?.takeIf { home }
        val bitmap = homeLayer ?: (if (useLiquid) {
            blurredBitmap(true, (radius * SHARP_WIDTH / screenWidth / 3f).roundToInt())
        } else {
            blurredBitmap(false, max(1, (radius * SAMPLE_WIDTH / screenWidth).roundToInt()))
        } ?: return false)

        val dim = backdropDim(host, dimColor)
        val rootView = host.rootView
        val screenHeight = rootView.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels

        val baseScale = max(
            screenWidth.toFloat() / bitmap.width,
            screenHeight.toFloat() / bitmap.height
        )
        val zoomScale = 1f + (MAX_ZOOM_SCALE - 1f) * zoom.coerceIn(0f, 1f)
        val scale = baseScale * zoomScale
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val wallpaperLeft = -(drawnWidth - screenWidth) * offsetX.coerceIn(0f, 1f)
        val wallpaperTop = -(drawnHeight - screenHeight) / 2f

        host.getLocationInWindow(location)
        val hostScale = host.cumulativeScale()

        radii.fill(corner, 0, 4)
        radii.fill(bottomCorner, 4, 8)
        clipPath.reset()
        clipPath.addRoundRect(rect, radii, Path.Direction.CW)

        source.set(0, 0, bitmap.width, bitmap.height)
        if (homeLayer != null) {
            target.set(0f, 0f, homeWidth, homeHeight)
        } else {
            target.set(wallpaperLeft, wallpaperTop, wallpaperLeft + drawnWidth, wallpaperTop + drawnHeight)
        }

        if (shapeDrawer != null) {
            val paint = if (useLiquid) {
                liquidPaint(bitmap, rect, corner, hostScale, alpha, dim)
            } else {
                shapePaint.shader = wallpaperShader(bitmap, hostScale)
                shapePaint.colorFilter = backdropFilter(dim)
                shapePaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
                shapePaint
            }
            shapeDrawer(paint)
            return true
        }

        if (useLiquid) {
            canvas.drawPath(clipPath, liquidPaint(bitmap, rect, corner, hostScale, alpha, dim))
            drawTint(canvas, tintColor, alpha)
            return true
        }

        canvas.save()
        canvas.clipPath(clipPath)

        canvas.scale(1f / hostScale, 1f / hostScale)
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())

        bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawBitmap(bitmap, source, target, bitmapPaint)
        canvas.restore()

        drawTint(canvas, dim, alpha)
        drawTint(canvas, tintColor, alpha)
        return true
    }

    @Suppress("DEPRECATION")
    fun canvasPaint(
        host: View,
        canvas: Canvas,
        localRect: RectF,
        corner: Float,
        blurRadius: Float,
        alpha: Float,
        liquid: Boolean,
        shapePath: Path? = null,
        maskKey: Any? = null,
        maskDrawer: ((Canvas) -> Unit)? = null,
        home: Boolean = false
    ): Paint? {
        canvas.getMatrix(canvasMatrix)
        return matrixPaint(host, canvasMatrix, localRect, corner, blurRadius, alpha, liquid, shapePath, maskKey, maskDrawer, home)
    }

    fun matrixPaint(
        host: View,
        matrix: Matrix,
        localRect: RectF,
        corner: Float,
        blurRadius: Float,
        alpha: Float,
        liquid: Boolean,
        shapePath: Path? = null,
        maskKey: Any? = null,
        maskDrawer: ((Canvas) -> Unit)? = null,
        home: Boolean = false
    ): Paint? {
        track(host)
        checkWallpaper(host.context)
        if (!isAvailable()) return null

        val screenWidth = host.resources.displayMetrics.widthPixels
        val radius = max(blurRadius, depthBlur)
        val useLiquid = liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val homeLayer = homeBitmap?.takeIf { home }
        val bitmap = homeLayer ?: (if (useLiquid) {
            blurredBitmap(true, (radius * SHARP_WIDTH / screenWidth / 3f).roundToInt())
        } else {
            blurredBitmap(false, max(1, (radius * SAMPLE_WIDTH / screenWidth).roundToInt()))
        } ?: return null)

        val screenHeight = host.rootView.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        val scale = max(screenWidth.toFloat() / bitmap.width, screenHeight.toFloat() / bitmap.height) *
                (1f + (MAX_ZOOM_SCALE - 1f) * zoom.coerceIn(0f, 1f))
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val wallpaperLeft = -(drawnWidth - screenWidth) * offsetX.coerceIn(0f, 1f)
        val wallpaperTop = -(drawnHeight - screenHeight) / 2f

        host.getLocationInWindow(location)
        if (!matrix.invert(canvasInverse)) return null

        shaderSource.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        if (homeLayer != null) {
            shaderTarget.set(0f, 0f, homeWidth, homeHeight)
        } else {
            shaderTarget.set(wallpaperLeft, wallpaperTop, wallpaperLeft + drawnWidth, wallpaperTop + drawnHeight)
        }
        canvasShaderMatrix.setRectToRect(shaderSource, shaderTarget, Matrix.ScaleToFit.FILL)
        canvasShaderMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
        val hostScale = host.cumulativeScale()
        canvasShaderMatrix.postScale(1f / hostScale, 1f / hostScale)
        canvasShaderMatrix.postConcat(canvasInverse)

        val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(canvasShaderMatrix)
        }

        val paint = if (useLiquid) {
            val shader = iconLiquidShader ?: RuntimeShader(LIQUID_SHADER).also { iconLiquidShader = it }
            shader.setInputShader("image", image)
            shader.setFloatUniform("origin", localRect.left, localRect.top)
            shader.setFloatUniform("size", localRect.width(), localRect.height())
            shader.setFloatUniform("radius", corner)
            val refraction = minOf(localRect.height(), localRect.width()) * LIQUID_REFRACTION
            shader.setFloatUniform("refraction", refraction)
            setDim(shader, host.cellScrim())
            val mask = shapePath?.let { path -> shapeMask(path, localRect, refraction) { it.drawPath(path, maskPaint) } }
                ?: maskDrawer?.let { shapeMask(maskKey ?: it, localRect, refraction, it) }
            if (mask != null) {
                val padding = maskPadding(refraction)
                shader.setInputShader("mask", mask)
                shader.setFloatUniform("useMask", 1f)
                shader.setFloatUniform("maskOrigin", localRect.left - padding, localRect.top - padding)
                shader.setFloatUniform("maskScale", 1f)
                shader.setFloatUniform("maskRange", maskBlurRadius(refraction) * MASK_RAMP)
            } else {
                disableMask(shader)
            }
            iconPaint.apply {
                this.shader = shader
                colorFilter = null
            }
        } else {
            iconPaint.apply {
                this.shader = image
                colorFilter = backdropFilter(host.cellScrim())
            }
        }
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return paint
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun disableMask(shader: RuntimeShader) {
        shader.setInputShader("mask", emptyMaskShader)
        shader.setFloatUniform("useMask", 0f)
        shader.setFloatUniform("maskOrigin", 0f, 0f)
        shader.setFloatUniform("maskScale", 1f)
        shader.setFloatUniform("maskRange", 1f)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setDim(shader: RuntimeShader, color: Int) {
        shader.setFloatUniform(
            "dim",
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
            Color.alpha(color) / 255f
        )
    }

    private fun maskBlurRadius(refraction: Float): Int = max(1, (refraction * MASK_BLUR).roundToInt())

    private fun maskPadding(refraction: Float): Int = MASK_PADDING + maskBlurRadius(refraction) * 3

    private fun shapeMask(source: Any, bounds: RectF, refraction: Float, drawer: (Canvas) -> Unit): BitmapShader {
        val key = Triple(source, bounds.width().roundToInt(), bounds.height().roundToInt())
        maskCache[key]?.let { return it }

        val padding = maskPadding(refraction)
        val width = bounds.width().roundToInt() + padding * 2
        val height = bounds.height().roundToInt() + padding * 2
        val bitmap = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            translate(padding - bounds.left, padding - bounds.top)
            drawer(this)
        }
        val blurred = BitmapShader(boxBlur(bitmap, maskBlurRadius(refraction)), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        if (maskCache.size >= MASK_CACHE_SIZE) maskCache.remove(maskCache.keys.first())
        maskCache[key] = blurred
        return blurred
    }

    fun pathCorner(path: Path, bounds: RectF): Float {
        if (bounds.isEmpty) return 0f
        val half = min(bounds.width(), bounds.height()) / 2f
        measureRegion.setPath(
            path,
            Region(
                bounds.left.toInt() - 1, bounds.top.toInt() - 1,
                bounds.right.toInt() + 1, bounds.bottom.toInt() + 1
            )
        )
        var inset = 0f
        while (inset < half) {
            if (measureRegion.contains((bounds.left + inset).toInt(), (bounds.top + inset).toInt())) break
            inset += 1f
        }
        return (inset / (1f - SQRT_HALF)).coerceIn(0f, half)
    }

    private fun drawTint(canvas: Canvas, tintColor: Int, alpha: Float) {
        if (tintColor ushr 24 == 0) return
        tintPaint.color = tintColor
        tintPaint.alpha = ((tintColor ushr 24) * alpha.coerceIn(0f, 1f)).roundToInt()
        canvas.drawPath(clipPath, tintPaint)
    }

    private fun wallpaperShader(bitmap: Bitmap, hostScale: Float): BitmapShader {
        shaderSource.set(source)
        liquidMatrix.setRectToRect(shaderSource, target, Matrix.ScaleToFit.FILL)
        liquidMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
        liquidMatrix.postScale(1f / hostScale, 1f / hostScale)

        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(liquidMatrix)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun liquidPaint(
        bitmap: Bitmap,
        rect: RectF,
        corner: Float,
        hostScale: Float,
        alpha: Float,
        dimColor: Int
    ): Paint {
        val image = wallpaperShader(bitmap, hostScale)

        val shader = liquidShader ?: RuntimeShader(LIQUID_SHADER).also { liquidShader = it }
        shader.setInputShader("image", image)
        shader.setFloatUniform("origin", rect.left, rect.top)
        shader.setFloatUniform("size", rect.width(), rect.height())
        shader.setFloatUniform("radius", corner)
        shader.setFloatUniform("refraction", minOf(rect.height(), rect.width()) * LIQUID_REFRACTION)
        setDim(shader, dimColor)
        disableMask(shader)

        liquidPaint.shader = shader
        liquidPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return liquidPaint
    }

    private fun View.cumulativeScale(): Float {
        var result = scaleX
        var current = parent
        while (current is View) {
            result *= current.scaleX
            current = current.parent
        }
        return result.takeIf { it > 0f } ?: 1f
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val buffer = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        repeat(3) {
            blurPass(pixels, buffer, width, height, radius, horizontal = true)
            blurPass(buffer, pixels, width, height, radius, horizontal = false)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun blurPass(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1

        for (line in 0 until lines) {
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val pixel = input[index(line, i.coerceIn(0, length - 1), width, horizontal)]
                r += pixel shr 16 and 0xFF
                g += pixel shr 8 and 0xFF
                b += pixel and 0xFF
            }
            for (i in 0 until length) {
                output[index(line, i, width, horizontal)] =
                    (0xFF shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val outPixel = input[index(line, (i - radius).coerceIn(0, length - 1), width, horizontal)]
                val inPixel = input[index(line, (i + radius + 1).coerceIn(0, length - 1), width, horizontal)]
                r += (inPixel shr 16 and 0xFF) - (outPixel shr 16 and 0xFF)
                g += (inPixel shr 8 and 0xFF) - (outPixel shr 8 and 0xFF)
                b += (inPixel and 0xFF) - (outPixel and 0xFF)
            }
        }
    }

    private fun index(line: Int, position: Int, width: Int, horizontal: Boolean): Int {
        return if (horizontal) line * width + position else position * width + line
    }
}
