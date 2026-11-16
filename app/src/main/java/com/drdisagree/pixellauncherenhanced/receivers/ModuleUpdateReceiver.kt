package com.drdisagree.pixellauncherenhanced.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.drdisagree.pixellauncherenhanced.data.module.ModuleServiceManager

class ModuleUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ModuleServiceManager.start()
    }
}
