package com.drdisagree.pixellauncherenhanced.data.config

import android.annotation.SuppressLint
import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEVELOPER_OPTIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FIXED_RECENTS_BUTTONS_WIDTH
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_CLEAR_ALL_BUTTON
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
            XPOSED_HOOK_CHECK -> !getBoolean(key)

            DEVELOPER_OPTIONS -> isPixelLauncher

            FIXED_RECENTS_BUTTONS_WIDTH -> getBoolean(RECENTS_CLEAR_ALL_BUTTON)

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
                return if (value.endsWith(".00")) value.substring(0, value.length - 3) else value
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
