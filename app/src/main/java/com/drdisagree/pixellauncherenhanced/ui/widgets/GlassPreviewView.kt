package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.glass.GlassShader
import com.drdisagree.pixellauncherenhanced.data.glass.GlassStyle
import com.drdisagree.pixellauncherenhanced.data.glass.GlassTarget
import com.drdisagree.pixellauncherenhanced.utils.HomePreviewLoader
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceIds
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GlassPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var target: GlassTarget = GlassTarget.ICONS
        set(value) {
            field = value
            invalidate()
        }

    private class Element(val rect: RectF, val corner: Float, val index: Int = 0)

    private val density = resources.displayMetrics.density
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sharp: Bitmap? = null
    private val blurCache = LinkedHashMap<Pair<Int, Int>, Bitmap>(BLUR_CACHE_SIZE, 0.75f, true)
    private val pendingBlurs = HashSet<Pair<Int, Int>>()

    @Volatile
    private var latestBlur: Pair<Int, Int>? = null
    private var monoGlyphs: List<Drawable> = emptyList()
    private var appIcons: List<Drawable> = emptyList()
    private var requested = false
    private val launcherColors = HashMap<String, Int?>()
    private val liveValues = HashMap<String, Int>()
    private var revealAnimator: ValueAnimator? = null
    private var revealProgress = 0f
    var onLoaded: (() -> Unit)? = null

    private val wallpaperPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val path = Path()
    private val wallpaperRect = RectF()
    private val shaderMatrix = Matrix()
    private val bitmapRect = RectF()
    private var liquidShader: Any? = null
    private val emptyMask by lazy {
        BitmapShader(createBitmap(1, 1), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val night: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private val screenWidth: Int
        get() = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

    private val screenHeight: Int
        get() = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (requested || w <= 0) return
        requested = true
        HomePreviewLoader.wallpaper(context, screenWidth, screenHeight) { drawable ->
            executor.execute {
                val bitmap = drawable?.let { runCatching { renderWallpaper(it) }.getOrNull() }
                prepareBitmap(bitmap ?: fallbackWallpaper())
            }
        }
        executor.execute {
            val glyphs = loadGlyphs()
            mainHandler.post {
                monoGlyphs = glyphs.first
                appIcons = glyphs.second
                invalidate()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacksAndMessages(null)
        revealAnimator?.cancel()
    }

    private fun renderWallpaper(drawable: Drawable): Bitmap {
        val width = min(SHARP_WIDTH, screenWidth)
        val height = (width * screenHeight.toFloat() / screenWidth).roundToInt()
        val bitmap = createBitmap(width, height)
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = max(width.toFloat() / intrinsicWidth, height.toFloat() / intrinsicHeight)
        val drawnWidth = (intrinsicWidth * scale).roundToInt()
        val drawnHeight = (intrinsicHeight * scale).roundToInt()
        val left = (width - drawnWidth) / 2
        val top = (height - drawnHeight) / 2
        drawable.setBounds(left, top, left + drawnWidth, top + drawnHeight)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun prepareBitmap(bitmap: Bitmap) {
        mainHandler.post {
            sharp = bitmap
            blurCache.clear()
            onLoaded?.invoke()
            reveal()
        }
    }

    private fun reveal() {
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = REVEAL_DURATION
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun fallbackWallpaper(): Bitmap {
        val width = min(SHARP_WIDTH, screenWidth)
        val height = (width * screenHeight.toFloat() / screenWidth).roundToInt()
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val colors = FALLBACK_COLORS.map { ContextCompat.getColor(context, it) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        canvas.drawPaint(paint)
        FALLBACK_SPOTS.forEachIndexed { index, (x, y, radius) ->
            val color = colors[(index + 2) % colors.size]
            paint.shader = RadialGradient(
                width * x, height * y, width * radius,
                color, ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP
            )
            canvas.drawPaint(paint)
        }
        return bitmap
    }

    private fun loadGlyphs(): Pair<List<Drawable>, List<Drawable>> {
        val packageManager = context.packageManager
        val activities = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        }.getOrDefault(emptyList())
        val ordered = activities.sortedBy { info ->
            PREFERRED_PACKAGES.indexOf(info.activityInfo.packageName).takeIf { it >= 0 } ?: PREFERRED_PACKAGES.size
        }
        val icons = ordered.mapNotNull { runCatching { it.loadIcon(packageManager) }.getOrNull() }
        val mono = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            icons.mapNotNull { (it as? AdaptiveIconDrawable)?.monochrome }.take(GLYPH_COUNT)
        } else {
            emptyList()
        }
        return mono to icons.take(OPEN_FOLDER_COLUMNS * OPEN_FOLDER_ROWS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val viewportHeight = width * screenHeight / screenWidth
        val offset = (viewportHeight - height) / 2f
        wallpaperRect.set(0f, -offset, width, viewportHeight - offset)

        val wallpaper = sharp ?: return
        val fading = revealProgress < 1f
        if (fading) canvas.saveLayerAlpha(null, (revealProgress * 255).roundToInt())
        bitmapRect.set(0f, 0f, wallpaper.width.toFloat(), wallpaper.height.toFloat())
        canvas.drawBitmap(wallpaper, null, wallpaperRect, wallpaperPaint)

        val mode = PanelBackground.supported(RPrefs.getString(target.styleKey))
        val style = target.readStyle(this::slider, RPrefs::getInt)
        val blur = slider(target.blurKey, DEFAULT_BLUR) * density
        val tint = slider(target.tintKey, DEFAULT_TINT)

        elements(width, height.toFloat()).forEach { element ->
            drawElement(canvas, element, mode, style, blur, tint)
        }
        if (fading) canvas.restore()
    }

    private fun elements(width: Float, area: Float): List<Element> {
        val centerY = area / 2f
        return when (target) {
            GlassTarget.ICONS -> {
                val size = dp(56f)
                val gap = dp(28f)
                val start = (width - (size * GLYPH_COUNT + gap * (GLYPH_COUNT - 1))) / 2f
                (0 until GLYPH_COUNT).map { index ->
                    val left = start + index * (size + gap)
                    Element(RectF(left, centerY - size / 2f, left + size, centerY + size / 2f), size / 2f, index)
                }
            }

            GlassTarget.FOLDERS -> {
                val size = dp(54f)
                val padding = dp(OPEN_FOLDER_PADDING_DP)
                val cell = dp(OPEN_FOLDER_CELL_DP)
                val panelWidth = cell * OPEN_FOLDER_COLUMNS + padding * 2f
                val panelHeight = cell * OPEN_FOLDER_ROWS + padding + dp(OPEN_FOLDER_TITLE_DP)
                val gap = dp(32f)
                val left = (width - (size + gap + panelWidth)) / 2f
                val panelLeft = left + size + gap
                listOf(
                    Element(RectF(left, centerY - size / 2f, left + size, centerY + size / 2f), size / 2f, 0),
                    Element(
                        RectF(panelLeft, centerY - panelHeight / 2f, panelLeft + panelWidth, centerY + panelHeight / 2f),
                        dp(OPEN_FOLDER_CORNER_DP),
                        1
                    )
                )
            }

            GlassTarget.DOCK -> {
                val height = dp(96f)
                listOf(Element(RectF(dp(12f), centerY - height / 2f, width - dp(12f), centerY + height / 2f), dp(28f)))
            }

            GlassTarget.WIDGETS -> {
                val height = dp(132f)
                listOf(Element(RectF(dp(24f), centerY - height / 2f, width - dp(24f), centerY + height / 2f), dp(24f)))
            }

            GlassTarget.SEARCH_BAR, GlassTarget.DRAWER_SEARCH -> {
                val height = dp(56f)
                listOf(Element(RectF(dp(24f), centerY - height / 2f, width - dp(24f), centerY + height / 2f), height / 2f))
            }
        }
    }

    private fun drawElement(canvas: Canvas, element: Element, mode: String, style: GlassStyle, blur: Float, tint: Int) {
        val rect = element.rect
        path.reset()
        path.addRoundRect(rect, element.corner, element.corner, Path.Direction.CW)
        val surface = surfaceColor()

        when {
            mode == PanelBackground.OFF -> {
                solidPaint.color = surface
                canvas.drawPath(path, solidPaint)
            }

            mode == PanelBackground.LIQUID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                drawShadow(canvas, rect, style.shadow)
                val base = sharp ?: return
                val bitmap = blurred(base.width, (blur * base.width / width / 3f).roundToInt()) ?: return
                canvas.drawPath(path, liquidPaint(bitmap, element, style))
                drawTint(canvas, surface, tint)
            }

            else -> {
                val base = sharp ?: return
                val (sampleWidth, sampleRadius) = GlassShader.frostedSample(blur, width.toFloat(), base.width)
                val bitmap = blurred(sampleWidth, sampleRadius) ?: return
                glassPaint.shader = imageShader(bitmap)
                glassPaint.colorFilter = GlassShader.backdropFilter(Color.TRANSPARENT, style.saturation)
                canvas.drawPath(path, glassPaint)
                drawTint(canvas, surface, tint)
                GlassShader.drawOutline(canvas, rect, element.corner, element.corner, style, 1f, density)
            }
        }

        drawContent(canvas, element)
    }

    private fun liquidPaint(bitmap: Bitmap, element: Element, style: GlassStyle): Paint {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return glassPaint
        val rect = element.rect
        val shader = liquidShader as? RuntimeShader ?: RuntimeShader(GlassShader.LIQUID).also { liquidShader = it }
        shader.setInputShader("image", imageShader(bitmap))
        shader.setFloatUniform("origin", rect.left, rect.top)
        shader.setFloatUniform("size", rect.width(), rect.height())
        shader.setFloatUniform("radius", element.corner)
        shader.setFloatUniform("bottomRadius", element.corner)
        GlassShader.applyStyle(shader, rect.width(), rect.height(), style)
        GlassShader.setDim(shader, Color.TRANSPARENT)
        shader.setInputShader("mask", emptyMask)
        shader.setFloatUniform("useMask", 0f)
        shader.setFloatUniform("maskOrigin", 0f, 0f)
        shader.setFloatUniform("maskScale", 1f)
        shader.setFloatUniform("maskRange", 1f)
        glassPaint.shader = shader
        glassPaint.colorFilter = null
        return glassPaint
    }

    private fun imageShader(bitmap: Bitmap): BitmapShader {
        bitmapRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        shaderMatrix.setRectToRect(bitmapRect, wallpaperRect, Matrix.ScaleToFit.FILL)
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(shaderMatrix) }
    }

    private fun drawShadow(canvas: Canvas, rect: RectF, strength: Float) {
        val alpha = (SHADOW_ALPHA * strength).roundToInt().coerceIn(0, 255)
        if (alpha <= 0) return
        val blur = max(dp(SHADOW_BLUR_DP), min(rect.width(), rect.height()) * SHADOW_SIZE_RATIO)
        shadowPaint.setShadowLayer(blur, blur * SHADOW_DX_RATIO, blur * SHADOW_DY_RATIO, Color.argb(alpha, 0, 0, 0))
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, shadowPaint)
        canvas.restore()
    }

    private fun drawTint(canvas: Canvas, color: Int, tint: Int) {
        if (tint <= 0) return
        tintPaint.color = ColorUtils.setAlphaComponent(color, tint.coerceIn(0, 100) * 255 / 100)
        canvas.drawPath(path, tintPaint)
    }

    private fun drawContent(canvas: Canvas, element: Element) {
        val rect = element.rect
        val content = if (night) Color.WHITE else DARK_CONTENT
        when (target) {
            GlassTarget.ICONS -> drawGlyph(canvas, monoGlyphs.getOrNull(element.index), rect, content, GLYPH_INSET)

            GlassTarget.FOLDERS -> if (element.index == 1) drawOpenFolder(canvas, rect, content) else {
                val inner = rect.width() * FOLDER_CONTENT_RATIO
                val cell = inner / 2f
                val icon = cell * FOLDER_ICON_RATIO
                val originX = rect.centerX() - inner / 2f
                val originY = rect.centerY() - inner / 2f
                appIcons.take(FOLDER_PREVIEW_COUNT).forEachIndexed { index, drawable ->
                    val centerX = originX + (index % 2 + 0.5f) * cell
                    val centerY = originY + (index / 2 + 0.5f) * cell
                    drawable.setBounds(
                        (centerX - icon / 2f).roundToInt(),
                        (centerY - icon / 2f).roundToInt(),
                        (centerX + icon / 2f).roundToInt(),
                        (centerY + icon / 2f).roundToInt()
                    )
                    drawable.draw(canvas)
                }
            }

            GlassTarget.DOCK -> {
                val size = dp(52f)
                val slot = rect.width() / GLYPH_COUNT
                appIcons.take(GLYPH_COUNT).forEachIndexed { index, icon ->
                    val centerX = rect.left + slot * (index + 0.5f)
                    icon.setBounds(
                        (centerX - size / 2f).roundToInt(),
                        (rect.centerY() - size / 2f).roundToInt(),
                        (centerX + size / 2f).roundToInt(),
                        (rect.centerY() + size / 2f).roundToInt()
                    )
                    icon.draw(canvas)
                }
            }

            GlassTarget.WIDGETS -> {
                textPaint.color = content
                textPaint.textSize = dp(44f)
                canvas.drawText(CLOCK_TEXT, rect.left + dp(24f), rect.centerY() + dp(10f), textPaint)
                linePaint.color = ColorUtils.setAlphaComponent(content, LINE_ALPHA)
                canvas.drawRoundRect(
                    rect.left + dp(24f), rect.centerY() + dp(26f),
                    rect.left + dp(150f), rect.centerY() + dp(36f),
                    dp(5f), dp(5f), linePaint
                )
            }

            GlassTarget.SEARCH_BAR, GlassTarget.DRAWER_SEARCH -> {
                val icon = ContextCompat.getDrawable(context, R.drawable.ic_search)
                val size = dp(24f)
                val left = rect.left + dp(20f)
                drawGlyph(canvas, icon, RectF(left, rect.centerY() - size / 2f, left + size, rect.centerY() + size / 2f), content, 0f)
                linePaint.color = ColorUtils.setAlphaComponent(content, LINE_ALPHA)
                canvas.drawRoundRect(
                    left + size + dp(16f), rect.centerY() - dp(5f),
                    left + size + dp(140f), rect.centerY() + dp(5f),
                    dp(5f), dp(5f), linePaint
                )
            }
        }
    }

    private fun drawOpenFolder(canvas: Canvas, rect: RectF, content: Int) {
        val padding = dp(OPEN_FOLDER_PADDING_DP)
        val cell = dp(OPEN_FOLDER_CELL_DP)
        val icon = dp(OPEN_FOLDER_ICON_DP)
        appIcons.take(OPEN_FOLDER_COLUMNS * OPEN_FOLDER_ROWS).forEachIndexed { index, drawable ->
            val centerX = rect.left + padding + (index % OPEN_FOLDER_COLUMNS + 0.5f) * cell
            val centerY = rect.top + padding + (index / OPEN_FOLDER_COLUMNS + 0.5f) * cell
            drawable.setBounds(
                (centerX - icon / 2f).roundToInt(),
                (centerY - icon / 2f).roundToInt(),
                (centerX + icon / 2f).roundToInt(),
                (centerY + icon / 2f).roundToInt()
            )
            drawable.draw(canvas)
        }
        val titleY = rect.bottom - dp(OPEN_FOLDER_TITLE_DP) / 2f
        linePaint.color = ColorUtils.setAlphaComponent(content, LINE_ALPHA)
        canvas.drawRoundRect(
            rect.centerX() - dp(32f), titleY - dp(5f),
            rect.centerX() + dp(32f), titleY + dp(5f),
            dp(5f), dp(5f), linePaint
        )
    }

    private fun drawGlyph(canvas: Canvas, drawable: Drawable?, rect: RectF, color: Int, inset: Float) {
        val glyph = drawable?.constantState?.newDrawable(resources)?.mutate() ?: return
        val pad = rect.width() * inset
        glyph.setBounds(
            (rect.left + pad).roundToInt(),
            (rect.top + pad).roundToInt(),
            (rect.right - pad).roundToInt(),
            (rect.bottom - pad).roundToInt()
        )
        glyph.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        glyph.draw(canvas)
    }

    private fun surfaceColor(): Int = when (target) {
        GlassTarget.ICONS -> launcherColor("themed_icon_background_color")
            ?: ContextCompat.getColor(context, if (night) android.R.color.system_accent1_800 else android.R.color.system_accent1_100)

        GlassTarget.FOLDERS -> folderColor()
        GlassTarget.DOCK -> if (RPrefs.getString(DOCK_BLUR_TINT_COLOR) == TINT_WIDGET) widgetColor() else folderColor()
        else -> widgetColor()
    }

    private fun folderColor(): Int =
        launcherColor(if (night) "folder_background_dark" else "folder_background_light") ?: fallbackSurface()

    private fun widgetColor(): Int = runCatching {
        val name = if (night) "system_widget_background_dark" else "system_widget_background_light"
        context.getColor(ResourceIds.find(resources, name, "color", "android"))
    }.getOrElse { fallbackSurface() }

    private fun slider(key: String, default: Int): Int = liveValues[key] ?: RPrefs.getSliderInt(key, default)

    fun preview(key: String, value: Int) {
        liveValues[key] = value
        invalidate()
    }

    fun clearPreview(key: String?) {
        if (key == null) liveValues.clear() else liveValues.remove(key)
        invalidate()
    }

    private fun fallbackSurface(): Int = if (night) DARK_SURFACE else LIGHT_SURFACE

    private fun launcherColor(name: String): Int? {
        val key = "$name:$night"
        if (key in launcherColors) return launcherColors[key]
        val color = listOf(PIXEL_LAUNCHER_PACKAGE, LAUNCHER3_PACKAGE).firstNotNullOfOrNull { launcher ->
            runCatching {
                val resources = context.createPackageContext(launcher, 0).resources
                val id = ResourceIds.find(resources, name, "color", launcher).takeIf { it != 0 }
                id?.let { resources.getColor(it, context.theme) }
            }.getOrNull()
        }
        launcherColors[key] = color
        return color
    }

    private fun blurred(width: Int, radius: Int): Bitmap? {
        val base = sharp ?: return null
        if (radius <= 0 && width >= base.width) return base
        val key = width to radius
        blurCache[key]?.let { return it }

        latestBlur = key
        if (pendingBlurs.add(key)) {
            executor.execute {
                val result = if (key == latestBlur) {
                    runCatching { GlassShader.sampleBlur(base, min(width, base.width), radius) }.getOrNull()
                } else {
                    null
                }
                mainHandler.post {
                    pendingBlurs.remove(key)
                    if (result != null && base === sharp) {
                        blurCache[key] = result
                        while (blurCache.size > BLUR_CACHE_SIZE) blurCache.remove(blurCache.keys.first())
                        invalidate()
                    }
                }
            }
        }
        val target = GlassShader.sampleSigma(width, radius)
        return blurCache.entries.minByOrNull { abs(GlassShader.sampleSigma(it.key.first, it.key.second) - target) }?.value ?: base
    }

    private fun dp(value: Float) = value * density

    companion object {
        private const val REVEAL_DURATION = 250L
        private const val SHARP_WIDTH = 720
        private const val BLUR_CACHE_SIZE = 6
        private const val DEFAULT_BLUR = 5
        private const val DEFAULT_TINT = 40
        private const val GLYPH_COUNT = 4
        private const val FOLDER_PREVIEW_COUNT = 4
        private const val GLYPH_INSET = -0.25f
        private const val FOLDER_CONTENT_RATIO = 0.97f
        private const val FOLDER_ICON_RATIO = 0.86f
        private const val OPEN_FOLDER_COLUMNS = 3
        private const val OPEN_FOLDER_ROWS = 2
        private const val OPEN_FOLDER_CELL_DP = 56f
        private const val OPEN_FOLDER_ICON_DP = 44f
        private const val OPEN_FOLDER_PADDING_DP = 16f
        private const val OPEN_FOLDER_TITLE_DP = 36f
        private const val OPEN_FOLDER_CORNER_DP = 28f
        private const val SHADOW_BLUR_DP = 4f
        private const val SHADOW_SIZE_RATIO = 0.08f
        private const val SHADOW_DX_RATIO = 0.25f
        private const val SHADOW_DY_RATIO = 0.375f
        private const val SHADOW_ALPHA = 32
        private const val LINE_ALPHA = 90
        private const val DARK_SURFACE = 0xFF1F1F23.toInt()
        private const val LIGHT_SURFACE = 0xFFF1F0F4.toInt()
        private const val DARK_CONTENT = 0xFF1B1B1F.toInt()
        private const val CLOCK_TEXT = "12:36"
        private const val TINT_WIDGET = "widget"
        private val FALLBACK_COLORS = listOf(
            android.R.color.system_accent1_300,
            android.R.color.system_accent3_600,
            android.R.color.system_accent2_200,
            android.R.color.system_accent1_700,
            android.R.color.system_accent3_200
        )
        private val FALLBACK_SPOTS = listOf(
            Triple(0.2f, 0.42f, 0.55f),
            Triple(0.85f, 0.5f, 0.5f),
            Triple(0.5f, 0.6f, 0.35f)
        )
        private val PREFERRED_PACKAGES = listOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.android.chrome",
            "com.google.android.apps.photos",
            "com.google.android.gm",
            "com.google.android.apps.maps"
        )
    }
}
