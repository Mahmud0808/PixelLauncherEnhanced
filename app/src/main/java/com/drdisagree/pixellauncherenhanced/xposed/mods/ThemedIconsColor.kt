package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.resParams
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadIcons
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class ThemedIconsColor(context: Context) : ModPack(context) {

    private var mCustomThemedIconColor = false
    private var mIconFgColorLight = Color.BLACK
    private var mIconBgColorLight = Color.WHITE
    private var mIconFgColorDark = Color.WHITE
    private var mIconBgColorDark = Color.BLACK
    private var mFolderColorLight = Color.WHITE
    private var mFolderColorDark = Color.BLACK
    private var packageName: String? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            mCustomThemedIconColor = getBoolean(THEMED_ICON_CUSTOM_COLOR, false)
            mIconFgColorLight = getInt(THEMED_ICON_CUSTOM_FG_COLOR_LIGHT, Color.BLACK)
            mIconBgColorLight = getInt(THEMED_ICON_CUSTOM_BG_COLOR_LIGHT, Color.WHITE)
            mIconFgColorDark = getInt(THEMED_ICON_CUSTOM_FG_COLOR_DARK, Color.WHITE)
            mIconBgColorDark = getInt(THEMED_ICON_CUSTOM_BG_COLOR_DARK, Color.BLACK)
            mFolderColorLight = getInt(FOLDER_CUSTOM_COLOR_LIGHT, Color.WHITE)
            mFolderColorDark = getInt(FOLDER_CUSTOM_COLOR_DARK, Color.BLACK)
        }

        when (key.firstOrNull()) {
            THEMED_ICON_CUSTOM_COLOR,
            THEMED_ICON_CUSTOM_FG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_BG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_FG_COLOR_DARK,
            THEMED_ICON_CUSTOM_BG_COLOR_DARK,
            FOLDER_CUSTOM_COLOR_LIGHT,
            FOLDER_CUSTOM_COLOR_DARK -> {
                replaceResources(packageName)
                reloadIcons()
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        packageName = loadPackageParam.packageName
        replaceResources(packageName)

        val sdCardAvailableReceiverClass =
            findClass("com.android.launcher3.model.SdCardAvailableReceiver")

        sdCardAvailableReceiverClass
            .hookMethod("onReceive")
            .parameters(
                Context::class.java,
                Intent::class.java
            )
            .runAfter { param ->
                val intent = param.args[1] as Intent

                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    replaceResources(packageName)
                    reloadIcons()
                }
            }
    }

    @SuppressLint("DiscouragedApi")
    private fun replaceResources(packageName: String?) {
        if (!mCustomThemedIconColor || packageName == null) return

        val resParam = resParams[packageName] ?: return

        val isDarkTheme = (mContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        mContext.resources.getIdentifier(
            "themed_icon_background_color",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(
                it,
                if (isDarkTheme) mIconBgColorDark else mIconBgColorLight
            )
        }
        mContext.resources.getIdentifier(
            "qsb_icon_tint_quaternary_mono",
            "color",
            packageName
        ).takeIf { it != 0 } ?: mContext.resources.getIdentifier(
            "themed_icon_color",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(
                it,
                if (isDarkTheme) mIconFgColorDark else mIconFgColorLight
            )
        }

        mContext.resources.getIdentifier(
            "themed_badge_icon_background_color",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(
                it,
                if (isDarkTheme) mIconBgColorDark else mIconBgColorLight
            )
        }
        mContext.resources.getIdentifier(
            "themed_badge_icon_color",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(
                it,
                if (isDarkTheme) mIconFgColorDark else mIconFgColorLight
            )
        }

        mContext.resources.getIdentifier(
            "folder_preview_light",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(it, mFolderColorLight)
        }
        mContext.resources.getIdentifier(
            "folder_preview_dark",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(it, mFolderColorDark)
        }
        mContext.resources.getIdentifier(
            "folder_background_light",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(it, mFolderColorLight)
        }
        mContext.resources.getIdentifier(
            "folder_background_dark",
            "color",
            packageName
        ).takeIf { it != 0 }?.let {
            resParam.res.setReplacement(it, mFolderColorDark)
        }
    }
}