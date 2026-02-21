package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_AT_A_GLANCE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Modifier

class SmartSpace(context: Context) : ModPack(context) {

    private var hideQuickspace = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideQuickspace = getBoolean(HIDE_AT_A_GLANCE, false)
        }

        when (key.firstOrNull()) {
            HIDE_AT_A_GLANCE -> restartLauncher(mContext)
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val nexusLauncherActivityClass = findClass(
            "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
            suppressError = true
        )

        nexusLauncherActivityClass
            .hookMethod("setupViews")
            .suppressError()
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                // Fields are obfuscated :)
                nexusLauncherActivityClass!!.declaredFields.forEach { field ->
                    val fieldValue = param.thisObject.getFieldSilently(field.name)
                    val isFinal = Modifier.isFinal(field.modifiers)

                    if (fieldValue is Boolean && isFinal && fieldValue) {
                        param.thisObject.setField(field.name, false)
                    }
                }
            }

        val launcherAppStateClass = findClass("com.android.launcher3.LauncherAppState")
        val launcherPrefsClass = findClass("com.android.launcher3.LauncherPrefs")
        val launcherPrefsCompanionClass = findClass(
            $$"com.android.launcher3.LauncherPrefs$Companion",
            suppressError = true
        )
        var quickspaceListenerRegistered = false

        launcherAppStateClass
            .hookConstructor()
            .runAfter { param ->
                if (!hideQuickspace || quickspaceListenerRegistered) return@runAfter

                val context = param.thisObject.getAnyField("mContext", "context") as Context
                val mModel = param.thisObject.getAnyField("mModel", "model")

                // Doesn't exist in Android 16 beta 4+
                val mOnTerminateCallback = param.thisObject.getFieldSilently("mOnTerminateCallback")

                val firstPagePinnedItemListener =
                    SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                        if (SMARTSPACE_ON_HOME_SCREEN == key) {
                            mModel.callMethod("forceReload")
                        }
                    }

                val launcherPrefs = try {
                    launcherPrefsClass.callStaticMethod("getPrefs", context)
                } catch (_: Throwable) {
                    launcherPrefsCompanionClass.callStaticMethod("getPrefs", context)
                }

                launcherPrefs.callMethod(
                    "registerOnSharedPreferenceChangeListener",
                    firstPagePinnedItemListener
                )
                quickspaceListenerRegistered = true

                mOnTerminateCallback.callMethod(
                    "add",
                    Runnable {
                        launcherPrefs.callMethod(
                            "unregisterOnSharedPreferenceChangeListener",
                            firstPagePinnedItemListener
                        )
                        quickspaceListenerRegistered = false
                    }
                )
            }

        val modelCallbacksClass = findClass(
            "com.android.launcher3.ModelCallbacks",
            suppressError = true
        )

        modelCallbacksClass
            .hookConstructor()
            .runAfter { param ->
                if (!hideQuickspace) return@runAfter

                param.thisObject.setFieldSilently("isFirstPagePinnedItemEnabled", false)
            }

        modelCallbacksClass
            .hookMethod("setIsFirstPagePinnedItemEnabled")
            .suppressError()
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                param.args[0] = false
            }

        modelCallbacksClass
            .hookMethod("getIsFirstPagePinnedItemEnabled")
            .suppressError()
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                param.result = false
            }

        val workspaceClass = findClass("com.android.launcher3.Workspace")

        workspaceClass
            .hookMethod("insertNewWorkspaceScreen")
            .parameters(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                val screenId = param.args[0] as Int
                val mWorkspaceScreens = param.thisObject.getField("mWorkspaceScreens")

                if (mWorkspaceScreens.callMethod("containsKey", screenId) as Boolean) {
                    param.result = null
                }
            }

        workspaceClass
            .hookMethod("bindAndInitFirstWorkspaceScreen")
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                val mWorkspaceScreens = param.thisObject.getField("mWorkspaceScreens")

                if (!(mWorkspaceScreens.callMethod("containsKey", 0) as Boolean)) {
                    val childCount = param.thisObject.callMethod("getChildCount") as Int
                    param.thisObject.callMethod("insertNewWorkspaceScreen", 0, childCount)
                }

                param.thisObject.setField("mFirstPagePinnedItem", null)
                param.result = null
            }

        val utilitiesClass = findClass("com.android.launcher3.Utilities")

        utilitiesClass
            .hookMethod("showQuickspace")
            .suppressError()
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                param.result = false
            }

        val gridSizeMigrationDBControllerClass = findClass(
            "com.android.launcher3.model.GridSizeMigrationDBController",
            "com.android.launcher3.model.GridSizeMigrationUtil"
        )
        val gridOccupancyClass = findClass("com.android.launcher3.util.GridOccupancy")!!

        gridSizeMigrationDBControllerClass
            .hookMethod("solveGridPlacement")
            .suppressError()
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                val helper = param.args[0]
                val srcReader = param.args[1]
                val destReader = param.args[2]
                val screenId = param.args[3] as Int
                val trgX = param.args[4] as Int
                val trgY = param.args[5] as Int
                val sortedItemsToPlace = param.args[6] as List<*>
                val idsInUse = try {
                    param.args[6] as List<*>
                } catch (_: Throwable) {
                    null
                }

                val occupied = gridOccupancyClass
                    .getDeclaredConstructor(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .newInstance(trgX, trgY)
                val trg = Point(trgX, trgY)
                val next = Point(0, 0)

                val existedEntries = if (destReader.hasMethod("mWorkspaceEntriesByScreenId")) {
                    destReader.callMethod("mWorkspaceEntriesByScreenId")
                } else {
                    destReader.getField("mWorkspaceEntriesByScreenId")
                }.callMethod("get", screenId) as? List<*>

                if (existedEntries != null) {
                    for (dbEntry in existedEntries) {
                        if (gridOccupancyClass.hasMethod("markCells", dbEntry!!::class.java)) {
                            occupied.callMethod("markCells", dbEntry)
                        } else if (gridOccupancyClass.hasMethod(
                                "markCells",
                                dbEntry::class.java,
                                Boolean::class.java
                            )
                        ) {
                            occupied.callMethod("markCells", dbEntry, true)
                        } else {
                            occupied.callMethod(
                                "markCells",
                                true,
                                dbEntry.getField("cellX"),
                                dbEntry.getField("cellY"),
                                dbEntry.getField("spanX"),
                                dbEntry.getField("spanY")
                            )
                        }
                    }
                }

                val iterator = sortedItemsToPlace.iterator()

                while (iterator.hasNext()) {
                    val entry = iterator.next()

                    if (entry.getField("minSpanX") as Int > trgX || entry.getField("minSpanY") as Int > trgY) {
                        iterator.callMethod("remove")
                        continue
                    }

                    for (y in next.y until trg.y) {
                        for (x in next.x until trg.x) {
                            val fits = occupied.callMethod(
                                "isRegionVacant",
                                x,
                                y,
                                entry.getField("spanX"),
                                entry.getField("spanY")
                            ) as Boolean
                            val minFits = occupied.callMethod(
                                "isRegionVacant",
                                x,
                                y,
                                entry.getField("minSpanX"),
                                entry.getField("minSpanY")
                            ) as Boolean

                            if (minFits) {
                                entry.setField("spanX", entry.getField("minSpanX"))
                                entry.setField("spanY", entry.getField("minSpanY"))
                            }

                            if (fits || minFits) {
                                entry.setField("screenId", screenId)
                                entry.setField("cellX", x)
                                entry.setField("cellY", y)

                                if (gridOccupancyClass.hasMethod("markCells", entry!!::class.java)) {
                                    occupied.callMethod("markCells", entry)
                                } else if (gridOccupancyClass.hasMethod(
                                        "markCells",
                                        entry::class.java,
                                        Boolean::class.java
                                    )
                                ) {
                                    occupied.callMethod("markCells", entry, true)
                                } else {
                                    occupied.callMethod(
                                        "markCells",
                                        true,
                                        entry.getField("cellX"),
                                        entry.getField("cellY"),
                                        entry.getField("spanX"),
                                        entry.getField("spanY")
                                    )
                                }

                                next.set(x + entry.getField("spanX") as Int, y)

                                if (idsInUse != null) {
                                    param.thisObject.callMethod(
                                        "insertEntryInDb",
                                        helper,
                                        entry,
                                        srcReader.getField("mTableName"),
                                        destReader.getField("mTableName"),
                                        idsInUse
                                    )
                                } else {
                                    param.thisObject.callMethod(
                                        "insertEntryInDb",
                                        helper,
                                        entry,
                                        srcReader.getField("mTableName"),
                                        destReader.getField("mTableName")
                                    )
                                }

                                iterator.callMethod("remove")
                                break
                            }
                        }

                        next.set(0, next.y)
                    }
                }

                param.result = null
            }

        val gridSizeMigrationLogicClass = findClass(
            "com.android.launcher3.model.GridSizeMigrationLogic",
            suppressError = true
        )
        val workspaceItemsToPlaceClass = findClass(
            $$"com.android.launcher3.model.GridSizeMigrationLogic$WorkspaceItemsToPlace",
            suppressError = true
        )
        val cellAndSpanClass = findClass("com.android.launcher3.util.CellAndSpan")

        gridSizeMigrationLogicClass
            .hookMethod("solveGridPlacement")
            .suppressError()
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                val screenId = param.args[0] as Int
                val trgX = param.args[1] as Int
                val trgY = param.args[2] as Int
                val sortedItemsToPlace = param.args[3] as List<*>
                val existedEntries = param.args[4] as? List<*>

                var cellAndSpan: Any? = null
                val workspaceItemsToPlace = workspaceItemsToPlaceClass!!
                    .getDeclaredConstructor(
                        sortedItemsToPlace::class.java,
                        sortedItemsToPlace::class.java
                    )
                    .newInstance(sortedItemsToPlace, ArrayList<Any>())
                val occupied = gridOccupancyClass
                    .getDeclaredConstructor(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .newInstance(trgX, trgY)

                val trg = Point(trgX, trgY)
                val next = Point(0, 0)

                if (existedEntries != null) {
                    val iterator = existedEntries.iterator()

                    while (iterator.hasNext()) {
                        val dbEntry = iterator.next()

                        if (gridOccupancyClass.hasMethod("markCells", dbEntry!!::class.java)) {
                            occupied.callMethod("markCells", dbEntry)
                        } else if (gridOccupancyClass.hasMethod(
                                "markCells",
                                dbEntry::class.java,
                                Boolean::class.java
                            )
                        ) {
                            occupied.callMethod("markCells", dbEntry, true)
                        } else {
                            occupied.callMethod(
                                "markCells",
                                true,
                                dbEntry.getField("cellX"),
                                dbEntry.getField("cellY"),
                                dbEntry.getField("spanX"),
                                dbEntry.getField("spanY")
                            )
                        }
                    }
                }

                val iterator = if (workspaceItemsToPlace.hasMethod("getMRemainingItemsToPlace")) {
                    workspaceItemsToPlace.callMethod("getMRemainingItemsToPlace")
                } else {
                    workspaceItemsToPlace.getField("mRemainingItemsToPlace")
                }.callMethod("iterator") as Iterator<*>

                while (iterator.hasNext()) {
                    val dbEntry = iterator.next()

                    if (dbEntry.getField("minSpanX") as Int > trgX || dbEntry.getField("minSpanY") as Int > trgY) {
                        iterator.callMethod("remove")
                        continue
                    }

                    var x = next.x
                    var y = next.y
                    val gridHeight = trg.y

                    while (true) {
                        if (y >= gridHeight) {
                            cellAndSpan = null
                            break
                        }

                        val gridWidth = trg.x

                        while (x < gridWidth) {
                            if (occupied.callMethod(
                                    "isRegionVacant",
                                    x,
                                    y,
                                    dbEntry.getField("minSpanX"),
                                    dbEntry.getField("minSpanY")
                                ) as Boolean
                            ) {
                                cellAndSpan = cellAndSpanClass!!
                                    .getDeclaredConstructor(
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType
                                    )
                                    .newInstance(
                                        x,
                                        y,
                                        dbEntry.getField("minSpanX"),
                                        dbEntry.getField("minSpanY")
                                    )
                                break
                            }

                            x++
                        }

                        y++
                        x = 0
                    }

                    cellAndSpan?.let {
                        dbEntry.setField("screenId", screenId)
                        dbEntry.setField("cellX", it.getField("cellX"))
                        dbEntry.setField("cellY", it.getField("cellY"))
                        dbEntry.setField("spanX", it.getField("spanX"))
                        dbEntry.setField("spanY", it.getField("spanY"))

                        if (gridOccupancyClass.hasMethod("markCells", dbEntry!!::class.java)) {
                            occupied.callMethod("markCells", dbEntry)
                        } else if (gridOccupancyClass.hasMethod(
                                "markCells",
                                dbEntry::class.java,
                                Boolean::class.java
                            )
                        ) {
                            occupied.callMethod("markCells", dbEntry, true)
                        } else {
                            occupied.callMethod(
                                "markCells",
                                true,
                                it.getField("cellX"),
                                it.getField("cellY"),
                                it.getField("spanX"),
                                it.getField("spanY")
                            )
                        }

                        next.set(
                            dbEntry.getField("cellX") as Int + dbEntry.getField("spanX") as Int,
                            dbEntry.getField("cellY") as Int
                        )

                        if (workspaceItemsToPlace.hasMethod("getMPlacementSolution")) {
                            workspaceItemsToPlace.callMethod("getMPlacementSolution")
                        } else {
                            workspaceItemsToPlace.getField("mPlacementSolution")
                        }.callMethod("add", dbEntry)

                        iterator.callMethod("remove")
                    }
                }

                param.result = workspaceItemsToPlace
            }

        val loaderCursorClass = findClass("com.android.launcher3.model.LoaderCursor")

        loaderCursorClass
            .hookMethod("checkAndAddItem")
            .runBefore { param ->
                if (!hideQuickspace) return@runBefore

                val dataModel = param.args[1]
                dataModel.setFieldSilently("isFirstPagePinnedItemEnabled", false)
            }

        val loaderTask = findClass("com.android.launcher3.model.LoaderTask")

        loaderTask
            .hookMethod("loadWorkspace", "loadWorkspaceImpl")
            .runAfter { param ->
                if (!hideQuickspace) return@runAfter

                val mBgDataModel = param.thisObject.getField("mBgDataModel")
                mBgDataModel.setFieldSilently("isFirstPagePinnedItemEnabled", false)
            }
    }

    companion object {
        private const val SMARTSPACE_ON_HOME_SCREEN = "pref_smartspace_home_screen"
    }
}