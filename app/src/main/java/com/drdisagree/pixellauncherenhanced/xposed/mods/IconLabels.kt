package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_ICON_LABELS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_LONG_LABELS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_ICON_LABELS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
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
import kotlin.math.ceil

class IconLabels(context: Context) : ModPack(context) {

    private var showDesktopLabels = true
    private var showDrawerLabels = true
    private var longDrawerLabels = false
    private var nativeLabelHiddenItem: Any? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            showDesktopLabels = getBoolean(DESKTOP_ICON_LABELS, true)
            showDrawerLabels = getBoolean(APP_DRAWER_ICON_LABELS, true)
            longDrawerLabels = getBoolean(APP_DRAWER_LONG_LABELS, false)
        }

        when (key.firstOrNull()) {
            DESKTOP_ICON_LABELS -> {
                if (nativeLabelHiddenItem == null || !setNativeLabelsHidden(!showDesktopLabels)) {
                    reloadLauncher(mContext)
                }
            }

            APP_DRAWER_ICON_LABELS,
            APP_DRAWER_LONG_LABELS -> reloadLauncher(mContext)
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

            if (itemInfo.hidesLabel(mDisplay)) removeLabel()
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

            if (itemInfo.hidesLabel(mDisplay)) reAddLabel()
        }

        findClass("com.android.launcher3.ShortcutAndWidgetContainer")
            .hookMethod("measureChild")
            .suppressError()
            .runAfter { param ->
                val child = param.args.getOrNull(0) as? View ?: return@runAfter
                val showLabels = if (DrawerFolders.isDrawerFolderItem(child.tag)) showDrawerLabels else showDesktopLabels
                if (showLabels) return@runAfter

                centerIconWithoutLabel(param.thisObject as View, child)
            }

        bubbleTextViewClass
            .hookMethod("onMeasure")
            .suppressError()
            .runAround(before = { param ->
                param.beforeLongLabelMeasure()

                val view = param.thisObject as View
                if (!view.isDrawerIconWithoutLabel()) return@runAround
                if (view.getFieldSilently("mCenterVertically") != true) return@runAround

                view.setField("mCenterVertically", false)
                param.setObjectExtra(CENTER_VERTICALLY, true)
            }, after = { param ->
                if (param.getObjectExtra(LONG_LABEL_CENTER) == true) {
                    (param.thisObject as View).setField("mCenterVertically", true)
                }
                if (param.getObjectExtra(CENTER_VERTICALLY) != true) return@runAround

                val view = param.thisObject as View
                view.setField("mCenterVertically", true)

                val iconSize = view.getFieldSilently("mIconSize") as? Int ?: return@runAround
                val paddingTop = ((view.measuredHeight - iconSize) / 2).coerceAtLeast(0)
                if (view.paddingTop != paddingTop) {
                    view.setPadding(view.paddingLeft, paddingTop, view.paddingRight, view.paddingBottom)
                }
            })

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

    private fun XC_MethodHook.MethodHookParam.beforeLongLabelMeasure() {
        val view = thisObject as TextView
        if (!view.usesLongLabel()) return

        if (view.maxLines != LONG_LABEL_LINES) {
            view.setSingleLine(false)
            view.maxLines = LONG_LABEL_LINES
            view.ellipsize = TextUtils.TruncateAt.END
        }

        val heightSpec = args[1] as Int
        if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.EXACTLY) return
        val height = View.MeasureSpec.getSize(heightSpec)
        args[1] = View.MeasureSpec.makeMeasureSpec(height + view.lineHeight, View.MeasureSpec.EXACTLY)

        if (view.getFieldSilently("mCenterVertically") != true) return
        val iconSize = view.getFieldSilently("mIconSize") as? Int ?: return
        val fontMetrics = view.paint.fontMetrics
        val content = iconSize + view.compoundDrawablePadding + ceil(fontMetrics.bottom - fontMetrics.top).toInt()
        val paddingTop = ((height - content) / 2).coerceAtLeast(0)

        view.setField("mCenterVertically", false)
        setObjectExtra(LONG_LABEL_CENTER, true)
        if (view.paddingTop != paddingTop) {
            view.setPadding(view.paddingLeft, paddingTop, view.paddingRight, view.paddingBottom)
        }
    }

    private fun View.usesLongLabel(): Boolean {
        if (!longDrawerLabels || !showDrawerLabels) return false
        if (getFieldSilently("mDisplay") != DISPLAY_ALL_APPS) return false
        if (DrawerFolders.isDrawerFolderItem(tag)) return false
        return callMethodSilently("shouldUseTwoLine") != true
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

    private fun Any?.hidesLabel(display: Int): Boolean {
        if (DrawerFolders.isDrawerFolderItem(this)) return !showDrawerLabels
        if (display.isDesktop()) return !showDesktopLabels && !display.isNativelyHandled()
        return display.isDrawer() && !showDrawerLabels
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
        private const val LONG_LABEL_CENTER = "pleLongLabelCenter"
        private const val LONG_LABEL_LINES = 2
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