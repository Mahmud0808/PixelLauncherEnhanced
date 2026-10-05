package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.DrawerTab
import com.drdisagree.pixellauncherenhanced.databinding.FragmentDrawerTabsBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.ui.adapters.DrawerTabAdapter
import com.drdisagree.pixellauncherenhanced.utils.DrawerTabsStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class DrawerTabs : Fragment() {

    private lateinit var binding: FragmentDrawerTabsBinding
    private lateinit var adapter: DrawerTabAdapter
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDrawerTabsBinding.inflate(inflater, container, false)

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.fragment_drawer_tabs_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DrawerTabAdapter(
            onStartDrag = { touchHelper.startDrag(it) },
            onClick = { tab -> if (!tab.isBuiltIn) openEditor(tab) },
            onRemove = { tab -> if (tab.isBuiltIn) setHidden(tab, true) else confirmDelete(tab) },
            onAdd = { tab -> setHidden(tab, false) }
        )

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (!adapter.isActiveTab(viewHolder.bindingAdapterPosition)) return 0
                return super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = adapter.isActiveTab(target.bindingAdapterPosition)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
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
                DrawerTabsStore.save(adapter.tabs)
                refreshBackgrounds()
            }
        })

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        touchHelper.attachToRecyclerView(binding.recyclerView)

        binding.addTab.setOnClickListener {
            showTabNameDialog(requireContext(), R.string.drawer_tabs_new, "") { name ->
                val tab = DrawerTab.newCustom(name)
                DrawerTabsStore.save(DrawerTabsStore.load() + tab)
                openEditor(tab)
            }
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
        adapter.submit(DrawerTabsStore.load())
    }

    private fun refreshBackgrounds() {
        for (i in 0 until binding.recyclerView.childCount) {
            adapter.updateBackground(
                binding.recyclerView.getChildViewHolder(binding.recyclerView.getChildAt(i))
            )
        }
    }

    private fun setHidden(tab: DrawerTab, hidden: Boolean) {
        val others = adapter.tabs.filterNot { it.id == tab.id }
        val updated = tab.copy(hidden = hidden)
        val tabs = if (hidden) {
            others + updated
        } else {
            others.filterNot { it.hidden } + updated + others.filter { it.hidden }
        }

        DrawerTabsStore.save(tabs)
        adapter.submit(tabs)
    }

    private fun confirmDelete(tab: DrawerTab) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.drawer_tabs_delete_title)
            .setMessage(getString(R.string.drawer_tabs_delete_desc, tab.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.drawer_tabs_delete) { _, _ ->
                DrawerTabsStore.save(DrawerTabsStore.load().filterNot { it.id == tab.id })
                adapter.submit(DrawerTabsStore.load())
            }
            .show()
    }

    private fun openEditor(tab: DrawerTab) {
        MainActivity.replaceFragment(
            parentFragmentManager,
            DrawerTabEditor().apply {
                arguments = Bundle().apply { putString(DrawerTabEditor.ARG_TAB_ID, tab.id) }
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
        fun showTabNameDialog(
            context: Context,
            @StringRes title: Int,
            initialName: String,
            onSave: (String) -> Unit
        ) {
            val input = TextInputEditText(context).apply {
                setText(initialName)
                setSelection(initialName.length)
                isSingleLine = true
            }
            val inputLayout = TextInputLayout(context).apply {
                hint = context.getString(R.string.drawer_tabs_new_hint)
                addView(input)
            }
            val container = FrameLayout(context).apply {
                setPadding(dpToPx(24), dpToPx(8), dpToPx(24), 0)
                addView(inputLayout)
            }

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .show()

            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()

                if (name.isEmpty()) {
                    inputLayout.error = context.getString(R.string.drawer_tabs_name_empty)
                } else {
                    dialog.dismiss()
                    onSave(name)
                }
            }

            input.requestFocus()
        }
    }
}
