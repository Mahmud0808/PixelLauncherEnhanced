package com.drdisagree.pixellauncherenhanced.xposed

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.UserManager
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.IRootProviderProxy
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FRAMEWORK_PACKAGE
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceHookManager
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.XprefsIsInitialized
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture

class HookEntry : ServiceConnection {

    private lateinit var mContext: Context

    init {
        instance = this
    }

    fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        isChildProcess = try {
            loadPackageParam.processName.contains(":")
        } catch (_: Throwable) {
            false
        }

        when (loadPackageParam.packageName) {
            FRAMEWORK_PACKAGE -> {
                val phoneWindowManagerClass =
                    findClass("com.android.server.policy.PhoneWindowManager")

                phoneWindowManagerClass
                    .hookMethod("init")
                    .runBefore { param ->
                        try {
                            if (!::mContext.isInitialized) {
                                mContext = param.args[0] as Context

                                HookRes.modRes = mContext.createPackageContext(
                                    BuildConfig.APPLICATION_ID,
                                    Context.CONTEXT_IGNORE_SECURITY
                                ).resources

                                XPrefs.init(mContext)
                                ResourceHookManager.init(mContext)

                                CompletableFuture.runAsync { waitForXprefsLoad(loadPackageParam) }
                            }
                        } catch (throwable: Throwable) {
                            log(this@HookEntry, throwable)
                        }
                    }
            }

            else -> {
                if (!isChildProcess) {
                    Instrumentation::class.java
                        .hookMethod("newApplication")
                        .parameters(
                            ClassLoader::class.java,
                            String::class.java,
                            Context::class.java
                        )
                        .runAfter { param ->
                            try {
                                if (!::mContext.isInitialized) {
                                    mContext = param.args[2] as Context

                                    HookRes.modRes = mContext.createPackageContext(
                                        BuildConfig.APPLICATION_ID,
                                        Context.CONTEXT_IGNORE_SECURITY
                                    ).resources

                                    XPrefs.init(mContext)
                                    ResourceHookManager.init(mContext)

                                    waitForXprefsLoad(loadPackageParam)
                                }
                            } catch (throwable: Throwable) {
                                log(this@HookEntry, throwable)
                            }
                        }
                }
            }
        }
    }

    private fun onXPrefsReady(loadPackageParam: LoadPackageParam) {
        if (!isChildProcess && BootLoopProtector.isBootLooped(loadPackageParam.packageName)) {
            log("[PLEnhanced] Possible crash in ${loadPackageParam.packageName} ; Module will not load for now...")
            return
        }

        loadModPacks(loadPackageParam)
    }

    private fun loadModPacks(loadPackageParam: LoadPackageParam) {
        if (HookRes.modRes
                .getStringArray(R.array.root_requirement)
                .toList()
                .contains(loadPackageParam.packageName)
        ) {
            forceConnectRootService()
        }

        for (mod in EntryList.getEntries(loadPackageParam.packageName)) {
            try {
                val modInstance = mod.getConstructor(Context::class.java).newInstance(mContext)

                if (XprefsIsInitialized) {
                    try {
                        modInstance.updatePrefs()
                    } catch (throwable: Throwable) {
                        log(this@HookEntry, "Failed to update prefs in ${mod.name}")
                        log(this@HookEntry, throwable)
                    }
                }

                modInstance.handleLoadPackage(loadPackageParam)
                runningMods.add(modInstance)
            } catch (invocationTargetException: InvocationTargetException) {
                log(this@HookEntry, "Start Error Dump - Occurred in ${mod.name}")
                log(this@HookEntry, invocationTargetException.cause)
            } catch (throwable: Throwable) {
                log(this@HookEntry, "Start Error Dump - Occurred in ${mod.name}")
                log(this@HookEntry, throwable)
            }
        }
    }

    private fun waitForXprefsLoad(loadPackageParam: LoadPackageParam) {
        while (true) {
            try {
                Xprefs.getBoolean("LoadTestBooleanValue", false)
                break
            } catch (_: Throwable) {
                try {
                    Thread.sleep(1000.toLong())
                } catch (_: Throwable) {
                }
            }
        }

        log("[PLEnhanced] Version: ${BuildConfig.VERSION_NAME}")
        log("[PLEnhanced] Hooked ${loadPackageParam.packageName}")

        onXPrefsReady(loadPackageParam)
    }

    private fun forceConnectRootService() {
        if (isConnectingRootService) return
        isConnectingRootService = true

        CoroutineScope(Dispatchers.IO).launch {
            val mUserManager = mContext.getSystemService(Context.USER_SERVICE) as? UserManager
            var waitedForUnlock = false

            while (mUserManager == null || !mUserManager.isUserUnlocked) {
                // device is still CE encrypted
                waitedForUnlock = true
                delay(2000)
            }

            if (waitedForUnlock) delay(5000) // wait for the unlocked account to settle down a bit

            while (rootProxyIPC == null) {
                connectRootService()
                delay(5000)
            }

            isConnectingRootService = false
        }
    }

    private fun connectRootService() {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    BuildConfig.APPLICATION_ID,
                    "${BuildConfig.APPLICATION_ID}.services.RootProviderProxy"
                )
            }

            mContext.bindService(
                intent,
                instance!!,
                Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY
            )
        } catch (throwable: Throwable) {
            log(this@HookEntry, throwable)
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val proxy = IRootProviderProxy.Stub.asInterface(service)
        rootProxyIPC = proxy

        synchronized(proxyQueue) {
            while (!proxyQueue.isEmpty()) {
                val runnable = proxyQueue.poll()!!
                proxyExecutor.execute {
                    try {
                        runnable.run(proxy)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        rootProxyIPC = null
        forceConnectRootService()
    }

    fun interface ProxyRunnable {
        @Throws(RemoteException::class)
        fun run(proxy: IRootProviderProxy)
    }

    companion object {
        private var _instance: WeakReference<HookEntry>? = null
        private var instance: HookEntry?
            get() = _instance?.get()
            set(value) {
                _instance = value?.let { WeakReference(it) }
            }

        val runningMods = ArrayList<ModPack>()
        var isChildProcess = false

        @Volatile
        private var rootProxyIPC: IRootProviderProxy? = null

        @Volatile
        private var isConnectingRootService = false
        private val proxyQueue: Queue<ProxyRunnable> = LinkedList()
        private val proxyExecutor = Executors.newSingleThreadExecutor()

        fun enqueueProxyCommand(runnable: ProxyRunnable) {
            rootProxyIPC?.let { proxy ->
                proxyExecutor.execute {
                    try {
                        runnable.run(proxy)
                    } catch (_: RemoteException) {
                    }
                }
            } ?: run {
                synchronized(proxyQueue) {
                    proxyQueue.add(runnable)
                }

                instance?.forceConnectRootService()
            }
        }

        fun connectRootProxy() {
            if (rootProxyIPC == null) instance?.forceConnectRootService()
        }
    }
}
