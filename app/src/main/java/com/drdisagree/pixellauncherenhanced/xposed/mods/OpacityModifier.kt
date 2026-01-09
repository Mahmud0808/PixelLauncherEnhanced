package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_RECENTS_LIVE_TILE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class OpacityModifier(context: Context) : ModPack(context) {

    private var appDrawerBackgroundOpacity: Int = -1
    private var recentsBackgroundOpacity: Int = -1
    private var disableRecentsLiveTile: Boolean = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            appDrawerBackgroundOpacity = getSliderInt(APP_DRAWER_BACKGROUND_OPACITY, -1) * 255 / 100
            recentsBackgroundOpacity = getSliderInt(RECENTS_BACKGROUND_OPACITY, -1) * 255 / 100
            disableRecentsLiveTile = getBoolean(DISABLE_RECENTS_LIVE_TILE, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val allAppsStateClass = findClass("com.android.launcher3.uioverrides.states.AllAppsState")
        val overviewStateClass = findClass("com.android.launcher3.uioverrides.states.OverviewState")
        val quickSwitchStateClass =
            findClass("com.android.launcher3.uioverrides.states.QuickSwitchState")
        val recentsStateClass = findClass("com.android.quickstep.fallback.RecentsState")
        val hintStateClass = findClass(
            "com.android.launcher3.states.HintState",
            "com.android.launcher3.uioverrides.states.HintState"
        )
        val activityAllAppsContainerViewClass =
            findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView")
        val taskbarAllAppsSlideInViewClass = findClass(
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView",
            suppressError = true
        )
        val scrimColorsClass = findClass(
            "com.android.launcher3.views.ScrimColors",
            suppressError = true
        )

        fun XC_MethodHook.MethodHookParam.updateScrimColorVariable() {
            val mScrimColor = thisObject.getFieldSilently("mScrimColor") as? Int

            if (mScrimColor != null) {
                thisObject.setField(
                    "mScrimColor",
                    ColorUtils.setAlphaComponent(
                        result as Int,
                        appDrawerBackgroundOpacity
                    )
                )
            }
        }

        activityAllAppsContainerViewClass
            .hookConstructor()
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                param.updateScrimColorVariable()
            }

        activityAllAppsContainerViewClass
            .hookMethod("updateHeaderScroll")
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                if (appDrawerBackgroundOpacity != 255) {
                    param.thisObject.setFieldSilently("mHeaderColor", Color.TRANSPARENT)
                    param.thisObject.callMethodSilently("invalidateHeader")
                }
            }

        activityAllAppsContainerViewClass
            .hookMethod(
                "getHeaderColor",
                "getScrimColor",
                "getBackgroundColor",
                "getBottomSheetBackgroundColor"
            )
            .suppressError()
            .runBefore { param ->
                if (appDrawerBackgroundOpacity < 0) return@runBefore

                param.updateScrimColorVariable()
            }
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                param.result = ColorUtils.setAlphaComponent(
                    param.result as Int,
                    appDrawerBackgroundOpacity
                )
            }

        allAppsStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                val launcher = param.args[0]
                val isTablet = (launcher
                    .callMethodSilently("getDeviceProfile")
                    .getFieldSilently("isTablet") as? Boolean
                    ?: launcher
                        .callMethodSilently("getDeviceProfile")
                        .getFieldSilently("mDeviceProperties")
                        .getFieldSilently("isTablet") as? Boolean) == true

                if (!isTablet) {
                    if (param.result is Int) {
                        param.result = ColorUtils.setAlphaComponent(
                            param.result as Int,
                            appDrawerBackgroundOpacity
                        )
                    } else {
                        param.result = getScrimColors(
                            param.result,
                            scrimColorsClass!!,
                            appDrawerBackgroundOpacity
                        )
                    }
                }
            }

        taskbarAllAppsSlideInViewClass
            .hookMethod("getScrimColor")
            .runAfter { param ->
                if (appDrawerBackgroundOpacity < 0) return@runAfter

                param.result = ColorUtils.setAlphaComponent(
                    param.result as Int,
                    appDrawerBackgroundOpacity
                )
            }

        overviewStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                if (param.result is Int) {
                    param.result = ColorUtils.setAlphaComponent(
                        param.result as Int,
                        recentsBackgroundOpacity
                    )
                } else {
                    param.result = getScrimColors(
                        param.result,
                        scrimColorsClass!!,
                        recentsBackgroundOpacity
                    )
                }
            }

        quickSwitchStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                val launcher = param.args[0]
                val deviceProfile = launcher.callMethod("getDeviceProfile")
                val isTaskbarPresentInApps =
                    deviceProfile.callMethodSilently("isTaskbarPresentInApps") as? Boolean
                        ?: deviceProfile.getField("isTaskbarPresentInApps") as Boolean
                val currentResult = param.result as Int

                if (currentResult != Color.TRANSPARENT && !isTaskbarPresentInApps) {
                    if (param.result is Int) {
                        param.result = ColorUtils.setAlphaComponent(
                            param.result as Int,
                            recentsBackgroundOpacity
                        )
                    } else {
                        param.result = getScrimColors(
                            param.result,
                            scrimColorsClass!!,
                            recentsBackgroundOpacity
                        )
                    }
                }
            }

        recentsStateClass
            .hookMethod("getScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                if (param.result is Int) {
                    param.result = ColorUtils.setAlphaComponent(
                        param.result as Int,
                        recentsBackgroundOpacity
                    )
                } else {
                    param.result = getScrimColors(
                        param.result,
                        scrimColorsClass!!,
                        recentsBackgroundOpacity
                    )
                }
            }

        hintStateClass
            .hookMethod("getWorkspaceScrimColor")
            .runAfter { param ->
                if (recentsBackgroundOpacity < 0) return@runAfter

                if (param.result is Int) {
                    param.result = ColorUtils.setAlphaComponent(
                        param.result as Int,
                        recentsBackgroundOpacity
                    )
                } else {
                    param.result = getScrimColors(
                        param.result,
                        scrimColorsClass!!,
                        recentsBackgroundOpacity
                    )
                }
            }

        val recentsViewClass = findClass("com.android.quickstep.views.RecentsView")

        recentsViewClass
            .hookMethod("onGestureAnimationEnd")
            .runAfter { param ->
                if (!disableRecentsLiveTile) return@runAfter

                param.thisObject.callMethod(
                    "switchToScreenshot",
                    Runnable {
                        param.thisObject.callMethod(
                            "finishRecentsAnimation",
                            true /* toRecents */,
                            false /* shouldPip */,
                            null
                        )
                    }
                )
            }
    }

    private fun getScrimColors(
        scrimColors: Any,
        scrimColorsClass: Class<*>,
        backgroundOpacity: Int
    ): Any {
        val backgroundColor = scrimColors.callMethodSilently("getBackgroundColor") as? Int
            ?: scrimColors.getField("backgroundColor") as Int
        val foregroundColor = scrimColors.callMethodSilently("getForegroundColor") as? Int
            ?: scrimColors.getField("foregroundColor") as Int

        return scrimColorsClass
            .getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .newInstance(
                ColorUtils.setAlphaComponent(backgroundColor, backgroundOpacity),
                foregroundColor
            )
    }
}