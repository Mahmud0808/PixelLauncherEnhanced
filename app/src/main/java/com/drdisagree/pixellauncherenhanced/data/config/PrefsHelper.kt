package com.drdisagree.pixellauncherenhanced.data.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FORCE_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_COVER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_TAP_FIRST_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEVELOPER_OPTIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_DOCK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_CORNER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_MARGIN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_STYLE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BOTTOM_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_TOP_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS_AT_BOTTOM
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS_ENABLED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_DARK_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FIXED_RECENTS_BUTTONS_WIDTH
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE_PROGRESS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_APPS_FROM_APP_DRAWER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_ARRANGEMENT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_AUTO_FILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_SWIPE_ACTION
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_CLEAR_ALL_BUTTON
import com.drdisagree.pixellauncherenhanced.data.common.Constants.POPUP_EDIT_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_DISABLE_SELECTION
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_MEMINFO
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_MEMINFO_CHIP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_MEMINFO_ZRAM
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_CLEAR_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.XPOSED_HOOK_CHECK
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs.getBoolean
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs.getSliderFloat
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs.getString
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs.getStringSet
import com.drdisagree.pixellauncherenhanced.ui.preferences.TwoTargetSwitchPreference
import com.drdisagree.pixellauncherenhanced.utils.AppUtils.isPixelLauncher

object PrefsHelper {

    fun isVisible(key: String?): Boolean {
        return when (key) {
            XPOSED_HOOK_CHECK -> false

            WIDGET_CLEAR_BACKGROUND -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    PanelBackground.supported(getString(WIDGET_BACKGROUND)) == PanelBackground.OFF

            APP_DRAWER_THEMED_ICONS,
            FORCE_THEMED_ICONS,
            THEMED_ICON_CUSTOM_COLOR -> getBoolean(HOME_THEMED_ICONS)

            FOLDER_COVER -> getBoolean(FOLDER_TAP_FIRST_APP)

            ICON_BACKGROUND -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getBoolean(HOME_THEMED_ICONS)

            FOLDER_BACKGROUND,
            WIDGET_BACKGROUND -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

            FOLDER_BACKGROUND_OPACITY,
            FOLDER_BACKGROUND_BLUR -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    PanelBackground.supported(getString(FOLDER_BACKGROUND)) != PanelBackground.OFF

            ICON_BACKGROUND_BLUR,
            ICON_BACKGROUND_TINT -> getBoolean(HOME_THEMED_ICONS) &&
                    PanelBackground.supported(getString(ICON_BACKGROUND)) != PanelBackground.OFF

            WIDGET_BLUR_RADIUS,
            WIDGET_BLUR_TINT -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    PanelBackground.supported(getString(WIDGET_BACKGROUND)) != PanelBackground.OFF

            DOCK_BACKGROUND -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !getBoolean(DISABLE_DOCK)

            DOCK_BLUR_RADIUS,
            DOCK_BLUR_TINT,
            DOCK_BLUR_TINT_COLOR,
            DOCK_BLUR_CORNER,
            DOCK_BLUR_STYLE,
            DOCK_BLUR_MARGIN -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !getBoolean(DISABLE_DOCK) &&
                    PanelBackground.supported(getString(DOCK_BACKGROUND)) != PanelBackground.OFF

            DEVELOPER_OPTIONS,
            RECENTS_DISABLE_SELECTION -> isPixelLauncher

            FIXED_RECENTS_BUTTONS_WIDTH -> getBoolean(RECENTS_CLEAR_ALL_BUTTON)

            RECENTS_MEMINFO_ZRAM,
            RECENTS_MEMINFO_CHIP -> getBoolean(RECENTS_MEMINFO)

            THEMED_ICON_CUSTOM_FG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_BG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_FG_COLOR_DARK,
            THEMED_ICON_CUSTOM_BG_COLOR_DARK,
            FOLDER_CUSTOM_COLOR_LIGHT,
            FOLDER_CUSTOM_COLOR_DARK -> getBoolean(HOME_THEMED_ICONS) && getBoolean(THEMED_ICON_CUSTOM_COLOR)

            SEARCH_BAR_BACKGROUND -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !getBoolean(DESKTOP_SEARCH_BAR) &&
                    !getBoolean(DISABLE_DOCK)

            SEARCH_BAR_BLUR_RADIUS,
            SEARCH_BAR_BLUR_TINT -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !getBoolean(DESKTOP_SEARCH_BAR) &&
                    !getBoolean(DISABLE_DOCK) &&
                    PanelBackground.supported(getString(SEARCH_BAR_BACKGROUND)) != PanelBackground.OFF

            DESKTOP_SEARCH_BAR,
            DOCK_BOTTOM_SPACING,
            DOCK_TOP_SPACING -> !getBoolean(DISABLE_DOCK)

            FREEFORM_GESTURE_PROGRESS,
            FREEFORM_MODE -> getBoolean(FREEFORM_GESTURE)

            HIDE_NAVIGATION_SPACE -> getBoolean(HIDE_GESTURE_PILL)

            LAUNCHER_DARK_PAGE_INDICATOR -> !getBoolean(LAUNCHER_HIDE_PAGE_INDICATOR)

            SEARCH_HIDDEN_APPS -> getBoolean(HIDE_APPS_FROM_APP_DRAWER)

            NO_DRAWER_SWIPE_ACTION,
            NO_DRAWER_ARRANGEMENT,
            NO_DRAWER_AUTO_FILL -> getBoolean(NO_DRAWER_MODE)

            DRAWER_TABS_ENABLED,
            "xposed_app_drawer_tabs",
            "xposed_app_drawer_folders" -> !getBoolean(NO_DRAWER_MODE)

            DRAWER_TABS_AT_BOTTOM -> getBoolean(DRAWER_TABS_ENABLED) && !getBoolean(NO_DRAWER_MODE)

            else -> true
        }
    }

    fun isEnabled(key: String): Boolean {
        return when (key) {
            else -> true
        }
    }

    @SuppressLint("DefaultLocale")
    fun getSummary(context: Context, key: String): String? {
        when {
            key.endsWith("Slider") -> {
                val value = String.format("%.2f", getSliderFloat(key, 0f))
                return if (value.endsWith(".00")) value.dropLast(3) else value
            }

            key.endsWith("List") -> {
                return getString(key, "")
            }

            key.endsWith("EditText") -> {
                return getString(key, "")
            }

            key.endsWith("MultiSelect") -> {
                return getStringSet(key, emptySet()).toString()
            }

            else -> return when (key) {
                else -> null
            }
        }
    }

    fun setupAllPreferences(group: PreferenceGroup) {
        var i = 0

        while (true) {
            try {
                val thisPreference = group.getPreference(i)

                setupPreference(thisPreference)

                if (thisPreference is PreferenceGroup) {
                    setupAllPreferences(thisPreference)
                    if (thisPreference is PreferenceCategory && thisPreference.isVisible) {
                        thisPreference.isVisible = (0 until thisPreference.preferenceCount)
                            .any { thisPreference.getPreference(it).isVisible }
                    }
                } else if (thisPreference is TwoTargetSwitchPreference) {
                    val switchPreference: TwoTargetSwitchPreference = thisPreference
                    switchPreference.isChecked = getBoolean(switchPreference.key)
                }
            } catch (_: Throwable) {
                break
            }

            i++
        }
    }

    private fun setupPreference(preference: Preference) {
        try {
            val key = preference.key

            preference.isVisible = isVisible(key)
            preference.isEnabled = isEnabled(key)

            getSummary(preference.context, key)?.let {
                preference.summary = it
            }
        } catch (_: Throwable) {
        }
    }
}
