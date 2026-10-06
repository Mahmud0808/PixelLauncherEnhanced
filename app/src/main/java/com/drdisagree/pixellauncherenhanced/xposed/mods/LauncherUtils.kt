package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.resetCounter
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class LauncherUtils(context: Context) : ModPack(context) {

    override fun updatePrefs(vararg key: String) {}

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        ThemesClass = findClass("com.android.launcher3.util.Themes")
        GraphicsUtilsClass = findClass("com.android.launcher3.icons.GraphicsUtils")
        InvariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")
        BaseIconCacheClass = findClass("com.android.launcher3.icons.cache.BaseIconCache")
        QuickstepLauncherClass = findClass("com.android.launcher3.uioverrides.QuickstepLauncher")
        LauncherAppStateClass = findClass("com.android.launcher3.LauncherAppState")
        LauncherAppStateCompanionClass = findClass(
            $$"com.android.launcher3.LauncherAppState$Companion",
            suppressError = true
        )

        InvariantDeviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                invariantDeviceProfileInstance = param.thisObject
            }

        BaseIconCacheClass
            .hookConstructor()
            .runAfter { param ->
                iconCacheInstance = param.thisObject
            }

        LauncherAppStateClass
            .hookConstructor()
            .runAfter { param ->
                mModel = param.thisObject.getAnyField("mModel", "model")
                if (invariantDeviceProfileInstance == null) {
                    invariantDeviceProfileInstance = param.thisObject.getAnyField(
                        "mInvariantDeviceProfile",
                        "invariantDeviceProfile"
                    )
                }
            }

        if (LauncherAppStateCompanionClass != null) {
            QuickstepLauncherClass
                .hookMethod("onCreate")
                .runAfter { param ->
                    if (invariantDeviceProfileInstance == null) {
                        invariantDeviceProfileInstance =
                            LauncherAppStateCompanionClass.callStaticMethod(
                                "getIDP",
                                param.thisObject
                            )
                    }
                }
        }
    }

    companion object {

        private var ThemesClass: Class<*>? = null
        private var GraphicsUtilsClass: Class<*>? = null
        private var InvariantDeviceProfileClass: Class<*>? = null
        private var BaseIconCacheClass: Class<*>? = null
        private var QuickstepLauncherClass: Class<*>? = null
        private var LauncherAppStateClass: Class<*>? = null
        private var LauncherAppStateCompanionClass: Class<*>? = null

        private var invariantDeviceProfileInstance: Any? = null
        private var iconCacheInstance: Any? = null

        private const val TAG = "LauncherUtils"
        private var mModel: Any? = null

        private var lastRestartTime = 0L

        fun getAttrColor(context: Context, resID: Int): Int {
            return runCatching {
                ThemesClass.callStaticMethod(
                    "getAttrColor",
                    context,
                    resID
                )
            }.getOrElse {
                runCatching {
                    ThemesClass.callStaticMethod(
                        "getAttrColor",
                        resID,
                        context
                    )
                }.getOrElse {
                    runCatching {
                        GraphicsUtilsClass.callStaticMethod(
                            "getAttrColor",
                            context,
                            resID
                        )
                    }.getOrElse {
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

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        modRes.getString(R.string.restarting_launcher),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000.milliseconds)
                    enqueueProxyCommand { proxy ->
                        proxy.runCommand("killall ${context.packageName}")
                    }
                }
            }
        }

        fun reloadLauncher(context: Context) {
            if (invariantDeviceProfileInstance.hasMethod("onConfigChanged", Context::class.java)) {
                invariantDeviceProfileInstance.callMethod("onConfigChanged", context)
            } else {
                invariantDeviceProfileInstance.callMethod("onConfigChanged")
            }
        }

        fun removeCachedIcons(packageName: String, user: UserHandle) {
            val iconCache = iconCacheInstance ?: return

            if (iconCache.hasMethod("removeIconsForPkg", UserHandle::class.java, String::class.java)) {
                iconCache.callMethodSilently("removeIconsForPkg", user, packageName)
            } else {
                iconCache.callMethodSilently("removeIconsForPkg", packageName, user)
            }
        }

        fun reloadIcons() {
            Handler(Looper.getMainLooper()).post {
                val iconCache = iconCacheInstance

                runCatching {
                    iconCache.getAnyField("mCache", "cache").callMethod("clear")
                }.onFailure { log(TAG, it) }

                runCatching {
                    val iconDb = iconCache.getAnyField("mIconDb", "iconDb")

                    if (iconDb.hasMethod("clear")) {
                        iconDb.callMethod("clear")
                    } else {
                        iconDb.getField("mOpenHelper").also { openHelper ->
                            openHelper.callMethod("clearDB", openHelper.callMethod("getWritableDatabase"))
                        }
                    }
                }.onFailure { log(TAG, it) }

                runCatching {
                    runCatching { mModel.callMethod("forceReload") }
                        .getOrElse { mModel.callMethod("forceReload", "reloadIcons") }
                }.onFailure { log(TAG, it) }
            }
        }
    }
}