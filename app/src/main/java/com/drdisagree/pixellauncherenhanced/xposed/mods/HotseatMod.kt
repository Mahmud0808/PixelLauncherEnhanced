package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR_OPACITY
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceHookManager
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
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
            .parameters(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType
            )
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
                val grid = mLauncher.callMethod("getDeviceProfile")
                val padding = grid.getFieldSilently("workspacePadding") as? Rect
                    ?: grid
                        .getField("mWorkspaceProfile")
                        .getField("workspacePadding") as Rect
                val workspace = param.thisObject as View

                workspace.setPadding(
                    padding.left,
                    padding.top,
                    padding.right,
                    if (desktopDockSpacing == -1) padding.bottom
                    else mContext.toPx(desktopDockSpacing + 20)
                )
            }

        ResourceHookManager
            .hookDimen()
            .whenCondition { hideDesktopSearchBar }
            .forPackageName(loadPackageParam.packageName)
            .addResource("qsb_widget_height") { 0 }
            .apply()
    }

    private fun triggerSearchBarVisibility() {
        mQuickSearchBar?.visibility = if (hideDesktopSearchBar) View.GONE else View.VISIBLE
    }

    private fun updateSearchBarOpacity() {
        mQuickSearchBar?.background?.alpha = desktopSearchBarOpacity * 255 / 100
    }
}