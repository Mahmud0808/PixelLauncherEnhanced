package com.drdisagree.pixellauncherenhanced.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.drdisagree.pixellauncherenhanced.data.model.DrawerTab
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import java.util.Collections

class DrawerTabAdapter(
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onClick: (DrawerTab) -> Unit,
    private val onRemove: (DrawerTab) -> Unit,
    private val onAdd: (DrawerTab) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Entry(val key: String) {
        data class Section(val active: Boolean) : Entry(if (active) "section_active" else "section_available")

        data class Tab(
            val tab: DrawerTab,
            val active: Boolean,
            val index: Int,
            val size: Int
        ) : Entry(tab.id)
    }

    private val activeTabs = mutableListOf<DrawerTab>()
    private val availableTabs = mutableListOf<DrawerTab>()
    private var entries: List<Entry> = emptyList()

    val tabs: List<DrawerTab>
        get() = activeTabs + availableTabs

    fun submit(tabs: List<DrawerTab>) {
        activeTabs.clear()
        availableTabs.clear()
        tabs.forEach { if (it.hidden) availableTabs.add(it) else activeTabs.add(it) }

        val old = entries
        val new = buildEntries()

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].key == new[newPos].key
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
        }).also { entries = new }.dispatchUpdatesTo(this)
    }

    private fun buildEntries(): List<Entry> {
        val result = mutableListOf<Entry>(Entry.Section(true))
        activeTabs.forEachIndexed { index, tab -> result.add(Entry.Tab(tab, true, index, activeTabs.size)) }

        if (availableTabs.isNotEmpty()) {
            result.add(Entry.Section(false))
            availableTabs.forEachIndexed { index, tab ->
                result.add(Entry.Tab(tab, false, index, availableTabs.size))
            }
        }

        return result
    }

    fun isActiveTab(position: Int) = (entries.getOrNull(position) as? Entry.Tab)?.active == true

    fun move(from: Int, to: Int) {
        if (!isActiveTab(from) || !isActiveTab(to)) return

        Collections.swap(activeTabs, from - 1, to - 1)
        entries = buildEntries()
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = entries.size

    override fun getItemViewType(position: Int): Int {
        return if (entries[position] is Entry.Section) TYPE_SECTION else TYPE_TAB
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_SECTION) {
            SectionHolder(inflater.inflate(R.layout.view_drawer_tab_section, parent, false))
        } else {
            TabHolder(inflater.inflate(R.layout.view_drawer_tab, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is Entry.Section -> bindSection(holder as SectionHolder, entry.active)
            is Entry.Tab -> bindTab(holder as TabHolder, entry)
        }
    }

    private fun bindSection(holder: SectionHolder, active: Boolean) {
        holder.title.setText(
            if (active) R.string.drawer_tabs_active_title else R.string.drawer_tabs_available_title
        )
        holder.summary.setText(
            if (active) R.string.drawer_tabs_active_desc else R.string.drawer_tabs_available_desc
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTab(holder: TabHolder, entry: Entry.Tab) {
        val tab = entry.tab
        val active = entry.active
        val context = holder.itemView.context

        holder.title.text = tab.displayName(context.resources)
        holder.summary.text = when (tab.type) {
            DrawerTab.Type.ALL -> context.getString(R.string.drawer_tab_desc_all)
            DrawerTab.Type.WORK -> context.getString(
                if (active) R.string.drawer_tabs_work_desc else R.string.drawer_tabs_work_removed
            )

            DrawerTab.Type.GAMES -> context.getString(R.string.drawer_tab_desc_games)
            DrawerTab.Type.SOCIAL -> context.getString(R.string.drawer_tab_desc_social)
            DrawerTab.Type.MEDIA -> context.getString(R.string.drawer_tab_desc_media)
            DrawerTab.Type.PRODUCTIVITY -> context.getString(R.string.drawer_tab_desc_productivity)
            DrawerTab.Type.NEWS -> context.getString(R.string.drawer_tab_desc_news)
            DrawerTab.Type.NAVIGATION -> context.getString(R.string.drawer_tab_desc_navigation)
            DrawerTab.Type.CUSTOM -> if (tab.apps.isEmpty()) {
                context.getString(R.string.drawer_tabs_custom_empty)
            } else {
                context.getString(R.string.drawer_tabs_custom_count, tab.apps.size)
            }
        }.let { summary ->
            if (tab.isCustomized) context.getString(R.string.drawer_tabs_customized, summary) else summary
        }

        holder.dragHandle.visibility = if (active) View.VISIBLE else View.INVISIBLE
        holder.dragHandle.setOnTouchListener { _, event ->
            if (active && event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }

        holder.action.apply {
            setImageResource(if (active) R.drawable.ic_close else R.drawable.ic_add)
            contentDescription = context.getString(
                if (active) R.string.drawer_tabs_remove else R.string.drawer_tabs_add
            )
            setOnClickListener {
                val current = entries.getOrNull(holder.bindingAdapterPosition) as? Entry.Tab
                    ?: return@setOnClickListener
                if (current.active) onRemove(current.tab) else onAdd(current.tab)
            }
        }

        holder.container.setOnClickListener {
            (entries.getOrNull(holder.bindingAdapterPosition) as? Entry.Tab)?.let { onClick(it.tab) }
        }

        applyShape(holder, entry.index, entry.size)
    }

    fun updateBackground(holder: RecyclerView.ViewHolder) {
        if (holder !is TabHolder) return
        val entry = entries.getOrNull(holder.bindingAdapterPosition) as? Entry.Tab ?: return
        applyShape(holder, entry.index, entry.size)
    }

    private fun applyShape(holder: TabHolder, index: Int, size: Int) {
        val layoutParams = holder.itemView.layoutParams as MarginLayoutParams

        RowBackgrounds.apply(holder.container, index, size)
        layoutParams.bottomMargin = if (index == size - 1) 0 else dpToPx(2)
        holder.itemView.layoutParams = layoutParams
        holder.container.clipToOutline = true
    }

    class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val summary: TextView = view.findViewById(R.id.summary)
    }

    class TabHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.container)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val title: TextView = view.findViewById(R.id.title)
        val summary: TextView = view.findViewById(R.id.summary)
        val action: ImageButton = view.findViewById(R.id.action)
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_TAB = 1
    }
}
