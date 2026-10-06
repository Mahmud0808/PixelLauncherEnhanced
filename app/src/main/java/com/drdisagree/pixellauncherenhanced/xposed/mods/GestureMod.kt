package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOUBLE_TAP_TO_SLEEP
import com.drdisagree.pixellauncherenhanced.data.enums.GestureAction
import com.drdisagree.pixellauncherenhanced.data.enums.HomeGesture
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.connectRootProxy
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.VibrationUtils
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import kotlin.math.hypot

class GestureMod(context: Context) : ModPack(context) {

    private val actions = mutableMapOf<HomeGesture, GestureAction>()
    private val apps = mutableMapOf<HomeGesture, String?>()

    private var firstTapTime: Long = 0
    private var firstTapX: Float = 0f
    private var firstTapY: Float = 0f
    private var isFirstTapRunning = false
    private var isFirstTapComplete = false
    private var isSecondTapPending = false

    private var pinchEligible = false
    private var pinchTracking = false
    private var pinchConsumed = false
    private var pinchStartSpan = 0f
    private var pendingPinch: HomeGesture? = null

    private var launcherRef: WeakReference<Any>? = null
    private var launcherStateClass: Class<*>? = null
    private var floatingViewClass: Class<*>? = null
    private var systemUiProxyClass: Class<*>? = null

    private var torchCameraId: String? = null
    private var torchOn = false
    private var torchCallbackRegistered = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            HomeGesture.entries.forEach { gesture ->
                val fallback = if (gesture == HomeGesture.DOUBLE_TAP && getBoolean(DOUBLE_TAP_TO_SLEEP, false)) {
                    GestureAction.SLEEP.value
                } else {
                    GestureAction.NONE.value
                }
                actions[gesture] = GestureAction.fromValue(getListString(gesture.key, fallback))
                apps[gesture] = getString(gesture.appKey, null)
            }
        }

        if (actions.containsValue(GestureAction.FLASHLIGHT)) registerTorchCallback()
        if (actions.values.any { it == GestureAction.SLEEP || it == GestureAction.SCREENSHOT }) {
            connectRootProxy()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        launcherStateClass = findClass("com.android.launcher3.LauncherState", suppressError = true)
        floatingViewClass = findClass("com.android.launcher3.AbstractFloatingView", suppressError = true)
        systemUiProxyClass = findClass("com.android.quickstep.SystemUiProxy", suppressError = true)

        findClass("com.android.launcher3.touch.WorkspaceTouchListener")
            .hookMethod("onTouch")
            .runAfter { param ->
                if (actionOf(HomeGesture.DOUBLE_TAP) == GestureAction.NONE) return@runAfter

                rememberLauncher(param.thisObject.getFieldSilently("mLauncher"))
                detectDoubleTap(param.args[1] as MotionEvent)
            }

        findClass("com.android.launcher3.dragndrop.DragLayer")
            .hookMethod("dispatchTouchEvent")
            .runBefore { param ->
                if (actionOf(HomeGesture.PINCH_IN) == GestureAction.NONE &&
                    actionOf(HomeGesture.PINCH_OUT) == GestureAction.NONE
                ) return@runBefore

                val event = param.args[0] as MotionEvent

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        rememberLauncher(param.thisObject.getAnyField("mContainer", "mActivity"))
                        pinchEligible = isHomeIdle()
                        pinchTracking = false
                        pinchConsumed = false
                        pendingPinch = null
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (pinchEligible && !pinchConsumed && event.pointerCount == 2) {
                            pinchStartSpan = event.span()
                            pinchTracking = pinchStartSpan > 0f
                        } else {
                            pinchTracking = false
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (pinchConsumed) {
                            param.result = true
                            return@runBefore
                        }
                        if (!pinchTracking || event.pointerCount < 2) return@runBefore

                        val span = event.span()
                        val minDistance = PINCH_MIN_DISTANCE_DP * mContext.resources.displayMetrics.density
                        val gesture = when {
                            span < pinchStartSpan * PINCH_IN_RATIO &&
                                    pinchStartSpan - span > minDistance -> HomeGesture.PINCH_IN

                            span > pinchStartSpan * PINCH_OUT_RATIO &&
                                    span - pinchStartSpan > minDistance -> HomeGesture.PINCH_OUT

                            else -> null
                        } ?: return@runBefore

                        pinchTracking = false
                        if (actionOf(gesture) == GestureAction.NONE) return@runBefore

                        pinchConsumed = true
                        pendingPinch = gesture
                        param.args[0] = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_CANCEL
                        }
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        pinchTracking = false
                        if (pinchConsumed) {
                            pinchConsumed = false
                            param.result = true
                            pendingPinch?.takeIf { event.actionMasked == MotionEvent.ACTION_UP }
                                ?.let { perform(it) }
                        }
                        pendingPinch = null
                    }

                    else -> if (pinchConsumed) param.result = true
                }
            }
    }

    private fun detectDoubleTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val currentTime = SystemClock.uptimeMillis()

                if (currentTime - firstTapTime > DOUBLE_TAP_TIMEOUT) {
                    isFirstTapRunning = false
                    isFirstTapComplete = false
                }
                isSecondTapPending = false

                if (!isFirstTapRunning) {
                    firstTapTime = currentTime
                    firstTapX = event.x
                    firstTapY = event.y
                    isFirstTapRunning = true
                } else if (isFirstTapComplete) {
                    val distance = hypot(event.x - firstTapX, event.y - firstTapY)

                    isSecondTapPending = distance <= TAP_DISTANCE_THRESHOLD && isHomeIdle()
                    isFirstTapRunning = false
                    isFirstTapComplete = false
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isSecondTapPending) {
                    isSecondTapPending = false
                    if (isHomeIdle()) perform(HomeGesture.DOUBLE_TAP)
                } else if (isFirstTapRunning && !isFirstTapComplete) {
                    isFirstTapComplete = true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL -> {
                isFirstTapRunning = false
                isFirstTapComplete = false
                isSecondTapPending = false
            }

            MotionEvent.ACTION_MOVE -> {
                val distance = hypot(event.x - firstTapX, event.y - firstTapY)

                if ((isFirstTapRunning || isSecondTapPending) && distance > TAP_DISTANCE_THRESHOLD) {
                    isFirstTapRunning = false
                    isFirstTapComplete = false
                    isSecondTapPending = false
                }
            }
        }
    }

    private fun perform(gesture: HomeGesture) {
        val action = actionOf(gesture)
        if (action == GestureAction.NONE) return

        VibrationUtils.triggerVibration(mContext, 2)

        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.SLEEP -> runRootCommand("input keyevent 223")
            GestureAction.NOTIFICATIONS -> expandStatusBar("expandNotificationsPanel", "expand-notifications")
            GestureAction.QUICK_SETTINGS -> expandStatusBar("expandSettingsPanel", "expand-settings")
            GestureAction.RECENTS -> openRecents()
            GestureAction.SCREENSHOT -> runRootCommand("input keyevent 120")
            GestureAction.ASSISTANT -> startAssistant()
            GestureAction.APP_DRAWER -> openAllApps()
            GestureAction.FLASHLIGHT -> toggleFlashlight()
            GestureAction.OPEN_APP -> openApp(apps[gesture])
        }
    }

    private fun actionOf(gesture: HomeGesture) = actions[gesture] ?: GestureAction.NONE

    private fun runRootCommand(command: String) {
        enqueueProxyCommand { proxy -> proxy.runCommand(command) }
    }

    private fun expandStatusBar(method: String, command: String) {
        runCatching {
            mContext.getSystemService("statusbar").callMethod(method)
        }.onFailure {
            runRootCommand("cmd statusbar $command")
        }
    }

    private fun openRecents() {
        val launcher = launcherRef?.get()
        val overview = launcherStateClass.getStaticFieldSilently("OVERVIEW")
        val stateManager = launcher.callMethodSilently("getStateManager")
            ?: launcher.getFieldSilently("mStateManager")

        if (overview == null || stateManager == null) {
            runRootCommand("input keyevent 187")
        } else {
            stateManager.callMethodSilently("goToState", overview)
        }
    }

    private fun startAssistant() {
        runCatching {
            systemUiProxyClass.getStaticFieldSilently("INSTANCE")!!
                .callMethod("get", mContext)
                .callMethod("startAssistant", Bundle())
        }.onFailure {
            runRootCommand("input keyevent 219")
        }
    }

    private fun openAllApps() {
        val launcher = launcherRef?.get() ?: return
        val allApps = launcherStateClass.getStaticFieldSilently("ALL_APPS") ?: return
        val stateManager = launcher.callMethodSilently("getStateManager")
            ?: launcher.getFieldSilently("mStateManager")

        stateManager.callMethodSilently("goToState", allApps)
    }

    private fun openApp(packageName: String?) {
        if (packageName.isNullOrEmpty()) return

        val context = launcherRef?.get() as? Context ?: mContext
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return

        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { log(this@GestureMod, it) }
    }

    private fun registerTorchCallback() {
        if (torchCallbackRegistered) return

        val cameraManager = mContext.getSystemService(CameraManager::class.java) ?: return

        runCatching {
            torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))

            torchCallbackRegistered = true
        }.onFailure { log(this@GestureMod, it) }
    }

    private fun toggleFlashlight() {
        registerTorchCallback()

        val cameraId = torchCameraId ?: return
        val cameraManager = mContext.getSystemService(CameraManager::class.java) ?: return

        runCatching {
            cameraManager.setTorchMode(cameraId, !torchOn)
        }.onFailure { log(this@GestureMod, it) }
    }

    private fun rememberLauncher(launcher: Any?) {
        if (launcher != null && launcherRef?.get() !== launcher) {
            launcherRef = WeakReference(launcher)
        }
    }

    private fun isHomeIdle(): Boolean {
        val launcher = launcherRef?.get() ?: return false
        val normal = launcherStateClass.getStaticFieldSilently("NORMAL")
        val inNormal = normal == null || launcher.callMethodSilently("isInState", normal) as? Boolean != false
        val topOpenView = floatingViewClass.callStaticMethodSilently("getTopOpenView", launcher)

        return inNormal && topOpenView == null
    }

    private fun MotionEvent.span(): Float = hypot(getX(0) - getX(1), getY(0) - getY(1))

    companion object {
        private const val DOUBLE_TAP_TIMEOUT = 400L
        private const val TAP_DISTANCE_THRESHOLD = 50f
        private const val PINCH_MIN_DISTANCE_DP = 64f
        private const val PINCH_IN_RATIO = 0.7f
        private const val PINCH_OUT_RATIO = 1.4f
    }
}
