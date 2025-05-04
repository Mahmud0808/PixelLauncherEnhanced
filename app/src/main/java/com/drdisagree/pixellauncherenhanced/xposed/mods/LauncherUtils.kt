package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.resetCounter
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LauncherUtils(context: Context) : ModPack(context) {

    override fun updatePrefs(vararg key: String) {}

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        ThemesClass = findClass("com.android.launcher3.util.Themes")
        GraphicsUtilsClass = findClass("com.android.launcher3.icons.GraphicsUtils")
        InvariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")
        BaseIconCacheClass = findClass("com.android.launcher3.icons.cache.BaseIconCache")
        LauncherAppStateClass = findClass("com.android.launcher3.LauncherAppState")

        InvariantDeviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                invariantDeviceProfileInstance = param.thisObject
            }

        BaseIconCacheClass
            .hookConstructor()
            .runAfter { param ->
                mIconDb = param.thisObject.getAnyField("mIconDb", "iconDb")
                mCache = param.thisObject.getAnyField("mCache", "cache")
            }

        LauncherAppStateClass
            .hookConstructor()
            .runAfter { param ->
                mModel = param.thisObject.getAnyField("mModel", "model")
            }
    }

    companion object {

        private var ThemesClass: Class<*>? = null
        private var GraphicsUtilsClass: Class<*>? = null
        private var InvariantDeviceProfileClass: Class<*>? = null
        private var BaseIconCacheClass: Class<*>? = null
        private var LauncherAppStateClass: Class<*>? = null

        private var invariantDeviceProfileInstance: Any? = null
        private var mIconDb: Any? = null
        private var mCache: Any? = null
        private var mModel: Any? = null

        private var lastRestartTime = 0L

        fun getAttrColor(context: Context, resID: Int): Int {
            return try {
                ThemesClass.callStaticMethod(
                    "getAttrColor",
                    context,
                    resID
                )
            } catch (_: Throwable) {
                try {
                    ThemesClass.callStaticMethod(
                        "getAttrColor",
                        resID,
                        context
                    )
                } catch (_: Throwable) {
                    try {
                        GraphicsUtilsClass.callStaticMethod(
                            "getAttrColor",
                            context,
                            resID
                        )
                    } catch (_: Throwable) {
                        GraphicsUtilsClass.callStaticMethod(
                            "getAttrColor",
                            resID,
                            context
                        )
                    }
                }
            } as Int
        }

        fun restartLauncher(context: Context) {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastRestartTime >= 500) {
                lastRestartTime = currentTime
                resetCounter(context.packageName)

                Toast.makeText(
                    context,
                    modRes.getString(R.string.restarting_launcher),
                    Toast.LENGTH_SHORT
                ).show()

                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    enqueueProxyCommand { proxy ->
                        proxy.runCommand("killall ${context.packageName}")
                    }
                }
            }
        }

        fun reloadLauncher(context: Context) {
            invariantDeviceProfileInstance.callMethod("onConfigChanged", context)
        }

        fun reloadIcons() {
            Handler(Looper.getMainLooper()).post {
                mCache.callMethod("clear")
                mIconDb.callMethod("clear")
                mModel.callMethod("forceReload")
            }
        }
    }
}