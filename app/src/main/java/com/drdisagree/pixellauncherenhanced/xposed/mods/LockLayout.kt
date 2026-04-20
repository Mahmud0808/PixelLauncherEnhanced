package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LOCK_LAYOUT
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class LockLayout(context: Context) : ModPack(context) {

    private var lockLayout = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            lockLayout = getBoolean(LOCK_LAYOUT, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val dragControllerClass = findClass("com.android.launcher3.dragndrop.DragController")
        val launcherAppWidgetHostViewClass =
            findClass("com.android.launcher3.widget.LauncherAppWidgetHostView")
        val systemShortcutClass = findClass("com.android.launcher3.popup.SystemShortcut")
        val launcherPopupItemDragHandlerClass = findClass(
            $$"com.android.launcher3.popup.PopupContainerWithArrow$LauncherPopupItemDragHandler",
            "com.android.launcher3.popup.LauncherPopupItemDragHandler",
        )
        val optionsPopupViewClass = findClass("com.android.launcher3.views.OptionsPopupView")
        val taskbarDragControllerClass = findClass(
            "com.android.launcher3.taskbar.TaskbarDragController",
            suppressError = true
        )

        dragControllerClass
            .hookMethod("onControllerInterceptTouchEvent")
            .runBefore { param ->
                if (!lockLayout) return@runBefore

                param.thisObject.callMethod("cancelDrag")
                param.result = false
            }

        taskbarDragControllerClass
            .hookMethod("endDrag")
            .runBefore { param ->
                if (!lockLayout) return@runBefore

                val mDragObject = param.thisObject.getFieldSilently("mDragObject")
                val dragView = mDragObject.getFieldSilently("dragView")

                if (mDragObject == null || dragView == null) {
                    param.result = null
                }
            }

        taskbarDragControllerClass
            .hookMethod("setupReturnDragAnimator")
            .runBefore { param ->
                if (!lockLayout) return@runBefore

                val taskbarReturnPropertiesListener = param.args[param.args.size - 1]

                taskbarReturnPropertiesListener::class.java
                    .hookMethod("updateDragShadow")
                    .runBefore runBefore2@{ param2 ->
                        if (!lockLayout) return@runBefore2

                        param2.result = null
                    }
            }

        launcherAppWidgetHostViewClass
            .hookMethod("onLongClick")
            .runBefore { param ->
                if (!lockLayout) return@runBefore

                param.result = true
            }

        systemShortcutClass
            .hookMethod("onClick")
            .runBefore { param ->
                if (!lockLayout) return@runBefore

                param.result = null
            }

        launcherPopupItemDragHandlerClass
            .hookMethod("onLongClick")
            .runBefore { param ->
                if (!lockLayout) return@runBefore

                param.result = false
            }

        fun showLayoutLockedToast() {
            Toast.makeText(
                mContext,
                modRes.getString(R.string.layout_is_locked),
                Toast.LENGTH_SHORT
            ).show()
        }

        if (optionsPopupViewClass.hasMethod("openWidgets")) {
            optionsPopupViewClass
                .hookMethod("openWidgets")
                .runBefore { param ->
                    if (!lockLayout) return@runBefore

                    showLayoutLockedToast()
                    param.result = null
                }
        } else if (optionsPopupViewClass.hasMethod("onWidgetsClicked")) {
            optionsPopupViewClass
                .hookMethod("onWidgetsClicked")
                .runBefore { param ->
                    if (!lockLayout) return@runBefore

                    showLayoutLockedToast()
                    param.result = false
                }
        } else if (optionsPopupViewClass.hasMethod("getOptions")) {
            val optionItemClass =
                findClass($$"com.android.launcher3.views.OptionsPopupView$OptionItem")

            @SuppressLint("DiscouragedApi")
            val widgetButtonTextId = mContext.resources.getIdentifier(
                "widget_button_text",
                "string",
                mContext.packageName
            )
            val widgetButtonText = mContext.getText(widgetButtonTextId)

            optionItemClass
                .hookConstructor()
                .runBefore { param ->
                    if (!lockLayout) return@runBefore

                    val shouldReplace = if (param.args[0] is Context) {
                        param.args[1] as Int == widgetButtonTextId
                    } else {
                        param.args[0] as CharSequence == widgetButtonText
                    }

                    if (shouldReplace) {
                        param.args[param.args.size - 1] = View.OnLongClickListener {
                            showLayoutLockedToast()
                            true
                        }
                    }
                }
        } else {
            log("Suitable method not found in OptionsPopupView class.")
        }
    }
}