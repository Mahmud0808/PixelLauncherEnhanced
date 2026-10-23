package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.XResForwarder
import android.content.res.XResources
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.MaterialPalette
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.resParams
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadIcons
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class ThemedIconsColor(context: Context) : ModPack(context) {

    private class ColorChoice(val color: Int, val role: MaterialPalette.Role?, val dark: Boolean) {
        fun value(resources: Resources): Int = role?.let { MaterialPalette.resolve(resources, it, dark) } ?: color

        fun replacement(resources: Resources): Any {
            val id = role?.let { MaterialPalette.resourceId(it, dark) }?.takeIf { it != 0 } ?: return color
            return XResForwarder(resources, id)
        }
    }

    private var mCustomThemedIconColor = false
    private var iconFgLight = ColorChoice(Color.BLACK, null, false)
    private var iconBgLight = ColorChoice(Color.WHITE, null, false)
    private var iconFgDark = ColorChoice(Color.WHITE, null, true)
    private var iconBgDark = ColorChoice(Color.BLACK, null, true)
    private var folderLight = ColorChoice(Color.WHITE, null, false)
    private var folderDark = ColorChoice(Color.BLACK, null, true)
    private var packageName: String? = null
    private val replacedIds = HashSet<Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rebindLauncher = Runnable { reloadLauncher(mContext) }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            fun choice(colorKey: String, default: Int) = ColorChoice(
                getInt(colorKey, default),
                MaterialPalette.effectiveRole(
                    colorKey,
                    getString(MaterialPalette.sourceKey(colorKey), null),
                    if (contains(colorKey)) getInt(colorKey, default) else null
                ),
                MaterialPalette.isDarkKey(colorKey)
            )
            mCustomThemedIconColor = getBoolean(THEMED_ICON_CUSTOM_COLOR, false)
            iconFgLight = choice(THEMED_ICON_CUSTOM_FG_COLOR_LIGHT, Color.BLACK)
            iconBgLight = choice(THEMED_ICON_CUSTOM_BG_COLOR_LIGHT, Color.WHITE)
            iconFgDark = choice(THEMED_ICON_CUSTOM_FG_COLOR_DARK, Color.WHITE)
            iconBgDark = choice(THEMED_ICON_CUSTOM_BG_COLOR_DARK, Color.BLACK)
            folderLight = choice(FOLDER_CUSTOM_COLOR_LIGHT, Color.WHITE)
            folderDark = choice(FOLDER_CUSTOM_COLOR_DARK, Color.BLACK)
        }

        val changed = key.firstOrNull() ?: return
        if (changed == THEMED_ICON_CUSTOM_COLOR || COLOR_KEYS.any { changed == it || changed == MaterialPalette.sourceKey(it) }) {
            clearResources(packageName)
            if (mCustomThemedIconColor) replaceResources(packageName)
            reloadIcons()
            mainHandler.removeCallbacks(rebindLauncher)
            mainHandler.postDelayed(rebindLauncher, REBIND_DELAY)
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

        findClass("com.android.launcher3.icons.ThemedIconDrawable", suppressError = true)
            .hookMethod("getColors")
            .suppressError()
            .runAfter { param ->
                if (!mCustomThemedIconColor) return@runAfter
                val colors = param.result as? IntArray ?: return@runAfter
                val context = param.args.firstOrNull() as? Context ?: return@runAfter
                val dark = isDarkTheme(context)
                if (colors.isNotEmpty()) colors[0] = (if (dark) iconBgDark else iconBgLight).value(context.resources)
                if (colors.size > 1) colors[1] = (if (dark) iconFgDark else iconFgLight).value(context.resources)
            }
    }

    private fun isDarkTheme(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun replace(res: XResources, id: Int, choice: ColorChoice) {
        replacedIds.add(id)
        res.setReplacement(id, choice.replacement(mContext.resources))
    }

    private fun clearResources(packageName: String?) {
        val resParam = resParams[packageName] ?: return
        replacedIds.forEach { id -> runCatching { resParam.res.setReplacement(id, null) } }
        replacedIds.clear()
    }

    @SuppressLint("DiscouragedApi")
    private fun replaceResources(packageName: String?) {
        if (!mCustomThemedIconColor || packageName == null) return

        val resParam = resParams[packageName] ?: return
        val dark = isDarkTheme(mContext)
        val background = if (dark) iconBgDark else iconBgLight
        val foreground = if (dark) iconFgDark else iconFgLight

        fun colorId(name: String) = mContext.resources.getIdentifier(name, "color", packageName).takeIf { it != 0 }

        BACKGROUND_COLORS.forEach { name -> colorId(name)?.let { replace(resParam.res, it, background) } }
        FOREGROUND_COLORS.forEach { name -> colorId(name)?.let { replace(resParam.res, it, foreground) } }
        FOLDER_LIGHT_COLORS.forEach { name -> colorId(name)?.let { replace(resParam.res, it, folderLight) } }
        FOLDER_DARK_COLORS.forEach { name -> colorId(name)?.let { replace(resParam.res, it, folderDark) } }
    }

    companion object {
        private const val REBIND_DELAY = 300L
        private val COLOR_KEYS = listOf(
            THEMED_ICON_CUSTOM_FG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_BG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_FG_COLOR_DARK,
            THEMED_ICON_CUSTOM_BG_COLOR_DARK,
            FOLDER_CUSTOM_COLOR_LIGHT,
            FOLDER_CUSTOM_COLOR_DARK
        )
        private val BACKGROUND_COLORS = listOf(
            "themed_icon_background_color",
            "themed_icon_adaptive_background_color",
            "themed_badge_icon_background_color"
        )
        private val FOREGROUND_COLORS = listOf(
            "themed_icon_color",
            "qsb_icon_tint_quaternary_mono",
            "themed_badge_icon_color"
        )
        private val FOLDER_LIGHT_COLORS = listOf("folder_preview_light", "folder_background_light")
        private val FOLDER_DARK_COLORS = listOf("folder_preview_dark", "folder_background_dark")
    }
}
