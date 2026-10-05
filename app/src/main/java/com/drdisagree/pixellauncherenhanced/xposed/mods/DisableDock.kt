package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_DOCK
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlin.math.min

class DisableDock(context: Context) : ModPack(context) {

    private var dockDisabled = false
    private var launcherModelRef: WeakReference<Any>? = null
    private var modelUpdateTaskClass: Class<*>? = null
    private var invariantDeviceProfileClass: Class<*>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            dockDisabled = getBoolean(DISABLE_DOCK, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherModelClass = findClass("com.android.launcher3.LauncherModel", suppressError = true) ?: return
        modelUpdateTaskClass = findClass(
            $$"com.android.launcher3.LauncherModel$ModelUpdateTask",
            suppressError = true
        )?.takeIf { it.isInterface }
        invariantDeviceProfileClass = findClass(
            "com.android.launcher3.InvariantDeviceProfile",
            suppressError = true
        )

        launcherModelClass
            .hookMethod("enqueueModelUpdateTask")
            .runBefore { param -> rememberModel(param.thisObject) }

        findClass("com.android.launcher3.model.LoaderTask", suppressError = true)
            .hookMethod("run")
            .suppressError()
            .runAfter { param ->
                rememberModel(
                    param.thisObject.getFieldSilently("mModel")
                        ?: param.thisObject.getFieldSilently("mApp").callMethodSilently("getModel")
                )
                if (dockDisabled) enqueueDockCleanup()
            }

        findClass("com.android.launcher3.Hotseat", suppressError = true)
            .hookMethod("isValidDropTarget")
            .suppressError()
            .runBefore { param -> if (dockDisabled) param.result = false }

        findClass("com.android.launcher3.Workspace", suppressError = true)
            .hookMethod("shouldUseHotseatAsDropLayout")
            .suppressError()
            .runBefore { param -> if (dockDisabled) param.result = false }

        listOf(
            "com.android.launcher3.util.HybridHotseatOrganizer",
            "com.android.launcher3.hybridhotseat.HotseatPredictionController"
        ).forEach { className ->
            findClass(className, suppressError = true)
                .hookMethod("fillGapsWithPrediction")
                .suppressError()
                .runBefore { param -> if (dockDisabled) param.result = null }
        }
    }

    private fun rememberModel(model: Any?) {
        if (model != null && launcherModelRef?.get() !== model) {
            launcherModelRef = WeakReference(model)
        }
    }

    private fun enqueueDockCleanup() {
        val model = launcherModelRef?.get() ?: return
        val taskClass = modelUpdateTaskClass ?: return
        var legacyArgs: Array<Any?>? = null

        val task = Proxy.newProxyInstance(taskClass.classLoader, arrayOf(taskClass)) { proxy, method, args ->
            when (method.name) {
                "execute" -> {
                    runCatching {
                        moveDockItems(
                            context = args[0].getFieldSilently("context") as? Context ?: mContext,
                            writer = { args[0].callMethod("getModelWriter")!! },
                            dataModel = args[1]
                        )
                    }.onFailure { log(this@DisableDock, it) }
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
                            moveDockItems(
                                context = initArgs[0].callMethodSilently("getContext") as? Context ?: mContext,
                                writer = { legacyWriter(initArgs[1] ?: model) },
                                dataModel = initArgs[2]!!
                            )
                        }.onFailure { log(this@DisableDock, it) }
                    }
                    null
                }

                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PixelLauncherEnhancedDockCleanup"
                else -> null
            }
        }

        model.callMethod("enqueueModelUpdateTask", task)
    }

    private fun moveDockItems(context: Context, writer: () -> Any, dataModel: Any) {
        val moved = synchronized(dataModel) {
            val items = (dataModel.getFieldSilently("itemsIdMap") as? Iterable<*>)
                ?.filterNotNull()
                ?: return

            val dockItems = items
                .filter { it.getFieldSilently("container") == CONTAINER_HOTSEAT }
                .sortedBy { it.intField("screenId") }
            if (dockItems.isEmpty()) return

            val idp = invariantDeviceProfileClass
                .getStaticFieldSilently("INSTANCE")
                .callMethodSilently("get", context)
            val columns = idp.getFieldSilently("numColumns") as? Int ?: return
            val rows = idp.getFieldSilently("numRows") as? Int ?: return

            val desktopItems = items.filter { it.getFieldSilently("container") == CONTAINER_DESKTOP }
            val grids = HashMap<Int, Array<BooleanArray>>()

            fun gridFor(screenId: Int) = grids.getOrPut(screenId) {
                Array(rows) { BooleanArray(columns) }.also { grid ->
                    if (screenId == FIRST_SCREEN_ID) grid[0].fill(true)
                }
            }

            desktopItems.forEach { item ->
                val grid = gridFor(item.intField("screenId"))
                val cellX = item.intField("cellX")
                val cellY = item.intField("cellY")

                for (y in cellY until min(rows, cellY + item.intField("spanY", 1))) {
                    for (x in cellX until min(columns, cellX + item.intField("spanX", 1))) {
                        if (x >= 0 && y >= 0) grid[y][x] = true
                    }
                }
            }

            val screens = (desktopItems.map { it.intField("screenId") } + FIRST_SCREEN_ID)
                .toSortedSet()
                .toMutableList()
            var screenIndex = 0

            fun nextFreeCell(): Triple<Int, Int, Int> {
                while (true) {
                    if (screenIndex >= screens.size) screens.add(screens.last() + 1)

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

            dockItems.onEach { item ->
                val (screenId, cellX, cellY) = nextFreeCell()
                item.setField("container", CONTAINER_DESKTOP)
                item.setField("screenId", screenId)
                item.setField("cellX", cellX)
                item.setField("cellY", cellY)
                item.setField("spanX", 1)
                item.setField("spanY", 1)
            }
        }

        writeMoves(writer(), moved)
        reloadModel()
    }

    private fun writeMoves(modelWriter: Any, items: List<Any>) {
        fun modify(target: Any) {
            items.forEach { item ->
                target.callMethod(
                    "modifyItemInDatabase",
                    item,
                    CONTAINER_DESKTOP,
                    item.intField("screenId"),
                    item.intField("cellX"),
                    item.intField("cellY"),
                    1,
                    1
                )
            }
        }

        if (modelWriter.javaClass.methods.any { it.name == "modifyItemInDatabase" }) {
            modify(modelWriter)
            return
        }

        val execute = modelWriter.javaClass.methods.first { method ->
            method.name == "execute" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isInterface
        }
        val transactionType = execute.parameterTypes[0]
        val transaction = Proxy.newProxyInstance(transactionType.classLoader, arrayOf(transactionType)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PixelLauncherEnhancedDockMove"
                else -> {
                    args?.getOrNull(0)?.let { modify(it) }
                    null
                }
            }
        }

        execute.invoke(modelWriter, transaction)
    }

    private fun legacyWriter(model: Any): Any {
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

    private fun reloadModel() {
        mainHandler.post {
            val model = launcherModelRef?.get() ?: return@post
            runCatching { model.callMethod("forceReload") }
                .recoverCatching { model.callMethod("forceReload", "PLEnhanced: dock disabled") }
                .onFailure { log(this@DisableDock, it) }
        }
    }

    private fun Any.intField(name: String, default: Int = 0): Int {
        return getFieldSilently(name) as? Int ?: default
    }

    companion object {
        private const val CONTAINER_DESKTOP = -100
        private const val CONTAINER_HOTSEAT = -101
        private const val FIRST_SCREEN_ID = 0
    }
}
