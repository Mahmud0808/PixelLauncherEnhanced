package com.drdisagree.pixellauncherenhanced.data.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.util.Base64
import com.drdisagree.pixellauncherenhanced.data.enums.IconSlot
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object IconPackManager {

    data class IconPackInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable?
    )

    data class Config(
        val iconPacks: List<String> = emptyList(),
        val themedIconPacks: List<String> = emptyList(),
        val maskUnsupported: Boolean = false,
        val overrides: Map<String, String> = emptyMap(),
        val homeOverrides: Map<String, String> = emptyMap(),
        val themedOverrides: Map<String, String> = emptyMap(),
        val homeThemedOverrides: Map<String, String> = emptyMap(),
        val labels: Map<String, String> = emptyMap()
    ) {
        val isActive: Boolean
            get() = iconPacks.isNotEmpty() || themedIconPacks.isNotEmpty() || overrides.isNotEmpty() ||
                    homeOverrides.isNotEmpty() || themedOverrides.isNotEmpty() || homeThemedOverrides.isNotEmpty()

        fun overridesFor(slot: IconSlot): Map<String, String> = when (slot) {
            IconSlot.DRAWER -> overrides
            IconSlot.HOME -> homeOverrides
            IconSlot.THEMED -> themedOverrides
            IconSlot.THEMED_HOME -> homeThemedOverrides
        }

        fun hasHomeOverride(component: String) = component in homeOverrides || component in homeThemedOverrides

        fun isCustomized(component: String) =
            IconSlot.entries.any { component in overridesFor(it) } || component in labels
    }

    enum class Source { PACK, MASK, CUSTOM, ORIGINAL }

    data class Resolved(val drawable: Drawable, val source: Source, val packageName: String? = null)

    private val iconPackActions = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.dlto.atom.launcher.THEME"
    )

    private const val THEMED_ICON_PACK_ACTION = "app.lawnchair.icons.THEMED_ICON"
    private const val THEMED_ICON_INSET = 0.28f
    private const val SHORTCUT_PREFIX = "shortcut:"
    private const val LEGACY_SHORTCUT_PREFIX = "legacy:"
    private const val SIGNATURE_VERSION = 8
    private const val CUSTOM_ICON_KEY_PREFIX = "xposed_customicon_"

    const val OVERRIDE_ORIGINAL = "original"
    const val OVERRIDE_CUSTOM = "custom"
    const val OVERRIDE_NONE = "none"
    private const val OVERRIDE_PACK_PREFIX = "pack:"

    private val packs = ConcurrentHashMap<String, IconPack>()
    private val failedPacks = ConcurrentHashMap.newKeySet<String>()

    fun installedIconPacks(context: Context) = queryPacks(context, iconPackActions)

    fun installedThemedIconPacks(context: Context) = queryPacks(context, listOf(THEMED_ICON_PACK_ACTION))

    private fun queryPacks(context: Context, actions: List<String>): List<IconPackInfo> {
        val packageManager = context.packageManager

        return actions
            .flatMap { action ->
                runCatching {
                    packageManager.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
                }.getOrDefault(emptyList())
            }
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { info ->
                IconPackInfo(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString(),
                    icon = runCatching { info.loadIcon(packageManager) }.getOrNull()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun pack(context: Context, packageName: String): IconPack? {
        packs[packageName]?.let { return it }
        if (packageName in failedPacks) return null

        return runCatching { IconPack(context, packageName) }
            .onSuccess { packs[packageName] = it }
            .onFailure { failedPacks.add(packageName) }
            .getOrNull()
    }

    fun invalidate(packageName: String) {
        packs.remove(packageName)
        failedPacks.remove(packageName)
    }

    fun clearCache() {
        packs.clear()
        failedPacks.clear()
    }

    fun resolve(
        context: Context,
        component: ComponentName,
        config: Config,
        density: Int,
        customIcon: (String) -> Bitmap?,
        original: () -> Drawable?
    ): Resolved? {
        val key = component.flattenToString()

        when (val override = config.overrides[key]) {
            null -> Unit
            OVERRIDE_ORIGINAL -> return null
            else -> resolveValue(context, override, density, customIcon(key))?.let { return it }
        }

        config.iconPacks.forEach { pkg ->
            pack(context, pkg)?.iconFor(component, density)?.let {
                return Resolved(PackIconDrawable.wrap(context, it), Source.PACK, pkg)
            }
        }

        if (config.maskUnsupported) {
            val topPack = config.iconPacks.firstOrNull()?.let { pack(context, it) }
            val base = original()

            if (topPack != null && base != null) {
                val size = (context.resources.displayMetrics.density * 96).toInt()
                topPack.maskIcon(base, size, key.hashCode())?.let {
                    return Resolved(PackIconDrawable.wrap(context, it), Source.MASK, topPack.packageName)
                }
            }
        }

        return null
    }

    fun resolveValue(context: Context, value: String, density: Int, customBitmap: Bitmap?): Resolved? {
        if (value == OVERRIDE_CUSTOM) {
            return customBitmap?.let { Resolved(adaptiveFromImage(context, it), Source.CUSTOM) }
        }

        val (pkg, name) = packDrawable(value) ?: return null
        return pack(context, pkg)?.loadDrawable(name, density)?.let {
            Resolved(PackIconDrawable.wrap(context, it), Source.PACK, pkg)
        }
    }

    fun themedMonochrome(
        context: Context,
        component: ComponentName,
        value: String?,
        config: Config,
        density: Int,
        original: Drawable?,
        customBitmap: Bitmap?
    ): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        return when (value) {
            null -> themedLayer(context, component, config, density) ?: (original as? AdaptiveIconDrawable)?.monochrome
            OVERRIDE_NONE -> null
            OVERRIDE_ORIGINAL -> (original as? AdaptiveIconDrawable)?.monochrome
            OVERRIDE_CUSTOM -> customBitmap?.let { InsetDrawable(BitmapDrawable(context.resources, it), THEMED_ICON_INSET) }
            else -> packDrawable(value)?.let { (pkg, name) ->
                pack(context, pkg)?.loadDrawable(name, density)?.let { glyph ->
                    if (glyph is AdaptiveIconDrawable) glyph.monochrome ?: glyph.foreground else InsetDrawable(glyph, THEMED_ICON_INSET)
                }
            }
        }
    }

    fun withMonochrome(icon: Drawable, monochrome: Drawable): Drawable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return icon

        val base = icon as? AdaptiveIconDrawable ?: AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT),
            InsetDrawable(icon, AdaptiveIconDrawable.getExtraInsetFraction() / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction()))
        )

        return AdaptiveIconDrawable(base.background, base.foreground, monochrome)
    }

    fun withoutMonochrome(icon: Drawable): Drawable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return icon
        val adaptive = icon as? AdaptiveIconDrawable ?: return icon
        if (adaptive.monochrome == null) return icon
        return AdaptiveIconDrawable(adaptive.background, adaptive.foreground)
    }

    private fun packDrawable(value: String): Pair<String, String>? {
        if (!value.startsWith(OVERRIDE_PACK_PREFIX)) return null
        return value.removePrefix(OVERRIDE_PACK_PREFIX).split('|', limit = 2)
            .let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
    }

    fun themedLayer(context: Context, component: ComponentName, config: Config, density: Int): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        val packIcon = config.themedIconPacks.firstNotNullOfOrNull { pkg ->
            pack(context, pkg)?.iconFor(component, density)
        } ?: return null

        return if (packIcon is AdaptiveIconDrawable) {
            packIcon.monochrome ?: packIcon.foreground
        } else {
            InsetDrawable(packIcon, THEMED_ICON_INSET)
        }
    }

    fun themedPreview(context: Context, monochrome: Drawable, colors: Pair<Int, Int>): Drawable {
        val glyph = (monochrome.constantState?.newDrawable(context.resources) ?: monochrome).mutate().apply {
            setTint(colors.second)
        }

        return AdaptiveIconDrawable(ColorDrawable(colors.first), glyph)
    }

    fun withThemedIcon(context: Context, component: ComponentName, icon: Drawable, config: Config, density: Int): Drawable? {
        val monochrome = themedLayer(context, component, config, density) ?: return null
        return withMonochrome(icon, monochrome)
    }

    fun adaptiveFromImage(context: Context, bitmap: Bitmap): Drawable {
        val extraInset = AdaptiveIconDrawable.getExtraInsetFraction()
        val inset = extraInset / (1 + 2 * extraInset)

        return AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT),
            InsetDrawable(BitmapDrawable(context.resources, bitmap), inset)
        )
    }

    fun signature(config: Config, themedMode: Boolean): String {
        val raw = buildString {
            append(SIGNATURE_VERSION).append('|')
            append(themedMode).append('|')
            append(config.iconPacks.joinToString(","))
            append('|').append(config.themedIconPacks.joinToString(","))
            append('|').append(config.maskUnsupported)
        }
        return "ple:" + hash(raw).take(12)
    }

    fun packageStamps(config: Config, customHashes: Map<String, Int>, slots: List<IconSlot>): Map<String, String> {
        val entries = HashMap<String, MutableList<String>>()

        slots.forEach { slot ->
            config.overridesFor(slot).forEach { (component, value) ->
                val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return@forEach
                val custom = customHashes[slot.customKey(component)] ?: 0
                entries.getOrPut(pkg) { mutableListOf() }.add("${slot.name}|$component|$value|$custom")
            }
        }

        return entries.mapValues { (_, values) -> "ple:" + hash(values.sorted().joinToString(",")).take(10) }
    }

    fun overrideForPack(packageName: String, drawable: String) = "$OVERRIDE_PACK_PREFIX$packageName|$drawable"

    fun overridePackage(value: String): String? {
        if (!value.startsWith(OVERRIDE_PACK_PREFIX)) return null
        return value.removePrefix(OVERRIDE_PACK_PREFIX).substringBefore('|')
    }

    fun withoutMissingPacks(overrides: Map<String, String>, isInstalled: (String) -> Boolean): Map<String, String> {
        return overrides.filterValues { value -> overridePackage(value)?.let(isInstalled) ?: true }
    }

    data class PinnedShortcut(val packageName: String, val id: String, val label: String, val icon: String?) {
        val component: ComponentName
            get() = shortcutComponent(packageName, id)
    }

    fun shortcutComponent(packageName: String, id: String) = ComponentName(packageName, SHORTCUT_PREFIX + id)

    fun isShortcut(component: ComponentName) = component.className.startsWith(SHORTCUT_PREFIX)

    fun legacyShortcutId(intentUri: String) = LEGACY_SHORTCUT_PREFIX + hash(intentUri).take(16)

    fun serializeShortcuts(shortcuts: List<PinnedShortcut>): String = JSONArray().apply {
        shortcuts.forEach { shortcut ->
            put(JSONObject().apply {
                put("package", shortcut.packageName)
                put("id", shortcut.id)
                put("label", shortcut.label)
                shortcut.icon?.let { put("icon", it) }
            })
        }
    }.toString()

    fun parseShortcuts(json: String?): List<PinnedShortcut> = runCatching {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            PinnedShortcut(
                packageName = obj.getString("package"),
                id = obj.getString("id"),
                label = obj.optString("label"),
                icon = obj.optString("icon").takeIf { it.isNotEmpty() }
            )
        }
    }.getOrDefault(emptyList())

    fun customIconKey(component: String) = CUSTOM_ICON_KEY_PREFIX + hash(component).take(16)

    fun isCustomIconKey(key: String) = key.startsWith(CUSTOM_ICON_KEY_PREFIX)

    fun encodeBitmap(bitmap: Bitmap): String {
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeBitmap(encoded: String?): Bitmap? {
        if (encoded.isNullOrEmpty()) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    fun parseList(json: String?): List<String> = runCatching {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    fun serializeList(list: List<String>): String = JSONArray(list).toString()

    fun parseOverrides(json: String?): Map<String, String> = runCatching {
        val obj = JSONObject(json ?: "{}")
        obj.keys().asSequence().associateWith { obj.getString(it) }
    }.getOrDefault(emptyMap())

    fun serializeOverrides(map: Map<String, String>): String = JSONObject(map).toString()

    private fun hash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
