package com.drdisagree.pixellauncherenhanced.xposed.core

import com.drdisagree.pixellauncherenhanced.xposed.reflection.callStaticMethod
import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.os.Process
import android.os.SystemClock
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.hook.ResourceHookManager
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.WallpaperGlass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList

object ModLoader {

    val runningMods = CopyOnWriteArrayList<ModPack>()

    fun load(packageName: String, classLoader: ClassLoader) {
        val processName = ModuleRuntime.processName
        ModuleRuntime.setPackage(LoadedPackage(packageName, classLoader, processName))
        if (ModuleRuntime.isChildProcess || EntryList.getEntries(packageName).isEmpty()) return

        Instrumentation::class.java
            .hookMethod("newApplication")
            .parameters(ClassLoader::class.java, String::class.java, Context::class.java)
            .runAfter { param ->
                if (ModuleRuntime.isContextReady) return@runAfter
                val context = param.args[2] as? Context ?: return@runAfter
                runCatching {
                    attachContext(context)
                    onContextReady()
                }.onFailure { log(this, it) }
            }
    }

    fun saveState(): Array<Any?>? {
        if (!ModuleRuntime.isPackageLoaded || !ModuleRuntime.isContextReady) return null
        val loaded = ModuleRuntime.loadedPackage
        return arrayOf(loaded.packageName, loaded.classLoader, ModuleRuntime.context)
    }

    fun restoreState(state: Any?) {
        val saved = state as? Array<*> ?: return
        val packageName = saved.getOrNull(0) as? String ?: return
        val classLoader = saved.getOrNull(1) as? ClassLoader ?: return
        val context = saved.getOrNull(2) as? Context ?: return
        ModuleRuntime.setPackage(LoadedPackage(packageName, classLoader, ModuleRuntime.processName))
        runCatching {
            attachContext(context)
            onContextReady()
        }.onFailure { log(this, it) }
        Handler(Looper.getMainLooper()).post { recreateActivities() }
    }

    private fun attachContext(context: Context) {
        val modRes = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
        ModuleRuntime.setContext(context, modRes)
        XPrefs.init(context)
        ResourceHookManager.init(context)
    }

    private fun onContextReady() {
        val packageName = ModuleRuntime.loadedPackage.packageName
        XPrefs.awaitSeed()

        log("[PLEnhanced] Version: ${BuildConfig.VERSION_NAME}")
        log("[PLEnhanced] Hooked $packageName via ${ModuleRuntime.module.frameworkName} API ${ModuleRuntime.apiVersion}")

        if (BootLoopProtector.isBootLooped(packageName)) {
            log("[PLEnhanced] Possible crash in $packageName ; Module will not load for now...")
            return
        }

        loadModPacks(packageName)
    }

    private fun loadModPacks(packageName: String) {
        val context = ModuleRuntime.context

        if (ModuleRuntime.modRes.getStringArray(R.array.root_requirement).contains(packageName)) {
            RootProxyClient.connect()
        }

        val entries = EntryList.getEntries(packageName)
        if (entries.isEmpty()) return

        DebugRecorder.attach(context, packageName)
        registerDebugProbes(packageName)
        restartOnModuleUpdate(context)

        val loadedPackage = ModuleRuntime.loadedPackage
        entries.forEach { mod ->
            val startedAt = SystemClock.elapsedRealtime()
            var prefsError: Throwable? = null
            var loadError: Throwable? = null
            DebugRecorder.withOwner(mod.simpleName) {
                try {
                    val instance = mod.getConstructor(Context::class.java).newInstance(context)
                    try {
                        instance.updatePrefs()
                    } catch (throwable: Throwable) {
                        prefsError = throwable
                        log(this, "Failed to update prefs in ${mod.name}")
                        log(this, throwable)
                    }
                    instance.handleLoadPackage(loadedPackage)
                    runningMods.add(instance)
                } catch (exception: InvocationTargetException) {
                    loadError = exception.cause ?: exception
                    log(this, "Start Error Dump - Occurred in ${mod.name}")
                    log(this, exception.cause)
                } catch (throwable: Throwable) {
                    loadError = throwable
                    log(this, "Start Error Dump - Occurred in ${mod.name}")
                    log(this, throwable)
                }
            }
            DebugRecorder.recordModResult(mod.simpleName, SystemClock.elapsedRealtime() - startedAt, prefsError, loadError)
        }

        ModuleRuntime.onDispose { runningMods.clear() }
    }

    fun dispatchPrefsChanged(vararg keys: String) {
        runningMods.forEach { mod ->
            try {
                mod.updatePrefs(*keys)
            } catch (throwable: Throwable) {
                log(this, "${mod.javaClass.simpleName} -> $throwable")
            }
        }
    }

    private fun restartOnModuleUpdate(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        val restart = Runnable { Process.killProcess(Process.myPid()) }
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(BuildConfig.APPLICATION_ID, PatternMatcher.PATTERN_LITERAL)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val grace = if (ModuleRuntime.supportsHotReload) HOT_RELOAD_GRACE_MS else 0L
                handler.postDelayed(restart, grace)
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            ModuleRuntime.onDispose {
                handler.removeCallbacks(restart)
                runCatching { context.unregisterReceiver(receiver) }
            }
        }.onFailure { log(this, it) }
    }

    private fun recreateActivities() {
        resumedActivities().forEach { activity -> runCatching { activity.recreate() } }
    }

    private fun resumedActivities(): List<Activity> {
        val thread = Reflection.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentActivityThread")
        val records = Reflection.getObjectField(thread, "mActivities") as? Map<*, *> ?: return emptyList()
        return records.values.mapNotNull { record -> Reflection.getObjectField(record, "activity") as? Activity }
    }

    private fun registerDebugProbes(packageName: String) {
        DebugRecorder.registerProbe("rootProxyConnected") { RootProxyClient.isConnected }
        DebugRecorder.registerProbe("xprefsInitialized") { XPrefs.isInitialized }
        DebugRecorder.registerProbe("framework") {
            "${ModuleRuntime.module.frameworkName} ${ModuleRuntime.module.frameworkVersion} API ${ModuleRuntime.apiVersion}"
        }
        DebugRecorder.registerProbe("bootLoopStrikes") {
            Xprefs.getInt("${BootLoopProtector.PACKAGE_STRIKE_KEY_KEY}$packageName", 0)
        }
        DebugRecorder.registerProbe("runningMods") { runningMods.joinToString { it.javaClass.simpleName } }
        DebugRecorder.registerModInstances { runningMods.toList() }
        DebugRecorder.registerProbe("wallpaperGlass") { WallpaperGlass.debugState() }
        DebugRecorder.registerProbe("iconPacks") { IconPackManager.debugState() }
        DebugRecorder.registerProbe("activities") {
            val thread = Reflection.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentActivityThread")
            (Reflection.getObjectField(thread, "mActivities") as? Map<*, *>)?.values?.joinToString { record ->
                val activity = Reflection.getObjectField(record, "activity")
                "${activity?.javaClass?.name} paused=${Reflection.getObjectField(record, "paused")}"
            }
        }
    }

    private const val HOT_RELOAD_GRACE_MS = 15000L
}
