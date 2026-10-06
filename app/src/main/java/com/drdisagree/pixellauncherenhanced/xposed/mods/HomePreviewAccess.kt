package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ContentProvider
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.MethodHookHelper
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class HomePreviewAccess(context: Context) : ModPack(context) {

    private val granting = ThreadLocal<Boolean>()
    private val renderers = ThreadLocal<MutableList<Any>>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var scopeHooked = false

    override fun updatePrefs(vararg key: String) {}

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        listOf(
            "com.android.launcher3.util.ContentProviderProxy",
            "com.android.launcher3.graphics.GridCustomizationsProvider"
        ).forEach { className ->
            val clazz = findClass(className, suppressError = true) ?: return@forEach
            val method = XposedHelpers.findMethodExactIfExists(
                clazz,
                "call",
                String::class.java,
                String::class.java,
                Bundle::class.java
            ) ?: return@forEach

            MethodHookHelper(method)
                .runBefore { param ->
                    val provider = param.thisObject as? ContentProvider ?: return@runBefore
                    if (callingPackage(provider) != BuildConfig.APPLICATION_ID) return@runBefore
                    hookScope()
                    granting.set(true)
                    renderers.set(mutableListOf())
                }
                .runAfter { param ->
                    if (granting.get() != true) return@runAfter
                    granting.remove()
                    val created = renderers.get().orEmpty()
                    renderers.remove()

                    if (param.args[0] == METHOD_PREVIEW_BITMAP && created.isNotEmpty()) {
                        mainHandler.post {
                            created.forEach { it.callMethodSilently("executeAllAndDestroy") }
                        }
                    }
                }
        }
    }

    @Synchronized
    private fun hookScope() {
        if (scopeHooked) return
        scopeHooked = true

        XposedHelpers.findMethodExactIfExists(
            "android.app.ContextImpl",
            null,
            "checkPermission",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )?.let { method ->
            MethodHookHelper(method).runBefore { param ->
                if (granting.get() == true && param.args[0] == BIND_WALLPAPER) {
                    param.result = PackageManager.PERMISSION_GRANTED
                }
            }
        }

        val runnableListClass = findClass("com.android.launcher3.util.RunnableList", suppressError = true) ?: return

        findClass("com.android.launcher3.preview.PreviewSurfaceRenderer", suppressError = true)
            .hookConstructor()
            .suppressError()
            .runBefore { param ->
                val created = renderers.get() ?: return@runBefore
                param.args.firstOrNull { runnableListClass.isInstance(it) }?.let { created.add(it) }
            }
    }

    private fun callingPackage(provider: ContentProvider): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            provider.callingAttributionSource?.packageName ?: provider.callingPackage
        } else {
            provider.callingPackage
        }
    }.getOrNull()

    companion object {
        private const val BIND_WALLPAPER = "android.permission.BIND_WALLPAPER"
        private const val METHOD_PREVIEW_BITMAP = "get_preview_bitmap"
    }
}
