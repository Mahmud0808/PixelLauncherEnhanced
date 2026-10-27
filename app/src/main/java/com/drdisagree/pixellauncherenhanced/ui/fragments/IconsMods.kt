package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.ListPreference

class IconsMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_icons_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.icons_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        if (PanelBackground.isLiquidSupported) return

        findPreference<ListPreference>(ICON_BACKGROUND)?.apply {
            entries = entries.take(2).toTypedArray()
            entryValues = entryValues.take(2).toTypedArray()
        }
    }
}
