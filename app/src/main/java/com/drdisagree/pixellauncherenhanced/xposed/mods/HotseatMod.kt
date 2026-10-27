package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_DOCK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BOTTOM_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_SEARCH_BAR_ABOVE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_TOP_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadModel
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceHookManager
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlin.math.roundToInt

class HotseatMod(context: Context) : ModPack(context) {

    private var hideDesktopSearchBar = false
    private var dockDisabled = false
    private var hidePageIndicator = false
    private var dockBottomSpacing = 0
    private var dockTopSpacing = 0
    private var searchBarAbove = false
    private val inProfileCopy = ThreadLocal<Boolean>()
    private var mQuickSearchBar: View? = null
    private val workspaceInsets = Rect()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideDesktopSearchBar = getBoolean(DESKTOP_SEARCH_BAR, false)
            dockDisabled = getBoolean(DISABLE_DOCK, false)
            hidePageIndicator = getBoolean(LAUNCHER_HIDE_PAGE_INDICATOR, false)
            dockBottomSpacing = getSliderInt(DOCK_BOTTOM_SPACING, 0)
            dockTopSpacing = getSliderInt(DOCK_TOP_SPACING, 0)
            searchBarAbove = getBoolean(DOCK_SEARCH_BAR_ABOVE, false)
        }

        when (key.firstOrNull()) {
            DESKTOP_SEARCH_BAR -> {
                triggerSearchBarVisibility()
                reloadLauncher(mContext)
            }

            DOCK_BOTTOM_SPACING,
            DOCK_TOP_SPACING,
            DOCK_SEARCH_BAR_ABOVE -> reloadLauncher(mContext)

            DISABLE_DOCK -> {
                triggerSearchBarVisibility()
                reloadLauncher(mContext)
                reloadModel()
            }

            LAUNCHER_HIDE_PAGE_INDICATOR -> if (dockDisabled) reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val hotseatClass = findClass("com.android.launcher3.Hotseat")
        val workspaceClass = findClass("com.android.launcher3.Workspace")

        hotseatClass
            .hookConstructor()
            .runAfter { param ->
                mQuickSearchBar = param.thisObject.getField("mQsb") as View
                triggerSearchBarVisibility()
            }

        hotseatClass
            .hookMethod("setInsets")
            .runAfter { param ->
                mQuickSearchBar = param.thisObject.getField("mQsb") as View
                triggerSearchBarVisibility()
            }

        hotseatClass
            .hookMethod("onLayout")
            .runAfter { param ->
                val hotseat = param.thisObject as ViewGroup
                if (hotseat.placeSearchBarAbove()) {
                    hotseat.callMethodSilently("onLayout", *param.args)
                }
            }

        hotseatClass
            .hookMethod("onInterceptTouchEvent")
            .runBefore { param -> if (dockDisabled) param.result = true }

        hotseatClass
            .hookMethod("onTouchEvent")
            .runBefore { param -> if (dockDisabled) param.result = false }

        workspaceClass
            .hookMethod("setPageIndicatorInset")
            .suppressError()
            .runAfter { param -> if (dockDisabled) placePageIndicatorAtBottom(param.thisObject as View) }

        workspaceClass
            .hookMethod("setInsets")
            .runAfter { param ->
                (param.args.firstOrNull() as? Rect)?.let { workspaceInsets.set(it) }
                if (!dockDisabled && dockTopSpacing == 0) return@runAfter

                val mLauncher = param.thisObject.getField("mLauncher")
                val grid = mLauncher.callMethodSilently("getDeviceProfile")
                    ?: mLauncher
                        .getField("deviceProfileRef")
                        .getField("value")
                val padding = grid.getFieldSilently("workspacePadding") as? Rect
                    ?: grid
                        .getAnyField("mWorkspaceProfile", "workspaceProfile")
                        .getField("workspacePadding") as Rect
                val workspace = param.thisObject as View

                val bottomPadding = when {
                    dockDisabled -> if (hidePageIndicator) 0 else pageIndicatorHeight(workspace)
                    else -> (padding.bottom + mContext.toPx(dockTopSpacing)).coerceAtLeast(0)
                }

                workspace.setPadding(workspace.paddingLeft, workspace.paddingTop, workspace.paddingRight, bottomPadding)

                if (dockDisabled) placePageIndicatorAtBottom(workspace)
            }

        val hotseatProfileClass = findClass(
            "com.android.launcher3.deviceprofile.HotseatProfile",
            suppressError = true
        )

        hotseatProfileClass
            .hookMethod("copy", $$"copy$default")
            .suppressError()
            .runBefore { inProfileCopy.set(true) }
            .runAfter { inProfileCopy.remove() }

        hotseatProfileClass
            .hookConstructor()
            .suppressError()
            .runAfter { param ->
                if (inProfileCopy.get() == true) return@runAfter
                param.thisObject.adjustHotseatSize(PROFILE_FIELDS)
            }

        findClass("com.android.launcher3.DeviceProfile")
            .hookMethod("updateHotseatSizes")
            .suppressError()
            .runAfter { param -> param.thisObject.adjustHotseatSize(LEGACY_FIELDS) }

        ResourceHookManager
            .hookDimen()
            .whenCondition { hideDesktopSearchBar || dockDisabled }
            .forPackageName(loadPackageParam.packageName)
            .addResource("qsb_widget_height") { 0 }
            .apply()
    }

    private fun Any.adjustHotseatSize(fields: HotseatFields) {
        var barSize = getFieldSilently(fields.barSize) as? Int ?: return
        var bottomSpace = getFieldSilently(fields.bottomSpace) as? Int ?: return
        val qsbInline = getFieldSilently("isQsbInline") as? Boolean ?: false

        if ((hideDesktopSearchBar || dockDisabled) && !qsbInline) {
            val qsbSpace = getFieldSilently(fields.qsbSpace) as? Int ?: 0
            val visualOwner = if (getFieldSilently(fields.qsbVisualHeight) is Int) this
            else getAnyFieldSilently("mHotseatProfile", "hotseatProfile")
            val visualField = if (visualOwner === this) fields.qsbVisualHeight else PROFILE_FIELDS.qsbVisualHeight
            val qsbVisualHeight = visualOwner?.getFieldSilently(visualField) as? Int ?: 0

            barSize -= qsbSpace + qsbVisualHeight
            setFieldSilently(fields.qsbSpace, 0)
            visualOwner?.setFieldSilently(visualField, 0)
        }

        val originalBottomSpace = getExtraFieldSilently(ORIGINAL_BOTTOM_SPACE) as? Int
            ?: bottomSpace.also { setExtraField(ORIGINAL_BOTTOM_SPACE, it) }
        val targetBottomSpace = (originalBottomSpace + mContext.toPx(dockBottomSpacing)).coerceAtLeast(0)
        val delta = targetBottomSpace - bottomSpace

        bottomSpace += delta
        barSize += delta

        setFieldSilently(fields.bottomSpace, bottomSpace)
        setFieldSilently(fields.barSize, barSize.coerceAtLeast(0))
    }

    private fun ViewGroup.placeSearchBarAbove(): Boolean {
        val qsb = getFieldSilently("mQsb") as? View ?: return false
        val original = getExtraFieldSilently(ORIGINAL_PADDING) as? Rect
        val applied = getExtraFieldSilently(APPLIED_PADDING) as? Rect
        val usesApplied = applied != null && paddingTop == applied.top && paddingBottom == applied.bottom
        val active = searchBarAbove && !hideDesktopSearchBar && !dockDisabled &&
                qsb.visibility == View.VISIBLE && getFieldSilently("mHasVerticalHotseat") != true &&
                !isQsbInline()

        if (!active) {
            if (original != null && usesApplied) {
                setExtraField(APPLIED_PADDING, null)
                setPadding(original.left, original.top, original.right, original.bottom)
                return true
            }
            return false
        }

        val base = if (original != null && usesApplied) original
        else Rect(paddingLeft, paddingTop, paddingRight, paddingBottom).also { setExtraField(ORIGINAL_PADDING, it) }
        val iconsHeight = height - base.top - base.bottom
        val qsbHeight = qsb.height
        if (iconsHeight <= 0 || qsbHeight <= 0) return false

        val top = (qsb.top + (qsbHeight - iconsHeight) / 2f).roundToInt()
        val bottom = height - top - iconsHeight
        val qsbTop = (base.top + (iconsHeight - qsbHeight) / 2f).roundToInt()
        qsb.layout(qsb.left, qsbTop, qsb.right, qsbTop + qsbHeight)
        if (paddingTop == top && paddingBottom == bottom) return false
        setExtraField(APPLIED_PADDING, Rect(base.left, top, base.right, bottom))
        setPadding(base.left, top, base.right, bottom)
        return true
    }

    private fun View.isQsbInline(): Boolean {
        val profile = context.callMethodSilently("getDeviceProfile")
            ?: context.getFieldSilently("mDeviceProfile")
            ?: return false
        val hotseat = profile.getAnyFieldSilently("hotseatProfile", "mHotseatProfile") ?: profile
        return hotseat.getFieldSilently("isQsbInline") as? Boolean ?: false
    }

    private fun pageIndicatorHeight(workspace: View): Int {
        val indicator = workspace.getFieldSilently("mPageIndicator") as? View
        return indicator?.layoutParams?.height?.takeIf { it > 0 } ?: mContext.toPx(24)
    }

    private fun placePageIndicatorAtBottom(workspace: View) {
        val indicator = workspace.getFieldSilently("mPageIndicator") as? View ?: return
        val layoutParams = indicator.layoutParams as? ViewGroup.MarginLayoutParams ?: return

        if (layoutParams.bottomMargin != workspaceInsets.bottom) {
            layoutParams.bottomMargin = workspaceInsets.bottom
            indicator.layoutParams = layoutParams
        }
    }

    private fun triggerSearchBarVisibility() {
        mQuickSearchBar?.visibility = if (hideDesktopSearchBar || dockDisabled) View.GONE else View.VISIBLE
    }

    private class HotseatFields(
        val barSize: String,
        val bottomSpace: String,
        val qsbSpace: String,
        val qsbVisualHeight: String
    )

    companion object {
        private const val ORIGINAL_BOTTOM_SPACE = "pleOriginalHotseatBottomSpace"
        private const val ORIGINAL_PADDING = "pleOriginalHotseatPadding"
        private const val APPLIED_PADDING = "pleAppliedHotseatPadding"

        private val PROFILE_FIELDS = HotseatFields(
            barSize = "barSizePx",
            bottomSpace = "barBottomSpacePx",
            qsbSpace = "qsbSpace",
            qsbVisualHeight = "qsbVisualHeight"
        )

        private val LEGACY_FIELDS = HotseatFields(
            barSize = "hotseatBarSizePx",
            bottomSpace = "hotseatBarBottomSpacePx",
            qsbSpace = "hotseatQsbSpace",
            qsbVisualHeight = "hotseatQsbVisualHeight"
        )
    }
}