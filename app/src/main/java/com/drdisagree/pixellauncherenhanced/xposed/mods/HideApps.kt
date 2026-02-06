package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UNHIDE_ALL_APPS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.ArrayList

class HideApps(context: Context) : ModPack(context) {

    private var appBlockList: Set<String> = mutableSetOf()
    private var searchHiddenApps: Boolean = false

    companion object {
        var SHOULD_UNHIDE_ALL_APPS = false

        fun updateLauncherIcons(context: Context) {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, "Restarting Launcher... 😋", Toast.LENGTH_SHORT).show()
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        Process.killProcess(Process.myPid())
                    }, 500)
                } catch (e: Exception) {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            appBlockList = getStringSet(APP_BLOCK_LIST, emptySet())!!
            searchHiddenApps = getBoolean(SEARCH_HIDDEN_APPS, false)
            SHOULD_UNHIDE_ALL_APPS = getBoolean(UNHIDE_ALL_APPS, false)
        }

        if (key.contains(APP_BLOCK_LIST) || 
            key.contains(SEARCH_HIDDEN_APPS) || 
            key.contains(UNHIDE_ALL_APPS)) {
            updateLauncherIcons(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val alphabeticalAppsListClass = findClass("com.android.launcher3.allapps.AlphabeticalAppsList")
        val allAppsStoreClass = findClass("com.android.launcher3.allapps.AllAppsStore")
        val predictionRowViewClass = findClass("com.android.launcher3.appprediction.PredictionRowView")
        val hotseatPredictionControllerClass = findClass("com.android.launcher3.hybridhotseat.HotseatPredictionController")
        val hybridHotseatOrganizerClass = findClass("com.android.launcher3.util.HybridHotseatOrganizer", suppressError = true)
        val appInfoClass = findClass("com.android.launcher3.model.data.AppInfo")

        allAppsStoreClass.hookMethod("setApps").runBefore { param ->
            val originalApps = param.args[0] as? Array<*> ?: return@runBefore
            if (!searchHiddenApps) {
                val filtered = originalApps.filterNot { it.getComponentName().matchesBlocklist() }
                val newArr = java.lang.reflect.Array.newInstance(appInfoClass!!, filtered.size) as Array<*>
                System.arraycopy(filtered.toTypedArray(), 0, newArr, 0, filtered.size)
                param.args[0] = newArr
            }
        }

        alphabeticalAppsListClass.hookMethod("onAppsUpdated").runAfter { param ->
            val mAdapterItems = (param.thisObject.getField("mAdapterItems") as? ArrayList<*>)?.toMutableList() ?: return@runAfter
            mAdapterItems.removeIf { item ->
                item.getFieldSilently("itemInfo").getComponentName().matchesBlocklist()
            }
            param.thisObject.setField("mAdapterItems", ArrayList(mAdapterItems))
        }

        predictionRowViewClass.hookMethod("applyPredictionApps").runBefore { param ->
            val apps = (param.thisObject.getField("mPredictedApps") as? ArrayList<*>)?.toMutableList() ?: return@runBefore
            apps.removeIf { it.getComponentName().matchesBlocklist() }
            param.thisObject.setField("mPredictedApps", ArrayList(apps))
        }

        val fillGapsMethod = if (hotseatPredictionControllerClass.hasMethod("fillGapsWithPrediction")) "mPredictedItems" else "predictedItems"
        val targetClass = if (hotseatPredictionControllerClass.hasMethod("fillGapsWithPrediction")) hotseatPredictionControllerClass else hybridHotseatOrganizerClass
        
        targetClass.hookMethod("fillGapsWithPrediction")
            .parameters(Boolean::class.java)
            .runBefore { param ->
                val items = (param.thisObject.getField(fillGapsMethod) as? List<*>)?.toMutableList() ?: return@runBefore
                items.removeIf { it.getComponentName().matchesBlocklist() }
            }
    }

    private fun Any?.getComponentName(): ComponentName {
        if (this == null) return ComponentName("", "")
        return getFieldSilently("componentName") as? ComponentName
            ?: getFieldSilently("mComponentName") as? ComponentName
            ?: callMethodSilently("getTargetComponent") as? ComponentName
            ?: ComponentName("", "")
    }

    private fun ComponentName?.matchesBlocklist(): Boolean {
        val pkg = this?.packageName ?: return false
        return !SHOULD_UNHIDE_ALL_APPS && appBlockList.contains(pkg)
    }
}
