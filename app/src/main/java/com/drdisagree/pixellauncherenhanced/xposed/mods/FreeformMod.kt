package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Rect
import android.os.Build
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE_PROGRESS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_MODE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformHintView
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformHintViewController
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils.currentToFreeform
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils.startAppBubble
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils.startFreeformByIntent
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformUtils.startFreeformFromRecents
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.newInstance
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
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
        val dragLayerClass = findClass("com.android.launcher3.dragndrop.DragLayer")

        // ── DragLayer lifecycle: init FreeformHintView ──
        dragLayerClass?.hookConstructor()
            ?.parameters(Context::class.java, android.util.AttributeSet::class.java)
            ?.runAfter { param ->
                if (!freeformEnabled) return@runAfter
                try {
                    FreeformHintViewController.init(
                        param.thisObject as android.view.ViewGroup,
                        param.args[0] as Context,
                        appContext.resources,
                        BuildConfig.APPLICATION_ID
                    )
                } catch (e: Exception) {
                    log("FreeformMod", "FreeformHintView init error: ${e.message}")
                }
            }

        // ── onCurrentShiftUpdated: hint view updates during gesture ──
        absSwipeClass
            .hookMethod("onCurrentShiftUpdated")
            .runAfter { param ->
                if (!freeformEnabled) return@runAfter
                val mProgress = param.thisObject
                    .getField("mCurrentShift")
                    .getField("value") as Float
                updateHintView(param.thisObject, mProgress, param)
            }

        // ── Gesture end (setEndTarget / onGestureEnd): reset hint view ──
        absSwipeClass
            .hookMethod("onGestureEnded")
            .runAfter {
                FreeformHintViewController.reset()
            }

        // ── initStateCallbacks: AOSP/3rd-party freeform trigger ──
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

                                when (freeformMode) {
                                    FreeformUtils.Variant.AOSP.id -> {
                                        startFreeformFromRecents(
                                            mTask,
                                            activityManagerWrapperClass.newInstance(),
                                            mBound
                                        )
                                    }

                                    FreeformUtils.Variant.BUBBLE.id -> {
                                        // Launch bubble immediately, with release shrink animation
                                        startAppBubble(mContext, mTask)
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

                                val hintView = FreeformHintViewController.getHintView()
                                if (hintView != null) {
                                    hintView.playReleaseAnimation(null)
                                }
                                param.thisObject
                                    .getField("mGestureState")
                                    .callMethod("setEndTarget", homeTarget)
                            }
                        }
                    )
            }
    }

    // ── FreeformHintView helpers ──

    private fun getDisplayRotation(handler: Any): Int {
        return try {
            val rv = handler.getField("mRecentsView")
            val oriState = rv.callMethod("getPagedViewOrientedState")
            oriState.callMethod("getDisplayRotation") as Int
        } catch (e: Exception) {
            android.view.Surface.ROTATION_0
        }
    }

    private fun getRecentsView(handler: Any): Any? {
        return try {
            handler.getField("mRecentsView")
        } catch (e: Exception) { null }
    }

    private fun getRunningTaskView(recentsView: Any): Any? {
        return try {
            recentsView.callMethod("getRunningTaskView")
        } catch (e: Exception) { null }
    }

    /**
     * Get the task thumbnail bounds relative to DragLayer (for freeform hint positioning).
     * Always uses thumbnail bounds regardless of mode; bubble-specific animations
     * are handled separately in the launch phase.
     */
    private fun getTaskBounds(handler: Any): Rect? {
        val recentsView = getRecentsView(handler) ?: return null
        val runningTaskView = getRunningTaskView(recentsView) ?: return null
        return try {
            val bounds = Rect()
            runningTaskView.javaClass.getMethod(
                "getThumbnailBounds", Rect::class.java, Boolean::class.javaPrimitiveType
            ).invoke(runningTaskView, bounds, true)
            bounds
        } catch (e: Exception) { null }
    }

    private fun updateHintView(handler: Any, shift: Float, param:  XC_MethodHook.MethodHookParam) {
        if (!freeformEnabled) return

        // Hint starts at (trigger threshold - 0.7)
        val hintStart = offProgress - 0.7f

        val phase = when {
            shift < hintStart -> FreeformHintView.HintPhase.HIDDEN
            shift < offProgress -> FreeformHintView.HintPhase.SWIPE_UP_HINT
            else -> FreeformHintView.HintPhase.EXPAND
        }

        val hintView = FreeformHintViewController.getHintView() ?: return

        // Haptic at trigger threshold
        if (phase != hintView.phase) {
            param.thisObject.callMethod("performHapticFeedback")
        }

        hintView.setDisplayRotation(getDisplayRotation(handler))
        hintView.setPhase(phase)

        if (phase == FreeformHintView.HintPhase.EXPAND) {
            val bounds = getTaskBounds(handler)
            if (bounds != null) {
                hintView.setTaskBounds(bounds)
            }
        }
    }
}
