package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPack
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.databinding.FragmentIconPickerBinding
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class IconPicker : Fragment() {

    private class PackIcons(
        val info: IconPackManager.IconPackInfo,
        val suggested: String?,
        val categories: List<Pair<String?, List<String>>>
    ) {
        val categorized = categories.any { it.first != null }
    }

    private sealed class GridItem {
        data class Header(val title: String) : GridItem()
        data class Icon(val name: String) : GridItem()
    }

    private lateinit var binding: FragmentIconPickerBinding
    private lateinit var component: ComponentName
    private var appIcon: Drawable? = null
    private var followIcon: Drawable? = null
    private var packIcons: List<PackIcons> = emptyList()
    private var filtered: List<List<GridItem>> = emptyList()
    private var selectedPack = 0
    private var visibleItems: List<GridItem> = emptyList()
    private val adapter = IconAdapter()
    private val drawableCache = LruCache<String, Drawable>(400)

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveCustomImage(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentIconPickerBinding.inflate(inflater, container, false)
        component = ComponentName.unflattenFromString(requireArguments().getString(ARG_COMPONENT)!!)!!

        setupToolbar(
            requireContext() as AppCompatActivity,
            requireArguments().getString(ARG_LABEL).orEmpty(),
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (visibleItems.getOrNull(position) is GridItem.Header) COLUMNS else 1
            }
        }
        binding.recyclerView.adapter = adapter

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedPack = tab.position
                showSelectedPack()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.recyclerView.scrollToPosition(0)
            }
        })

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) = applySearch()
        })

        bindChoices()
        load()
    }

    override fun onStop() {
        super.onStop()
        if (!isRemoving) IconPackStore.applyIfChanged()
    }

    private fun load() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext

            withContext(Dispatchers.IO) {
                IconPackStore.pruneMissingPackOverrides(context)
                val packageManager = context.packageManager
                val app = IconPackStore.launcherApps(context).firstOrNull { it.component == component }
                appIcon = app?.icon ?: runCatching { packageManager.getActivityIcon(component) }.getOrNull()

                val followConfig = IconPackStore.config().let { it.copy(overrides = it.overrides - component.flattenToString()) }
                followIcon = app?.let { IconPackStore.previewIcon(context, it, followConfig) } ?: appIcon

                packIcons = IconPackManager.installedIconPacks(context).mapNotNull { info ->
                    val pack = IconPackManager.pack(context, info.packageName) ?: return@mapNotNull null
                    PackIcons(info, pack.drawableNameFor(component), pack.categories())
                }
            }

            binding.progressBar.visibility = View.GONE
            bindChoices()
            buildTabs()
            applySearch()
        }
    }

    private fun buildTabs() {
        binding.tabs.removeAllTabs()
        packIcons.forEach { binding.tabs.addTab(binding.tabs.newTab(), false) }
        binding.tabs.visibility = if (packIcons.isEmpty()) View.GONE else View.VISIBLE

        val suggestedIndex = packIcons.indexOfFirst { it.suggested != null }.takeIf { it >= 0 } ?: 0
        if (packIcons.isNotEmpty()) binding.tabs.getTabAt(suggestedIndex)?.select()
    }

    private fun applySearch() {
        val query = binding.search.text.toString().trim().lowercase().replace(' ', '_')

        binding.choices.root.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
        fun matches(name: String) = query.isEmpty() || name.lowercase().contains(query)

        filtered = packIcons.map { pack ->
            buildList {
                pack.suggested?.takeIf(::matches)?.let { suggested ->
                    if (pack.categorized) add(GridItem.Header(getString(R.string.icon_picker_suggested)))
                    add(GridItem.Icon(suggested))
                }

                pack.categories.forEach { (title, names) ->
                    val visible = names.filter { matches(it) && (it != pack.suggested || pack.categorized) }
                    if (visible.isEmpty()) return@forEach
                    if (pack.categorized) {
                        add(GridItem.Header(if (title == null || title == IconPack.OTHER_CATEGORY) getString(R.string.icon_picker_other) else title))
                    }
                    visible.forEach { add(GridItem.Icon(it)) }
                }
            }
        }

        packIcons.forEachIndexed { index, pack ->
            val count = filtered[index].filterIsInstance<GridItem.Icon>().map { it.name }.distinct().size
            binding.tabs.getTabAt(index)?.text = getString(R.string.icon_picker_tab, pack.info.label, count)
        }

        showSelectedPack()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showSelectedPack() {
        visibleItems = filtered.getOrNull(selectedPack).orEmpty()
        adapter.notifyDataSetChanged()
        binding.recyclerView.scrollToPosition(0)

        val empty = visibleItems.isEmpty()
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (empty && binding.progressBar.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        binding.emptyText.setText(if (packIcons.isEmpty()) R.string.icon_packs_none else R.string.icon_picker_no_results)
    }

    private fun bindChoices() {
        val override = IconPackStore.config().overrides[component.flattenToString()]
        val customIcon = IconPackStore.customIcon(component.flattenToString())
            ?.let { IconPackManager.adaptiveFromImage(requireContext(), it) }

        bindChoice(binding.choices.choiceFollow.root, followIcon, R.string.icon_choice_pack, override == null) {
            choose(null)
        }
        bindChoice(
            binding.choices.choiceOriginal.root,
            appIcon,
            R.string.icon_choice_original,
            override == IconPackManager.OVERRIDE_ORIGINAL
        ) { choose(IconPackManager.OVERRIDE_ORIGINAL) }
        bindChoice(
            binding.choices.choiceCustom.root,
            customIcon,
            R.string.icon_choice_custom,
            override == IconPackManager.OVERRIDE_CUSTOM
        ) { imagePicker.launch("image/*") }
    }

    private fun bindChoice(card: MaterialCardView, icon: Drawable?, label: Int, selected: Boolean, onClick: () -> Unit) {
        card.isChecked = selected
        card.strokeColor = MaterialColors.getColor(
            card,
            if (selected) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant
        )
        card.findViewById<TextView>(R.id.choiceLabel).setText(label)
        card.findViewById<ImageView>(R.id.choiceIcon).apply {
            if (icon != null) {
                imageTintList = null
                setImageDrawable(icon)
            } else {
                setImageResource(R.drawable.ic_add)
                imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
        }
        card.setOnClickListener { onClick() }
    }

    private fun choose(value: String?) {
        IconPackStore.setOverride(component, value)
        parentFragmentManager.popBackStack()
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
                Toast.makeText(context, R.string.icon_picker_image_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            IconPackStore.setCustomIcon(component, bitmap)
            parentFragmentManager.popBackStack()
        }
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

    private inner class IconAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = visibleItems.size

        override fun getItemViewType(position: Int) = if (visibleItems[position] is GridItem.Header) TYPE_HEADER else TYPE_ICON

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_HEADER) R.layout.view_drawer_tab_section else R.layout.view_icon_preview
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val pack = packIcons.getOrNull(selectedPack) ?: return
            val item = visibleItems[position]

            if (item is GridItem.Header) {
                holder.itemView.findViewById<TextView>(R.id.title).text = item.title
                holder.itemView.findViewById<TextView>(R.id.summary).visibility = View.GONE
                holder.itemView.setPadding(dpToPx(12), dpToPx(16), dpToPx(12), dpToPx(4))
                return
            }

            val name = (item as GridItem.Icon).name
            val key = "${pack.info.packageName}|$name"
            val view = holder.itemView
            val imageView = view.findViewById<ImageView>(R.id.icon)

            view.findViewById<TextView>(R.id.label).text = name.replace('_', ' ')
            imageView.tag = key
            imageView.setImageDrawable(drawableCache.get(key))

            if (drawableCache.get(key) == null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val drawable = withContext(Dispatchers.IO) {
                        IconPackManager.pack(requireContext().applicationContext, pack.info.packageName)?.loadDrawable(name)
                    } ?: return@launch
                    drawableCache.put(key, drawable)
                    if (imageView.tag == key) imageView.setImageDrawable(drawable)
                }
            }

            view.setOnClickListener { choose(IconPackManager.overrideForPack(pack.info.packageName, name)) }
        }
    }

    companion object {
        const val ARG_COMPONENT = "component"
        const val ARG_LABEL = "label"
        private const val COLUMNS = 5
        private const val CUSTOM_ICON_SIZE = 256
        private const val TYPE_HEADER = 0
        private const val TYPE_ICON = 1
    }
}
