package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_ICON_LABELS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_ICON_LABELS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class IconLabels(context: Context) : ModPack(context) {

    private var showDesktopLabels = true
    private var showDrawerLabels = true
    private var nativeLabelHiddenItem: Any? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            showDesktopLabels = getBoolean(DESKTOP_ICON_LABELS, true)
            showDrawerLabels = getBoolean(APP_DRAWER_ICON_LABELS, true)
        }

        when (key.firstOrNull()) {
            DESKTOP_ICON_LABELS -> {
                if (nativeLabelHiddenItem == null || !setNativeLabelsHidden(!showDesktopLabels)) {
                    reloadLauncher(mContext)
                }
            }

            APP_DRAWER_ICON_LABELS -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val bubbleTextViewClass = findClass("com.android.launcher3.BubbleTextView")

        nativeLabelHiddenItem = findClass("com.android.launcher3.LauncherPrefs", suppressError = true)
            .getStaticFieldSilently("WORKSPACE_ITEMS_LABEL_HIDDEN")

        fun XC_MethodHook.MethodHookParam.beforeHookedLabel() {
            val mDisplay = thisObject.getField("mDisplay") as Int
            val itemInfo = args[0]

            fun removeLabel() {
                val title = itemInfo.getFieldSilently("title")
                if (title != null) {
                    itemInfo.setExtraField("titleText", title)
                }
                itemInfo.setField("title", null)
            }

            if (mDisplay.isDesktop() && !showDesktopLabels && !mDisplay.isNativelyHandled()) {
                removeLabel()
            } else if (mDisplay.isDrawer() && !showDrawerLabels) {
                removeLabel()
            }
        }

        fun XC_MethodHook.MethodHookParam.afterHookedLabel() {
            val mDisplay = thisObject.getField("mDisplay") as Int
            val itemInfo = args[0]

            fun reAddLabel() {
                val title = itemInfo.getExtraFieldSilently("titleText")
                if (title != null) {
                    itemInfo.setField("title", title)
                }
            }

            if (mDisplay.isDesktop() && !showDesktopLabels && !mDisplay.isNativelyHandled()) {
                reAddLabel()
            } else if (mDisplay.isDrawer() && !showDrawerLabels) {
                reAddLabel()
            }
        }

        findClass("com.android.launcher3.ShortcutAndWidgetContainer")
            .hookMethod("measureChild")
            .suppressError()
            .runAfter { param ->
                if (showDesktopLabels) return@runAfter

                val child = param.args.getOrNull(0) as? View ?: return@runAfter
                centerIconWithoutLabel(param.thisObject as View, child)
            }

        bubbleTextViewClass
            .hookMethod("onMeasure")
            .suppressError()
            .runBefore { param ->
                val view = param.thisObject as View
                if (!view.isDrawerIconWithoutLabel()) return@runBefore
                if (view.getFieldSilently("mCenterVertically") != true) return@runBefore

                view.setField("mCenterVertically", false)
                param.setObjectExtra(CENTER_VERTICALLY, true)
            }
            .runAfter { param ->
                if (param.getObjectExtra(CENTER_VERTICALLY) != true) return@runAfter

                val view = param.thisObject as View
                view.setField("mCenterVertically", true)

                val iconSize = view.getFieldSilently("mIconSize") as? Int ?: return@runAfter
                val paddingTop = ((view.measuredHeight - iconSize) / 2).coerceAtLeast(0)
                if (view.paddingTop != paddingTop) {
                    view.setPadding(view.paddingLeft, paddingTop, view.paddingRight, view.paddingBottom)
                }
            }

        try {
            bubbleTextViewClass
                .hookMethod("applyLabel")
                .parameters("com.android.launcher3.model.data.ItemInfoWithIcon")
                .throwError()
                .runBefore { param -> param.beforeHookedLabel() }
                .runAfter { param -> param.afterHookedLabel() }
        } catch (_: Throwable) {
            bubbleTextViewClass
                .hookMethod("applyIconAndLabel")
                .parameters("com.android.launcher3.model.data.ItemInfoWithIcon")
                .runBefore { param -> param.beforeHookedLabel() }
                .runAfter { param -> param.afterHookedLabel() }
        }
    }

    private fun centerIconWithoutLabel(container: View, child: View) {
        val containerType = container.getFieldSilently("mContainerType") as? Int ?: return
        if (containerType != CONTAINER_WORKSPACE && containerType != CONTAINER_FOLDER) return

        val iconSize = when {
            child.javaClass.name.endsWith(".FolderIcon") ->
                child.getFieldSilently("mFolderName").getFieldSilently("mIconSize") as? Int
            else -> child.getFieldSilently("mIconSize") as? Int
        } ?: return

        val layoutParams = child.layoutParams ?: return
        if (layoutParams.width <= 0 || layoutParams.height <= 0 || iconSize <= 0) return

        val paddingTop = ((layoutParams.height - iconSize) / 2).coerceAtLeast(0)
        if (child.paddingTop == paddingTop) return

        child.setPadding(child.paddingLeft, paddingTop, child.paddingRight, child.paddingBottom)
        child.measure(
            View.MeasureSpec.makeMeasureSpec(layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(layoutParams.height, View.MeasureSpec.EXACTLY)
        )
    }

    private fun setNativeLabelsHidden(hidden: Boolean): Boolean {
        val launcherPrefs = runCatching {
            findClass("com.android.launcher3.LauncherPrefs").callStaticMethod("get", mContext)
        }.getOrNull() ?: findClass(
            $$"com.android.launcher3.LauncherPrefs$Companion",
            suppressError = true
        ).callStaticMethodSilently("get", mContext)

        return runCatching { launcherPrefs.callMethod("put", nativeLabelHiddenItem, hidden) }
            .onFailure { log(this@IconLabels, it) }
            .isSuccess
    }

    private fun View.isDrawerIconWithoutLabel(): Boolean {
        if (showDrawerLabels) return false
        return (getFieldSilently("mDisplay") as? Int)?.isDrawer() == true
    }

    private fun Int.isNativelyHandled(): Boolean {
        return nativeLabelHiddenItem != null && this == DISPLAY_WORKSPACE
    }

    private fun Int.isDesktop(): Boolean {
        return this in setOf(
            DISPLAY_WORKSPACE,
            DISPLAY_FOLDER,
            DISPLAY_SEARCH_RESULT,
            DISPLAY_SEARCH_RESULT_SMALL
        )
    }

    private fun Int.isDrawer(): Boolean {
        return this in setOf(
            DISPLAY_ALL_APPS,
            DISPLAY_PREDICTION_ROW,
            DISPLAY_SEARCH_RESULT_APP_ROW,
        )
    }

    companion object {
        private const val CENTER_VERTICALLY = "pleCenterVertically"
        const val DISPLAY_WORKSPACE: Int = 0
        const val DISPLAY_ALL_APPS: Int = 1
        const val DISPLAY_FOLDER: Int = 2
        const val DISPLAY_SEARCH_RESULT: Int = 6
        const val DISPLAY_SEARCH_RESULT_SMALL: Int = 7
        const val DISPLAY_PREDICTION_ROW: Int = 8
        const val DISPLAY_SEARCH_RESULT_APP_ROW: Int = 9
        const val CONTAINER_WORKSPACE: Int = 0
        const val CONTAINER_FOLDER: Int = 2
    }
}