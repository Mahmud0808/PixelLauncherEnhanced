package com.drdisagree.pixellauncherenhanced.ui.fragments

import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.LOAD_TIME_KEY_KEY
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.PACKAGE_STRIKE_KEY_KEY
import java.util.Calendar

class RecentsMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_recents_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.recents_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar
}