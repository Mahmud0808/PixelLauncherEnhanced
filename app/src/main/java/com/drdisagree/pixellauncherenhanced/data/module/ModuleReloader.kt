package com.drdisagree.pixellauncherenhanced.data.module

import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService

object ModuleReloader {

    private const val TAG = "ModuleReloader"

    private val requested = HashSet<String>()

    @Synchronized
    fun reloadStaleTargets(service: XposedService) {
        if (service.apiVersion < XposedService.API_102) return
        runCatching { service.runningTargets }
            .getOrElse { return }
            .filter { it.state == HookedTarget.State.STALE }
            .filter { requested.add("${it.pid}:${it.loadedVersionCode}") }
            .forEach { target ->
                runCatching {
                    service.hotReloadModule(target, null) { reloaded, result ->
                        Log.i(TAG, "${reloaded.processName}: ${result.status} ${result.message.orEmpty()}")
                    }
                }.onFailure { Log.w(TAG, "Hot reload request failed for ${target.processName}", it) }
            }
    }
}
