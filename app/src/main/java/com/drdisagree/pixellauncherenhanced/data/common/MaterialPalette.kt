package com.drdisagree.pixellauncherenhanced.data.common

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import androidx.annotation.StringRes
import com.drdisagree.pixellauncherenhanced.R

object MaterialPalette {

    enum class Group(@param:StringRes val label: Int) {
        PRIMARY(R.string.material_group_primary),
        SECONDARY(R.string.material_group_secondary),
        TERTIARY(R.string.material_group_tertiary),
        SURFACE(R.string.material_group_surface)
    }

    enum class Role(
        val id: String,
        val group: Group,
        @param:StringRes val label: Int,
        private val light: String,
        private val dark: String
    ) {
        PRIMARY("primary", Group.PRIMARY, R.string.material_role_primary, "system_accent1_600", "system_accent1_200"),
        ON_PRIMARY("on_primary", Group.PRIMARY, R.string.material_role_on_primary, "system_accent1_0", "system_accent1_800"),
        PRIMARY_CONTAINER("primary_container", Group.PRIMARY, R.string.material_role_primary_container, "system_accent1_100", "system_accent1_700"),
        ON_PRIMARY_CONTAINER("on_primary_container", Group.PRIMARY, R.string.material_role_on_primary_container, "system_accent1_900", "system_accent1_100"),
        SECONDARY("secondary", Group.SECONDARY, R.string.material_role_secondary, "system_accent2_600", "system_accent2_200"),
        ON_SECONDARY("on_secondary", Group.SECONDARY, R.string.material_role_on_secondary, "system_accent2_0", "system_accent2_800"),
        SECONDARY_CONTAINER("secondary_container", Group.SECONDARY, R.string.material_role_secondary_container, "system_accent2_100", "system_accent2_700"),
        ON_SECONDARY_CONTAINER("on_secondary_container", Group.SECONDARY, R.string.material_role_on_secondary_container, "system_accent2_900", "system_accent2_100"),
        TERTIARY("tertiary", Group.TERTIARY, R.string.material_role_tertiary, "system_accent3_600", "system_accent3_200"),
        ON_TERTIARY("on_tertiary", Group.TERTIARY, R.string.material_role_on_tertiary, "system_accent3_0", "system_accent3_800"),
        TERTIARY_CONTAINER("tertiary_container", Group.TERTIARY, R.string.material_role_tertiary_container, "system_accent3_100", "system_accent3_700"),
        ON_TERTIARY_CONTAINER("on_tertiary_container", Group.TERTIARY, R.string.material_role_on_tertiary_container, "system_accent3_900", "system_accent3_100"),
        SURFACE("surface", Group.SURFACE, R.string.material_role_surface, "system_neutral1_10", "system_neutral1_900"),
        ON_SURFACE("on_surface", Group.SURFACE, R.string.material_role_on_surface, "system_neutral1_900", "system_neutral1_100"),
        SURFACE_VARIANT("surface_variant", Group.SURFACE, R.string.material_role_surface_variant, "system_neutral2_100", "system_neutral2_700"),
        ON_SURFACE_VARIANT("on_surface_variant", Group.SURFACE, R.string.material_role_on_surface_variant, "system_neutral2_700", "system_neutral2_200"),
        SURFACE_CONTAINER_LOWEST("surface_container_lowest", Group.SURFACE, R.string.material_role_surface_container_lowest, "system_neutral1_0", "system_neutral1_1000"),
        SURFACE_CONTAINER_LOW("surface_container_low", Group.SURFACE, R.string.material_role_surface_container_low, "system_neutral1_50", "system_neutral1_900"),
        SURFACE_CONTAINER("surface_container", Group.SURFACE, R.string.material_role_surface_container, "system_neutral1_100", "system_neutral1_800"),
        SURFACE_CONTAINER_HIGH("surface_container_high", Group.SURFACE, R.string.material_role_surface_container_high, "system_neutral2_100", "system_neutral2_800"),
        SURFACE_CONTAINER_HIGHEST("surface_container_highest", Group.SURFACE, R.string.material_role_surface_container_highest, "system_neutral2_200", "system_neutral2_700");

        fun resourceNames(dark: Boolean): List<String> = listOfNotNull(
            "system_${id}_${if (dark) "dark" else "light"}".takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
            if (dark) this.dark else light
        )
    }

    private const val SOURCE_SUFFIX = "_material"
    const val CUSTOM = "custom"

    private val DEFAULTS = mapOf(
        Constants.THEMED_ICON_CUSTOM_FG_COLOR_LIGHT to (Color.BLACK to Role.ON_PRIMARY_CONTAINER),
        Constants.THEMED_ICON_CUSTOM_BG_COLOR_LIGHT to (Color.WHITE to Role.PRIMARY_CONTAINER),
        Constants.FOLDER_CUSTOM_COLOR_LIGHT to (Color.WHITE to Role.SURFACE_CONTAINER_HIGH),
        Constants.THEMED_ICON_CUSTOM_FG_COLOR_DARK to (Color.WHITE to Role.ON_PRIMARY_CONTAINER),
        Constants.THEMED_ICON_CUSTOM_BG_COLOR_DARK to (Color.BLACK to Role.PRIMARY_CONTAINER),
        Constants.FOLDER_CUSTOM_COLOR_DARK to (Color.BLACK to Role.SURFACE_CONTAINER_HIGH)
    )

    fun effectiveRole(colorKey: String, source: String?, color: Int?): Role? {
        if (source == CUSTOM) return null
        role(source)?.let { return it }
        val (defaultColor, defaultRole) = DEFAULTS[colorKey] ?: return null
        return defaultRole.takeIf { color == null || color == defaultColor }
    }

    fun sourceKey(colorKey: String) = colorKey + SOURCE_SUFFIX

    fun isDarkKey(colorKey: String) = colorKey.contains("dark", ignoreCase = true)

    fun role(id: String?): Role? = Role.entries.firstOrNull { it.id == id }

    @SuppressLint("DiscouragedApi")
    fun resourceId(role: Role, dark: Boolean): Int {
        val system = Resources.getSystem()
        return role.resourceNames(dark).firstNotNullOfOrNull { name ->
            system.getIdentifier(name, "color", "android").takeIf { it != 0 }
        } ?: 0
    }

    fun resolve(resources: Resources, role: Role, dark: Boolean): Int? {
        val id = resourceId(role, dark).takeIf { it != 0 } ?: return null
        return runCatching { resources.getColor(id, null) }.getOrNull()
    }
}
