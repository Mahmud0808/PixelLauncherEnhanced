package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ScaleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_CORNER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_MARGIN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_PADDING_BOTTOM
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_PADDING_TOP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_STYLE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_CLEAR_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.data.glass.GlassStyle
import com.drdisagree.pixellauncherenhanced.data.glass.GlassTarget
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.BlurPanel
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.BlurPanelDrawable
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceIds
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
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

class BlurPanels(context: Context) : ModPack(context) {

    private var dockBlurRadius = 0
    private var dockBlurTint = 0
    private var dockTintWidgetColor = false
    private var dockLiquid = false
    private var dockStyle = GlassStyle.DEFAULT
    private var widgetStyle = GlassStyle.DEFAULT
    private var searchStyle = GlassStyle.DEFAULT
    private var drawerSearchStyle = GlassStyle.DEFAULT
    private var widgetLiquid = false
    private var dockBlurCorner = DEFAULT_DOCK_CORNER
    private var dockAttached = false
    private var dockBlurMargin = DEFAULT_DOCK_MARGIN
    private var dockPaddingTop = DOCK_PADDING
    private var dockPaddingBottom = DOCK_PADDING
    private val windowLocation = IntArray(2)
    private val maskBounds = Rect()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var widgetBlurRadius = 0
    private var widgetBlurTint = 0
    private var widgetClearBackground = false
    private var searchBlurRadius = 0
    private var searchBlurTint = 0
    private var searchLiquid = false
    private var searchGlass = false
    private var drawerSearchGlass = false
    private var drawerSearchLiquid = false
    private var drawerSearchBlurRadius = 0
    private var drawerSearchBlurTint = 0
    private var installingSearchPanel = false
    private var widgetGlass = false
    private var widgetHostClass: Class<*>? = null
    private val qsbHostClass: Class<*>? by lazy {
        findClass("com.android.launcher3.qsb.QsbWidgetHostView", suppressError = true)
    }
    private val hotseatClass: Class<*>? by lazy { findClass("com.android.launcher3.Hotseat", suppressError = true) }
    private val hosts: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val rect = RectF()
    private val panelRadii = FloatArray(8)
    private val shadowRect = RectF()
    private val shadowRadii = FloatArray(8)
    private val shadowPath = Path()
    private val panelFrame = RectF()
    private val frameBounds = RectF()
    private val sourceBounds = RectF()
    private val imageRect = RectF()
    private var dockPanelOn = false
    private var wallpaperIsLight: Boolean? = null
    private var wallpaperListenerRegistered = false
    private var wallpaperChecked = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            val dockBackground = PanelBackground.supported(getString(DOCK_BACKGROUND, BACKGROUND_OFF))
            val dockEnabled = dockBackground != BACKGROUND_OFF
            dockPanelOn = dockEnabled
            dockBlurRadius = if (dockEnabled) getSliderInt(DOCK_BLUR_RADIUS, DEFAULT_BLUR) else 0
            dockBlurTint = if (dockEnabled) getSliderInt(DOCK_BLUR_TINT, DEFAULT_TINT) else 0
            dockLiquid = dockBackground == BACKGROUND_LIQUID
            dockStyle = GlassTarget.DOCK.readStyle(this::getSliderInt, this::getInt)
            dockTintWidgetColor = getString(DOCK_BLUR_TINT_COLOR, TINT_FOLDER) == TINT_WIDGET
            dockBlurCorner = getSliderInt(DOCK_BLUR_CORNER, DEFAULT_DOCK_CORNER)
            dockAttached = getString(DOCK_BLUR_STYLE, STYLE_FLOATING) == STYLE_ATTACHED
            dockBlurMargin = getSliderInt(DOCK_BLUR_MARGIN, DEFAULT_DOCK_MARGIN)
            dockPaddingTop = getSliderInt(DOCK_BLUR_PADDING_TOP, DOCK_PADDING)
            dockPaddingBottom = getSliderInt(DOCK_BLUR_PADDING_BOTTOM, DOCK_PADDING)
            val widgetBackground = PanelBackground.supported(getString(WIDGET_BACKGROUND, BACKGROUND_OFF))
            val widgetEnabled = widgetBackground != BACKGROUND_OFF
            widgetBlurRadius = if (widgetEnabled) getSliderInt(WIDGET_BLUR_RADIUS, DEFAULT_BLUR) else 0
            widgetBlurTint = if (widgetEnabled) getSliderInt(WIDGET_BLUR_TINT, DEFAULT_TINT) else 0
            widgetLiquid = widgetBackground == BACKGROUND_LIQUID
            widgetStyle = GlassTarget.WIDGETS.readStyle(this::getSliderInt, this::getInt)
            widgetGlass = widgetEnabled
            widgetClearBackground = widgetEnabled || getBoolean(WIDGET_CLEAR_BACKGROUND, false)
            val searchBackground = PanelBackground.supported(getString(SEARCH_BAR_BACKGROUND, BACKGROUND_OFF))
            val searchEnabled = searchBackground != BACKGROUND_OFF
            searchBlurRadius = if (searchEnabled) getSliderInt(SEARCH_BAR_BLUR_RADIUS, DEFAULT_BLUR) else 0
            searchBlurTint = if (searchEnabled) getSliderInt(SEARCH_BAR_BLUR_TINT, DEFAULT_TINT) else 0
            searchLiquid = searchBackground == BACKGROUND_LIQUID
            searchStyle = GlassTarget.SEARCH_BAR.readStyle(this::getSliderInt, this::getInt)
            searchGlass = searchEnabled
            val drawerSearchBackground = PanelBackground.supported(getString(DRAWER_SEARCH_BACKGROUND, BACKGROUND_OFF))
            drawerSearchGlass = drawerSearchBackground != BACKGROUND_OFF
            drawerSearchLiquid = drawerSearchBackground == BACKGROUND_LIQUID
            drawerSearchStyle = GlassTarget.DRAWER_SEARCH.readStyle(this::getSliderInt, this::getInt)
            drawerSearchBlurRadius = getSliderInt(DRAWER_SEARCH_BLUR_RADIUS, DEFAULT_BLUR)
            drawerSearchBlurTint = getSliderInt(DRAWER_SEARCH_BLUR_TINT, DEFAULT_TINT)
        }

        when (key.firstOrNull()) {
            DOCK_BLUR_RADIUS,
            DOCK_BLUR_TINT,
            DOCK_BLUR_TINT_COLOR,
            DOCK_BLUR_CORNER,
            DOCK_BLUR_STYLE,
            DOCK_BACKGROUND,
            DOCK_BLUR_MARGIN,
            DOCK_BLUR_PADDING_TOP,
            DOCK_BLUR_PADDING_BOTTOM,
            WIDGET_BLUR_RADIUS,
            WIDGET_BLUR_TINT,
            SEARCH_BAR_BLUR_RADIUS,
            SEARCH_BAR_BLUR_TINT,
            DRAWER_SEARCH_BACKGROUND,
            DRAWER_SEARCH_BLUR_RADIUS,
            DRAWER_SEARCH_BLUR_TINT,
            in PANEL_STYLE_KEYS -> hosts.toList().forEach {
                it.post {
                    it.background?.invalidateSelf()
                    it.invalidate()
                }
            }

            WIDGET_BACKGROUND,
            SEARCH_BAR_BACKGROUND,
            WIDGET_CLEAR_BACKGROUND -> {
                hosts.toList().forEach { host ->
                    host.post {
                        if (host is ViewGroup && host.isWidgetHost() && !host.isSkippedSearchBar()) {
                            if (host.panelClear()) {
                                host.clearLargeBackgrounds(host.width * host.height)
                            } else {
                                host.restoreClearedBackgrounds()
                            }
                            host.setExtraField(PANEL_TARGET, host.findPanelTargets())
                        }
                        host.background?.invalidateSelf()
                        host.invalidate()
                    }
                }
                reloadLauncher(mContext)
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        findClass("com.android.launcher3.CellLayout", suppressError = true)
            .hookMethod("onDraw")
            .suppressError()
            .runAfter { param ->
                val canvas = param.args.getOrNull(0) as? Canvas ?: return@runAfter
                drawWidgetShadows(param.thisObject as ViewGroup, canvas)
            }

        listOf(
            "com.android.quickstep.util.BaseDepthControllerImpl",
            "com.android.quickstep.util.BaseDepthController",
            "com.android.launcher3.statehandlers.DepthController"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("applyDepthAndBlur")
                .suppressError()
                .runAfter { param ->
                    val current = param.thisObject.getFieldSilently("mCurrentBlur") as? Int ?: return@runAfter
                    val max = param.thisObject.getFieldSilently("mMaxBlurRadius") as? Int ?: return@runAfter
                    BlurPanel.setLauncherBlur(if (max > 0) current.toFloat() / max else 0f)
                    WallpaperGlass.setDepthBlur(current.toFloat(), max.toFloat())
                }
        }

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
                val blurred = dockBlurRadius > 0 || widgetBlurRadius > 0 || searchBlurRadius > 0
                if (!blurred && !WallpaperGlass.isAvailable()) return@runBefore

                val name = param.thisObject.javaClass.name
                if (LAYERED_CONTAINERS.any { name.endsWith(it) }) {
                    param.args[0] = View.LAYER_TYPE_NONE
                }
            }

        findClass(WallpaperManager::class.java.name)
            .hookMethod("setWallpaperOffsets")
            .suppressError()
            .runAfter { param ->
                (param.args.getOrNull(1) as? Number)?.let { WallpaperGlass.offsetX = it.toFloat() }
                WallpaperGlass.onOffsetsChanged()
            }

        findClass(WallpaperManager::class.java.name)
            .hookMethod("setWallpaperZoomOut")
            .suppressError()
            .runAfter { param ->
                (param.args.getOrNull(1) as? Number)?.let { WallpaperGlass.zoom = it.toFloat() }
                WallpaperGlass.onOffsetsChanged()
            }

        findClass("com.android.launcher3.Hotseat", suppressError = true)
            .hookConstructor()
            .suppressError()
            .runAfter { param ->
                val hotseat = param.thisObject as ViewGroup
                hotseat.installDockPanel()
                (hotseat.getFieldSilently("mQsb") as? View)?.installSearchPanel()
                hotseat.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        hotseat.post { hotseat.installGlassOverlay() }
                    }

                    override fun onViewDetachedFromWindow(view: View) {}
                })
                if (hotseat.isAttachedToWindow) hotseat.post { hotseat.installGlassOverlay() }
            }

        val drawerSearchClass = findClass("com.android.launcher3.allapps.search.AppsSearchContainerLayout", suppressError = true)
        drawerSearchClass
            .hookConstructor()
            .suppressError()
            .runAfter { param -> (param.thisObject as? View)?.installDrawerSearchPanel() }

        if (drawerSearchClass != null) {
            findClass("android.view.View")
                .hookMethod("setBackgroundDrawable")
                .suppressError()
                .runAfter { param ->
                    if (!drawerSearchClass.isInstance(param.thisObject)) return@runAfter
                    (param.thisObject as View).installDrawerSearchPanel()
                }
        }

        val searchDelegateClass = findClass("com.google.android.apps.nexuslauncher.qsb.SearchDelegateView", suppressError = true)
        searchDelegateClass
            .hookMethod("onAttachedToWindow", "onLayout", "onThemeChanged")
            .suppressError()
            .runAfter { param -> (param.thisObject as? View)?.installSearchPanel() }

        val qsbOuterClass = findClass("com.android.launcher3.qsb.QsbOuterDrawable", suppressError = true)
        if (qsbOuterClass != null) {
            findClass("android.view.View")
                .hookMethod("setBackgroundDrawable")
                .suppressError()
                .runAfter { param ->
                    if (!qsbOuterClass.isInstance(param.args.getOrNull(0))) return@runAfter
                    (param.thisObject as View).installSearchPanel()
                }
        }

        if (searchDelegateClass != null) {
            findClass("android.view.View")
                .hookMethod("setBackgroundDrawable")
                .suppressError()
                .runAfter { param ->
                    if (param.thisObject.javaClass !== searchDelegateClass) return@runAfter
                    (param.thisObject as View).installSearchPanel()
                }
        }

        findClass("com.android.quickstep.views.FloatingWidgetBackgroundView", suppressError = true)
            .hookMethod("init")
            .suppressError()
            .runAfter { param ->
                val view = param.thisObject as View
                view.installFloatingWidgetPanel()
                if (widgetClearBackground || widgetUsesGlass()) {
                    (view.getFieldSilently("mFallbackDrawable") as? Drawable)?.alpha = 0
                }
            }

        widgetHostClass = findClass(
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
                val widget = param.thisObject as? ViewGroup ?: return@runAfter
                if (!widget.isWidgetHost() || widget.isSkippedSearchBar()) return@runAfter
                if (!widget.panelClear()) return@runAfter
                widget.post { widget.clearLargeBackgrounds(widget.width * widget.height) }
            }

        widgetHostClass
            .hookMethod("onLayout")
            .suppressError()
            .runAfter { param -> (param.thisObject as ViewGroup).onWidgetLayout() }

        qsbHostClass
            .hookMethod("onLayout")
            .suppressError()
            .runAfter { param ->
                val widget = param.thisObject as ViewGroup
                if (widget.isWidgetHost()) widget.onWidgetLayout()
            }
    }

    private fun ViewGroup.onWidgetLayout() {
        installWidgetPanel()
        if (isSkippedSearchBar()) return

        val targets = findPanelTargets()
        if (targets != getExtraFieldSilently(PANEL_TARGET)) {
            setExtraField(PANEL_TARGET, targets)
            invalidate()
        }
        if (!panelClear()) return

        clearLargeBackgrounds(width * height)
    }

    private fun View.isWidgetHost(): Boolean =
        widgetHostClass?.isInstance(this) == true || (qsbHostClass?.isInstance(this) == true && isHotseatSearchBar())

    private fun View.installSearchPanel() {
        if (installingSearchPanel || isWidgetHost()) return
        val current = background ?: return
        if (current.hasBlurPanel()) return
        val holder = current as? DrawableWrapper
        val original = if (holder != null) holder.drawable ?: return else current
        val pill = original.pillLayer() ?: original
        hosts.add(this)
        (backgroundTintList?.defaultColor ?: pill.fillColor())?.let { setExtraField(PANEL_COLOR, it) }

        val hidden = if (original is RippleDrawable) pill else original
        val shown = if (original.javaClass.name.endsWith(QSB_OUTER_DRAWABLE)) GlassHidden(original, this) else original
        val glass = BlurPanelDrawable(shown, onShown = { panel().refreshSoon() }) { canvas ->
            hidden.alpha = if (searchGlass) 0 else 255
            if (!searchGlass || width <= 0 || height <= 0) return@BlurPanelDrawable

            if (pill.bounds.isEmpty) rect.set(0f, 0f, width.toFloat(), height.toFloat()) else rect.set(pill.bounds)
            if (rect.isEmpty) return@BlurPanelDrawable
            val corner = (pill as? GradientDrawable)?.cornerRadius?.takeIf { it > 0f }
                ?.let { minOf(it, rect.height() / 2f) }
                ?: (rect.height() / 2f)

            if (WallpaperGlass.draw(
                    host = this,
                    canvas = canvas,
                    rect = rect,
                    corner = corner,
                    bottomCorner = corner,
                    blurRadius = searchBlurRadius.dp(this),
                    tintColor = panelTintColor(searchBlurTint),
                    liquid = searchLiquid,
                    style = searchStyle,
                    dimColor = dockBackdropTint(),
                    frostedBackdrop = dockBackdropRadius()
                )
            ) return@BlurPanelDrawable

            panel().draw(
                canvas = canvas,
                rect = rect,
                cornerRadius = corner,
                blurRadius = searchBlurRadius.dp(this),
                tintColor = panelTintColor(searchBlurTint),
                outline = searchStyle.takeUnless { searchLiquid }
            )
        }

        installingSearchPanel = true
        try {
            if (holder != null) {
                holder.drawable = glass
                invalidate()
            } else {
                background = glass
            }
        } finally {
            installingSearchPanel = false
        }
    }

    private fun View.installDrawerSearchPanel() {
        if (installingSearchPanel) return
        val original = background ?: return
        if (original.hasBlurPanel()) return
        val pill = original.pillLayer() ?: original
        val hidden = if (original is RippleDrawable) pill else original
        val color = backgroundTintList?.defaultColor ?: pill.fillColor()
        hosts.add(this)

        val glass = BlurPanelDrawable(original, onShown = { panel().refreshSoon() }) { canvas ->
            hidden.alpha = if (drawerSearchGlass) 0 else 255
            if (!drawerSearchGlass || width <= 0 || height <= 0) return@BlurPanelDrawable

            if (pill.bounds.isEmpty) rect.set(0f, 0f, width.toFloat(), height.toFloat()) else rect.set(pill.bounds)
            if (rect.isEmpty) return@BlurPanelDrawable
            val corner = (pill as? GradientDrawable)?.cornerRadius?.takeIf { it > 0f }
                ?.let { minOf(it, rect.height() / 2f) }
                ?: (rect.height() / 2f)
            val tintColor = color?.let {
                ColorUtils.setAlphaComponent(it, drawerSearchBlurTint.coerceIn(0, 100) * 255 / 100)
            } ?: tint(drawerSearchBlurTint)
            val blurRadius = IconGlass.drawerGlassBlur(this, drawerSearchBlurRadius.dp(this))

            if (WallpaperGlass.draw(
                    host = this,
                    canvas = canvas,
                    rect = rect,
                    corner = corner,
                    bottomCorner = corner,
                    blurRadius = blurRadius,
                    tintColor = tintColor,
                    liquid = drawerSearchLiquid,
                    style = drawerSearchStyle,
                    dimColor = IconGlass.drawerGlassTint(this) ?: 0,
                    home = true
                )
            ) return@BlurPanelDrawable

            panel().draw(
                canvas = canvas,
                rect = rect,
                cornerRadius = corner,
                blurRadius = blurRadius,
                tintColor = tintColor,
                outline = drawerSearchStyle.takeUnless { drawerSearchLiquid }
            )
        }

        installingSearchPanel = true
        try {
            background = glass
        } finally {
            installingSearchPanel = false
        }
    }

    private inner class GlassHidden(inner: Drawable, host: View) : DrawableWrapper(inner) {
        private val host = WeakReference(host)

        override fun draw(canvas: Canvas) {
            host.get()?.aiButtonShape()?.alpha = if (searchGlass) INNER_PILL_ALPHA else 255
            val inner = drawable ?: return
            val opacity = if (searchGlass) inner.getFieldSilently(OUTER_OPACITY) else null
            if (opacity == null) {
                super.draw(canvas)
                return
            }
            inner.setFieldSilently(OUTER_OPACITY, 0)
            val save = canvas.saveLayerAlpha(RectF(bounds), INNER_PILL_ALPHA)
            try {
                super.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
                inner.setFieldSilently(OUTER_OPACITY, opacity)
            }
        }
    }

    private fun View.aiButtonShape(): Drawable? {
        val id = ResourceIds.find(resources, AI_MODE_BUTTON, "id", context.packageName).takeIf { it != 0 } ?: return null
        val ripple = findViewById<View>(id)?.background as? RippleDrawable ?: return null
        return if (ripple.numberOfLayers > 0) ripple.getDrawable(0) else null
    }

    private fun Drawable.hasBlurPanel(): Boolean = when (this) {
        is BlurPanelDrawable -> true
        is DrawableWrapper -> drawable?.hasBlurPanel() == true
        else -> false
    }

    private fun Drawable.pillLayer(): Drawable? = when (this) {
        is LayerDrawable -> (0 until numberOfLayers)
            .filter { getId(it) != android.R.id.mask }
            .firstNotNullOfOrNull { getDrawable(it)?.pillLayer() }
        is DrawableWrapper -> drawable?.pillLayer()
        else -> this
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
                roundBottom = !dockAttached,
                outline = dockStyle.takeUnless { dockLiquid }
            )
        }
    }

    private fun dockUsesGlass(): Boolean = dockBlurRadius > 0 || dockLiquid

    private fun widgetUsesGlass(): Boolean = widgetGlass

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
                    liquid = dockLiquid,
                    style = dockStyle
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

    private fun View.shapeMask(): Drawable? {
        val source = (this as? ImageView)?.drawable?.takeIf { it.isPlainShape() || isShapeImage() }
            ?: background?.takeIf { it.isPlainShape() }
            ?: return null
        if (source.isRoundedRect()) return null

        (getExtraFieldSilently(SHAPE_MASK) as? Pair<*, *>)?.let { (cachedSource, mask) ->
            if (cachedSource === source) return mask as? Drawable
        }
        val mask = source.constantState?.newDrawable(resources)?.mutate()?.apply {
            alpha = 255
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        setExtraField(SHAPE_MASK, source to mask)
        return mask
    }

    private fun ViewGroup.drawMaskedLiquid(canvas: Canvas, target: View, mask: Drawable): Boolean {
        val paint = WallpaperGlass.canvasPaint(
            host = this,
            canvas = canvas,
            localRect = rect,
            corner = 0f,
            blurRadius = panelBlur().dp(this),
            alpha = 1f,
            liquid = true,
            style = panelStyle(),
            maskKey = mask,
            maskDrawer = { drawMask(it, target, mask) },
            frostedBackdrop = dockBackdropRadius(),
            dimColor = dockBackdropTint()
        ) ?: return false

        canvas.drawRect(rect, paint)
        val tintColor = panelTintColor(panelTint())
        if (Color.alpha(tintColor) != 0) {
            tintPaint.color = tintColor
            canvas.drawRect(rect, tintPaint)
        }
        return true
    }

    private fun drawMask(canvas: Canvas, target: View, mask: Drawable) {
        val save = canvas.save()
        if (target is ImageView) {
            canvas.translate(rect.left + target.paddingLeft, rect.top + target.paddingTop)
            canvas.concat(target.imageMatrix)
            target.drawable?.bounds?.let { mask.bounds = it }
        } else {
            rect.roundOut(maskBounds)
            mask.bounds = maskBounds
        }
        mask.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun View.setShapeShown(shown: Boolean) {
        val alpha = if (shown) 255 else 0
        if (this is ImageView && (isShapeImage() || drawable?.isPlainShape() == true)) {
            if (imageAlpha != alpha) imageAlpha = alpha
        } else {
            background?.takeIf { it.alpha != alpha }?.let { background = it.mutate().apply { this.alpha = alpha } }
        }
    }

    private fun Drawable.isRoundedRect(): Boolean {
        return when (this) {
            is ColorDrawable -> true
            is GradientDrawable -> shape == GradientDrawable.RECTANGLE
            is DrawableWrapper -> drawable?.isRoundedRect() != false
            is DrawableContainer -> current?.isRoundedRect() != false
            is LayerDrawable -> (0 until numberOfLayers).all { getDrawable(it)?.isRoundedRect() != false }
            else -> false
        }
    }

    private fun ViewGroup.installWidgetPanel() {
        if (background is BlurPanelDrawable) return
        hosts.add(this)

        background = BlurPanelDrawable(background, onShown = { panel().refreshSoon() }) { canvas ->
            val shadows = ArrayList<WidgetShadow>()
            drawWidgetPanel(canvas, shadows)
            updateWidgetShadows(shadows)
        }
    }

    private fun ViewGroup.drawWidgetPanel(canvas: Canvas, shadows: MutableList<WidgetShadow>) {
        if (isSkippedSearchBar()) return
        val blurRadius = panelBlur()
        val blurTint = panelTint()
        val liquid = panelLiquid()
        if (!panelGlass()) return

        val targets = panelTargets()
        if (targets.isEmpty()) {
            if ((getExtraFieldSilently(PANEL_TARGET) as? List<*>)?.isNotEmpty() == true) panel().waitForContent()
            return
        }
        computePanelFrame(targets)
        targets.forEach { drawPanelTarget(canvas, it, blurRadius, blurTint, liquid, shadows) }
    }

    private fun ViewGroup.updateWidgetShadows(shadows: List<WidgetShadow>) {
        val previous = getExtraFieldSilently(WIDGET_SHADOWS) as? List<*> ?: emptyList<WidgetShadow>()
        if (previous == shadows) return
        setExtraField(WIDGET_SHADOWS, shadows)
        (parent?.parent as? View)?.postInvalidateOnAnimation()
    }

    private fun drawWidgetShadows(cellLayout: ViewGroup, canvas: Canvas) {
        val container = cellLayout.getFieldSilently("mShortcutsAndWidgets") as? ViewGroup ?: return
        if (container.visibility != View.VISIBLE) return
        val density = cellLayout.resources.displayMetrics.density

        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            val shadows = child.getExtraFieldSilently(WIDGET_SHADOWS) as? List<*> ?: continue
            if (shadows.isEmpty()) continue

            val save = canvas.save()
            canvas.translate(container.left + container.translationX, container.top + container.translationY)
            canvas.translate(child.left.toFloat(), child.top.toFloat())
            canvas.concat(child.matrix)
            shadows.filterIsInstance<WidgetShadow>().forEach { shadow ->
                shadowRect.set(shadow.left, shadow.top, shadow.right, shadow.bottom)
                shadow.radii.forEachIndexed { i, radius -> shadowRadii[i] = radius }
                shadowPath.reset()
                shadowPath.addRoundRect(shadowRect, shadowRadii, Path.Direction.CW)
                WallpaperGlass.drawLiquidShadow(
                    canvas, shadowPath, density, child.alpha * container.alpha, strength = shadow.strength,
                    shapeKey = listOf(shadowRect.width(), shadowRect.height(), shadow.radii)
                )
            }
            canvas.restoreToCount(save)
        }
    }

    private data class WidgetShadow(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radii: List<Float>,
        val strength: Float
    )

    private fun ViewGroup.drawPanelTarget(
        canvas: Canvas,
        target: View,
        blurRadius: Int,
        blurTint: Int,
        liquid: Boolean,
        shadows: MutableList<WidgetShadow>
    ) {
        if (!targetBounds(target, rect)) return

        val shape = target.panelShape()
        val ownRadius = shape?.cornerRadius?.takeIf { it > 0f }
        val cornerRadius = ownRadius ?: widgetCornerRadius()
        val corner = minOf(cornerRadius, minOf(rect.width(), rect.height()) / 2f)
        val cornerRadii = shape?.perCornerRadii(rect)
            ?: if (ownRadius == null) frameRadii(rect, corner) else null

        val mask = target.shapeMask()
        val layer = if (mask != null) canvas.saveLayer(rect, null) else -1

        val drawn = if (mask != null && liquid) {
            drawMaskedLiquid(canvas, target, mask)
        } else WallpaperGlass.draw(
            host = this,
            canvas = canvas,
            rect = rect,
            corner = corner,
            bottomCorner = corner,
            blurRadius = blurRadius.dp(this),
            tintColor = panelTintColor(blurTint),
            liquid = liquid,
            style = panelStyle(),
            dimColor = dockBackdropTint(),
            frostedBackdrop = dockBackdropRadius(),
            cornerRadii = cornerRadii,
            deferShadow = { bounds, radii ->
                shadows.add(
                    WidgetShadow(bounds.left, bounds.top, bounds.right, bounds.bottom, radii.toList(), panelStyle().shadow)
                )
            }
        )

        if (mask != null) {
            if (drawn) {
                canvas.saveLayer(rect, maskPaint)
                drawMask(canvas, target, mask)
                canvas.restore()
            }
            canvas.restoreToCount(layer)
            target.post { target.setShapeShown(!drawn) }
            return
        }
        if (drawn) return

        panel().draw(
            canvas = canvas,
            rect = rect,
            cornerRadius = cornerRadius,
            blurRadius = blurRadius.dp(this),
            tintColor = panelTintColor(blurTint),
            outline = panelStyle().takeUnless { liquid },
            cornerRadii = cornerRadii
        )
    }

    private fun Drawable.gradientShape(): GradientDrawable? = when (this) {
        is GradientDrawable -> this
        is DrawableWrapper -> drawable?.gradientShape()
        is DrawableContainer -> current?.gradientShape()
        is LayerDrawable -> (0 until numberOfLayers).firstNotNullOfOrNull { getDrawable(it)?.gradientShape() }
        else -> null
    }

    private fun View.panelShape(): GradientDrawable? =
        (this as? ImageView)?.drawable?.gradientShape() ?: background?.gradientShape()

    private fun ViewGroup.computePanelFrame(targets: List<View>) {
        panelFrame.setEmpty()
        targets.forEach { target -> if (targetBounds(target, frameBounds)) panelFrame.union(frameBounds) }
    }

    private fun frameRadii(bounds: RectF, corner: Float): FloatArray? {
        if (panelFrame.isEmpty) return null

        val left = bounds.left - panelFrame.left <= FRAME_EDGE_SLACK
        val top = bounds.top - panelFrame.top <= FRAME_EDGE_SLACK
        val right = panelFrame.right - bounds.right <= FRAME_EDGE_SLACK
        val bottom = panelFrame.bottom - bounds.bottom <= FRAME_EDGE_SLACK
        if (left && top && right && bottom) return null

        panelRadii.fill(if (left && top) corner else 0f, 0, 2)
        panelRadii.fill(if (top && right) corner else 0f, 2, 4)
        panelRadii.fill(if (right && bottom) corner else 0f, 4, 6)
        panelRadii.fill(if (bottom && left) corner else 0f, 6, 8)
        return panelRadii
    }

    private fun GradientDrawable.perCornerRadii(bounds: RectF): FloatArray? {
        val radii = runCatching { cornerRadii }.getOrNull()?.takeIf { it.size == 8 } ?: return null
        val limit = minOf(bounds.width(), bounds.height()) / 2f
        for (i in 0 until 8) panelRadii[i] = minOf(radii[i], limit)
        return panelRadii
    }

    private fun View.sourcePanel(out: RectF): Float? {
        val host = getFieldSilently("mSourceView") as? ViewGroup ?: return null
        if (host.width <= 0 || host.height <= 0) return null

        val target = host.panelTargets().singleOrNull() ?: return null
        if (!host.targetBounds(target, sourceBounds)) return null

        val scaleX = width / host.width.toFloat()
        val scaleY = height / host.height.toFloat()
        val shape = target.panelShape()?.cornerRadius?.takeIf { it > 0f } ?: widgetCornerRadius()
        val corner = minOf(shape, minOf(sourceBounds.width(), sourceBounds.height()) / 2f)

        out.set(
            sourceBounds.left * scaleX,
            sourceBounds.top * scaleY,
            sourceBounds.right * scaleX,
            sourceBounds.bottom * scaleY
        )
        return minOf(corner * minOf(scaleX, scaleY), minOf(out.width(), out.height()) / 2f)
    }

    private fun View.installFloatingWidgetPanel() {
        if (background is BlurPanelDrawable) return
        hosts.add(this)

        background = BlurPanelDrawable(background, onShown = { panel().refreshSoon() }) { canvas ->
            if (!widgetGlass) return@BlurPanelDrawable
            if (width <= 0 || height <= 0) return@BlurPanelDrawable

            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val cornerRadius = sourcePanel(rect)
                ?: (getFieldSilently("mOutlineRadius") as? Float)?.takeIf { it > 0f }
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
                    liquid = widgetLiquid,
                    style = widgetStyle
                )
            ) return@BlurPanelDrawable

            panel().draw(
                canvas = canvas,
                rect = rect,
                cornerRadius = cornerRadius,
                blurRadius = widgetBlurRadius.dp(this),
                tintColor = tint(widgetBlurTint),
                outline = widgetStyle.takeUnless { widgetLiquid }
            )
        }
    }

    private fun ViewGroup.dockBounds(out: RectF): Boolean {
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        val qsb = getFieldSilently("mQsb") as? View

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE || child.height == 0) continue
            if (child === qsb && !child.hasVisibleContent()) continue

            top = minOf(top, child.top + child.translationY)
            bottom = maxOf(bottom, child.bottom + child.translationY)
        }
        if (top == Float.MAX_VALUE) return false

        val margin = dockBlurMargin.dp(this)
        val paddingTop = dockPaddingTop.dp(this)
        val panelTop = maxOf(top - paddingTop, pageIndicatorBottom() + PAGE_INDICATOR_GAP.dp(this))
        val panelBottom = if (dockAttached) {
            height.toFloat() + windowGapBelow()
        } else {
            maxOf(bottom + dockPaddingBottom.dp(this), panelTop)
        }
        out.set(margin, panelTop, width - margin, panelBottom)
        return !out.isEmpty
    }

    private fun View.hasVisibleContent(): Boolean {
        if (visibility != View.VISIBLE || alpha <= 0f || width == 0 || height == 0) return false
        background?.let { if ((it as? ColorDrawable)?.alpha != 0) return true }
        if (this !is ViewGroup) return javaClass != View::class.java
        for (i in 0 until childCount) {
            if (getChildAt(i).hasVisibleContent()) return true
        }
        return false
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

    private fun View.folderBackgroundColor(): Int? {
        val name = if (isNightMode()) "folder_background_dark" else "folder_background_light"
        val id = ResourceIds.find(resources, name, "color", context.packageName).takeIf { it != 0 }
            ?: return null
        return runCatching { context.getColor(id) }.getOrNull()
    }

    private fun View.materialWidgetColor(): Int? {
        val name = if (isNightMode()) "system_widget_background_dark" else "system_widget_background_light"
        val id = ResourceIds.find(resources, name, "color", "android").takeIf { it != 0 } ?: return null
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

    private fun View.isHotseatSearchBar(): Boolean {
        if (ResourceIds.entryName(resources, id) == HOTSEAT_SEARCH_ID) return true
        val hotseat = hotseatClass ?: return false
        var current = parent
        while (current is View) {
            if (hotseat.isInstance(current)) return true
            current = current.parent
        }
        return false
    }

    private fun View.onDock(): Boolean {
        if (!dockPanelOn) return false
        val parent = parent ?: return false
        dockMembership[this]?.takeIf { it.first.get() === parent }?.let { return it.second }
        val member = isHotseatSearchBar()
        dockMembership[this] = WeakReference(parent) to member
        return member
    }

    private fun View.dockBackdropRadius(): Float =
        when {
            dockBlurRadius <= 0 || !onDock() -> 0f
            dockLiquid -> WallpaperGlass.liquidBlur(dockBlurRadius.dp(this))
            else -> dockBlurRadius.dp(this)
        }

    private fun View.dockBackdropTint(): Int = if (onDock()) dockTint(dockBlurTint) else 0

    private fun View.isSkippedSearchBar(): Boolean = isSearchBar() && !isHotseatSearchBar()

    private fun View.panelBlur(): Int = if (isHotseatSearchBar()) searchBlurRadius else widgetBlurRadius

    private fun View.panelTint(): Int = if (isHotseatSearchBar()) searchBlurTint else widgetBlurTint

    private fun View.panelLiquid(): Boolean = if (isHotseatSearchBar()) searchLiquid else widgetLiquid

    private fun View.panelStyle(): GlassStyle = if (isHotseatSearchBar()) searchStyle else widgetStyle

    private fun View.panelTintColor(opacity: Int): Int {
        val original = (getExtraFieldSilently(PANEL_COLOR) as? Int)?.takeIf { isHotseatSearchBar() }
            ?: return tint(opacity)
        return ColorUtils.setAlphaComponent(original, opacity.coerceIn(0, 100) * 255 / 100)
    }

    private fun View.panelGlass(): Boolean = if (isHotseatSearchBar()) searchGlass else widgetGlass

    private fun View.panelClear(): Boolean = if (isHotseatSearchBar()) searchGlass else widgetClearBackground

    private fun View.isSearchBar(): Boolean {
        return ResourceIds.entryName(resources, id)?.startsWith("search_container") == true
    }

    private fun View.widgetCornerRadius(): Float {
        return runCatching {
            Resources.getSystem().getDimension(android.R.dimen.system_app_widget_background_radius)
        }.getOrDefault(DEFAULT_WIDGET_CORNER.dp(this))
    }

    private fun ViewGroup.clearLargeBackgrounds(hostArea: Int) {
        if (hostArea <= 0) return

        val colors = linkedSetOf<Int>()
        clearLargeBackgrounds(hostArea, colors, sectionPanels().toSet())
        if (colors.isNotEmpty()) {
            colors.maxByOrNull { Color.alpha(it) }?.let { setExtraField(PANEL_COLOR, it) }
            clearMatchingBackgrounds(colors)
        }
    }

    private fun ViewGroup.clearLargeBackgrounds(hostArea: Int, colors: MutableSet<Int>, sections: Set<View>) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val isLargeArea = child.width * child.height >= hostArea * LARGE_BACKGROUND_RATIO
            val isLarge = isLargeArea || child in sections

            if (isLarge && child.shapeMask() != null && !WallpaperGlass.isAvailable()) {
                if (child is ViewGroup) child.clearLargeBackgrounds(hostArea, colors, sections)
                continue
            }

            if (isLarge) {
                child.background?.takeIf { it.isFilled() }?.let { background ->
                    if (isLargeArea) {
                        (child.backgroundTintList?.defaultColor ?: background.fillColor())?.let { colors.add(it) }
                    }
                    child.setExtraField(ORIGINAL_BACKGROUND_ALPHA, background.alpha)
                    child.background = background.mutate().apply { alpha = 0 }
                    child.setExtraField(CLEARED, true)
                }

                if (child.isShapeImage() && (child as ImageView).imageAlpha != 0) {
                    child.imageTintList?.defaultColor?.let { colors.add(it) }
                    child.setExtraField(ORIGINAL_IMAGE_ALPHA, child.imageAlpha)
                    child.imageAlpha = 0
                    child.setExtraField(CLEARED, true)
                } else if (child is ImageView && child.imageAlpha != 0 && child.drawable?.isPlainShape() == true &&
                    child.drawable?.isFilled() == true && child.drawable?.isLevelBased() != true
                ) {
                    (child.imageTintList?.defaultColor ?: child.drawable?.fillColor())?.let { colors.add(it) }
                    child.setExtraField(ORIGINAL_IMAGE_ALPHA, child.imageAlpha)
                    child.imageAlpha = 0
                    child.setExtraField(CLEARED, true)
                }
            }

            if (child is ViewGroup) child.clearLargeBackgrounds(hostArea, colors, sections)
        }
    }

    private fun ViewGroup.restoreClearedBackgrounds() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            (child.getExtraFieldSilently(ORIGINAL_BACKGROUND_ALPHA) as? Int)?.let { alpha ->
                child.background?.alpha = alpha
                child.setExtraField(ORIGINAL_BACKGROUND_ALPHA, null)
            }
            (child.getExtraFieldSilently(ORIGINAL_IMAGE_ALPHA) as? Int)?.let { alpha ->
                (child as? ImageView)?.imageAlpha = alpha
                child.setExtraField(ORIGINAL_IMAGE_ALPHA, null)
            }
            if (child.getExtraFieldSilently(CLEARED) == true) child.setExtraField(CLEARED, null)
            if (child is ViewGroup) child.restoreClearedBackgrounds()
        }
        setExtraField(PANEL_COLOR, null)
        setExtraField(PANEL_TARGET, null)
    }

    private fun ViewGroup.clearMatchingBackgrounds(colors: Set<Int>) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is TextView || child is ViewGroup) {
                child.background?.takeIf { it.isFilled() && it.alpha != 0 }?.let { background ->
                    val color = background.fillColor() ?: return@let
                    if (colors.any { it.matches(color) }) {
                        child.setExtraField(ORIGINAL_BACKGROUND_ALPHA, background.alpha)
                        child.background = background.mutate().apply { alpha = 0 }
                    }
                }
            }
            if (child is ViewGroup) child.clearMatchingBackgrounds(colors)
        }
    }

    private fun Int.matches(other: Int): Boolean {
        return abs(Color.red(this) - Color.red(other)) <= COLOR_TOLERANCE &&
                abs(Color.green(this) - Color.green(other)) <= COLOR_TOLERANCE &&
                abs(Color.blue(this) - Color.blue(other)) <= COLOR_TOLERANCE
    }

    private fun Drawable.fillColor(): Int? {
        return when (this) {
            is ColorDrawable -> color
            is GradientDrawable -> color?.defaultColor ?: colors?.firstOrNull()
            is ShapeDrawable -> paint.color
            is DrawableWrapper -> drawable?.fillColor()
            is DrawableContainer -> current?.fillColor()
            is LayerDrawable -> (numberOfLayers - 1 downTo 0).firstNotNullOfOrNull { layer(it)?.fillColor() }
            else -> null
        }
    }

    private fun Drawable.isFilled(): Boolean {
        if (alpha == 0) return false
        return when (this) {
            is ColorDrawable -> Color.alpha(color) != 0
            is GradientDrawable -> colors?.any { Color.alpha(it) != 0 }
                ?: color?.let { Color.alpha(it.defaultColor) != 0 || it.isStateful }
                ?: false
            is ShapeDrawable -> Color.alpha(paint.color) != 0 || paint.shader != null
            is DrawableWrapper -> drawable?.isFilled() == true
            is DrawableContainer -> current?.isFilled() == true
            is LayerDrawable -> (0 until numberOfLayers).any { layer(it)?.isFilled() == true }
            else -> true
        }
    }

    private fun LayerDrawable.layer(index: Int): Drawable? {
        return if (getId(index) == android.R.id.mask) null else getDrawable(index)
    }

    private fun View.isShapeImage(): Boolean {
        if (this !is ImageView) return false
        val group = parent as? ViewGroup ?: return false
        if (group.childCount < 2 || group.getChildAt(0) !== this) return false
        if (drawable?.isLevelBased() == true) return false
        var source = drawable
        while (source is DrawableWrapper) source = source.drawable
        return source is VectorDrawable
    }

    private fun View.hasPanel(): Boolean {
        if (getExtraFieldSilently(CLEARED) == true) return true
        if (isShapeImage()) return true
        if (background?.isFilled() == true) return true
        return this is ImageView && imageAlpha != 0 &&
                drawable?.let { it.isPlainShape() && it.isFilled() && !it.isLevelBased() } == true
    }

    private fun Drawable.isLevelBased(): Boolean {
        return when (this) {
            is ClipDrawable, is ScaleDrawable -> true
            is DrawableWrapper -> drawable?.isLevelBased() == true
            is DrawableContainer -> current?.isLevelBased() == true
            is LayerDrawable -> (0 until numberOfLayers).any { getDrawable(it)?.isLevelBased() == true }
            else -> false
        }
    }

    private fun ViewGroup.panelTargets(): List<View> {
        val cached = (getExtraFieldSilently(PANEL_TARGET) as? List<*>)?.filterIsInstance<View>()
        if (!cached.isNullOrEmpty() && cached.all { it.isAttachedToWindow && it.isInside(this) }) return cached

        return findPanelTargets().also { setExtraField(PANEL_TARGET, it) }
    }

    private fun View.isInside(ancestor: View): Boolean {
        var current = parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun ViewGroup.findPanelTargets(): List<View> {
        val single = findPanelTarget()
        val sections = withoutStacked(sectionPanels())
        if (sections.isEmpty() || single == null) return sections.ifEmpty { listOfNotNull(single) }
        if (single in sections) return sections
        return if (single.area() > sections.sumOf { it.area() }) listOf(single) else sections
    }

    private fun View.area(): Int = width * height

    private fun ViewGroup.withoutStacked(sections: List<View>): List<View> {
        if (sections.size < 2) return sections
        val bounds = sections.map { target -> RectF().also { targetBounds(target, it) } }
        return sections.filterIndexed { index, _ ->
            bounds.indices.none { other ->
                other != index && bounds[other].covers(bounds[index]) &&
                        (other < index || !bounds[index].covers(bounds[other]))
            }
        }
    }

    private fun RectF.covers(other: RectF): Boolean {
        return left - other.left <= FRAME_EDGE_SLACK && top - other.top <= FRAME_EDGE_SLACK &&
                other.right - right <= FRAME_EDGE_SLACK && other.bottom - bottom <= FRAME_EDGE_SLACK
    }

    private fun ViewGroup.sectionPanels(): List<View> {
        val hostArea = width * height
        if (hostArea <= 0) return emptyList()

        fun visit(group: ViewGroup): List<View>? {
            if (group is AdapterView<*>) return null
            val sections = (0 until group.childCount).map { group.getChildAt(it) }.filter {
                it.visibility == View.VISIBLE && it.area() > 0 && it.hasPanel() &&
                        (it.width >= group.width * SECTION_SPAN || it.height >= group.height * SECTION_SPAN)
            }
            if (sections.size >= 2 && sections.sumOf { it.area() } >= hostArea * LARGE_BACKGROUND_RATIO) return sections
            for (i in 0 until group.childCount) {
                (group.getChildAt(i) as? ViewGroup)?.let { child -> visit(child)?.let { return it } }
            }
            return null
        }

        return visit(this).orEmpty()
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
                val isPanel = child.hasPanel()
                if (isPanel && area >= hostArea * LARGE_BACKGROUND_RATIO && area >= bestArea) {
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
        target.narrowToImage(out)
        return !out.isEmpty
    }

    private fun View.narrowToImage(out: RectF) {
        val image = (this as? ImageView)?.drawable ?: return
        if (image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return

        imageRect.set(image.bounds)
        imageMatrix.mapRect(imageRect)
        imageRect.offset(out.left + paddingLeft, out.top + paddingTop)
        if (!imageRect.isEmpty) out.intersect(imageRect)
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

    private val dockMembership = WeakHashMap<View, Pair<WeakReference<ViewParent>, Boolean>>()

    init {
        instance = WeakReference(this)
    }

    companion object {
        private var instance: WeakReference<BlurPanels>? = null

        fun dockBackdropRadius(view: View): Float = instance?.get()?.run { view.dockBackdropRadius() } ?: 0f

        fun dockBackdropTint(view: View): Int = instance?.get()?.run { view.dockBackdropTint() } ?: 0

        private const val BLUR_PANEL = "pleBlurPanel"
        private const val PANEL_TARGET = "plePanelTarget"
        private const val WIDGET_SHADOWS = "pleWidgetShadows"
        private const val DEFAULT_BLUR = 5
        private const val DEFAULT_TINT = 40
        private const val HOTSEAT_SEARCH_ID = "search_container_hotseat"
        private const val QSB_OUTER_DRAWABLE = ".QsbOuterDrawable"
        private const val OUTER_OPACITY = "mOpacity"
        private const val AI_MODE_BUTTON = "ai_mode_button"
        private const val INNER_PILL_ALPHA = 128
        private const val PANEL_COLOR = "plePanelColor"
        private const val ORIGINAL_BACKGROUND_ALPHA = "pleOriginalBackgroundAlpha"
        private const val ORIGINAL_IMAGE_ALPHA = "pleOriginalImageAlpha"
        private const val SHAPE_MASK = "pleShapeMask"
        private const val CLEARED = "pleCleared"
        private const val COLOR_TOLERANCE = 6
        private const val GLASS_OVERLAY = "pleGlassOverlay"
        private const val GLASS_APPEAR_SPEED = 2.5f
        private const val RESUME_REFRESH_DELAY = 300L
        private const val DEFAULT_DOCK_CORNER = 28
        private const val DEFAULT_WIDGET_CORNER = 24
        private const val FRAME_EDGE_SLACK = 1f
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
        private const val SECTION_SPAN = 0.9f
        private val PANEL_STYLE_KEYS = listOf(
            GlassTarget.DOCK,
            GlassTarget.WIDGETS,
            GlassTarget.SEARCH_BAR,
            GlassTarget.DRAWER_SEARCH
        ).flatMap { it.styleKeys }
    }
}
