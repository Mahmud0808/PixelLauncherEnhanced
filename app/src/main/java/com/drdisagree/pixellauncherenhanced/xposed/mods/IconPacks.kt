package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.ShortcutInfo
import android.graphics.Canvas
import android.content.pm.PackageItemInfo
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_OVERRIDES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_OVERRIDES_HOME
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_THEMED_OVERRIDES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_THEMED_OVERRIDES_HOME
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LABEL_OVERRIDES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACKS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACKS_ENABLED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACK_APPLIED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACK_APPLY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACK_MASK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_PACKS
import com.drdisagree.pixellauncherenhanced.data.enums.IconSlot
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.data.iconpack.PackIconDrawable
import com.drdisagree.pixellauncherenhanced.data.iconpack.ThemedPackIconDrawable
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.MethodHookHelper
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.newInstance
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

class IconPacks(context: Context) : ModPack(context) {

    private var config = IconPackManager.Config()
    private var pendingApply = 0L
    private var themedMode = false
    private var signature = ""
    private val customIcons = ConcurrentHashMap<String, Bitmap>()
    private val customHashes = ConcurrentHashMap<String, Int>()
    private var packageStamps: Map<String, String> = emptyMap()
    private var itemStamps: Map<String, String> = emptyMap()
    private val homeBitmaps = ConcurrentHashMap<String, Any>()
    private val iconSources = ConcurrentHashMap<String, MutableSet<String>>()
    private var launcherModelRef: WeakReference<Any>? = null
    private var modelCallbacksRef: WeakReference<Any>? = null
    private var drawerRef: WeakReference<Any>? = null
    private var savedScroll: Map<Any, ScrollAnchor>? = null
    private var restoreScrollUntil = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val taskComponent = ThreadLocal<ComponentName?>()
    private val exportingShortcuts = ThreadLocal<Boolean>()
    private var bgDataModelRef: WeakReference<Any>? = null
    private val skippedWrap = ThreadLocal<Any?>()

    override fun updatePrefs(vararg key: String) {
        if (key.firstOrNull() == PINNED_SHORTCUTS_REQUEST) {
            Thread { exportPinnedShortcuts() }.start()
            return
        }
        if (key.firstOrNull() == PINNED_SHORTCUTS) return

        val changedKey = key.firstOrNull()
        val previousStamps = itemStamps

        Xprefs.apply {
            pendingApply = getLong(ICON_PACK_APPLY, 0L).takeIf { it != getLong(ICON_PACK_APPLIED, 0L) } ?: 0L
            themedMode = getBoolean(HOME_THEMED_ICONS, false)
            val labels = IconPackManager.parseOverrides(getString(LABEL_OVERRIDES, null))

            customIcons.clear()
            customHashes.clear()
            homeBitmaps.clear()

            if (!getBoolean(ICON_PACKS_ENABLED, false)) {
                config = IconPackManager.Config(labels = labels)
                signature = ""
                packageStamps = emptyMap()
                itemStamps = computeItemStamps()
                if (changedKey == ICON_PACKS_ENABLED) applyIcons()
                else if (changedKey == LABEL_OVERRIDES) refreshEdited(previousStamps, itemStamps)
                return
            }

            config = IconPackManager.Config(
                iconPacks = IconPackManager.parseList(getString(ICON_PACKS, null)),
                themedIconPacks = IconPackManager.parseList(getString(THEMED_ICON_PACKS, null)),
                maskUnsupported = getBoolean(ICON_PACK_MASK, false),
                overrides = IconPackManager.parseOverrides(getString(ICON_OVERRIDES, null)),
                homeOverrides = IconPackManager.parseOverrides(getString(ICON_OVERRIDES_HOME, null)),
                themedOverrides = IconPackManager.parseOverrides(getString(ICON_THEMED_OVERRIDES, null)),
                homeThemedOverrides = IconPackManager.parseOverrides(getString(ICON_THEMED_OVERRIDES_HOME, null)),
                labels = labels
            )

            IconSlot.entries.forEach { slot ->
                config.overridesFor(slot)
                    .filterValues { it == IconPackManager.OVERRIDE_CUSTOM }
                    .keys
                    .forEach { component ->
                        val customKey = slot.customKey(component)
                        val encoded = getString(IconPackManager.customIconKey(customKey), null) ?: return@forEach
                        IconPackManager.decodeBitmap(encoded)?.let {
                            customIcons[customKey] = it
                            customHashes[customKey] = encoded.hashCode()
                        }
                    }
            }
        }

        signature = IconPackManager.signature(config, themedMode)
        packageStamps = IconPackManager.packageStamps(config, customHashes, listOf(IconSlot.DRAWER, IconSlot.THEMED))
        itemStamps = computeItemStamps()

        when {
            changedKey == ICON_PACKS_ENABLED || changedKey == ICON_PACK_APPLY -> applyIcons()
            changedKey != null && isPerAppKey(changedKey) -> refreshEdited(previousStamps, itemStamps)
        }
    }

    private fun isPerAppKey(key: String) =
        key == LABEL_OVERRIDES || IconPackManager.isCustomIconKey(key) || IconSlot.entries.any { it.prefKey == key }

    private fun computeItemStamps(): Map<String, String> {
        val stamps = HashMap<String, StringBuilder>()

        IconSlot.entries.forEach { slot ->
            config.overridesFor(slot).forEach { (component, value) ->
                stamps.getOrPut(component) { StringBuilder() }
                    .append(slot.name).append('=').append(value).append(':')
                    .append(customHashes[slot.customKey(component)] ?: 0).append(';')
            }
        }
        config.labels.forEach { (component, label) ->
            stamps.getOrPut(component) { StringBuilder() }.append("label=").append(label)
        }

        return stamps.mapValues { it.value.toString() }
    }

    private fun refreshEdited(previous: Map<String, String>, current: Map<String, String>) {
        val changed = (previous.keys + current.keys).filter { previous[it] != current[it] }
        if (changed.isEmpty()) return

        val components = changed.mapNotNull { ComponentName.unflattenFromString(it) }
        val shortcutPackages = components.filter { IconPackManager.isShortcut(it) }.map { it.packageName }.toSet()
        val shortcutChanged = shortcutPackages.isNotEmpty()
        val packages = components.filterNot { IconPackManager.isShortcut(it) }.map { it.packageName }.toSet()

        mainHandler.post {
            val model = launcherModelRef?.get()

            when {
                model == null && modelCallbacksRef?.get() == null -> restartLauncher(mContext)
                shortcutChanged && model != null -> {
                    userProfiles().forEach { user ->
                        shortcutPackages.forEach { LauncherUtils.removeCachedIcons(it, user) }
                    }
                    if (model.hasMethod("forceReload", String::class.java)) {
                        model.callMethodSilently("forceReload", "pleEdit")
                    } else {
                        model.callMethodSilently("forceReload")
                    }
                }

                else -> refreshApps(packages)
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        registerPackReceiver()

        findClass("com.android.launcher3.LauncherModel", suppressError = true)
            .hookMethod("enqueueModelUpdateTask")
            .suppressError()
            .runBefore { param -> launcherModelRef = WeakReference(param.thisObject) }

        findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", suppressError = true)
            .hookConstructor()
            .suppressError()
            .runAfter { param -> drawerRef = WeakReference(param.thisObject) }

        findClass("com.android.launcher3.allapps.AlphabeticalAppsList", suppressError = true)
            .hookMethod("onAppsUpdated")
            .suppressError()
            .runAfter { if (savedScroll != null) restoreDrawerScroll() }

        listOf("com.android.launcher3.ModelCallbacks", "com.android.launcher3.Launcher").forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("bindItemsUpdated", "bindWorkspaceItemsChanged")
                .suppressError()
                .runAfter {
                    if (SystemClock.uptimeMillis() <= restoreScrollUntil) mainHandler.post { refreshFolderPreviews() }
                }
        }

        findClass("com.android.launcher3.model.ModelLauncherCallbacks", suppressError = true)
            .hookConstructor()
            .suppressError()
            .runAfter { param -> modelCallbacksRef = WeakReference(param.thisObject) }

        findClass("com.android.launcher3.model.LoaderTask", suppressError = true)
            .hookMethod("run")
            .suppressError()
            .runAfter { param ->
                param.thisObject.getFieldSilently("mBgDataModel")?.let { bgDataModelRef = WeakReference(it) }
                exportPinnedShortcuts()
                val token = pendingApply.takeIf { it != 0L } ?: return@runAfter
                pendingApply = 0L
                runCatching { Xprefs.edit().putLong(ICON_PACK_APPLIED, token).apply() }
            }

        findClass("com.android.launcher3.model.LoaderCursor", suppressError = true)
            .hookMethod("checkAndAddItem")
            .suppressError()
            .runBefore { param -> applyLegacyShortcutOverride(param.args[0]) }

        findClass("com.android.launcher3.icons.IconCache", suppressError = true)
            .hookMethod("applyCacheEntry")
            .suppressError()
            .runAfter { param ->
                if (config.labels.isEmpty()) return@runAfter
                val info = param.args.getOrNull(1) ?: return@runAfter
                applyLabelOverride(info, info.callMethodSilently("getTargetComponent") as? ComponentName)
            }

        findClass("com.android.launcher3.model.data.WorkspaceItemInfo", suppressError = true)
            .hookMethod("updateFromDeepShortcutInfo")
            .suppressError()
            .runAfter { param ->
                if (config.labels.isEmpty()) return@runAfter
                val shortcut = param.args.getOrNull(0) as? ShortcutInfo ?: return@runAfter
                applyLabelOverride(param.thisObject, IconPackManager.shortcutComponent(shortcut.`package`, shortcut.id))
            }

        findClass("com.android.launcher3.model.data.ItemInfoWithIcon", suppressError = true)
            ?.declaredMethods
            ?.filter { it.name == "newIcon" }
            ?.forEach { method ->
                MethodHookHelper(method)
                    .runBefore { param ->
                        val home = homeBitmapFor(param.thisObject) ?: return@runBefore
                        param.setObjectExtra(HOME_ORIGINAL_BITMAP, param.thisObject.getFieldSilently("bitmap"))
                        param.thisObject.setField("bitmap", home)
                    }
                    .runAfter { param ->
                        val original = param.getObjectExtra(HOME_ORIGINAL_BITMAP) ?: return@runAfter
                        param.thisObject.setField("bitmap", original)
                    }
            }

        LauncherApps::class.java
            .hookMethod("getShortcutIconDrawable")
            .suppressError()
            .runAfter { param ->
                if (exportingShortcuts.get() == true || !config.isActive) return@runAfter
                val shortcut = param.args.getOrNull(0) as? ShortcutInfo ?: return@runAfter
                val component = IconPackManager.shortcutComponent(shortcut.`package`, shortcut.id)
                if (component.flattenToString() !in config.overrides) return@runAfter

                val density = param.args.getOrNull(1) as? Int ?: mContext.resources.configuration.densityDpi
                val original = param.result as? Drawable
                param.result = replaceIcon(component, original, density) ?: return@runAfter
            }

        val iconProviderClass = findClass("com.android.launcher3.icons.IconProvider", suppressError = true) ?: return

        iconProviderClass
            .hookMethod("getIcon")
            .suppressError()
            .runAfter { param ->
                if (!config.isActive) return@runAfter

                if (param.result is PackIconDrawable) return@runAfter

                val component = componentOf(param.args.getOrNull(0)) ?: return@runAfter
                val density = param.args.lastOrNull() as? Int ?: mContext.resources.configuration.densityDpi
                val original = param.result as? Drawable

                param.result = replaceIcon(component, original, density) ?: return@runAfter
            }

        iconProviderClass
            .hookMethod("updateSystemState")
            .suppressError()
            .runAfter { param ->
                if (!config.isActive) return@runAfter

                when (val state = param.thisObject.getFieldSilently("mSystemState")) {
                    is String -> param.thisObject.setField("mSystemState", "$state $signature")
                    null -> Unit
                    else -> state.callMethodSilently("withAdditionalValues", arrayOf(signature))
                        ?.let { param.thisObject.setField("mSystemState", it) }
                }
            }

        iconProviderClass
            .hookMethod("getStateForApp")
            .suppressError()
            .runAfter { param ->
                val packageName = (param.args.getOrNull(0) as? ApplicationInfo)?.packageName ?: return@runAfter
                val stamp = packageStamps[packageName] ?: return@runAfter
                param.result.callMethodSilently("withAdditionalValues", arrayOf(stamp))?.let { param.result = it }
            }

        iconProviderClass
            .hookMethod("getSystemStateForPackage")
            .suppressError()
            .runAfter { param ->
                val packageName = param.args.getOrNull(1) as? String ?: return@runAfter
                val stamp = packageStamps[packageName] ?: return@runAfter
                (param.result as? String)?.let { param.result = "$it $stamp" }
            }

        iconProviderClass
            .hookMethod("getSystemIconState")
            .suppressError()
            .runAfter { param ->
                if (!config.isActive) return@runAfter
                (param.result as? String)?.takeIf { !it.endsWith(signature) }
                    ?.let { param.result = "$it $signature" }
            }

        findClass("com.android.launcher3.icons.BaseIconFactory", suppressError = true)
            .hookMethod("createBadgedIconBitmap")
            .suppressError()
            .runBefore { param ->
                val combined = param.args.getOrNull(0) as? ThemedPackIconDrawable
                if (combined != null) {
                    val method = param.method as Method
                    fun create(drawable: Drawable): Any? = method.invoke(
                        param.thisObject,
                        *param.args.copyOf().also { it[0] = drawable }
                    )

                    val normal = create(combined.regular) ?: return@runBefore
                    create(combined.themed)?.let { copyThemedLayers(it, normal) }
                    param.result = normal
                    return@runBefore
                }

                if (param.args.getOrNull(0) !is PackIconDrawable) return@runBefore

                val options = param.args.getOrNull(1) ?: return@runBefore
                val previous = OPTION_FIELDS.associateWith { options.getFieldSilently(it) }
                OPTION_FIELDS.forEach { field ->
                    if (options.javaClass.hasField(field)) options.setField(field, java.lang.Boolean.FALSE)
                }
                skippedWrap.set(options to previous)
            }
            .runAfter {
                @Suppress("UNCHECKED_CAST")
                (skippedWrap.get() as? Pair<Any, Map<String, Any?>>)?.let { (options, previous) ->
                    previous.forEach { (field, value) ->
                        if (options.javaClass.hasField(field)) options.setField(field, value)
                    }
                }
                skippedWrap.remove()
            }

        findClass("com.android.launcher3.icons.BaseIconFactory", suppressError = true)
            .hookMethod("normalizeAndWrapToAdaptiveIcon")
            .suppressError()
            .runBefore { param ->
                if (param.args.getOrNull(0) is PackIconDrawable) param.args[1] = false
            }

        findClass("com.android.quickstep.TaskIconCache", suppressError = true)
            .hookMethod("getBitmapInfo", "getCacheEntry", "getBitmapInfoCacheEntry")
            .suppressError()
            .runBefore { param ->
                val task = param.args.firstOrNull { it?.javaClass?.name?.endsWith(".Task") == true } ?: return@runBefore
                taskComponent.set(task.getFieldSilently("key").callMethodSilently("getComponent") as? ComponentName)
            }
            .runAfter { param ->
                if (param.args.any { it?.javaClass?.name?.endsWith(".Task") == true }) taskComponent.remove()
            }

        findClass("com.android.quickstep.TaskIconCache", suppressError = true)
            .hookMethod("getIcon")
            .suppressError()
            .runBefore { param ->
                if (param.args.firstOrNull() !is ActivityManager.TaskDescription) return@runBefore

                val component = taskComponent.get() ?: return@runBefore
                if (config.isActive && isCovered(component)) param.result = null
            }
    }

    private fun registerPackReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                if (intent.action != Intent.ACTION_PACKAGE_REPLACED &&
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                ) return
                if (packageName !in usedPackPackages()) return

                IconPackManager.invalidate(packageName)

                if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
                    refreshAllApps()
                } else {
                    if (intent.action == Intent.ACTION_PACKAGE_REMOVED) forgetPack(packageName)
                    val affected = iconSources.remove(packageName)
                    if (affected.isNullOrEmpty()) refreshAllApps() else refreshApps(affected)
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                mContext.registerReceiver(receiver, filter)
            }
        }
    }

    private fun userProfiles(): List<UserHandle> = runCatching {
        mContext.getSystemService(UserManager::class.java).userProfiles
    }.getOrNull() ?: listOf(Process.myUserHandle())

    private fun refreshApps(appPackages: Collection<String>) {
        if (appPackages.isEmpty()) return

        val model = launcherModelRef?.get()
        val target = modelCallbacksRef?.get()
            ?: model?.takeIf { it.javaClass.methods.any { method -> method.name == "onPackageChanged" } }
            ?: model?.callMethodSilently("newModelCallbacks")
            ?: return
        val launcherApps = mContext.getSystemService(LauncherApps::class.java)
        val users = runCatching {
            mContext.getSystemService(UserManager::class.java).userProfiles
        }.getOrNull() ?: listOf(Process.myUserHandle())
        val wanted = appPackages.toSet()
        val batched = target.javaClass.methods.any { it.name == "onPackagesAvailable" && it.parameterTypes.size == 3 }

        saveDrawerScroll()
        mainHandler.postDelayed({ refreshFolderPreviews() }, FOLDER_REFRESH_DELAY_MS)

        users.forEach { user ->
            val packages = runCatching {
                launcherApps.getActivityList(null, user)
                    .map { it.componentName.packageName }
                    .filter { it in wanted }
                    .distinct()
            }.getOrDefault(emptyList())
            if (packages.isEmpty()) return@forEach

            if (batched) {
                target.callMethodSilently("onPackagesAvailable", packages.toTypedArray(), user, true)
            } else {
                packages.forEach { target.callMethodSilently("onPackageChanged", it, user) }
            }
        }
    }

    private fun saveDrawerScroll() {
        val container = drawerRef?.get() ?: return
        val anchors = IdentityHashMap<Any, ScrollAnchor>()

        (container.getFieldSilently("mAH") as? List<*>)?.forEach { holder ->
            val recyclerView = holder.getFieldSilently("mRecyclerView") as? ViewGroup ?: return@forEach
            val layoutManager = recyclerView.callMethodSilently("getLayoutManager")
            val position = layoutManager.callMethodSilently("findFirstVisibleItemPosition") as? Int ?: -1
            val anchorView = (layoutManager.callMethodSilently("findViewByPosition", position) as? View)
                ?: recyclerView.getChildAt(0)

            anchors[recyclerView] = ScrollAnchor(
                position = position,
                offset = (anchorView?.top ?: recyclerView.paddingTop) - recyclerView.paddingTop,
                state = recyclerView.callMethodSilently("onSaveInstanceState") as? Parcelable
            )
        }

        savedScroll = anchors
        restoreScrollUntil = SystemClock.uptimeMillis() + SCROLL_RESTORE_WINDOW_MS
    }

    private fun restoreDrawerScroll() {
        if (SystemClock.uptimeMillis() > restoreScrollUntil) {
            savedScroll = null
            return
        }

        savedScroll?.forEach { (recyclerView, anchor) ->
            val layoutManager = recyclerView.callMethodSilently("getLayoutManager")
            val itemCount = layoutManager.callMethodSilently("getItemCount") as? Int ?: 0

            if (anchor.position >= 0 && itemCount > 0 && layoutManager.hasField("mPendingScrollPosition")) {
                layoutManager.setField("mPendingScrollPosition", anchor.position.coerceAtMost(itemCount - 1))
                layoutManager.setField("mPendingScrollPositionOffset", anchor.offset)
                if (layoutManager.hasField("mPendingSavedState")) layoutManager.setField("mPendingSavedState", null)
                (recyclerView as? View)?.requestLayout()
            } else if (anchor.state != null) {
                recyclerView.callMethodSilently("onRestoreInstanceState", anchor.state)
            }
        }
    }

    private fun refreshFolderPreviews() {
        val root = (drawerRef?.get() as? View)?.rootView ?: return
        val pending = ArrayDeque<View>().apply { add(root) }

        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()

            if (view.javaClass.name.endsWith(".folder.FolderIcon")) {
                runCatching { view.callMethod("updatePreviewItems", false) }
                    .recoverCatching { view.callMethod("updatePreviewItems", java.util.function.Predicate<Any?> { true }) }
                view.invalidate()
                continue
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
            }
        }
    }

    private fun Any?.hasField(name: String): Boolean {
        var type: Class<*>? = this?.javaClass
        while (type != null) {
            if (type.declaredFields.any { it.name == name }) return true
            type = type.superclass
        }
        return false
    }

    private class ScrollAnchor(val position: Int, val offset: Int, val state: Parcelable?)

    private fun refreshAllApps() {
        val launcherApps = mContext.getSystemService(LauncherApps::class.java)
        val packages = runCatching {
            mContext.getSystemService(UserManager::class.java).userProfiles.flatMap { user ->
                launcherApps.getActivityList(null, user).map { it.componentName.packageName }
            }
        }.getOrDefault(emptyList())

        refreshApps(packages)
    }

    private fun recordSource(packPackage: String?, appPackage: String) {
        if (packPackage == null) return
        iconSources.getOrPut(packPackage) { ConcurrentHashMap.newKeySet() }.add(appPackage)
    }

    private fun forgetPack(packageName: String) {
        val overrides = IconPackManager.withoutMissingPacks(config.overrides) { it != packageName }
        val iconPacks = config.iconPacks - packageName
        val themedPacks = config.themedIconPacks - packageName

        config = config.copy(iconPacks = iconPacks, themedIconPacks = themedPacks, overrides = overrides)

        runCatching {
            Xprefs.edit()
                .putString(ICON_PACKS, IconPackManager.serializeList(iconPacks))
                .putString(THEMED_ICON_PACKS, IconPackManager.serializeList(themedPacks))
                .putString(ICON_OVERRIDES, IconPackManager.serializeOverrides(overrides))
                .commit()
        }
    }

    private fun usedPackPackages(): Set<String> {
        if (!config.isActive) return emptySet()

        val overridePacks = IconSlot.entries.flatMap { slot ->
            config.overridesFor(slot).values.mapNotNull { IconPackManager.overridePackage(it) }
        }

        return (config.iconPacks + config.themedIconPacks + overridePacks).toSet()
    }

    private fun applyIcons() {
        IconPackManager.clearCache()
        runCatching { mContext.deleteDatabase(ICON_CACHE_DB) }
        restartLauncher(mContext)
    }

    private fun replaceIcon(component: ComponentName, original: Drawable?, density: Int): Drawable? {
        val key = component.flattenToString()
        val inRecents = taskComponent.get() != null
        val resolved = runCatching {
            IconPackManager.resolve(
                context = mContext,
                component = component,
                config = config,
                density = density,
                customIcon = { customIcons[it] },
                original = { original?.constantState?.newDrawable()?.mutate() ?: original }
            )
        }.getOrNull()

        resolved?.packageName?.let { recordSource(it, component.packageName) }
        val regular = resolved?.drawable
        if (inRecents) return regular

        return composeIcon(
            component = component,
            regular = regular,
            hasIconOverride = key in config.overrides,
            themedValue = config.themedOverrides[key],
            themedCustom = customIcons[IconSlot.THEMED.customKey(key)],
            original = original,
            density = density
        )
    }

    private fun composeIcon(
        component: ComponentName,
        regular: Drawable?,
        hasIconOverride: Boolean,
        themedValue: String?,
        themedCustom: Bitmap?,
        original: Drawable?,
        density: Int
    ): Drawable? {
        if (themedValue != null) {
            if (themedValue == IconPackManager.OVERRIDE_NONE) {
                return regular ?: original?.let { IconPackManager.withoutMonochrome(it) }
            }

            val base = regular ?: original ?: return null
            val monochrome = runCatching {
                IconPackManager.themedMonochrome(mContext, component, themedValue, config, density, original, themedCustom)
            }.getOrNull() ?: return regular

            return ThemedPackIconDrawable(base, IconPackManager.withMonochrome(base, monochrome))
        }

        if (themedMode && !hasIconOverride && original != null) {
            themedPackIcon(component, original, density)?.let { themed ->
                return regular?.let { ThemedPackIconDrawable(it, themed) } ?: themed
            }

            if (hasStockMonochrome(original)) {
                return regular?.let { ThemedPackIconDrawable(it, original) }
            }
        }

        return regular
    }

    private fun homeBitmapFor(info: Any?): Any? {
        if (config.homeOverrides.isEmpty() && config.homeThemedOverrides.isEmpty()) return null
        if (info.getFieldSilently("itemType") != ITEM_TYPE_APPLICATION) return null

        val container = info.getFieldSilently("container") as? Int ?: return null
        if (container < 0 && container !in HOME_CONTAINERS) return null

        val component = info.callMethodSilently("getTargetComponent") as? ComponentName ?: return null
        val key = component.flattenToString()
        if (!config.hasHomeOverride(key)) return null

        homeBitmaps[key]?.let { return it }
        return buildHomeBitmap(component)?.also { homeBitmaps[key] = it }
    }

    private fun buildHomeBitmap(component: ComponentName): Any? {
        val key = component.flattenToString()
        val density = mContext.resources.configuration.densityDpi
        val original = runCatching { mContext.packageManager.getActivityIcon(component) }.getOrNull()
        val homeValue = config.homeOverrides[key]

        val regular = runCatching {
            when (homeValue) {
                null -> IconPackManager.resolve(mContext, component, config, density, { customIcons[it] }, { original })?.drawable
                IconPackManager.OVERRIDE_ORIGINAL -> null
                else -> IconPackManager.resolveValue(mContext, homeValue, density, customIcons[IconSlot.HOME.customKey(key)])?.drawable
            }
        }.getOrNull()

        val homeThemed = config.homeThemedOverrides[key]
        val themedSlot = if (homeThemed != null) IconSlot.THEMED_HOME else IconSlot.THEMED
        val drawable = composeIcon(
            component = component,
            regular = regular,
            hasIconOverride = (homeValue ?: config.overrides[key]) != null,
            themedValue = homeThemed ?: config.themedOverrides[key],
            themedCustom = customIcons[themedSlot.customKey(key)],
            original = original,
            density = density
        ) ?: regular ?: original ?: return null

        return createBitmapInfo(drawable)
    }

    private fun applyLabelOverride(info: Any?, component: ComponentName?) {
        if (info == null || component == null) return
        val label = config.labels[component.flattenToString()] ?: return

        if (info.getExtraFieldSilently(ORIGINAL_TITLE) == null) {
            info.setExtraField(ORIGINAL_TITLE, info.getFieldSilently("title"))
        }
        info.setField("title", label)
        info.setFieldSilently("appTitle", label)
        info.setFieldSilently("contentDescription", label)
    }

    private fun exportPinnedShortcuts() {
        val launcherApps = mContext.getSystemService(LauncherApps::class.java) ?: return
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return

        val users = runCatching {
            mContext.getSystemService(UserManager::class.java).userProfiles
        }.getOrNull() ?: listOf(Process.myUserHandle())
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val size = (mContext.resources.displayMetrics.density * SHORTCUT_PREVIEW_DP).toInt()

        exportingShortcuts.set(true)
        val shortcuts = try {
            users.flatMap { user ->
                runCatching { launcherApps.getShortcuts(query, user) }.getOrNull().orEmpty()
            }.map { shortcut ->
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(shortcut, mContext.resources.configuration.densityDpi)
                }.getOrNull()?.let { drawable ->
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(bitmap))
                    IconPackManager.encodeBitmap(bitmap)
                }
                IconPackManager.PinnedShortcut(
                    packageName = shortcut.`package`,
                    id = shortcut.id,
                    label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id).toString(),
                    icon = icon
                )
            }.plus(legacyShortcuts(size)).distinctBy { it.component }.sortedBy { it.label.lowercase() }
        } finally {
            exportingShortcuts.remove()
        }

        val serialized = IconPackManager.serializeShortcuts(shortcuts)
        if (Xprefs.getString(PINNED_SHORTCUTS, null) == serialized) return
        runCatching { Xprefs.edit().putString(PINNED_SHORTCUTS, serialized).apply() }
    }

    private fun legacyShortcuts(size: Int): List<IconPackManager.PinnedShortcut> {
        val model = bgDataModelRef?.get() ?: return emptyList()
        val itemsIdMap = model.getFieldSilently("itemsIdMap")
        val items = itemsIdMap as? SparseArray<*>
            ?: itemsIdMap.getFieldSilently("itemsIdMap") as? SparseArray<*>
            ?: return emptyList()

        val infos = synchronized(model) { (0 until items.size()).mapNotNull { items.valueAt(it) } }

        return infos.mapNotNull { info ->
            val (packageName, id) = legacyShortcutKey(info) ?: return@mapNotNull null
            val bitmap = (info.getExtraFieldSilently(ORIGINAL_LEGACY_ICON)
                ?: info.getFieldSilently("bitmap").getFieldSilently("icon")) as? Bitmap
            val title = (info.getExtraFieldSilently(ORIGINAL_TITLE) ?: info.getFieldSilently("title")) as? CharSequence

            IconPackManager.PinnedShortcut(
                packageName = packageName,
                id = id,
                label = title?.toString().orEmpty().ifEmpty { packageName },
                icon = bitmap?.let { IconPackManager.encodeBitmap(Bitmap.createScaledBitmap(it, size, size, true)) }
            )
        }
    }

    private fun legacyShortcutKey(info: Any?): Pair<String, String>? {
        if (info.getFieldSilently("itemType") != ITEM_TYPE_SHORTCUT) return null

        val intent = info.getFieldSilently("intent") as? Intent ?: return null
        val packageName = intent.`package` ?: intent.component?.packageName ?: return null

        return packageName to IconPackManager.legacyShortcutId(intent.toUri(0))
    }

    private fun applyLegacyShortcutOverride(info: Any?) {
        val (packageName, id) = legacyShortcutKey(info) ?: return
        val component = IconPackManager.shortcutComponent(packageName, id)
        applyLabelOverride(info, component)
        if (!config.isActive || component.flattenToString() !in config.overrides) return

        val originalBitmap = info.getFieldSilently("bitmap").getFieldSilently("icon") as? Bitmap
        val original = originalBitmap?.let { BitmapDrawable(mContext.resources, it) }
        val density = mContext.resources.configuration.densityDpi
        val replaced = replaceIcon(component, original, density) ?: return
        val bitmapInfo = createBitmapInfo(replaced) ?: return

        info.setExtraField(ORIGINAL_LEGACY_ICON, originalBitmap)
        info.setField("bitmap", bitmapInfo)
    }

    private fun createBitmapInfo(drawable: Drawable): Any? {
        val factory = findClass($$"com.android.launcher3.icons.LauncherIcons$Companion", suppressError = true)
            .callStaticMethodSilently("obtain", mContext)
            ?: findClass("com.android.launcher3.icons.LauncherIcons", suppressError = true)
                .callStaticMethodSilently("obtain", mContext)
            ?: return null

        return try {
            val options = findClass($$"com.android.launcher3.icons.BaseIconFactory$IconOptions", suppressError = true)
                ?.newInstance()
            runCatching {
                if (options != null) {
                    factory.callMethod("createBadgedIconBitmap", drawable, options)
                } else {
                    factory.callMethod("createBadgedIconBitmap", drawable)
                }
            }.getOrNull()
        } finally {
            if (factory.hasMethod("recycle")) factory.callMethodSilently("recycle") else factory.callMethodSilently("close")
        }
    }

    private fun hasStockMonochrome(icon: Drawable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || icon !is AdaptiveIconDrawable) return false
        val monochrome = icon.monochrome ?: return false
        return XposedHelpers.getAdditionalInstanceField(icon, "mMonochromeIcon") !== monochrome
    }

    private fun themedPackIcon(component: ComponentName, original: Drawable, density: Int): Drawable? {
        if (config.themedIconPacks.isEmpty()) return null

        val themed = runCatching {
            IconPackManager.withThemedIcon(mContext, component, original, config, density)
        }.getOrNull() ?: return null

        config.themedIconPacks.firstOrNull { pkg ->
            IconPackManager.pack(mContext, pkg)?.covers(component) == true
        }?.let { recordSource(it, component.packageName) }
        return themed
    }

    private fun isCovered(component: ComponentName): Boolean {
        val override = config.overrides[component.flattenToString()]
        if (override != null) return override != IconPackManager.OVERRIDE_ORIGINAL
        if (config.maskUnsupported && config.iconPacks.isNotEmpty()) return true

        return config.iconPacks.any { pkg -> IconPackManager.pack(mContext, pkg)?.covers(component) == true }
    }

    private fun copyThemedLayers(from: Any, to: Any) {
        var type: Class<*>? = from.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && THEMED_FIELD.containsMatchIn(it.name) }
                .forEach { field ->
                    runCatching {
                        field.isAccessible = true
                        field.get(from)?.let { field.set(to, it) }
                    }
                }
            type = type.superclass
        }
    }

    private fun Class<*>.hasField(name: String): Boolean {
        var type: Class<*>? = this
        while (type != null) {
            if (type.declaredFields.any { it.name == name }) return true
            type = type.superclass
        }
        return false
    }

    companion object {
        private const val ICON_CACHE_DB = "app_icons.db"
        private const val SCROLL_RESTORE_WINDOW_MS = 4_000L
        private const val FOLDER_REFRESH_DELAY_MS = 1_500L
        private const val SHORTCUT_PREVIEW_DP = 60
        private const val ITEM_TYPE_SHORTCUT = 1
        private const val ITEM_TYPE_APPLICATION = 0
        private val HOME_CONTAINERS = setOf(-100, -101, -103)
        private const val HOME_ORIGINAL_BITMAP = "pleHomeOriginalBitmap"
        private const val ORIGINAL_TITLE = "pleOriginalTitle"
        private const val ORIGINAL_LEGACY_ICON = "pleOriginalLegacyIcon"
        private val THEMED_FIELD = Regex("mono|whiteshadow|themed", RegexOption.IGNORE_CASE)
        private val OPTION_FIELDS = listOf("wrapNonAdaptiveIcon", "isFullBleed", "drawFullBleed", "addShadows", "mAddShadows")
    }

    private fun componentOf(info: Any?): ComponentName? {
        return when (info) {
            is LauncherActivityInfo -> info.componentName
            is ActivityInfo -> ComponentName(info.packageName, info.name)
            is PackageItemInfo -> info.name?.let { ComponentName(info.packageName, it) }
            else -> null
        }
    }
}
