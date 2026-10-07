package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Build
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_DRAWER_SCROLLBAR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

class DrawerScrollbar(context: Context) : ModPack(context) {

    private var hideScrollbar = false
    private var scrollerRef: WeakReference<View>? = null
    private var drawerClass: Class<*>? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideScrollbar = getBoolean(HIDE_DRAWER_SCROLLBAR, false)
        }

        when (key.firstOrNull()) {
            HIDE_DRAWER_SCROLLBAR -> scrollerRef?.get()?.let { scroller ->
                clearGestureExclusion(scroller)
                scroller.invalidate()
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        drawerClass = findClass(
            "com.android.launcher3.allapps.ActivityAllAppsContainerView",
            suppressError = true
        )

        val fastScrollerClass = findClass(
            "com.android.launcher3.views.RecyclerViewFastScroller",
            suppressError = true
        ) ?: return

        fastScrollerClass
            .hookMethod("onDraw")
            .runBefore { param ->
                val scroller = param.thisObject as View
                if (!hideScrollbar || !scroller.isDrawerScroller()) return@runBefore

                clearGestureExclusion(scroller)
                param.result = null
            }

        fastScrollerClass
            .hookMethod("handleTouchEvent", "isHitInParent", "shouldBlockIntercept")
            .suppressError()
            .runBefore { param ->
                if (hideScrollbar && (param.thisObject as View).isDrawerScroller()) {
                    param.result = false
                }
            }
    }

    private fun View.isDrawerScroller(): Boolean {
        (getExtraFieldSilently(IS_DRAWER_SCROLLER) as? Boolean)?.let { return it }
        if (!isAttachedToWindow) return false

        var parent = parent
        var isDrawer = false
        while (parent != null) {
            if (drawerClass?.isInstance(parent) == true || parent.javaClass.name.endsWith("AllAppsContainerView")) {
                isDrawer = true
                break
            }
            parent = parent.parent
        }

        setExtraField(IS_DRAWER_SCROLLER, isDrawer)
        if (isDrawer) scrollerRef = WeakReference(this)
        return isDrawer
    }

    private fun clearGestureExclusion(scroller: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && scroller.systemGestureExclusionRects.isNotEmpty()) {
            scroller.systemGestureExclusionRects = emptyList()
        }
    }

    companion object {
        private const val IS_DRAWER_SCROLLER = "pleIsDrawerScroller"
    }
}
