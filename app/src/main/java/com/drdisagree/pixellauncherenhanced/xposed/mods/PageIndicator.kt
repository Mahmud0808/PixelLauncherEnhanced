package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_DARK_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

class PageIndicator(context: Context) : ModPack(context) {

    private var darkPageIndicatorEnabled = false
    private var hidePageIndicator = false
    private var pageIndicatorRef: WeakReference<View>? = null
    private var pageIndicatorId = 0

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            darkPageIndicatorEnabled = getBoolean(LAUNCHER_DARK_PAGE_INDICATOR, false)
            hidePageIndicator = getBoolean(LAUNCHER_HIDE_PAGE_INDICATOR, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_DARK_PAGE_INDICATOR -> restartLauncher(mContext)
            LAUNCHER_HIDE_PAGE_INDICATOR -> pageIndicatorRef?.get()?.invalidateTree()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherClass = findClass("com.android.launcher3.Launcher")

        try {
            launcherClass
                .hookMethod("setupViews")
                .throwError()
                .runAfter { param -> setupPageIndicator(param.thisObject) }
        } catch (_: Throwable) {
            launcherClass
                .hookMethod("onCreate")
                .runAfter { param -> setupPageIndicator(param.thisObject) }
        }

        listOf(
            "com.android.launcher3.pageindicators.PageIndicatorDots",
            "com.android.launcher3.pageindicators.WorkspacePageIndicator"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("onDraw")
                .suppressError()
                .runBefore { param ->
                    if (hidePageIndicator && (param.thisObject as View).isWorkspaceIndicator()) {
                        param.result = null
                    }
                }
        }
    }

    private fun setupPageIndicator(launcher: Any) {
        val pageIndicator = launcher.getField("mWorkspace").callMethod("getPageIndicator")

        (pageIndicator as? View)?.let { pageIndicatorRef = WeakReference(it) }

        if (darkPageIndicatorEnabled) {
            pageIndicator.callMethod("setPaintColor", Color.BLACK)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun View.isWorkspaceIndicator(): Boolean {
        if (pageIndicatorId == 0) {
            pageIndicatorId = resources.getIdentifier("page_indicator", "id", context.packageName)
        }

        var view: View? = this

        repeat(5) {
            if (view === pageIndicatorRef?.get() || view?.id == pageIndicatorId) return true
            view = view?.parent as? View
        }

        return false
    }

    private fun View.invalidateTree() {
        invalidate()
        (this as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) group.getChildAt(i).invalidateTree()
        }
    }
}
