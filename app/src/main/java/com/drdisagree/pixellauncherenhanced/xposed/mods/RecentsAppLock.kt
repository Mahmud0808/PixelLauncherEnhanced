package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_LOCK_APP
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.LockedApps
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.Executors

class RecentsAppLock(context: Context) : ModPack(context) {

    private var lockEnabled = false
    private val executor = Executors.newSingleThreadExecutor()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            lockEnabled = getBoolean(RECENTS_LOCK_APP, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val taskViewClass = findClass("com.android.quickstep.views.TaskView", suppressError = true) ?: return
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

    private fun View.isLocked(): Boolean {
        return tasks().any { task ->
            val key = task.getFieldSilently("key") ?: return@any false
            val packageName = (key.callMethodSilently("getComponent") as? ComponentName)?.packageName
                ?: key.callMethodSilently("getPackageName") as? String
                ?: return@any false
            val userId = key.getFieldSilently("userId") as? Int ?: return@any false
            LockedApps.isLocked(mContext, packageName, userId)
        }
    }

    private fun View.tasks(): List<Any> {
        (callMethodSilently("getTaskContainers") as? List<*>)
            ?.mapNotNull { it?.callMethodSilently("getTask") ?: it?.getFieldSilently("task") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return listOfNotNull(callMethodSilently("getTask"), getFieldSilently("mSecondaryTask"))
    }

    private fun Any.taskId(): Int? = getFieldSilently("key")?.getFieldSilently("id") as? Int
}
