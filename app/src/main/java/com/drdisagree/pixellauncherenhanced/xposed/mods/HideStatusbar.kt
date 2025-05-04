package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_STATUSBAR
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class HideStatusbar(context: Context) : ModPack(context) {

    private var hideStatusbarEnabled = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideStatusbarEnabled = getBoolean(LAUNCHER_HIDE_STATUSBAR, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_HIDE_STATUSBAR -> restartLauncher(mContext)
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
                if (!hideStatusbarEnabled) return@runAfter

                val launcherActivity = param.thisObject as Activity

                val noStatusBarStateListener = object : CustomStateListener() {
                    override fun onStateTransitionStart() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            launcherActivity.window.decorView.windowInsetsController?.show(
                                WindowInsetsCompat.Type.statusBars()
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            launcherActivity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        }
                    }

                    override fun onStateTransitionComplete() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            launcherActivity.window.decorView.windowInsetsController?.hide(
                                WindowInsetsCompat.Type.statusBars()
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            launcherActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        }
                    }
                }

                param.thisObject.setExtraField(
                    "noStatusBarStateListener",
                    getListener(noStatusBarStateListener)
                )

                param.thisObject
                    .callMethod("getStateManager")
                    .callMethod(
                        "addStateListener",
                        param.thisObject.getExtraField("noStatusBarStateListener")
                    )
            }

        quickstepLauncherClass
            .hookMethod("onDestroy")
            .runAfter { param ->
                if (!hideStatusbarEnabled) return@runAfter

                param.thisObject
                    .callMethod("getStateManager")
                    .callMethod(
                        "removeStateListener",
                        param.thisObject.getExtraField("noStatusBarStateListener")
                    )
            }
    }

    private fun getListener(listener: CustomStateListener): Any {
        val listenerClass =
            findClass("com.android.launcher3.statemanager.StateManager\$StateListener")!!

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
            }

            return null
        }

        abstract fun onStateTransitionStart()

        abstract fun onStateTransitionComplete()
    }

    companion object {
        private lateinit var OVERVIEW: Any
    }
}