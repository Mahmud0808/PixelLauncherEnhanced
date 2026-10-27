package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.drdisagree.pixellauncherenhanced.utils.Messages
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.BackupCardPreference
import com.drdisagree.pixellauncherenhanced.utils.BackupCrypto
import com.drdisagree.pixellauncherenhanced.utils.BackupException
import com.drdisagree.pixellauncherenhanced.utils.DrawerLayoutBackup
import com.drdisagree.pixellauncherenhanced.utils.FullBackup
import com.drdisagree.pixellauncherenhanced.utils.HomeLayoutBackup
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher
import com.drdisagree.pixellauncherenhanced.utils.SettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            if (uri != null) verifyBackup(uri, BackupCrypto.HOME_LAYOUT_MAGIC, R.string.home_layout_invalid_file) {
                confirmRestoreLayout(uri)
            }
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
            if (uri != null) verifyBackup(uri, BackupCrypto.DRAWER_LAYOUT_MAGIC, R.string.drawer_layout_invalid_file) {
                confirmRestoreDrawerLayout(uri)
            }
        }

    private var fullIncludeHiddenApps = true

    private val backupFullLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                val includeHiddenApps = fullIncludeHiddenApps
                runBackupTask(R.string.full_backup_success) {
                    FullBackup.backup(it, uri, includeHiddenApps)
                }
            }
        }

    private val restoreFullLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) verifyBackup(uri, BackupCrypto.FULL_MAGIC, R.string.full_invalid_file) {
                confirmRestoreFull(uri)
            }
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
            if (uri != null) verifyBackup(uri, BackupCrypto.SETTINGS_MAGIC, R.string.settings_invalid_file) {
                confirmRestoreSettings(uri)
            }
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

        findPreference<BackupCardPreference>(CARD_FULL)?.apply {
            onBackup = { chooseFullBackupOptions() }
            onRestore = { restoreFullLauncher.launch(BACKUP_MIME_TYPES) }
        }

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

    private fun chooseFullBackupOptions() {
        var checked = true
        val context = requireContext()

        ExpressiveDialog.show(
            context = context,
            icon = R.drawable.ic_upload,
            title = getString(R.string.backup_full_title),
            content = ExpressiveDialog.toggleRow(
                context,
                getString(R.string.backup_drawer_layout_include_hidden_apps),
                checked
            ) { checked = it },
            positive = ExpressiveDialog.Action(getString(R.string.backup_button)) {
                fullIncludeHiddenApps = checked
                backupFullLauncher.launch(FullBackup.defaultFileName)
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel))
        )
    }

    private fun confirmRestoreFull(uri: Uri) {
        ExpressiveDialog.show(
            context = requireContext(),
            icon = R.drawable.ic_download,
            title = getString(R.string.restore_full_title),
            message = getString(R.string.restore_full_confirm_desc),
            positive = ExpressiveDialog.Action(getString(R.string.restore_home_layout_button)) {
                restoreFull(uri)
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel))
        )
    }

    private fun restoreFull(uri: Uri) {
        val context = requireContext().applicationContext

        lifecycleScope.launch {
            val message = try {
                val homeError = FullBackup.restore(context, uri)
                context.restartLauncher()
                activity?.recreate()
                if (homeError == null) {
                    context.getString(R.string.full_restore_success)
                } else {
                    context.getString(
                        R.string.full_restore_home_skipped,
                        context.getString(homeError.messageRes, *homeError.formatArgs)
                    )
                }
            } catch (e: BackupException) {
                context.getString(e.messageRes, *e.formatArgs)
            } catch (e: Exception) {
                context.getString(R.string.backup_failed)
            }

            Messages.show(activity ?: context, message, long = true)
        }
    }

    private fun confirmRestoreLayout(uri: Uri) {
        ExpressiveDialog.show(
            context = requireContext(),
            icon = R.drawable.ic_download,
            title = getString(R.string.restore_home_layout_title),
            message = getString(R.string.restore_home_layout_confirm_desc),
            positive = ExpressiveDialog.Action(getString(R.string.restore_home_layout_button)) {
                runBackupTask(R.string.home_layout_restore_success) {
                    HomeLayoutBackup.restore(it, uri)
                }
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel))
        )
    }

    private fun chooseDrawerLayoutBackupOptions() {
        var checked = true
        val context = requireContext()

        ExpressiveDialog.show(
            context = context,
            icon = R.drawable.ic_upload,
            title = getString(R.string.backup_drawer_layout_title),
            content = ExpressiveDialog.toggleRow(
                context,
                getString(R.string.backup_drawer_layout_include_hidden_apps),
                checked
            ) { checked = it },
            positive = ExpressiveDialog.Action(getString(R.string.backup_button)) {
                includeHiddenApps = checked
                backupDrawerLayoutLauncher.launch(DrawerLayoutBackup.defaultFileName)
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel))
        )
    }

    private fun confirmRestoreDrawerLayout(uri: Uri) {
        ExpressiveDialog.show(
            context = requireContext(),
            icon = R.drawable.ic_download,
            title = getString(R.string.restore_drawer_layout_title),
            message = getString(R.string.restore_drawer_layout_confirm_desc),
            positive = ExpressiveDialog.Action(getString(R.string.restore_home_layout_button)) {
                runBackupTask(R.string.drawer_layout_restore_success) {
                    DrawerLayoutBackup.restore(it, uri)
                }
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel))
        )
    }

    private fun confirmRestoreSettings(uri: Uri) {
        ExpressiveDialog.show(
            context = requireContext(),
            icon = R.drawable.ic_download,
            title = getString(R.string.restore_settings_title),
            message = getString(R.string.restore_settings_confirm_desc),
            positive = ExpressiveDialog.Action(getString(R.string.restore_home_layout_button)) {
                runBackupTask(
                    successRes = R.string.settings_restore_success,
                    onSuccess = {
                        it.restartLauncher()
                        activity?.recreate()
                    }
                ) {
                    SettingsBackup.restore(it, uri)
                }
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel))
        )
    }

    private fun verifyBackup(uri: Uri, magic: ByteArray, @StringRes invalidRes: Int, onValid: () -> Unit) {
        val context = requireContext().applicationContext

        lifecycleScope.launch {
            val header = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(BACKUP_HEADER_SIZE)
                        var read = 0
                        while (read < buffer.size) {
                            val count = input.read(buffer, read, buffer.size - read)
                            if (count < 0) break
                            read += count
                        }
                        buffer.copyOf(read)
                    }
                }.getOrNull()
            }

            val error = when {
                header == null -> invalidRes
                else -> BackupCrypto.wrongTypeMessage(header, magic)
                    ?: invalidRes.takeUnless { BackupCrypto.hasMagic(magic, header) }
            }

            if (error == null) {
                onValid()
            } else {
                Messages.show(activity ?: context, context.getString(error), long = true)
            }
        }
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
        private const val BACKUP_HEADER_SIZE = 16
        private const val CARD_FULL = "xposed_backupcardfull"
        private const val CARD_HOME = "xposed_backupcardhome"
        private const val CARD_DRAWER = "xposed_backupcarddrawer"
        private const val CARD_SETTINGS = "xposed_backupcardsettings"
    }
}
