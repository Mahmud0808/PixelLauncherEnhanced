package com.drdisagree.pixellauncherenhanced.data.config

import android.annotation.SuppressLint
import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEVELOPER_OPTIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_DOCK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS_AT_BOTTOM
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS_ENABLED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_DARK_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_HIDE_PAGE_INDICATOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FIXED_RECENTS_BUTTONS_WIDTH
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_CUSTOM_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_GESTURE_PROGRESS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FREEFORM_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_APPS_FROM_APP_DRAWER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_ARRANGEMENT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_AUTO_FILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_SWIPE_ACTION
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_CLEAR_ALL_BUTTON
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_DISABLE_SELECTION
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_BG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_COLOR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_DARK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_CUSTOM_FG_COLOR_LIGHT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.XPOSED_HOOK_CHECK
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

            DEVELOPER_OPTIONS,
            RECENTS_DISABLE_SELECTION -> isPixelLauncher

            FIXED_RECENTS_BUTTONS_WIDTH -> getBoolean(RECENTS_CLEAR_ALL_BUTTON)

            THEMED_ICON_CUSTOM_FG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_BG_COLOR_LIGHT,
            THEMED_ICON_CUSTOM_FG_COLOR_DARK,
            THEMED_ICON_CUSTOM_BG_COLOR_DARK,
            FOLDER_CUSTOM_COLOR_LIGHT,
            FOLDER_CUSTOM_COLOR_DARK -> getBoolean(THEMED_ICON_CUSTOM_COLOR)

            DESKTOP_SEARCH_BAR_OPACITY -> isPixelLauncher &&
                    !getBoolean(DESKTOP_SEARCH_BAR) &&
                    !getBoolean(DISABLE_DOCK)

            DESKTOP_SEARCH_BAR,
            DESKTOP_DOCK_SPACING -> !getBoolean(DISABLE_DOCK)

            FREEFORM_GESTURE_PROGRESS,
            FREEFORM_MODE -> getBoolean(FREEFORM_GESTURE)

            HIDE_NAVIGATION_SPACE -> getBoolean(HIDE_GESTURE_PILL)

            LAUNCHER_DARK_PAGE_INDICATOR -> !getBoolean(LAUNCHER_HIDE_PAGE_INDICATOR)

            SEARCH_HIDDEN_APPS -> getBoolean(HIDE_APPS_FROM_APP_DRAWER)

            NO_DRAWER_SWIPE_ACTION,
            NO_DRAWER_ARRANGEMENT,
            NO_DRAWER_AUTO_FILL -> getBoolean(NO_DRAWER_MODE)

            DRAWER_TABS_ENABLED,
            "xposed_app_drawer_tabs" -> !getBoolean(NO_DRAWER_MODE)

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
