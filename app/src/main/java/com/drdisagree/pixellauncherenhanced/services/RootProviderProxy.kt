package com.drdisagree.pixellauncherenhanced.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import com.drdisagree.pixellauncherenhanced.IRootProviderProxy
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.utils.RootShell
import com.topjohnwu.superuser.Shell

class RootProviderProxy : Service() {

    override fun onBind(intent: Intent): IBinder {
        return RootProviderProxyIPC(this)
    }

    internal inner class RootProviderProxyIPC(context: Context) : IRootProviderProxy.Stub() {

        init {
            try {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(20)
                )
            } catch (_: Throwable) {
            }

            rootGranted = Shell.getShell().isRoot

            rootAllowedPacks = listOf<String>(
                *context.resources.getStringArray(R.array.root_requirement)
            )
        }

        @Throws(RemoteException::class)
        override fun runCommand(command: String): Array<out String?> {
            ensureEnvironment()

            try {
                val result = Shell.cmd(command).exec().out
                return result.toTypedArray<String>()
            } catch (_: Throwable) {
                return arrayOfNulls(0)
            }
        }

        @Throws(RemoteException::class)
        private fun ensureEnvironment() {
            if (!rootGranted) rootGranted = RootShell.isRootShell()
            if (!rootGranted) {
                throw RemoteException("Root permission denied")
            }

            ensureSecurity(Binder.getCallingUid())
        }

        @Throws(RemoteException::class)
        private fun ensureSecurity(uid: Int) {
            for (packageName in packageManager.getPackagesForUid(uid)!!) {
                if (rootAllowedPacks.contains(packageName)) return
            }

            throw RemoteException("$packageName is not allowed to use root commands")
        }
    }

    companion object {
        var TAG: String = "[PLEnhanced] ${RootProviderProxy::class.java.simpleName}: "
        private var rootAllowedPacks: List<String> = listOf()
        private var rootGranted: Boolean = false
    }
}