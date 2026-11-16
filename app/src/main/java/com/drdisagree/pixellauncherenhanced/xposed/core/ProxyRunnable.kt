package com.drdisagree.pixellauncherenhanced.xposed.core

import android.os.RemoteException
import com.drdisagree.pixellauncherenhanced.IRootProviderProxy

fun interface ProxyRunnable {
    @Throws(RemoteException::class)
    fun run(proxy: IRootProviderProxy)
}
