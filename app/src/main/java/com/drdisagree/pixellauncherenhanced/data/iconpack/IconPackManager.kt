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
        val overrides: Map<String, String> = emptyMap()
    ) {
        val isActive: Boolean
            get() = iconPacks.isNotEmpty() || themedIconPacks.isNotEmpty() || overrides.isNotEmpty()
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
    private const val SIGNATURE_VERSION = 6

    const val OVERRIDE_ORIGINAL = "original"
    const val OVERRIDE_CUSTOM = "custom"
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
            OVERRIDE_CUSTOM -> customIcon(key)?.let { bitmap ->
                return Resolved(adaptiveFromImage(context, bitmap), Source.CUSTOM)
            }

            else -> if (override.startsWith(OVERRIDE_PACK_PREFIX)) {
                val (pkg, name) = override.removePrefix(OVERRIDE_PACK_PREFIX).split('|', limit = 2)
                    .let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
                pack(context, pkg)?.loadDrawable(name, density)?.let {
                    return Resolved(PackIconDrawable.wrap(context, it), Source.PACK, pkg)
                }
            }
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

        val base = icon as? AdaptiveIconDrawable ?: AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT),
            InsetDrawable(icon, AdaptiveIconDrawable.getExtraInsetFraction() / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction()))
        )

        return AdaptiveIconDrawable(base.background, base.foreground, monochrome)
    }

    fun adaptiveFromImage(context: Context, bitmap: Bitmap): Drawable {
        val extraInset = AdaptiveIconDrawable.getExtraInsetFraction()
        val inset = extraInset / (1 + 2 * extraInset)

        return AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT),
            InsetDrawable(BitmapDrawable(context.resources, bitmap), inset)
        )
    }

    fun signature(config: Config, customIconKeys: Collection<String>, themedMode: Boolean): String {
        val raw = buildString {
            append(SIGNATURE_VERSION).append('|')
            append(themedMode).append('|')
            append(config.iconPacks.joinToString(","))
            append('|').append(config.themedIconPacks.joinToString(","))
            append('|').append(config.maskUnsupported)
            append('|').append(config.overrides.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
            append('|').append(customIconKeys.sorted().joinToString(","))
        }
        return "ple:" + hash(raw).take(12)
    }

    fun overrideForPack(packageName: String, drawable: String) = "$OVERRIDE_PACK_PREFIX$packageName|$drawable"

    fun overridePackage(value: String): String? {
        if (!value.startsWith(OVERRIDE_PACK_PREFIX)) return null
        return value.removePrefix(OVERRIDE_PACK_PREFIX).substringBefore('|')
    }

    fun withoutMissingPacks(overrides: Map<String, String>, isInstalled: (String) -> Boolean): Map<String, String> {
        return overrides.filterValues { value -> overridePackage(value)?.let(isInstalled) ?: true }
    }

    fun customIconKey(component: String) = "xposed_customicon_" + hash(component).take(16)

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
