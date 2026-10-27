package com.drdisagree.pixellauncherenhanced.ui.fragments

import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat

class DockSpacingMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_dock_spacing_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.dock_spacing_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar
}
