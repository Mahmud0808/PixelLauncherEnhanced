package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
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
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.enums.IconSlot
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPack
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.databinding.FragmentIconPackBrowserBinding
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IconPackBrowser : Fragment() {

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

    private lateinit var binding: FragmentIconPackBrowserBinding
    private lateinit var component: ComponentName
    private lateinit var slot: IconSlot
    private var currentPack: String? = null
    private var packIcons: List<PackIcons> = emptyList()
    private var filtered: List<List<GridItem>> = emptyList()
    private var selectedPack = 0
    private var visibleItems: List<GridItem> = emptyList()
    private val adapter = IconAdapter()
    private val drawableCache = LruCache<String, Bitmap>(ICON_CACHE_SIZE)
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(RENDER_THREADS)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentIconPackBrowserBinding.inflate(inflater, container, false)
        component = ComponentName.unflattenFromString(requireArguments().getString(ARG_COMPONENT)!!)!!
        slot = requireArguments().getString(ARG_SLOT)?.let { IconSlot.valueOf(it) } ?: IconSlot.DRAWER

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.icon_choice_from_pack,
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
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(COLUMNS * 4)
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

        load()
    }

    private fun load() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext

            withContext(Dispatchers.IO) {
                currentPack = IconPackStore.override(slot, component)
                    ?.takeIf { IconPackManager.overridePackage(it) != null }

                val packs = if (slot.isThemed) {
                    (IconPackManager.installedThemedIconPacks(context) + IconPackManager.installedIconPacks(context))
                        .distinctBy { it.packageName }
                } else {
                    IconPackManager.installedIconPacks(context)
                }

                packIcons = packs.mapNotNull { info ->
                    val pack = IconPackManager.pack(context, info.packageName) ?: return@mapNotNull null
                    PackIcons(info, pack.drawableNameFor(component), pack.categories())
                }
            }

            binding.progressBar.visibility = View.GONE
            buildTabs()
            applySearch()
        }
    }

    private fun buildTabs() {
        binding.tabs.removeAllTabs()
        packIcons.forEach { binding.tabs.addTab(binding.tabs.newTab(), false) }
        binding.tabs.visibility = if (packIcons.isEmpty()) View.GONE else View.VISIBLE

        val currentPackage = currentPack?.let { IconPackManager.overridePackage(it) }
        val selectedIndex = packIcons.indexOfFirst { it.info.packageName == currentPackage }.takeIf { it >= 0 }
            ?: packIcons.indexOfFirst { it.suggested != null }.takeIf { it >= 0 }
            ?: 0
        if (packIcons.isNotEmpty()) binding.tabs.getTabAt(selectedIndex)?.select()
    }

    private fun applySearch() {
        val query = binding.search.text.toString().trim().lowercase().replace(' ', '_')
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

    private fun choose(value: String) {
        IconPackStore.setOverride(slot, component, value)
        parentFragmentManager.popBackStack(IconPicker::class.java.simpleName, FragmentManager.POP_BACK_STACK_INCLUSIVE)
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

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        var job: Job? = null
    }

    private inner class IconAdapter : RecyclerView.Adapter<Holder>() {

        override fun getItemCount() = visibleItems.size

        override fun getItemViewType(position: Int) = if (visibleItems[position] is GridItem.Header) TYPE_HEADER else TYPE_ICON

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = if (viewType == TYPE_HEADER) R.layout.view_drawer_tab_section else R.layout.view_icon_preview
            return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.job = null
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val pack = packIcons.getOrNull(selectedPack) ?: return
            val item = visibleItems[position]
            holder.job?.cancel()

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
            val value = IconPackManager.overrideForPack(pack.info.packageName, name)
            val selected = currentPack == value

            view.findViewById<TextView>(R.id.label).text = name.replace('_', ' ')
            view.setBackgroundResource(if (selected) R.drawable.bg_icon_tile_selected else R.drawable.bg_icon_tile)
            view.findViewById<ImageView>(R.id.badge).apply {
                visibility = if (selected) View.VISIBLE else View.GONE
                setImageResource(R.drawable.ic_check)
            }

            val cached = drawableCache.get(key)
            imageView.tag = key
            imageView.setImageBitmap(cached)

            if (cached == null) {
                val context = requireContext().applicationContext
                val size = dpToPx(ICON_SIZE_DP)
                holder.job = viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = withContext(renderDispatcher) {
                        runCatching {
                            IconPackManager.pack(context, pack.info.packageName)
                                ?.loadDrawable(name)
                                ?.let { drawable -> if (slot.isThemed) themedGlyphPreview(context, drawable) else drawable }
                                ?.let { IconPackStore.renderPreview(it, size) }
                        }.getOrNull()
                    } ?: return@launch
                    drawableCache.put(key, bitmap)
                    if (imageView.tag == key) imageView.setImageBitmap(bitmap)
                }
            }

            view.setOnClickListener { choose(value) }
        }
    }

    companion object {
        const val ARG_COMPONENT = "component"
        const val ARG_SLOT = "slot"
        private const val COLUMNS = 5
        private const val TYPE_HEADER = 0
        private const val TYPE_ICON = 1
        private const val ICON_SIZE_DP = 56
        private const val ICON_CACHE_SIZE = 600
        private const val RENDER_THREADS = 3
        private const val THEMED_GLYPH_INSET = 0.28f

        fun newInstance(component: ComponentName, slot: IconSlot) = IconPackBrowser().apply {
            arguments = Bundle().apply {
                putString(ARG_COMPONENT, component.flattenToString())
                putString(ARG_SLOT, slot.name)
            }
        }
    }
}
