package com.drdisagree.pixellauncherenhanced.xposed.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.UserManager
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.IRootProviderProxy
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Executors

object RootProxyClient : ServiceConnection {

    @Volatile
    private var proxy: IRootProviderProxy? = null

    @Volatile
    private var connecting = false

    private val queue: Queue<ProxyRunnable> = LinkedList()
    private val executor = Executors.newSingleThreadExecutor()

    val isConnected: Boolean
        get() = proxy != null

    fun enqueueProxyCommand(runnable: ProxyRunnable) {
        proxy?.let { current ->
            executor.execute {
                try {
                    runnable.run(current)
                } catch (_: RemoteException) {
                }
            }
        } ?: run {
            synchronized(queue) { queue.add(runnable) }
            connect()
        }
    }

    fun connectRootProxy() {
        if (proxy == null) connect()
    }

    fun connect() {
        if (connecting || !ModuleRuntime.isContextReady) return
        connecting = true

        CoroutineScope(Dispatchers.IO).launch {
            val context = ModuleRuntime.context
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            var waitedForUnlock = false

            while (userManager == null || !userManager.isUserUnlocked) {
                waitedForUnlock = true
                delay(UNLOCK_POLL_MS)
            }

            if (waitedForUnlock) delay(UNLOCK_SETTLE_MS)

            while (proxy == null) {
                bind(context)
                delay(BIND_RETRY_MS)
            }

            connecting = false
        }
    }

    private fun bind(context: Context) {
        try {
            val intent = Intent().setComponent(
                ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.services.RootProviderProxy")
            )
            context.bindService(intent, this, Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY)
        } catch (throwable: Throwable) {
            log(this, throwable)
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val connected = IRootProviderProxy.Stub.asInterface(service)
        proxy = connected

        synchronized(queue) {
            while (queue.isNotEmpty()) {
                val runnable = queue.poll() ?: break
                executor.execute {
                    try {
                        runnable.run(connected)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        proxy = null
        connect()
    }

    private const val UNLOCK_POLL_MS = 2000L
    private const val UNLOCK_SETTLE_MS = 5000L
    private const val BIND_RETRY_MS = 5000L
}
