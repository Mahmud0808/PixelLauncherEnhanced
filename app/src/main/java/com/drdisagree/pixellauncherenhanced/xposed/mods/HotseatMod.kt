package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR_OPACITY
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
    private var desktopDockSpacing = -1
    private var desktopSearchBarOpacity = 100
    private var mQuickSearchBar: View? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideDesktopSearchBar = getBoolean(DESKTOP_SEARCH_BAR, false)
            desktopDockSpacing = getSliderInt(DESKTOP_DOCK_SPACING, -1)
            desktopSearchBarOpacity = getSliderInt(DESKTOP_SEARCH_BAR_OPACITY, 100)
        }

        when (key.firstOrNull()) {
            DESKTOP_SEARCH_BAR -> {
                triggerSearchBarVisibility()
                restartLauncher(mContext)
            }

            DESKTOP_DOCK_SPACING -> restartLauncher(mContext)

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

        workspaceClass
            .hookMethod("setInsets")
            .runAfter { param ->
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

                workspace.setPadding(
                    padding.left,
                    padding.top,
                    padding.right,
                    if (desktopDockSpacing == -1) padding.bottom
                    else mContext.toPx(desktopDockSpacing + 20)
                )

                fitPageIndicator(workspace, grid)
            }

        workspaceClass
            .hookMethod("setPageIndicatorInset")
            .suppressError()
            .runAfter { param ->
                val workspace = param.thisObject as View
                val grid = workspace.getField("mLauncher").callMethodSilently("getDeviceProfile")
                fitPageIndicator(workspace, grid)
            }

        ResourceHookManager
            .hookDimen()
            .whenCondition { hideDesktopSearchBar }
            .forPackageName(loadPackageParam.packageName)
            .addResource("qsb_widget_height") { 0 }
            .apply()
    }

    private fun fitPageIndicator(workspace: View, grid: Any?) {
        if (desktopDockSpacing == -1 || grid == null) return
        if (grid.callMethodSilently("isVerticalBarLayout") == true) return

        val pageIndicator = workspace.getFieldSilently("mPageIndicator") as? View ?: return
        val layoutParams = pageIndicator.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val indicatorHeight = layoutParams.height.takeIf { it > 0 }
            ?: grid.getFieldSilently("workspacePageIndicatorHeight") as? Int
            ?: runCatching { grid.getAnyField("mWorkspaceProfile", "workspaceProfile") }.getOrNull()
                .getFieldSilently("workspacePageIndicatorHeight") as? Int
            ?: return
        val maxBottomMargin = (workspace.paddingBottom - indicatorHeight).coerceAtLeast(0)

        if (layoutParams.bottomMargin > maxBottomMargin) {
            layoutParams.bottomMargin = maxBottomMargin
            pageIndicator.layoutParams = layoutParams
        }
    }

    private fun triggerSearchBarVisibility() {
        mQuickSearchBar?.visibility = if (hideDesktopSearchBar) View.GONE else View.VISIBLE
    }

    private fun updateSearchBarOpacity() {
        mQuickSearchBar?.background?.alpha = desktopSearchBarOpacity * 255 / 100
    }
}