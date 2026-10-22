package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_RECENTS_LIVE_TILE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.BlurPanel
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.DragPreviewGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FolderFrost
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceIds
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.WallpaperGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.folderPreviewBounds
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

class OpacityModifier(context: Context) : ModPack(context) {

    private var appDrawerBackgroundOpacity: Int = -1
    private val sheetLayer = ThreadLocal<Int>()
    private var recentsBackgroundOpacity: Int = -1
    private var disableRecentsLiveTile: Boolean = false
    private var folderBackgroundOpacity: Int = -1
    private val shapeCorners = WeakHashMap<Any, Pair<Float, Float>>()
    private val addToPathMethods = HashMap<Class<*>, Method?>()
    private var folderBackgroundBlur: Int = 0
    private var folderLiquid = false
    private var folderBlurAnimator: ValueAnimator? = null
    private var folderFrost: FolderFrost? = null
    private val previewRect = RectF()
    private val previewBounds = Rect()

    init {
        instance = WeakReference(this)
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            appDrawerBackgroundOpacity = getSliderInt(APP_DRAWER_BACKGROUND_OPACITY, -1) * 255 / 100
            recentsBackgroundOpacity = getSliderInt(RECENTS_BACKGROUND_OPACITY, -1) * 255 / 100
            disableRecentsLiveTile = getBoolean(DISABLE_RECENTS_LIVE_TILE, false)
            val folderBackground = PanelBackground.supported(getString(FOLDER_BACKGROUND, BACKGROUND_OFF))
            val folderEnabled = folderBackground != BACKGROUND_OFF
            folderBackgroundBlur = if (folderEnabled) getSliderInt(FOLDER_BACKGROUND_BLUR, DEFAULT_FOLDER_BLUR) else 0
            folderLiquid = folderBackground == BACKGROUND_LIQUID
            folderBackgroundOpacity = if (folderEnabled) {
                getSliderInt(FOLDER_BACKGROUND_OPACITY, DEFAULT_FOLDER_TINT) * 255 / 100
            } else -1
        }

        if (key.firstOrNull() in setOf(FOLDER_BACKGROUND_OPACITY, FOLDER_BACKGROUND)) reloadLauncher(mContext)
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val allAppsStateClass = findClass("com.android.launcher3.uioverrides.states.AllAppsState")
        val overviewStateClass = findClass("com.android.launcher3.uioverrides.states.OverviewState")
        val quickSwitchStateClass =
            findClass("com.android.launcher3.uioverrides.states.QuickSwitchState")
        val recentsStateClass = findClass("com.android.quickstep.fallback.RecentsState")
        val hintStateClass = findClass(
            "com.android.launcher3.states.HintState",
            "com.android.launcher3.uioverrides.states.HintState"
        )
        val activityAllAppsContainerViewClass =
            findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView")
        val taskbarAllAppsSlideInViewClass = findClass(
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView",
            suppressError = true
        )
        val scrimColorsClass = findClass(
            "com.android.launcher3.views.ScrimColors",
            suppressError = true
        )

        fun XC_MethodHook.MethodHookParam.updateScrimColorVariable() {
            val mScrimColor = thisObject.getFieldSilently("mScrimColor") as? Int

            if (mScrimColor != null) {
                thisObject.setField(
                    "mScrimColor",
                    ColorUtils.setAlphaComponent(
                        mScrimColor,
                        appDrawerBackgroundOpacity
                    )
                )
            }
        }

        activityAllAppsContainerViewClass
            .hookConstructor()
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                param.updateScrimColorVariable()
            }

        activityAllAppsContainerViewClass
            .hookMethod("updateHeaderScroll")
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                if (appDrawerBackgroundOpacity != 255) {
                    param.thisObject.setFieldSilently("mHeaderColor", Color.TRANSPARENT)
                    param.thisObject.callMethodSilently("invalidateHeader")
                }
            }

        activityAllAppsContainerViewClass
            .hookMethod(
                "getHeaderColor",
                "getScrimColor",
                "getBackgroundColor",
                "getBottomSheetBackgroundColor"
            )
            .suppressError()
            .runBefore { param ->
                if (appDrawerBackgroundOpacity < 0) return@runBefore

                param.updateScrimColorVariable()
            }
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                param.result = ColorUtils.setAlphaComponent(
                    param.result as Int,
                    appDrawerBackgroundOpacity
                )
            }

        activityAllAppsContainerViewClass
            .hookMethod("drawOnScrimWithScaleAndBottomOffset", "drawOnScrimWithScale")
            .suppressError()
            .runBefore { param ->
                sheetLayer.remove()
                if (appDrawerBackgroundOpacity !in 0..254) return@runBefore
                if (param.thisObject.getFieldSilently("mScrimColor") is Int) return@runBefore

                if (appDrawerBackgroundOpacity == 0) {
                    param.result = null
                    return@runBefore
                }
                val canvas = param.args.getOrNull(0) as? Canvas ?: return@runBefore
                sheetLayer.set(canvas.saveLayerAlpha(null, appDrawerBackgroundOpacity))
            }
            .runAfter { param ->
                val save = sheetLayer.get() ?: return@runAfter
                sheetLayer.remove()
                (param.args.getOrNull(0) as? Canvas)?.restoreToCount(save)
            }

        allAppsStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                val deviceProfile = param.args[0].callMethodSilently("getDeviceProfile")
                val isTablet = (deviceProfile.getFieldSilently("isTablet") as? Boolean
                    ?: deviceProfile
                        .getAnyFieldSilently("mDeviceProperties", "deviceProperties")
                        .getAnyFieldSilently("isTablet", "isLargeScreen") as? Boolean) == true

                if (!isTablet) {
                    if (param.result is Int) {
                        param.result = ColorUtils.setAlphaComponent(
                            param.result as Int,
                            appDrawerBackgroundOpacity
                        )
                    } else {
                        param.result = getScrimColors(
                            param.result,
                            scrimColorsClass!!,
                            appDrawerBackgroundOpacity
                        )
                    }
                }
            }

        taskbarAllAppsSlideInViewClass
            .hookMethod("getScrimColor")
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                param.result = ColorUtils.setAlphaComponent(
                    param.result as Int,
                    appDrawerBackgroundOpacity
                )
            }

        overviewStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                if (param.result is Int) {
                    param.result = ColorUtils.setAlphaComponent(
                        param.result as Int,
                        recentsBackgroundOpacity
                    )
                } else {
                    param.result = getScrimColors(
                        param.result,
                        scrimColorsClass!!,
                        recentsBackgroundOpacity
                    )
                }
            }

        quickSwitchStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                val launcher = param.args[0]
                val deviceProfile = launcher.callMethod("getDeviceProfile")
                val isTaskbarPresentInApps =
                    deviceProfile.callMethodSilently("isTaskbarPresentInApps") as? Boolean
                        ?: deviceProfile.getField("isTaskbarPresentInApps") as Boolean
                val currentResult = param.result as Int

                if (currentResult != Color.TRANSPARENT && !isTaskbarPresentInApps) {
                    if (param.result is Int) {
                        param.result = ColorUtils.setAlphaComponent(
                            param.result as Int,
                            recentsBackgroundOpacity
                        )
                    } else {
                        param.result = getScrimColors(
                            param.result,
                            scrimColorsClass!!,
                            recentsBackgroundOpacity
                        )
                    }
                }
            }

        recentsStateClass
            .hookMethod("getScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                if (param.result is Int) {
                    param.result = ColorUtils.setAlphaComponent(
                        param.result as Int,
                        recentsBackgroundOpacity
                    )
                } else {
                    param.result = getScrimColors(
                        param.result,
                        scrimColorsClass!!,
                        recentsBackgroundOpacity
                    )
                }
            }

        hintStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                if (param.result is Int) {
                    param.result = ColorUtils.setAlphaComponent(
                        param.result as Int,
                        recentsBackgroundOpacity
                    )
                } else {
                    param.result = getScrimColors(
                        param.result,
                        scrimColorsClass!!,
                        recentsBackgroundOpacity
                    )
                }
            }

        findClass("com.android.launcher3.folder.Folder")
            .hookMethod("onFinishInflate", "animateOpen")
            .suppressError()
            .runAfter { param ->
                (param.thisObject.getFieldSilently("mBackground") as? GradientDrawable)
                    ?.alpha = folderBackgroundOpacity.takeIf { it >= 0 } ?: 255
            }

        findClass("com.android.launcher3.views.ClipIconView", suppressError = true)
            .hookMethod("draw")
            .suppressError()
            .runBefore { param ->
                val view = param.thisObject as? View ?: return@runBefore
                val floating = view.parent as? View ?: return@runBefore
                val original = floating.getFieldSilently("mOriginalIcon") as? View ?: return@runBefore
                if (!original.javaClass.name.endsWith(".FolderIcon")) return@runBefore
                if (GestureMod.coversFolder(original) || !targetsWholeFolder(floating)) return@runBefore
                val background = view.getFieldSilently("mBackground") as? Drawable ?: return@runBefore
                if (background is FloatingFolderGlass) return@runBefore

                val foreground = view.getFieldSilently("mForeground") as? Drawable
                if (foreground != null && foreground !is LiveFolderPreview) {
                    view.setFieldSilently(
                        "mForeground",
                        LiveFolderPreview(foreground, background, view, original).apply { bounds = foreground.bounds }
                    )
                }
                if (folderGlass()) {
                    view.setFieldSilently(
                        "mBackground",
                        FloatingFolderGlass(background, view, original).apply { bounds = background.bounds }
                    )
                }
            }
            .runAfter { param ->
                (param.thisObject.getFieldSilently("mBackground") as? FloatingFolderGlass)?.let {
                    param.thisObject.setFieldSilently("mBackground", it.inner)
                }
                (param.thisObject.getFieldSilently("mForeground") as? LiveFolderPreview)?.let {
                    param.thisObject.setFieldSilently("mForeground", it.inner)
                }
            }

        findClass("com.android.launcher3.folder.PreviewBackground")
            .hookMethod("drawBackground")
            .suppressError()
            .runAround(before = { param ->
                val glass = folderGlass()
                if (folderBackgroundOpacity < 0 && !glass) return@runAround

                val color = param.thisObject.getFieldSilently("mBgColor") as? Int ?: return@runAround
                param.setObjectExtra(PREVIEW_ORIGINAL_COLOR, color)

                val context = param.thisObject.getFieldSilently("mContext") as? Context
                val base = context?.takeIf { glass }?.folderBackgroundColor() ?: color
                val alpha = if (folderBackgroundOpacity >= 0) folderBackgroundOpacity else Color.alpha(base)
                param.thisObject.setFieldSilently("mBgColor", ColorUtils.setAlphaComponent(base, alpha))
            }, after = { param ->
                val color = param.getObjectExtra(PREVIEW_ORIGINAL_COLOR) as? Int ?: return@runAround
                param.thisObject.setFieldSilently("mBgColor", color)
            })

        DragPreviewGlass.install()
        val folderClass = findClass("com.android.launcher3.folder.Folder")

        folderClass
            .hookMethod("animateOpen")
            .suppressError()
            .runAfter { param ->
                if (!folderGlass()) return@runAfter

                val folder = param.thisObject as View
                val dragLayer = folder.parent as? ViewGroup ?: return@runAfter
                val frosted = folder.getExtraFieldSilently(FOLDER_FROST) as? FolderFrost
                    ?: FolderFrost(folder).also { folder.setExtraField(FOLDER_FROST, it) }

                val ownBlur = folderBackgroundBlur * folder.resources.displayMetrics.density
                val inDrawer = folder.getFieldSilently("mInfo").getFieldSilently("container") == CONTAINER_ALL_APPS
                frosted.maxRadius = ownBlur
                frosted.backdropRadius = if (inDrawer) IconGlass.drawerBlur(folder, ownBlur) else null
                frosted.backdropTint = if (inDrawer) IconGlass.drawerBackdropTint(folder) ?: 0 else 0
                frosted.liquid = folderLiquid
                frosted.home = inDrawer
                frosted.start(folder.blurTargets(dragLayer), dragLayer.width, dragLayer.height)
                folderFrost = frosted
                animateFolderBlur(1f, folder.getFieldSilently("mCurrentAnimator") as? Animator)
            }

        folderClass
            .hookMethod("dispatchDraw")
            .suppressError()
            .runBefore { param ->
                val frost = param.thisObject.getExtraFieldSilently(FOLDER_FROST) as? FolderFrost
                    ?: return@runBefore
                if (frost.progress <= 0f) return@runBefore

                val canvas = param.args.getOrNull(0) as? Canvas ?: return@runBefore
                val clip = param.thisObject.getFieldSilently("mClipPath").let {
                    it as? Path ?: it.getFieldSilently("path") as? Path
                }
                val animating = (param.thisObject.getFieldSilently("mCurrentAnimator") as? Animator)
                    ?.isRunning == true
                if (animating && (clip == null || clip.isEmpty)) return@runBefore
                val background = param.thisObject.getFieldSilently("mBackground") as? GradientDrawable
                    ?: return@runBefore

                frost.draw(canvas, clip, background.bounds, background.cornerRadius)
            }

        findClass("com.android.launcher3.util.QuickstepBackgroundBlurHelper", suppressError = true)
            .hookMethod("drawFolderBlur")
            .suppressError()
            .runBefore { param ->
                val folder = param.args.getOrNull(2) ?: return@runBefore
                val frost = folder.getExtraFieldSilently(FOLDER_FROST) as? FolderFrost ?: return@runBefore
                if (folderGlass() && frost.progress > 0f) param.result = null
            }

        findClass("com.android.launcher3.folder.FolderIcon")
            .hookMethod("dispatchDraw")
            .suppressError()
            .runBefore { param ->
                if (!folderGlass()) return@runBefore

                val folderIcon = param.thisObject as View
                if (folderIcon.getFieldSilently("mBackgroundIsVisible") == false || GestureMod.coversFolder(folderIcon)) {
                    (folderIcon.getExtraFieldSilently(PREVIEW_BLUR) as? BlurPanel)?.markHidden()
                    return@runBefore
                }

                val canvas = param.args.getOrNull(0) as? Canvas ?: return@runBefore
                val preview = folderIcon.getFieldSilently("mBackground") ?: return@runBefore
                if (!preview.folderPreviewBounds(previewBounds)) return@runBefore
                val previewSize = previewBounds.width()
                val radius = previewSize / 2
                val scaledRadius = preview.callMethodSilently("getScaledRadius") as? Int ?: radius
                val offsetX = previewBounds.left - (scaledRadius - radius)
                val offsetY = previewBounds.top - (scaledRadius - radius)

                previewRect.set(
                    offsetX.toFloat(),
                    offsetY.toFloat(),
                    (offsetX + scaledRadius * 2).toFloat(),
                    (offsetY + scaledRadius * 2).toFloat()
                )

                val inDrawer = folderIcon.tag.getFieldSilently("container") == CONTAINER_ALL_APPS
                val blurRadius = folderIcon.folderIconBlur()
                val drawerTint = if (inDrawer) IconGlass.drawerGlassTint(folderIcon) ?: 0 else 0
                val shape = folderIcon.folderShape()
                val shapeCorner = shape?.cornerRadius(scaledRadius.toFloat()) ?: scaledRadius.toFloat()
                if (DragPreviewGlass.isCapturing(canvas)) {
                    DragPreviewGlass.capture(
                        canvas,
                        Path().apply { addRoundRect(previewRect, shapeCorner, shapeCorner, Path.Direction.CW) },
                        previewRect,
                        shapeCorner,
                        blurRadius,
                        1f,
                        folderLiquid
                    )
                    return@runBefore
                }
                if (WallpaperGlass.draw(
                        host = folderIcon,
                        canvas = canvas,
                        rect = previewRect,
                        corner = shapeCorner,
                        bottomCorner = shapeCorner,
                        blurRadius = blurRadius,
                        tintColor = Color.TRANSPARENT,
                        liquid = folderLiquid,
                        dimColor = drawerTint,
                        home = inDrawer,
                        shapeDrawer = shape?.let { shape ->
                            { paint: Paint ->
                                shape.callMethodSilently(
                                    "drawShape",
                                    canvas,
                                    offsetX.toFloat(),
                                    offsetY.toFloat(),
                                    scaledRadius.toFloat(),
                                    paint
                                )
                            }
                        }
                    )
                ) return@runBefore
                if (inDrawer) return@runBefore

                val panel = folderIcon.getExtraFieldSilently(PREVIEW_BLUR) as? BlurPanel
                    ?: BlurPanel(folderIcon).also { folderIcon.setExtraField(PREVIEW_BLUR, it) }

                panel.draw(
                    canvas = canvas,
                    rect = previewRect,
                    cornerRadius = scaledRadius.toFloat(),
                    blurRadius = blurRadius,
                    tintColor = Color.TRANSPARENT
                )
            }

        folderClass
            .hookMethod("handleClose")
            .suppressError()
            .runAfter { param ->
                if (folderFrost == null) return@runAfter

                if (param.args.getOrNull(0) == true) {
                    animateFolderBlur(
                        0f,
                        param.thisObject.getFieldSilently("mCurrentAnimator") as? Animator
                    )
                } else {
                    clearFolderBlur()
                }
            }

        folderClass
            .hookMethod("onDetachedFromWindow")
            .suppressError()
            .runAfter {
                if (folderBlurAnimator?.isRunning != true) clearFolderBlur()
            }

        val recentsViewClass = findClass("com.android.quickstep.views.RecentsView")

        recentsViewClass
            .hookMethod("onGestureAnimationEnd")
            .runAfter { param ->
                if (!disableRecentsLiveTile) return@runAfter

                param.thisObject.callMethod(
                    "switchToScreenshot",
                    Runnable {
                        param.thisObject.callMethod(
                            "finishRecentsAnimation",
                            true /* toRecents */,
                            false /* shouldPip */,
                            null
                        )
                    }
                )
            }
    }

    private fun View.folderShape(): Any? {
        val themeManager = findClass("com.android.launcher3.graphics.ThemeManager", suppressError = true)
            ?.getStaticFieldSilently("INSTANCE")
            ?.callMethodSilently("get", context)
            ?: return null
        return themeManager.getFieldSilently("iconState")?.getFieldSilently("folderShape")
    }

    private fun Any.cornerRadius(radius: Float): Float {
        (getFieldSilently("radiusRatio") as? Float)?.let { return radius * it }
        if (javaClass.simpleName.endsWith("Circle")) return radius

        shapeCorners[this]?.takeIf { it.first == radius }?.let { return it.second }
        val path = Path()
        if (!addShapeToPath(path, radius)) return radius
        val bounds = RectF().also { path.computeBounds(it, true) }
        if (bounds.isEmpty) return radius
        return WallpaperGlass.pathCorner(path, bounds).also { shapeCorners[this] = radius to it }
    }

    private fun Any.addShapeToPath(path: Path, radius: Float): Boolean {
        val method = addToPathMethods.getOrPut(javaClass) {
            javaClass.methods.firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "addToPath" && types.size == 4 && types.count { it == Path::class.java } == 1 &&
                        types.count { it == Float::class.javaPrimitiveType } == 3
            }
        } ?: return false
        val floats = arrayOf(0f, 0f, radius).iterator()
        val args = method.parameterTypes.map { if (it == Path::class.java) path else floats.next() }
        return runCatching { method.invoke(this, *args.toTypedArray()) }.isSuccess
    }

    private fun Context.folderBackgroundColor(): Int? {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val name = if (night) "folder_background_dark" else "folder_background_light"
        val id = ResourceIds.find(resources, name, "color", packageName).takeIf { it != 0 } ?: return null
        return runCatching { getColor(id) }.getOrNull()
    }

    private inner class LiveFolderPreview(
        val inner: Drawable,
        private val background: Drawable,
        private val host: View,
        private val folderIcon: View
    ) : Drawable() {

        private val rect = RectF()
        private val bounds = Rect()

        override fun draw(canvas: Canvas) {
            val manager = folderIcon.getFieldSilently("mPreviewItemManager")
            val hasBounds = folderIcon.getFieldSilently("mBackground").folderPreviewBounds(bounds)
            val previewSize = bounds.width()
            val offsetX = bounds.left
            val offsetY = bounds.top
            rect.set(background.bounds)
            rect.insetByRatio(folderIcon.previewRatio())
            if (manager == null || !hasBounds || rect.isEmpty) {
                inner.draw(canvas)
                return
            }

            val drawables = listOf("mCurrentPageParams", "mFirstPageParams")
                .flatMap { (manager.getFieldSilently(it) as? List<*>).orEmpty() }
                .mapNotNull { it?.getFieldSilently("drawable") as? Drawable }
                .distinct()
            val callbacks = drawables.map { it.callback }
            drawables.forEach { it.callback = host }

            val scale = rect.width() / previewSize
            val saved = canvas.save()
            canvas.translate(rect.left - offsetX * scale, rect.top - offsetY * scale)
            canvas.scale(scale, scale)
            runCatching { manager.callMethod("draw", canvas) }
            canvas.restoreToCount(saved)

            drawables.forEachIndexed { index, drawable -> drawable.callback = callbacks[index] }
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

    private fun RectF.insetByRatio(ratio: Float) {
        if (ratio >= 1f) return
        inset(width() * (1f - ratio) / 2f, height() * (1f - ratio) / 2f)
    }

    private fun View.previewRatio(): Float {
        val previewSize = getFieldSilently("mBackground")?.getFieldSilently("previewSize") as? Int ?: return 1f
        val iconSize = getFieldSilently("mFolderName")?.getFieldSilently("mIconSize") as? Int ?: return 1f
        if (previewSize <= 0 || iconSize <= 0) return 1f
        return (previewSize.toFloat() / iconSize).coerceIn(0.1f, 1f)
    }

    private inner class FloatingFolderGlass(
        val inner: Drawable,
        private val host: View,
        private val folderIcon: View
    ) : Drawable() {

        private val rect = RectF()
        private val shapePath = Path()

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            rect.insetByRatio(folderIcon.previewRatio())
            if (rect.isEmpty) {
                inner.draw(canvas)
                return
            }
            val corner = folderIcon.folderShape()?.cornerRadius(rect.width() / 2f) ?: (rect.width() / 2f)
            val glass = WallpaperGlass.canvasPaint(
                host = host,
                canvas = canvas,
                localRect = rect,
                corner = corner,
                blurRadius = folderBackgroundBlur * host.resources.displayMetrics.density,
                alpha = inner.alpha / 255f,
                liquid = folderLiquid
            )
            if (glass == null) {
                inner.draw(canvas)
                return
            }

            shapePath.reset()
            shapePath.addRoundRect(rect, corner, corner, Path.Direction.CW)
            val saved = canvas.save()
            canvas.clipPath(shapePath)
            canvas.drawPaint(glass)
            val base = host.context.folderBackgroundColor()
            if (base != null) {
                val tint = if (folderBackgroundOpacity >= 0) folderBackgroundOpacity else Color.alpha(base)
                tintPaint.color = ColorUtils.setAlphaComponent(base, tint * inner.alpha / 255)
                canvas.drawPaint(tintPaint)
            }
            canvas.restoreToCount(saved)
        }

        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    private fun folderGlass(): Boolean = folderBackgroundBlur > 0 || folderLiquid

    private fun View.showsFolderBackground(requireVisible: Boolean = true): Boolean {
        if (GestureMod.coversFolder(this)) return false
        return !requireVisible || getFieldSilently("mBackgroundIsVisible") != false
    }

    private fun View.folderIconBlur(): Float {
        val own = folderBackgroundBlur * resources.displayMetrics.density
        val inDrawer = tag.getFieldSilently("container") == CONTAINER_ALL_APPS
        return if (inDrawer) IconGlass.drawerGlassBlur(this, own) else own
    }

    private fun View.folderIconTint(): Int? {
        val background = getFieldSilently("mBackground")
        val context = background?.getFieldSilently("mContext") as? Context ?: context
        val base = context.takeIf { folderGlass() }?.folderBackgroundColor()
            ?: background?.getFieldSilently("mBgColor") as? Int
            ?: return null
        val alpha = if (folderBackgroundOpacity >= 0) folderBackgroundOpacity else Color.alpha(base)
        return if (alpha == 0) null else ColorUtils.setAlphaComponent(base, alpha)
    }

    private fun View.blurTargets(dragLayer: ViewGroup): List<View> {
        if (getFieldSilently("mInfo").getFieldSilently("container") == CONTAINER_ALL_APPS) {
            var launcher: Context? = context
            while (launcher is ContextWrapper && launcher !is Activity) launcher = launcher.baseContext
            return listOfNotNull(launcher.getFieldSilently("mAppsView") as? View)
        }
        return (0 until dragLayer.childCount)
            .map { dragLayer.getChildAt(it) }
            .filter { it !== this && BLUR_TARGET.containsMatchIn(it.javaClass.name) }
    }

    private fun animateFolderBlur(target: Float, source: Animator?) {
        val frosted = folderFrost ?: return
        val previous = folderBlurAnimator
        folderBlurAnimator = null
        previous?.cancel()

        val duration = ((source as? AnimatorSet)?.totalDuration ?: source?.duration)
            ?.takeIf { it > 0 }
            ?: DEFAULT_FOLDER_ANIMATION_DURATION

        folderBlurAnimator = ValueAnimator.ofFloat(frosted.progress, target).apply {
            this.duration = duration
            interpolator = source?.interpolator ?: DecelerateInterpolator()
            addUpdateListener { frosted.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    BlurPanel.refreshAllSoon()
                    if (folderBlurAnimator !== animation) return
                    folderBlurAnimator = null
                    if (target == 0f) clearFolderBlur() else if (frosted.home) WallpaperGlass.onOffsetsChanged()
                }
            })
            start()
        }
    }

    private fun clearFolderBlur() {
        val previous = folderBlurAnimator
        folderBlurAnimator = null
        previous?.cancel()
        val drawerFrost = folderFrost?.home == true
        folderFrost?.stop()
        folderFrost = null
        BlurPanel.refreshAllSoon()
        if (drawerFrost) WallpaperGlass.onOffsetsChanged()
    }

    private fun getScrimColors(
        scrimColors: Any,
        scrimColorsClass: Class<*>,
        backgroundOpacity: Int
    ): Any {
        val backgroundColor = scrimColors.callMethodSilently("getBackgroundColor") as? Int
            ?: scrimColors.getField("backgroundColor") as Int
        val foregroundColor = scrimColors.callMethodSilently("getForegroundColor") as? Int
            ?: scrimColors.getField("foregroundColor") as Int

        return scrimColorsClass
            .getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .newInstance(
                ColorUtils.setAlphaComponent(backgroundColor, backgroundOpacity),
                foregroundColor
            )
    }

    companion object {
        private var instance: WeakReference<OpacityModifier>? = null

        fun folderFrost(folder: View): FolderFrost? = folder.getExtraFieldSilently(FOLDER_FROST) as? FolderFrost

        fun drawerFrostActive(): Boolean = instance?.get()?.folderFrost?.let { it.home && it.progress > 0f } == true

        fun drawerFrostAnimating(): Boolean = instance?.get()?.let { it.folderFrost?.home == true && it.folderBlurAnimator?.isRunning == true } == true

        fun targetsWholeFolder(floating: View): Boolean {
            val info = floating.getFieldSilently("mIconLoadResult")?.getFieldSilently("itemInfo") ?: return true
            return info.javaClass.name.endsWith("FolderInfo")
        }

        fun folderIconBlur(folderIcon: View, requireVisible: Boolean = true): Float? {
            val modifier = instance?.get() ?: return null
            return with(modifier) {
                folderIcon.takeIf { folderGlass() && it.showsFolderBackground(requireVisible) }?.folderIconBlur()
            }
        }

        fun folderIconTint(folderIcon: View, requireVisible: Boolean = true): Int? {
            val modifier = instance?.get() ?: return null
            return with(modifier) {
                folderIcon.takeIf { it.showsFolderBackground(requireVisible) }?.folderIconTint()
            }
        }

        private const val CONTAINER_ALL_APPS = -104
        private const val PREVIEW_ORIGINAL_COLOR = "plePreviewOriginalColor"
        private const val FOLDER_FROST = "pleFolderFrost"
        private const val DEFAULT_FOLDER_BLUR = 5
        private const val DEFAULT_FOLDER_TINT = 40
        private const val BACKGROUND_OFF = "off"
        private const val BACKGROUND_LIQUID = "liquid"
        private const val PREVIEW_BLUR = "plePreviewBlur"
        private const val DEFAULT_FOLDER_ANIMATION_DURATION = 300L
        private val BLUR_TARGET = Regex("Workspace|Hotseat|PageIndicator|Qsb|SearchContainer")
    }
}
