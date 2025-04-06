package com.drdisagree.pixellauncherenhanced.xposed.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ACTION_APP_LIST_UPDATED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ACTION_HOOK_CHECK_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ACTION_HOOK_CHECK_RESULT
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class BroadcastHook(context: Context) : ModPack(context) {

    private var broadcastRegistered = false

    override fun updatePrefs(vararg key: String) {}

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        if (broadcastRegistered) return

        broadcastRegistered = true

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_HOOK_CHECK_REQUEST)
        }

        val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_HOOK_CHECK_REQUEST) {
                    returnHookResult()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(
                broadcastReceiver,
                intentFilter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            mContext.registerReceiver(broadcastReceiver, intentFilter)
        }

        registerAdditionalReceivers()
    }

    private fun returnHookResult() {
        Thread {
            mContext.sendBroadcast(
                Intent()
                    .setAction(ACTION_HOOK_CHECK_RESULT)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
        }.start()
    }

    private fun updateAppList() {
        Thread {
            mContext.sendBroadcast(
                Intent()
                    .setAction(ACTION_APP_LIST_UPDATED)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
        }.start()
    }

    private fun registerAdditionalReceivers() {
        val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action in setOf(
                        Intent.ACTION_PACKAGE_ADDED,
                        Intent.ACTION_PACKAGE_REMOVED,
                        Intent.ACTION_PACKAGE_REPLACED
                    )
                ) {
                    updateAppList()
                }
            }
        }

        val intentFilterWithoutScheme = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
        }

        val intentFilterWithScheme = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(
                broadcastReceiver,
                intentFilterWithoutScheme,
                Context.RECEIVER_EXPORTED
            )
            mContext.registerReceiver(
                broadcastReceiver,
                intentFilterWithScheme,
                Context.RECEIVER_EXPORTED
            )
        } else {
            mContext.registerReceiver(broadcastReceiver, intentFilterWithoutScheme)
            mContext.registerReceiver(broadcastReceiver, intentFilterWithScheme)
        }
    }
}