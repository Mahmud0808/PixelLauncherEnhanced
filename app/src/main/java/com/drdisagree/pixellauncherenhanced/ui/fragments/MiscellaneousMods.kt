package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.BACKUP_HOME_LAYOUT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.BACKUP_SETTINGS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RESTART_LAUNCHER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RESTORE_HOME_LAYOUT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RESTORE_SETTINGS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.SwitchPreference
import com.drdisagree.pixellauncherenhanced.utils.BackupException
import com.drdisagree.pixellauncherenhanced.utils.HomeLayoutBackup
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher
import com.drdisagree.pixellauncherenhanced.utils.SettingsBackup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MiscellaneousMods : ControlledPreferenceFragmentCompat() {

    private val backupLayoutLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                runBackupTask(R.string.home_layout_backup_success) {
                    HomeLayoutBackup.backup(it, uri)
                }
            }
        }

    private val restoreLayoutLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestoreLayout(uri)
        }

    private var includeHiddenApps = true

    private val backupSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                val includeHiddenApps = includeHiddenApps
                runBackupTask(R.string.settings_backup_success) {
                    SettingsBackup.backup(it, uri, includeHiddenApps)
                }
            }
        }

    private val restoreSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestoreSettings(uri)
        }

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

        findPreference<Preference>(BACKUP_HOME_LAYOUT)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                backupLayoutLauncher.launch(HomeLayoutBackup.defaultFileName)
                true
            }

        findPreference<Preference>(RESTORE_HOME_LAYOUT)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                restoreLayoutLauncher.launch(BACKUP_MIME_TYPES)
                true
            }

        findPreference<Preference>(BACKUP_SETTINGS)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                chooseSettingsBackupOptions()
                true
            }

        findPreference<Preference>(RESTORE_SETTINGS)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                restoreSettingsLauncher.launch(BACKUP_MIME_TYPES)
                true
            }
    }

    private fun confirmRestoreLayout(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_home_layout_title)
            .setMessage(R.string.restore_home_layout_confirm_desc)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_home_layout_button) { _, _ ->
                runBackupTask(R.string.home_layout_restore_success) {
                    HomeLayoutBackup.restore(it, uri)
                }
            }
            .show()
    }

    private fun chooseSettingsBackupOptions() {
        val checked = booleanArrayOf(true)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_settings_title)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.backup_settings_include_hidden_apps)),
                checked
            ) { _, _, isChecked -> checked[0] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_button) { _, _ ->
                includeHiddenApps = checked[0]
                backupSettingsLauncher.launch(SettingsBackup.defaultFileName)
            }
            .show()
    }

    private fun confirmRestoreSettings(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_settings_title)
            .setMessage(R.string.restore_settings_confirm_desc)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_home_layout_button) { _, _ ->
                runBackupTask(
                    successRes = R.string.settings_restore_success,
                    onSuccess = {
                        it.restartLauncher()
                        activity?.recreate()
                    }
                ) {
                    SettingsBackup.restore(it, uri)
                }
            }
            .show()
    }

    private fun runBackupTask(
        @StringRes successRes: Int,
        onSuccess: (Context) -> Unit = {},
        task: suspend (Context) -> Unit
    ) {
        val context = requireContext().applicationContext

        lifecycleScope.launch {
            val message = try {
                task(context)
                onSuccess(context)
                context.getString(successRes)
            } catch (e: BackupException) {
                context.getString(e.messageRes, *e.formatArgs)
            } catch (e: Exception) {
                context.getString(R.string.backup_failed)
            }

            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val BACKUP_MIME_TYPES = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream"
        )
    }
}
