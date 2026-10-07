package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.children
import androidx.core.view.isVisible
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FIXED_RECENTS_BUTTONS_WIDTH
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_CLEAR_ALL_BUTTON
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_DISABLE_SELECTION
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_REMOVE_SCREENSHOT_BUTTON
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.MethodHookHelper
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers.findMethodBestMatch
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method

class ClearAllButton(context: Context) : ModPack(context) {

    private var clearAllButton = false
    private var fixedButtonWidth = false
    private var removeScreenshotButton = false
    private var disableSelection = false
    private val hookedOverlayClasses = mutableSetOf<Class<*>>()
    private var recentsViewInstance: Any? = null
    private var actionClearAllButton: Button? = null
    private var actionButtonsRef: WeakReference<ViewGroup>? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            clearAllButton = getBoolean(RECENTS_CLEAR_ALL_BUTTON, false)
            fixedButtonWidth = clearAllButton && getBoolean(FIXED_RECENTS_BUTTONS_WIDTH, false)
            removeScreenshotButton = getBoolean(RECENTS_REMOVE_SCREENSHOT_BUTTON, false)
            disableSelection = getBoolean(RECENTS_DISABLE_SELECTION, false)
        }

        when (key.firstOrNull()) {
            RECENTS_CLEAR_ALL_BUTTON,
            FIXED_RECENTS_BUTTONS_WIDTH,
            RECENTS_REMOVE_SCREENSHOT_BUTTON,
            RECENTS_DISABLE_SELECTION -> Handler(Looper.getMainLooper()).post { updateVisibility() }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val recentsViewClass = findClass("com.android.quickstep.views.RecentsView")
        val overviewModalTaskStateClass =
            findClass("com.android.launcher3.uioverrides.states.OverviewModalTaskState")
        val backgroundAppStateClass =
            findClass("com.android.launcher3.uioverrides.states.BackgroundAppState")
        val overviewStateClass =
            findClass("com.android.launcher3.uioverrides.states.OverviewState")
        val featureFlagsClass = findClass("com.android.launcher3.config.FeatureFlags", suppressError = true)
        val recentsStateClass = findClass("com.android.quickstep.fallback.RecentsState")
        val overviewActionsViewClass = findClass("com.android.quickstep.views.OverviewActionsView")
        val dismissAllTasksMethod: Method =
            findMethodBestMatch(recentsViewClass, "dismissAllTasks", View::class.java)

        recentsViewClass
            .hookConstructor()
            .runAfter { param ->
                recentsViewInstance = param.thisObject
            }

        val taskOverlayClass = findClass(
            $$"com.android.quickstep.TaskOverlayFactory$TaskOverlay",
            suppressError = true
        )

        taskOverlayClass
            .hookConstructor()
            .runAfter { param ->
                val overlayClass = param.thisObject::class.java
                if (overlayClass == taskOverlayClass || !hookedOverlayClasses.add(overlayClass)) {
                    return@runAfter
                }

                overlayClass.declaredMethods
                    .filter { it.name == "initOverlay" && it.parameterTypes.lastOrNull() == Boolean::class.javaPrimitiveType }
                    .forEach { method ->
                        MethodHookHelper(method)
                            .runBefore { param2 ->
                                if (!disableSelection) return@runBefore

                                param2.args[param2.args.size - 1] = true
                            }
                    }
            }

        backgroundAppStateClass
            .hookMethod("getVisibleElements")
            .runAfter { param ->
                if (!clearAllButton) return@runAfter

                val result = param.result as Int
                param.result = result and CLEAR_ALL_BUTTON.inv()
            }

        overviewModalTaskStateClass
            .hookMethod("getVisibleElements")
            .runBefore { param ->
                if (!clearAllButton) return@runBefore

                param.result = OVERVIEW_ACTIONS
            }

        overviewStateClass
            .hookMethod("getVisibleElements")
            .runBefore { param ->
                if (!clearAllButton) return@runBefore

                val launcher = param.args[0]
                var elements: Int = OVERVIEW_ACTIONS
                val deviceProfile = launcher.callMethodSilently("getDeviceProfile")
                    ?: launcher
                        .getField("deviceProfileRef")
                        .getField("value")
                val isPhone = deviceProfile.getFieldSilently("isPhone") as? Boolean
                    ?: deviceProfile
                        .getField("mDeviceProperties")
                        .getField("isPhone") as Boolean
                val isLandscape = deviceProfile.getFieldSilently("isLandscape") as? Boolean
                    ?: deviceProfile
                        .getField("mDeviceProperties")
                        .getField("isLandscape") as Boolean

                val showFloatingSearch = if (isPhone) {
                    // Only show search in phone overview in portrait mode.
                    !isLandscape
                } else {
                    // Only show search in tablet overview if taskbar is not visible.
                    !(deviceProfile.getField("isTaskbarPresent") as Boolean) ||
                            param.thisObject.callMethod("isTaskbarStashed", launcher) as Boolean
                }

                if (showFloatingSearch) {
                    elements = elements or FLOATING_SEARCH_BAR
                }

                val splitContextual = featureFlagsClass.callStaticMethodSilently("enableSplitContextual") as? Boolean ?: true
                val splitSelecting = runCatching {
                    if (launcher.hasMethod("isSplitSelectionActive")) {
                        launcher.callMethod("isSplitSelectionActive") as Boolean
                    } else {
                        launcher
                            .getField("isSplitSelectActiveRef")
                            .getField("value")
                            .callMethod("booleanValue") as Boolean
                    }
                }.getOrDefault(false)

                if (splitContextual && splitSelecting) {
                    elements = elements and CLEAR_ALL_BUTTON.inv()
                }

                param.result = elements
            }

        recentsStateClass
            .hookMethod("hasClearAllButton")
            .runBefore { param ->
                if (!clearAllButton) return@runBefore

                param.result = false
            }

        overviewActionsViewClass
            .hookMethod("onFinishInflate")
            .runAfter { param ->
                val mActionButtons =
                    param.thisObject.getFieldSilently("mActionButtons") as? LinearLayout
                        ?: (param.thisObject as ViewGroup).findViewById(
                            mContext.resources.getIdentifier(
                                "action_buttons",
                                "id",
                                mContext.packageName
                            )
                        )

                val contextThemeWrapper = ContextThemeWrapper(
                    mActionButtons.context,
                    mContext.resources.getIdentifier(
                        "ThemeControlHighlightWorkspaceColor",
                        "style",
                        mContext.packageName
                    )
                )

                actionClearAllButton = Button(
                    contextThemeWrapper,
                    null,
                    0,
                    mContext.resources.getIdentifier(
                        "OverviewActionButton",
                        "style",
                        mContext.packageName
                    )
                ).apply {
                    id = View.generateViewId()
                    text = modRes.getString(R.string.recents_clear_all)
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val spacingId = mContext.resources.getIdentifier(
                                "overview_actions_button_spacing",
                                "dimen",
                                mContext.packageName
                            )
                        if (spacingId != 0) {
                            marginStart = mContext.resources.getDimensionPixelSize(spacingId)
                        }
                    }
                    setCompoundDrawablesWithIntrinsicBounds(
                        modRes.getDrawable(R.drawable.ic_clear_all),
                        null,
                        null,
                        null
                    )
                    setOnClickListener { view ->
                        dismissAllTasksMethod.invoke(recentsViewInstance, view)
                    }
                }

                val referenceButton = mActionButtons.children
                    .filterIsInstance<Button>()
                    .firstOrNull { it !== actionClearAllButton }
                referenceButton?.background?.constantState?.newDrawable()?.let { drawable ->
                    actionClearAllButton?.background = drawable.mutate()
                }

                mActionButtons.addView(actionClearAllButton)
                actionButtonsRef = WeakReference(mActionButtons)

                updateVisibility()
            }
    }

    private fun updateVisibility() {
        val row = actionButtonsRef?.get() ?: return
        actionClearAllButton?.visibility = if (clearAllButton) View.VISIBLE else View.GONE

        var childCount = if (clearAllButton) 3 else 2
        if (removeScreenshotButton) childCount--
        if (disableSelection) childCount--
        childCount = childCount.coerceAtLeast(2)

        if (fixedButtonWidth) wrapButtons(row) else unwrapButtons(row)

        val maxWidth = mContext.resources.displayMetrics.widthPixels / childCount
        row.children.forEach { child ->
            if (child is Button) child.maxWidth = maxWidth
        }

        row.setHidden("action_screenshot", removeScreenshotButton)
        row.setHidden("action_select", disableSelection)
    }

    private fun wrapButtons(row: ViewGroup) {
        if (row.getExtraFieldSilently(WRAPPED) == true) return
        row.setExtraField(WRAPPED, true)

        row.setExtraField(ORIGINAL_WIDTH, row.layoutParams?.width)
        row.layoutParams?.width = ViewGroup.LayoutParams.MATCH_PARENT

        row.children.toList().forEach { child ->
            if (child is Button) {
                child.setExtraField(ORIGINAL_MARGIN, (child.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart)
                child.setExtraField(ORIGINAL_MAX_LINES, child.maxLines)
                child.setExtraField(ORIGINAL_ELLIPSIZE, child.ellipsize)
                child.maxLines = 1
                child.ellipsize = TextUtils.TruncateAt.END

                val container = LinearLayout(mContext).apply {
                    tag = CONTAINER_TAG
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                }
                val index = row.indexOfChild(child)
                row.removeView(child)
                container.addView(child)
                row.addView(container, index)

                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = 0

                container.visibility = if (child.isVisible) View.VISIBLE else View.GONE
                container.setExtraField(
                    LAYOUT_LISTENER,
                    child.addVisibilityListener { isVisible ->
                        container.visibility = if (isVisible) View.VISIBLE else View.GONE
                    }
                )
            } else if (child.tag != CONTAINER_TAG) {
                child.setExtraField(ORIGINAL_VISIBILITY, child.visibility)
                child.visibility = View.GONE
                child.setExtraField(
                    LAYOUT_LISTENER,
                    child.addVisibilityListener { isVisible -> if (isVisible) child.visibility = View.GONE }
                )
            }
        }
    }

    private fun unwrapButtons(row: ViewGroup) {
        if (row.getExtraFieldSilently(WRAPPED) != true) return
        row.setExtraField(WRAPPED, false)

        row.children.toList().forEach { child ->
            if (child.tag == CONTAINER_TAG && child is ViewGroup) {
                child.removeLayoutListener()
                val button = child.getChildAt(0) as? Button ?: return@forEach
                val index = row.indexOfChild(child)
                child.removeView(button)
                row.removeView(child)
                row.addView(button, index)

                (button.getExtraFieldSilently(ORIGINAL_MARGIN) as? Int)?.let {
                    (button.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = it
                }
                (button.getExtraFieldSilently(ORIGINAL_MAX_LINES) as? Int)?.let { button.maxLines = it }
                button.ellipsize = button.getExtraFieldSilently(ORIGINAL_ELLIPSIZE) as? TextUtils.TruncateAt
            } else {
                child.removeLayoutListener()
                (child.getExtraFieldSilently(ORIGINAL_VISIBILITY) as? Int)?.let { child.visibility = it }
            }
        }

        (row.getExtraFieldSilently(ORIGINAL_WIDTH) as? Int)?.let { row.layoutParams?.width = it }
        row.requestLayout()
    }

    @SuppressLint("DiscouragedApi")
    private fun ViewGroup.setHidden(idName: String, hidden: Boolean) {
        val id = mContext.resources.getIdentifier(idName, "id", mContext.packageName)
        if (id == 0) return
        val view = findViewById<View>(id) ?: return
        val listener = view.getExtraFieldSilently(HIDE_LISTENER)

        if (hidden) {
            if (listener == null) {
                view.setExtraField(STOCK_VISIBILITY, view.visibility)
                view.setExtraField(
                    HIDE_LISTENER,
                    view.addVisibilityListener { isVisible ->
                        if (isVisible) {
                            view.setExtraField(STOCK_VISIBILITY, View.VISIBLE)
                            view.visibility = View.GONE
                        }
                    }
                )
            }
            view.visibility = View.GONE
        } else if (listener is ViewTreeObserver.OnGlobalLayoutListener) {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            view.setExtraField(HIDE_LISTENER, null)
            view.visibility = view.getExtraFieldSilently(STOCK_VISIBILITY) as? Int ?: View.VISIBLE
        }
    }

    private fun View.addVisibilityListener(onVisibilityChanged: (Boolean) -> Unit): ViewTreeObserver.OnGlobalLayoutListener {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { onVisibilityChanged(isVisible) }
        viewTreeObserver.addOnGlobalLayoutListener(listener)
        return listener
    }

    private fun View.removeLayoutListener() {
        (getExtraFieldSilently(LAYOUT_LISTENER) as? ViewTreeObserver.OnGlobalLayoutListener)?.let {
            viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        setExtraField(LAYOUT_LISTENER, null)
    }

    companion object {
        private const val CONTAINER_TAG = "action_button_container"
        private const val WRAPPED = "pleButtonsWrapped"
        private const val ORIGINAL_WIDTH = "pleOriginalWidth"
        private const val ORIGINAL_MARGIN = "pleOriginalMargin"
        private const val ORIGINAL_MAX_LINES = "pleOriginalMaxLines"
        private const val ORIGINAL_ELLIPSIZE = "pleOriginalEllipsize"
        private const val ORIGINAL_VISIBILITY = "pleOriginalVisibility"
        private const val STOCK_VISIBILITY = "pleStockVisibility"
        private const val LAYOUT_LISTENER = "pleLayoutListener"
        private const val HIDE_LISTENER = "pleHideListener"
        private const val OVERVIEW_ACTIONS = 1 shl 3
        private const val CLEAR_ALL_BUTTON = 1 shl 4
        private const val FLOATING_SEARCH_BAR = 1 shl 7
    }
}