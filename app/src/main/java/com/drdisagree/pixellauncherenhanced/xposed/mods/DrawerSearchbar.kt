package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.reflection.getField
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import kotlin.math.max

class DrawerSearchbar(context: Context) : ModPack(context) {

    private var drawerSearchbar = true
    private var mSearchContainer: View? = null
    private var containerRef: WeakReference<ViewGroup>? = null
    private var measuring = false
    private var searchMargin: Int? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            drawerSearchbar = getBoolean(DRAWER_SEARCH_BAR, true)
        }

        when (key.firstOrNull()) {
            DRAWER_SEARCH_BAR -> {
                updateVisibility()
                containerRef?.get()?.let { relayout(it) }
            }
        }
    }

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
        val activityAllAppsContainerViewClass =
            findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView")

        activityAllAppsContainerViewClass
            .hookMethod("onFinishInflate")
            .runAfter { param ->
                containerRef = WeakReference(param.thisObject as ViewGroup)
                mSearchContainer = param.thisObject.getField("mSearchContainer") as View
                updateVisibility()
            }

        activityAllAppsContainerViewClass
            .hookMethod("layoutBelowSearchContainer")
            .suppressError()
            .priority(XposedInterface.PRIORITY_LOWEST)
            .runAfter { param ->
                if (drawerSearchbar || measuring) return@runAfter
                val view = param.args.getOrNull(0) as? View ?: return@runAfter
                val layoutParams = view.layoutParams as? RelativeLayout.LayoutParams ?: return@runAfter
                val margin = searchMargin(param.thisObject as ViewGroup)
                if (margin <= 0) return@runAfter
                layoutParams.topMargin = max(0, layoutParams.topMargin - margin)
                view.layoutParams = layoutParams
            }
    }

    private fun searchMargin(container: ViewGroup): Int {
        searchMargin?.let { return it }
        val probe = View(container.context).apply {
            layoutParams = RelativeLayout.LayoutParams(0, 0)
            tag = PROBE_TAG
        }
        measuring = true
        try {
            container.callMethodSilently("layoutBelowSearchContainer", probe, false)
        } finally {
            measuring = false
        }
        return (probe.layoutParams as RelativeLayout.LayoutParams).topMargin.also { searchMargin = it }
    }

    private fun relayout(container: ViewGroup) {
        container.post {
            container.callMethodSilently("rebindAdapters", true)
            container.requestLayout()
        }
    }

    private fun updateVisibility() {
        if (drawerSearchbar) {
            mSearchContainer?.visibility = View.VISIBLE
        } else {
            mSearchContainer?.visibility = View.GONE
        }
    }

    companion object {
        const val PROBE_TAG = "ple_search_margin_probe"
    }
}
