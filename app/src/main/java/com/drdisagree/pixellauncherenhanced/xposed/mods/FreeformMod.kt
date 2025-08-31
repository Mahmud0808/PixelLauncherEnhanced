package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Rect
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE_PROGRESS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_MODE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils.currentToFreeform
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils.startFreeformByIntent
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils.startFreeformFromRecents
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.newInstance
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class FreeformMod(context: Context) : ModPack(context) {

    private var freeformEnabled: Boolean = false
    private var offProgress: Float = 3f
    private var freeformMode: Int = 0

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            freeformEnabled = getBoolean(FREEFORM_GESTURE, false)
            offProgress = getSliderFloat(FREEFORM_GESTURE_PROGRESS, 3f)
            freeformMode = getListString(FREEFORM_MODE, "2")!!.toInt()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val absSwipeClass = findClass("com.android.quickstep.AbsSwipeUpHandler")
        val gestureStateClass = findClass("com.android.quickstep.GestureState")
        val gestureEndTargetClass = findClass("com.android.quickstep.GestureState.GestureEndTarget")
        val activityManagerWrapperClass =
            findClass("com.android.systemui.shared.system.ActivityManagerWrapper")

        absSwipeClass
            .hookMethod("updateSysUiFlags")
            .runAfter { param ->
                if (!freeformEnabled) return@runAfter

                val mProgress = param.thisObject
                    .getField("mCurrentShift")
                    .getField("value") as Float

                if (mProgress > offProgress) {
                    param.thisObject.callMethod("performHapticFeedback")
                }
            }

        absSwipeClass
            .hookMethod("initStateCallbacks")
            .runAfter { param ->
                if (!freeformEnabled) return@runAfter

                val stateEndTargetSet =
                    gestureStateClass.getStaticField("STATE_END_TARGET_SET") as Int

                @Suppress("unused")
                val stateRecentsAnimationStarted =
                    gestureStateClass.getStaticField("STATE_RECENTS_ANIMATION_STARTED") as Int
                val homeTarget = gestureEndTargetClass?.getEnumConstants()?.get(0)

                param.thisObject
                    .getField("mGestureState")
                    .callMethod(
                        "runOnceAtState",
                        stateEndTargetSet,
                        Runnable {
                            val mProgress = param.thisObject
                                .getField("mCurrentShift")
                                .getField("value") as Float
                                                        
                            if (mProgress > offProgress) {
                                val mTask =
                                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
                                        (param.thisObject
                                            .getField("mRecentsView")
                                            .callMethod("getRunningTaskView")
                                            .callMethod("getTaskContainers"))
                                            .callMethod("get", 0)
                                            .callMethod("getTask")
                                    } else {
                                        param.thisObject
                                            .getField("mRecentsView")
                                            .callMethod("getRunningTaskView")
                                            .callMethod("getTask")
                                    }

                                when (freeformMode) {
                                    FreeformUtils.Variant.AOSP.id -> {
                                        val mSnapshotView =
                                            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
                                                (param.thisObject
                                                    .getField("mRecentsView")
                                                    .callMethod("getRunningTaskView")
                                                    .callMethod("getTaskContainers"))
                                                    .callMethod("get", 0)
                                                    .callMethod("getSnapshotView")
                                            } else {
                                                param.thisObject
                                                    .getField("mRecentsView")
                                                    .callMethod("getRunningTaskView")
                                                    .callMethod("getThumbnail")
                                            }

                                        val position = IntArray(2)
                                        mSnapshotView.callMethod("getLocationOnScreen", position)
                                        val w = mSnapshotView.callMethod("getWidth") as Int
                                        val h = mSnapshotView.callMethod("getHeight") as Int
                                        val mBound = Rect(
                                            position[0],
                                            position[1],
                                            position[0] + w,
                                            position[1] + h
                                        )

                                        startFreeformFromRecents(
                                            mTask,
                                            activityManagerWrapperClass.newInstance(),
                                            mBound
                                        )
                                    }

                                    FreeformUtils.Variant.YAMF.id,
                                    FreeformUtils.Variant.REYAMF.id -> {
                                        currentToFreeform(
                                            mContext,
                                            FreeformUtils.Variant.fromId(freeformMode)
                                        )
                                    }

                                    else -> {
                                        startFreeformByIntent(
                                            mContext,
                                            mTask,
                                            FreeformUtils.Variant.fromId(freeformMode)
                                        )
                                    }
                                }

                                param.thisObject
                                    .getField("mGestureState")
                                    .callMethod("setEndTarget", homeTarget)
                            }
                        }
                    )
            }
    }
}