package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.view.View
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACKS_ENABLED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.POPUP_EDIT_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.POPUP_KILL_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.POPUP_UNINSTALL_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_KILL_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_UNINSTALL_APP
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.InjectedResources
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy
import java.util.stream.Stream

class AppShortcuts(context: Context) : ModPack(context) {

    private enum class Action(val iconRes: Int, val labelRes: Int) {
        EDIT(R.drawable.ic_edit, R.string.popup_edit),
        KILL(R.drawable.ic_kill_app, R.string.kill_app),
        UNINSTALL(R.drawable.ic_uninstall, R.string.uninstall_app)
    }

    private var popupEditApp = false
    private var popupKillApp = false
    private var popupUninstallApp = false
    private var recentsKillApp = false
    private var recentsUninstallApp = false

    private var appInfoShortcutClass: Class<*>? = null
    private var appInfoShortcutConstructor: Constructor<*>? = null
    private val popupFactories = HashMap<Action, Any>()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            popupEditApp = getBoolean(POPUP_EDIT_APP, false) && getBoolean(ICON_PACKS_ENABLED, false)
            popupKillApp = getBoolean(POPUP_KILL_APP, false)
            popupUninstallApp = getBoolean(POPUP_UNINSTALL_APP, false)
            recentsKillApp = getBoolean(RECENTS_KILL_APP, false)
            recentsUninstallApp = getBoolean(RECENTS_UNINSTALL_APP, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        appInfoShortcutClass = findClass(
            $$"com.android.launcher3.popup.SystemShortcut$AppInfo",
            suppressError = true
        ) ?: return
        appInfoShortcutConstructor = appInfoShortcutClass!!.declaredConstructors
            .filter { constructor -> constructor.parameterTypes.none { it.isPrimitive } }
            .minByOrNull { it.parameterTypes.size }
            ?.apply { isAccessible = true }
            ?: return

        appInfoShortcutClass
            .hookMethod("onClick")
            .runBefore { param ->
                val action = getAdditionalInstanceField(param.thisObject, ACTION_KEY) as? Action
                    ?: return@runBefore
                val view = param.args[0] as? View ?: return@runBefore

                findClass("com.android.launcher3.AbstractFloatingView", suppressError = true)
                    .callStaticMethodSilently(
                        "closeAllOpenViews",
                        param.thisObject.getFieldSilently("mTarget")
                    )
                performAction(
                    action,
                    view.context,
                    param.thisObject.getFieldSilently("mItemInfo")
                )
                if (action == Action.KILL) {
                    dismissTaskView(param.thisObject.getFieldSilently("mOriginalView") as? View)
                }
                param.result = null
            }

        hookAppIconPopup()
        hookRecentsMenu()
    }

    private fun hookAppIconPopup() {
        val factoryClass = findClass(
            $$"com.android.launcher3.popup.SystemShortcut$Factory",
            suppressError = true
        ) ?: return

        Action.entries.forEach { action ->
            popupFactories[action] = Proxy.newProxyInstance(
                factoryClass.classLoader,
                arrayOf(factoryClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "getShortcut" -> createShortcut(action, args.orEmpty())
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "PixelLauncherEnhanced${action.name}ShortcutFactory"
                    else -> null
                }
            }
        }

        listOf(
            "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
            "com.android.launcher3.uioverrides.QuickstepLauncher",
            "com.android.launcher3.Launcher"
        )
            .firstNotNullOfOrNull { className ->
                findClass(className, suppressError = true)
                    ?.takeIf { clazz -> clazz.declaredMethods.any { it.name == "getSupportedShortcuts" } }
            }
            .hookMethod("getSupportedShortcuts")
            .suppressError()
            .runAfter { param ->
                @Suppress("UNCHECKED_CAST")
                val original = param.result as? Stream<Any?> ?: return@runAfter
                val added = buildList {
                    if (popupEditApp) add(popupFactories[Action.EDIT])
                    if (popupKillApp) add(popupFactories[Action.KILL])
                    if (popupUninstallApp) add(popupFactories[Action.UNINSTALL])
                }.filterNotNull()

                if (added.isNotEmpty()) {
                    param.result = Stream.concat(original, added.stream())
                }
            }
    }

    private fun hookRecentsMenu() {
        findClass("com.android.quickstep.TaskOverlayFactory", suppressError = true)
            .hookMethod("getEnabledShortcuts")
            .suppressError()
            .runAfter { param ->
                if (!recentsKillApp && !recentsUninstallApp) return@runAfter

                val original = param.result as? List<*> ?: return@runAfter
                val template = original.firstOrNull {
                    appInfoShortcutClass!!.isInstance(it) &&
                            getAdditionalInstanceField(it, ACTION_KEY) == null
                } ?: return@runAfter
                val args = arrayOf(
                    template.getFieldSilently("mTarget"),
                    template.getFieldSilently("mItemInfo"),
                    template.getFieldSilently("mOriginalView")
                )
                val added = buildList {
                    if (recentsKillApp) add(createShortcut(Action.KILL, args))
                    if (recentsUninstallApp) add(createShortcut(Action.UNINSTALL, args))
                }.filterNotNull()

                if (added.isNotEmpty()) {
                    param.result = ArrayList(original).apply { addAll(added) }
                }
            }
    }

    private fun createShortcut(action: Action, args: Array<out Any?>): Any? {
        val constructor = appInfoShortcutConstructor ?: return null
        val itemInfo = args.firstOrNull { it?.getFieldSilently("itemType") != null } ?: return null
        val packageName = itemInfo.packageName() ?: return null

        if (packageName == mContext.packageName) return null
        if (action == Action.EDIT && editTarget(itemInfo) == null) return null
        if (action == Action.UNINSTALL && !isUninstallable(packageName, itemInfo.user())) {
            return null
        }

        val constructorArgs = constructor.parameterTypes.map { type ->
            args.firstOrNull { type.isInstance(it) } ?: when (type) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }

        return runCatching {
            constructor.newInstance(*constructorArgs.toTypedArray()).apply {
                val labelId = InjectedResources.idFor(action.labelRes)
                setField("mIconResId", InjectedResources.idFor(action.iconRes))
                if (hasField("mLabelResId")) {
                    setField("mLabelResId", labelId)
                } else {
                    setField("mLabel", resourceString(labelId) ?: fixedString(modRes.getString(action.labelRes)))
                }
                setFieldSilently("mAccessibilityActionId", labelId)
                setFieldSilently("mIsCollapsible", true)
                setAdditionalInstanceField(this, ACTION_KEY, action)
            }
        }.onFailure {
            log(this@AppShortcuts, it)
        }.getOrNull()
    }

    private fun resourceString(resId: Int): Any? {
        return findClass($$"com.android.launcher3.popup.ui.StringContainer$ResourceString", suppressError = true)
            ?.getConstructor(Int::class.javaPrimitiveType)
            ?.newInstance(resId)
    }

    private fun fixedString(text: String): Any? {
        return findClass($$"com.android.launcher3.popup.ui.StringContainer$FixedString", suppressError = true)
            ?.getConstructor(String::class.java)
            ?.newInstance(text)
    }

    private fun Any.hasField(name: String): Boolean {
        var type: Class<*>? = javaClass
        while (type != null) {
            if (type.declaredFields.any { it.name == name }) return true
            type = type.superclass
        }
        return false
    }

    private fun performAction(action: Action, context: Context, itemInfo: Any?) {
        val packageName = itemInfo.packageName() ?: return
        val user = itemInfo.user()

        when (action) {
            Action.EDIT -> {
                val component = editTarget(itemInfo) ?: return
                val container = itemInfo.getFieldSilently("container") as? Int ?: -1
                val title = itemInfo.getFieldSilently("title") as? CharSequence

                runCatching {
                    context.startActivity(
                        Intent()
                            .setComponent(ComponentName(BuildConfig.APPLICATION_ID, EDITOR_ACTIVITY))
                            .putExtra(EXTRA_COMPONENT, component.flattenToString())
                            .putExtra(EXTRA_LABEL, title?.toString())
                            .putExtra(EXTRA_HOME, container >= 0 || container in HOME_CONTAINERS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    log(this@AppShortcuts, it)
                }
            }

            Action.KILL -> {
                val userId = user.callMethodSilently("getIdentifier") as? Int ?: user.hashCode()
                enqueueProxyCommand { proxy ->
                    proxy.runCommand("am force-stop --user $userId $packageName")
                }

                val label = itemInfo.getFieldSilently("title") as? CharSequence ?: packageName
                Toast.makeText(
                    context,
                    modRes.getString(R.string.app_killed, label),
                    Toast.LENGTH_SHORT
                ).show()
            }

            Action.UNINSTALL -> {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
                            .putExtra(Intent.EXTRA_USER, user)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    log(this@AppShortcuts, it)
                }
            }
        }
    }

    private fun dismissTaskView(taskView: View?) {
        val recentsView = taskView?.parent ?: return
        val recentsViewClass =
            findClass("com.android.quickstep.views.RecentsView", suppressError = true) ?: return
        if (!recentsViewClass.isInstance(recentsView)) return

        runCatching { recentsView.callMethod("dismissTaskView", taskView, true) }
            .recoverCatching { recentsView.callMethod("dismissTask", taskView, true, true) }
            .onFailure { log(this@AppShortcuts, it) }
    }

    private fun isUninstallable(packageName: String, user: UserHandle): Boolean {
        val appInfo = runCatching {
            mContext.getSystemService(LauncherApps::class.java)
                .getApplicationInfo(packageName, 0, user)
        }.getOrNull() ?: return false

        return appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    private fun Any?.packageName(): String? {
        return (callMethodSilently("getTargetComponent") as? ComponentName)?.packageName
            ?: (getFieldSilently("componentName") as? ComponentName)?.packageName
            ?: (getFieldSilently("intent") as? Intent)?.let { it.component?.packageName ?: it.`package` }
    }

    private fun editTarget(itemInfo: Any?): ComponentName? {
        val packageName = itemInfo.packageName() ?: return null

        return when (itemInfo.getFieldSilently("itemType")) {
            ITEM_TYPE_APPLICATION -> itemInfo.callMethodSilently("getTargetComponent") as? ComponentName
            ITEM_TYPE_DEEP_SHORTCUT -> {
                val id = itemInfo.callMethodSilently("getDeepShortcutId") as? String
                    ?: (itemInfo.getFieldSilently("intent") as? Intent)?.getStringExtra(EXTRA_SHORTCUT_ID)
                    ?: return null
                IconPackManager.shortcutComponent(packageName, id)
            }

            else -> null
        }
    }

    private fun Any?.user(): UserHandle {
        return getFieldSilently("user") as? UserHandle ?: Process.myUserHandle()
    }

    companion object {
        private const val ACTION_KEY = "plenhanced_app_shortcut_action"
        private const val EDITOR_ACTIVITY = "${BuildConfig.APPLICATION_ID}.ui.activities.AppEditorActivity"
        private const val EXTRA_COMPONENT = "component"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_HOME = "home"
        private const val EXTRA_SHORTCUT_ID = "shortcut_id"
        private const val ITEM_TYPE_APPLICATION = 0
        private const val ITEM_TYPE_DEEP_SHORTCUT = 6
        private val HOME_CONTAINERS = setOf(-100, -101, -103)
    }
}
