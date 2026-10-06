package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.doOnPreDraw
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.XPOSED_HOOK_CHECK
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.DashboardHeroPreference
import com.drdisagree.pixellauncherenhanced.ui.preferences.HookCheckPreference

class HomePage : ControlledPreferenceFragmentCompat() {

    private var hookCheckPreference: HookCheckPreference? = null

    override val title: String
        get() = getString(R.string.app_name)

    override val backButtonEnabled: Boolean
        get() = false

    override val layoutResource: Int
        get() = R.xml.home_page

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<HookCheckPreference>(XPOSED_HOOK_CHECK)?.apply {
            hookCheckPreference = this

            setOnPreferenceClickListener {
                try {
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.component = ComponentName(
                        "org.lsposed.manager",
                        "org.lsposed.manager.ui.activities.MainActivity"
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            initializeHookCheck()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }
    }

    override fun updateScreen(key: String?) {
        super.updateScreen(key)
        findPreference<DashboardHeroPreference>(DASHBOARD_HERO)?.refresh()
    }

    override fun onResume() {
        super.onResume()

        HookCheckPreference.isHooked = false
        hookCheckPreference?.initializeHookCheck()
    }

    companion object {
        private const val DASHBOARD_HERO = "dashboard_hero"
    }
}
