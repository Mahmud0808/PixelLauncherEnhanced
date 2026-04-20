package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_TOP_SHADOW
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class TopShadow(context: Context) : ModPack(context) {

    private var removeTopShadow = false
    private var sysUiScrimInstance: Any? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            removeTopShadow = getBoolean(LAUNCHER_HIDE_TOP_SHADOW, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_HIDE_TOP_SHADOW -> if (removeTopShadow) {
                updateMaskBitmaps()
            } else {
                restartLauncher(mContext)
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val sysUiScrimClass = findClass("com.android.launcher3.graphics.SysUiScrim")

        sysUiScrimClass
            .hookConstructor()
            .runAfter { param ->
                sysUiScrimInstance = param.thisObject
                updateMaskBitmaps()
            }

        sysUiScrimClass
            .hookMethod(
                "onViewAttachedToWindow",
                "onViewDetachedFromWindow"
            )
            .runBefore { param ->
                if (!removeTopShadow) return@runBefore

                param.result = null
            }

        sysUiScrimClass
            .hookMethod("createDitheredAlphaMask")
            .suppressError()
            .runAfter { param ->
                if (!removeTopShadow) return@runAfter

                val bitmap = param.result as Bitmap
                bitmap.eraseColor(Color.TRANSPARENT)
            }
    }

    private fun updateMaskBitmaps() {
        if (!removeTopShadow || sysUiScrimInstance == null) return

        val mRoot = sysUiScrimInstance.getField("mRoot") as View
        val mTopMaskPaint = sysUiScrimInstance.getAnyField(
            "mTopMaskPaint",
            "mWallpaperScrimPaint"
        ) as Paint

        mTopMaskPaint.color = Color.rgb(0x22, 0x22, 0x22)

        // Use tiny transparent bitmaps to prevent the scrim from regenerating visible masks
        val transparent = createBitmap(1, 1, Bitmap.Config.ALPHA_8)

        sysUiScrimInstance.apply {
            setField("mHideSysUiScrim", true)
            try {
                setField("mTopMaskBitmap", transparent)
            } catch (_: Throwable) {
                setField("mTopScrim", transparent.toDrawable(mContext.resources))
            }
            setAnyField(transparent, "mBottomMask", "mBottomMaskBitmap")
            setAnyField(mTopMaskPaint, "mTopMaskPaint", "mWallpaperScrimPaint")
        }

        mRoot.invalidate()
    }
}