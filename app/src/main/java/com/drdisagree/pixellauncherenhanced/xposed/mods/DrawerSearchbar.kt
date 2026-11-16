package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.reflection.getField
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage

class DrawerSearchbar(context: Context) : ModPack(context) {

    private var drawerSearchbar = true
    private var mSearchContainer: View? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            drawerSearchbar = getBoolean(DRAWER_SEARCH_BAR, true)
        }

        when (key.firstOrNull()) {
            DRAWER_SEARCH_BAR -> updateVisibility()
        }
    }

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
        val activityAllAppsContainerViewClass =
            findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView")

        activityAllAppsContainerViewClass
            .hookMethod("onFinishInflate")
            .runAfter { param ->
                mSearchContainer = param.thisObject.getField("mSearchContainer") as View
                updateVisibility()
            }
    }

    private fun updateVisibility() {
        if (drawerSearchbar) {
            mSearchContainer?.visibility = View.VISIBLE
        } else {
            mSearchContainer?.visibility = View.GONE
        }
    }
}