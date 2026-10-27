package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_DRAWER_HANDLE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

class DrawerHandle(context: Context) : ModPack(context) {

    private var hideHandle = false
    private var handleRef: WeakReference<View>? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideHandle = getBoolean(HIDE_DRAWER_HANDLE, false)
        }

        when (key.firstOrNull()) {
            HIDE_DRAWER_HANDLE -> handleRef?.get()?.let { it.post { it.applyHidden() } }
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", suppressError = true)
            .hookMethod("onFinishInflate")
            .suppressError()
            .runAfter { param ->
                val container = param.thisObject as View
                val id = container.resources.getIdentifier("bottom_sheet_handle", "id", container.context.packageName)
                if (id == 0) return@runAfter
                val handle = container.findViewById<View>(id) ?: return@runAfter

                handle.setExtraField(ORIGINAL_BACKGROUND, handle.background)
                handleRef = WeakReference(handle)
                handle.applyHidden()
            }
    }

    private fun View.applyHidden() {
        background = if (hideHandle) null else getExtraFieldSilently(ORIGINAL_BACKGROUND) as? Drawable
    }

    companion object {
        private const val ORIGINAL_BACKGROUND = "pleOriginalHandleBackground"
    }
}
