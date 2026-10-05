package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS_ENABLED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_MODE
import com.drdisagree.pixellauncherenhanced.data.model.DrawerTab
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate
import kotlin.math.abs

class AppDrawerTabs(context: Context) : ModPack(context) {

    private var tabsEnabled = false
    private var noDrawerMode = false
    private var tabs: List<DrawerTab> = emptyList()
    private var selectedTabId = DrawerTab.Type.ALL.key
    private var lastPersonalTabId = DrawerTab.Type.ALL.key

    private var containerRef: WeakReference<ViewGroup>? = null
    private var tabBar: TabBar? = null
    private var gestureDetector: GestureDetector? = null
    private var swipeStartedOnBar = false
    private var overridingUsingTabs = false
    private var savedUsingTabs = false
    private var bindingTakeover = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applicationInfoCache = ConcurrentHashMap<String, Any>()
    private val noApplicationInfo = Any()

    private val barActive: Boolean
        get() = tabsEnabled && !noDrawerMode

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            tabsEnabled = getBoolean(DRAWER_TABS_ENABLED, false)
            noDrawerMode = getBoolean(NO_DRAWER_MODE, false)
            tabs = DrawerTab.parse(getString(DRAWER_TABS, null))
        }

        when (key.firstOrNull()) {
            DRAWER_TABS_ENABLED -> restartLauncher(mContext)
            DRAWER_TABS -> mainHandler.post { onTabsChanged() }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val containerClass = findClass(
            "com.android.launcher3.allapps.ActivityAllAppsContainerView",
            suppressError = true
        ) ?: return

        containerClass
            .hookConstructor()
            .runAfter { param -> containerRef = WeakReference(param.thisObject as ViewGroup) }

        containerClass
            .hookMethod("setupHeader")
            .suppressError()
            .runBefore { param ->
                val container = param.thisObject
                savedUsingTabs = container.getFieldSilently("mUsingTabs") == true
                bindingTakeover = isWorkTakeover(container)

                if (bindingTakeover) {
                    overridingUsingTabs = true
                    container.setField("mUsingTabs", false)
                }
            }
            .runAfter { param ->
                if (overridingUsingTabs) {
                    param.thisObject.setField("mUsingTabs", savedUsingTabs)
                    overridingUsingTabs = false
                }
            }

        containerClass
            .hookMethod("layoutBelowSearchContainer", "alignParentTop")
            .suppressError()
            .runBefore { param ->
                val view = param.args.getOrNull(0) as? View ?: return@runBefore
                val container = param.thisObject

                if (param.args.getOrNull(1) == true &&
                    view !== container.getFieldSilently("mHeader") &&
                    isWorkTakeover(container)
                ) {
                    param.args[1] = false
                }
            }
            .runAfter { param ->
                if (!barActive) return@runAfter

                val view = param.args.getOrNull(0) as? View ?: return@runAfter
                val container = param.thisObject as ViewGroup
                val layoutParams = view.layoutParams as? RelativeLayout.LayoutParams ?: return@runAfter

                if (view.javaClass.name.contains("SearchRecyclerView")) return@runAfter

                if (view === container.getFieldSilently("mHeader")) {
                    attachTabBar(container, layoutParams)
                }

                layoutParams.topMargin += barHeight(container)
                view.layoutParams = layoutParams
            }

        containerClass
            .hookMethod("onActivePageChanged")
            .suppressError()
            .runAfter { param ->
                val container = param.thisObject
                if (!isWorkTakeover(container)) return@runAfter

                val page = param.args.getOrNull(0) as? Int ?: return@runAfter
                val selected = selectedTab()

                if (page == 1 && selected?.type != DrawerTab.Type.WORK) {
                    visibleTabs().firstOrNull { it.type == DrawerTab.Type.WORK }
                        ?.let { selectTab(it, fromPager = true) }
                } else if (page == 0 && selected?.type == DrawerTab.Type.WORK) {
                    val fallback = visibleTabs().firstOrNull { it.id == lastPersonalTabId }
                        ?: visibleTabs().firstOrNull { it.type != DrawerTab.Type.WORK }
                    fallback?.let { selectTab(it, fromPager = true) }
                }
            }

        containerClass
            .hookMethod("animateToSearchState", "reset", "onClearSearchResult")
            .suppressError()
            .runAfter { mainHandler.post { updateBarVisibility() } }

        containerClass
            .hookMethod("onInterceptTouchEvent")
            .suppressError()
            .runBefore { param ->
                val event = param.args.getOrNull(0) as? MotionEvent ?: return@runBefore
                handleSwipe(param.thisObject as ViewGroup, event)
            }

        findClass("com.android.launcher3.allapps.AllAppsStore", suppressError = true)
            .hookMethod("setApps")
            .suppressError()
            .runAfter {
                applicationInfoCache.clear()
                mainHandler.post { tabBar?.let { refreshBar() } }
            }

        findClass("com.android.launcher3.allapps.AlphabeticalAppsList", suppressError = true)
            .hookMethod("onAppsUpdated")
            .suppressError()
            .runBefore { param ->
                val list = param.thisObject
                if (!barActive || list.getFieldSilently("mAllAppsStore") == null) return@runBefore

                val tab = selectedTab()?.takeIf { it.filtersApps } ?: return@runBefore

                @Suppress("UNCHECKED_CAST")
                val original = list.getFieldSilently("mItemFilter") as? Predicate<Any?>
                val tabFilter = Predicate<Any?> { info -> info.matchesTab(tab) }

                list.setExtraField(original ?: NO_FILTER)
                list.setField("mItemFilter", original?.and(tabFilter) ?: tabFilter)
            }
            .runAfter { param ->
                val list = param.thisObject
                val original = list.takeExtraField() ?: return@runAfter
                list.setField("mItemFilter", original.takeIf { it !== NO_FILTER })
            }
    }

    private fun isWorkTakeover(container: Any): Boolean {
        val hasWorkProfile = if (overridingUsingTabs) {
            savedUsingTabs
        } else {
            container.getFieldSilently("mUsingTabs") == true
        }

        return barActive && hasWorkProfile &&
                tabs.any { it.type == DrawerTab.Type.WORK && !it.hidden }
    }

    private fun hasWorkProfile(): Boolean {
        val container = containerRef?.get() ?: return false
        return if (overridingUsingTabs) savedUsingTabs else container.getFieldSilently("mUsingTabs") == true
    }

    private fun onTabsChanged() {
        val container = containerRef?.get() ?: return
        if (!barActive) return

        if (hasWorkProfile() && isWorkTakeover(container) != bindingTakeover) {
            restartLauncher(mContext)
            return
        }

        refreshBar()
        refreshLists(container)
    }

    private fun visibleTabs(): List<DrawerTab> {
        val apps = personalApps()

        return tabs.filter { tab ->
            when {
                tab.hidden -> false
                tab.type == DrawerTab.Type.WORK -> hasWorkProfile()
                tab.filtersApps -> apps.any { it.matchesTab(tab) }
                else -> true
            }
        }
    }

    private fun selectedTab(): DrawerTab? {
        val visible = visibleTabs()
        return visible.firstOrNull { it.id == selectedTabId } ?: visible.firstOrNull()
    }

    private fun personalApps(): List<Any> {
        val store = containerRef?.get()?.getFieldSilently("mAllAppsStore") ?: return emptyList()
        val apps = (store.getFieldSilently("mApps") ?: store.callMethodSilently("getApps")) as? Array<*>
            ?: return emptyList()
        val myUser = Process.myUserHandle()

        return apps.filterNotNull().filter { (it.getFieldSilently("user") as? UserHandle ?: myUser) == myUser }
    }

    private fun Any?.matchesTab(tab: DrawerTab): Boolean {
        val component = this.getFieldSilently("componentName") as? ComponentName
            ?: this.callMethodSilently("getTargetComponent") as? ComponentName
            ?: return false

        return tab.matches(component.packageName, applicationInfo(component.packageName))
    }

    private fun applicationInfo(packageName: String): ApplicationInfo? {
        val cached = applicationInfoCache.getOrPut(packageName) {
            runCatching { mContext.packageManager.getApplicationInfo(packageName, 0) }
                .getOrNull() ?: noApplicationInfo
        }
        return cached as? ApplicationInfo
    }

    private fun selectTab(tab: DrawerTab, fromPager: Boolean = false) {
        val container = containerRef?.get() ?: return
        val changed = tab.id != selectedTab()?.id

        selectedTabId = tab.id
        if (tab.type != DrawerTab.Type.WORK) lastPersonalTabId = tab.id

        tabBar?.setTabs(visibleTabs(), tab.id)

        if (!fromPager && isWorkTakeover(container)) {
            val pager = container.getFieldSilently("mViewPager")
            val targetPage = if (tab.type == DrawerTab.Type.WORK) 1 else 0
            if (pager.callMethodSilently("getCurrentPage") != targetPage) {
                pager.callMethodSilently("snapToPage", targetPage)
            }
        }

        if (changed) refreshLists(container)
    }

    private fun refreshLists(container: Any) {
        (container.getFieldSilently("mAH") as? List<*>)?.forEach { holder ->
            holder.getFieldSilently("mAppsList").callMethodSilently("onAppsUpdated")
            holder.getFieldSilently("mRecyclerView").callMethodSilently("scrollToTop")
        }
    }

    private fun attachTabBar(container: ViewGroup, headerParams: RelativeLayout.LayoutParams) {
        val header = container.getFieldSilently("mHeader") as? View
        val nativeStrip = header.getFieldSilently("mTabLayout") as? ViewGroup

        val bar = tabBar?.takeIf { it.parent === container } ?: TabBar(container.context, nativeStrip).also { bar ->
            (tabBar?.parent as? ViewGroup)?.removeView(tabBar)
            tabBar = bar
            container.addView(bar)
            header?.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                bar.translationY = view.paddingTop.toFloat()
                alignWithNativeTabs(container, bar)
            }
        }

        bar.layoutParams = RelativeLayout.LayoutParams(headerParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = barHeight(container)
            leftMargin = 0
            rightMargin = 0
        }
        bar.translationY = (header?.paddingTop ?: 0).toFloat()

        alignWithNativeTabs(container, bar)
        refreshBar()
        updateBarVisibility()
    }

    private fun alignWithNativeTabs(container: ViewGroup, bar: TabBar) {
        val header = container.getFieldSilently("mHeader") as? ViewGroup
        val nativeStrip = header.getFieldSilently("mTabLayout") as? View
        val fallback = container.context.dp(16)

        if (header == null || nativeStrip == null || header.width == 0) {
            bar.setContentPadding(fallback, fallback)
            return
        }

        val margins = nativeStrip.layoutParams as? ViewGroup.MarginLayoutParams
        val available = header.width - header.paddingLeft - header.paddingRight -
                (margins?.leftMargin ?: 0) - (margins?.rightMargin ?: 0)

        nativeStrip.measure(
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(nativeStrip.layoutParams?.height?.coerceAtLeast(0) ?: 0, View.MeasureSpec.EXACTLY)
        )

        val inset = ((available - nativeStrip.measuredWidth) / 2).coerceAtLeast(0)
        val headerLocation = IntArray(2).also { header.getLocationInWindow(it) }
        val containerLocation = IntArray(2).also { container.getLocationInWindow(it) }
        val headerLeft = headerLocation[0] - containerLocation[0]
        val headerRight = containerLocation[0] + container.width - (headerLocation[0] + header.width)

        bar.setContentPadding(
            headerLeft + header.paddingLeft + (margins?.leftMargin ?: 0) + inset,
            headerRight + header.paddingRight + (margins?.rightMargin ?: 0) + inset
        )
    }

    private fun refreshBar() {
        val bar = tabBar ?: return
        val visible = visibleTabs()
        bar.setTabs(visible, selectedTab()?.id)
    }

    private fun updateBarVisibility() {
        val bar = tabBar ?: return
        val container = containerRef?.get() ?: return
        val searching = container.callMethodSilently("isSearching") as? Boolean
            ?: container.getFieldSilently("mIsSearching") as? Boolean
            ?: false

        bar.animate().cancel()
        if (searching) {
            bar.animate().alpha(0f).setDuration(120).withEndAction { bar.visibility = View.INVISIBLE }
        } else {
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(120)
        }
    }

    private fun handleSwipe(container: ViewGroup, event: MotionEvent) {
        if (!barActive || tabBar == null || hasWorkProfile()) return

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val bar = tabBar!!
            swipeStartedOnBar = event.y >= bar.top && event.y <= bar.bottom
        }
        if (swipeStartedOnBar) return

        val detector = gestureDetector ?: GestureDetector(
            container.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (abs(velocityX) < container.context.dp(800) || abs(velocityX) < abs(velocityY) * 2) {
                        return false
                    }
                    if (tabBar?.visibility != View.VISIBLE) return false

                    val rtl = container.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val step = if ((velocityX < 0) != rtl) 1 else -1
                    val visible = visibleTabs()
                    val index = visible.indexOfFirst { it.id == selectedTab()?.id }
                    val target = visible.getOrNull(index + step) ?: return false

                    container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    selectTab(target)
                    return true
                }
            }
        ).also { gestureDetector = it }

        detector.onTouchEvent(event)
    }

    private fun Any.setExtraField(value: Any) {
        de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField(this, ORIGINAL_FILTER_KEY, value)
    }

    private fun Any.takeExtraField(): Any? {
        return de.robv.android.xposed.XposedHelpers.removeAdditionalInstanceField(this, ORIGINAL_FILTER_KEY)
    }

    private fun barHeight(container: ViewGroup): Int {
        val nativeStrip = container.getFieldSilently("mHeader").getFieldSilently("mTabLayout") as? View
        val tabHeight = nativeStrip?.layoutParams?.height?.takeIf { it > 0 }
            ?: container.context.dp(CHIP_HEIGHT_DP + 8)
        return tabHeight + container.context.dp(BAR_BOTTOM_GAP_DP)
    }

    @SuppressLint("ViewConstructor")
    private inner class TabBar(context: Context, nativeStrip: ViewGroup?) : FrameLayout(context) {

        private val template = nativeStrip?.getChildAt(0) as? TextView
        private val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isFillViewport = true
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(16))
            addView(track, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        private val trackFrame = FrameLayout(context).apply {
            background = nativeStrip?.background?.constantState?.newDrawable(context.resources)?.mutate()
                ?: GradientDrawable().apply {
                    cornerRadius = context.dp(CHIP_HEIGHT_DP).toFloat()
                    setColor(
                        themeColor(
                            android.R.color.system_neutral2_800,
                            android.R.color.system_neutral2_100,
                            0xFF2B2930.toInt(),
                            0xFFECE6F0.toInt()
                        )
                    )
                }

            if (nativeStrip?.background != null) {
                setPadding(nativeStrip.paddingLeft, nativeStrip.paddingTop, nativeStrip.paddingRight, nativeStrip.paddingBottom)
            } else {
                val inset = context.dp(4)
                setPadding(inset, inset, inset, inset)
            }

            clipToOutline = true
            addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> matchTabRadius() }
        }
        private var currentIds: List<String> = emptyList()
        private var currentSelected: String? = null

        init {
            addView(trackFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun setContentPadding(left: Int, right: Int) {
            if (paddingLeft != left || paddingRight != right) {
                setPadding(left, 0, right, context.dp(BAR_BOTTOM_GAP_DP))
            }
        }

        fun setTabs(visible: List<DrawerTab>, selectedId: String?) {
            val ids = visible.map { it.id + "|" + it.displayName(modRes) }
            val rebuild = ids != currentIds

            if (rebuild) {
                track.removeAllViews()
                visible.forEach { tab ->
                    track.addView(
                        createTab(tab),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f
                        )
                    )
                }
                currentIds = ids
                trackFrame.post { matchTabRadius() }
            }

            if (rebuild || selectedId != currentSelected) {
                currentSelected = selectedId

                for (i in 0 until track.childCount) {
                    val tab = track.getChildAt(i) as TextView
                    tab.isSelected = tab.tag == selectedId
                    if (template == null) styleFallback(tab, tab.isSelected)
                }

                (0 until track.childCount).map { track.getChildAt(it) }
                    .firstOrNull { it.tag == selectedId }
                    ?.let { tab ->
                        scroller.post {
                            scroller.smoothScrollTo(tab.left - (scroller.width - tab.width) / 2, 0)
                        }
                    }
            }

            visibility = if (visible.size > 1) visibility.takeIf { it != GONE } ?: VISIBLE else GONE
        }

        private fun matchTabRadius() {
            val height = trackFrame.height
            if (height == 0) return

            var outerRadius = height / 2f
            trackFrame.background?.forEachGradient { gradient ->
                val radius = gradient.cornerRadii?.firstOrNull() ?: gradient.cornerRadius
                outerRadius = minOf(radius, height / 2f)
            }

            val innerHeight = height - trackFrame.paddingTop - trackFrame.paddingBottom
            val innerRadius = (outerRadius - trackFrame.paddingTop)
                .coerceAtLeast(context.dp(MIN_TAB_RADIUS_DP).toFloat())
                .coerceAtMost(innerHeight / 2f)

            for (i in 0 until track.childCount) {
                track.getChildAt(i).background?.forEachGradient { it.cornerRadius = innerRadius }
            }
        }

        private fun createTab(tab: DrawerTab): TextView {
            return TextView(context).apply {
                tag = tab.id
                text = tab.displayName(modRes)
                gravity = Gravity.CENTER
                maxLines = 1
                isClickable = true
                isFocusable = true

                if (template != null) {
                    background = template.background?.constantState?.newDrawable(context.resources)?.mutate()
                    setTextColor(template.textColors)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, template.textSize)
                    typeface = template.typeface
                    letterSpacing = template.letterSpacing
                    isAllCaps = template.transformationMethod != null
                    setPadding(
                        maxOf(template.paddingLeft, context.dp(16)),
                        template.paddingTop,
                        maxOf(template.paddingRight, context.dp(16)),
                        template.paddingBottom
                    )
                } else {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(context.dp(16), 0, context.dp(16), 0)
                }

                setOnClickListener {
                    if (tab.id != selectedTab()?.id) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        selectTab(tab)
                    }
                }
            }
        }

        private fun styleFallback(tab: TextView, selected: Boolean) {
            tab.background = if (selected) {
                GradientDrawable().apply {
                    cornerRadius = context.dp(CHIP_HEIGHT_DP).toFloat()
                    setColor(
                        themeColor(
                            android.R.color.system_accent1_200,
                            android.R.color.system_accent1_600,
                            0xFFD0BCFF.toInt(),
                            0xFF6750A4.toInt()
                        )
                    )
                }
            } else {
                null
            }

            tab.setTextColor(
                if (selected) {
                    themeColor(
                        android.R.color.system_accent1_800,
                        android.R.color.system_accent1_0,
                        0xFF381E72.toInt(),
                        0xFFFFFFFF.toInt()
                    )
                } else {
                    themeColor(
                        android.R.color.system_neutral1_100,
                        android.R.color.system_neutral1_800,
                        0xFFE6E0E9.toInt(),
                        0xFF322F35.toInt()
                    )
                }
            )
        }

        private fun themeColor(nightRes: Int, dayRes: Int, nightFallback: Int, dayFallback: Int): Int {
            val night = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getColor(if (night) nightRes else dayRes)
            } else {
                if (night) nightFallback else dayFallback
            }
        }
    }

    private fun Drawable.forEachGradient(action: (GradientDrawable) -> Unit) {
        when (this) {
            is GradientDrawable -> action(this)
            is LayerDrawable -> for (i in 0 until numberOfLayers) getDrawable(i)?.forEachGradient(action)
            is DrawableWrapper -> drawable?.forEachGradient(action)
            is DrawableContainer -> (constantState as? DrawableContainer.DrawableContainerState)
                ?.children
                ?.forEach { it?.forEachGradient(action) }
        }
    }

    private fun Context.dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val BAR_BOTTOM_GAP_DP = 8
        private const val CHIP_HEIGHT_DP = 36
        private const val MIN_TAB_RADIUS_DP = 4
        private const val ORIGINAL_FILTER_KEY = "plenhanced_drawer_tab_filter"
        private val NO_FILTER = Any()
    }
}
