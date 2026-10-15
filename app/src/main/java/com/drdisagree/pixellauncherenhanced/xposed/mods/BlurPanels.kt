package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_CORNER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_MARGIN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_STYLE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_CLEAR_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.BlurPanel
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.BlurPanelDrawable
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.WallpaperGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections
import java.util.WeakHashMap

class BlurPanels(context: Context) : ModPack(context) {

    private var dockBlurRadius = 0
    private var dockBlurTint = 0
    private var dockTintWidgetColor = false
    private var dockLiquid = false
    private var widgetLiquid = false
    private var dockBlurCorner = DEFAULT_DOCK_CORNER
    private var dockAttached = false
    private var dockBlurMargin = DEFAULT_DOCK_MARGIN
    private val windowLocation = IntArray(2)
    private var widgetBlurRadius = 0
    private var widgetBlurTint = 0
    private var widgetClearBackground = false
    private val hosts: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val rect = RectF()
    private var wallpaperIsLight: Boolean? = null
    private var wallpaperListenerRegistered = false
    private var wallpaperChecked = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            val dockBackground = PanelBackground.supported(getString(DOCK_BACKGROUND, BACKGROUND_OFF))
            val dockEnabled = dockBackground != BACKGROUND_OFF
            dockBlurRadius = if (dockEnabled) getSliderInt(DOCK_BLUR_RADIUS, 0) else 0
            dockBlurTint = if (dockEnabled) getSliderInt(DOCK_BLUR_TINT, 0) else 0
            dockLiquid = dockBackground == BACKGROUND_LIQUID
            dockTintWidgetColor = getString(DOCK_BLUR_TINT_COLOR, TINT_FOLDER) == TINT_WIDGET
            dockBlurCorner = getSliderInt(DOCK_BLUR_CORNER, DEFAULT_DOCK_CORNER)
            dockAttached = getString(DOCK_BLUR_STYLE, STYLE_FLOATING) == STYLE_ATTACHED
            dockBlurMargin = getSliderInt(DOCK_BLUR_MARGIN, DEFAULT_DOCK_MARGIN)
            val widgetBackground = PanelBackground.supported(getString(WIDGET_BACKGROUND, BACKGROUND_OFF))
            val widgetEnabled = widgetBackground != BACKGROUND_OFF
            widgetBlurRadius = if (widgetEnabled) getSliderInt(WIDGET_BLUR_RADIUS, 0) else 0
            widgetBlurTint = if (widgetEnabled) getSliderInt(WIDGET_BLUR_TINT, 0) else 0
            widgetLiquid = widgetBackground == BACKGROUND_LIQUID
            widgetClearBackground = widgetEnabled || getBoolean(WIDGET_CLEAR_BACKGROUND, false)
        }

        when (key.firstOrNull()) {
            DOCK_BLUR_RADIUS,
            DOCK_BLUR_TINT,
            DOCK_BLUR_TINT_COLOR,
            DOCK_BLUR_CORNER,
            DOCK_BLUR_STYLE,
            DOCK_BACKGROUND,
            DOCK_BLUR_MARGIN,
            WIDGET_BLUR_RADIUS,
            WIDGET_BLUR_TINT -> hosts.toList().forEach {
                it.post {
                    it.background?.invalidateSelf()
                    it.invalidate()
                }
            }

            WIDGET_BACKGROUND,
            WIDGET_CLEAR_BACKGROUND -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        findClass("com.android.launcher3.Launcher", suppressError = true)
            .hookMethod("onResume")
            .suppressError()
            .runAfter { param ->
                (param.thisObject as? Activity)?.window?.decorView?.apply {
                    post { BlurPanel.refreshAll() }
                    postDelayed({ BlurPanel.refreshAll() }, RESUME_REFRESH_DELAY)
                }
                registerWallpaperColorsListener()
            }

        findClass("com.android.launcher3.Workspace", suppressError = true)
            .hookMethod("onPageEndTransition")
            .suppressError()
            .runAfter {
                BlurPanel.refreshAllSoon()
            }

        findClass("android.view.View")
            .hookMethod("setLayerType")
            .suppressError()
            .runBefore { param ->
                if (param.args.getOrNull(0) != View.LAYER_TYPE_HARDWARE) return@runBefore
                if (dockBlurRadius <= 0 && widgetBlurRadius <= 0) return@runBefore
                if (WallpaperGlass.isAvailable()) return@runBefore

                val name = param.thisObject.javaClass.name
                if (LAYERED_CONTAINERS.any { name.endsWith(it) }) {
                    param.args[0] = View.LAYER_TYPE_NONE
                }
            }

        findClass(WallpaperManager::class.java.name)
            .hookMethod("setWallpaperOffsets")
            .suppressError()
            .runAfter { param ->
                (param.args.getOrNull(1) as? Float)?.let { WallpaperGlass.offsetX = it }
                WallpaperGlass.onOffsetsChanged()
            }

        findClass(WallpaperManager::class.java.name)
            .hookMethod("setWallpaperZoomOut")
            .suppressError()
            .runAfter { param ->
                (param.args.getOrNull(1) as? Float)?.let { WallpaperGlass.zoom = it }
                WallpaperGlass.onOffsetsChanged()
            }

        findClass("com.android.launcher3.Hotseat", suppressError = true)
            .hookConstructor()
            .suppressError()
            .runAfter { param ->
                val hotseat = param.thisObject as ViewGroup
                hotseat.installDockPanel()
                hotseat.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        hotseat.post { hotseat.installGlassOverlay() }
                    }

                    override fun onViewDetachedFromWindow(view: View) {}
                })
                if (hotseat.isAttachedToWindow) hotseat.post { hotseat.installGlassOverlay() }
            }

        findClass("com.android.quickstep.views.FloatingWidgetBackgroundView", suppressError = true)
            .hookMethod("init")
            .suppressError()
            .runAfter { param ->
                val view = param.thisObject as View
                view.installFloatingWidgetPanel()
                if (widgetClearBackground && widgetBlurRadius > 0) {
                    (view.getFieldSilently("mFallbackDrawable") as? Drawable)?.alpha = 0
                }
            }

        val widgetHostClass = findClass(
            "com.android.launcher3.widget.LauncherAppWidgetHostView",
            suppressError = true
        )

        widgetHostClass
            .hookConstructor()
            .suppressError()
            .runAfter { param -> (param.thisObject as ViewGroup).installWidgetPanel() }

        findClass("android.appwidget.AppWidgetHostView")
            .hookMethod("updateAppWidget")
            .suppressError()
            .runAfter { param ->
                if (!widgetClearBackground) return@runAfter

                val widget = param.thisObject as? ViewGroup ?: return@runAfter
                if (widgetHostClass?.isInstance(widget) != true || widget.isSearchBar()) return@runAfter
                widget.post { widget.clearLargeBackgrounds(widget.width * widget.height) }
            }

        widgetHostClass
            .hookMethod("onLayout")
            .suppressError()
            .runAfter { param ->
                val widget = param.thisObject as ViewGroup
                widget.installWidgetPanel()
                if (widget.isSearchBar()) return@runAfter

                val target = widget.findPanelTarget()
                if (target !== widget.getExtraFieldSilently(PANEL_TARGET)) {
                    widget.setExtraField(PANEL_TARGET, target)
                    widget.invalidate()
                }
                if (!widgetClearBackground) return@runAfter

                widget.clearLargeBackgrounds(widget.width * widget.height)
            }
    }

    private fun ViewGroup.installDockPanel() {
        if (background is BlurPanelDrawable) return
        hosts.add(this)

        background = BlurPanelDrawable(background, onShown = { panel().refreshSoon() }) { canvas ->
            if (dockBlurRadius <= 0 && dockBlurTint <= 0) return@BlurPanelDrawable
            if (!dockBounds(rect)) return@BlurPanelDrawable

            if (dockUsesGlass() && WallpaperGlass.isAvailable()) return@BlurPanelDrawable

            panel().draw(
                canvas = canvas,
                rect = rect,
                cornerRadius = dockBlurCorner.dp(this),
                blurRadius = dockBlurRadius.dp(this),
                tintColor = dockTint(dockBlurTint),
                roundBottom = !dockAttached
            )
        }
    }

    private fun dockUsesGlass(): Boolean = dockBlurRadius > 0 || dockLiquid

    private fun widgetUsesGlass(): Boolean = widgetBlurRadius > 0 || widgetLiquid

    private fun ViewGroup.installGlassOverlay() {
        val parent = parent as? ViewGroup ?: return
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).getExtraFieldSilently(GLASS_OVERLAY) == true) return
        }

        val hotseat = this
        val dragLayer = parent
        val overlay = object : View(context) {
            private val dockRect = RectF()
            private val points = FloatArray(4)
            private var lastState = 0
            private var lastHotseatAlpha = 1f
            private var glassAlpha = 1f

            init {
                setExtraField(GLASS_OVERLAY, true)
                setWillNotDraw(false)
                viewTreeObserver.addOnPreDrawListener {
                    val state = listOf(
                        hotseat.left, hotseat.top, hotseat.translationX, hotseat.translationY,
                        hotseat.scaleX, hotseat.scaleY, hotseat.visibleAlpha(), hotseat.isShown,
                        WallpaperGlass.offsetX, WallpaperGlass.zoom, WallpaperGlass.isAvailable(),
                        drawerCover(dragLayer)
                    ).hashCode()
                    if (state != lastState) {
                        lastState = state
                        invalidate()
                    }
                    true
                }
            }

            override fun onDraw(canvas: Canvas) {
                val hotseatAlpha = hotseat.visibleAlpha() * (1f - drawerCover(dragLayer))
                glassAlpha = when {
                    hotseatAlpha >= 1f -> 1f
                    hotseatAlpha < lastHotseatAlpha -> minOf(glassAlpha, hotseatAlpha)
                    else -> maxOf(glassAlpha, (hotseatAlpha * GLASS_APPEAR_SPEED).coerceAtMost(1f))
                }
                lastHotseatAlpha = hotseatAlpha
                if (!dockUsesGlass() || !hotseat.isShown || hotseatAlpha <= 0f) return
                if (!hotseat.dockBounds(dockRect)) return

                points[0] = dockRect.left
                points[1] = dockRect.top
                points[2] = dockRect.right
                points[3] = dockRect.bottom
                hotseat.matrix.mapPoints(points)
                val offsetX = hotseat.left - left
                val offsetY = hotseat.top - top
                dockRect.set(
                    points[0] + offsetX,
                    points[1] + offsetY,
                    points[2] + offsetX,
                    points[3] + offsetY
                )

                val corner = minOf(
                    dockBlurCorner.dp(hotseat) * hotseat.scaleX,
                    minOf(dockRect.width(), dockRect.height()) / 2f
                )
                WallpaperGlass.draw(
                    host = this,
                    canvas = canvas,
                    rect = dockRect,
                    corner = corner,
                    bottomCorner = if (dockAttached) 0f else corner,
                    blurRadius = dockBlurRadius.dp(hotseat),
                    tintColor = hotseat.dockTint(dockBlurTint),
                    alpha = glassAlpha,
                    liquid = dockLiquid
                )
            }
        }

        parent.addView(
            overlay,
            parent.indexOfChild(this).coerceAtLeast(0),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        hosts.add(overlay)
        (overlay.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.setFieldSilently("ignoreInsets", true)
            params.setMargins(0, 0, 0, 0)
            overlay.layoutParams = params
        }
    }

    private fun drawerCover(parent: ViewGroup): Float {
        var cover = 0f
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val name = child.javaClass.name
            if (!name.endsWith("SearchContainerView") && !name.endsWith("AllAppsContainerView")) continue
            if (child.visibility != View.VISIBLE || child.height <= 0) continue
            val shown = 1f - (child.translationY / child.height).coerceIn(0f, 1f)
            cover = maxOf(cover, shown * child.alpha)
        }
        return cover.coerceIn(0f, 1f)
    }

    private fun ViewGroup.visibleAlpha(): Float {
        var childAlpha = if (childCount == 0) 1f else 0f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.VISIBLE) childAlpha = maxOf(childAlpha, child.alpha)
        }
        return alpha * childAlpha
    }

    private fun ViewGroup.installWidgetPanel() {
        if (background is BlurPanelDrawable) return
        hosts.add(this)

        background = BlurPanelDrawable(background, onShown = { panel().refreshSoon() }) { canvas ->
            if (widgetBlurRadius <= 0 && widgetBlurTint <= 0 && !widgetLiquid) return@BlurPanelDrawable
            if (isSearchBar()) return@BlurPanelDrawable

            val target = panelTarget()
            if (target == null || !targetBounds(target, rect)) {
                if (getExtraFieldSilently(PANEL_TARGET) != null) panel().waitForContent()
                return@BlurPanelDrawable
            }

            val cornerRadius = (target.background as? GradientDrawable)?.cornerRadius
                ?.takeIf { it > 0f }
                ?: widgetCornerRadius()
            val corner = minOf(cornerRadius, minOf(rect.width(), rect.height()) / 2f)

            if (widgetUsesGlass() && WallpaperGlass.draw(
                    host = this,
                    canvas = canvas,
                    rect = rect,
                    corner = corner,
                    bottomCorner = corner,
                    blurRadius = widgetBlurRadius.dp(this),
                    tintColor = tint(widgetBlurTint),
                    liquid = widgetLiquid
                )
            ) return@BlurPanelDrawable

            panel().draw(
                canvas = canvas,
                rect = rect,
                cornerRadius = cornerRadius,
                blurRadius = widgetBlurRadius.dp(this),
                tintColor = tint(widgetBlurTint)
            )
        }
    }

    private fun View.installFloatingWidgetPanel() {
        if (background is BlurPanelDrawable) return
        hosts.add(this)

        background = BlurPanelDrawable(background, onShown = { panel().refreshSoon() }) { canvas ->
            if (widgetBlurRadius <= 0 && widgetBlurTint <= 0) return@BlurPanelDrawable
            if (width <= 0 || height <= 0) return@BlurPanelDrawable

            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            panel().draw(
                canvas = canvas,
                rect = rect,
                cornerRadius = (getFieldSilently("mOutlineRadius") as? Float)?.takeIf { it > 0f }
                    ?: widgetCornerRadius(),
                blurRadius = widgetBlurRadius.dp(this),
                tintColor = tint(widgetBlurTint)
            )
        }
    }

    private fun ViewGroup.dockBounds(out: RectF): Boolean {
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE || child.height == 0) continue

            top = minOf(top, child.top + child.translationY)
            bottom = maxOf(bottom, child.bottom + child.translationY)
        }
        if (top == Float.MAX_VALUE) return false

        val margin = dockBlurMargin.dp(this)
        val padding = DOCK_PADDING.dp(this)
        val panelBottom = if (dockAttached) height.toFloat() + windowGapBelow() else bottom + padding
        val panelTop = maxOf(top - padding, pageIndicatorBottom() + PAGE_INDICATOR_GAP.dp(this))
        out.set(margin, panelTop, width - margin, panelBottom)
        return !out.isEmpty
    }

    private fun View.pageIndicatorBottom(): Float {
        val workspace = context.launcherActivity()
            ?.let { it.getFieldSilently("mWorkspace") ?: it.callMethodSilently("getWorkspace") } as? View
            ?: return -Float.MAX_VALUE
        val indicator = workspace.getFieldSilently("mPageIndicator") as? View
            ?: return -Float.MAX_VALUE
        if (indicator.visibility != View.VISIBLE || indicator.height == 0) return -Float.MAX_VALUE

        val indicatorCenter = indicator.layoutTopInRoot() + indicator.height / 2
        return (indicatorCenter - layoutTopInRoot()).toFloat()
    }

    private fun Context.launcherActivity(): Activity? {
        var current: Context? = this
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun View.layoutTopInRoot(): Int {
        var top = 0
        var current: View? = this
        while (current != null) {
            top += current.top
            current = current.parent as? View
        }
        return top
    }

    private fun View.windowGapBelow(): Float {
        getLocationInWindow(windowLocation)
        return (rootView.height - windowLocation[1] - height).coerceAtLeast(0).toFloat()
    }

    private fun View.panel(): BlurPanel {
        return getExtraFieldSilently(BLUR_PANEL) as? BlurPanel
            ?: BlurPanel(this).also { setExtraField(BLUR_PANEL, it) }
    }

    private fun View.tint(opacity: Int): Int {
        return tint(opacity, materialWidgetColor())
    }

    private fun View.dockTint(opacity: Int): Int {
        return tint(opacity, if (dockTintWidgetColor) materialWidgetColor() else folderBackgroundColor())
    }

    private fun View.tint(opacity: Int, materialColor: Int?): Int {
        val base = materialColor ?: run {
            if (!wallpaperChecked) {
                wallpaperChecked = true
                wallpaperIsLight = wallpaperIsLight(context)
            }
            val light = wallpaperIsLight ?: !isNightMode()
            if (light) Color.WHITE else Color.BLACK
        }
        return ColorUtils.setAlphaComponent(base, opacity.coerceIn(0, 100) * 255 / 100)
    }

    private fun View.isNightMode(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    @SuppressLint("DiscouragedApi")
    private fun View.folderBackgroundColor(): Int? {
        val name = if (isNightMode()) "folder_background_dark" else "folder_background_light"
        val id = resources.getIdentifier(name, "color", context.packageName).takeIf { it != 0 }
            ?: return null
        return runCatching { context.getColor(id) }.getOrNull()
    }

    @SuppressLint("DiscouragedApi")
    private fun View.materialWidgetColor(): Int? {
        val name = if (isNightMode()) "system_widget_background_dark" else "system_widget_background_light"
        val id = resources.getIdentifier(name, "color", "android").takeIf { it != 0 } ?: return null
        return runCatching { context.getColor(id) }.getOrNull()
    }

    private fun wallpaperIsLight(context: Context): Boolean? {
        return runCatching {
            WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.colorHints
                ?.let { it and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0 }
        }.getOrNull()
    }

    private fun registerWallpaperColorsListener() {
        if (wallpaperListenerRegistered) return
        wallpaperListenerRegistered = true

        runCatching {
            WallpaperManager.getInstance(mContext).addOnColorsChangedListener(
                { colors, which ->
                    if (which and WallpaperManager.FLAG_SYSTEM == 0) return@addOnColorsChangedListener
                    WallpaperGlass.invalidateWallpaper(mContext)
                    wallpaperIsLight = colors?.let {
                        it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
                    }
                    hosts.toList().forEach { it.postInvalidate() }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    private fun View.isSearchBar(): Boolean {
        if (id == View.NO_ID) return false
        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return false
        return name.startsWith("search_container")
    }

    private fun View.widgetCornerRadius(): Float {
        return runCatching {
            Resources.getSystem().getDimension(android.R.dimen.system_app_widget_background_radius)
        }.getOrDefault(DEFAULT_WIDGET_CORNER.dp(this))
    }

    private fun ViewGroup.clearLargeBackgrounds(hostArea: Int) {
        if (hostArea <= 0) return

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val isLarge = child.width * child.height >= hostArea * LARGE_BACKGROUND_RATIO

            if (isLarge) {
                child.background?.let { background ->
                    if (background.alpha != 0) child.background = background.mutate().apply { alpha = 0 }
                }

                if (child is ImageView && child.imageAlpha != 0 && child.drawable?.isPlainShape() == true) {
                    child.imageAlpha = 0
                }
            }

            if (child is ViewGroup) child.clearLargeBackgrounds(hostArea)
        }
    }

    private fun ViewGroup.panelTarget(): View? {
        val cached = getExtraFieldSilently(PANEL_TARGET) as? View
        if (cached != null && cached.isAttachedToWindow && cached.isInside(this)) return cached

        return findPanelTarget()?.also { setExtraField(PANEL_TARGET, it) }
    }

    private fun View.isInside(ancestor: View): Boolean {
        var current = parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun ViewGroup.findPanelTarget(): View? {
        val hostArea = width * height
        if (hostArea <= 0) return null

        var best: View? = null
        var bestArea = 0

        fun visit(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue

                val area = child.width * child.height
                val isPanel = child.background != null ||
                        (child is ImageView && child.drawable?.isPlainShape() == true)
                if (isPanel && area >= hostArea * LARGE_BACKGROUND_RATIO && area > bestArea) {
                    best = child
                    bestArea = area
                }
                if (child is ViewGroup) visit(child)
            }
        }

        visit(this)
        return best
    }

    private fun ViewGroup.targetBounds(target: View, out: RectF): Boolean {
        var left = 0f
        var top = 0f
        var current: View? = target
        while (current != null && current !== this) {
            left += current.left + current.translationX
            top += current.top + current.translationY
            current = current.parent as? View
        }
        if (current !== this) return false

        out.set(left, top, left + target.width, top + target.height)
        return !out.isEmpty
    }

    private fun Drawable.isPlainShape(): Boolean {
        return when (this) {
            is ColorDrawable, is GradientDrawable, is ShapeDrawable -> true
            is LayerDrawable -> (0 until numberOfLayers).all { getDrawable(it)?.isPlainShape() != false }
            is DrawableWrapper -> drawable?.isPlainShape() != false
            is DrawableContainer -> current?.isPlainShape() != false
            else -> false
        }
    }

    private fun Int.dp(view: View): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            toFloat(),
            view.resources.displayMetrics
        )
    }

    companion object {
        private const val BLUR_PANEL = "pleBlurPanel"
        private const val PANEL_TARGET = "plePanelTarget"
        private const val GLASS_OVERLAY = "pleGlassOverlay"
        private const val GLASS_APPEAR_SPEED = 2.5f
        private const val RESUME_REFRESH_DELAY = 300L
        private const val DEFAULT_DOCK_CORNER = 28
        private const val DEFAULT_WIDGET_CORNER = 24
        private const val DEFAULT_DOCK_MARGIN = 12
        private val LAYERED_CONTAINERS = listOf(".Workspace", ".Hotseat", ".ShortcutAndWidgetContainer")
        private const val BACKGROUND_OFF = "off"
        private const val BACKGROUND_LIQUID = "liquid"
        private const val TINT_FOLDER = "folder"
        private const val TINT_WIDGET = "widget"
        private const val STYLE_FLOATING = "floating"
        private const val STYLE_ATTACHED = "attached"
        private const val DOCK_PADDING = 12
        private const val PAGE_INDICATOR_GAP = 8
        private const val LARGE_BACKGROUND_RATIO = 0.5f
    }
}
