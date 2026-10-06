package com.drdisagree.pixellauncherenhanced.ui.fragments

import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat

class MiscellaneousMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_advanced_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.miscellaneous_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar
}
