package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.ComponentName
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.databinding.FragmentAppIconsBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.utils.IconApplyDialog
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.syncAppBarWithList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppIcons : Fragment() {

    private sealed class Item {
        abstract val key: String
        open val iconKey: String = ""

        data class Section(val title: Int) : Item() {
            override val key = "section:$title"
        }

        class App(
            val app: IconPackStore.LauncherApp,
            val customized: Boolean,
            val version: Int,
            inCustomizedSection: Boolean = false
        ) : Item() {
            override val iconKey: String = app.component.flattenToString()
            override val key: String = if (inCustomizedSection) "customized:$iconKey" else iconKey
        }

        class Shortcut(val shortcut: IconPackManager.PinnedShortcut, val customized: Boolean, val version: Int) : Item() {
            override val iconKey: String = shortcut.component.flattenToString()
            override val key: String = "shortcut:$iconKey"
        }
    }

    private lateinit var binding: FragmentAppIconsBinding
    private var loadJob: Job? = null
    private var config = IconPackManager.Config()
    private var configKey: String? = null
    private var editingKey: String? = null
    private val versions = HashMap<String, Int>()
    private val iconCache = LruCache<String, Bitmap>(ICON_CACHE_SIZE)
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(RENDER_THREADS)
    private val adapter = ItemAdapter()
    private val shortcutsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PINNED_SHORTCUTS) load()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentAppIconsBinding.inflate(inflater, container, false)

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.per_app_icons_title,
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
                    if (adapter.currentList.getOrNull(position) is Item.Section) COLUMNS else 1
            }
        }
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(COLUMNS * 4)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), binding.recyclerView.paddingBottom)

        binding.applyButton.setOnClickListener {
            IconApplyDialog.apply(this) { updateApplyButton() }
            updateApplyButton()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { list, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dpToPx(96) + navBarInset)
            binding.applyButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dpToPx(24) + navBarInset
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.recyclerView)
    }

    override fun onStart() {
        super.onStart()
        IconFlow.navigating = false
        editingKey?.let { key ->
            iconCache.remove(key)
            versions[key] = (versions[key] ?: 0) + 1
        }
        editingKey = null
        updateApplyButton()
        load()
        RPrefs.registerOnSharedPreferenceChangeListener(shortcutsListener)
        IconPackStore.requestPinnedShortcuts()
    }

    override fun onStop() {
        super.onStop()
        RPrefs.unregisterOnSharedPreferenceChangeListener(shortcutsListener)
        if (!IconFlow.navigating && !isRemoving) IconPackStore.applyIfChanged()
    }

    private fun load() {
        val snapshot = IconPackStore.launcherAppsSnapshot

        if (adapter.currentList.isEmpty()) {
            if (snapshot != null) {
                refreshConfig()
                show(buildItems(snapshot))
            } else {
                binding.progressBar.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.INVISIBLE
            }
        }

        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext

            val apps = withContext(Dispatchers.IO) {
                IconPackStore.pruneMissingPackOverrides(context)
                IconPackStore.launcherApps(context)
            }

            refreshConfig()
            show(buildItems(apps))
        }
    }

    private fun refreshConfig() {
        config = IconPackStore.config()
        val key = IconPackManager.signature(config, emptyList(), RPrefs.getBoolean(HOME_THEMED_ICONS))
        if (key != configKey) {
            if (configKey != null) iconCache.evictAll()
            configKey = key
        }
    }

    private fun buildItems(launcherApps: List<IconPackStore.LauncherApp>): List<Item> {
        val apps = launcherApps.map { app ->
            val key = app.component.flattenToString()
            Item.App(app, key in config.overrides, versions[key] ?: 0)
        }
        val customized = apps.filter { it.customized }.map { Item.App(it.app, true, it.version, inCustomizedSection = true) }
        val shortcuts = IconPackStore.pinnedShortcuts().map { shortcut ->
            val key = shortcut.component.flattenToString()
            Item.Shortcut(shortcut, key in config.overrides, versions[key] ?: 0)
        }

        return buildList {
            if (shortcuts.isNotEmpty()) {
                add(Item.Section(R.string.per_app_icons_shortcuts))
                addAll(shortcuts)
            }
            if (customized.isNotEmpty()) {
                add(Item.Section(R.string.per_app_icons_customized))
                addAll(customized)
                add(Item.Section(R.string.per_app_icons_all))
            } else if (shortcuts.isNotEmpty()) {
                add(Item.Section(R.string.per_app_icons_all))
            }
            addAll(apps)
        }
    }

    private fun show(items: List<Item>) {
        val firstLoad = adapter.currentList.isEmpty()

        adapter.submitList(items) {
            if (firstLoad) syncAppBarWithList(binding.header.appBarLayout, binding.recyclerView)
        }
        updateApplyButton()
        binding.progressBar.visibility = View.GONE
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun renderIcon(item: Item, size: Int): Bitmap? {
        val context = context?.applicationContext ?: return null
        val drawable = when (item) {
            is Item.App -> IconPackStore.previewIcon(context, item.app, config)
            is Item.Shortcut -> IconPackStore.previewShortcutIcon(context, item.shortcut, config)
            is Item.Section -> null
        } ?: return null
        return IconPackStore.renderPreview(drawable, size)
    }

    private fun openPicker(component: ComponentName, label: String) {
        IconFlow.navigating = true
        editingKey = component.flattenToString()
        MainActivity.replaceFragment(
            parentFragmentManager,
            IconPicker().apply {
                arguments = Bundle().apply {
                    putString(IconPicker.ARG_COMPONENT, component.flattenToString())
                    putString(IconPicker.ARG_LABEL, label)
                }
            }
        )
    }

    private fun updateApplyButton() {
        if (IconPackStore.hasPendingChanges) binding.applyButton.show() else binding.applyButton.hide()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            IconFlow.navigating = true
            parentFragmentManager.popBackStackImmediate()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        var job: Job? = null
    }

    private inner class ItemAdapter : ListAdapter<Item, Holder>(DIFF) {

        override fun getItemViewType(position: Int) = if (getItem(position) is Item.Section) TYPE_SECTION else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = if (viewType == TYPE_SECTION) R.layout.view_drawer_tab_section else R.layout.view_icon_preview
            return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            holder.job?.cancel()

            if (item is Item.Section) {
                holder.itemView.findViewById<TextView>(R.id.title).setText(item.title)
                holder.itemView.findViewById<TextView>(R.id.summary).visibility = View.GONE
                return
            }

            val (label, customized, component) = when (item) {
                is Item.App -> Triple(item.app.label, item.customized, item.app.component)
                is Item.Shortcut -> Triple(item.shortcut.label, item.customized, item.shortcut.component)
                else -> return
            }

            holder.itemView.findViewById<TextView>(R.id.label).text = label
            holder.itemView.findViewById<ImageView>(R.id.badge).visibility = if (customized) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { openPicker(component, label) }

            val imageView = holder.itemView.findViewById<ImageView>(R.id.icon)
            val cached = iconCache.get(item.iconKey)
            imageView.tag = item.iconKey
            imageView.setImageBitmap(cached)
            if (cached != null) return

            val size = dpToPx(ICON_SIZE_DP)
            holder.job = viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(renderDispatcher) { runCatching { renderIcon(item, size) }.getOrNull() }
                    ?: return@launch
                iconCache.put(item.iconKey, bitmap)
                if (imageView.tag == item.iconKey) imageView.setImageBitmap(bitmap)
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.job = null
        }
    }

    companion object {
        private const val COLUMNS = 5
        private const val TYPE_SECTION = 0
        private const val TYPE_APP = 1
        private const val ICON_SIZE_DP = 56
        private const val ICON_CACHE_SIZE = 600
        private const val RENDER_THREADS = 3

        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: Item, newItem: Item) = when {
                oldItem is Item.Section && newItem is Item.Section -> oldItem == newItem
                oldItem is Item.App && newItem is Item.App ->
                    oldItem.app.label == newItem.app.label &&
                            oldItem.customized == newItem.customized &&
                            oldItem.version == newItem.version

                oldItem is Item.Shortcut && newItem is Item.Shortcut ->
                    oldItem.shortcut == newItem.shortcut &&
                            oldItem.customized == newItem.customized &&
                            oldItem.version == newItem.version

                else -> false
            }
        }
    }
}
