package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Matrix
import android.os.Build
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_PAGE_TRANSITION
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

class PageTransitions(context: Context) : ModPack(context) {

    private var transition = NONE
    private var workspaceRef: WeakReference<ViewGroup>? = null
    private val transformed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            transition = getString(HOME_PAGE_TRANSITION, NONE) ?: NONE
        }

        if (key.firstOrNull() == HOME_PAGE_TRANSITION) {
            workspaceRef?.get()?.let { workspace -> workspace.post { workspace.applyTransition() } }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        findClass("com.android.launcher3.Workspace", suppressError = true)
            .hookMethod("onScrollChanged")
            .suppressError()
            .runAfter { param ->
                val workspace = param.thisObject as? ViewGroup ?: return@runAfter
                workspaceRef = WeakReference(workspace)
                workspace.applyTransition()
            }
    }

    private fun ViewGroup.applyTransition() {
        val count = callMethodSilently("getPageCount") as? Int ?: childCount
        val active = transition != NONE &&
                getFieldSilently("mIsSwitchingState") != true &&
                scaleX == 1f && scaleY == 1f

        for (index in 0 until count) {
            val page = callMethodSilently("getPageAt", index) as? View ?: getChildAt(index) ?: continue
            if (!active || page.width == 0) {
                page.resetTransition()
                continue
            }

            val pageScroll = callMethodSilently("getScrollForPage", index) as? Int ?: continue
            val step = pageStep(count).takeIf { it > 0f } ?: page.width.toFloat()
            val offset = (pageScroll - scrollX) / step
            if (abs(offset) >= 1f) {
                page.resetTransition()
                continue
            }

            page.applyTransition(offset, step)
        }
    }

    private fun ViewGroup.pageStep(count: Int): Float {
        if (count < 2) return 0f
        val first = callMethodSilently("getScrollForPage", 0) as? Int ?: return 0f
        val second = callMethodSilently("getScrollForPage", 1) as? Int ?: return 0f
        return abs(second - first).toFloat()
    }

    private fun View.applyTransition(offset: Float, step: Float) {
        resetTransition()
        transformed.add(this)

        when (transition) {
            CUBE -> {
                cameraDistance = width * CAMERA_DISTANCE_FACTOR * resources.displayMetrics.density
                pivotX = if (offset > 0f) 0f else width.toFloat()
                pivotY = height / 2f
                rotationY = offset * CUBE_ANGLE
            }

            STACK -> if (offset < 0f) {
                val scale = 1f + offset * STACK_SCALE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    animationMatrix = Matrix().apply {
                        setScale(scale, scale, width / 2f, height / 2f)
                        postTranslate(-offset * step, 0f)
                    }
                } else {
                    scaleX = scale
                    scaleY = scale
                }
                alpha = 1f + offset
            }

            FADE -> alpha = 1f - abs(offset)

            ZOOM -> {
                val scale = 1f - abs(offset) * ZOOM_SCALE
                scaleX = scale
                scaleY = scale
            }
        }
    }

    private fun View.resetTransition() {
        if (!transformed.remove(this)) return
        if (rotationY != 0f) rotationY = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && animationMatrix != null) animationMatrix = null
        if (scaleX != 1f) scaleX = 1f
        if (scaleY != 1f) scaleY = 1f
        if (alpha != 1f) alpha = 1f
        pivotX = width / 2f
        pivotY = height / 2f
    }

    companion object {
        private const val NONE = "none"
        private const val CUBE = "cube"
        private const val STACK = "stack"
        private const val FADE = "fade"
        private const val ZOOM = "zoom"
        private const val CUBE_ANGLE = 90f
        private const val CAMERA_DISTANCE_FACTOR = 8f
        private const val STACK_SCALE = 0.2f
        private const val ZOOM_SCALE = 0.25f
    }
}
