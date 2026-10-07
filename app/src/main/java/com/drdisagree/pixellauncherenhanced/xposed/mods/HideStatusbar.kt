package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_STATUSBAR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class HideStatusbar(context: Context) : ModPack(context) {

    private var hideStatusbarEnabled = false
    private var launcherRef: WeakReference<Activity>? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideStatusbarEnabled = getBoolean(LAUNCHER_HIDE_STATUSBAR, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_HIDE_STATUSBAR -> Handler(Looper.getMainLooper()).post {
                val launcher = launcherRef?.get()?.takeUnless { it.isDestroyed } ?: return@post
                if (hideStatusbarEnabled && !launcher.isInOverview()) hideStatusBar(launcher) else showStatusBar(launcher)
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherStateClass = findClass("com.android.launcher3.LauncherState")!!
        OVERVIEW = launcherStateClass.getStaticField("OVERVIEW")

        val quickstepLauncherClass =
            findClass("com.android.launcher3.uioverrides.QuickstepLauncher")

        quickstepLauncherClass
            .hookMethod("onCreate")
            .runAfter { param ->
                val launcherActivity = param.thisObject as Activity
                launcherRef = WeakReference(launcherActivity)

                val noStatusBarStateListener = object : CustomStateListener() {
                    override fun onStateTransitionStart() {
                        if (hideStatusbarEnabled) showStatusBar(launcherActivity)
                    }

                    override fun onStateTransitionComplete() {
                        if (hideStatusbarEnabled) hideStatusBar(launcherActivity)
                    }
                }

                val listener = getListener(noStatusBarStateListener)
                launcherActivity.setExtraField(LISTENER_KEY, listener)
                launcherActivity.callMethod("getStateManager").callMethod("addStateListener", listener)

                if (hideStatusbarEnabled) hideStatusBar(launcherActivity)
            }

        quickstepLauncherClass
            .hookMethod("onDestroy")
            .runAfter { param ->
                val listener = param.thisObject.getExtraFieldSilently(LISTENER_KEY) ?: return@runAfter
                param.thisObject
                    .callMethodSilently("getStateManager")
                    ?.callMethodSilently("removeStateListener", listener)
            }
    }

    private fun Activity.isInOverview(): Boolean {
        val stateManager = callMethodSilently("getStateManager") ?: return false
        val state = stateManager.getFieldSilently("mState") ?: stateManager.callMethodSilently("getState")
        return state == OVERVIEW
    }

    private fun hideStatusBar(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.decorView.windowInsetsController?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun showStatusBar(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.decorView.windowInsetsController?.show(WindowInsetsCompat.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun getListener(listener: CustomStateListener): Any {
        val listenerClass =
            findClass($$"com.android.launcher3.statemanager.StateManager$StateListener")!!

        return Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
            listener
        )
    }

    private abstract class CustomStateListener : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
            when (method?.name) {
                "onStateTransitionStart" -> {
                    val toState = args!![0]

                    if (toState == OVERVIEW) {
                        onStateTransitionStart()
                    }
                }

                "onStateTransitionComplete" -> {
                    val finalState = args!![0]

                    if (finalState != OVERVIEW) {
                        onStateTransitionComplete()
                    }
                }

                "equals" -> return proxy === args?.getOrNull(0)
                "hashCode" -> return System.identityHashCode(proxy)
            }

            return null
        }

        abstract fun onStateTransitionStart()

        abstract fun onStateTransitionComplete()
    }

    companion object {
        private const val LISTENER_KEY = "pleNoStatusBarStateListener"
        private lateinit var OVERVIEW: Any
    }
}
