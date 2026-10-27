package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat

class WidgetMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_widgets_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.widget_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        hideLiquidBelowTiramisu(WIDGET_BACKGROUND)
    }
}
