package com.drdisagree.pixellauncherenhanced.data.glass

import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SEARCH_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_TINT

enum class GlassTarget(
    val id: String,
    val styleKey: String,
    val blurKey: String,
    val tintKey: String
) {
    ICONS("icon", ICON_BACKGROUND, ICON_BACKGROUND_BLUR, ICON_BACKGROUND_TINT),
    FOLDERS("folder", FOLDER_BACKGROUND, FOLDER_BACKGROUND_BLUR, FOLDER_BACKGROUND_OPACITY),
    DOCK("dock", DOCK_BACKGROUND, DOCK_BLUR_RADIUS, DOCK_BLUR_TINT),
    SEARCH_BAR("searchbar", SEARCH_BAR_BACKGROUND, SEARCH_BAR_BLUR_RADIUS, SEARCH_BAR_BLUR_TINT),
    WIDGETS("widget", WIDGET_BACKGROUND, WIDGET_BLUR_RADIUS, WIDGET_BLUR_TINT),
    DRAWER_SEARCH("drawersearch", DRAWER_SEARCH_BACKGROUND, DRAWER_SEARCH_BLUR_RADIUS, DRAWER_SEARCH_BLUR_TINT);

    val refractionKey = "xposed_${id}glassrefraction"
    val depthKey = "xposed_${id}glassdepth"
    val dispersionKey = "xposed_${id}glassdispersion"
    val vibrancyKey = "xposed_${id}glassvibrancy"
    val rimKey = "xposed_${id}glassrim"
    val shadowKey = "xposed_${id}glassshadow"

    val liquidKeys = listOf(refractionKey, depthKey, dispersionKey, rimKey, shadowKey)
    val styleKeys = liquidKeys + vibrancyKey

    fun readStyle(readSlider: (String, Int) -> Int) = GlassStyle(
        refraction = readSlider(refractionKey, GlassStyle.DEFAULT_REFRACTION) / 100f,
        depth = readSlider(depthKey, GlassStyle.DEFAULT_DEPTH) / 100f,
        dispersion = readSlider(dispersionKey, GlassStyle.DEFAULT_DISPERSION) / 100f,
        vibrancy = readSlider(vibrancyKey, GlassStyle.DEFAULT_VIBRANCY) / 100f,
        rim = readSlider(rimKey, GlassStyle.DEFAULT_RIM) / 100f,
        shadow = readSlider(shadowKey, GlassStyle.DEFAULT_SHADOW) / 100f
    )

    companion object {
        fun forId(id: String?) = entries.firstOrNull { it.id == id }

        fun forStyleKey(key: String?) = entries.firstOrNull { key in it.styleKeys }
    }
}
