package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_RECENTS_LIVE_TILE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_STYLE
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.RecentsCardStyle
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.RecentsCardStyle.Companion.STYLE_DEFAULT
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.TaskDimDrawable
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callStaticMethodSilently
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

class RecentsStyles(context: Context) : ModPack(context) {

    private var style = STYLE_DEFAULT
    private var liveTileDisabled = false
    private var deoptimized = false
    private var finishTakesPip = true
    private var cornerRadius: Float? = null
    private val framedViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val leavingCards = WeakHashMap<View, Float>()
    private var leaveProgress = 0f
    private var leaveAnimator: ValueAnimator? = null
    private val visibleRect = RectF()
    private var taskViewClass: Class<*>? = null
    private var recentsViewRef: WeakReference<ViewGroup>? = null
    private var recentsMembers: RecentsMembers? = null
    private var dismissFieldX: Field? = null
    private var dismissFieldY: Field? = null
    private var lastScroll = Int.MIN_VALUE
    private var snappingView: WeakReference<ViewGroup>? = null
    private var lastHandler: Any? = null
    private var lastHorizontal = true
    private val cardStyle = RecentsCardStyle()
    private val states = WeakHashMap<View, CardState>()
    private val dims = WeakHashMap<View, TaskDimDrawable>()
    private val overlapInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private class RecentsMembers(type: Class<*>) {
        val showAsGrid = findMethod(type, "showAsGrid")
        val orientationHandler = findMethod(type, "getPagedOrientationHandler")
        val fullscreenProgress = Reflection.findFieldIfExists(type, "mFullscreenProgress")
        val isRtl = Reflection.findFieldIfExists(type, "mIsRtl")
        val pageSpacing = Reflection.findFieldIfExists(type, "mPageSpacing")
        val pageScrolls = Reflection.findFieldIfExists(type, "mPageScrolls")
        val scroller = Reflection.findFieldIfExists(type, "mScroller")
    }

    private class CardState {
        var scale = 1f
        var translation = 0f
        var horizontal = true
        var addedScale = 1f
        var addedX = 0f
        var addedY = 0f
        var appliedScale = Float.NaN
        var appliedX = Float.NaN
        var appliedY = Float.NaN
        var naturalPosition = Float.NaN
        var lastDistance = Float.NaN
        var positionResidual = 0f
        var distanceResidual = 0f
        var residualAt = 0L
        var holdUntil = 0L
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            style = getListString(RECENTS_STYLE, STYLE_DEFAULT) ?: STYLE_DEFAULT
            liveTileDisabled = getBoolean(DISABLE_RECENTS_LIVE_TILE, false)
        }

        if (key.firstOrNull() == RECENTS_STYLE) {
            Handler(Looper.getMainLooper()).post { recentsViewRef?.get()?.let { refresh(it) } }
        }
    }

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
        val recentsViewClass = findClass("com.android.quickstep.views.RecentsView") ?: return
        val taskView = findClass("com.android.quickstep.views.TaskView") ?: return
        taskViewClass = taskView
        finishTakesPip = recentsViewClass.declaredMethods.any {
            it.name == "finishRecentsAnimation" && it.parameterCount == 3
        }

        taskView
            .hookMethod("applyScale", "applyTranslationX", "applyTranslationY")
            .suppressError()
            .runAfter { param ->
                val view = param.thisObject as View
                states[view]?.let { write(view, it) }
            }

        taskView
            .hookMethod("onClick")
            .suppressError()
            .runBefore { param ->
                if (!RecentsCardStyle.isStack(style)) return@runBefore
                val view = param.thisObject as View
                val recentsView = view.callMethodSilently("getRecentsView") as? ViewGroup ?: return@runBefore
                if (!isActive(recentsView)) return@runBefore
                val index = recentsView.indexOfChild(view)
                if (index >= 0 && !isCurrent(recentsView, view)) {
                    recentsView.callMethodSilently("snapToPage", index)
                    snappingView = WeakReference(recentsView)
                    param.result = null
                    return@runBefore
                }
                hideStack(recentsView, view)
            }

        findClass("com.android.launcher3.PagedView")
            .hookMethod("onInterceptTouchEvent")
            .suppressError()
            .runBefore { param ->
                val recentsView = snappingView?.get() ?: return@runBefore
                if (param.thisObject !== recentsView) return@runBefore
                if ((param.args.getOrNull(0) as? MotionEvent)?.actionMasked != MotionEvent.ACTION_DOWN) return@runBefore
                snappingView = null
                finishSnap(recentsView)
            }

        recentsViewClass
            .hookMethod("isTaskViewVisible")
            .suppressError()
            .runAfter { param ->
                if (param.result != false || !RecentsCardStyle.isStack(style)) return@runAfter
                val recentsView = param.thisObject as ViewGroup
                val child = param.args.getOrNull(0) as? View ?: return@runAfter
                if (states[child] != null && isActive(recentsView) && isStackVisible(recentsView, child)) {
                    param.result = true
                }
            }

        recentsViewClass
            .hookMethod("updatePageOffsets", "setFullscreenProgress", "resetTaskVisuals")
            .suppressError()
            .runAfter { param -> refresh(param.thisObject as ViewGroup) }

        recentsViewClass
            .hookMethod("onScrollChanged")
            .suppressError()
            .runAfter { param ->
                if (style == STYLE_DEFAULT && states.isEmpty()) return@runAfter
                val recentsView = param.thisObject as ViewGroup
                if (primaryScroll(recentsView, isHorizontal(recentsView)) != lastScroll) refresh(recentsView)
            }

        recentsViewClass
            .hookMethod("onGestureAnimationStart", "reset")
            .suppressError()
            .runBefore { expandStack() }

        recentsViewClass
            .hookMethod("setOverviewStateEnabled")
            .suppressError()
            .runBefore { param -> if (param.args.getOrNull(0) == true) expandStack() }

        recentsViewClass
            .hookMethod("onGestureAnimationEnd")
            .suppressError()
            .runAfter { param ->
                val recentsView = param.thisObject as ViewGroup
                if (liveTileDisabled || !isActive(recentsView)) return@runAfter
                recentsView.callMethodSilently("switchToScreenshot", Runnable {
                    if (finishTakesPip) {
                        recentsView.callMethodSilently("finishRecentsAnimation", true, false, null)
                    } else {
                        recentsView.callMethodSilently("finishRecentsAnimation", true, null)
                    }
                })
            }
    }

    private fun members(recentsView: ViewGroup): RecentsMembers =
        recentsMembers ?: RecentsMembers(recentsView.javaClass).also { recentsMembers = it }

    private fun isActive(recentsView: ViewGroup): Boolean =
        style != STYLE_DEFAULT &&
                recentsView.resources.configuration.smallestScreenWidthDp < TABLET_WIDTH_DP &&
                runCatching { members(recentsView).showAsGrid?.invoke(recentsView) }.getOrNull() != true

    private fun isHorizontal(recentsView: ViewGroup): Boolean {
        val handler = runCatching { members(recentsView).orientationHandler?.invoke(recentsView) }.getOrNull()
        if (handler != null && handler === lastHandler) return lastHorizontal
        lastHandler = handler
        lastHorizontal = handler?.callMethodSilently("getPrimaryValue", 1f, 0f) as? Float != 0f
        return lastHorizontal
    }

    private fun primaryScroll(recentsView: ViewGroup, horizontal: Boolean): Int =
        if (horizontal) recentsView.scrollX else recentsView.scrollY

    private fun finishSnap(recentsView: ViewGroup) {
        val nextPage = recentsView.callMethodSilently("getNextPage") as? Int ?: return
        val scroller = runCatching { members(recentsView).scroller?.get(recentsView) as? OverScroller }.getOrNull()
        val running = scroller?.isFinished == false
        if (!running && recentsView.callMethodSilently("getCurrentPage") == nextPage) return
        if (running) recentsView.callMethodSilently("abortScrollerAnimation", true)
        recentsView.callMethodSilently("setCurrentPage", nextPage)
        refresh(recentsView)
    }

    private fun isCurrent(recentsView: ViewGroup, view: View): Boolean {
        val index = recentsView.indexOfChild(view)
        return index == recentsView.callMethodSilently("getNextPage") ||
                index == recentsView.callMethodSilently("getCurrentPage")
    }

    private fun hideStack(recentsView: ViewGroup, launching: View) {
        val horizontal = isHorizontal(recentsView)
        val center = (if (horizontal) recentsView.width else recentsView.height) / 2f
        val scroll = primaryScroll(recentsView, horizontal)
        for (index in 0 until recentsView.childCount) {
            val child = recentsView.getChildAt(index)
            if (child === launching || states[child]?.translation?.let { it != 0f } != true) continue
            val start = (if (horizontal) child.left else child.top) - scroll
            val size = if (horizontal) child.width else child.height
            leavingCards[child] = if (start + size / 2f < center) -1f else 1f
        }
        if (leavingCards.isEmpty()) return
        leaveAnimator?.cancel()
        leaveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LEAVE_DURATION
            interpolator = overlapInterpolator
            addUpdateListener {
                leaveProgress = it.animatedValue as Float
                leavingCards.keys.forEach { card -> card.alpha = 1f - leaveProgress }
                refresh(recentsView)
            }
            start()
        }
    }

    private fun expandStack() {
        leaveAnimator?.cancel()
        leaveAnimator = null
        leavingCards.keys.forEach { it.alpha = 1f }
        leavingCards.clear()
        leaveProgress = 0f
    }

    private fun isStackVisible(recentsView: ViewGroup, child: View): Boolean {
        visibleRect.set(0f, 0f, child.width.toFloat(), child.height.toFloat())
        child.matrix.mapRect(visibleRect)
        visibleRect.offset((child.left - recentsView.scrollX).toFloat(), (child.top - recentsView.scrollY).toFloat())
        return visibleRect.right > 0f && visibleRect.left < recentsView.width &&
                visibleRect.bottom > 0f && visibleRect.top < recentsView.height
    }

    private fun overlapFactor(recentsView: ViewGroup): Float {
        val fullscreen = runCatching { members(recentsView).fullscreenProgress?.getFloat(recentsView) }.getOrNull() ?: 0f
        if (fullscreen >= FULLSCREEN_THRESHOLD) return 0f
        val progress = (1f - fullscreen / FULLSCREEN_THRESHOLD).coerceIn(0f, 1f)
        return overlapInterpolator.getInterpolation(progress)
    }

    private fun attachFrameRefresh(recentsView: ViewGroup) {
        if (!framedViews.add(recentsView)) return
        recentsView.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener {
            if (style != STYLE_DEFAULT || states.isNotEmpty()) refresh(recentsView)
            true
        })
    }

    private fun refresh(recentsView: ViewGroup) {
        recentsViewRef = WeakReference(recentsView)
        attachFrameRefresh(recentsView)
        val active = isActive(recentsView)
        if (!active && states.isEmpty()) return
        val taskView = taskViewClass ?: return
        if (active) deoptimizeCallers(taskView)

        val members = members(recentsView)
        val horizontal = isHorizontal(recentsView)
        val scroll = primaryScroll(recentsView, horizontal)
        lastScroll = scroll
        val rtl = runCatching { members.isRtl?.getBoolean(recentsView) }.getOrNull() ?: false
        val spacing = (runCatching { members.pageSpacing?.getInt(recentsView) }.getOrNull() ?: 0).toFloat()
        val pageScrolls = runCatching { members.pageScrolls?.get(recentsView) as? IntArray }.getOrNull()
        val progress = if (active) overlapFactor(recentsView) else 0f
        val corner = cornerRadius ?: (findClass("com.android.quickstep.util.TaskCornerRadius", suppressError = true)
            .callStaticMethodSilently("get", recentsView.context) as? Float ?: 0f)
            .also { cornerRadius = it }

        val now = SystemClock.uptimeMillis()
        val stack = active && RecentsCardStyle.isStack(style)
        val flinging = runCatching { (members.scroller?.get(recentsView) as? OverScroller)?.isFinished == false }
            .getOrNull() == true
        var settling = false

        for (index in 0 until recentsView.childCount) {
            val child = recentsView.getChildAt(index)
            if (!taskView.isInstance(child)) continue
            val dismiss = dismissTranslation(child, horizontal)
            val size = (if (horizontal) child.width else child.height).toFloat()
            val hasScroll = active && pageScrolls != null && index < pageScrolls.size
            var distance = if (hasScroll) pageScrolls[index] - scroll + dismiss else 0f
            var shift = 0f
            if (stack && hasScroll) {
                val natural = (if (horizontal) child.left else child.top) + dismiss - scroll
                val state = settle(child, natural, distance, size + spacing, flinging, now)
                distance += state.distanceResidual
                shift = state.positionResidual
                if (state.distanceResidual != 0f || shift != 0f) settling = true
            }
            cardStyle.update(style, distance, size, spacing, if (hasScroll) progress else 0f, rtl)
            val leave = leavingCards[child]?.let { it * size * LEAVE_DISTANCE * leaveProgress } ?: 0f
            apply(child as ViewGroup, index, active, horizontal, corner, shift + leave)
        }
        if (settling) recentsView.postInvalidateOnAnimation()
    }

    private fun settle(
        child: View,
        natural: Float,
        distance: Float,
        pageSize: Float,
        flinging: Boolean,
        now: Long
    ): CardState {
        val state = states[child] ?: CardState().also { states[child] = it }
        if (now > state.holdUntil) {
            val decay = exp(-(now - max(state.residualAt, state.holdUntil)) / SETTLE_TIME_CONSTANT)
            state.positionResidual = settled(state.positionResidual * decay)
            state.distanceResidual = settled(state.distanceResidual * decay)
        }
        val threshold = pageSize * JUMP_FRACTION
        val positionJump = natural - state.naturalPosition
        val distanceJump = distance - state.lastDistance
        if (!flinging && !positionJump.isNaN() && abs(positionJump) > threshold) {
            state.positionResidual -= positionJump
            state.holdUntil = now + SETTLE_HOLD_MS
        }
        if (!flinging && !distanceJump.isNaN() && abs(distanceJump) > threshold) {
            state.distanceResidual -= distanceJump
            state.holdUntil = now + SETTLE_HOLD_MS
        }
        state.naturalPosition = natural
        state.lastDistance = distance
        state.residualAt = now
        return state
    }

    private fun settled(value: Float): Float = if (abs(value) < SETTLE_MIN) 0f else value

    private fun dismissTranslation(child: View, horizontal: Boolean): Float {
        val field = if (horizontal) {
            dismissFieldX ?: DISMISS_X.firstNotNullOfOrNull { Reflection.findFieldIfExists(child.javaClass, it) }
                ?.also { dismissFieldX = it }
        } else {
            dismissFieldY ?: DISMISS_Y.firstNotNullOfOrNull { Reflection.findFieldIfExists(child.javaClass, it) }
                ?.also { dismissFieldY = it }
        } ?: return 0f
        return runCatching { field.getFloat(child) }.getOrNull() ?: 0f
    }

    private fun apply(
        child: ViewGroup,
        index: Int,
        active: Boolean,
        horizontal: Boolean,
        corner: Float,
        residual: Float
    ) {
        val state = states[child]

        if (!active) {
            if (state == null) return
            state.scale = 1f
            state.translation = 0f
            write(child, state)
            states.remove(child)
            child.elevation = 0f
            updateDim(child, 0f, corner)
            return
        }

        val current = state ?: CardState().also { states[child] = it }
        current.scale = cardStyle.scale
        current.translation = cardStyle.translation + residual
        current.horizontal = horizontal
        write(child, current)
        child.elevation = cardStyle.elevation - index * Z_TIE
        updateDim(child, cardStyle.dim, corner)
    }

    private fun write(view: View, state: CardState) {
        if (view.scaleX != state.appliedScale) state.addedScale = 1f
        if (view.translationX != state.appliedX) state.addedX = 0f
        if (view.translationY != state.appliedY) state.addedY = 0f

        if (state.addedScale != state.scale) {
            view.scaleX = view.scaleX / state.addedScale * state.scale
            view.scaleY = view.scaleY / state.addedScale * state.scale
            state.addedScale = state.scale
        }
        val targetX = if (state.horizontal) state.translation else 0f
        val targetY = if (state.horizontal) 0f else state.translation
        if (state.addedX != targetX) {
            view.translationX = view.translationX - state.addedX + targetX
            state.addedX = targetX
        }
        if (state.addedY != targetY) {
            view.translationY = view.translationY - state.addedY + targetY
            state.addedY = targetY
        }

        state.appliedScale = view.scaleX
        state.appliedX = view.translationX
        state.appliedY = view.translationY
    }

    private fun updateDim(child: ViewGroup, amount: Float, corner: Float) {
        val existing = dims[child]
        if (amount <= 0f) {
            existing?.let {
                it.amount = 0f
                child.overlay.remove(it)
                dims.remove(child)
            }
            return
        }
        val drawable = existing ?: TaskDimDrawable(child, corner).also {
            dims[child] = it
            child.overlay.add(it)
        }
        drawable.setBounds(0, 0, child.width, child.height)
        drawable.amount = amount
    }

    private fun deoptimizeCallers(taskView: Class<*>) {
        if (deoptimized) return
        deoptimized = true
        taskView.declaredMethods
            .filter { method ->
                !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers) &&
                        method.name !in APPLY_METHODS &&
                        CALLER_PATTERN.matches(method.name)
            }
            .forEach { runCatching { ModuleRuntime.module.deoptimize(it) } }
        VISIBILITY_CALLERS.forEach { (className, methodName) ->
            findClass(className, suppressError = true)?.declaredMethods
                ?.filter { it.name == methodName && !Modifier.isAbstract(it.modifiers) }
                ?.forEach { runCatching { ModuleRuntime.module.deoptimize(it) } }
        }
    }

    companion object {
        private fun findMethod(type: Class<*>, name: String): Method? {
            var current: Class<*>? = type
            while (current != null) {
                current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let {
                    it.isAccessible = true
                    return it
                }
                current = current.superclass
            }
            return null
        }

        private const val FULLSCREEN_THRESHOLD = 0.35f
        private const val LEAVE_DURATION = 300L
        private const val LEAVE_DISTANCE = 1.2f
        private val VISIBILITY_CALLERS = listOf(
            "com.android.quickstep.views.RecentsDismissUtils" to "dismissAllTasks",
            "com.android.quickstep.views.RecentsDismissUtils" to "buildDismissReflowSpringAnimationChain",
            "com.android.quickstep.views.RecentsView" to "onTouchEvent",
            "com.android.quickstep.views.RecentsView" to "getHighestVisibleTaskView",
            "com.android.quickstep.TaskViewUtils" to "findTaskViewToLaunch",
            "com.android.launcher3.uioverrides.touchcontrollers.TaskViewDismissTouchController" to "onControllerInterceptTouchEvent",
            "com.android.launcher3.uioverrides.touchcontrollers.TaskViewLaunchTouchController" to "onControllerInterceptTouchEvent",
            "com.android.launcher3.uioverrides.touchcontrollers.TaskViewTouchController" to "onControllerInterceptTouchEvent"
        )
        private const val SETTLE_TIME_CONSTANT = 80f
        private const val SETTLE_MIN = 0.5f
        private const val SETTLE_HOLD_MS = 80L
        private const val JUMP_FRACTION = 0.5f
        private val DISMISS_X = listOf("dismissTranslationX", "mDismissTranslationX")
        private val DISMISS_Y = listOf("dismissTranslationY", "mDismissTranslationY")
        private const val Z_TIE = 0.5f
        private const val TABLET_WIDTH_DP = 600
        private val APPLY_METHODS = listOf("applyScale", "applyTranslationX", "applyTranslationY")
        private val CALLER_PATTERN =
            Regex("^(access\\$)?(set|reset|on|update).*(Translation|Scale|Progress|Modal|Grid|Fullscreen|Transforms|Layout|Orient|Dismiss).*")
    }
}
