package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UNHIDE_ALL_APPS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Modifier
import java.util.Arrays

class HideApps(context: Context) : ModPack(context) {

    private var appBlockList: Set<String> = mutableSetOf()
    private var searchHiddenApps: Boolean = false
    private var invariantDeviceProfileInstance: Any? = null

    companion object {
        var SHOULD_UNHIDE_ALL_APPS = false
        private var activityAllAppsContainerViewInstance: Any? = null
        private var hotseatPredictionControllerInstance: Any? = null
        private var hybridHotseatOrganizerClassInstance: Any? = null
        private var predictionRowViewInstance: Any? = null

        fun updateLauncherIcons(context: Context) {
            activityAllAppsContainerViewInstance.callMethod("onAppsUpdated")
            hotseatPredictionControllerInstance.callMethodSilently("fillGapsWithPrediction", true)
            hybridHotseatOrganizerClassInstance.callMethodSilently("fillGapsWithPrediction", true)
            predictionRowViewInstance.callMethod("applyPredictionApps")
            reloadLauncher(context)
        }
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            appBlockList = getStringSet(APP_BLOCK_LIST, emptySet())!!
            searchHiddenApps = getBoolean(SEARCH_HIDDEN_APPS, false)
            SHOULD_UNHIDE_ALL_APPS = getBoolean(UNHIDE_ALL_APPS, false)
        }

        when (key.firstOrNull()) {
            APP_BLOCK_LIST -> updateLauncherIcons(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")
        val activityAllAppsContainerViewClass =
            findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView")
        val hotseatPredictionControllerClass =
            findClass("com.android.launcher3.hybridhotseat.HotseatPredictionController")
        val hybridHotseatOrganizerClass = findClass(
            "com.android.launcher3.util.HybridHotseatOrganizer",
            suppressError = true
        )
        val predictionRowViewClass =
            findClass("com.android.launcher3.appprediction.PredictionRowView")
        val defaultAppSearchAlgorithmClass = findClass(
            "com.android.launcher3.allapps.DefaultAppSearchAlgorithm",
            "com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm"
        )
        val alphabeticalAppsListClass =
            findClass("com.android.launcher3.allapps.AlphabeticalAppsList")
        val allAppsStoreClass = findClass("com.android.launcher3.allapps.AllAppsStore")
        val appInfoClass = findClass("com.android.launcher3.model.data.AppInfo")
        val allAppsListClass = findClass("com.android.launcher3.model.AllAppsList")
        val launcherModelClass = findClass("com.android.launcher3.LauncherModel")

        invariantDeviceProfileClass
            .hookConstructor()
            .runAfter { param -> invariantDeviceProfileInstance = param.thisObject }

        activityAllAppsContainerViewClass
            .hookConstructor()
            .runAfter { param -> activityAllAppsContainerViewInstance = param.thisObject }

        hotseatPredictionControllerClass
            .hookConstructor()
            .runAfter { param -> hotseatPredictionControllerInstance = param.thisObject }

        hybridHotseatOrganizerClass
            .hookConstructor()
            .runAfter { param -> hybridHotseatOrganizerClassInstance = param.thisObject }

        predictionRowViewClass
            .hookConstructor()
            .runAfter { param -> predictionRowViewInstance = param.thisObject }

        allAppsStoreClass
            .hookMethod("setApps")
            .runAfter { param ->
                val apps = param.args[0]

                if (apps != null) {
                    param.thisObject.setExtraField("mAppsBackup", apps)
                }
            }

        @Suppress("UNCHECKED_CAST")
        Arrays::class.java
            .hookMethod("binarySearch")
            .parameters(
                Array<Any?>::class.java,
                Any::class.java,
                Comparator::class.java,
            )
            .runBefore { param ->
                val mApps = param.args[0] as? Array<*> ?: return@runBefore

                if (!mApps
                        .javaClass
                        .componentType
                        .simpleName
                        .equals(appInfoClass!!.simpleName, ignoreCase = true)
                ) return@runBefore

                val appInfo = param.args[1]
                val comparator = param.args[2] as Comparator<Any?>
                val componentName = appInfo.getComponentName()

                val binarySearch = binarySearch(mApps, appInfo, comparator)

                if (binarySearch < 0 || (!searchHiddenApps && componentName.matchesBlocklist())) {
                    param.result = -1
                }
            }

        predictionRowViewClass
            .hookMethod("applyPredictionApps")
            .runBefore { param ->
                val mPredictedApps =
                    (param.thisObject.getField("mPredictedApps") as ArrayList<*>).toMutableList()

                val iterator = mPredictedApps.iterator()
                iterator.removeMatches()

                param.thisObject.setField("mPredictedApps", ArrayList(mPredictedApps))
            }

        if (hotseatPredictionControllerClass.hasMethod("fillGapsWithPrediction")) {
            hotseatPredictionControllerClass
                .hookMethod("fillGapsWithPrediction")
                .parameters(Boolean::class.java)
                .runBefore { param ->
                    val mPredictedItems =
                        (param.thisObject.getField("mPredictedItems") as List<*>).toMutableList()

                    val iterator = mPredictedItems.iterator()
                    iterator.removeMatches()
                }
        } else {
            hybridHotseatOrganizerClass
                .hookMethod("fillGapsWithPrediction")
                .parameters(Boolean::class.java)
                .runBefore { param ->
                    val mPredictedItems =
                        (param.thisObject.getField("predictedItems") as List<*>).toMutableList()

                    val iterator = mPredictedItems.iterator()
                    iterator.removeMatches()
                }
        }

        try {
            defaultAppSearchAlgorithmClass
                .hookMethod("getTitleMatchResult")
                .throwError()
                .runBefore { param ->
                    if (searchHiddenApps) return@runBefore

                    val index = if (param.args[0] is Context) 1 else 0
                    val apps = (param.args[index] as List<*>).toMutableList()

                    val iterator = apps.iterator()
                    iterator.removeMatches()

                    param.args[index] = ArrayList(apps)
                }
        } catch (_: Throwable) {
            // Method seems to be unused on newer versions of pixel launcher
            // But still hook it just in case

            fun removeAppResult(param: XC_MethodHook.MethodHookParam) {
                if (searchHiddenApps) return

                val appsIndex = param.args.indexOfFirst {
                    it::class.java.simpleName == allAppsListClass!!.simpleName
                }

                val apps = param.args[appsIndex]
                val data = apps.getField("data") as ArrayList<*>

                val iterator = data.iterator()
                iterator.removeMatches()

                apps.setField("data", ArrayList(data))
                param.args[appsIndex] = apps
            }

            val methodName = (defaultAppSearchAlgorithmClass!!.declaredMethods.toList()
                .union(defaultAppSearchAlgorithmClass.methods.toList()))
                .firstOrNull { method ->
                    Modifier.isStatic(method.modifiers) &&
                            method.parameterTypes.any { it == String::class.java } &&
                            method.parameterTypes.any { it.simpleName == allAppsListClass!!.simpleName } &&
                            method.parameterCount >= 2
                }?.name

            if (methodName != null) {
                defaultAppSearchAlgorithmClass
                    .hookMethod(methodName)
                    .runBefore { param ->
                        removeAppResult(param)
                    }
            } else {
                val baseModelUpdateTaskClass = findClass(
                    "com.android.launcher3.model.BaseModelUpdateTask",
                    suppressError = Build.VERSION.SDK_INT >= 36
                )

                launcherModelClass
                    .hookMethod("enqueueModelUpdateTask")
                    .runBefore { param ->
                        val modelUpdateTask = param.args[0]

                        if (baseModelUpdateTaskClass != null &&
                            modelUpdateTask::class.java.simpleName != baseModelUpdateTaskClass.simpleName
                        ) return@runBefore

                        modelUpdateTask::class.java
                            .hookMethod("execute")
                            .runBefore { param2 ->
                                removeAppResult(param2)
                            }
                    }
            }
        }

        alphabeticalAppsListClass
            .hookMethod("onAppsUpdated")
            .runAfter { param ->
                val mAdapterItems =
                    (param.thisObject.getField("mAdapterItems") as ArrayList<*>).toMutableList()

                val iterator = mAdapterItems.iterator()

                while (iterator.hasNext()) {
                    val item = iterator.next()
                    val itemInfo = item.getFieldSilently("itemInfo")
                    val componentName = itemInfo.getComponentName()

                    if (componentName.matchesBlocklist()) {
                        iterator.remove()
                    }
                }

                param.thisObject.setField("mAdapterItems", ArrayList(mAdapterItems))
            }
    }

    private fun MutableIterator<Any?>.removeMatches() {
        while (hasNext()) {
            val itemInfo = next()
            val componentName = itemInfo.getComponentName()

            if (componentName.matchesBlocklist()) {
                remove()
            }
        }
    }

    private fun Any?.getComponentName(): ComponentName {
        if (this == null) return ComponentName("", "")

        return getFieldSilently("componentName") as? ComponentName
            ?: getFieldSilently("mComponentName") as? ComponentName
            ?: callMethod("getTargetComponent") as ComponentName
    }

    private fun ComponentName?.matchesBlocklist(): Boolean {
        return this?.packageName.matchesBlocklist()
    }

    private fun String?.matchesBlocklist(): Boolean {
        if (isNullOrEmpty() || SHOULD_UNHIDE_ALL_APPS) return false
        return appBlockList.contains(this)
    }

    fun <T> binarySearch(
        array: Array<out T>,
        key: T,
        comparator: Comparator<in T>
    ): Int {
        var low = 0
        var high = array.size - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = array[mid]
            val cmp = comparator.compare(midVal, key)

            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }

        return -(low + 1)
    }
}