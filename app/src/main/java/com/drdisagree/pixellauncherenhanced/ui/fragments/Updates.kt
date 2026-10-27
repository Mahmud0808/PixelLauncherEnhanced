package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_AUTO_CHECK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_NOTIFY
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.PreferenceCategory
import com.drdisagree.pixellauncherenhanced.ui.preferences.ReleaseNotesPreference
import com.drdisagree.pixellauncherenhanced.ui.preferences.SwitchPreference
import com.drdisagree.pixellauncherenhanced.ui.preferences.UpdateCardPreference
import com.drdisagree.pixellauncherenhanced.ui.preferences.UpdateCardPreference.Action
import com.drdisagree.pixellauncherenhanced.ui.widgets.ReleaseNotesView
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager.State
import kotlinx.coroutines.launch

class Updates : ControlledPreferenceFragmentCompat() {

    private var lastState: State? = null
    private var pendingInstall = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            setNotify(granted)
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openNotificationSettings()
            }
        }

    override val title: String
        get() = getString(R.string.updates_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.updates

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<UpdateCardPreference>(CARD)?.apply {
            onAction = { onAction(it) }
            onSkip = {
                val release = when (val state = UpdateManager.state.value) {
                    is State.Available -> state.release
                    is State.Ready -> state.release
                    else -> null
                }
                if (release != null) {
                    UpdateManager.skip(requireContext(), release)
                    refresh()
                }
            }
        }

        findPreference<SwitchPreference>(UPDATE_AUTO_CHECK)?.apply {
            setOnPreferenceChangeListener { _, _ ->
                view?.post { UpdateManager.schedule(requireContext()) }
                true
            }
        }

        findPreference<SwitchPreference>(UPDATE_NOTIFY)?.apply {
            setOnPreferenceChangeListener { _, value ->
                if (value == true && UpdateManager.needsNotificationPermission(requireContext())) {
                    requestNotifications()
                    false
                } else {
                    UpdateManager.setNotifications(requireContext(), value == true)
                    true
                }
            }
        }

        findPreference<Preference>(RELEASES)?.setOnPreferenceClickListener {
            openUrl(UpdateManager.RELEASES_URL)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        UpdateManager.restore(context)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect { state ->
                    render(state)
                    if (lastState is State.Downloading && state is State.Ready) install(state)
                    lastState = state
                }
            }
        }

        val stale = System.currentTimeMillis() - UpdateManager.lastCheck > AUTO_REFRESH_MS
        val current = UpdateManager.state.value
        if (stale && (current == State.Idle || current is State.UpToDate || current is State.Failed)) {
            viewLifecycleOwner.lifecycleScope.launch { UpdateManager.check(context.applicationContext, manual = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.clearNotification(requireContext())
        if (pendingInstall && UpdateManager.canInstall(requireContext())) {
            pendingInstall = false
            (UpdateManager.state.value as? State.Ready)?.let { install(it) }
        }
    }

    override fun updateScreen(key: String?) {
        super.updateScreen(key)
        findPreference<PreferenceCategory>(PREFERENCES)?.isVisible = !UpdateManager.isDevBuild
        findPreference<SwitchPreference>(UPDATE_NOTIFY)?.isEnabled = UpdateManager.autoCheckEnabled
        render(UpdateManager.state.value)
    }

    private fun render(state: State) {
        findPreference<UpdateCardPreference>(CARD)?.state = state
        val release = when (state) {
            is State.Available -> state.release
            is State.Downloading -> state.release
            is State.Ready -> state.release
            is State.Failed -> state.release?.takeIf { UpdateManager.isNewer(it.version) }
            else -> null
        }
        findPreference<ReleaseNotesPreference>(NOTES)?.apply {
            this.release = release
            isVisible = release != null && ReleaseNotesView.hasContent(release.notes)
        }
    }

    private fun onAction(action: Action) {
        val context = requireContext()
        when (action) {
            Action.CHECK -> lifecycleScope.launch { UpdateManager.check(context.applicationContext, manual = true) }
            Action.DOWNLOAD -> (UpdateManager.state.value as? State.Available)?.let {
                UpdateManager.download(context, it.release)
            }

            Action.RETRY -> {
                val failed = UpdateManager.state.value as? State.Failed
                val release = failed?.release
                if (failed?.error == UpdateManager.Error.DOWNLOAD && release != null) {
                    UpdateManager.download(context, release)
                } else {
                    lifecycleScope.launch { UpdateManager.check(context.applicationContext, manual = true) }
                }
            }

            Action.CANCEL -> UpdateManager.cancelDownload()
            Action.INSTALL -> (UpdateManager.state.value as? State.Ready)?.let { install(it) }
            Action.RELEASES -> openUrl(currentPage())
            Action.DEV_DOWNLOAD -> openUrl(UpdateManager.DEV_BUILDS_URL)
            Action.BUILD -> (UpdateManager.state.value as? State.DevAvailable)?.let { openUrl(it.build.pageUrl) }
        }
    }

    private fun currentPage(): String = when (val state = UpdateManager.state.value) {
        is State.Available -> state.release.pageUrl
        is State.Ready -> state.release.pageUrl
        is State.Failed -> state.release?.pageUrl
        else -> null
    }?.ifBlank { null } ?: UpdateManager.RELEASES_URL

    private fun install(state: State.Ready) {
        val context = requireContext()
        if (!UpdateManager.canInstall(context)) {
            ExpressiveDialog.show(
                context = context,
                icon = R.drawable.ic_download,
                title = getString(R.string.update_install_permission_title),
                message = getString(R.string.update_install_permission_desc),
                positive = ExpressiveDialog.Action(getString(R.string.update_install_permission_action)) {
                    pendingInstall = runCatching { startActivity(UpdateManager.installPermissionIntent(context)) }.isSuccess
                },
                negative = ExpressiveDialog.Action(getString(android.R.string.cancel))
            )
            return
        }
        runCatching { startActivity(UpdateManager.installIntent(context, state.file)) }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            )
        }
    }

    private fun setNotify(enabled: Boolean) {
        UpdateManager.setNotifications(requireContext(), enabled)
        findPreference<SwitchPreference>(UPDATE_NOTIFY)?.isChecked = enabled
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    companion object {
        private const val CARD = "update_card"
        private const val NOTES = "update_notes"
        private const val RELEASES = "update_releases"
        private const val PREFERENCES = "update_category_preferences"
        private val AUTO_REFRESH_MS = 15 * 60 * 1000L
    }
}
