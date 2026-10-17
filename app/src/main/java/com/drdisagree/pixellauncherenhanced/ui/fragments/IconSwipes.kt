package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.ComponentName
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SWIPE_ACTIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS
import com.drdisagree.pixellauncherenhanced.data.common.IconSwipeActions
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.enums.GestureAction
import com.drdisagree.pixellauncherenhanced.databinding.FragmentHiddenAppsBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.AppEditorActivity
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.drdisagree.pixellauncherenhanced.utils.ShortcutPicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class IconSwipes : Fragment() {

    private class Target(val component: ComponentName, val label: String, val icon: () -> Drawable?) {
        val key: String = component.flattenToString()
    }

    private sealed class Item {
        data class Section(@param:StringRes val title: Int) : Item()
        class Row(val target: Target, val index: Int, val count: Int) : Item()
    }

    private lateinit var binding: FragmentHiddenAppsBinding
    private var apps: List<Target> = emptyList()
    private var shortcuts: List<Target> = emptyList()
    private var items: List<Item> = emptyList()
    private var actions: Map<String, Map<IconSwipeActions.Direction, IconSwipeActions.Action>> = emptyMap()
    private var actionsBefore: Map<String, Map<IconSwipeActions.Direction, IconSwipeActions.Action>> = emptyMap()
    private var configuredOnEntry: Set<String> = emptySet()
    private val adapter = SwipeAdapter()
    private val shortcutIcons = HashMap<String, Drawable?>()

    private val actionLabels by lazy { resources.getStringArray(R.array.gesture_action_str) }
    private val actionValues by lazy { resources.getStringArray(R.array.gesture_action_value) }

    private val shortcutsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PINNED_SHORTCUTS) {
            loadShortcuts()
            refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentHiddenAppsBinding.inflate(inflater, container, false)
        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.fragment_icon_swipes_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configuredOnEntry = IconSwipeActions.parse(RPrefs.getString(ICON_SWIPE_ACTIONS)).filterValues { it.isNotEmpty() }.keys

        val searchSpace = dpToPx(SEARCH_HEIGHT_DP) + resources.getDimensionPixelSize(R.dimen.ui_container_margin_side) * 2
        binding.recyclerView.setPadding(0, searchSpace, 0, binding.recyclerView.paddingBottom)

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { list, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dpToPx(16) + navBarInset)
            insets
        }
        ViewCompat.requestApplyInsets(binding.recyclerView)

        binding.search.doAfterTextChanged {
            binding.clear.visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE
            applyFilter()
        }
        binding.clear.setOnClickListener { binding.search.setText("") }

        loadShortcuts()

        val cached = IconPackStore.launcherAppsSnapshot
        if (cached != null) {
            apps = cached.toTargets()
            binding.progressBar.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            refresh()
            return
        }

        binding.recyclerView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { IconPackStore.launcherApps(requireContext().applicationContext) }
            apps = loaded.toTargets()
            binding.progressBar.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        RPrefs.registerOnSharedPreferenceChangeListener(shortcutsListener)
        IconPackStore.requestPinnedShortcuts()
    }

    override fun onStop() {
        super.onStop()
        RPrefs.unregisterOnSharedPreferenceChangeListener(shortcutsListener)
    }

    override fun onResume() {
        super.onResume()
        if (apps.isNotEmpty() || shortcuts.isNotEmpty()) refresh()
    }

    private fun List<IconPackStore.LauncherApp>.toTargets(): List<Target> =
        sortedBy { it.label.lowercase(Locale.getDefault()) }.map { app -> Target(app.component, app.label) { app.icon } }

    private fun loadShortcuts() {
        val context = context ?: return
        shortcuts = IconPackStore.pinnedShortcuts().map { shortcut ->
            val key = shortcut.component.flattenToString()
            Target(shortcut.component, shortcut.label) {
                shortcutIcons.getOrPut(key) { IconPackStore.shortcutIcon(context, shortcut) }
            }
        }
    }

    private fun refresh() {
        if (!::binding.isInitialized) return
        actions = IconSwipeActions.parse(RPrefs.getString(ICON_SWIPE_ACTIONS))
        applyFilter()
    }

    private fun applyFilter() {
        val query = binding.search.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        fun List<Target>.filtered() = filter { query.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(query) }
            .sortedByDescending { it.key in configuredOnEntry }

        val shownShortcuts = shortcuts.filtered()
        val shownApps = apps.filtered()
        val old = items
        val updated = buildList {
            if (shownShortcuts.isNotEmpty()) {
                add(Item.Section(R.string.per_app_icons_shortcuts))
                shownShortcuts.forEachIndexed { index, target -> add(Item.Row(target, index, shownShortcuts.size)) }
                if (shownApps.isNotEmpty()) add(Item.Section(R.string.per_app_icons_all))
            }
            shownApps.forEachIndexed { index, target -> add(Item.Row(target, index, shownApps.size)) }
        }
        items = updated
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = updated.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].key() == updated[newPos].key()
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val before = old[oldPos]
                val after = updated[newPos]
                if (before !is Item.Row || after !is Item.Row) return true
                return before.index == after.index && before.count == after.count &&
                        actionsBefore[before.target.key] == actions[after.target.key]
            }
        }).dispatchUpdatesTo(adapter)
        actionsBefore = actions
    }

    private fun Item.key(): String = when (this) {
        is Item.Section -> "section:$title"
        is Item.Row -> "row:${target.key}"
    }

    private fun summary(target: Target): String {
        val entry = actions[target.key].orEmpty()
        if (entry.isEmpty()) return getString(R.string.icon_swipes_none)
        return IconSwipeActions.Direction.entries.mapNotNull { direction ->
            val action = entry[direction] ?: return@mapNotNull null
            val title = getString(if (direction == IconSwipeActions.Direction.UP) R.string.swipe_up_title else R.string.swipe_down_title)
            "$title: ${actionLabel(action)}"
        }.joinToString(" · ")
    }

    private fun actionLabel(action: IconSwipeActions.Action): String {
        if (action.type == GestureAction.OPEN_SHORTCUT) {
            ShortcutPicker.label(requireContext(), action.app)?.let { return it }
        }
        if (action.type == GestureAction.OPEN_APP) {
            val label = action.app?.let { pkg -> apps.firstOrNull { it.component.packageName == pkg }?.label ?: pkg }.orEmpty()
            return getString(R.string.swipe_open_app, label)
        }
        return actionLabels.getOrNull(actionValues.indexOf(action.type.value)).orEmpty()
    }

    companion object {
        private const val SEARCH_HEIGHT_DP = 48
        private const val TYPE_SECTION = 0
        private const val TYPE_ROW = 1
    }

    private inner class SwipeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Section) TYPE_SECTION else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_SECTION) R.layout.view_drawer_tab_section else R.layout.view_app_list
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val item = items[position]) {
                is Item.Section -> {
                    view.findViewById<TextView>(R.id.title).apply {
                        visibility = View.VISIBLE
                        setText(item.title)
                    }
                    view.findViewById<TextView>(R.id.summary)?.visibility = View.GONE
                }

                is Item.Row -> {
                    val target = item.target
                    view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(target.icon())
                    view.findViewById<MaterialTextView>(R.id.title).text = target.label
                    view.findViewById<MaterialTextView>(R.id.summary).text = summary(target)
                    view.findViewById<MaterialSwitch>(R.id.switchView).visibility = View.GONE
                    view.findViewById<View>(R.id.recommended).visibility = View.GONE
                    view.findViewById<View>(R.id.container).setOnClickListener {
                        startActivity(AppEditorActivity.intent(requireContext(), target.component, target.label))
                    }
                    RowBackgrounds.apply(view, item.index, item.count)
                }
            }
        }
    }
}
