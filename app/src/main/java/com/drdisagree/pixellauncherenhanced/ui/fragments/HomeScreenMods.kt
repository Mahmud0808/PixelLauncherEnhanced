package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Build
import android.os.Bundle
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.ListPreference
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.LOAD_TIME_KEY_KEY
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.PACKAGE_STRIKE_KEY_KEY
import java.util.Calendar

class HomeScreenMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_home_screen_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.home_screen_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

        listOf(DOCK_BACKGROUND, WIDGET_BACKGROUND, FOLDER_BACKGROUND).forEach { key ->
            findPreference<ListPreference>(key)?.apply {
                entries = entries.take(2).toTypedArray()
                entryValues = entryValues.take(2).toTypedArray()
            }
        }
    }
}
