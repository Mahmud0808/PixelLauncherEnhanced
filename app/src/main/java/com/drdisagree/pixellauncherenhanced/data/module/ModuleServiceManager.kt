package com.drdisagree.pixellauncherenhanced.data.module

import com.drdisagree.pixellauncherenhanced.data.prefs.RemotePrefsSync
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

object ModuleServiceManager : XposedServiceHelper.OnServiceListener {

    private val started = AtomicBoolean(false)

    @Volatile
    var service: XposedService? = null
        private set

    fun start() {
        if (started.compareAndSet(false, true)) XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        RemotePrefsSync.attach(service)
        ModuleReloader.reloadStaleTargets(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service !== service) return
        RemotePrefsSync.detach()
        this.service = null
    }
}
