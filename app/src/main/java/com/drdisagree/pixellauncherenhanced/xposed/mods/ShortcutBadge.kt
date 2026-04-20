package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.REMOVE_ICON_BADGE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadIcons
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class ShortcutBadge(context: Context) : ModPack(context) {

    private var removeBadge = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            removeBadge = getBoolean(REMOVE_ICON_BADGE, false)
        }

        when (key.firstOrNull()) {
            REMOVE_ICON_BADGE -> reloadIcons()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val bubbleTextViewClass = findClass("com.android.launcher3.BubbleTextView")
        val bitmapInfoClass = findClass("com.android.launcher3.icons.BitmapInfo")

        bubbleTextViewClass
            .hookConstructor()
            .runAfter { param ->
                if (!removeBadge) return@runAfter

                param.thisObject.setField("mHideBadge", true)
            }

        bubbleTextViewClass
            .hookMethod("setHideBadge")
            .suppressError()
            .runAfter { param ->
                if (!removeBadge) return@runAfter

                param.thisObject.setField("mHideBadge", true)
            }

        try {
            bitmapInfoClass
                .hookMethod("applyFlags")
                .throwError()
                .runAfter { param ->
                    if (!removeBadge) return@runAfter

                    val fastBitmapDrawable = param.args[1]
                    fastBitmapDrawable.callMethod("setBadge", null)
                }
        } catch (_: Throwable) {
            bitmapInfoClass
                .hookMethod("newIcon")
                .runAfter { param ->
                    if (!removeBadge) return@runAfter

                    val fastBitmapDrawable = param.result
                    fastBitmapDrawable.setField("badge", null)
                    fastBitmapDrawable.callMethod("updateFilter")
                }
        }
    }
}