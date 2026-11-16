package com.drdisagree.pixellauncherenhanced.xposed.core

import android.content.Context
import android.content.res.Resources
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.CopyOnWriteArrayList

object ModuleRuntime {

    lateinit var module: XposedModule
        private set

    lateinit var processName: String
        private set

    lateinit var loadedPackage: LoadedPackage
        private set

    lateinit var context: Context
        private set

    lateinit var modRes: Resources
        private set

    private val disposables = CopyOnWriteArrayList<() -> Unit>()

    val isAttached: Boolean
        get() = ::module.isInitialized

    val isPackageLoaded: Boolean
        get() = ::loadedPackage.isInitialized

    val isContextReady: Boolean
        get() = ::context.isInitialized

    val isChildProcess: Boolean
        get() = ::processName.isInitialized && processName.contains(':')

    val apiVersion: Int
        get() = if (isAttached) module.apiVersion else 0

    val supportsHotReload: Boolean
        get() = apiVersion >= XposedInterface.API_102

    fun attach(module: XposedModule, processName: String) {
        this.module = module
        this.processName = processName
    }

    fun setPackage(loadedPackage: LoadedPackage) {
        this.loadedPackage = loadedPackage
    }

    fun setContext(context: Context, modRes: Resources) {
        this.context = context
        this.modRes = modRes
    }

    fun onDispose(action: () -> Unit) {
        disposables.add(action)
    }

    fun dispose() {
        disposables.forEach { runCatching(it) }
        disposables.clear()
    }
}
