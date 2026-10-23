package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_TINT
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.data.glass.GlassShader
import com.drdisagree.pixellauncherenhanced.data.glass.GlassStyle
import com.drdisagree.pixellauncherenhanced.data.glass.GlassTarget
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.DragPreviewGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.WallpaperGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.ceil

class IconGlass(context: Context) : ModPack(context) {

    private var enabled = false
    private var folderGlass = false
    private var liquid = false
    private var blurRadius = 0
    private var tintOpacity = DEFAULT_TINT
    private var iconStyle = GlassStyle.DEFAULT
    private val currentHost = ThreadLocal<View?>()
    private val currentDrawable = ThreadLocal<Drawable?>()
    private val glassNodes = WeakHashMap<Drawable, GlassNode>()
    private val hostNodes = WeakHashMap<View, MutableList<WeakReference<GlassNode>>>()
    private val localRect = RectF()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        colorFilter = ColorMatrixColorFilter(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 255f, 0f)
        )
    }
    private var scrimView: WeakReference<View>? = null
    private var scrimFrame = -1L
    private var scrimRoot: WeakReference<View>? = null
    private var scrimColor: Int? = null
    private var drawerBlur: Float? = null
    private val folderClass: Class<*>? by lazy {
        findClass("com.android.launcher3.folder.Folder", suppressError = true)
    }
    private val allAppsContainerClass: Class<*>? by lazy {
        findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", suppressError = true)
    }
    @Volatile
    private var sheetColor = 0
    private var drawerOpacity = -1
    private val alphaMasks = WeakHashMap<Bitmap, Bitmap>()
    private val shapeCache = WeakHashMap<Any, ShapeParts>()
    private val delegateCache = WeakHashMap<Any, DelegateParts>()
    private val ancestry = WeakHashMap<View, Ancestry>()
    private val fullBounds = Rect()
    private val canvasNodeField: Field? by lazy { XposedHelpers.findFieldIfExists(RecordingCanvas::class.java, "mNode") }
    private val viewNodeField: Field? by lazy { XposedHelpers.findFieldIfExists(View::class.java, "mRenderNode") }
    private val cornerCache = WeakHashMap<Path, Float>()
    private val boundsCache = WeakHashMap<Path, RectF>()
    private val visibleAreaFactor: Float by lazy {
        findClass("com.android.launcher3.icons.IconNormalizer", suppressError = true)
            .getStaticFieldSilently("ICON_VISIBLE_AREA_FACTOR") as? Float ?: DEFAULT_VISIBLE_AREA
    }

    init {
        instance = WeakReference(this)
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            val background = PanelBackground.supported(getString(ICON_BACKGROUND, PanelBackground.OFF))
            enabled = background != PanelBackground.OFF
            folderGlass = PanelBackground.supported(getString(FOLDER_BACKGROUND, PanelBackground.OFF)) != PanelBackground.OFF
            liquid = background == PanelBackground.LIQUID
            blurRadius = getSliderInt(ICON_BACKGROUND_BLUR, DEFAULT_BLUR)
            tintOpacity = getSliderInt(ICON_BACKGROUND_TINT, DEFAULT_TINT)
            iconStyle = GlassTarget.ICONS.readStyle(this::getSliderInt, this::getInt)
            drawerOpacity = getSliderInt(APP_DRAWER_BACKGROUND_OPACITY, -1) * 255 / 100
        }

        when (key.firstOrNull()) {
            ICON_BACKGROUND -> reloadLauncher(mContext)
            ICON_BACKGROUND_BLUR,
            ICON_BACKGROUND_TINT,
            in GlassTarget.ICONS.styleKeys -> WallpaperGlass.onOffsetsChanged()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        DragPreviewGlass.install()

        val allAppsState = findClass("com.android.launcher3.LauncherState", suppressError = true)
            .getStaticFieldSilently("ALL_APPS")
        val stateListenerClass = findClass(
            $$"com.android.launcher3.statemanager.StateManager$StateListener",
            suppressError = true
        )
        if (allAppsState != null && stateListenerClass != null) {
            findClass("com.android.launcher3.Launcher")
                .hookMethod("onCreate")
                .suppressError()
                .runAfter { param ->
                    val launcher = param.thisObject as? Activity ?: return@runAfter
                    val listener = Proxy.newProxyInstance(
                        stateListenerClass.classLoader,
                        arrayOf(stateListenerClass),
                        InvocationHandler { proxy, method, args ->
                            when (method.name) {
                                "onStateTransitionStart" -> if (args?.getOrNull(0) == allAppsState) captureHome(launcher)
                                "onStateTransitionComplete" -> if (args?.getOrNull(0) != allAppsState) WallpaperGlass.clearHome()
                                "equals" -> return@InvocationHandler proxy === args?.getOrNull(0)
                                "hashCode" -> return@InvocationHandler System.identityHashCode(proxy)
                            }
                            null
                        }
                    )
                    launcher.callMethodSilently("getStateManager")?.callMethodSilently("addStateListener", listener)
                }
        }

        findClass("com.android.launcher3.icons.FastBitmapDrawable")
            .hookMethod("draw")
            .suppressError()
            .runBefore { param ->
                if (!enabled) return@runBefore
                val drawable = param.thisObject as? Drawable
                currentHost.set(drawable?.hostView())
                currentDrawable.set(drawable)
            }
            .runAfter {
                currentHost.remove()
                currentDrawable.remove()
            }

        findClass("com.android.launcher3.dragndrop.DragView", suppressError = true)
            .hookMethod("draw")
            .suppressError()
            .runBefore { param ->
                if (!enabled) return@runBefore
                val dragView = param.thisObject as? View ?: return@runBefore
                val background = dragView.getFieldSilently("mBgSpringDrawable") as? Drawable ?: return@runBefore
                if (background is FloatingGlassBackground) return@runBefore
                val content = dragView.getFieldSilently("mContent") as? ImageView ?: return@runBefore
                if (!DragPreviewGlass.hasGlass(content.drawable)) return@runBefore
                dragView.setFieldSilently(
                    "mBgSpringDrawable",
                    FloatingGlassBackground(background, dragView, "mScaledMaskPath", maskByInner = true)
                        .apply { bounds = background.bounds }
                )
            }

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
                val shapeParts = shapeParts(shape) ?: return@runBefore
                val parts = delegateParts(param.thisObject) ?: return@runBefore
                val path = shapeParts.path
                val pathSize = shapeParts.size
                val monoIcon = parts.monoIcon
                val monoPaint = parts.monoPaint
                if (pathSize <= 0f || bounds.isEmpty) return@runBefore
                val monoBounds = parts.bounds ?: fullBounds.apply { set(0, 0, pathSize.toInt(), pathSize.toInt()) }

                (shape.getFieldSilently("shadowLayer") as? Bitmap)?.let {
                    canvas.drawBitmap(it, null, bounds, paint)
                }

                val save = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / pathSize, bounds.height() / pathSize)
                canvas.scale(visibleAreaFactor, visibleAreaFactor, pathSize / 2f, pathSize / 2f)
                if (liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    WallpaperGlass.drawLiquidShadow(
                        canvas, path, host.resources.displayMetrics.density, paint.alpha / 255f,
                        bounds.width() / pathSize * visibleAreaFactor, iconStyle.shadow
                    )
                }
                canvas.clipPath(path)

                val shapeBounds = pathBounds(path)
                localRect.set(shapeBounds)
                if (DragPreviewGlass.isCapturing(canvas)) {
                    DragPreviewGlass.capture(
                        canvas, path, localRect, shapeCorner(path, shapeBounds),
                        host.glassBlurRadius(), paint.alpha / 255f, liquid, iconStyle
                    )
                    drawTint(canvas, paint)
                    drawOutline(canvas, path, paint, host, bounds.width() / pathSize * visibleAreaFactor)
                    canvas.drawBitmap(monoIcon, null, monoBounds, monoPaint)
                    canvas.restoreToCount(save)
                    param.result = null
                    return@runBefore
                }
                val drawable = currentDrawable.get()
                if (drawable != null && canvas.recordsFor(host)) {
                    val node = glassNode(drawable, host)
                    canvas.getMatrix(node.matrix)
                    node.rect.set(localRect)
                    node.corner = shapeCorner(path, shapeBounds)
                    node.alpha = paint.alpha / 255f
                    node.path = path
                    node.size = pathSize
                    node.attached = node.record()
                    if (!node.attached) {
                        canvas.restoreToCount(save)
                        return@runBefore
                    }
                    canvas.drawRenderNode(node.node)
                } else {
                    val glass = WallpaperGlass.canvasPaint(
                        host = host,
                        canvas = canvas,
                        localRect = localRect,
                        corner = shapeCorner(path, shapeBounds),
                        blurRadius = host.glassBlurRadius(),
                        alpha = paint.alpha / 255f,
                        liquid = liquid,
                        style = iconStyle,
                        shapePath = path,
                        home = host.samplesHome(),
                        frostedBackdrop = BlurPanels.dockBackdropRadius(host),
                        dimColor = BlurPanels.dockBackdropTint(host)
                    )
                    if (glass == null) {
                        canvas.restoreToCount(save)
                        return@runBefore
                    }
                    canvas.drawPaint(glass)
                    drawDrawerScrim(canvas, host)
                }
                drawTint(canvas, paint)
                drawOutline(canvas, path, paint, host, bounds.width() / pathSize * visibleAreaFactor)
                canvas.drawBitmap(monoIcon, null, monoBounds, monoPaint)
                canvas.restoreToCount(save)
                param.result = null
            }

        findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", suppressError = true)
            .hookMethod("drawOnScrimWithScaleAndBottomOffset", "drawOnScrimWithScale")
            .suppressError()
            .runAfter { param ->
                if (!enabled && !folderGlass) return@runAfter
                val paint = param.thisObject.getFieldSilently("mHeaderPaint") as? Paint ?: return@runAfter
                var color = paint.color
                if (drawerOpacity in 0..254 && param.thisObject.getFieldSilently("mScrimColor") !is Int) {
                    color = ColorUtils.setAlphaComponent(color, Color.alpha(color) * drawerOpacity / 255)
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

                val floating = view.parent as? View ?: return@runBefore
                val original = floating.getFieldSilently("mOriginalIcon") as? View ?: return@runBefore
                val icon = original.getFieldSilently("mIcon") ?: GestureMod.coverIcon(original)
                val folderItem = original.isFolderIcon() && !OpacityModifier.targetsWholeFolder(floating) &&
                        floating.getFieldSilently("mIconLoadResult")?.getFieldSilently("isThemed") == true
                if (!icon.isGlassIcon() && !folderItem) return@runBefore

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
                val host = drawable.hostView() ?: return@runBefore
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
                    liquid = liquid,
                    style = iconStyle,
                    home = host.samplesHome()
                ) ?: return@runBefore

                val mask = alphaMask(bgBitmap)
                canvas.drawBitmap(mask, null, bounds, glass)
                host.backdropColors().forEach {
                    scrimPaint.color = it
                    canvas.drawBitmap(mask, null, bounds, scrimPaint)
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

    @SuppressLint("DiscouragedApi")
    private fun captureHome(launcher: Activity) {
        if (!enabled && !folderGlass) return
        val root = launcher.window?.decorView ?: return
        val targets = listOf("workspace", "hotseat").mapNotNull { name ->
            root.resources.getIdentifier(name, "id", launcher.packageName)
                .takeIf { it != 0 }
                ?.let { root.findViewById<View>(it) }
        }
        val first = targets.firstOrNull() ?: return
        val radius = maxOf(blurRadius * root.resources.displayMetrics.density, first.drawerBlurRadius())
        WallpaperGlass.captureHome(targets, radius)
    }

    private fun Any?.isGlassIcon(): Boolean {
        if (this !is Drawable) return false
        if (javaClass.name.endsWith(".ThemedIconDrawable")) return true
        return getFieldSilently("delegate")?.javaClass?.name?.endsWith(".ThemedIconDelegate") == true
    }

    private inner class FloatingGlassBackground(
        val inner: Drawable,
        private val host: View,
        private val clipField: String = "mClipPath",
        private val maskByInner: Boolean = false
    ) : Drawable() {

        private val rect = RectF()
        private var visible: RectF? = null
        private val visibleFor = Rect()

        override fun draw(canvas: Canvas) {
            val clip = host.getFieldSilently(clipField)?.let {
                it as? Path ?: it.getFieldSilently("path") as? Path
            }
            if (clip != null && !clip.isEmpty) clip.computeBounds(rect, true) else rect.set(bounds)
            if (rect.isEmpty) {
                inner.draw(canvas)
                return
            }
            var corner = if (clip != null && !clip.isEmpty) WallpaperGlass.pathCorner(clip, rect) else 0f
            if (maskByInner) {
                val width = rect.width()
                visibleRect()?.let { if (rect.intersect(it)) corner *= rect.width() / width }
            }

            val drawerIcon = drawerSource()
            val glass = WallpaperGlass.canvasPaint(
                host = host,
                canvas = canvas,
                localRect = rect,
                corner = corner,
                blurRadius = drawerIcon?.glassBlurRadius() ?: host.glassBlurRadius(),
                alpha = inner.alpha / 255f,
                liquid = liquid,
                style = iconStyle,
                home = drawerIcon != null
            )
            if (glass == null) {
                inner.draw(canvas)
                return
            }

            if (maskByInner) {
                val layer = canvas.saveLayer(rect, null)
                canvas.drawPaint(glass)
                drawerIcon?.let { drawDrawerTint(canvas, it) }
                clip?.let { drawOutline(canvas, it) }
                val mask = canvas.saveLayer(rect, maskPaint)
                inner.draw(canvas)
                canvas.restoreToCount(mask)
                canvas.restoreToCount(layer)
            } else {
                canvas.drawPaint(glass)
                drawerIcon?.let { drawDrawerTint(canvas, it) }
            }
            val tintAlpha = tintOpacity.coerceIn(0, 100) * 255 / 100
            if (tintAlpha > 0) {
                val previous = inner.alpha
                inner.alpha = previous * tintAlpha / 255
                inner.draw(canvas)
                inner.alpha = previous
            }
            if (!maskByInner) clip?.let { drawOutline(canvas, it) }
        }

        private fun drawOutline(canvas: Canvas, clip: Path) {
            if (clip.isEmpty) return
            GlassShader.drawOutline(
                canvas, clip, iconStyle.takeUnless { liquid }, inner.alpha / 255f,
                host.resources.displayMetrics.density
            )
        }

        private fun drawerSource(): View? {
            val original = (host.parent as? View)?.getFieldSilently("mOriginalIcon") as? View ?: return null
            return original.takeIf { it.isInDrawer() }
        }

        private fun visibleRect(): RectF? {
            val area = inner.bounds
            if (area.isEmpty) return null
            if (visibleFor == area) return visible
            visibleFor.set(area)
            visible = null

            val bitmap = runCatching {
                Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            val drawn = runCatching {
                val canvas = Canvas(bitmap)
                canvas.translate(-area.left.toFloat(), -area.top.toFloat())
                inner.draw(canvas)
            }.isSuccess
            if (!drawn) {
                bitmap.recycle()
                return null
            }

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (pixels[y * bitmap.width + x] ushr 24 == 0) continue
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
            bitmap.recycle()
            if (right < left || bottom < top) return null

            visible = RectF(
                (area.left + left).toFloat(),
                (area.top + top).toFloat(),
                (area.left + right + 1).toFloat(),
                (area.top + bottom + 1).toFloat()
            )
            return visible
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
        host.openFolder()?.let { folder ->
            OpacityModifier.folderFrost(folder)?.takeIf { it.progress > 0f }?.backdropColors()?.forEach {
                drawScrim(canvas, it)
            }
            folder.panelColor()?.let { drawScrim(canvas, it) }
            return
        }
        if (host.isInDrawer()) drawDrawerTint(canvas, host)
        host.folderIconSource()
            ?.let { OpacityModifier.folderIconTint(it, requireVisible = it === host) }
            ?.let { drawScrim(canvas, it) }
    }

    private fun drawDrawerTint(canvas: Canvas, icon: View) {
        drawerScrimColor(icon)?.let { drawScrim(canvas, it) }
        sheetColor.takeIf { Color.alpha(it) != 0 }?.let { drawScrim(canvas, it) }
    }

    private fun drawScrim(canvas: Canvas, color: Int) {
        scrimPaint.color = color
        canvas.drawPaint(scrimPaint)
    }

    private fun View.backdropColors(): List<Int> {
        openFolder()?.let { folder ->
            val frost = OpacityModifier.folderFrost(folder)?.takeIf { it.progress > 0f }
            return frost?.backdropColors().orEmpty() + listOfNotNull(folder.panelColor())
        }
        val drawer = if (isInDrawer()) {
            listOfNotNull(drawerScrimColor(this), sheetColor.takeIf { Color.alpha(it) != 0 })
        } else emptyList()
        val folder = folderIconSource()?.let { OpacityModifier.folderIconTint(it, requireVisible = it === this) }
        return drawer + listOfNotNull(folder)
    }

    private inner class GlassNode(host: View) {
        private val host = WeakReference(host)
        val node = RenderNode(GLASS_NODE)
        val matrix = Matrix()
        val rect = RectF()
        var corner = 0f
        var alpha = 1f
        var path: Path? = null
        var size = 0f
        var attached = false

        fun refresh(): Boolean {
            val view = host.get() ?: return false
            if (!attached) return false
            if (WallpaperGlass.isDepthBlurred && !view.samplesHome()) return true
            if (OpacityModifier.drawerFrostActive() && view.isInDrawer()) return true
            if (OpacityModifier.drawerFrostAnimating() && view.openFolder() != null) return true
            return record()
        }

        fun record(): Boolean {
            val view = host.get() ?: return false
            val glass = WallpaperGlass.matrixPaint(
                host = view,
                matrix = matrix,
                localRect = rect,
                corner = corner,
                blurRadius = view.glassBlurRadius(),
                alpha = alpha,
                liquid = liquid,
                style = iconStyle,
                shapePath = path,
                home = view.samplesHome(),
                frostedBackdrop = BlurPanels.dockBackdropRadius(view),
                dimColor = BlurPanels.dockBackdropTint(view)
            ) ?: return false
            val extent = ceil(size).toInt().coerceAtLeast(1)
            node.setPosition(0, 0, extent, extent)
            val canvas = node.beginRecording(extent, extent)
            canvas.drawPaint(glass)
            drawDrawerScrim(canvas, view)
            node.endRecording()
            return true
        }
    }

    private fun glassNode(drawable: Drawable, host: View): GlassNode {
        glassNodes[drawable]?.let { return it }
        val node = GlassNode(host)
        glassNodes[drawable] = node
        val nodes = hostNodes.getOrPut(host) {
            mutableListOf<WeakReference<GlassNode>>().also { list ->
                val hostRef = WeakReference(host)
                val ownGlass = host.isFolderIcon()
                WallpaperGlass.setRefresher(host) {
                    refreshNodes(list) && !(ownGlass && folderGlass && hostRef.get()?.drawsGlassNow() == true)
                }
            }
        }
        nodes.add(WeakReference(node))
        return node
    }

    private fun Canvas.recordsFor(view: View): Boolean {
        val canvasNode = canvasNodeField ?: return false
        val viewNode = viewNodeField ?: return false
        return runCatching { canvasNode.get(this) === viewNode.get(view) }.getOrDefault(false)
    }

    private fun View.drawsGlassNow(): Boolean = !WallpaperGlass.isDepthBlurred || isInDrawer()

    private fun refreshNodes(nodes: MutableList<WeakReference<GlassNode>>): Boolean {
        nodes.removeAll { it.get() == null }
        if (nodes.isEmpty()) return false
        return nodes.all { it.get()?.refresh() == true }
    }

    private class ShapeParts(val path: Path, val size: Float)

    private class DelegateParts(val monoIcon: Bitmap, val bounds: Rect?, val monoPaint: Paint)

    private class Ancestry(val parent: WeakReference<ViewParent>, val inDrawer: Boolean, val folder: WeakReference<View>?)

    private fun shapeParts(shape: Any): ShapeParts? {
        shapeCache[shape]?.let { return it }
        val path = shape.getFieldSilently("path") as? Path ?: return null
        val size = (shape.getFieldSilently("pathSize") as? Int)?.toFloat() ?: return null
        return ShapeParts(path, size).also { shapeCache[shape] = it }
    }

    private fun delegateParts(delegate: Any): DelegateParts? {
        delegateCache[delegate]?.let { return it }
        val monoIcon = delegate.getFieldSilently("monoIcon") as? Bitmap ?: return null
        val monoPaint = delegate.getFieldSilently("monoPaint") as? Paint ?: return null
        val bounds = delegate.getFieldSilently("shapeBounds") as? Rect
        return DelegateParts(monoIcon, bounds, monoPaint).also { delegateCache[delegate] = it }
    }

    private fun View.ancestry(): Ancestry? {
        val parent = parent ?: return null
        ancestry[this]?.takeIf { it.parent.get() === parent }?.let { return it }

        val containerClass = allAppsContainerClass
        val folderClass = folderClass
        var inDrawer = false
        var folder: View? = null
        var current: ViewParent? = parent
        while (current is View) {
            if (!inDrawer && (containerClass?.isInstance(current) == true || current.javaClass.name.contains("AllApps"))) {
                inDrawer = true
            }
            if (folder == null && folderClass?.isInstance(current) == true) folder = current
            current = current.parent
        }
        return Ancestry(WeakReference(parent), inDrawer, folder?.let { WeakReference(it) })
            .also { ancestry[this] = it }
    }

    private fun View.isFolderIcon(): Boolean = javaClass.name.endsWith(".FolderIcon")

    private fun View.folderIconSource(): View? {
        if (isFolderIcon()) return this
        if (!javaClass.name.endsWith(".ClipIconView")) return null
        return ((parent as? View)?.getFieldSilently("mOriginalIcon") as? View)?.takeIf { it.isFolderIcon() }
    }

    private fun View.openFolder(): View? = ancestry()?.folder?.get()

    private fun View.panelColor(): Int? {
        val background = getFieldSilently("mBackground") as? GradientDrawable ?: return null
        val color = background.color?.defaultColor ?: return null
        val alpha = Color.alpha(color) * background.alpha / 255
        return if (alpha == 0) null else ColorUtils.setAlphaComponent(color, alpha)
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

    private fun drawerTint(host: View, anywhere: Boolean = false): Int? {
        if (!anywhere && !host.isInDrawer()) return null
        val sheet = sheetColor.takeIf { Color.alpha(it) != 0 }
        val scrim = drawerScrimColor(host, anywhere)
        return when {
            scrim != null && sheet != null -> ColorUtils.compositeColors(sheet, scrim)
            else -> sheet ?: scrim
        }
    }

    private fun drawerScrimColor(host: View, anywhere: Boolean = false): Int? {
        if (!anywhere && !host.isInDrawer()) return null
        val root = host.rootView
        val frame = AnimationUtils.currentAnimationTimeMillis()
        if (frame == scrimFrame && scrimRoot?.get() === root) return scrimColor

        val scrim = scrimView?.get()?.takeIf { it.isAttachedToWindow && it.rootView === root }
            ?: root.findScrimView()?.also { scrimView = WeakReference(it) }
        val color = scrim?.takeIf { it.visibility == View.VISIBLE }
            ?.let { it.getFieldSilently("mBackgroundColor") as? Int }
        val alpha = color?.let { (Color.alpha(it) * scrim.alpha).toInt().coerceIn(0, 255) } ?: 0
        scrimFrame = frame
        scrimRoot = WeakReference(root)
        scrimColor = if (color == null || alpha == 0) null else ColorUtils.setAlphaComponent(color, alpha)
        return scrimColor
    }

    private fun View.glassBlurRadius(): Float {
        val own = blurRadius * resources.displayMetrics.density
        openFolder()?.let { folder ->
            val frost = OpacityModifier.folderFrost(folder)?.takeIf { it.progress > 0f } ?: return own
            return maxOf(own, frost.glassRadius)
        }
        folderIconSource()?.let { folder ->
            OpacityModifier.folderIconBlur(folder, requireVisible = folder === this)?.let { return maxOf(own, it) }
        }
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

    private fun View.isInDrawer(): Boolean = ancestry()?.inDrawer == true

    private fun View.samplesHome(): Boolean =
        isInDrawer() || openFolder()?.let { OpacityModifier.folderFrost(it)?.home } == true

    private fun View.findScrimView(): View? {
        if (javaClass.name.endsWith(".ScrimView")) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) {
            getChildAt(i).findScrimView()?.let { return it }
        }
        return null
    }

    private fun drawOutline(canvas: Canvas, path: Path, paint: Paint, host: View, scale: Float) {
        if (scale <= 0f) return
        GlassShader.drawOutline(
            canvas, path, iconStyle.takeUnless { liquid }, paint.alpha / 255f,
            host.resources.displayMetrics.density / scale
        )
    }

    private fun drawTint(canvas: Canvas, paint: Paint) {
        val tintAlpha = tintOpacity.coerceIn(0, 100) * 255 / 100
        if (tintAlpha <= 0) return
        tintPaint.set(paint)
        tintPaint.color = ColorUtils.setAlphaComponent(paint.color, Color.alpha(paint.color) * tintAlpha / 255)
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
        private var instance: WeakReference<IconGlass>? = null

        fun drawerGlassBlur(host: View, own: Float): Float {
            val glass = instance?.get() ?: return own
            return with(glass) { if (host.isInDrawer()) maxOf(own, host.drawerBlurRadius()) else own }
        }

        fun drawerGlassTint(host: View): Int? = instance?.get()?.drawerTint(host)

        fun drawerBackdropTint(host: View): Int? = instance?.get()?.drawerTint(host, anywhere = true)

        fun drawerBlur(host: View, own: Float): Float {
            val glass = instance?.get() ?: return own
            return maxOf(own, with(glass) { host.drawerBlurRadius() })
        }

        private const val MAX_CALLBACK_DEPTH = 6
        private const val GLASS_NODE = "pleIconGlass"
        private const val DEFAULT_VISIBLE_AREA = 0.92f
        private const val LEGACY_CORNER_RATIO = 0.25f
        private const val DEFAULT_BLUR = 5
        private const val DEFAULT_TINT = 40
        private const val DEFAULT_DRAWER_BLUR = 30
    }
}
