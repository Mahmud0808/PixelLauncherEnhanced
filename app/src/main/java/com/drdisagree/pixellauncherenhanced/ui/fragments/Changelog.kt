package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.ChangelogStatusPreference
import com.drdisagree.pixellauncherenhanced.ui.preferences.ReleaseNotesPreference
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Changelog : ControlledPreferenceFragmentCompat() {

    private var loadJob: Job? = null

    override val title: String
        get() = getString(R.string.changelog_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.changelog

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<ChangelogStatusPreference>(STATUS)?.onRetry = { load() }
        load()
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        val status = findPreference<ChangelogStatusPreference>(STATUS) ?: return
        status.failed = false
        loadJob = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            val releases = UpdateManager.changelog()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed > INSTANT_MS) delay(MIN_LOADING_MS - elapsed)
            if (releases.isNullOrEmpty()) {
                status.failed = true
                return@launch
            }
            val screen = preferenceScreen ?: return@launch
            screen.removePreference(status)
            releases.forEachIndexed { index, release ->
                screen.addPreference(ReleaseNotesPreference(requireContext()).apply {
                    key = "changelog_${release.version}"
                    order = index
                    isIconSpaceReserved = false
                    headline = false
                    expanded = index == 0
                    badge = when {
                        UpdateManager.isInstalled(release.version) -> R.string.changelog_installed
                        index == 0 -> R.string.changelog_latest
                        else -> 0
                    }
                    this.release = release
                })
            }
        }
    }

    companion object {
        private const val STATUS = "changelog_status"
        private const val INSTANT_MS = 50L
        private const val MIN_LOADING_MS = 700L
    }
}
