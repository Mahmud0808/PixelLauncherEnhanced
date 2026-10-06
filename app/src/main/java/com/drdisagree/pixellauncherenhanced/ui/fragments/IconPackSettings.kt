package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.enums.IconSlot
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.databinding.FragmentIconPacksBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.utils.IconApplyDialog
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.syncAppBarWithList
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

class IconPackSettings : Fragment() {

    private sealed class Entry {
        data class Section(@param:StringRes val title: Int, @param:StringRes val description: Int) : Entry()
        data class Pack(
            val info: IconPackManager.IconPackInfo,
            val themed: Boolean,
            val enabled: Boolean,
            val index: Int,
            val size: Int
        ) : Entry()

        data class Mask(val enabled: Boolean) : Entry()
        data object PerAppIcons : Entry()
    }

    private class PackStats(val coverage: Int, val samples: List<Drawable>)

    private lateinit var binding: FragmentIconPacksBinding
    private val adapter = EntryAdapter()
    private lateinit var touchHelper: ItemTouchHelper

    private var appCount = 0
    private var launcherApps: List<IconPackStore.LauncherApp> = emptyList()
    private var iconPacks: List<IconPackManager.IconPackInfo> = emptyList()
    private var themedPacks: List<IconPackManager.IconPackInfo> = emptyList()
    private val enabledIconPacks = mutableListOf<String>()
    private val enabledThemedPacks = mutableListOf<String>()
    private val stats = HashMap<String, PackStats>()
    private val maskablePacks = HashSet<String>()
    private var entries: List<Entry> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentIconPacksBinding.inflate(inflater, container, false)

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.fragment_icon_packs_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val entry = entries.getOrNull(viewHolder.bindingAdapterPosition) as? Entry.Pack
                return if (entry?.enabled == true) super.getMovementFlags(recyclerView, viewHolder) else 0
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = entries.getOrNull(current.bindingAdapterPosition) as? Entry.Pack ?: return false
                val to = entries.getOrNull(target.bindingAdapterPosition) as? Entry.Pack ?: return false
                return to.enabled && to.themed == from.themed
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = entries[viewHolder.bindingAdapterPosition] as Entry.Pack
                val to = entries[target.bindingAdapterPosition] as Entry.Pack
                val list = if (from.themed) enabledThemedPacks else enabledIconPacks
                Collections.swap(list, from.index, to.index)

                val moved = entries.toMutableList()
                Collections.swap(moved, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                entries = moved.map { entry ->
                    if (entry is Entry.Pack && entry.enabled && entry.themed == from.themed) {
                        entry.copy(index = list.indexOf(entry.info.packageName))
                    } else {
                        entry
                    }
                }
                adapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                saveOrder()
            }
        })
        touchHelper.attachToRecyclerView(binding.recyclerView)

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

        load()
    }

    override fun onStart() {
        super.onStart()
        IconFlow.navigating = false
        updateApplyButton()
        if (entries.isNotEmpty()) refreshInstalledPacks()
    }

    override fun onStop() {
        super.onStop()
        if (!IconFlow.navigating) IconPackStore.applyIfChanged()
    }

    private fun load() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.INVISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext

            withContext(Dispatchers.IO) {
                launcherApps = IconPackStore.launcherApps(context)
                appCount = launcherApps.size
                iconPacks = IconPackManager.installedIconPacks(context)
                themedPacks = IconPackManager.installedThemedIconPacks(context)
                computeStats(context, iconPacks + themedPacks)
                IconPackStore.pruneMissingPackOverrides(context)
            }

            val config = IconPackStore.config()
            enabledIconPacks.clear()
            enabledIconPacks.addAll(config.iconPacks.filter { pkg -> iconPacks.any { it.packageName == pkg } })
            enabledThemedPacks.clear()
            enabledThemedPacks.addAll(config.themedIconPacks.filter { pkg -> themedPacks.any { it.packageName == pkg } })

            binding.progressBar.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            rebuild()
            syncAppBarWithList(binding.header.appBarLayout, binding.recyclerView)
        }
    }

    private fun computeStats(context: android.content.Context, packs: List<IconPackManager.IconPackInfo>) {
        packs.forEach { info ->
            val pack = IconPackManager.pack(context, info.packageName) ?: return@forEach
            val covered = launcherApps.filter { pack.covers(it.component) }
            if (pack.hasMask) maskablePacks.add(info.packageName)
            stats[info.packageName] = PackStats(
                coverage = covered.size,
                samples = covered.take(SAMPLE_COUNT).mapNotNull { pack.iconFor(it.component) }
            )
        }
    }

    private fun refreshInstalledPacks() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val known = (iconPacks + themedPacks).mapTo(HashSet()) { it.packageName }

            val (installedIcons, installedThemed) = withContext(Dispatchers.IO) {
                IconPackStore.pruneMissingPackOverrides(context)
                IconPackManager.installedIconPacks(context) to IconPackManager.installedThemedIconPacks(context)
            }
            updateApplyButton()
            val installed = (installedIcons + installedThemed).mapTo(HashSet()) { it.packageName }

            if (installed == known &&
                installedIcons.map { it.packageName } == iconPacks.map { it.packageName } &&
                installedThemed.map { it.packageName } == themedPacks.map { it.packageName }
            ) {
                rebuild()
                return@launch
            }

            val added = (installedIcons + installedThemed).filter { it.packageName !in known }
            withContext(Dispatchers.IO) { computeStats(context, added) }

            (known - installed).forEach { stats.remove(it); maskablePacks.remove(it) }
            iconPacks = installedIcons
            themedPacks = installedThemed

            val config = IconPackStore.config()
            enabledIconPacks.clear()
            enabledIconPacks.addAll(config.iconPacks.filter { pkg -> installedIcons.any { it.packageName == pkg } })
            enabledThemedPacks.clear()
            enabledThemedPacks.addAll(config.themedIconPacks.filter { pkg -> installedThemed.any { it.packageName == pkg } })
            rebuild()
        }
    }

    private fun rebuild() {
        val result = mutableListOf<Entry>()

        if (themedPacks.isNotEmpty()) {
            result.add(Entry.Section(R.string.themed_icon_packs_title, R.string.themed_icon_packs_desc))
            result.addAll(packEntries(themedPacks, enabledThemedPacks, themed = true))
        }

        result.add(Entry.Section(R.string.icon_packs_title, R.string.icon_packs_section_desc))
        if (iconPacks.isEmpty()) {
            result.add(Entry.Section(0, R.string.icon_packs_none))
        } else {
            result.addAll(packEntries(iconPacks, enabledIconPacks, themed = false))
            if (enabledIconPacks.firstOrNull() in maskablePacks) {
                result.add(Entry.Mask(IconPackStore.config().maskUnsupported))
            }
        }

        result.add(Entry.Section(R.string.per_app_icons_title, 0))
        result.add(Entry.PerAppIcons)

        val old = entries
        entries = result

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = result.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].key() == result[newPos].key()
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == result[newPos]
        }).dispatchUpdatesTo(adapter)
    }

    private fun Entry.key(): String = when (this) {
        is Entry.Section -> "section:$title:$description"
        is Entry.Pack -> "pack:$themed:${info.packageName}"
        is Entry.Mask -> "mask"
        Entry.PerAppIcons -> "perapp"
    }

    private fun packEntries(
        installed: List<IconPackManager.IconPackInfo>,
        enabled: List<String>,
        themed: Boolean
    ): List<Entry.Pack> {
        val ordered = enabled.mapNotNull { pkg -> installed.firstOrNull { it.packageName == pkg } } +
                installed.filter { it.packageName !in enabled }

        return ordered.mapIndexed { position, info ->
            Entry.Pack(
                info = info,
                themed = themed,
                enabled = info.packageName in enabled,
                index = if (info.packageName in enabled) enabled.indexOf(info.packageName) else position,
                size = ordered.size
            )
        }
    }

    private fun togglePack(entry: Entry.Pack) {
        val list = if (entry.themed) enabledThemedPacks else enabledIconPacks
        if (entry.enabled) list.remove(entry.info.packageName) else list.add(entry.info.packageName)
        saveOrder()
    }

    private fun saveOrder() {
        IconPackStore.setIconPacks(enabledIconPacks.toList())
        IconPackStore.setThemedIconPacks(enabledThemedPacks.toList())
        rebuild()
        updateApplyButton()
    }

    private fun openPerAppIcons() {
        IconFlow.navigating = true
        MainActivity.replaceFragment(parentFragmentManager, AppIcons())
    }

    private fun updateApplyButton() {
        if (IconPackStore.hasPendingChanges) binding.applyButton.show() else binding.applyButton.hide()
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

    private inner class EntryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = entries.size

        override fun getItemViewType(position: Int) = if (entries[position] is Entry.Section) TYPE_SECTION else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_SECTION) R.layout.view_drawer_tab_section else R.layout.view_icon_pack
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView

            when (val entry = entries[position]) {
                is Entry.Section -> {
                    view.findViewById<TextView>(R.id.title).apply {
                        visibility = if (entry.title != 0) View.VISIBLE else View.GONE
                        if (entry.title != 0) setText(entry.title)
                    }
                    view.findViewById<TextView>(R.id.summary).apply {
                        visibility = if (entry.description != 0) View.VISIBLE else View.GONE
                        if (entry.description != 0) setText(entry.description)
                    }
                }

                is Entry.Pack -> {
                    val stat = stats[entry.info.packageName]

                    view.findViewById<ImageView>(R.id.icon).apply {
                        visibility = View.VISIBLE
                        setImageDrawable(entry.info.icon)
                    }
                    view.findViewById<TextView>(R.id.title).text = entry.info.label
                    view.findViewById<TextView>(R.id.summary).apply {
                        visibility = View.VISIBLE
                        text = getString(R.string.icon_pack_coverage, stat?.coverage ?: 0, appCount)
                    }
                    view.findViewById<MaterialSwitch>(R.id.switchView).apply {
                        visibility = View.VISIBLE
                        isChecked = entry.enabled
                    }
                    view.findViewById<LinearLayout>(R.id.samples).bindSamples(stat?.samples.orEmpty())

                    view.findViewById<ImageView>(R.id.dragHandle).apply {
                        visibility = if (entry.enabled) View.VISIBLE else View.INVISIBLE
                        setOnTouchListener { _, event ->
                            if (entry.enabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
                                touchHelper.startDrag(holder)
                            }
                            false
                        }
                    }
                    view.findViewById<View>(R.id.container).apply {
                        setOnClickListener {
                            (entries.getOrNull(holder.bindingAdapterPosition) as? Entry.Pack)?.let { togglePack(it) }
                        }
                        applyShape(this, position.indexInGroup(), entry.size)
                    }
                }

                is Entry.Mask -> bindPlainRow(
                    view = view,
                    title = getString(R.string.icon_pack_mask_title),
                    summary = getString(R.string.icon_pack_mask_desc),
                    checked = entry.enabled,
                    topMargin = dpToPx(12)
                ) {
                    IconPackStore.setMaskUnsupported(!entry.enabled)
                    rebuild()
                    updateApplyButton()
                }

                Entry.PerAppIcons -> {
                    val count = IconPackStore.config().let { config ->
                        (IconSlot.entries.flatMap { config.overridesFor(it).keys } + config.labels.keys).toSet().size
                    }
                    bindPlainRow(
                        view = view,
                        title = getString(R.string.per_app_icons_title),
                        summary = if (count > 0) {
                            getString(R.string.per_app_icons_count, count)
                        } else {
                            getString(R.string.per_app_icons_desc)
                        },
                        checked = null
                    ) { openPerAppIcons() }
                }
            }
        }

        private fun bindPlainRow(
            view: View,
            title: String,
            summary: String,
            checked: Boolean?,
            topMargin: Int = 0,
            onClick: () -> Unit
        ) {
            view.findViewById<ImageView>(R.id.icon).visibility = View.GONE
            view.findViewById<ImageView>(R.id.dragHandle).visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.samples).visibility = View.GONE
            view.findViewById<TextView>(R.id.title).text = title
            view.findViewById<TextView>(R.id.summary).apply {
                visibility = View.VISIBLE
                text = summary
            }
            view.findViewById<MaterialSwitch>(R.id.switchView).apply {
                visibility = if (checked != null) View.VISIBLE else View.GONE
                isChecked = checked == true
            }
            view.findViewById<View>(R.id.container).apply {
                setOnClickListener { onClick() }
                applyShape(this, 0, 1, topMargin)
            }
        }

        private fun Int.indexInGroup(): Int {
            var start = this
            while (start > 0 && entries[start - 1] is Entry.Pack) start--
            return this - start
        }

        private fun LinearLayout.bindSamples(samples: List<Drawable>) {
            removeAllViews()
            visibility = if (samples.isEmpty()) View.GONE else View.VISIBLE
            samples.forEach { drawable ->
                addView(ImageView(context).apply {
                    setImageDrawable(drawable)
                }, LinearLayout.LayoutParams(dpToPx(SAMPLE_SIZE_DP), dpToPx(SAMPLE_SIZE_DP)))
            }
            doOnLayout { fitSamples() }
        }

        private fun LinearLayout.fitSamples() {
            val count = childCount
            if (count == 0 || width == 0) return

            val maxSize = dpToPx(SAMPLE_SIZE_DP)
            val maxGap = dpToPx(SAMPLE_GAP_DP)
            val scale = (width.toFloat() / (count * maxSize + (count - 1) * maxGap)).coerceAtMost(1f)
            val size = (maxSize * scale).toInt()
            val gap = (maxGap * scale).toInt()

            for (index in 0 until count) {
                val child = getChildAt(index)
                val params = child.layoutParams as LinearLayout.LayoutParams
                val end = if (index == count - 1) 0 else gap
                if (params.width != size || params.marginEnd != end) {
                    params.width = size
                    params.height = size
                    params.marginEnd = end
                    child.layoutParams = params
                }
            }
        }

        private fun applyShape(container: View, index: Int, size: Int, topMargin: Int = 0) {
            val layoutParams = container.layoutParams as MarginLayoutParams
            RowBackgrounds.apply(container, index, size)
            layoutParams.topMargin = topMargin
            layoutParams.bottomMargin = if (index == size - 1) 0 else dpToPx(2)
            container.layoutParams = layoutParams
            container.clipToOutline = true
        }
    }

    companion object {
        private const val SAMPLE_COUNT = 5
        private const val SAMPLE_SIZE_DP = 28
        private const val SAMPLE_GAP_DP = 6
        private const val TYPE_SECTION = 0
        private const val TYPE_ROW = 1
    }
}

object IconFlow {
    var navigating = false
}
