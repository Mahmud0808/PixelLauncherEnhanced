package com.drdisagree.pixellauncherenhanced.utils

import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.topjohnwu.superuser.Shell

object RootShell {

    fun init() {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        @Suppress("DEPRECATION")
        if (Shell.getCachedShell() == null) {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(20)
            )
        }
    }

    fun checkRoot(callback: (Boolean) -> Unit) {
        init()
        Shell.getShell { callback(Shell.isAppGrantedRoot() == true) }
    }
}
