package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_TINT
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.WallpaperGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap

class IconGlass(context: Context) : ModPack(context) {

    private var enabled = false
    private var liquid = false
    private var blurRadius = 0
    private var tintOpacity = DEFAULT_TINT
    private val currentHost = ThreadLocal<View?>()
    private val localRect = RectF()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scrimView: WeakReference<View>? = null
    private var drawerBlur: Float? = null
    private val allAppsContainerClass: Class<*>? by lazy {
        findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", suppressError = true)
    }
    @Volatile
    private var sheetColor = 0
    private var drawerOpacity = -1
    private val alphaMasks = WeakHashMap<Bitmap, Bitmap>()
    private val cornerCache = WeakHashMap<Path, Float>()
    private val boundsCache = WeakHashMap<Path, RectF>()
    private val visibleAreaFactor: Float by lazy {
        findClass("com.android.launcher3.icons.IconNormalizer", suppressError = true)
            .getStaticFieldSilently("ICON_VISIBLE_AREA_FACTOR") as? Float ?: DEFAULT_VISIBLE_AREA
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            val background = PanelBackground.supported(getString(ICON_BACKGROUND, PanelBackground.OFF))
            enabled = background != PanelBackground.OFF
            liquid = background == PanelBackground.LIQUID
            blurRadius = getSliderInt(ICON_BACKGROUND_BLUR, DEFAULT_BLUR)
            tintOpacity = getSliderInt(ICON_BACKGROUND_TINT, DEFAULT_TINT)
            drawerOpacity = getSliderInt(APP_DRAWER_BACKGROUND_OPACITY, -1) * 255 / 100
        }

        when (key.firstOrNull()) {
            ICON_BACKGROUND -> reloadLauncher(mContext)
            ICON_BACKGROUND_BLUR,
            ICON_BACKGROUND_TINT -> WallpaperGlass.onOffsetsChanged()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        if (!PanelBackground.isBlurSupported) return

        findClass("com.android.launcher3.icons.FastBitmapDrawable")
            .hookMethod("draw")
            .suppressError()
            .runBefore { param ->
                if (!enabled) return@runBefore
                currentHost.set((param.thisObject as? Drawable)?.callback as? View)
            }
            .runAfter { currentHost.remove() }

        findClass("com.android.launcher3.icons.mono.ThemedIconDelegate", suppressError = true)
            .hookMethod("drawContent")
            .suppressError()
            .runBefore { param ->
                if (!enabled) return@runBefore
                val host = currentHost.get() ?: return@runBefore
                val shape = param.args.getOrNull(1) ?: return@runBefore
                val canvas = param.args.getOrNull(2) as? Canvas ?: return@runBefore
                val bounds = param.args.getOrNull(3) as? Rect ?: return@runBefore
                val paint = param.args.getOrNull(4) as? Paint ?: return@runBefore
                val delegate = param.thisObject

                val path = shape.getFieldSilently("path") as? Path ?: return@runBefore
                val pathSize = (shape.getFieldSilently("pathSize") as? Int)?.toFloat() ?: return@runBefore
                val monoIcon = delegate.getFieldSilently("monoIcon") as? Bitmap ?: return@runBefore
                val monoBounds = delegate.getFieldSilently("shapeBounds") as? Rect
                val monoPaint = delegate.getFieldSilently("monoPaint") as? Paint ?: return@runBefore
                if (pathSize <= 0f || bounds.isEmpty) return@runBefore

                (shape.getFieldSilently("shadowLayer") as? Bitmap)?.let {
                    canvas.drawBitmap(it, null, bounds, paint)
                }

                val save = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / pathSize, bounds.height() / pathSize)
                canvas.scale(visibleAreaFactor, visibleAreaFactor, pathSize / 2f, pathSize / 2f)
                canvas.clipPath(path)

                val shapeBounds = pathBounds(path)
                localRect.set(shapeBounds)
                val glass = WallpaperGlass.canvasPaint(
                    host = host,
                    canvas = canvas,
                    localRect = localRect,
                    corner = shapeCorner(path, shapeBounds),
                    blurRadius = host.glassBlurRadius(),
                    alpha = paint.alpha / 255f,
                    liquid = liquid,
                    shapePath = path
                )
                if (glass == null) {
                    canvas.restoreToCount(save)
                    return@runBefore
                }

                canvas.drawPaint(glass)
                drawDrawerScrim(canvas, host)
                drawTint(canvas, paint)
                canvas.drawBitmap(monoIcon, null, monoBounds ?: Rect(0, 0, pathSize.toInt(), pathSize.toInt()), monoPaint)
                canvas.restoreToCount(save)
                param.result = null
            }

        findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", suppressError = true)
            .hookMethod("drawOnScrimWithScaleAndBottomOffset", "drawOnScrimWithScale")
            .suppressError()
            .runAfter { param ->
                if (!enabled) return@runAfter
                val paint = param.thisObject.getFieldSilently("mHeaderPaint") as? Paint ?: return@runAfter
                var color = paint.color
                if (drawerOpacity in 0..254 && param.thisObject.getFieldSilently("mScrimColor") !is Int) {
                    color = ColorUtils.setAlphaComponent(color, android.graphics.Color.alpha(color) * drawerOpacity / 255)
                }
                if (sheetColor != color) {
                    sheetColor = color
                    WallpaperGlass.onOffsetsChanged()
                }
            }

        findClass("com.android.launcher3.views.ClipIconView", suppressError = true)
            .hookMethod("draw")
            .suppressError()
            .runBefore { param ->
                if (!enabled) return@runBefore
                val view = param.thisObject as? View ?: return@runBefore
                val background = view.getFieldSilently("mBackground") as? Drawable ?: return@runBefore
                if (background is FloatingGlassBackground) return@runBefore

                val original = (view.parent as? View)?.getFieldSilently("mOriginalIcon") as? View
                    ?: return@runBefore
                if (!original.getFieldSilently("mIcon").isGlassIcon()) return@runBefore

                view.setFieldSilently(
                    "mBackground",
                    FloatingGlassBackground(background, view).apply { bounds = background.bounds }
                )
            }
            .runAfter { param ->
                val glass = param.thisObject.getFieldSilently("mBackground") as? FloatingGlassBackground
                    ?: return@runAfter
                param.thisObject.setFieldSilently("mBackground", glass.inner)
            }

        findClass("com.android.launcher3.icons.ThemedIconDrawable", suppressError = true)
            .hookMethod("drawInternal")
            .suppressError()
            .runBefore { param ->
                if (!enabled) return@runBefore
                val drawable = param.thisObject as? Drawable ?: return@runBefore
                val host = drawable.callback as? View ?: return@runBefore
                val canvas = param.args.getOrNull(0) as? Canvas ?: return@runBefore
                val bounds = param.args.getOrNull(1) as? Rect ?: return@runBefore

                val bgBitmap = drawable.getFieldSilently("mBgBitmap") as? Bitmap ?: return@runBefore
                val bgPaint = drawable.getFieldSilently("mBgPaint") as? Paint ?: return@runBefore
                val monoIcon = drawable.getFieldSilently("mMonoIcon") as? Bitmap ?: return@runBefore
                val monoPaint = drawable.getFieldSilently("mMonoPaint") as? Paint ?: return@runBefore
                if (bounds.isEmpty) return@runBefore

                localRect.set(bounds)
                val glass = WallpaperGlass.canvasPaint(
                    host = host,
                    canvas = canvas,
                    localRect = localRect,
                    corner = bounds.width() * LEGACY_CORNER_RATIO,
                    blurRadius = host.glassBlurRadius(),
                    alpha = drawable.alpha / 255f,
                    liquid = liquid
                ) ?: return@runBefore

                val mask = alphaMask(bgBitmap)
                canvas.drawBitmap(mask, null, bounds, glass)
                if (host.isInDrawer()) {
                    listOfNotNull(drawerScrimColor(host), sheetColor.takeIf { android.graphics.Color.alpha(it) != 0 })
                        .forEach {
                            scrimPaint.color = it
                            canvas.drawBitmap(mask, null, bounds, scrimPaint)
                        }
                }
                val tintAlpha = tintOpacity.coerceIn(0, 100) * 255 / 100
                if (tintAlpha > 0) {
                    val previous = bgPaint.alpha
                    bgPaint.alpha = previous * tintAlpha / 255
                    canvas.drawBitmap(bgBitmap, null, bounds, bgPaint)
                    bgPaint.alpha = previous
                }
                canvas.drawBitmap(monoIcon, null, bounds, monoPaint)
                param.result = null
            }
    }

    private fun Any?.isGlassIcon(): Boolean {
        if (this !is Drawable) return false
        if (javaClass.name.endsWith(".ThemedIconDrawable")) return true
        return getFieldSilently("delegate")?.javaClass?.name?.endsWith(".ThemedIconDelegate") == true
    }

    private inner class FloatingGlassBackground(
        val inner: Drawable,
        private val host: View
    ) : Drawable() {

        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val clip = host.getFieldSilently("mClipPath")?.let {
                it as? Path ?: it.getFieldSilently("path") as? Path
            }
            if (clip != null && !clip.isEmpty) clip.computeBounds(rect, true) else rect.set(bounds)
            if (rect.isEmpty) {
                inner.draw(canvas)
                return
            }

            val glass = WallpaperGlass.canvasPaint(
                host = host,
                canvas = canvas,
                localRect = rect,
                corner = if (clip != null && !clip.isEmpty) WallpaperGlass.pathCorner(clip, rect) else 0f,
                blurRadius = host.glassBlurRadius(),
                alpha = inner.alpha / 255f,
                liquid = liquid
            )
            if (glass == null) {
                inner.draw(canvas)
                return
            }

            canvas.drawPaint(glass)
            val tintAlpha = tintOpacity.coerceIn(0, 100) * 255 / 100
            if (tintAlpha > 0) {
                val previous = inner.alpha
                inner.alpha = previous * tintAlpha / 255
                inner.draw(canvas)
                inner.alpha = previous
            }
        }

        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
        }

        override fun getAlpha(): Int = inner.alpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            inner.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun onBoundsChange(bounds: Rect) {
            inner.bounds = bounds
        }
    }

    private fun drawDrawerScrim(canvas: Canvas, host: View) {
        if (!host.isInDrawer()) return
        drawerScrimColor(host)?.let {
            scrimPaint.color = it
            canvas.drawPaint(scrimPaint)
        }
        sheetColor.takeIf { android.graphics.Color.alpha(it) != 0 }?.let {
            scrimPaint.color = it
            canvas.drawPaint(scrimPaint)
        }
    }

    private fun drawerScrimColor(host: View): Int? {
        if (!host.isInDrawer()) return null
        val scrim = scrimView?.get()?.takeIf { it.isAttachedToWindow && it.rootView === host.rootView }
            ?: host.rootView.findScrimView()?.also { scrimView = WeakReference(it) }
            ?: return null
        if (scrim.visibility != View.VISIBLE) return null

        val color = scrim.getFieldSilently("mBackgroundColor") as? Int ?: return null
        val alpha = (android.graphics.Color.alpha(color) * scrim.alpha).toInt().coerceIn(0, 255)
        return if (alpha == 0) null else ColorUtils.setAlphaComponent(color, alpha)
    }

    private fun View.glassBlurRadius(): Float {
        val own = blurRadius * resources.displayMetrics.density
        return if (isInDrawer()) maxOf(own, drawerBlurRadius()) else own
    }

    @SuppressLint("DiscouragedApi")
    private fun View.drawerBlurRadius(): Float {
        drawerBlur?.let { return it }
        val res = resources
        val radius = res.getIdentifier("max_depth_blur_radius_enhanced", "dimen", context.packageName)
            .takeIf { it != 0 }
            ?.let { runCatching { res.getDimension(it) }.getOrNull() }
            ?: res.getIdentifier("max_depth_blur_radius", "integer", context.packageName)
                .takeIf { it != 0 }
                ?.let { runCatching { res.getInteger(it).toFloat() }.getOrNull() }
            ?: (DEFAULT_DRAWER_BLUR * res.displayMetrics.density)
        drawerBlur = radius
        return radius
    }

    private fun View.isInDrawer(): Boolean {
        val containerClass = allAppsContainerClass
        var current = parent
        while (current is View) {
            if (containerClass?.isInstance(current) == true) return true
            if (current.javaClass.name.contains("AllApps")) return true
            current = current.parent
        }
        return false
    }

    private fun View.findScrimView(): View? {
        if (javaClass.name.endsWith(".ScrimView")) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) {
            getChildAt(i).findScrimView()?.let { return it }
        }
        return null
    }

    private fun drawTint(canvas: Canvas, paint: Paint) {
        val tintAlpha = tintOpacity.coerceIn(0, 100) * 255 / 100
        if (tintAlpha <= 0) return
        tintPaint.set(paint)
        tintPaint.color = ColorUtils.setAlphaComponent(paint.color, android.graphics.Color.alpha(paint.color) * tintAlpha / 255)
        canvas.drawPaint(tintPaint)
    }

    private fun pathBounds(path: Path): RectF {
        return boundsCache.getOrPut(path) { RectF().also { path.computeBounds(it, true) } }
    }

    private fun shapeCorner(path: Path, bounds: RectF): Float {
        return cornerCache.getOrPut(path) { WallpaperGlass.pathCorner(path, bounds) }
    }

    private fun alphaMask(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ALPHA_8) return bitmap
        return alphaMasks.getOrPut(bitmap) { bitmap.extractAlpha() }
    }

    companion object {
        private const val DEFAULT_VISIBLE_AREA = 0.92f
        private const val LEGACY_CORNER_RATIO = 0.25f
        private const val DEFAULT_BLUR = 12
        private const val DEFAULT_TINT = 30
        private const val DEFAULT_DRAWER_BLUR = 30
    }
}
