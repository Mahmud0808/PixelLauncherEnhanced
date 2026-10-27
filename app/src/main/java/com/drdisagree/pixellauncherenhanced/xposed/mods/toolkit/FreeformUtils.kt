package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.UserHandle
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass

object FreeformUtils {

    enum class Variant(val id: Int) {
        AOSP(0),
        SUNSHINE(1),
        LMAO(2),
        YAMF(3),
        REYAMF(4),
        BUBBLE(5);

        companion object {
            fun fromId(id: Int): Variant = entries.find { it.id == id } ?: AOSP
        }
    }

    fun startFreeformByIntent(mContext: Context, task: Any?, mode: Variant) {
        Intent(getFreeformIntent(mode)).setPackage(getFreeformPackage(mode)).apply {
            callMethod(
                "putExtra",
                "packageName",
                task.getFieldSilently("key").callMethod("getPackageName")
            )
            callMethod(
                "putExtra",
                "activityName",
                task.callMethod("getTopComponent").callMethod("getClassName")
            )
            callMethod(
                "putExtra",
                "userId",
                task.getFieldSilently("key").getFieldSilently("userId")
            )
            callMethod(
                "putExtra",
                "taskId",
                task.getFieldSilently("key").getFieldSilently("id")
            )
            mContext.callMethod("sendBroadcast", this)
        }
    }

    fun currentToFreeform(mContext: Context, mode: Variant) {
        Intent(getCurrentToFreeformIntent(mode)).apply {
            mContext.callMethod("sendBroadcast", this)
        }
    }

    fun startFreeformFromRecents(task: Any?, iamw: Any?, r: Rect) {
        iamw.callMethod(
            "startActivityFromRecents",
            task.getFieldSilently("key"),
            freeformOpt?.setLaunchBounds(r)
        )
    }

    val freeformOpt: ActivityOptions?
        get() {
            val opt = ActivityOptions.makeBasic().apply {
                callMethod("setLaunchWindowingMode", 5)
                //callMethod("setTaskAlwaysOnTop", true)
                //callMethod("setTaskOverlay", true, true)
                
            }

            /* final View decorView = container.getWindow().getDecorView();
            final WindowInsets insets = decorView.getRootWindowInsets();
            r.offsetTo(insets.getSystemWindowInsetLeft() + 50, insets.getSystemWindowInsetTop() + 50);*/
            //opt;

            return opt
        }

    fun getFreeformIntent(mode: Variant): String {
        return when (mode) {
            Variant.LMAO -> "com.libremobileos.freeform.START_FREEFORM"
            else -> "com.sunshine.freeform.start_freeform"
        }
    }

    fun getFreeformPackage(mode: Variant): String {
        return when (mode) {
            Variant.LMAO -> "com.libremobileos.freeform"
            else -> "com.sunshine.freeform"
        }
    }
    
    fun getCurrentToFreeformIntent(mode: Variant): String {
        return when (mode) {
            Variant.REYAMF -> "com.mja.reyamf.action.CURRENT_TO_WINDOW"
            else -> "io.github.duzhaokun123.yamf.action.CURRENT_TO_WINDOW"
        }
    }

    fun startAppBubble(mContext: Context, task: Any?) {
        try {
            val key = task.getFieldSilently("key")
            val packageName = key.callMethod("getPackageName") as? String ?: return
            val userId = key.getFieldSilently("userId") as? Int ?: return

            // Use the original baseIntent from the task as the foundation,
            // so all original extras/data/categories are preserved.
            val baseIntent = key.getFieldSilently("baseIntent") as? Intent
            val intent = if (baseIntent != null) {
                Intent(baseIntent)
            } else {
                Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            }
            // Override component with the top activity for precise targeting.
            // baseIntent may contain an alias (e.g. .DefaultIcon for TG);
            // getTopComponent() resolves to the real activity.
            val topComponent = task.callMethod("getTopComponent")
            val className = topComponent?.callMethod("getClassName") as? String
            if (className != null) {
                intent.component = android.content.ComponentName(packageName, className)
            }
            // Always ensure package is set (BubbleData.getOrCreateBubble needs it)
            intent.setPackage(packageName)

            val userHandle = try {
                UserHandle::class.java.getDeclaredConstructor(Int::class.java)
                    .newInstance(userId)
            } catch (_: Exception) {
                UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .newInstance(userId)
            }

            // Get SystemUiProxy instance via Dagger singleton
            val sysUiProxyClass = findClass("com.android.quickstep.SystemUiProxy")!!
            val daggerSingleton = sysUiProxyClass.getStaticFieldSilently("INSTANCE")
            val systemUiProxy = daggerSingleton?.callMethod("get", mContext) ?: return

            // Get EntryPoint.TASKBAR_ICON_MENU enum value
            val entryPointClass = findClass(
                "com.android.wm.shell.shared.bubbles.logging.EntryPoint"
            ) ?: return
            val entryPoint = entryPointClass.enumConstants!![0] // TASKBAR_ICON_MENU

            // Avoid XposedHelpers method resolution entirely.
            // Iterate SystemUiProxy methods, find showAppBubble with 4 params, invoke directly.
            val method = systemUiProxy.javaClass.methods.firstOrNull { m ->
                m.name == "showAppBubble" && m.parameterCount == 4
            } ?: run {
                log("FreeformUtils", "showAppBubble with 4 params not found")
                return
            }
            method.invoke(systemUiProxy, intent, userHandle, entryPoint, null)
            log("FreeformUtils", "showAppBubble invoked OK")
        } catch (e: Exception) {
            log("FreeformUtils", "Failed to show app bubble: ${e.message}")
        }
    }
}
