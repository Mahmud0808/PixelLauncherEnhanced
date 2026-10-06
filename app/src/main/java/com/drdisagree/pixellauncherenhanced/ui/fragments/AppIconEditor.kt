package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.enums.IconSlot
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.databinding.FragmentAppIconEditorBinding
import com.drdisagree.pixellauncherenhanced.databinding.ViewAppEditorTileBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.Messages
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppIconEditor : Fragment() {

    private class Previews(val hero: Drawable?, val icon: Drawable?, val themed: Drawable?)

    private lateinit var binding: FragmentAppIconEditorBinding
    private lateinit var component: ComponentName
    private val isShortcut: Boolean
        get() = IconPackManager.isShortcut(component)
    private var originalLabel = ""
    private var appIcon: Drawable? = null
    private var home = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentAppIconEditorBinding.inflate(inflater, container, false)
        component = ComponentName.unflattenFromString(requireArguments().getString(ARG_COMPONENT)!!)!!
        originalLabel = requireArguments().getString(ARG_LABEL).orEmpty()
        home = savedInstanceState?.getBoolean(STATE_HOME) ?: requireArguments().getBoolean(ARG_HOME)

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.app_editor_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val themedSupported = !isShortcut && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        binding.locationToggle.visibility = if (isShortcut) View.GONE else View.VISIBLE
        binding.themedTile.root.visibility = if (themedSupported) View.VISIBLE else View.GONE
        binding.tileSpace.visibility = binding.themedTile.root.visibility

        binding.locationToggle.check(if (home) R.id.locationHome else R.id.locationDrawer)
        binding.locationToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            home = checkedId == R.id.locationHome
            refresh()
        }

        binding.iconTile.root.setOnClickListener { openPicker(if (home) IconSlot.HOME else IconSlot.DRAWER) }
        binding.themedTile.root.setOnClickListener { openPicker(if (home) IconSlot.THEMED_HOME else IconSlot.THEMED) }

        val savedLabel = IconPackStore.label(component)
        binding.nameInput.setText(savedLabel ?: originalLabel)
        binding.nameInput.doAfterTextChanged { updateNameState() }
        binding.nameInput.setOnEditorActionListener { input, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitName()
                input.clearFocus()
            }
            false
        }
        binding.nameInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitName() }
        binding.nameLayout.setEndIconOnClickListener {
            binding.nameInput.setText(originalLabel)
            IconPackStore.setLabel(component, null)
            updateNameState()
        }

        binding.resetButton.setOnClickListener {
            IconPackStore.reset(component)
            binding.nameInput.setText(originalLabel)
            refresh()
            Messages.show(requireContext(), getString(R.string.app_editor_reset_done))
        }

        updateNameState()
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onStop() {
        super.onStop()
        commitName()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HOME, home)
    }

    private fun commitName() {
        if (!::binding.isInitialized) return
        val text = binding.nameInput.text?.toString()?.trim().orEmpty()
        IconPackStore.setLabel(component, text.takeIf { it.isNotEmpty() && it != originalLabel })
        updateNameState()
    }

    private fun updateNameState() {
        val text = binding.nameInput.text?.toString()?.trim().orEmpty()
        binding.heroName.text = text.ifEmpty { originalLabel }
        binding.nameLayout.isEndIconVisible = text != originalLabel
        binding.nameLayout.helperText = if (text != originalLabel && originalLabel.isNotEmpty()) {
            getString(R.string.app_editor_original_name, originalLabel)
        } else null
        binding.resetButton.isEnabled = IconPackStore.config().isCustomized(component.flattenToString()) ||
                text != originalLabel
    }

    private fun refresh() {
        binding.heroLocation.setText(
            when {
                isShortcut -> R.string.app_editor_location_shortcut
                home -> R.string.app_editor_location_home
                else -> R.string.app_editor_location_drawer
            }
        )

        val config = IconPackStore.config()
        val key = component.flattenToString()
        val iconSlot = if (home) IconSlot.HOME else IconSlot.DRAWER
        val themedSlot = if (home) IconSlot.THEMED_HOME else IconSlot.THEMED

        bindTile(binding.iconTile, R.string.app_editor_icon, summary(iconSlot, config.overridesFor(iconSlot)[key]))
        bindTile(binding.themedTile, R.string.app_editor_themed_icon, summary(themedSlot, config.overridesFor(themedSlot)[key]))
        updateNameState()

        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val previews = withContext(Dispatchers.Default) { runCatching { loadPreviews(context, config) }.getOrNull() }
                ?: return@launch
            binding.heroIcon.setImageDrawable(previews.hero)
            binding.iconTile.tileIcon.setImageDrawable(previews.icon)
            binding.themedTile.tileIcon.setImageDrawable(previews.themed)
        }
    }

    private fun loadPreviews(context: Context, config: IconPackManager.Config): Previews {
        if (isShortcut) {
            val shortcut = IconPackStore.pinnedShortcuts().firstOrNull { it.component == component }
            val icon = shortcut?.let { IconPackStore.previewShortcutIcon(context, it, config) }
                ?: runCatching { context.packageManager.getApplicationIcon(component.packageName) }.getOrNull()
            return Previews(icon, icon, null)
        }

        val base = appIcon ?: runCatching { context.packageManager.getActivityIcon(component) }.getOrNull()
            ?.also { appIcon = it }
            ?: return Previews(null, null, null)
        val icon = IconPackStore.previewFor(context, component, base, config, home, themed = false)
        val themed = IconPackStore.previewFor(context, component, base, config, home, themed = true)
        val themedMode = RPrefs.getBoolean(HOME_THEMED_ICONS)

        return Previews(if (themedMode) themed else icon, icon, themed)
    }

    private fun bindTile(tile: ViewAppEditorTileBinding, title: Int, summary: String) {
        tile.tileTitle.setText(title)
        tile.tileSummary.text = summary
    }

    private fun summary(slot: IconSlot, value: String?): String = when {
        value == null -> getString(
            when (slot) {
                IconSlot.DRAWER -> if (IconPackStore.config().iconPacks.isEmpty()) R.string.icon_value_default else R.string.icon_choice_pack
                IconSlot.THEMED -> R.string.icon_choice_automatic
                IconSlot.HOME, IconSlot.THEMED_HOME -> R.string.icon_choice_same_as_drawer
            }
        )

        value == IconPackManager.OVERRIDE_ORIGINAL -> getString(R.string.icon_choice_original)
        value == IconPackManager.OVERRIDE_NONE -> getString(R.string.icon_choice_no_themed)
        value == IconPackManager.OVERRIDE_CUSTOM -> getString(R.string.icon_choice_custom)
        else -> IconPackManager.overridePackage(value)?.let { pkg ->
            runCatching {
                val packageManager = requireContext().packageManager
                packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
            }.getOrNull() ?: pkg
        } ?: value
    }

    private fun openPicker(slot: IconSlot) {
        commitName()
        MainActivity.replaceFragment(
            parentFragmentManager,
            IconPicker().apply {
                arguments = Bundle().apply {
                    putString(IconPicker.ARG_COMPONENT, component.flattenToString())
                    putString(IconPicker.ARG_LABEL, originalLabel)
                    putString(IconPicker.ARG_SLOT, slot.name)
                }
            }
        )
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            parentFragmentManager.popBackStackImmediate()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val ARG_COMPONENT = "component"
        const val ARG_LABEL = "label"
        const val ARG_HOME = "home"
        private const val STATE_HOME = "state_home"

        fun newInstance(component: ComponentName, label: String, home: Boolean = false) = AppIconEditor().apply {
            arguments = Bundle().apply {
                putString(ARG_COMPONENT, component.flattenToString())
                putString(ARG_LABEL, label)
                putBoolean(ARG_HOME, home)
            }
        }
    }
}
