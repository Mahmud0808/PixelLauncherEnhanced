package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_LOCK_APP
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.LockedApps
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class RecentsAppLock(context: Context) : ModPack(context) {

    private var lockEnabled = false
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recentsViewRef: WeakReference<ViewGroup>? = null
    private var taskViewClass: Class<*>? = null
    private var iconChipClass: Class<*>? = null
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            lockEnabled = getBoolean(RECENTS_LOCK_APP, false)
        }

        when (key.firstOrNull()) {
            RECENTS_LOCK_APP -> mainHandler.post { refreshIndicators() }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val taskViewClass = findClass("com.android.quickstep.views.TaskView", suppressError = true) ?: return
        this.taskViewClass = taskViewClass
        iconChipClass = findClass("com.android.quickstep.views.IconAppChipView", suppressError = true)
        hookIndicators()
        LockedApps.addListener { mainHandler.post { refreshIndicators() } }
        val activityManagerWrapperClass = findClass(
            "com.android.systemui.shared.system.ActivityManagerWrapper",
            suppressError = true
        ) ?: return

        findClass("com.android.quickstep.views.RecentsView", suppressError = true)
            .hookMethod("dismissAllTasks")
            .suppressError()
            .runBefore { param ->
                if (!lockEnabled) return@runBefore

                val recentsView = param.thisObject as? ViewGroup ?: return@runBefore
                val taskViews = (0 until recentsView.childCount)
                    .map { recentsView.getChildAt(it) }
                    .filter { taskViewClass.isInstance(it) }
                val (lockedViews, unlockedViews) = taskViews.partition { it.isLocked() }
                if (lockedViews.isEmpty()) return@runBefore

                param.result = null

                val taskIds = unlockedViews.flatMap { view -> view.tasks().mapNotNull { it.taskId() } }
                if (taskIds.isEmpty()) return@runBefore

                val activityManager = activityManagerWrapperClass.callStaticMethodSilently("getInstance")
                executor.execute {
                    taskIds.forEach { taskId ->
                        runCatching { activityManager.callMethodSilently("removeTask", taskId) }
                            .onFailure { log(this@RecentsAppLock, it) }
                    }
                }
            }
    }

    private fun View.isLocked(): Boolean = tasks().any { it.isLockedTask() }

    private fun Any.isLockedTask(): Boolean {
        val key = getFieldSilently("key") ?: return false
        val packageName = (key.callMethodSilently("getComponent") as? ComponentName)?.packageName
            ?: key.callMethodSilently("getPackageName") as? String
            ?: return false
        val userId = key.getFieldSilently("userId") as? Int ?: return false
        return LockedApps.isLocked(mContext, packageName, userId)
    }

    private fun hookIndicators() {
        findClass("com.android.quickstep.views.RecentsView", suppressError = true)
            .hookConstructor()
            .suppressError()
            .runAfter { param -> recentsViewRef = WeakReference(param.thisObject as ViewGroup) }

        iconChipClass
            .hookMethod("setText")
            .suppressError()
            .runAfter { param ->
                val chip = param.thisObject as View
                chip.setExtraField(PLAIN_TITLE, param.args.getOrNull(0) as? CharSequence)
                applyChipIndicator(chip)
            }

        findClass("com.android.quickstep.views.IconView", suppressError = true)
            .hookMethod("onDraw")
            .suppressError()
            .runAfter { param ->
                if (!lockEnabled) return@runAfter
                val icon = param.thisObject as View
                if (icon.insideChip()) return@runAfter
                val task = icon.ownerTask() ?: return@runAfter
                if (task.isLockedTask()) drawBadge(param.args[0] as Canvas, icon)
            }
    }

    private fun refreshIndicators() {
        val recentsView = recentsViewRef?.get() ?: return
        for (index in 0 until recentsView.childCount) {
            val taskView = recentsView.getChildAt(index)
            if (taskViewClass?.isInstance(taskView) != true) continue
            taskView.iconViews().forEach { icon ->
                if (iconChipClass?.isInstance(icon) == true) applyChipIndicator(icon) else icon.invalidate()
            }
        }
    }

    private fun applyChipIndicator(chip: View) {
        val title = chip.getFieldSilently("appTitle") as? TextView ?: return
        val plain = chip.getExtraFieldSilently(PLAIN_TITLE) as? CharSequence ?: title.text?.toString() ?: return
        val locked = lockEnabled && chip.ownerTask()?.isLockedTask() == true

        if (!locked) {
            if (title.text != plain) title.text = plain
            return
        }

        val drawable = modRes.getDrawable(R.drawable.ic_lock, null).mutate().apply {
            setTint(title.currentTextColor)
            val size = title.textSize.toInt()
            setBounds(0, 0, size, size)
        }
        title.text = SpannableStringBuilder().append(
            LOCK_PLACEHOLDER,
            ImageSpan(
                drawable,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DynamicDrawableSpan.ALIGN_CENTER
                else DynamicDrawableSpan.ALIGN_BASELINE
            ),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        ).append(" ").append(plain)
    }

    private fun drawBadge(canvas: Canvas, icon: View) {
        val size = minOf(icon.width, icon.height) * BADGE_RATIO
        val cx = icon.width - size / 2
        val cy = icon.height - size / 2
        badgePaint.color = Color.BLACK
        badgePaint.alpha = BADGE_ALPHA
        canvas.drawCircle(cx, cy, size / 2, badgePaint)

        val glyph = (size * 0.62f).toInt()
        modRes.getDrawable(R.drawable.ic_lock, null).mutate().apply {
            setTint(Color.WHITE)
            setBounds((cx - glyph / 2).toInt(), (cy - glyph / 2).toInt(), (cx + glyph / 2).toInt(), (cy + glyph / 2).toInt())
            draw(canvas)
        }
    }

    private fun View.insideChip(): Boolean {
        var parent = parent
        while (parent != null) {
            if (iconChipClass?.isInstance(parent) == true) return true
            if (taskViewClass?.isInstance(parent) == true) return false
            parent = parent.parent
        }
        return false
    }

    private fun View.owningTaskView(): View? {
        var parent = parent
        while (parent != null) {
            if (taskViewClass?.isInstance(parent) == true) return parent as View
            parent = parent.parent
        }
        return null
    }

    private fun View.ownerTask(): Any? {
        val taskView = owningTaskView() ?: return null
        (taskView.callMethodSilently("getTaskContainers") as? List<*>)?.forEach { container ->
            val icon = container?.callMethodSilently("getIconView") ?: container?.getFieldSilently("iconView")
            if (icon === this) {
                return container?.callMethodSilently("getTask") ?: container?.getFieldSilently("task")
            }
        }
        (taskView.callMethodSilently("getTaskIdAttributeContainers") as? Array<*>)?.forEach { container ->
            if (container?.callMethodSilently("getIconView") === this) return container.callMethodSilently("getTask")
        }
        if (taskView.callMethodSilently("getIconView") === this) return taskView.callMethodSilently("getTask")
        return null
    }

    private fun View.iconViews(): List<View> {
        val icons = ArrayList<View>()
        (callMethodSilently("getTaskContainers") as? List<*>)?.forEach { container ->
            ((container?.callMethodSilently("getIconView") ?: container?.getFieldSilently("iconView")) as? View)
                ?.let { icons.add(it) }
        }
        if (icons.isEmpty()) {
            (callMethodSilently("getTaskIdAttributeContainers") as? Array<*>)?.forEach { container ->
                (container?.callMethodSilently("getIconView") as? View)?.let { icons.add(it) }
            }
        }
        if (icons.isEmpty()) (callMethodSilently("getIconView") as? View)?.let { icons.add(it) }
        return icons
    }

    private fun View.tasks(): List<Any> {
        (callMethodSilently("getTaskContainers") as? List<*>)
            ?.mapNotNull { it?.callMethodSilently("getTask") ?: it?.getFieldSilently("task") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return listOfNotNull(callMethodSilently("getTask"), getFieldSilently("mSecondaryTask"))
    }

    private fun Any.taskId(): Int? = getFieldSilently("key")?.getFieldSilently("id") as? Int

    companion object {
        private const val PLAIN_TITLE = "plePlainChipTitle"
        private const val LOCK_PLACEHOLDER = "\uFFFC"
        private const val BADGE_RATIO = 0.42f
        private const val BADGE_ALPHA = 200
    }
}
