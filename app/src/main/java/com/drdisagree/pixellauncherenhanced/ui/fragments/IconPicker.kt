package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.enums.IconSlot
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.databinding.FragmentIconPickerBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.Messages
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class IconPicker : Fragment() {

    private class Previews(
        val current: Drawable?,
        val follow: Drawable?,
        val second: Drawable?,
        val custom: Drawable?,
        val pack: Drawable?,
        val packLabel: String?
    )

    private lateinit var binding: FragmentIconPickerBinding
    private lateinit var component: ComponentName
    private lateinit var slot: IconSlot
    private val isShortcut: Boolean
        get() = IconPackManager.isShortcut(component)
    private var previews: Previews? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveCustomImage(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentIconPickerBinding.inflate(inflater, container, false)
        component = ComponentName.unflattenFromString(requireArguments().getString(ARG_COMPONENT)!!)!!
        slot = requireArguments().getString(ARG_SLOT)?.let { IconSlot.valueOf(it) } ?: IconSlot.DRAWER

        setupToolbar(
            requireContext() as AppCompatActivity,
            getString(slotTitle(slot)),
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.previewSummary.text = requireArguments().getString(ARG_LABEL).orEmpty()
        bindChoices()
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            previews = withContext(Dispatchers.IO) {
                IconPackStore.pruneMissingPackOverrides(context)
                runCatching { loadPreviews(context) }.getOrNull()
            }
            bindChoices()
        }
    }

    private fun loadPreviews(context: Context): Previews {
        val packageManager = context.packageManager
        val key = component.flattenToString()
        val config = IconPackStore.config()
        val packValue = config.overridesFor(slot)[key]?.takeIf { IconPackManager.overridePackage(it) != null }

        val pack = packValue?.let { value ->
            val pkg = IconPackManager.overridePackage(value) ?: return@let null
            IconPackManager.pack(context, pkg)?.loadDrawable(value.substringAfter('|'))
                ?.let { if (slot.isThemed) themedGlyphPreview(context, it) else it }
        }
        val packLabel = packValue?.let { value ->
            val pkg = IconPackManager.overridePackage(value).orEmpty()
            val label = runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }
                .getOrNull() ?: pkg
            getString(R.string.icon_choice_pack_current, label, value.substringAfter('|').replace('_', ' '))
        }

        if (isShortcut) {
            val shortcut = IconPackStore.pinnedShortcuts().firstOrNull { it.component == component }
            val icon = shortcut?.let { IconPackStore.shortcutIcon(context, it) }
                ?: runCatching { packageManager.getApplicationIcon(component.packageName) }.getOrNull()
            val current = shortcut?.let { IconPackStore.previewShortcutIcon(context, it, config) } ?: icon
            return Previews(current, icon, icon, customPreview(context, key, config), pack, packLabel)
        }

        val app = (IconPackStore.launcherAppsSnapshot ?: IconPackStore.launcherApps(context))
            .firstOrNull { it.component == component }
        val icon = app?.icon ?: runCatching { packageManager.getActivityIcon(component) }.getOrNull()
            ?: return Previews(null, null, null, null, pack, packLabel)

        val without = when (slot) {
            IconSlot.DRAWER -> config.copy(overrides = config.overrides - key)
            IconSlot.HOME -> config.copy(homeOverrides = config.homeOverrides - key)
            IconSlot.THEMED -> config.copy(themedOverrides = config.themedOverrides - key)
            IconSlot.THEMED_HOME -> config.copy(homeThemedOverrides = config.homeThemedOverrides - key)
        }

        val current = IconPackStore.previewFor(context, component, icon, config, slot.isHome, slot.isThemed)
        val follow = when (slot) {
            IconSlot.DRAWER -> IconPackStore.previewFor(context, component, icon, without, home = false, themed = false)
            IconSlot.HOME -> IconPackStore.previewFor(context, component, icon, config, home = false, themed = false)
            IconSlot.THEMED -> IconPackStore.previewFor(context, component, icon, without, home = false, themed = true)
            IconSlot.THEMED_HOME -> IconPackStore.previewFor(context, component, icon, without, home = true, themed = true)
        }
        val second = when (slot) {
            IconSlot.DRAWER, IconSlot.HOME -> icon
            IconSlot.THEMED -> IconPackStore.previewFor(context, component, icon, config, home = false, themed = false)
            IconSlot.THEMED_HOME -> IconPackStore.previewFor(context, component, icon, config, home = true, themed = false)
        }

        return Previews(current, follow, second, customPreview(context, key, config), pack, packLabel)
    }

    private fun customPreview(context: Context, key: String, config: IconPackManager.Config): Drawable? {
        val bitmap = IconPackStore.customIcon(slot, key) ?: return null
        if (!slot.isThemed) return IconPackManager.adaptiveFromImage(context, bitmap)

        return IconPackManager.themedMonochrome(
            context, component, IconPackManager.OVERRIDE_CUSTOM, config,
            context.resources.displayMetrics.densityDpi, null, bitmap
        )?.let { IconPackStore.themedPreview(context, it) }
    }

    private fun bindChoices() {
        val override = IconPackStore.override(slot, component)
        val previews = previews
        val choices = binding.choices
        val isPack = override?.let { IconPackManager.overridePackage(it) } != null

        val (followLabel, followSummary) = when (slot) {
            IconSlot.DRAWER -> R.string.icon_choice_pack to R.string.icon_choice_pack_desc
            IconSlot.THEMED -> R.string.icon_choice_automatic to R.string.icon_choice_automatic_desc
            IconSlot.HOME, IconSlot.THEMED_HOME -> R.string.icon_choice_same_as_drawer to R.string.icon_choice_same_as_drawer_desc
        }
        val secondValue = if (slot.isThemed) IconPackManager.OVERRIDE_NONE else IconPackManager.OVERRIDE_ORIGINAL
        val (secondLabel, secondSummary) = if (slot.isThemed) {
            R.string.icon_choice_no_themed to R.string.icon_choice_no_themed_desc
        } else {
            R.string.icon_choice_original to R.string.icon_choice_original_desc
        }

        choices.choiceFollow.root.visibility = if (isShortcut) View.GONE else View.VISIBLE

        bindChoice(choices.choiceFollow.root, previews?.follow, getString(followLabel), getString(followSummary), override == null) {
            choose(null)
        }
        bindChoice(
            choices.choiceOriginal.root,
            previews?.second,
            getString(secondLabel),
            getString(secondSummary),
            override == secondValue || (isShortcut && override == null)
        ) { choose(if (isShortcut) null else secondValue) }
        bindChoice(
            choices.choiceCustom.root,
            previews?.custom,
            getString(R.string.icon_choice_custom),
            getString(if (slot.isThemed) R.string.icon_choice_custom_themed_desc else R.string.icon_choice_custom_desc),
            override == IconPackManager.OVERRIDE_CUSTOM
        ) { imagePicker.launch("image/*") }
        bindChoice(
            choices.choicePack.root,
            if (isPack) previews?.pack else null,
            getString(R.string.icon_choice_from_pack),
            if (isPack) previews?.packLabel.orEmpty() else getString(R.string.icon_choice_from_pack_desc),
            isPack,
            chevron = true
        ) { openBrowser() }

        val rows = (choices.root as ViewGroup).children.filter { it.visibility == View.VISIBLE }.toList()
        rows.forEachIndexed { index, row -> RowBackgrounds.apply(row, index, rows.size) }

        binding.previewIcon.setImageDrawable(previews?.current)
        binding.previewTitle.text = when {
            isPack -> previews?.packLabel.orEmpty()
            override == null && isShortcut -> getString(R.string.icon_choice_original)
            override == null -> getString(followLabel)
            override == secondValue -> getString(secondLabel)
            override == IconPackManager.OVERRIDE_CUSTOM -> getString(R.string.icon_choice_custom)
            else -> ""
        }
    }

    private fun bindChoice(
        row: View,
        icon: Drawable?,
        label: String,
        summary: String,
        selected: Boolean,
        chevron: Boolean = false,
        onClick: () -> Unit
    ) {
        row.findViewById<TextView>(R.id.choiceLabel).text = label
        row.findViewById<TextView>(R.id.choiceSummary).text = summary
        row.findViewById<MaterialRadioButton>(R.id.choiceRadio).isChecked = selected
        row.findViewById<View>(R.id.choiceChevron).visibility = if (chevron) View.VISIBLE else View.GONE
        row.findViewById<ImageView>(R.id.choiceIcon).setImageDrawable(
            icon ?: ContextCompat.getDrawable(
                row.context,
                if (chevron) R.drawable.ic_settings_icon_pack else R.drawable.ic_add
            )?.mutate()?.apply {
                setTint(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        )
        row.setOnClickListener { onClick() }
    }

    private fun choose(value: String?) {
        IconPackStore.setOverride(slot, component, value)
        parentFragmentManager.popBackStack()
    }

    private fun openBrowser() {
        MainActivity.replaceFragment(parentFragmentManager, IconPackBrowser.newInstance(component, slot))
    }

    private fun saveCustomImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

                    var sample = 1
                    while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CUSTOM_ICON_SIZE) sample *= 2

                    val decoded = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@runCatching null

                    val side = min(decoded.width, decoded.height)
                    val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
                    Bitmap.createScaledBitmap(square, CUSTOM_ICON_SIZE, CUSTOM_ICON_SIZE, true)
                }.getOrNull()
            }

            if (bitmap == null) {
                Messages.show(requireActivity(), getString(R.string.icon_picker_image_failed))
                return@launch
            }

            IconPackStore.setCustomIcon(slot, component, bitmap)
            parentFragmentManager.popBackStack()
        }
    }

    private fun themedGlyphPreview(context: Context, drawable: Drawable): Drawable {
        val glyph = if (drawable is AdaptiveIconDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawable.monochrome ?: drawable.foreground
        } else {
            InsetDrawable(drawable, THEMED_GLYPH_INSET)
        }
        return IconPackStore.themedPreview(context, glyph)
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
        const val ARG_SLOT = "slot"
        private const val CUSTOM_ICON_SIZE = 256
        private const val THEMED_GLYPH_INSET = 0.28f

        fun slotTitle(slot: IconSlot) = when (slot) {
            IconSlot.DRAWER -> R.string.icon_slot_drawer
            IconSlot.HOME -> R.string.icon_slot_home
            IconSlot.THEMED -> R.string.icon_slot_themed
            IconSlot.THEMED_HOME -> R.string.icon_slot_themed_home
        }
    }
}
