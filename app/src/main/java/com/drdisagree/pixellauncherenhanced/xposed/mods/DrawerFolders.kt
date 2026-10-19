package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.animation.Animator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.TextView
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_FOLDERS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_FOLDERS_ENABLED
import com.drdisagree.pixellauncherenhanced.data.model.DrawerFolder
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.folderPreviewBounds
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

class DrawerFolders(context: Context) : ModPack(context) {

    private class FolderEntry(val folder: DrawerFolder, val apps: List<Any>) {
        val components: List<ComponentName?> = apps.map { it.getFieldSilently("componentName") as? ComponentName }

        fun sameContent(other: FolderEntry) =
            folder.id == other.folder.id && folder.title == other.folder.title && components == other.components
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var folders: List<DrawerFolder> = emptyList()
    private var foldersEnabled = false
    private val appLists: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val folderHolders: MutableMap<Any, FrameLayout> = Collections.synchronizedMap(WeakHashMap())
    private var iconViewType = DEFAULT_ICON_VIEW_TYPE
    private var adapterItemClass: Class<*>? = null
    private var appInfoClass: Class<*>? = null
    private var folderInfoClass: Class<*>? = null
    private var allAppsContainerClass: Class<*>? = null
    private var drawerFolderActive = false
    private var openDrawerFolder: WeakReference<Any>? = null
    private var closeAfterLaunch = false
    private var parkedRipple: Pair<View, android.graphics.drawable.Drawable?>? = null
    private var listPath: String? = null
    private var shownFolders = 0
    private var createdHolders = 0
    private var failedHolders = 0
    private val previewBounds = Rect()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            folders = DrawerFolder.parse(getString(DRAWER_FOLDERS, null))
            foldersEnabled = getBoolean(DRAWER_FOLDERS_ENABLED, false)
        }

        when (key.firstOrNull()) {
            DRAWER_FOLDERS, DRAWER_FOLDERS_ENABLED -> mainHandler.post { refreshLists() }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        DebugRecorder.registerProbe("drawerFolders") {
            "enabled=$foldersEnabled, configured=${folders.size}, eligible=${folders.count { it.apps.size >= MIN_FOLDER_APPS }}, " +
                    "tabScoped=${folders.count { it.tabId != null }}, shown=$shownFolders, path=$listPath, " +
                    "holders=$createdHolders, holderFallbacks=$failedHolders"
        }
        DebugRecorder.registerHealth("Drawer folder views created") {
            if (createdHolders + failedHolders == 0) null else failedHolders == 0
        }

        if (findClass("com.android.launcher3.allapps.AllAppsFolderStore", suppressError = true) != null) {
            listPath = "native"
            return
        }

        adapterItemClass = findClass($$"com.android.launcher3.allapps.BaseAllAppsAdapter$AdapterItem", suppressError = true)
            ?: return
        appInfoClass = findClass("com.android.launcher3.model.data.AppInfo", suppressError = true) ?: return
        folderInfoClass = findClass("com.android.launcher3.model.data.FolderInfo", suppressError = true) ?: return

        hookAppsList()
        hookAdapter()
        hookFolder()
        hookSearch()
    }

    private fun hookSearch() {
        val containerClass = findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", suppressError = true)
        allAppsContainerClass = containerClass

        containerClass
            .hookMethod("animateToSearchState")
            .runBefore { param ->
                if (param.args.firstOrNull { it is Boolean } == true) closeDrawerFolder(param.thisObject as? View)
            }

        containerClass
            .hookMethod("setSearchResults")
            .runBefore { param ->
                if (param.args.firstOrNull() != null) closeDrawerFolder(param.thisObject as? View)
            }
    }

    private fun closeDrawerFolder(anchor: View?) {
        val context = anchor?.context ?: return
        val folder = findClass("com.android.launcher3.folder.Folder", suppressError = true)
            ?.declaredMethods
            ?.firstOrNull { it.name == "getOpen" && it.parameterTypes.size == 1 }
            ?.let { runCatching { it.invoke(null, context) }.getOrNull() }
            ?: return
        if (folder.getFieldSilently("mInfo").isDrawerFolder()) folder.closeNow()
    }

    private fun hookAppsList() {
        val listClass = findClass("com.android.launcher3.allapps.AlphabeticalAppsList", suppressError = true) ?: return

        if (listClass.declaredMethods.any { it.name == "addAppsWithSections" }) {
            listPath = "addAppsWithSections"
            hookAddAppsWithSections(listClass)
        } else {
            listPath = "updateAdapterItems"
            hookUpdateAdapterItems(listClass)
        }
    }

    private fun buildEntries(appsList: Any, apps: List<Any>): List<FolderEntry> {
        if (!foldersEnabled || folders.isEmpty()) return emptyList()

        val activeTab = AppDrawerTabs.activeTabId()
        val source by lazy {
            if (activeTab == null) apps else appsList.storeApps(apps.firstOrNull()?.getFieldSilently("user"))
        }
        val claimed = mutableSetOf<ComponentName>()
        return folders.filter { it.belongsTo(activeTab) }.mapNotNull { folder ->
            val members = folder.apps.filterNot { HideApps.isHidden(it) }
                .flatMap { pkg -> source.filter { it.packageName() == pkg } }
                .filter { it.component()?.let(claimed::add) == true }
            members.takeIf { it.size >= MIN_FOLDER_APPS }?.let { FolderEntry(folder, it) }
        }
    }

    private fun List<Any>.withoutFoldered(entries: List<FolderEntry>): List<Any> {
        val foldered = entries.flatMap { entry -> entry.apps.mapNotNull { it.component() } }.toSet()
        return filterNot { it.component() in foldered }
    }

    private fun hookUpdateAdapterItems(listClass: Class<*>) {
        listClass
            .hookMethod("updateAdapterItems")
            .capability("Drawer folders")
            .runAround(before = { param ->
                appLists.add(param.thisObject)

                val original = param.thisObject.getFieldSilently("mApps") as? List<*> ?: return@runAround
                val apps = original.filterNotNull()
                val entries = buildEntries(param.thisObject, apps)
                shownFolders = entries.size
                if (entries.isEmpty()) return@runAround

                val remaining = apps.withoutFoldered(entries)
                val section = remaining.firstOrNull()?.getFieldSilently("sectionName")
                val placeholders = entries.mapNotNull { entry ->
                    createPlaceholder(entry)?.apply { section?.let { setFieldSilently("sectionName", it) } }
                }
                param.setObjectExtra(ORIGINAL_APPS, original)
                param.thisObject.setFieldSilently("mApps", ArrayList(placeholders + remaining))
            }, after = { param ->
                val original = param.getObjectExtra(ORIGINAL_APPS) ?: return@runAround
                param.thisObject.setFieldSilently("mApps", original)
            })

        adapterItemClass
            .hookMethod("asApp")
            .runBefore { param ->
                val placeholder = param.args.getOrNull(0) ?: return@runBefore
                val entry = placeholder.getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry ?: return@runBefore
                createAdapterItem(entry, placeholder)?.let { param.result = it }
            }
    }

    private fun hookAddAppsWithSections(listClass: Class<*>) {
        listClass
            .hookMethod("addAppsWithSections")
            .capability("Drawer folders")
            .runAround(before = { param ->
                appLists.add(param.thisObject)

                val listIndex = param.args.indexOfFirst { it is List<*> }.takeIf { it >= 0 } ?: return@runAround
                val apps = (param.args[listIndex] as List<*>).filterNotNull()
                val entries = buildEntries(param.thisObject, apps)
                shownFolders = entries.size
                if (entries.isEmpty()) return@runAround

                param.args[listIndex] = apps.withoutFoldered(entries)
                param.setObjectExtra(PENDING_ENTRIES, entries)
            }, after = { param ->
                @Suppress("UNCHECKED_CAST")
                val entries = param.getObjectExtra(PENDING_ENTRIES) as? List<FolderEntry> ?: return@runAround
                val start = param.args.firstOrNull { it is Int } as? Int ?: return@runAround
                val end = param.result as? Int ?: return@runAround

                @Suppress("UNCHECKED_CAST")
                val items = param.thisObject.getFieldSilently("mAdapterItems") as? MutableList<Any?> ?: return@runAround
                if (start !in 0..items.size) return@runAround

                (items.getOrNull(start)?.getFieldSilently("viewType") as? Int)
                    ?.takeIf { it and FOLDER_VIEW_TYPE == 0 }
                    ?.let { iconViewType = it }

                val folderItems = entries.mapNotNull { entry ->
                    createPlaceholder(entry)?.let { createAdapterItem(entry, it) }
                }
                if (folderItems.isEmpty()) return@runAround

                items.addAll(start, folderItems)
                (param.thisObject.getFieldSilently("mFastScrollerSections") as? List<*>)?.forEach { section ->
                    val position = section.getFieldSilently("position") as? Int ?: return@forEach
                    if (position >= start) section.setFieldSilently("position", position + folderItems.size)
                }
                param.result = end + folderItems.size
            })
    }

    private fun createPlaceholder(entry: FolderEntry): Any? = runCatching {
        appInfoClass!!.getConstructor(appInfoClass).newInstance(entry.apps.first()).apply {
            setFieldSilently("title", entry.folder.title)
            setExtraField(FOLDER_ENTRY, entry)
        }
    }.onFailure { log(this, it) }.getOrNull()

    private fun createAdapterItem(entry: FolderEntry, placeholder: Any): Any? = runCatching {
        adapterItemClass!!.getConstructor(Int::class.javaPrimitiveType).newInstance(iconViewType or FOLDER_VIEW_TYPE).apply {
            setFieldSilently("itemInfo", placeholder)
            setExtraField(FOLDER_ENTRY, entry)
        }
    }.onFailure { log(this, it) }.getOrNull()

    private fun hookAdapter() {
        val adapterClass = findClass("com.android.launcher3.allapps.BaseAllAppsAdapter", suppressError = true) ?: return

        adapterClass
            .hookMethod("onCreateViewHolder")
            .capability("Drawer folders")
            .runBefore { param ->
                val viewType = param.args.getOrNull(1) as? Int ?: return@runBefore
                if (viewType and FOLDER_VIEW_TYPE == 0) return@runBefore
                val parent = param.args[0] as? ViewGroup ?: return@runBefore

                val container = FrameLayout(parent.context).apply {
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = parent.recyclerLayoutParams()
                }
                val holder = runCatching {
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, arrayOf(parent, iconViewType))
                        ?.takeIf { it.replaceItemView(container) }
                }.onFailure { log(this, it) }.getOrNull()
                if (holder == null) {
                    failedHolders++
                    param.args[1] = iconViewType
                    return@runBefore
                }
                createdHolders++
                folderHolders[holder] = container
                param.result = holder
            }

        adapterClass
            .hookMethod("onBindViewHolder")
            .capability("Drawer folders")
            .runBefore { param ->
                val container = folderHolders[param.args.getOrNull(0) ?: return@runBefore] ?: return@runBefore
                val position = param.args.getOrNull(1) as? Int ?: return@runBefore
                param.result = null

                val apps = param.thisObject.getFieldSilently("mApps")
                val item = (apps.getFieldSilently("mAdapterItems") as? List<*>)?.getOrNull(position)
                val entry = item.getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry ?: return@runBefore
                val activity = param.thisObject.getFieldSilently("mActivityContext") as? Context ?: container.context
                bindFolder(container, activity, entry)
            }

        adapterItemClass
            .hookMethod("isSameAs")
            .runBefore { param ->
                val mine = param.thisObject.getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry
                val other = param.args.getOrNull(0).getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry
                if (mine == null && other == null) return@runBefore
                param.result = mine != null && other != null && mine.folder.id == other.folder.id
            }

        adapterItemClass
            .hookMethod("isContentSame")
            .runBefore { param ->
                val mine = param.thisObject.getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry
                val other = param.args.getOrNull(0).getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry
                if (mine == null && other == null) return@runBefore
                param.result = mine != null && other != null && mine.sameContent(other)
            }
    }

    private fun Any.replaceItemView(view: View): Boolean {
        val fields = generateSequence<Class<*>>(javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && View::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .filter { field -> field.get(this).let { it is View && it !is ViewGroup } }
            .toList()
        if (fields.isEmpty()) return false
        fields.forEach { it.set(this, view) }
        return true
    }

    @SuppressLint("DiscouragedApi")
    private fun bindFolder(container: FrameLayout, activity: Context, entry: FolderEntry) {
        val current = container.getChildAt(0)
        val bound = current.getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry
        if (bound != null && bound.sameContent(entry)) return

        container.removeAllViews()
        val layoutId = activity.resources.getIdentifier("folder_icon", "layout", activity.packageName)
        if (layoutId == 0) return

        runCatching {
            val folderInfo = createFolderInfo(activity, entry) ?: return
            val icon = findClass("com.android.launcher3.folder.FolderIcon", suppressError = true)
                ?.declaredMethods
                ?.firstOrNull { it.name == "inflateFolderAndIcon" && it.parameterTypes.size == 4 }
                ?.invoke(null, layoutId, activity, container, folderInfo) as? View
                ?: return
            icon.setExtraField(FOLDER_ENTRY, entry)
            icon.setOnLongClickListener { view -> view.performClick() }
            (icon.getFieldSilently("mFolderName") as? View)?.visibility = View.VISIBLE
            container.addView(
                icon,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            icon.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> alignWithApps(container, view) }
        }.onFailure { log(this, it) }
    }

    private fun alignWithApps(container: FrameLayout, icon: View, attempt: Int = 0) {
        val list = container.parent as? ViewGroup
        val appIcon = list?.let { (0 until it.childCount).map(it::getChildAt) }
            ?.firstOrNull { it !== container && it.javaClass.name.endsWith("BubbleTextView") && it.height > 0 }
        if (appIcon == null || !icon.getFieldSilently("mBackground").folderPreviewBounds(previewBounds)) {
            if (attempt < MAX_ALIGN_ATTEMPTS) icon.post { alignWithApps(container, icon, attempt + 1) }
            return
        }
        val appIconSize = appIcon.getFieldSilently("mIconSize") as? Int ?: previewBounds.height()
        val offset = (appIcon.paddingTop + appIconSize / 2f - previewBounds.exactCenterY()).roundToInt()
        val params = icon.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin != offset) {
            params.topMargin = offset
            icon.layoutParams = params
        }
        val label = icon.getFieldSilently("mFolderName") as? TextView ?: return
        val resized = label.matchLabel(appIcon as? TextView ?: return, offset.toFloat())
        if (resized && attempt < MAX_ALIGN_ATTEMPTS) icon.post { alignWithApps(container, icon, attempt + 1) }
    }

    private fun TextView.matchLabel(appLabel: TextView, iconTop: Float): Boolean {
        val resized = textSize != appLabel.textSize
        if (resized) setTextSize(TypedValue.COMPLEX_UNIT_PX, appLabel.textSize)
        if (typeface != appLabel.typeface) typeface = appLabel.typeface
        if (textColors != appLabel.textColors) setTextColor(appLabel.textColors)
        if (shadowRadius != appLabel.shadowRadius || shadowColor != appLabel.shadowColor) {
            setShadowLayer(appLabel.shadowRadius, appLabel.shadowDx, appLabel.shadowDy, appLabel.shadowColor)
        }
        val shown = !appLabel.text.isNullOrEmpty()
        alpha = if (shown) 1f else 0f
        if (resized || !shown || baseline < 0 || appLabel.baseline < 0) return resized
        val labelOffset = appLabel.baseline - (iconTop + top + baseline)
        if (translationY != labelOffset) translationY = labelOffset
        return false
    }

    private fun createFolderInfo(context: Context, entry: FolderEntry): Any? {
        val folderInfo = folderInfoClass!!.getConstructor().newInstance()
        val id = FOLDER_ID_BASE - entry.folder.id
        folderInfo.setFieldSilently("id", id)
        folderInfo.setFieldSilently("container", CONTAINER_ALL_APPS)
        folderInfo.setFieldSilently("title", entry.folder.title)
        folderInfo.setExtraField(FOLDER_ID, entry.folder.id)
        folderInfo.setExtraField(FOLDER_ENTRY, entry)

        entry.apps.forEachIndexed { rank, app ->
            val item = app.callMethodSilently("makeWorkspaceItem", context) ?: return@forEachIndexed
            item.setFieldSilently("container", id)
            item.setFieldSilently("rank", rank)
            folderInfo.addToFolder(item)
        }
        return folderInfo
    }

    private fun Any.addToFolder(item: Any) {
        val method = generateSequence<Class<*>>(javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == "add" && it.parameterTypes.firstOrNull()?.isInstance(item) == true }
            .minByOrNull { it.parameterTypes.size }
            ?: return
        val extra = method.parameterTypes.drop(1).map<Class<*>, Any> { if (it == Boolean::class.javaPrimitiveType) false else 0 }
        method.isAccessible = true
        method.invoke(this, item, *extra.toTypedArray())
    }

    private fun hookFolder() {
        val folderClass = findClass("com.android.launcher3.folder.Folder", suppressError = true) ?: return

        folderClass
            .hookMethod("animateOpen")
            .runBefore { param ->
                if (!param.thisObject.getFieldSilently("mInfo").isDrawerFolder()) return@runBefore
                drawerFolderActive = true
                openDrawerFolder = WeakReference(param.thisObject)
            }

        folderClass
            .hookMethod("closeComplete")
            .runAfter { param ->
                if (param.thisObject.getFieldSilently("mInfo").isDrawerFolder()) drawerFolderActive = false
                parkedRipple?.let { (ripple, background) -> ripple.background = background }
                parkedRipple = null
            }

        val launcherClass = findClass("com.android.launcher3.Launcher", suppressError = true)

        launcherClass
            .hookMethod("startActivitySafely")
            .runAfter { param ->
                if (param.args.drawerFolderItem() != null) closeAfterLaunch = true
            }

        launcherClass
            .hookMethod("onStop", "onResume")
            .runAfter {
                if (!closeAfterLaunch) return@runAfter
                closeAfterLaunch = false
                val folder = openDrawerFolder?.get()?.takeIf { drawerFolderActive } ?: return@runAfter
                openDrawerFolder = null
                folder.closeNow()
            }

        findClass("com.android.launcher3.views.ScrimView", suppressError = true)
            .hookMethod("setBackgroundColor")
            .runBefore { param ->
                if (drawerFolderActive && param.thisObject.isScrimView()) param.result = null
            }

        findClass($$"com.android.launcher3.folder.FolderSpringAnimatorSet$Factory", suppressError = true)
            .hookMethod("playSpringAnimation")
            .suppressError()
            .runBefore { param ->
                if (drawerFolderActive && param.args.lastOrNull().isScrimView()) param.result = null
            }

        folderClass
            .hookMethod("updateItemLocationsInDatabaseBatch")
            .runBefore { param ->
                if (param.thisObject.getFieldSilently("mInfo").isDrawerFolder()) param.result = null
            }

        folderClass
            .hookMethod("onLongClick")
            .runBefore { param ->
                val info = param.thisObject.getFieldSilently("mInfo")
                if (!info.isDrawerFolder()) return@runBefore
                val view = param.args.getOrNull(0) as? View ?: return@runBefore
                if (!startDrawerDrag(view, info)) showAppPopup(view)
                param.result = true
            }

        folderClass
            .hookMethod("animateOpen")
            .runAfter { param ->
                val folder = param.thisObject
                if (!folder.getFieldSilently("mInfo").isDrawerFolder()) return@runAfter
                folder.getFieldSilently("mPageIndicator")?.setFieldSilently("mEntryAnimationRadiusFactors", FloatArray(0))
                (folder as View).searchRipple()?.let { ripple ->
                    if (parkedRipple?.first !== ripple) parkedRipple = ripple to ripple.background
                    ripple.background = null
                }
            }

        findClass("com.android.launcher3.dragndrop.DragController", suppressError = true)
            .hookMethod("callOnDragStart")
            .runBefore {
                val folder = openDrawerFolder?.get()?.takeIf { drawerFolderActive } ?: return@runBefore
                openDrawerFolder = null
                folder.closeNow()
            }

        findClass("com.android.launcher3.QuickstepTransitionManager", suppressError = true)
            .hookMethod("findLauncherView")
            .runAfter { param ->
                if (folderHolders.isEmpty() || (param.result as? View).isInAllApps()) return@runAfter
                val packageName = param.args.firstOrNull().closingPackage() ?: return@runAfter
                drawerFolderIcon(packageName)?.let { param.result = it }
            }

        listOf(
            "com.android.launcher3.Launcher",
            "com.android.launcher3.util.BaseContext",
            "com.android.launcher3.views.ActivityContext"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("findFolderIcon")
                .suppressError()
                .runAfter { param ->
                    if (param.result != null) return@runAfter
                    val id = param.args.firstOrNull() as? Int ?: return@runAfter
                    if (id !in (FOLDER_ID_BASE - MAX_FOLDER_ID)..<FOLDER_ID_BASE) return@runAfter
                    param.result = folderHolders.values.toList()
                        .mapNotNull { it.getChildAt(0) }
                        .firstOrNull { it.tag.getFieldSilently("id") == id }
                }
        }

        findClass("com.android.launcher3.folder.LauncherDelegate", suppressError = true)
            .hookMethod("replaceFolderWithFinalItem")
            .runBefore { param ->
                if (param.args.getOrNull(0).getFieldSilently("mInfo").isDrawerFolder()) param.result = false
            }

        listOf(
            "com.android.launcher3.DropTargetHandler" to "onDeleteComplete",
            "com.android.launcher3.dragndrop.ItemDeleteHandler" to "completeDelete"
        ).forEach { (className, completeMethod) ->
            val handlerClass = findClass(className, suppressError = true)

            handlerClass
                .hookMethod("prepareToUndoDelete")
                .suppressError()
                .runBefore { param ->
                    if (param.args.drawerFolderItem() != null) param.result = null
                }

            handlerClass
                .hookMethod(completeMethod)
                .suppressError()
                .runBefore { param ->
                    val item = param.args.drawerFolderItem() ?: return@runBefore
                    param.result = null
                    val folderId = FOLDER_ID_BASE - (item.getFieldSilently("container") as Int)
                    val packageName = (item.callMethodSilently("getTargetComponent") as? ComponentName)?.packageName
                        ?: return@runBefore
                    removeApp(folderId, packageName)
                }
        }

        folderInfoClass
            .hookMethod("setOption")
            .runBefore { param ->
                if (param.thisObject.isDrawerFolder()) param.result = null
            }

        folderInfoClass
            .hookMethod("setTitle")
            .runBefore { param ->
                val info = param.thisObject
                if (!info.isDrawerFolder()) return@runBefore
                val title = param.args.getOrNull(0)?.toString()?.trim().orEmpty()
                param.result = null
                if (title.isEmpty()) return@runBefore

                info.setFieldSilently("title", title)
                val folderId = info.getExtraFieldSilently(FOLDER_ID) as? Int ?: return@runBefore
                saveTitle(folderId, title)
            }
    }

    private fun startDrawerDrag(view: View, folderInfo: Any?): Boolean {
        val entry = folderInfo.getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry ?: return false
        val item = view.tag ?: return false
        val component = item.callMethodSilently("getTargetComponent") as? ComponentName ?: return false
        val user = item.getFieldSilently("user")
        val app = entry.apps.firstOrNull { app ->
            app.component() == component && (user == null || app.getFieldSilently("user") == user)
        } ?: return false
        val listener = findClass("com.android.launcher3.touch.ItemLongClickListener", suppressError = true)
            ?.getStaticFieldSilently("INSTANCE_ALL_APPS") as? View.OnLongClickListener
            ?: return false

        view.tag = app
        return try {
            listener.onLongClick(view)
        } catch (throwable: Throwable) {
            log(this, throwable)
            false
        } finally {
            view.tag = item
        }
    }

    private fun showAppPopup(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        var context: Context? = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val controller = context.getFieldSilently("mPopupControllerForAppIcons")
            ?: findClass("com.android.launcher3.popup.PopupControllerForAppIcon", suppressError = true)
                ?.let { runCatching { it.getConstructor().newInstance() }.getOrNull() }
        if (controller != null) {
            controller.callMethodSilently("show", view)
            return
        }

        findClass("com.android.launcher3.popup.PopupContainerWithArrow", suppressError = true)
            ?.declaredMethods
            ?.firstOrNull { it.name == "showForIcon" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(view) }
            ?.let { runCatching { it.invoke(null, view) }.onFailure { log(this, it) } }
    }

    private fun saveTitle(folderId: Int, title: String) {
        saveFolders { if (it.id == folderId) it.copy(title = title) else it }
    }

    private fun removeApp(folderId: Int, packageName: String) {
        saveFolders { if (it.id == folderId) it.copy(apps = it.apps - packageName) else it }
        mainHandler.post { refreshLists() }
    }

    private fun saveFolders(transform: (DrawerFolder) -> DrawerFolder) {
        val updated = DrawerFolder.parse(Xprefs.getString(DRAWER_FOLDERS, null)).map(transform)
        folders = updated
        Xprefs.edit().putString(DRAWER_FOLDERS, DrawerFolder.serialize(updated)).apply()
    }

    private fun Any?.closingPackage(): String? {
        val targets = (this as? Array<*>)?.toList() ?: listOf(this)
        return targets.firstNotNullOfOrNull { target ->
            if (target.getFieldSilently("mode") != MODE_CLOSING) return@firstNotNullOfOrNull null
            val task = target.getFieldSilently("taskInfo") ?: return@firstNotNullOfOrNull null
            val component = task.getFieldSilently("baseActivity") ?: task.getFieldSilently("topActivity")
                ?: (task.getFieldSilently("baseIntent") as? Intent)?.component
            (component as? ComponentName)?.packageName
        }
    }

    private fun View?.isInAllApps(): Boolean {
        val containerClass = allAppsContainerClass ?: return false
        return generateSequence(this?.parent) { it.parent }.any { containerClass.isInstance(it) }
    }

    private fun drawerFolderIcon(packageName: String): View? {
        val visible = Rect()
        return folderHolders.values.toList().firstNotNullOfOrNull { container ->
            val icon = container.getChildAt(0) ?: return@firstNotNullOfOrNull null
            val entry = icon.getExtraFieldSilently(FOLDER_ENTRY) as? FolderEntry ?: return@firstNotNullOfOrNull null
            if (entry.components.none { it?.packageName == packageName }) return@firstNotNullOfOrNull null
            val fullyVisible = icon.isAttachedToWindow && icon.isShown &&
                    icon.getGlobalVisibleRect(visible) && visible.height() >= icon.height
            icon.takeIf { fullyVisible }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun View.searchRipple(): View? {
        val res = resources
        val containerId = res.getIdentifier("search_container_all_apps", "id", context.packageName)
        val rippleId = res.getIdentifier("ripple", "id", context.packageName)
        if (containerId == 0 || rippleId == 0) return null
        return rootView.findViewById<View>(containerId)?.findViewById(rippleId)
    }

    private fun Any.closeNow() {
        (getFieldSilently("mCurrentAnimator") as? Animator)?.takeIf { it.isRunning }?.end()
        callMethodSilently("close", false)
    }

    private fun Array<Any?>.drawerFolderItem(): Any? = firstOrNull { isDrawerFolderItem(it) }

    private fun refreshLists() {
        appLists.toList().forEach { it.callMethodSilently("onAppsUpdated") }
    }

    private fun Any?.isScrimView(): Boolean = this?.javaClass?.name?.endsWith("ScrimView") == true

    private fun Any?.isDrawerFolder(): Boolean =
        this != null && getExtraFieldSilently(FOLDER_ID) is Int

    private fun Any.component(): ComponentName? = getFieldSilently("componentName") as? ComponentName

    private fun Any.packageName(): String? = component()?.packageName

    private fun Any.storeApps(user: Any?): List<Any> {
        val store = getFieldSilently("mAllAppsStore") ?: return emptyList()
        val apps = (store.getFieldSilently("mApps") ?: store.callMethodSilently("getApps")) as? Array<*>
            ?: return emptyList()
        val owner = user ?: android.os.Process.myUserHandle()
        return apps.filterNotNull().filter { (it.getFieldSilently("user") ?: owner) == owner }
    }

    private fun ViewGroup.recyclerLayoutParams(): ViewGroup.LayoutParams {
        val base = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return runCatching {
            ViewGroup::class.java.getDeclaredMethod("generateLayoutParams", ViewGroup.LayoutParams::class.java)
                .apply { isAccessible = true }
                .invoke(this, base) as ViewGroup.LayoutParams
        }.getOrDefault(base)
    }

    companion object {
        fun isDrawerFolderItem(item: Any?): Boolean {
            val container = item.getFieldSilently("container") as? Int ?: return false
            return container in (FOLDER_ID_BASE - MAX_FOLDER_ID)..<FOLDER_ID_BASE
        }

        private const val DEFAULT_ICON_VIEW_TYPE = 1 shl 1
        private const val FOLDER_VIEW_TYPE = 1 shl 24
        private const val CONTAINER_ALL_APPS = -104
        private const val MODE_CLOSING = 1
        private const val FOLDER_ID_BASE = Int.MAX_VALUE - 1000
        private const val MIN_FOLDER_APPS = 2
        private const val MAX_ALIGN_ATTEMPTS = 5
        private const val MAX_FOLDER_ID = 1_000_000
        private const val FOLDER_ENTRY = "pleDrawerFolderEntry"
        private const val FOLDER_ID = "pleDrawerFolderId"
        private const val PENDING_ENTRIES = "pleDrawerFolderPending"
        private const val ORIGINAL_APPS = "pleDrawerFolderOriginalApps"
    }
}
