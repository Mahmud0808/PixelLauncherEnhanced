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
    const val ACTION_APP_LIST_UPDATED = "${BuildConfig.APPLICATION_ID}.ACTION_APP_LIST_UPDATED"
    const val FORCE_THEMED_ICONS = "xposed_forcethemedicons"
    const val APP_DRAWER_THEMED_ICONS = "xposed_appdrawerthemedicons"
    const val APP_DRAWER_BACKGROUND_OPACITY = "xposed_appdrawerbackgroundopacity"
    const val RECENTS_BACKGROUND_OPACITY = "xposed_recentsbackgroundopacity"
    const val DISABLE_RECENTS_LIVE_TILE = "xposed_disablerecentslivetile"
    const val DOUBLE_TAP_TO_SLEEP = "xposed_doubletaptosleep"
    const val ALLOW_WALLPAPER_ZOOMING = "xposed_allowwallpaperzooming"
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
    const val DRAWER_SEARCH_BAR = "xposed_drawersearchbar"
    const val RECENTS_CLEAR_ALL_BUTTON = "xposed_recentsclearallbutton"
    const val FIXED_RECENTS_BUTTONS_WIDTH = "xposed_fixedrecentsbuttonswidth"
    const val DESKTOP_GRID_ROWS = "xposed_desktopgridrows"
    const val DESKTOP_GRID_COLUMNS = "xposed_desktopgridcolumns"
    const val APP_DRAWER_GRID_COLUMNS = "xposed_appdrawergridcolumns"
    const val APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER = "xposed_appdrawergridrowheightmultiplier"
    const val APP_BLOCK_LIST = "xposed_appblocklist"
    const val SEARCH_HIDDEN_APPS = "xposed_searchhiddenapps"
    const val REMOVE_ICON_BADGE = "xposed_removeiconbadge"
    const val RECENTS_REMOVE_SCREENSHOT_BUTTON = "xposed_recentsremovescreenshotbutton"
    const val THEMED_ICON_CUSTOM_COLOR = "xposed_themediconcustomcolor"
    const val THEMED_ICON_CUSTOM_FG_COLOR_LIGHT = "xposed_themediconcustomfgcolorlight"
    const val THEMED_ICON_CUSTOM_BG_COLOR_LIGHT = "xposed_themediconcustombgcolorlight"
    const val THEMED_ICON_CUSTOM_FG_COLOR_DARK = "xposed_themediconcustomfgcolordark"
    const val THEMED_ICON_CUSTOM_BG_COLOR_DARK = "xposed_themediconcustombgcolordark"
    const val FOLDER_CUSTOM_COLOR_LIGHT = "xposed_foldercustomcolorlight"
    const val FOLDER_CUSTOM_COLOR_DARK = "xposed_foldercustomcolordark"
    const val DESKTOP_SEARCH_BAR_OPACITY = "xposed_desktopsearchbaropacity"
    const val FREEFORM_GESTURE = "xposed_startfreeformbygesture"
    const val FREEFORM_MODE = "xposed_freeform_mode"
    const val FREEFORM_GESTURE_PROGRESS = "xposed_startfreeformprogress"
    const val HIDE_GESTURE_PILL = "xposed_hidegesturepill"
    const val QUICK_LAUNCH = "xposed_quicklaunch"

    val PREF_UPDATE_EXCLUSIONS = listOf(
        BootLoopProtector.LOAD_TIME_KEY_KEY,
        BootLoopProtector.PACKAGE_STRIKE_KEY_KEY,
    )
}