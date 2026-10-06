package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_APPS_FROM_APP_DRAWER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_ARRANGEMENT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_AUTO_FILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_AUTO_SCREENS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_FIRST_SCREEN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_SWIPE_ACTION
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers.removeAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.text.Collator
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.math.min

class AppDrawerMode(context: Context) : ModPack(context) {

    private var noDrawerMode = false
    private var swipeUpAction = SWIPE_UP_NOTHING
    private var arrangement = ARRANGEMENT_SORTED
    private var autoFill = true
    private val inWorkspaceSync = ThreadLocal<Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resortRunnable = Runnable { enqueueWorkspaceSync() }

    private var launcherModelRef: WeakReference<Any>? = null
    private var modelUpdateTaskClass: Class<*>? = null
    private var callbackTaskClass: Class<*>? = null
    private var invariantDeviceProfileClass: Class<*>? = null
    private val privateProfiles = ConcurrentHashMap<UserHandle, Boolean>()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            noDrawerMode = getBoolean(NO_DRAWER_MODE, false)
            swipeUpAction = getListString(NO_DRAWER_SWIPE_ACTION, "0")!!.toInt()
            arrangement = getListString(NO_DRAWER_ARRANGEMENT, "0")!!.toInt()
            autoFill = getBoolean(NO_DRAWER_AUTO_FILL, true)
        }

        when (key.firstOrNull()) {
            NO_DRAWER_MODE -> {
                enqueueWorkspaceSync()
                HideApps.updateLauncherIcons(mContext)
            }

            NO_DRAWER_ARRANGEMENT,
            NO_DRAWER_AUTO_FILL,
            APP_BLOCK_LIST,
            HIDE_APPS_FROM_APP_DRAWER -> if (noDrawerMode) enqueueWorkspaceSync()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherModelClass = findClass("com.android.launcher3.LauncherModel") ?: return
        modelUpdateTaskClass = findClass(
            $$"com.android.launcher3.LauncherModel$ModelUpdateTask",
            suppressError = true
        )?.takeIf { it.isInterface } ?: return
        callbackTaskClass = findClass(
            $$"com.android.launcher3.LauncherModel$CallbackTask",
            suppressError = true
        )?.takeIf { it.isInterface }
        invariantDeviceProfileClass = findClass(
            "com.android.launcher3.InvariantDeviceProfile",
            suppressError = true
        )

        launcherModelClass
            .hookMethod("enqueueModelUpdateTask")
            .runBefore { param -> rememberLauncherModel(param.thisObject) }

        findClass("com.android.launcher3.model.LoaderTask", suppressError = true)
            .hookMethod("run")
            .suppressError()
            .runAfter { param ->
                rememberLauncherModel(
                    param.thisObject.getFieldSilently("mModel")
                        ?: param.thisObject.getFieldSilently("mApp").callMethodSilently("getModel")
                )
                enqueueWorkspaceSync()
            }

        findClass("com.android.launcher3.model.tasks.PackageUpdatedTask", suppressError = true)
            .hookMethod("execute")
            .suppressError()
            .runAfter { if (noDrawerMode) enqueueWorkspaceSync() }

        listOf(
            "com.android.launcher3.model.ModelWriter",
            $$"com.android.launcher3.model.ModelWriter$TransactionContextImpl"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod(
                    "modifyItemInDatabase",
                    "moveItemInDatabase",
                    "moveItemsInDatabase",
                    "addOrMoveItemInDatabase",
                    "addItemToDatabase",
                    "addItemsToDatabase",
                    "deleteItemFromDatabase",
                    "deleteItemsFromDatabase",
                    "deleteWidgetInfo"
                )
                .suppressError()
                .runBefore { param -> scheduleResortIfManaged(param.args) }
                .runAfter { param -> scheduleResortIfManaged(param.args) }
        }

        findClass("com.android.launcher3.SessionCommitReceiver", suppressError = true)
            .hookMethod("isEnabled")
            .suppressError()
            .runBefore { param -> if (noDrawerMode) param.result = false }

        hookAppsList()
        hookSwipeUp()
        hookRemoval()
        hookWorkspaceOptions()
    }

    @SuppressLint("DiscouragedApi")
    private fun hookWorkspaceOptions() {
        val allAppsLabel = mContext.resources.getIdentifier(
            "all_apps_button_label",
            "string",
            mContext.packageName
        )
        if (allAppsLabel == 0) return

        findClass("com.android.launcher3.popup.WorkspaceLongPressOptions", suppressError = true)
            .hookMethod("getAll")
            .suppressError()
            .runAfter { param ->
                if (!noDrawerMode) return@runAfter

                val original = param.result as? List<*> ?: return@runAfter
                val filtered = original.filterNot { it.labelResId() == allAppsLabel }
                if (filtered.size == original.size) return@runAfter

                val returnType = (param.method as? Method)?.returnType ?: return@runAfter
                val replacement = original.javaClass.declaredConstructors
                    .sortedBy { it.parameterTypes.size }
                    .firstNotNullOfOrNull { constructor ->
                        runCatching {
                            constructor.isAccessible = true
                            val instance = when {
                                constructor.parameterTypes.isEmpty() -> constructor.newInstance()
                                constructor.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) ->
                                    constructor.newInstance(filtered.size)

                                else -> null
                            }

                            @Suppress("UNCHECKED_CAST")
                            (instance as? MutableList<Any?>)?.apply { addAll(filtered) }
                        }.getOrNull()
                    }
                    ?: ArrayList(filtered)

                if (returnType.isInstance(replacement)) param.result = replacement
            }

        findClass("com.android.launcher3.views.OptionsPopupView", suppressError = true)
            .hookMethod("getOptions")
            .suppressError()
            .runAfter { param ->
                if (!noDrawerMode) return@runAfter

                @Suppress("UNCHECKED_CAST")
                (param.result as? MutableList<Any?>)
                    ?.removeAll { it.getFieldSilently("labelRes") == allAppsLabel }
            }
    }

    private fun hookRemoval() {
        val deleteDropTargetClass = findClass("com.android.launcher3.DeleteDropTarget", suppressError = true)

        listOf(deleteDropTargetClass, findClass("com.android.launcher3.ButtonDropTarget", suppressError = true))
            .forEach { targetClass ->
                targetClass
                    .hookMethod("supportsDrop")
                    .suppressError()
                    .runAfter { param ->
                        if (deleteDropTargetClass?.isInstance(param.thisObject) != true) return@runAfter
                        if (noDrawerMode && param.args[0].isProtectedFromRemoval()) {
                            param.result = false
                        }
                    }
            }

        val removeFactory = findClass(
            "com.android.launcher3.popup.SystemShortcut",
            suppressError = true
        ).getStaticFieldSilently("REMOVE") ?: return

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
                if (!noDrawerMode) return@runAfter

                val itemInfo = param.args.firstOrNull { it?.getFieldSilently("itemType") != null }
                if (itemInfo != null && !itemInfo.isProtectedFromRemoval()) return@runAfter

                @Suppress("UNCHECKED_CAST")
                val shortcuts = param.result as? Stream<Any?> ?: return@runAfter
                param.result = shortcuts.filter { it !== removeFactory }
            }
    }

    private fun hookAppsList() {
        findClass("com.android.launcher3.allapps.AlphabeticalAppsList", suppressError = true)
            .hookMethod("onAppsUpdated")
            .suppressError()
            .runBefore { param ->
                if (!noDrawerMode || param.thisObject.getFieldSilently("mAllAppsStore") == null) {
                    return@runBefore
                }

                setAdditionalInstanceField(
                    param.thisObject,
                    ORIGINAL_FILTER_KEY,
                    param.thisObject.getFieldSilently("mItemFilter") ?: NO_FILTER
                )
                param.thisObject.setField(
                    "mItemFilter",
                    Predicate<Any?> { app -> isPrivateProfile(app.getFieldSilently("user") as? UserHandle) }
                )
            }
            .runAfter { param ->
                val original = removeAdditionalInstanceField(param.thisObject, ORIGINAL_FILTER_KEY)
                    ?: return@runAfter
                param.thisObject.setField("mItemFilter", original.takeIf { it !== NO_FILTER })
            }
    }

    private fun hookSwipeUp() {
        val launcherStateClass = findClass("com.android.launcher3.LauncherState", suppressError = true)
            ?: return
        val normalState = launcherStateClass.getStaticFieldSilently("NORMAL")
        val allAppsState = launcherStateClass.getStaticFieldSilently("ALL_APPS")

        listOf(
            "com.android.launcher3.uioverrides.touchcontrollers.PortraitStatesTouchController",
            "com.android.launcher3.uioverrides.touchcontrollers.NoButtonNavbarToOverviewTouchController"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("getTargetState")
                .suppressError()
                .runAfter { param ->
                    if (!noDrawerMode || swipeUpAction != SWIPE_UP_NOTHING) return@runAfter
                    if (normalState == null || allAppsState == null) return@runAfter

                    if (param.args.firstOrNull() === normalState && param.result === allAppsState) {
                        param.result = normalState
                    }
                }
        }

        findClass("com.android.launcher3.uioverrides.QuickstepLauncher", suppressError = true)
            .hookMethod("onStateSetEnd")
            .suppressError()
            .runAfter { param ->
                if (!noDrawerMode || swipeUpAction != SWIPE_UP_SEARCH) return@runAfter
                if (allAppsState == null || param.args[0] !== allAppsState) return@runAfter

                param.thisObject.callMethodSilently("getAppsView")
                    .getFieldSilently("mSearchUiManager")
                    .callMethodSilently("getEditText")
                    .callMethodSilently("showKeyboard")
            }
    }

    private fun rememberLauncherModel(model: Any?) {
        if (model != null && launcherModelRef?.get() !== model) {
            launcherModelRef = WeakReference(model)
        }
    }

    private fun enqueueWorkspaceSync() {
        if (!noDrawerMode && storedFirstModScreen() == null) return

        val model = launcherModelRef?.get() ?: return
        val taskClass = modelUpdateTaskClass ?: return
        var legacyArgs: Array<Any?>? = null
        val task = Proxy.newProxyInstance(taskClass.classLoader, arrayOf(taskClass)) { proxy, method, args ->
            when (method.name) {
                "execute" -> {
                    runCatching { syncWorkspace(args[0], args[1], args[2]) }
                        .onFailure { log(this@AppDrawerMode, it) }
                    null
                }

                "init" -> {
                    legacyArgs = args
                    null
                }

                "run" -> {
                    val initArgs = legacyArgs
                    if (initArgs != null && model.callMethodSilently("isModelLoaded") != false) {
                        runCatching {
                            val controller = LegacyTaskController(
                                context = initArgs[0].callMethodSilently("getContext") as? Context ?: mContext,
                                model = initArgs[1] ?: model,
                                uiExecutor = initArgs[4] as Executor
                            )
                            syncWorkspace(controller, initArgs[2]!!, initArgs[3]!!)
                        }.onFailure { log(this@AppDrawerMode, it) }
                    }
                    null
                }

                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PixelLauncherEnhancedWorkspaceSync"
                else -> null
            }
        }

        model.callMethod("enqueueModelUpdateTask", task)
    }

    private fun syncWorkspace(controller: Any, dataModel: Any, allAppsList: Any) {
        inWorkspaceSync.set(true)

        try {
            synchronized(dataModel) {
                val items = (dataModel.getFieldSilently("itemsIdMap") as? Iterable<*>)
                    ?.filterNotNull()
                    ?: return

                if (noDrawerMode) {
                    syncAutoPages(controller, items, allAppsList)
                } else {
                    removeAutoAddedItems(controller, items)
                }
            }
        } finally {
            inWorkspaceSync.remove()
        }
    }

    private fun syncAutoPages(controller: Any, items: List<Any>, allAppsList: Any) {
        val context = controller.taskContext()
        val blockList = if (HideApps.HIDE_APPS_ENABLED) {
            Xprefs.getStringSet(APP_BLOCK_LIST, emptySet()).orEmpty()
        } else {
            emptySet()
        }
        val sorted = arrangement == ARRANGEMENT_SORTED
        val collator = Collator.getInstance()
        val byTitle = Comparator<Any> { first, second ->
            collator.compare(
                first.getFieldSilently("title")?.toString().orEmpty(),
                second.getFieldSilently("title")?.toString().orEmpty()
            )
        }
        val byPosition = compareBy<Any>(
            { it.intField("screenId") },
            { it.intField("cellY") },
            { it.intField("cellX") }
        )

        val desktopItems = items.filter { it.getFieldSilently("container") == CONTAINER_DESKTOP }
        val firstModScreen = storedFirstModScreen()
            ?: ((desktopItems.maxOfOrNull { it.intField("screenId") } ?: 0) + 1)
        val modItems = desktopItems.filter { it.intField("screenId") >= firstModScreen }
        val modApps = modItems.filter { it.getFieldSilently("itemType") == ITEM_TYPE_APPLICATION }
        val fixedItems = modItems.filter { it.getFieldSilently("itemType") != ITEM_TYPE_APPLICATION }

        val hiddenItems = modApps.filter { it.isAutoAdded() && it.packageName() in blockList }
        val hiddenItemSet = identitySetOf(hiddenItems)
        val duplicateItems = modApps
            .filter { it !in hiddenItemSet }
            .groupBy { it.appKey() }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                val keep = group.firstOrNull { !it.isAutoAdded() } ?: group.minWithOrNull(byPosition)
                group.filter { it !== keep && it.isAutoAdded() }
            }
        val removedItems = hiddenItems + duplicateItems
        val removedItemSet = identitySetOf(removedItems)
        val keptApps = modApps.filter { it !in removedItemSet }

        val modFolderIds = fixedItems.mapNotNullTo(HashSet()) { it.getFieldSilently("id") as? Int }
        val presentApps = (if (sorted) {
            keptApps + items.filter { it.getFieldSilently("container") in modFolderIds }
        } else {
            items
        }).mapNotNullTo(HashSet()) { item ->
            item.takeIf { it.getFieldSilently("itemType") == ITEM_TYPE_APPLICATION }?.appKey()
        }

        val newItems = (allAppsList.getFieldSilently("data") as? List<*>).orEmpty()
            .filterNotNull()
            .filter { app ->
                val key = app.appKey() ?: return@filter false
                key !in presentApps &&
                        app.packageName() !in blockList &&
                        !isPrivateProfile(app.getFieldSilently("user") as? UserHandle)
            }
            .distinctBy { it.appKey() }
            .sortedWith(byTitle)
            .mapNotNull { app ->
                val item = app.callMethodSilently("makeWorkspaceItem", context)
                    ?: app.callMethodSilently("makeWorkspaceItem")
                    ?: return@mapNotNull null
                val intent = item.getFieldSilently("intent") as? Intent ?: return@mapNotNull null
                item.apply {
                    setField("intent", Intent(intent).putExtra(AUTO_ADDED_EXTRA, true))
                    setField("container", CONTAINER_DESKTOP)
                }
            }

        val idp = invariantDeviceProfileClass
            .getStaticFieldSilently("INSTANCE")
            .callMethodSilently("get", context)
        val columns = idp.getFieldSilently("numColumns") as? Int ?: return
        val rows = idp.getFieldSilently("numRows") as? Int ?: return

        val grids = HashMap<Int, Array<BooleanArray>>()

        fun gridFor(screenId: Int) = grids.getOrPut(screenId) { Array(rows) { BooleanArray(columns) } }

        fun occupy(item: Any) {
            val grid = gridFor(item.intField("screenId"))
            val cellX = item.intField("cellX")
            val cellY = item.intField("cellY")

            for (y in cellY until min(rows, cellY + item.intField("spanY", 1))) {
                for (x in cellX until min(columns, cellX + item.intField("spanX", 1))) {
                    if (y >= 0 && x >= 0) grid[y][x] = true
                }
            }
        }

        fixedItems.forEach { occupy(it) }

        val screens = modItems.mapTo(sortedSetOf()) { it.intField("screenId") }.toMutableList()
        if (screens.isEmpty()) screens.add(firstModScreen)
        var nextScreenId = maxOf(
            desktopItems.maxOfOrNull { it.intField("screenId") } ?: 0,
            screens.last()
        ) + 1
        var screenIndex = 0

        fun nextFreeCell(): Triple<Int, Int, Int> {
            while (true) {
                if (screenIndex >= screens.size) screens.add(nextScreenId++)

                val screenId = screens[screenIndex]
                val grid = gridFor(screenId)

                for (y in 0 until rows) {
                    for (x in 0 until columns) {
                        if (!grid[y][x]) {
                            grid[y][x] = true
                            return Triple(screenId, x, y)
                        }
                    }
                }

                screenIndex++
            }
        }

        val assignments = IdentityHashMap<Any, Triple<Int, Int, Int>>()

        when {
            autoFill -> {
                val order = if (sorted) {
                    (keptApps + newItems).sortedWith(byTitle)
                } else {
                    keptApps.sortedWith(byPosition) + newItems
                }
                order.forEach { assignments[it] = nextFreeCell() }
            }

            sorted -> {
                keptApps.forEach { occupy(it) }
                val cells = keptApps
                    .map { Triple(it.intField("screenId"), it.intField("cellX"), it.intField("cellY")) }
                    .toMutableList()
                newItems.forEach { cells.add(nextFreeCell()) }
                val orderedCells = cells.sortedWith(compareBy({ it.first }, { it.third }, { it.second }))
                (keptApps + newItems).sortedWith(byTitle).forEachIndexed { index, item ->
                    assignments[item] = orderedCells[index]
                }
            }

            else -> {
                keptApps.forEach { occupy(it) }
                newItems.forEach { assignments[it] = nextFreeCell() }
            }
        }

        val newItemSet = identitySetOf(newItems)
        val movedItems = ArrayList<Any>()

        assignments.forEach { (item, target) ->
            val (screenId, cellX, cellY) = target
            val isNew = item in newItemSet

            if (!isNew &&
                item.intField("screenId") == screenId &&
                item.intField("cellX") == cellX &&
                item.intField("cellY") == cellY
            ) return@forEach

            item.setField("screenId", screenId)
            item.setField("cellX", cellX)
            item.setField("cellY", cellY)
            if (!isNew) movedItems.add(item)
        }

        if (Xprefs.getInt(NO_DRAWER_FIRST_SCREEN, -1) != firstModScreen) saveFirstModScreen(firstModScreen)

        if (removedItems.isEmpty() && movedItems.isEmpty() && newItems.isEmpty()) return

        runCatching { applyChanges(controller, newItems, movedItems, assignments, removedItems) }
            .onFailure { log(this@AppDrawerMode, it) }
    }

    private fun applyChanges(
        controller: Any,
        added: List<Any>,
        moved: List<Any>,
        targets: Map<Any, Triple<Int, Int, Int>>,
        removed: List<Any>
    ) {
        val modelWriter = controller.modelWriter()
        val bulkAdd = modelWriter.javaClass.methods.any { it.name == "addItemsToDatabase" }

        fun writeTo(target: Any) {
            if (added.isNotEmpty()) {
                if (bulkAdd) {
                    target.callMethod("addItemsToDatabase", ArrayList(added))
                } else {
                    added.forEach { item ->
                        target.callMethod(
                            "addItemToDatabase",
                            item,
                            CONTAINER_DESKTOP,
                            item.intField("screenId"),
                            item.intField("cellX"),
                            item.intField("cellY")
                        )
                    }
                }
            }

            moved.forEach { item ->
                val (screenId, cellX, cellY) = targets[item] ?: return@forEach
                target.callMethod(
                    "modifyItemInDatabase",
                    item,
                    CONTAINER_DESKTOP,
                    screenId,
                    cellX,
                    cellY,
                    item.intField("spanX", 1),
                    item.intField("spanY", 1)
                )
            }
        }

        val execute = modelWriter.transactionExecuteMethod()

        if (execute != null) {
            val transactionType = execute.parameterTypes[0]
            val transaction = Proxy.newProxyInstance(
                transactionType.classLoader,
                arrayOf(transactionType)
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "PixelLauncherEnhancedWorkspaceUpdate"
                    else -> {
                        val context = args?.getOrNull(0)
                        if (context != null) {
                            inWorkspaceSync.set(true)
                            try {
                                writeTo(context)
                                if (removed.isNotEmpty()) {
                                    context.callMethod("deleteItemsFromDatabase", DELETE_REASON, ArrayList(removed))
                                    reloadModel()
                                }
                            } finally {
                                inWorkspaceSync.remove()
                            }
                        }
                        null
                    }
                }
            }

            execute.invoke(modelWriter, transaction)
            if (removed.isNotEmpty()) return
        } else {
            writeTo(modelWriter)

            if (removed.isNotEmpty()) {
                deleteItems(modelWriter, removed)
                return
            }

            if (!bulkAdd) {
                reloadModel()
                return
            }
        }

        scheduleCallbacks(controller) { callbacks ->
            if (added.isNotEmpty()) {
                callbacks.callMethodSilently("bindItemsAdded", ArrayList(added))
            }
            if (moved.isNotEmpty()) {
                val bound = callbacks.javaClass.methods.any { it.name == "bindItemsUpdated" } &&
                        runCatching { callbacks.callMethod("bindItemsUpdated", identitySetOf(moved)) }.isSuccess
                if (!bound) reloadModel()
            }
        }
    }

    private fun Any.legacyDeleteMethod(): Method? {
        return javaClass.methods.firstOrNull { method ->
            method.name == "deleteItemsFromDatabase" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes.any { it == String::class.java } &&
                    method.parameterTypes.any { Collection::class.java.isAssignableFrom(it) }
        }
    }

    private fun Any.transactionExecuteMethod(): Method? {
        if (legacyDeleteMethod() != null) return null

        return javaClass.methods.firstOrNull { method ->
            method.name == "execute" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isInterface
        }
    }

    private fun scheduleResortIfManaged(args: Array<Any?>) {
        val touchesManagedItem = args.any { arg ->
            when (arg) {
                is Collection<*> -> arg.any { it?.isManagedPosition() == true }
                null -> false
                else -> arg.isManagedPosition()
            }
        }
        if (touchesManagedItem) scheduleResort()
    }

    private fun scheduleResort() {
        if (!noDrawerMode || inWorkspaceSync.get() == true) return
        if (arrangement == ARRANGEMENT_CUSTOM && !autoFill) return

        mainHandler.removeCallbacks(resortRunnable)
        mainHandler.postDelayed(resortRunnable, RESORT_DELAY_MS)
    }

    private fun removeAutoAddedItems(controller: Any, items: List<Any>) {
        val firstModScreen = storedFirstModScreen() ?: return

        val removed = items.filter { item ->
            item.isAutoAdded() &&
                    item.getFieldSilently("container") == CONTAINER_DESKTOP &&
                    item.intField("screenId") >= firstModScreen
        }

        if (removed.isEmpty()) {
            saveFirstModScreen(null)
            return
        }

        runCatching { deleteItems(controller.modelWriter(), removed) }
            .onSuccess { saveFirstModScreen(null) }
            .onFailure { log(this@AppDrawerMode, it) }
    }

    private fun deleteItems(modelWriter: Any, items: List<Any>) {
        val legacyDelete = modelWriter.legacyDeleteMethod()

        if (legacyDelete != null) {
            legacyDelete.invoke(
                modelWriter,
                *legacyDelete.parameterTypes.map { if (it == String::class.java) DELETE_REASON else ArrayList(items) }
                    .toTypedArray()
            )
            reloadModel()
            return
        }

        val execute = modelWriter.javaClass.methods.first { method ->
            method.name == "execute" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isInterface
        }
        val transactionType = execute.parameterTypes[0]
        val transaction = Proxy.newProxyInstance(
            transactionType.classLoader,
            arrayOf(transactionType)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PixelLauncherEnhancedWorkspaceDelete"
                else -> {
                    args?.getOrNull(0).callMethod("deleteItemsFromDatabase", DELETE_REASON, ArrayList(items))
                    reloadModel()
                    null
                }
            }
        }

        execute.invoke(modelWriter, transaction)
    }

    private fun scheduleCallbacks(controller: Any, action: (Any) -> Unit) {
        if (controller is LegacyTaskController) {
            (controller.model.callMethodSilently("getCallbacks") as? Array<*>).orEmpty()
                .filterNotNull()
                .forEach { callbacks ->
                    controller.uiExecutor.execute {
                        runCatching { action(callbacks) }.onFailure { log(this@AppDrawerMode, it) }
                    }
                }
            return
        }

        val taskClass = callbackTaskClass
        if (taskClass == null || !controller.hasMethod("scheduleCallbackTask")) {
            reloadModel()
            return
        }

        val task = Proxy.newProxyInstance(taskClass.classLoader, arrayOf(taskClass)) { proxy, method, args ->
            when (method.name) {
                "execute" -> {
                    args?.getOrNull(0)?.let { callbacks ->
                        runCatching { action(callbacks) }.onFailure { log(this@AppDrawerMode, it) }
                    }
                    null
                }

                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PixelLauncherEnhancedWorkspaceBind"
                else -> null
            }
        }

        controller.callMethodSilently("scheduleCallbackTask", task)
    }

    private fun Any?.labelResId(): Int? {
        return getFieldSilently("labelResId") as? Int
            ?: getFieldSilently("id") as? Int
            ?: getFieldSilently("label").getFieldSilently("resId") as? Int
    }

    private fun Any.taskContext(): Context {
        if (this is LegacyTaskController) return context
        return getFieldSilently("context") as? Context ?: mContext
    }

    private fun Any.modelWriter(): Any {
        if (this !is LegacyTaskController) return callMethod("getModelWriter")!!

        val getWriter = model.javaClass.methods
            .filter { it.name == "getWriter" }
            .maxByOrNull { it.parameterTypes.size }!!
        val args = getWriter.parameterTypes.map { type ->
            when {
                type == Boolean::class.javaPrimitiveType -> false
                type.simpleName == "CellPosMapper" -> type.getStaticFieldSilently("DEFAULT")
                else -> null
            }
        }
        return getWriter.invoke(model, *args.toTypedArray())!!
    }

    private class LegacyTaskController(
        val context: Context,
        val model: Any,
        val uiExecutor: Executor
    )

    private fun reloadModel() {
        Handler(Looper.getMainLooper()).post {
            val model = launcherModelRef?.get()
            if (model == null) {
                LauncherUtils.restartLauncher(mContext)
                return@post
            }

            runCatching { model.callMethod("forceReload") }
                .recoverCatching { model.callMethod("forceReload", DELETE_REASON) }
                .onFailure {
                    log(this@AppDrawerMode, it)
                    LauncherUtils.restartLauncher(mContext)
                }
        }
    }

    private fun isPrivateProfile(user: UserHandle?): Boolean {
        if (user == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false

        return privateProfiles.getOrPut(user) {
            runCatching {
                mContext.getSystemService(LauncherApps::class.java)
                    .getLauncherUserInfo(user)
                    ?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
            }.getOrDefault(false)
        }
    }

    private fun storedFirstModScreen(): Int? {
        Xprefs.getInt(NO_DRAWER_FIRST_SCREEN, -1).takeIf { it > 0 }?.let { return it }

        return Xprefs.getStringSet(NO_DRAWER_AUTO_SCREENS, emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
            .minOrNull()
    }

    private fun saveFirstModScreen(screenId: Int?) {
        Xprefs.edit().apply {
            if (screenId == null) remove(NO_DRAWER_FIRST_SCREEN) else putInt(NO_DRAWER_FIRST_SCREEN, screenId)
            remove(NO_DRAWER_AUTO_SCREENS)
        }.apply()
    }

    private fun Any.isManagedPosition(): Boolean = isAutoAdded() || isOnModPage()

    private fun identitySetOf(items: List<Any>): MutableSet<Any> {
        return Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(items) }
    }

    private fun Any.packageName(): String? {
        return (callMethodSilently("getTargetComponent") as? ComponentName)?.packageName
    }

    private fun Any.appKey(): String? {
        val component = callMethodSilently("getTargetComponent") as? ComponentName ?: return null
        val user = getFieldSilently("user") as? UserHandle ?: return null
        return "${component.flattenToShortString()}#${user.hashCode()}"
    }

    private fun Any?.isProtectedFromRemoval(): Boolean {
        val item = this ?: return false

        return when (item.getFieldSilently("itemType")) {
            ITEM_TYPE_APPLICATION -> item.isOnModPage() || !item.hasOtherIcon(excludedContainer = null)
            ITEM_TYPE_FOLDER -> {
                val folderId = item.getFieldSilently("id") as? Int
                val contents = item.callMethodSilently("getContents") as? Collection<*>
                    ?: item.getFieldSilently("contents") as? Collection<*>
                    ?: return true

                contents.filterNotNull().any { child ->
                    child.getFieldSilently("itemType") == ITEM_TYPE_APPLICATION &&
                            !child.hasOtherIcon(excludedContainer = folderId)
                }
            }

            else -> false
        }
    }

    private fun Any.isOnModPage(): Boolean {
        val firstModScreen = storedFirstModScreen() ?: return false
        return getFieldSilently("container") == CONTAINER_DESKTOP &&
                intField("screenId") >= firstModScreen
    }

    private fun Any.hasOtherIcon(excludedContainer: Int?): Boolean {
        val key = appKey() ?: return false
        val items = launcherModelRef?.get()
            .getFieldSilently("mBgDataModel")
            .getFieldSilently("itemsIdMap") as? Iterable<*>
            ?: return false

        return items.any { other ->
            other != null &&
                    other !== this &&
                    other.getFieldSilently("itemType") == ITEM_TYPE_APPLICATION &&
                    (excludedContainer == null || other.getFieldSilently("container") != excludedContainer) &&
                    other.appKey() == key
        }
    }

    private fun Any.isAutoAdded(): Boolean {
        return (getFieldSilently("intent") as? Intent)?.getBooleanExtra(AUTO_ADDED_EXTRA, false) == true
    }

    private fun Any.intField(name: String, default: Int = 0): Int {
        return getFieldSilently(name) as? Int ?: default
    }

    companion object {
        private const val SWIPE_UP_NOTHING = 0
        private const val SWIPE_UP_SEARCH = 1

        private const val ARRANGEMENT_SORTED = 0
        private const val ARRANGEMENT_CUSTOM = 1
        private const val RESORT_DELAY_MS = 600L

        private const val ITEM_TYPE_APPLICATION = 0
        private const val ITEM_TYPE_FOLDER = 2
        private const val CONTAINER_DESKTOP = -100

        private const val AUTO_ADDED_EXTRA = "plenhanced_auto"
        private const val DELETE_REASON = "PLEnhanced: app drawer re-enabled"
        private const val ORIGINAL_FILTER_KEY = "plenhanced_original_item_filter"
        private val NO_FILTER = Any()
    }
}
