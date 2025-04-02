package com.drdisagree.pixellauncherenhanced.data.common

import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector

object Constants {

    // Shared Preference File
    const val SHARED_PREFERENCES = "${BuildConfig.APPLICATION_ID}_preferences"

    // System packages
    const val FRAMEWORK_PACKAGE = "android"
    const val PIXEL_LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"
    const val LAUNCHER3_PACKAGE = "com.android.launcher3"

    // Preferences
    const val VIBRATE_UI = "vibrate_ui"
    const val XPOSED_HOOK_CHECK = "xposed_hook_check"
    const val ACTION_HOOK_CHECK_REQUEST = "${BuildConfig.APPLICATION_ID}.ACTION_HOOK_CHECK_REQUEST"
    const val ACTION_HOOK_CHECK_RESULT = "${BuildConfig.APPLICATION_ID}.ACTION_HOOK_CHECK_RESULT"
    const val FORCE_THEMED_ICONS = "xposed_forcethemedicons"
    const val APP_DRAWER_THEMED_ICONS = "xposed_appdrawerthemedicons"
    const val APP_DRAWER_BACKGROUND_OPACITY = "xposed_appdrawerbackgroundopacity"
    const val RECENTS_BACKGROUND_OPACITY = "xposed_recentsbackgroundopacity"
    const val DISABLE_RECENTS_LIVE_TILE = "xposed_disablerecentslivetile"
    const val DOUBLE_TAP_TO_SLEEP = "xposed_doubletaptosleep"
    const val LAUNCHER_HIDE_STATUSBAR = "xposed_launcherhidestatusbar"
    const val LAUNCHER_HIDE_TOP_SHADOW = "xposed_launcherhidetopshadow"
    const val DESKTOP_ICON_LABELS = "xposed_desktopiconlabels"
    const val APP_DRAWER_ICON_LABELS = "xposed_appdrawericonlabels"
    const val HIDE_AT_A_GLANCE = "xposed_hideataglance"
    const val DESKTOP_SEARCH_BAR = "xposed_desktopsearchbar"
    const val DESKTOP_DOCK_SPACING = "xposed_desktopdockspacing"
    const val LAUNCHER_ICON_SIZE = "xposed_launchericonsize"
    const val LAUNCHER_TEXT_SIZE = "xposed_launchertextsize"
    const val RESTART_LAUNCHER = "xposed_restartlauncher"
    const val DEVELOPER_OPTIONS = "xposed_developeroptions"
    const val ENTRY_IN_LAUNCHER_SETTINGS = "xposed_entryinlaunchersettings"
    const val LOCK_LAYOUT = "xposed_locklayout"

    val PREF_UPDATE_EXCLUSIONS = listOf(
        BootLoopProtector.LOAD_TIME_KEY_KEY,
        BootLoopProtector.PACKAGE_STRIKE_KEY_KEY,
    )
}