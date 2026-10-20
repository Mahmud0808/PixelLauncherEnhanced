package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS_ENABLED
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.DrawerFolder
import com.drdisagree.pixellauncherenhanced.data.model.DrawerTab
import com.drdisagree.pixellauncherenhanced.databinding.FragmentDrawerTabsBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.ui.adapters.DrawerFolderAdapter
import com.drdisagree.pixellauncherenhanced.utils.DrawerFoldersStore
import com.drdisagree.pixellauncherenhanced.utils.DrawerTabsStore
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class DrawerFolders : Fragment() {

    private lateinit var binding: FragmentDrawerTabsBinding
    private lateinit var adapter: DrawerFolderAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private val tabsAdapter = TabsAdapter()
    private val emptyAdapter = EmptyAdapter()
    private var selectedTab: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDrawerTabsBinding.inflate(inflater, container, false)

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.drawer_folders_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let { selectedTab = it.getString(STATE_TAB) }

        adapter = DrawerFolderAdapter(
            onStartDrag = { touchHelper.startDrag(it) },
            onClick = ::openEditor,
            onDelete = ::confirmDelete
        )

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (viewHolder !is DrawerFolderAdapter.FolderHolder) return 0
                return super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (target !is DrawerFolderAdapter.FolderHolder) return false
                adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.02f)?.scaleY(1.02f)?.translationZ(8f)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                DrawerFoldersStore.saveOrder(selectedTab, adapter.folders)
                refreshBackgrounds()
            }
        })

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = ConcatAdapter(tabsAdapter, adapter, emptyAdapter)
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        touchHelper.attachToRecyclerView(binding.recyclerView)

        binding.addTab.setText(R.string.drawer_folders_new)
        binding.addTab.setOnClickListener {
            DrawerTabs.showTabNameDialog(
                requireContext(),
                R.string.drawer_folders_new,
                "",
                R.string.drawer_folders_new_hint
            ) { name -> openEditor(DrawerFoldersStore.create(name, selectedTab)) }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.recyclerView.setPadding(
                binding.recyclerView.paddingLeft,
                binding.recyclerView.paddingTop,
                binding.recyclerView.paddingRight,
                dpToPx(96) + navBarInset
            )
            binding.addTab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dpToPx(24) + navBarInset
            }

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onResume() {
        super.onResume()
        showFolders()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, selectedTab)
    }

    private fun showFolders() {
        val tabs = folderTabs()
        if (tabs.none { it.first == selectedTab }) selectedTab = null
        tabsAdapter.submit(tabs.takeIf { it.size > 1 }.orEmpty())

        val folders = DrawerFoldersStore.load(selectedTab)
        adapter.submit(folders)
        emptyAdapter.show(folders.isEmpty())
    }

    private fun folderTabs(): List<Pair<String?, String>> {
        if (!RPrefs.getBoolean(DRAWER_TABS_ENABLED)) return listOf(null to getString(R.string.drawer_tab_all))
        val tabs = DrawerTabsStore.load().filter { !it.hidden && it.type != DrawerTab.Type.WORK }
        val all = null to getString(R.string.drawer_tab_all)
        return listOf(all) + tabs.filter { it.type != DrawerTab.Type.ALL }.map { it.id to it.displayName(resources) }
    }

    private fun selectTab(tabId: String?) {
        if (selectedTab == tabId) return
        selectedTab = tabId
        showFolders()
    }

    private fun refreshBackgrounds() {
        for (i in 0 until binding.recyclerView.childCount) {
            adapter.updateBackground(binding.recyclerView.getChildViewHolder(binding.recyclerView.getChildAt(i)))
        }
    }

    private fun confirmDelete(folder: DrawerFolder) {
        ExpressiveDialog.show(
            context = requireContext(),
            icon = R.drawable.ic_delete,
            title = getString(R.string.drawer_folders_delete_title),
            message = getString(R.string.drawer_folders_delete_desc, folder.title),
            positive = ExpressiveDialog.Action(getString(R.string.drawer_folders_delete)) {
                DrawerFoldersStore.delete(folder.id)
                showFolders()
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel)),
            tone = ExpressiveDialog.Tone.ERROR
        )
    }

    private fun openEditor(folder: DrawerFolder) {
        MainActivity.replaceFragment(
            parentFragmentManager,
            DrawerFolderEditor().apply {
                arguments = Bundle().apply { putInt(DrawerFolderEditor.ARG_FOLDER_ID, folder.id) }
            }
        )
    }

    private inner class TabsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var tabs: List<Pair<String?, String>> = emptyList()

        fun submit(newTabs: List<Pair<String?, String>>) {
            if (newTabs == tabs) return
            val hadHeader = tabs.isNotEmpty()
            tabs = newTabs
            when {
                hadHeader && tabs.isEmpty() -> notifyItemRemoved(0)
                !hadHeader && tabs.isNotEmpty() -> notifyItemInserted(0)
                tabs.isNotEmpty() -> notifyItemChanged(0)
            }
        }

        override fun getItemCount() = if (tabs.isEmpty()) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_drawer_folder_tabs, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val group = holder.itemView.findViewById<ChipGroup>(R.id.chips)
            group.setOnCheckedStateChangeListener(null)
            group.removeAllViews()
            tabs.forEach { (tabId, name) ->
                val chip = LayoutInflater.from(group.context)
                    .inflate(R.layout.view_drawer_folder_tab_chip, group, false) as Chip
                chip.id = View.generateViewId()
                chip.text = name
                chip.tag = tabId
                group.addView(chip)
                chip.isChecked = tabId == selectedTab
            }
            group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
                val chip = checkedIds.firstOrNull()?.let { chipGroup.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
                selectTab(chip.tag as String?)
            }
        }
    }

    private class EmptyAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var visible = false

        fun show(show: Boolean) {
            if (visible == show) return
            visible = show
            if (show) notifyItemInserted(0) else notifyItemRemoved(0)
        }

        override fun getItemCount() = if (visible) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_drawer_folders_empty, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    companion object {
        private const val STATE_TAB = "folder_tab"
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
}
