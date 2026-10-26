package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import com.drdisagree.pixellauncherenhanced.data.glass.GlassShader
import com.drdisagree.pixellauncherenhanced.data.glass.GlassStyle
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object WallpaperGlass {

    private const val SAMPLE_WIDTH = 160
    private const val DEFAULT_MAX_ZOOM_SCALE = 1.1f
    private const val HOME_SCALE = 0.25f
    private const val HOME_NODE = "pleHomeBackdrop"
    private const val WALLPAPER_COPY = "ple_wallpaper"
    private const val SHARP_WIDTH = 720
    private const val SQRT_HALF = 0.70710677f
    private const val MASK_BLUR = 0.3f
    private const val MASK_RAMP = 1.7f
    private const val SHADOW_BLUR_DP = 4f
    private const val SHADOW_SIZE_RATIO = 0.08f
    private const val SHADOW_DX_RATIO = 0.25f
    private const val SHADOW_DY_RATIO = 0.375f
    private const val SHADOW_ALPHA = 32
    private const val MASK_PADDING = 8
    private const val MASK_CACHE_SIZE = 24
    private const val BLUR_SIGMA_SCALE = 0.57735f
    private const val MASK_SIZE_STEP = 8
    private const val SETTLE_DELAY_MS = 90L
    private const val SHADOW_CACHE_SIZE = 32
    private const val SHADOW_SCALE_STEPS = 4f
    private const val VOLATILE_SHADOW_CHANGES = 3
    private const val BLUR_CACHE_SIZE = 10
    private const val LIQUID_BLUR_RATIO = 3f
    private const val DEPTH_LEVELS = 3
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val shadowBounds = RectF()
    private val shadowCache = LinkedHashMap<Any, Bitmap>(SHADOW_CACHE_SIZE, 0.75f, true)
    private val shadowDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
    private val shadowMatrix = Matrix()
    private val shadowValues = FloatArray(9)
    private val shadowGenerations = WeakHashMap<Path, IntArray>()
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
    private val maskCache = LinkedHashMap<Any, MaskEntry>(MASK_CACHE_SIZE, 0.75f, true)
    private val emptyMaskShader by lazy {
        BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private var liquidShader: RuntimeShader? = null

    @Volatile
    private var smallBase: Bitmap? = null

    @Volatile
    private var sharpBase: Bitmap? = null
    private val blurCache = LinkedHashMap<Pair<Int, Int>, Bitmap>(BLUR_CACHE_SIZE, 0.75f, true)
    private val pendingBlurs = HashSet<Pair<Int, Int>>()
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
    private val settleRunners = WeakHashMap<View, Runnable>()
    private val settledRoots: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    var performanceMode = false

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
        val settled = settledRoots.remove(root)
        val dirty = dirtyRoots.remove(root)
        if (performanceMode && dirty && !settled) settleLater(root)
        val all = settled || (dirty && !performanceMode)
        var refreshed = false
        var deferred = false
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
                if (performanceMode && !all) {
                    deferred = true
                } else if (refresh(view)) {
                    refreshed = true
                }
            }
        }
        trackBuffer.clear()
        if (deferred) settleLater(root)
        if (refreshed) root.invalidate()
        return true
    }

    private fun settleLater(root: View) {
        val settle = settleRunners.getOrPut(root) {
            val rootRef = WeakReference(root)
            Runnable {
                val target = rootRef.get() ?: return@Runnable
                settledRoots.add(target)
                target.invalidate()
            }
        }
        root.removeCallbacks(settle)
        root.postDelayed(settle, SETTLE_DELAY_MS)
    }

    var generation = 0
        private set

    private fun invalidateTracked() {
        generation++
        for (root in rootListeners.keys) {
            if (root == null || !dirtyRoots.add(root)) continue
            root.invalidate()
        }
    }

    private var cellLayoutClass: Class<*>? = null
    private var cellBackgroundField: Field? = null

    private fun View.cellScrim(): Int {
        var current: View? = this
        while (current != null) {
            val type = current.javaClass
            if (type === cellLayoutClass || (cellLayoutClass == null && type.name.endsWith(".CellLayout"))) {
                if (cellLayoutClass == null) {
                    cellLayoutClass = type
                    cellBackgroundField = generateSequence<Class<*>>(type) { it.superclass }
                        .firstNotNullOfOrNull { runCatching { it.getDeclaredField("mBackground") }.getOrNull() }
                        ?.apply { isAccessible = true }
                }
                val background = cellBackgroundField?.get(current) as? Drawable ?: return 0
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

    @get:SuppressLint("DiscouragedApi")
    private val maxZoomScale by lazy {
        val system = Resources.getSystem()
        system.getIdentifier("config_wallpaperMaxScale", "dimen", "android").takeIf { it != 0 }
            ?.let { runCatching { system.getFloat(it) }.getOrNull() }
            ?.takeIf { it >= 1f }
            ?: DEFAULT_MAX_ZOOM_SCALE
    }

    private fun wallpaperBounds(bitmapWidth: Int, bitmapHeight: Int, screenWidth: Int, screenHeight: Int, out: RectF) {
        val scale = max(screenWidth.toFloat() / bitmapWidth, screenHeight.toFloat() / bitmapHeight)
        val drawnWidth = bitmapWidth * scale
        val drawnHeight = bitmapHeight * scale
        val left = -(drawnWidth - screenWidth) * offsetX.coerceIn(0f, 1f)
        val top = -(drawnHeight - screenHeight) / 2f
        val zoomScale = maxZoomScale + (1f - maxZoomScale) * zoom.coerceIn(0f, 1f)
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        out.set(
            centerX + (left - centerX) * zoomScale,
            centerY + (top - centerY) * zoomScale,
            centerX + (left + drawnWidth - centerX) * zoomScale,
            centerY + (top + drawnHeight - centerY) * zoomScale
        )
    }

    fun captureHome(targets: List<View>, blurRadius: Float) {
        val root = targets.firstOrNull()?.rootView ?: return
        checkWallpaper(root.context)
        if (liveWallpaper) return
        val base = sharpBase ?: smallBase ?: return
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return

        val screenWidth = root.resources.displayMetrics.widthPixels
        val wallpaperRect = RectF().also { wallpaperBounds(base.width, base.height, screenWidth, height, it) }

        val bitmapWidth = max(1, (width * HOME_SCALE).roundToInt())
        val bitmapHeight = max(1, (height * HOME_SCALE).roundToInt())
        val node = RenderNode(HOME_NODE).apply {
            setPosition(0, 0, bitmapWidth, bitmapHeight)
            val canvas = beginRecording(bitmapWidth, bitmapHeight)
            canvas.scale(HOME_SCALE, HOME_SCALE)
            canvas.drawBitmap(base, null, wallpaperRect, Paint(Paint.FILTER_BITMAP_FLAG))
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
            val blurred = runCatching { GlassShader.boxBlur(rendered, radius) }.onFailure { log(this, it) }.getOrNull()
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
        "blurSamples=${blurCache.keys.joinToString { "${it.first}/${it.second}" }}",
        "trackedViews=${listeners.size}",
        "offsetX=$offsetX",
        "zoom=$zoom",
        "copyOutput=$lastCopyOutput"
    ).joinToString("; ")

    fun invalidateWallpaper(context: Context) {
        smallBase = null
        sharpBase = null
        blurCache.clear()
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
                blurCache.clear()
                invalidateTracked()
            }
        }
    }

    private fun frostedBitmap(radius: Float, screenWidth: Int, screenHeight: Int): Bitmap? {
        val base = sharpBase ?: return null
        val coverWidth = base.width * max(screenWidth.toFloat() / base.width, screenHeight.toFloat() / base.height)
        val (width, sampleRadius) = GlassShader.frostedSample(radius, coverWidth, base.width)
        return blurredBitmap(width, sampleRadius)
    }

    fun liquidBlur(radius: Float): Float = radius / LIQUID_BLUR_RATIO

    private fun clearBitmap(radius: Float, screenWidth: Int): Bitmap? =
        blurredBitmap(SHARP_WIDTH, (liquidBlur(radius) * SHARP_WIDTH / screenWidth).roundToInt())

    private fun blurredBitmap(width: Int, radius: Int): Bitmap? {
        val base = sharpBase ?: return null
        if (radius <= 0 && width >= base.width) return base
        val key = width to radius
        blurCache[key]?.let { return it }

        if (pendingBlurs.add(key)) {
            executor.execute {
                val blurred = runCatching { GlassShader.sampleBlur(base, min(width, base.width), radius) }
                    .onFailure { log(this, it) }.getOrNull()
                mainHandler.post {
                    pendingBlurs.remove(key)
                    if (blurred != null && base === sharpBase) {
                        blurCache[key] = blurred
                        while (blurCache.size > BLUR_CACHE_SIZE) blurCache.remove(blurCache.keys.first())
                        invalidateTracked()
                    }
                }
            }
        }
        val target = GlassShader.sampleSigma(width, radius)
        return blurCache.entries.minByOrNull { abs(GlassShader.sampleSigma(it.key.first, it.key.second) - target) }?.value ?: base
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
        home: Boolean = false,
        frostedBackdrop: Float = 0f,
        style: GlassStyle = GlassStyle.DEFAULT,
        cornerRadii: FloatArray? = null
    ): Boolean {
        track(host)
        checkWallpaper(host.context)
        if (!isAvailable()) return false

        val screenWidth = host.resources.displayMetrics.widthPixels
        val screenHeight = host.rootView.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        val radius = max(blurRadius, depthBlur)
        val useLiquid = liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val homeLayer = homeBitmap?.takeIf { home }
        val bitmap = homeLayer ?: when {
            useLiquid && frostedBackdrop > 0f ->
                frostedBitmap(maxOf(frostedBackdrop, depthBlur, liquidBlur(blurRadius)), screenWidth, screenHeight)

            useLiquid -> clearBitmap(radius, screenWidth)
            else -> frostedBitmap(max(radius, frostedBackdrop), screenWidth, screenHeight)
        } ?: return false

        val dim = backdropDim(host, dimColor)

        host.getLocationInWindow(location)
        val hostScale = host.cumulativeScale()

        if (cornerRadii != null) cornerRadii.copyInto(radii) else GlassShader.cornerRadii(corner, bottomCorner, radii)
        clipPath.reset()
        clipPath.addRoundRect(rect, radii, Path.Direction.CW)

        source.set(0, 0, bitmap.width, bitmap.height)
        if (homeLayer != null) {
            target.set(0f, 0f, homeWidth, homeHeight)
        } else {
            wallpaperBounds(bitmap.width, bitmap.height, screenWidth, screenHeight, target)
        }

        if (shapeDrawer != null) {
            if (useLiquid && prepareShadow(shadowBlur(host.resources.displayMetrics.density, rect), alpha, style.shadow)) shapeDrawer(shadowPaint)
            val paint = if (useLiquid) {
                liquidPaint(bitmap, rect, radii, hostScale, alpha, dim, style)
            } else {
                shapePaint.shader = wallpaperShader(bitmap, hostScale)
                shapePaint.colorFilter = GlassShader.backdropFilter(dim, style.saturation)
                shapePaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
                shapePaint
            }
            shapeDrawer(paint)
            return true
        }

        if (useLiquid) {
            drawLiquidShadow(
                canvas, clipPath, host.resources.displayMetrics.density, alpha, strength = style.shadow,
                shapeKey = listOf(rect.width(), rect.height(), radii.toList())
            )
            canvas.drawPath(clipPath, liquidPaint(bitmap, rect, radii, hostScale, alpha, dim, style))
            drawTint(canvas, tintColor, alpha)
            return true
        }

        canvas.save()
        canvas.clipPath(clipPath)

        canvas.scale(1f / hostScale, 1f / hostScale)
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())

        bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        bitmapPaint.colorFilter = GlassShader.backdropFilter(Color.TRANSPARENT, style.saturation)
        canvas.drawBitmap(bitmap, source, target, bitmapPaint)
        canvas.restore()

        drawTint(canvas, dim, alpha)
        drawTint(canvas, tintColor, alpha)
        GlassShader.drawOutline(
            canvas, rect, radii, style.takeUnless { liquid }, alpha, host.resources.displayMetrics.density
        )
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
        home: Boolean = false,
        frostedBackdrop: Float = 0f,
        dimColor: Int = 0,
        style: GlassStyle = GlassStyle.DEFAULT
    ): Paint? {
        canvas.getMatrix(canvasMatrix)
        return matrixPaint(
            host, canvasMatrix, localRect, corner, blurRadius, alpha, liquid,
            shapePath, maskKey, maskDrawer, home, frostedBackdrop, dimColor, style
        )
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
        home: Boolean = false,
        frostedBackdrop: Float = 0f,
        dimColor: Int = 0,
        style: GlassStyle = GlassStyle.DEFAULT
    ): Paint? {
        track(host)
        checkWallpaper(host.context)
        if (!isAvailable()) return null

        val screenWidth = host.resources.displayMetrics.widthPixels
        val screenHeight = host.rootView.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        val radius = max(blurRadius, depthBlur)
        val useLiquid = liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val homeLayer = homeBitmap?.takeIf { home }
        val bitmap = homeLayer ?: when {
            useLiquid && frostedBackdrop > 0f ->
                frostedBitmap(maxOf(frostedBackdrop, depthBlur, liquidBlur(blurRadius)), screenWidth, screenHeight)

            useLiquid -> clearBitmap(radius, screenWidth)
            else -> frostedBitmap(max(radius, frostedBackdrop), screenWidth, screenHeight)
        } ?: return null

        host.getLocationInWindow(location)
        if (!matrix.invert(canvasInverse)) return null

        shaderSource.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        if (homeLayer != null) {
            shaderTarget.set(0f, 0f, homeWidth, homeHeight)
        } else {
            wallpaperBounds(bitmap.width, bitmap.height, screenWidth, screenHeight, shaderTarget)
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
            val shader = iconLiquidShader ?: RuntimeShader(GlassShader.LIQUID).also { iconLiquidShader = it }
            shader.setInputShader("image", image)
            shader.setFloatUniform("origin", localRect.left, localRect.top)
            shader.setFloatUniform("size", localRect.width(), localRect.height())
            GlassShader.setRadii(shader, corner)
            GlassShader.applyStyle(shader, localRect.width(), localRect.height(), style)
            GlassShader.setDim(shader, backdropDim(host, dimColor))
            val edge = GlassShader.edgeWidth(localRect.width(), localRect.height(), style)
            val mask = shapePath?.let { path -> shapeMask(path, localRect, edge) { it.drawPath(path, maskPaint) } }
                ?: maskDrawer?.let { shapeMask(maskKey ?: it, localRect, edge, it) }
            if (mask != null) {
                shader.setInputShader("mask", mask.shader)
                shader.setFloatUniform("useMask", 1f)
                shader.setFloatUniform(
                    "maskOrigin",
                    localRect.left + mask.originX / mask.scale,
                    localRect.top + mask.originY / mask.scale
                )
                shader.setFloatUniform("maskScale", mask.scale)
                shader.setFloatUniform("maskRange", mask.range / mask.scale)
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
                colorFilter = GlassShader.backdropFilter(backdropDim(host, dimColor), style.saturation)
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

    private fun maskBlurRadius(edge: Float): Int = max(1, (edge * MASK_BLUR).roundToInt())

    private fun maskPadding(edge: Float): Int = MASK_PADDING + maskBlurRadius(edge) * 3

    private class MaskEntry(
        val shader: BitmapShader,
        val originX: Float,
        val originY: Float,
        val scale: Float,
        val range: Float
    )

    private fun shapeMask(source: Any, bounds: RectF, edge: Float, drawer: (Canvas) -> Unit): MaskEntry {
        val bucketWidth = max(MASK_SIZE_STEP, (bounds.width() / MASK_SIZE_STEP).roundToInt() * MASK_SIZE_STEP)
        val scale = bucketWidth / max(1f, bounds.width())
        val bucketHeight = max(1, (bounds.height() * scale).roundToInt())
        val blurRadius = maskBlurRadius(edge * scale)
        val key = listOf(source, bucketWidth, bucketHeight, blurRadius)
        maskCache[key]?.let { return it }

        val padding = maskPadding(edge * scale)
        val sharp = Bitmap.createBitmap(bucketWidth + padding * 2, bucketHeight + padding * 2, Bitmap.Config.ALPHA_8)
        Canvas(sharp).apply {
            translate(padding.toFloat(), padding.toFloat())
            scale(scale, scale)
            translate(-bounds.left, -bounds.top)
            drawer(this)
        }
        val sigma = sqrt(blurRadius * (blurRadius + 1f))
        val blurPaint = Paint().apply {
            maskFilter = BlurMaskFilter(max(0.5f, (sigma - 0.5f) / BLUR_SIGMA_SCALE), BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        val blurred = sharp.extractAlpha(blurPaint, offset)
        sharp.recycle()
        val entry = MaskEntry(
            BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR },
            offset[0] - padding.toFloat(),
            offset[1] - padding.toFloat(),
            scale,
            blurRadius * MASK_RAMP
        )
        if (maskCache.size >= MASK_CACHE_SIZE) maskCache.remove(maskCache.keys.first())
        maskCache[key] = entry
        return entry
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

    fun drawLiquidShadow(
        canvas: Canvas,
        path: Path,
        density: Float,
        alpha: Float = 1f,
        unitScale: Float = 1f,
        strength: Float = 1f,
        cached: Boolean = true,
        shapeKey: Any? = null
    ) {
        path.computeBounds(shadowBounds, true)
        if (shadowBounds.isEmpty) return
        val generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) path.generationId else 0
        if (!cached || (shapeKey == null && path.isVolatile(generation))) {
            if (!prepareShadow(shadowBlur(density / unitScale, shadowBounds), alpha, strength)) return
            canvas.save()
            canvas.clipOutPath(path)
            canvas.drawPath(path, shadowPaint)
            canvas.restore()
            return
        }
        val blur = shadowBlur(density / unitScale, shadowBounds)
        val shadowAlpha = (SHADOW_ALPHA * alpha.coerceIn(0f, 1f) * strength).roundToInt().coerceIn(0, 255)
        if (shadowAlpha <= 0 || blur <= 0f) return

        @Suppress("DEPRECATION")
        canvas.getMatrix(shadowMatrix)
        shadowMatrix.getValues(shadowValues)
        val deviceScale = hypot(shadowValues[Matrix.MSCALE_X], shadowValues[Matrix.MSKEW_Y])
        val scale = (ceil(deviceScale * SHADOW_SCALE_STEPS) / SHADOW_SCALE_STEPS).coerceIn(0.25f, 4f)
        val key = listOf(
            shapeKey ?: System.identityHashCode(path), if (shapeKey == null) generation else 0,
            shadowBounds.width(), shadowBounds.height(), (blur * 8f).roundToInt(), scale
        )
        val bitmap = shadowCache[key] ?: renderShadow(path, blur, scale).also {
            shadowCache[key] = it
            while (shadowCache.size > SHADOW_CACHE_SIZE) shadowCache.remove(shadowCache.keys.first())
        }
        val padding = shadowPadding(blur)
        shadowDrawPaint.alpha = shadowAlpha
        canvas.save()
        canvas.translate(shadowBounds.left - padding, shadowBounds.top - padding)
        canvas.scale(1f / scale, 1f / scale)
        canvas.drawBitmap(bitmap, 0f, 0f, shadowDrawPaint)
        canvas.restore()
    }

    private fun Path.isVolatile(generation: Int): Boolean {
        val state = shadowGenerations.getOrPut(this) { intArrayOf(generation, 0) }
        if (state[0] == generation) {
            state[1] = 0
        } else {
            state[0] = generation
            state[1]++
        }
        return state[1] >= VOLATILE_SHADOW_CHANGES
    }

    private fun shadowPadding(blur: Float): Float = ceil(blur * (2f + SHADOW_DY_RATIO) + 2f)

    private fun renderShadow(path: Path, blur: Float, scale: Float): Bitmap {
        val padding = shadowPadding(blur)
        val width = max(1, ceil((shadowBounds.width() + padding * 2) * scale).toInt())
        val height = max(1, ceil((shadowBounds.height() + padding * 2) * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(blur, blur * SHADOW_DX_RATIO, blur * SHADOW_DY_RATIO, Color.BLACK)
        }
        Canvas(bitmap).apply {
            scale(scale, scale)
            translate(padding - shadowBounds.left, padding - shadowBounds.top)
            drawPath(path, paint)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        }
        return bitmap
    }

    private fun shadowBlur(unit: Float, bounds: RectF): Float =
        max(SHADOW_BLUR_DP * unit, min(bounds.width(), bounds.height()) * SHADOW_SIZE_RATIO)

    private fun prepareShadow(blur: Float, alpha: Float, strength: Float): Boolean {
        val shadowAlpha = (SHADOW_ALPHA * alpha.coerceIn(0f, 1f) * strength).roundToInt().coerceIn(0, 255)
        if (shadowAlpha <= 0 || blur <= 0f) return false
        shadowPaint.setShadowLayer(
            blur,
            blur * SHADOW_DX_RATIO,
            blur * SHADOW_DY_RATIO,
            Color.argb(shadowAlpha, 0, 0, 0)
        )
        return true
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
        cornerRadii: FloatArray,
        hostScale: Float,
        alpha: Float,
        dimColor: Int,
        style: GlassStyle
    ): Paint {
        val image = wallpaperShader(bitmap, hostScale)

        val shader = liquidShader ?: RuntimeShader(GlassShader.LIQUID).also { liquidShader = it }
        shader.setInputShader("image", image)
        shader.setFloatUniform("origin", rect.left, rect.top)
        shader.setFloatUniform("size", rect.width(), rect.height())
        GlassShader.setRadii(shader, cornerRadii)
        GlassShader.applyStyle(shader, rect.width(), rect.height(), style)
        GlassShader.setDim(shader, dimColor)
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
}
