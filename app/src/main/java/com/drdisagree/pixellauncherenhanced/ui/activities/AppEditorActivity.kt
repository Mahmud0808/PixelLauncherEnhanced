package com.drdisagree.pixellauncherenhanced.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.databinding.SheetAppEditorBinding
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppEditorActivity : AppCompatActivity() {

    private lateinit var component: ComponentName
    private var originalLabel = ""
    private var home = false
    private lateinit var behavior: BottomSheetBehavior<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        component = intent.getStringExtra(EXTRA_COMPONENT)?.let { ComponentName.unflattenFromString(it) }
            ?: return finish()
        home = intent.getBooleanExtra(EXTRA_HOME, false)
        originalLabel = originalLabel(intent.getStringExtra(EXTRA_LABEL))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        showSheet()
    }

    private fun originalLabel(fallback: String?): String {
        if (IconPackManager.isShortcut(component)) {
            return IconPackStore.pinnedShortcuts().firstOrNull { it.component == component }?.label
                ?: fallback.orEmpty()
        }
        return runCatching { packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString() }
            .getOrNull() ?: fallback.orEmpty()
    }

    private fun showSheet() {
        val binding = SheetAppEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val basePadding = binding.sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.sheet) { sheet, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars()).bottom
            sheet.updatePadding(bottom = basePadding + bottom)
            insets
        }

        binding.nameInput.setText(IconPackStore.label(component) ?: originalLabel)
        binding.nameInput.setSelection(binding.nameInput.text?.length ?: 0)

        fun updateReset() {
            binding.nameLayout.isEndIconVisible = binding.nameInput.text?.toString()?.trim() != originalLabel
        }
        updateReset()
        binding.nameInput.doAfterTextChanged { updateReset() }
        binding.nameLayout.setEndIconOnClickListener {
            binding.nameInput.setText(originalLabel)
            binding.nameInput.setSelection(originalLabel.length)
        }
        binding.nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save(binding)
                dismiss()
                true
            } else false
        }

        binding.iconContainer.setOnClickListener { openEditor(binding) }
        binding.moreButton.setOnClickListener { openEditor(binding) }
        binding.doneButton.setOnClickListener {
            save(binding)
            dismiss()
        }
        binding.scrim.setOnClickListener { dismiss() }

        behavior = BottomSheetBehavior.from<View>(binding.sheet).apply {
            state = BottomSheetBehavior.STATE_HIDDEN
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(sheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) finish()
                }

                override fun onSlide(sheet: View, slideOffset: Float) {
                    binding.scrim.alpha = (slideOffset + 1f).coerceIn(0f, 1f)
                }
            })
        }
        binding.sheet.post { behavior.state = BottomSheetBehavior.STATE_EXPANDED }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        })

        lifecycleScope.launch {
            val icon = withContext(Dispatchers.Default) { runCatching { loadIcon() }.getOrNull() }
            binding.icon.setImageDrawable(icon)
        }
    }

    private fun loadIcon(): Drawable? {
        val config = IconPackStore.config()

        if (IconPackManager.isShortcut(component)) {
            return IconPackStore.pinnedShortcuts().firstOrNull { it.component == component }
                ?.let { IconPackStore.previewShortcutIcon(this, it, config) }
                ?: runCatching { packageManager.getApplicationIcon(component.packageName) }.getOrNull()
        }

        val base = runCatching { packageManager.getActivityIcon(component) }.getOrNull() ?: return null
        return IconPackStore.previewFor(this, component, base, config, home, RPrefs.getBoolean(HOME_THEMED_ICONS))
    }

    private fun save(binding: SheetAppEditorBinding) {
        val text = binding.nameInput.text?.toString()?.trim().orEmpty()
        IconPackStore.setLabel(component, text.takeIf { it.isNotEmpty() && it != originalLabel })
    }

    private fun dismiss() {
        currentFocus?.clearFocus()
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun openEditor(binding: SheetAppEditorBinding) {
        save(binding)

        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_IS_ROOTED, true)
                .putExtra(MainActivity.EXTRA_EDIT_COMPONENT, component.flattenToString())
                .putExtra(MainActivity.EXTRA_EDIT_LABEL, originalLabel)
                .putExtra(MainActivity.EXTRA_EDIT_HOME, home)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    companion object {
        private const val EXTRA_COMPONENT = "component"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_HOME = "home"
    }
}
