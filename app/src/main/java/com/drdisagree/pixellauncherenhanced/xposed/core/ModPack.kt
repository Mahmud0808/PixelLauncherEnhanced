package com.drdisagree.pixellauncherenhanced.xposed.core

import android.content.Context
import android.content.pm.PackageManager
import com.drdisagree.pixellauncherenhanced.BuildConfig

abstract class ModPack(private val context: Context) {

    protected val mContext: Context get() = context

    protected val appContext: Context
        get() = try {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (exception: PackageManager.NameNotFoundException) {
            throw RuntimeException(exception)
        }

    abstract fun updatePrefs(vararg key: String)

    abstract fun handleLoadPackage(loadedPackage: LoadedPackage)
}
