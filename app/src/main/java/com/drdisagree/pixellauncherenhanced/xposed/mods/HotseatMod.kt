package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_DOCK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceHookManager
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class HotseatMod(context: Context) : ModPack(context) {

    private var hideDesktopSearchBar = false
    private var dockDisabled = false
    private var hidePageIndicator = false
    private var desktopDockSpacing = -1
    private var desktopSearchBarOpacity = 100
    private var mQuickSearchBar: View? = null
    private val workspaceInsets = Rect()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideDesktopSearchBar = getBoolean(DESKTOP_SEARCH_BAR, false)
            dockDisabled = getBoolean(DISABLE_DOCK, false)
            hidePageIndicator = getBoolean(LAUNCHER_HIDE_PAGE_INDICATOR, false)
            desktopDockSpacing = getSliderInt(DESKTOP_DOCK_SPACING, -1)
            desktopSearchBarOpacity = getSliderInt(DESKTOP_SEARCH_BAR_OPACITY, 100)
        }

        when (key.firstOrNull()) {
            DESKTOP_SEARCH_BAR -> {
                triggerSearchBarVisibility()
                restartLauncher(mContext)
            }

            DESKTOP_DOCK_SPACING,
            DISABLE_DOCK -> restartLauncher(mContext)

            LAUNCHER_HIDE_PAGE_INDICATOR -> if (dockDisabled) restartLauncher(mContext)

            DESKTOP_SEARCH_BAR_OPACITY -> updateSearchBarOpacity()
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
                updateSearchBarOpacity()
            }

        hotseatClass
            .hookMethod("setInsets")
            .runAfter { param ->
                mQuickSearchBar = param.thisObject.getField("mQsb") as View
                triggerSearchBarVisibility()
                updateSearchBarOpacity()
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
                    desktopDockSpacing == -1 -> padding.bottom
                    else -> mContext.toPx(desktopDockSpacing + 20)
                }

                workspace.setPadding(padding.left, padding.top, padding.right, bottomPadding)

                if (dockDisabled) placePageIndicatorAtBottom(workspace)
            }

        ResourceHookManager
            .hookDimen()
            .whenCondition { hideDesktopSearchBar || dockDisabled }
            .forPackageName(loadPackageParam.packageName)
            .addResource("qsb_widget_height") { 0 }
            .apply()
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

    private fun updateSearchBarOpacity() {
        mQuickSearchBar?.background?.alpha = desktopSearchBarOpacity * 255 / 100
    }
}