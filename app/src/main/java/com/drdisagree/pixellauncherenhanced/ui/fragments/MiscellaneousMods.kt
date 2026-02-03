package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import androidx.preference.Preference
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RESTART_LAUNCHER
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.SwitchPreference
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher

class MiscellaneousMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_miscellaneous_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.miscellaneous_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<SwitchPreference>(HIDE_GESTURE_PILL)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference, newValue ->
                if (newValue == false && RPrefs.getBoolean(HIDE_NAVIGATION_SPACE)) {
                    RPrefs.putBoolean(HIDE_NAVIGATION_SPACE, false)
                    findPreference<SwitchPreference>(HIDE_NAVIGATION_SPACE)?.isChecked = false
                }
                true
            }

        findPreference<Preference>(RESTART_LAUNCHER)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                context?.restartLauncher()
                true
            }
    }
}