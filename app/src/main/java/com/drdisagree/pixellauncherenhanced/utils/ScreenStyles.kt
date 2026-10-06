package com.drdisagree.pixellauncherenhanced.utils

import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.enums.TilePalette
import com.drdisagree.pixellauncherenhanced.data.model.ScreenStyle
import com.google.android.material.shape.MaterialShapes

object ScreenStyles {

    val HOME_SCREEN = ScreenStyle(
        R.string.fragment_home_screen_title, R.drawable.ic_home_screen,
        TilePalette.PRIMARY, MaterialShapes.COOKIE_9, MaterialShapes.CLOVER_8
    )
    val APP_DRAWER = ScreenStyle(
        R.string.fragment_app_drawer_title, R.drawable.ic_app_drawer,
        TilePalette.SECONDARY, MaterialShapes.SUNNY, MaterialShapes.VERY_SUNNY
    )
    val ICONS = ScreenStyle(
        R.string.fragment_icons_title, R.drawable.ic_icons,
        TilePalette.TERTIARY, MaterialShapes.COOKIE_6, MaterialShapes.FLOWER
    )
    val GESTURES = ScreenStyle(
        R.string.fragment_gestures_title, R.drawable.ic_gestures,
        TilePalette.TERTIARY, MaterialShapes.SOFT_BURST, MaterialShapes.BURST
    )
    val RECENTS = ScreenStyle(
        R.string.fragment_recents_title, R.drawable.ic_recents,
        TilePalette.SECONDARY, MaterialShapes.GEM, MaterialShapes.PENTAGON
    )
    val BACKUP = ScreenStyle(
        R.string.fragment_backup_title, R.drawable.ic_backup_restore,
        TilePalette.NEUTRAL, MaterialShapes.CLOVER_4, MaterialShapes.PUFFY
    )
    val ADVANCED = ScreenStyle(
        R.string.fragment_advanced_title, R.drawable.ic_advanced,
        TilePalette.NEUTRAL, MaterialShapes.PUFFY_DIAMOND, MaterialShapes.SOFT_BOOM
    )
    val ABOUT = ScreenStyle(
        R.string.fragment_about_title, R.drawable.ic_about,
        TilePalette.NEUTRAL, MaterialShapes.PIXEL_CIRCLE, MaterialShapes.COOKIE_12
    )
}
