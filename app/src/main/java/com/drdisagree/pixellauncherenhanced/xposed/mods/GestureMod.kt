package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOUBLE_TAP_TO_SLEEP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_COVER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_TAP_FIRST_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SWIPE_ACTIONS
import com.drdisagree.pixellauncherenhanced.data.common.IconSwipeActions
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
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
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import kotlin.math.abs
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

    private var swipeActions: Map<String, Map<IconSwipeActions.Direction, IconSwipeActions.Action>> = emptyMap()
    private var folderTapFirstApp = false
    private var folderCover = false
    private val coverBounds = Rect()
    private var coverHiddenPreview: WeakReference<View>? = null
    private var allowFolderOpen = false
    private var swipeTarget: WeakReference<View>? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDownTime = 0L
    private var swipeConsumed = false
    private var pendingSwipe: (() -> Unit)? = null
    private var bubbleTextViewClass: Class<*>? = null
    private var folderIconClass: Class<*>? = null

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
            swipeActions = IconSwipeActions.parse(getString(ICON_SWIPE_ACTIONS, null))
            folderTapFirstApp = getBoolean(FOLDER_TAP_FIRST_APP, false)
            folderCover = folderTapFirstApp && getBoolean(FOLDER_COVER, false)
        }

        val swipeTypes = swipeActions.values.flatMap { it.values }.map { it.type }
        val allActions = actions.values + swipeTypes
        if (GestureAction.FLASHLIGHT in allActions) registerTorchCallback()
        if (allActions.any { it == GestureAction.SLEEP || it == GestureAction.SCREENSHOT }) {
            connectRootProxy()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        launcherStateClass = findClass("com.android.launcher3.LauncherState", suppressError = true)
        floatingViewClass = findClass("com.android.launcher3.AbstractFloatingView", suppressError = true)
        systemUiProxyClass = findClass("com.android.quickstep.SystemUiProxy", suppressError = true)
        bubbleTextViewClass = findClass("com.android.launcher3.BubbleTextView", suppressError = true)
        folderIconClass = findClass("com.android.launcher3.folder.FolderIcon", suppressError = true)

        findClass("com.android.launcher3.folder.Folder", suppressError = true)
            .hookMethod("animateOpen")
            .suppressError()
            .runBefore { param ->
                if (!folderTapFirstApp || allowFolderOpen) return@runBefore
                val launcher = launcherRef?.get()
                if (launcher.callMethodSilently("getDragController").callMethodSilently("isDragging") == true) return@runBefore
                val folderIcon = param.thisObject.getFieldSilently("mFolderIcon") as? View ?: return@runBefore
                if (coverDrawable(folderIcon) == null && firstFolderItem(folderIcon) == null) return@runBefore
                param.result = null
                folderIcon.post { openFirstFolderApp(folderIcon) }
            }

        findClass("com.android.launcher3.views.FloatingIconView", suppressError = true)
            .hookMethod("setIcon")
            .suppressError()
            .runBefore { param ->
                if (!folderCover) return@runBefore
                val original = param.thisObject.getFieldSilently("mOriginalIcon") as? View ?: return@runBefore
                if (folderIconClass?.isInstance(original) != true) return@runBefore
                val cover = coverDrawable(original) ?: return@runBefore
                param.args[0] = cover.constantState?.newDrawable()?.mutate() ?: return@runBefore
                param.args[1] = null
            }

        val previewBackgroundClass = findClass("com.android.launcher3.folder.PreviewBackground", suppressError = true)
        listOf("drawBackground", "drawBackgroundStroke", "drawShadow").forEach { name ->
            previewBackgroundClass
                .hookMethod(name)
                .suppressError()
                .runBefore { param ->
                    if (!folderCover) return@runBefore
                    val folderIcon = param.thisObject.getFieldSilently("mInvalidateDelegate") as? View ?: return@runBefore
                    if (folderIconClass?.isInstance(folderIcon) != true || coverDrawable(folderIcon) == null) return@runBefore
                    param.result = null
                }
        }

        folderIconClass
            .hookMethod("dispatchDraw")
            .suppressError()
            .runBefore { param ->
                coverHiddenPreview = null
                if (!folderCover) return@runBefore
                val folderIcon = param.thisObject as? View ?: return@runBefore
                if (coverDrawable(folderIcon) == null) return@runBefore
                if (folderIcon.getFieldSilently("mBackgroundIsVisible") != true) return@runBefore
                folderIcon.getFieldSilently("mPreviewItemManager").callMethodSilently("recomputePreviewDrawingParams")
                folderIcon.setFieldSilently("mBackgroundIsVisible", false)
                coverHiddenPreview = WeakReference(folderIcon)
            }
            .runAfter { param ->
                val hidden = coverHiddenPreview?.get()
                coverHiddenPreview = null
                if (hidden !== param.thisObject) return@runAfter
                hidden.setFieldSilently("mBackgroundIsVisible", true)
                drawFolderCover(hidden, param.args[0] as? Canvas ?: return@runAfter)
            }


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
                if (swipeActions.isNotEmpty() || folderTapFirstApp) {
                    val dragLayer = param.thisObject as? ViewGroup
                    val replacement = dragLayer?.let { handleIconSwipe(it, param.args[0] as MotionEvent) }
                    if (replacement === CONSUMED) {
                        param.result = true
                        return@runBefore
                    }
                    if (replacement is MotionEvent) {
                        param.args[0] = replacement
                        return@runBefore
                    }
                }

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

    private fun handleIconSwipe(dragLayer: ViewGroup, event: MotionEvent): Any? {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeConsumed = false
                pendingSwipe = null
                swipeTarget = null
                rememberLauncher(dragLayer.getAnyField("mContainer", "mActivity"))

                val target = iconAt(dragLayer, event.rawX, event.rawY)?.takeIf { hasSwipe(it) } ?: return null
                swipeTarget = WeakReference(target)
                swipeDownX = event.rawX
                swipeDownY = event.rawY
                swipeDownTime = event.eventTime
            }

            MotionEvent.ACTION_MOVE -> {
                if (swipeConsumed) return CONSUMED
                val target = swipeTarget?.get() ?: return null
                if (event.eventTime - swipeDownTime > ViewConfiguration.getLongPressTimeout()) {
                    swipeTarget = null
                    return null
                }

                val dx = event.rawX - swipeDownX
                val dy = event.rawY - swipeDownY
                val slop = ViewConfiguration.get(mContext).scaledTouchSlop
                if (abs(dx) > slop && abs(dx) >= abs(dy)) {
                    swipeTarget = null
                    return null
                }
                if (abs(dy) < slop || abs(dy) < abs(dx) * SWIPE_DIRECTION_RATIO) return null

                val direction = if (dy < 0) IconSwipeActions.Direction.UP else IconSwipeActions.Direction.DOWN
                val run = swipeRunnable(target, direction)
                swipeTarget = null
                if (run == null || !isHomeReadyForIcons()) return null

                swipeConsumed = true
                pendingSwipe = run
                target.cancelLongPress()
                target.isPressed = false
                return MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val tapped = swipeTarget?.get()
                swipeTarget = null
                if (event.actionMasked == MotionEvent.ACTION_UP && !swipeConsumed && tapped != null &&
                    folderTapFirstApp && folderIconClass?.isInstance(tapped) == true &&
                    event.eventTime - swipeDownTime < ViewConfiguration.getLongPressTimeout() &&
                    hypot(event.rawX - swipeDownX, event.rawY - swipeDownY) < ViewConfiguration.get(mContext).scaledTouchSlop &&
                    isHomeReadyForIcons()
                ) {
                    tapped.cancelLongPress()
                    tapped.isPressed = false
                    tapped.post { openFirstFolderApp(tapped) }
                    return MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                }
                val run = pendingSwipe
                pendingSwipe = null
                if (swipeConsumed) {
                    swipeConsumed = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) run?.invoke()
                    return CONSUMED
                }
            }

            else -> if (swipeConsumed) return CONSUMED
        }
        return null
    }

    private fun iconAt(root: ViewGroup, rawX: Float, rawY: Float): View? {
        val location = IntArray(2)

        fun search(view: View): View? {
            if (!view.isShown) return null
            view.getLocationOnScreen(location)
            if (rawX < location[0] || rawY < location[1] ||
                rawX > location[0] + view.width * view.scaleX || rawY > location[1] + view.height * view.scaleY
            ) return null
            if (folderIconClass?.isInstance(view) == true || bubbleTextViewClass?.isInstance(view) == true) return view
            if (view !is ViewGroup) return null
            for (index in view.childCount - 1 downTo 0) {
                search(view.getChildAt(index))?.let { return it }
            }
            return null
        }

        return search(root)
    }

    private fun hasSwipe(target: View): Boolean {
        if (folderIconClass?.isInstance(target) == true) return folderTapFirstApp
        val key = swipeKey(target) ?: return false
        return swipeActions[key]?.isNotEmpty() == true
    }

    private fun swipeRunnable(target: View, direction: IconSwipeActions.Direction): (() -> Unit)? {
        if (folderIconClass?.isInstance(target) == true) {
            if (!folderTapFirstApp || direction != IconSwipeActions.Direction.UP) return null
            return {
                target.post {
                    allowFolderOpen = true
                    target.performClick()
                    allowFolderOpen = false
                }
            }
        }
        val key = swipeKey(target) ?: return null
        val action = swipeActions[key]?.get(direction) ?: return null
        return { runAction(action.type, action.app) }
    }

    private fun swipeKey(view: View): String? {
        val info = view.tag ?: return null
        val target = info.callMethodSilently("getTargetComponent") as? ComponentName
        val intent = info.getFieldSilently("intent") as? Intent
        val packageName = target?.packageName ?: intent?.component?.packageName ?: intent?.`package`

        return when (info.getFieldSilently("itemType")) {
            ITEM_TYPE_APPLICATION -> target?.flattenToString()
            ITEM_TYPE_DEEP_SHORTCUT -> {
                val id = intent?.getStringExtra(EXTRA_SHORTCUT_ID)
                    ?: info.callMethodSilently("getDeepShortcutId") as? String
                    ?: return null
                IconPackManager.shortcutComponent(packageName ?: return null, id).flattenToString()
            }

            ITEM_TYPE_SHORTCUT -> {
                val uri = intent?.toUri(0) ?: return null
                IconPackManager.shortcutComponent(packageName ?: return null, IconPackManager.legacyShortcutId(uri)).flattenToString()
            }

            else -> null
        }
    }

    private fun firstFolderItem(folderIcon: View): View? =
        (folderIcon.getFieldSilently("mFolder").callMethodSilently("getIconsInReadingOrder") as? List<*>)
            ?.firstOrNull() as? View

    private fun drawFolderCover(folderIcon: View, canvas: Canvas) {
        val icon = coverDrawable(folderIcon) ?: return
        val background = folderIcon.getFieldSilently("mBackground") ?: return
        val left = background.getFieldSilently("basePreviewOffsetX") as? Int ?: return
        val top = background.getFieldSilently("basePreviewOffsetY") as? Int ?: return
        val size = background.getFieldSilently("previewSize") as? Int ?: return
        if (size <= 0) return

        val iconSize = (folderIcon.getFieldSilently("mFolderName")?.getFieldSilently("mIconSize") as? Int)?.takeIf { it > 0 } ?: size
        val scale = background.getFieldSilently("mScale") as? Float ?: 1f
        val scaled = (iconSize * scale).toInt()
        val centerX = left + size / 2
        val centerY = top + size / 2
        coverBounds.set(centerX - scaled / 2, centerY - scaled / 2, centerX - scaled / 2 + scaled, centerY - scaled / 2 + scaled)

        val previous = Rect(icon.bounds)
        icon.bounds = coverBounds
        icon.draw(canvas)
        icon.bounds = previous
    }

    private fun firstPreviewParams(folderIcon: View): Any? =
        (folderIcon.getFieldSilently("mPreviewItemManager")?.getFieldSilently("mFirstPageParams") as? List<*>)
            ?.firstOrNull { it?.getFieldSilently("drawable") is Drawable && it.getFieldSilently("item") != null }

    private fun coverDrawable(folderIcon: View): Drawable? {
        firstPreviewParams(folderIcon)?.let { return it.getFieldSilently("drawable") as? Drawable }
        return firstFolderItem(folderIcon)?.getFieldSilently("mIcon") as? Drawable
    }

    private fun openFirstFolderApp(folderIcon: View) {
        val first = firstPreviewParams(folderIcon)?.getFieldSilently("item") ?: firstFolderItem(folderIcon)?.tag ?: run {
            val folderInfo = folderIcon.getFieldSilently("mInfo") ?: folderIcon.tag ?: return
            val contents = (folderInfo.getFieldSilently("contents") ?: folderInfo.callMethodSilently("getContents")) as? List<*>
            contents?.filterNotNull()?.minByOrNull { it.getFieldSilently("rank") as? Int ?: Int.MAX_VALUE }
        } ?: return
        val intent = first.callMethodSilently("getIntent") as? Intent ?: return

        val launcher = launcherRef?.get()
        val launched = launcher.callMethodSilently("startActivitySafely", folderIcon, intent, first) != null
        if (launched) return
        val context = launcher as? Context ?: mContext
        runCatching {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { log(this@GestureMod, it) }
    }

    private fun perform(gesture: HomeGesture) {
        runAction(actionOf(gesture), apps[gesture])
    }

    private fun runAction(action: GestureAction, app: String?) {
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
            GestureAction.OPEN_APP -> openApp(app)
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

    private fun isNormalState(launcher: Any): Boolean {
        val normal = launcherStateClass.getStaticFieldSilently("NORMAL") ?: return true
        val stateManager = launcher.callMethodSilently("getStateManager") ?: launcher.getFieldSilently("mStateManager")
        val state = stateManager?.getFieldSilently("mState") ?: return true
        return state === normal
    }

    private fun isHomeReadyForIcons(): Boolean {
        val launcher = launcherRef?.get() ?: return false
        val inNormal = isNormalState(launcher)
        val topOpenView = floatingViewClass.callStaticMethodSilently("getTopOpenView", launcher)

        return inNormal && (topOpenView == null || topOpenView.javaClass.simpleName.startsWith("Floating"))
    }

    private fun isHomeIdle(): Boolean {
        val launcher = launcherRef?.get() ?: return false
        val inNormal = isNormalState(launcher)
        val topOpenView = floatingViewClass.callStaticMethodSilently("getTopOpenView", launcher)

        return inNormal && topOpenView == null
    }

    private fun MotionEvent.span(): Float = hypot(getX(0) - getX(1), getY(0) - getY(1))

    init {
        instance = WeakReference(this)
    }

    companion object {
        private var instance: WeakReference<GestureMod>? = null

        fun coversFolder(folderIcon: View): Boolean {
            val mod = instance?.get() ?: return false
            return mod.folderCover && mod.folderIconClass?.isInstance(folderIcon) == true && mod.coverDrawable(folderIcon) != null
        }

        private const val DOUBLE_TAP_TIMEOUT = 400L
        private const val TAP_DISTANCE_THRESHOLD = 50f
        private const val PINCH_MIN_DISTANCE_DP = 64f
        private const val PINCH_IN_RATIO = 0.7f
        private const val PINCH_OUT_RATIO = 1.4f
        private const val SWIPE_DIRECTION_RATIO = 1.2f
        private const val ITEM_TYPE_APPLICATION = 0
        private const val ITEM_TYPE_SHORTCUT = 1
        private const val ITEM_TYPE_DEEP_SHORTCUT = 6
        private const val EXTRA_SHORTCUT_ID = "shortcut_id"
        private val CONSUMED = Any()
    }
}
