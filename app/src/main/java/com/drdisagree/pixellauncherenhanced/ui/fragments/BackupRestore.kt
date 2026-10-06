package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.utils.Messages
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.BackupCardPreference
import com.drdisagree.pixellauncherenhanced.utils.BackupException
import com.drdisagree.pixellauncherenhanced.utils.DrawerLayoutBackup
import com.drdisagree.pixellauncherenhanced.utils.HomeLayoutBackup
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher
import com.drdisagree.pixellauncherenhanced.utils.SettingsBackup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class BackupRestore : ControlledPreferenceFragmentCompat() {

    private val backupLayoutLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
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

    private val backupDrawerLayoutLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                val includeHiddenApps = includeHiddenApps
                runBackupTask(R.string.drawer_layout_backup_success) {
                    DrawerLayoutBackup.backup(it, uri, includeHiddenApps)
                }
            }
        }

    private val restoreDrawerLayoutLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestoreDrawerLayout(uri)
        }

    private val backupSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                runBackupTask(R.string.settings_backup_success) {
                    SettingsBackup.backup(it, uri)
                }
            }
        }

    private val restoreSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestoreSettings(uri)
        }

    override val title: String
        get() = getString(R.string.fragment_backup_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.backup_restore

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<BackupCardPreference>(CARD_HOME)?.apply {
            onBackup = { backupLayoutLauncher.launch(HomeLayoutBackup.defaultFileName) }
            onRestore = { restoreLayoutLauncher.launch(BACKUP_MIME_TYPES) }
        }

        findPreference<BackupCardPreference>(CARD_DRAWER)?.apply {
            onBackup = { chooseDrawerLayoutBackupOptions() }
            onRestore = { restoreDrawerLayoutLauncher.launch(BACKUP_MIME_TYPES) }
        }

        findPreference<BackupCardPreference>(CARD_SETTINGS)?.apply {
            onBackup = { backupSettingsLauncher.launch(SettingsBackup.defaultFileName) }
            onRestore = { restoreSettingsLauncher.launch(BACKUP_MIME_TYPES) }
        }
    }

    private fun confirmRestoreLayout(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialComponents_MaterialAlertDialog_Centered)
            .setIcon(R.drawable.ic_download)
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

    private fun chooseDrawerLayoutBackupOptions() {
        val checked = booleanArrayOf(true)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialComponents_MaterialAlertDialog_Centered)
            .setIcon(R.drawable.ic_upload)
            .setTitle(R.string.backup_drawer_layout_title)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.backup_drawer_layout_include_hidden_apps)),
                checked
            ) { _, _, isChecked -> checked[0] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_button) { _, _ ->
                includeHiddenApps = checked[0]
                backupDrawerLayoutLauncher.launch(DrawerLayoutBackup.defaultFileName)
            }
            .show()
    }

    private fun confirmRestoreDrawerLayout(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialComponents_MaterialAlertDialog_Centered)
            .setIcon(R.drawable.ic_download)
            .setTitle(R.string.restore_drawer_layout_title)
            .setMessage(R.string.restore_drawer_layout_confirm_desc)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_home_layout_button) { _, _ ->
                runBackupTask(R.string.drawer_layout_restore_success) {
                    DrawerLayoutBackup.restore(it, uri)
                }
            }
            .show()
    }

    private fun confirmRestoreSettings(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialComponents_MaterialAlertDialog_Centered)
            .setIcon(R.drawable.ic_download)
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

            Messages.show(activity ?: context, message, long = true)
        }
    }

    companion object {
        private val BACKUP_MIME_TYPES = arrayOf("application/octet-stream", "*/*")
        private const val CARD_HOME = "xposed_backupcardhome"
        private const val CARD_DRAWER = "xposed_backupcarddrawer"
        private const val CARD_SETTINGS = "xposed_backupcardsettings"
    }
}
