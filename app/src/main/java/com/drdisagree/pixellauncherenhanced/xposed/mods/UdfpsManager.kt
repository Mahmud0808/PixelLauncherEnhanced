package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.view.View
import android.widget.ImageView
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_FINGERPRINT_CIRCLE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_FINGERPRINT_ICON
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class UdfpsManager(context: Context) : ModPack(context) {

    private val TRANSPARENT = 0
    private val OPAQUE = 255
    private var transparentBG = false
    private var transparentFG = false
    private var mDeviceEntryIconView: View? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            transparentBG = getBoolean(HIDE_FINGERPRINT_CIRCLE, false)
            transparentFG = getBoolean(HIDE_FINGERPRINT_ICON, false)
        }

        if (key.isNotEmpty()) {
            when (key.firstOrNull()) {
                HIDE_FINGERPRINT_CIRCLE, HIDE_FINGERPRINT_ICON -> setUDFPSGraphics(true)
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val DeviceEntryIconViewClass = findClass("com.android.systemui.keyguard.ui.view.DeviceEntryIconView")
        val DeviceEntryIconViewModelClass = findClass("com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel")

        DeviceEntryIconViewModelClass
            .hookConstructor()
            .runAfter { param ->
                if (transparentBG && !transparentFG) {
                    try {
                        val ReadonlyStateFlowClass = findClass("kotlinx.coroutines.flow.ReadonlyStateFlow")
                        val stateFlowValue = getStateFlowImplOf(false)
                        val newFlow = ReadonlyStateFlowClass?.constructors?.get(0)?.newInstance(stateFlowValue)
                        XposedHelpers.setObjectField(param.thisObject, "useBackgroundProtection", newFlow)
                    } catch (_: Throwable) {
                    }
                }
            }

        DeviceEntryIconViewClass
            .hookConstructor()
            .runAfter { param ->
                mDeviceEntryIconView = param.thisObject as View
                setUDFPSGraphics(false)

                mDeviceEntryIconView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.z = 100f
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                })

                mDeviceEntryIconView?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.z = 100f
                }
            }
    }

    private fun setUDFPSGraphics(force: Boolean) {
        mDeviceEntryIconView?.let { view ->
            if (transparentFG || force) {
                try {
                    val iconView = XposedHelpers.getObjectField(view, "iconView") as ImageView
                    iconView.imageAlpha = if (transparentFG) TRANSPARENT else OPAQUE
                } catch (_: Throwable) {}
            }
            if (transparentFG || transparentBG || force) {
                try {
                    val bgView = XposedHelpers.getObjectField(view, "bgView") as ImageView
                    bgView.imageAlpha = if (transparentFG || transparentBG) TRANSPARENT else OPAQUE
                } catch (_: Throwable) {}
            }
        }
    }

    private fun getStateFlowImplOf(value: Any): Any {
        val stateFlowKtClass = findClass("kotlinx.coroutines.flow.StateFlowKt")
        return XposedHelpers.callStaticMethod(stateFlowKtClass, "MutableStateFlow", value)
    }
}
