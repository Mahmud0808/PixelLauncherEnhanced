package com.drdisagree.pixellauncherenhanced.xposed

import com.drdisagree.pixellauncherenhanced.xposed.core.ModLoader
import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class ModuleMain : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModuleRuntime.attach(this, param.processName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        ModLoader.load(param.packageName, param.classLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(ModLoader.saveState())
        ModuleRuntime.dispose()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        ModuleRuntime.attach(this, param.processName)
        ModLoader.restoreState(param.savedInstanceState)
    }
}
