package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_RECENTS_LIVE_TILE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
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
    private var folderBackgroundOpacity: Int = -1

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            appDrawerBackgroundOpacity = getSliderInt(APP_DRAWER_BACKGROUND_OPACITY, -1) * 255 / 100
            recentsBackgroundOpacity = getSliderInt(RECENTS_BACKGROUND_OPACITY, -1) * 255 / 100
            disableRecentsLiveTile = getBoolean(DISABLE_RECENTS_LIVE_TILE, false)
            folderBackgroundOpacity = getSliderInt(FOLDER_BACKGROUND_OPACITY, -1) * 255 / 100
        }

        if (key.firstOrNull() == FOLDER_BACKGROUND_OPACITY) reloadLauncher(mContext)
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

                val deviceProfile = param.args[0].callMethodSilently("getDeviceProfile")
                val isTablet = (deviceProfile.getFieldSilently("isTablet") as? Boolean
                    ?: deviceProfile
                        .getAnyFieldSilently("mDeviceProperties", "deviceProperties")
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

        findClass("com.android.launcher3.folder.Folder")
            .hookMethod("onFinishInflate", "animateOpen")
            .suppressError()
            .runAfter { param ->
                (param.thisObject.getFieldSilently("mBackground") as? GradientDrawable)
                    ?.alpha = folderBackgroundOpacity.takeIf { it >= 0 } ?: 255
            }

        findClass("com.android.launcher3.folder.PreviewBackground")
            .hookMethod("drawBackground")
            .suppressError()
            .runBefore { param ->
                if (folderBackgroundOpacity < 0) return@runBefore

                val color = param.thisObject.getFieldSilently("mBgColor") as? Int ?: return@runBefore
                param.setObjectExtra(PREVIEW_ORIGINAL_COLOR, color)
                param.thisObject.setFieldSilently(
                    "mBgColor",
                    ColorUtils.setAlphaComponent(color, Color.alpha(color) * folderBackgroundOpacity / 255)
                )
            }
            .runAfter { param ->
                val color = param.getObjectExtra(PREVIEW_ORIGINAL_COLOR) as? Int ?: return@runAfter
                param.thisObject.setFieldSilently("mBgColor", color)
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

    companion object {
        private const val PREVIEW_ORIGINAL_COLOR = "plePreviewOriginalColor"
    }
}
