package com.drdisagree.pixellauncherenhanced.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_OVERRIDES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACKS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACK_APPLY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACK_MASK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_PACKS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager

object IconPackStore {

    data class LauncherApp(
        val component: ComponentName,
        val label: String,
        val icon: Drawable
    )

    private var dirty = false

    val hasPendingChanges: Boolean
        get() = dirty

    fun config() = IconPackManager.Config(
        iconPacks = IconPackManager.parseList(RPrefs.getString(ICON_PACKS, null)),
        themedIconPacks = IconPackManager.parseList(RPrefs.getString(THEMED_ICON_PACKS, null)),
        maskUnsupported = RPrefs.getBoolean(ICON_PACK_MASK, false),
        overrides = IconPackManager.parseOverrides(RPrefs.getString(ICON_OVERRIDES, null))
    )

    fun setIconPacks(packs: List<String>) {
        if (config().iconPacks == packs) return
        RPrefs.putString(ICON_PACKS, IconPackManager.serializeList(packs))
        dirty = true
    }

    fun setThemedIconPacks(packs: List<String>) {
        if (config().themedIconPacks == packs) return
        RPrefs.putString(THEMED_ICON_PACKS, IconPackManager.serializeList(packs))
        dirty = true
    }

    fun setMaskUnsupported(enabled: Boolean) {
        if (config().maskUnsupported == enabled) return
        RPrefs.putBoolean(ICON_PACK_MASK, enabled)
        dirty = true
    }

    fun setOverride(component: ComponentName, value: String?) {
        val key = component.flattenToString()
        val overrides = config().overrides.toMutableMap()
        if (overrides[key] == value && value != IconPackManager.OVERRIDE_CUSTOM) return

        if (value == null) overrides.remove(key) else overrides[key] = value
        if (value != IconPackManager.OVERRIDE_CUSTOM) RPrefs.clearPref(IconPackManager.customIconKey(key))

        RPrefs.putString(ICON_OVERRIDES, IconPackManager.serializeOverrides(overrides))
        dirty = true
    }

    fun setCustomIcon(component: ComponentName, bitmap: Bitmap) {
        val key = component.flattenToString()
        RPrefs.putString(IconPackManager.customIconKey(key), IconPackManager.encodeBitmap(bitmap))
        setOverride(component, IconPackManager.OVERRIDE_CUSTOM)
    }

    fun customIcon(component: String): Bitmap? {
        return IconPackManager.decodeBitmap(RPrefs.getString(IconPackManager.customIconKey(component), null))
    }

    fun pruneMissingPackOverrides(context: Context): Boolean {
        val overrides = config().overrides
        val packageManager = context.packageManager
        val pruned = IconPackManager.withoutMissingPacks(overrides) { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
        if (pruned.size == overrides.size) return false

        RPrefs.putString(ICON_OVERRIDES, IconPackManager.serializeOverrides(pruned))
        return true
    }

    fun applyIfChanged(): Long? {
        if (!dirty) return null
        dirty = false
        val token = System.currentTimeMillis()
        RPrefs.putLong(ICON_PACK_APPLY, token)
        return token
    }

    fun launcherApps(context: Context): List<LauncherApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return packageManager.queryIntentActivities(intent, 0)
            .map { info ->
                LauncherApp(
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    label = info.loadLabel(packageManager).toString(),
                    icon = info.activityInfo.loadIcon(packageManager)
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    fun pinnedShortcuts(): List<IconPackManager.PinnedShortcut> =
        IconPackManager.parseShortcuts(RPrefs.getString(PINNED_SHORTCUTS, null))

    fun requestPinnedShortcuts() {
        RPrefs.putLong(PINNED_SHORTCUTS_REQUEST, System.currentTimeMillis())
    }

    fun shortcutIcon(context: Context, shortcut: IconPackManager.PinnedShortcut): Drawable? {
        IconPackManager.decodeBitmap(shortcut.icon)?.let { return BitmapDrawable(context.resources, it) }
        return runCatching { context.packageManager.getApplicationIcon(shortcut.packageName) }.getOrNull()
    }

    fun previewShortcutIcon(context: Context, shortcut: IconPackManager.PinnedShortcut, config: IconPackManager.Config): Drawable? {
        val original = shortcutIcon(context, shortcut)
        if (shortcut.component.flattenToString() !in config.overrides) return original

        return runCatching {
            IconPackManager.resolve(
                context = context,
                component = shortcut.component,
                config = config,
                density = context.resources.displayMetrics.densityDpi,
                customIcon = { customIcon(it) },
                original = { original }
            )?.drawable
        }.getOrNull() ?: original
    }

    fun previewIcon(context: Context, app: LauncherApp, config: IconPackManager.Config): Drawable {
        val themedMode = RPrefs.getBoolean(HOME_THEMED_ICONS)
        val density = context.resources.displayMetrics.densityDpi
        val hasOverride = app.component.flattenToString() in config.overrides

        val colors by lazy { themedColors(context) }

        if (themedMode && !hasOverride) {
            runCatching { IconPackManager.themedLayer(context, app.component, config, density) }
                .getOrNull()
                ?.let { return IconPackManager.themedPreview(context, it, colors) }
        }

        val icon = runCatching { resolveIcon(context, app, config)?.drawable }.getOrNull() ?: app.icon

        if (themedMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (icon as? AdaptiveIconDrawable)?.monochrome?.let { return IconPackManager.themedPreview(context, it, colors) }
        }

        return icon
    }

    @SuppressLint("DiscouragedApi")
    private fun themedColors(context: Context): Pair<Int, Int> {
        val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        if (RPrefs.getBoolean(THEMED_ICON_CUSTOM_COLOR)) {
            return if (night) {
                RPrefs.getInt(THEMED_ICON_CUSTOM_BG_COLOR_DARK, Color.BLACK) to RPrefs.getInt(THEMED_ICON_CUSTOM_FG_COLOR_DARK, Color.WHITE)
            } else {
                RPrefs.getInt(THEMED_ICON_CUSTOM_BG_COLOR_LIGHT, Color.WHITE) to RPrefs.getInt(THEMED_ICON_CUSTOM_FG_COLOR_LIGHT, Color.BLACK)
            }
        }

        listOf(PIXEL_LAUNCHER_PACKAGE, LAUNCHER3_PACKAGE).forEach { launcher ->
            runCatching {
                val resources = context.createPackageContext(launcher, 0).resources
                val background = resources.getIdentifier("themed_icon_background_color", "color", launcher)
                val foreground = resources.getIdentifier("themed_icon_color", "color", launcher)
                if (background != 0 && foreground != 0) {
                    return resources.getColor(background, null) to resources.getColor(foreground, null)
                }
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (night) {
                context.getColor(android.R.color.system_accent1_800) to context.getColor(android.R.color.system_accent1_100)
            } else {
                context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_accent1_700)
            }
        } else {
            if (night) Color.BLACK to Color.WHITE else Color.WHITE to Color.BLACK
        }
    }

    fun resolveIcon(context: Context, app: LauncherApp, config: IconPackManager.Config): IconPackManager.Resolved? {
        return IconPackManager.resolve(
            context = context,
            component = app.component,
            config = config,
            density = context.resources.displayMetrics.densityDpi,
            customIcon = { customIcon(it) },
            original = { app.icon.constantState?.newDrawable()?.mutate() ?: app.icon }
        )
    }
}
