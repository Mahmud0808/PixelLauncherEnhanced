package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_ENDLESS_SCROLLING
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class EndlessPages(context: Context) : ModPack(context) {

    private var endlessScrolling = false
    private val pendingTarget = ThreadLocal<Int>()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            endlessScrolling = getBoolean(HOME_ENDLESS_SCROLLING, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val workspaceClass = findClass("com.android.launcher3.Workspace", suppressError = true) ?: return

        findClass("com.android.launcher3.PagedView", suppressError = true)
            .hookMethod("onTouchEvent")
            .suppressError()
            .runBefore { param ->
                pendingTarget.remove()
                if (!endlessScrolling) return@runBefore
                val event = param.args.getOrNull(0) as? MotionEvent ?: return@runBefore
                if (event.actionMasked != MotionEvent.ACTION_UP) return@runBefore

                val view = param.thisObject as? View ?: return@runBefore
                if (!workspaceClass.isInstance(view)) return@runBefore

                val target = view.wrapTarget(event)
                if (target >= 0) pendingTarget.set(target)
            }
            .runAfter { param ->
                val target = pendingTarget.get() ?: return@runAfter
                pendingTarget.remove()
                param.thisObject.callMethodSilently("snapToPage", target)
            }
    }

    private fun View.wrapTarget(event: MotionEvent): Int {
        if (getFieldSilently("mIsBeingDragged") != true) return -1
        if (scaleX != 1f || scaleY != 1f) return -1

        val count = callMethodSilently("getPageCount") as? Int ?: return -1
        val current = getFieldSilently("mCurrentPage") as? Int ?: return -1
        if (count < 2 || callMethodSilently("getNextPage") != current) return -1

        val downX = getFieldSilently("mDownMotionX") as? Float ?: return -1
        var delta = downX - event.x
        if (getFieldSilently("mIsRtl") == true) delta = -delta
        val threshold = width * SWIPE_THRESHOLD
        if (threshold <= 0f) return -1

        return when {
            current == count - 1 && delta > threshold -> 0
            current == 0 && delta < -threshold && getFieldSilently("mOverlayEdgeEffect") == null -> count - 1
            else -> -1
        }
    }

    companion object {
        private const val SWIPE_THRESHOLD = 0.15f
    }
}
