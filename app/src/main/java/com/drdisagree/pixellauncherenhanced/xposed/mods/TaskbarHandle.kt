package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class TaskbarHandle(context: Context) : ModPack(context) {

    private var mHidePill = false
    private var mHideNavSpace = false
    private var stashedHandleViewObj: Any? = null
    private var mIsRegionDark: Boolean? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            mHidePill = getBoolean(HIDE_GESTURE_PILL, false)
            mHideNavSpace = mHidePill && getBoolean(HIDE_NAVIGATION_SPACE, false)
        }

        when (key.firstOrNull()) {
            HIDE_GESTURE_PILL -> updateHandleColor(true)
            HIDE_NAVIGATION_SPACE -> restartLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val stashedHandleViewClass = findClass("com.android.launcher3.taskbar.StashedHandleView")

        stashedHandleViewClass
            .hookConstructor()
            .runAfter { param ->
                stashedHandleViewObj = param.thisObject
                updateHandleColor()
            }

        stashedHandleViewClass
            .hookMethod("updateHandleColor")
            .runBefore { param ->
                mIsRegionDark = param.args[0] as? Boolean
            }

        val taskbarActivityContextClass =
            findClass("com.android.launcher3.taskbar.TaskbarActivityContext")

        @Suppress("UNCHECKED_CAST")
        taskbarActivityContextClass
            .hookMethod("notifyUpdateLayoutParams")
            .runBefore { param ->
                if (!mHideNavSpace) return@runBefore

                val layoutParams =
                    param.thisObject.getFieldSilently("mWindowLayoutParams") as? WindowManager.LayoutParams
                        ?: return@runBefore
                layoutParams.transformLayoutParams()

                val rotationParams =
                    layoutParams.getFieldSilently("paramsForRotation") as? Array<WindowManager.LayoutParams?>
                        ?: return@runBefore

                rotationParams.forEach { rotationLayoutParams ->
                    rotationLayoutParams.transformLayoutParams()
                }
            }
    }

    @SuppressLint("DiscouragedApi")
    private fun updateHandleColor(apply: Boolean = false) {
        val mStashedHandleLightColor = if (!mHidePill) ContextCompat.getColor(
            mContext,
            mContext.resources.getIdentifier(
                "taskbar_stashed_handle_light_color",
                "color",
                mContext.packageName
            )
        ) else Color.TRANSPARENT
        val mStashedHandleDarkColor = if (!mHidePill) ContextCompat.getColor(
            mContext,
            mContext.resources.getIdentifier(
                "taskbar_stashed_handle_dark_color",
                "color",
                mContext.packageName
            )
        ) else Color.TRANSPARENT

        stashedHandleViewObj?.apply {
            setField("mStashedHandleLightColor", mStashedHandleLightColor)
            setField("mStashedHandleDarkColor", mStashedHandleDarkColor)

            if (apply) {
                if (mIsRegionDark != null) {
                    callMethod("updateHandleColor", mIsRegionDark != true, true)
                    callMethod("updateHandleColor", mIsRegionDark == true, true)
                } else {
                    callMethod("updateHandleColor", false, true)
                    callMethod("updateHandleColor", true, true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun WindowManager.LayoutParams?.transformLayoutParams() {
        if (this == null) return

        val providedInsets = getFieldSilently("providedInsets") ?: return

        val providedInsetsLength = java.lang.reflect.Array.getLength(providedInsets)

        for (i in 0..<providedInsetsLength) {
            val insetsFrame = java.lang.reflect.Array.get(providedInsets, i) ?: continue

            // no constants, maximum compatibility with Android versions
            if (!insetsFrame.toString().contains("type=navigationBars", ignoreCase = true)) continue

            val noneInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Insets.NONE
            } else {
                Insets.of(0, 0, 0, 0)
            }
            insetsFrame.callMethod("setInsetsSize", noneInsets)
        }
    }
}