package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.createBitmap
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_TOP_SHADOW
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.WeakHashMap

class TopShadow(context: Context) : ModPack(context) {

    private var removeTopShadow = false
    private var sysUiScrimInstance: Any? = null
    private val standIns = WeakHashMap<Any, Any>()

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            removeTopShadow = getBoolean(LAUNCHER_HIDE_TOP_SHADOW, false)
        }

        when (key.firstOrNull()) {
            LAUNCHER_HIDE_TOP_SHADOW -> Handler(Looper.getMainLooper()).post {
                if (removeTopShadow) hideScrim() else restoreScrim()
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val sysUiScrimClass = findClass("com.android.launcher3.graphics.SysUiScrim")

        sysUiScrimClass
            .hookConstructor()
            .runAfter { param ->
                sysUiScrimInstance = param.thisObject
                hideScrim()
            }

        sysUiScrimClass
            .hookMethod("onViewAttachedToWindow", "onViewDetachedFromWindow")
            .runBefore { param ->
                if (!removeTopShadow) return@runBefore

                if (param.method.name == "onViewAttachedToWindow") {
                    param.thisObject.setExtraField(ATTACH_SKIPPED, true)
                }
                param.result = null
            }

        sysUiScrimClass
            .hookMethod("createDitheredAlphaMask")
            .suppressError()
            .runAfter { param ->
                if (!removeTopShadow) return@runAfter

                val original = param.result as? Bitmap ?: return@runAfter
                param.result = transparentBitmap(original).also { standIns[it] = original }
            }
    }

    private fun hideScrim() {
        val scrim = sysUiScrimInstance ?: return
        if (!removeTopShadow) return

        @Suppress("UNCHECKED_CAST")
        val originals = scrim.getExtraFieldSilently(ORIGINALS) as? HashMap<String, Any?>
            ?: HashMap<String, Any?>().also { scrim.setExtraField(ORIGINALS, it) }

        MASK_FIELDS.forEach { name ->
            val current = scrim.getFieldSilently(name) ?: return@forEach
            if (standIns.containsKey(current)) return@forEach

            if (!originals.containsKey(name)) originals[name] = current
            val standIn: Any = when (current) {
                is Bitmap -> transparentBitmap(current)
                is Drawable -> ColorDrawable(Color.TRANSPARENT)
                else -> return@forEach
            }
            standIns[standIn] = current
            scrim.setFieldSilently(name, standIn)
        }

        (scrim.getAnyFieldSilently("mTopMaskPaint", "mWallpaperScrimPaint") as? Paint)?.let { paint ->
            if (!originals.containsKey(PAINT_COLOR)) originals[PAINT_COLOR] = paint.color
            paint.color = Color.rgb(0x22, 0x22, 0x22)
        }

        scrim.setFieldSilently("mHideSysUiScrim", true)
        (scrim.getFieldSilently("mRoot") as? View)?.invalidate()
    }

    private fun restoreScrim() {
        val scrim = sysUiScrimInstance ?: return

        @Suppress("UNCHECKED_CAST")
        val originals = scrim.getExtraFieldSilently(ORIGINALS) as? HashMap<String, Any?> ?: HashMap()

        MASK_FIELDS.forEach { name ->
            val current = scrim.getFieldSilently(name) ?: return@forEach
            val original = standIns.remove(current) ?: originals[name] ?: return@forEach
            scrim.setFieldSilently(name, original)
        }

        (scrim.getAnyFieldSilently("mTopMaskPaint", "mWallpaperScrimPaint") as? Paint)?.let { paint ->
            (originals[PAINT_COLOR] as? Int)?.let { paint.color = it }
        }
        originals.clear()

        val root = scrim.getFieldSilently("mRoot") as? View
        root?.let { scrim.setFieldSilently("mHideSysUiScrim", isWorkspaceDarkText(it.context)) }

        if (scrim.getExtraFieldSilently(ATTACH_SKIPPED) == true && root?.isAttachedToWindow == true) {
            scrim.setExtraField(ATTACH_SKIPPED, false)
            scrim.callMethodSilently("onViewAttachedToWindow", root)
        }

        scrim.callMethodSilently("reapplySysUiAlpha")
        root?.invalidate()
    }

    private fun transparentBitmap(source: Bitmap): Bitmap =
        createBitmap(source.width.coerceAtLeast(1), source.height.coerceAtLeast(1), Bitmap.Config.ALPHA_8)

    @SuppressLint("DiscouragedApi")
    private fun isWorkspaceDarkText(context: Context): Boolean {
        val attr = context.resources.getIdentifier("isWorkspaceDarkText", "attr", context.packageName)
        if (attr == 0) return false
        val value = TypedValue()
        return context.theme.resolveAttribute(attr, value, true) && value.data != 0
    }

    companion object {
        private const val ORIGINALS = "pleScrimOriginals"
        private const val ATTACH_SKIPPED = "pleScrimAttachSkipped"
        private const val PAINT_COLOR = "paintColor"
        private val MASK_FIELDS = listOf("mTopMaskBitmap", "mTopScrim", "mBottomMask", "mBottomMaskBitmap")
    }
}
