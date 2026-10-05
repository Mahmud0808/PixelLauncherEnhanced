package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.databinding.FragmentAppIconsBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.utils.IconApplyDialog
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppIcons : Fragment() {

    private sealed class Item {
        data class Section(val title: Int) : Item()
        data class App(val app: IconPackStore.LauncherApp, val icon: Drawable, val customized: Boolean) : Item()
    }

    private lateinit var binding: FragmentAppIconsBinding
    private var items: List<Item> = emptyList()
    private var loadJob: Job? = null
    private val adapter = ItemAdapter()

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
                override fun getSpanSize(position: Int) = if (items.getOrNull(position) is Item.Section) COLUMNS else 1
            }
        }
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
        updateApplyButton()
        load()
    }

    override fun onStop() {
        super.onStop()
        if (!IconFlow.navigating && !isRemoving) IconPackStore.applyIfChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun load() {
        if (items.isEmpty()) {
            binding.progressBar.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        }

        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext

            items = withContext(Dispatchers.Default) {
                IconPackStore.pruneMissingPackOverrides(context)
                val config = IconPackStore.config()
                val apps = IconPackStore.launcherApps(context).map { app ->
                    Item.App(app, IconPackStore.previewIcon(context, app, config), app.component.flattenToString() in config.overrides)
                }
                val customized = apps.filter { it.customized }

                buildList {
                    if (customized.isNotEmpty()) {
                        add(Item.Section(R.string.per_app_icons_customized))
                        addAll(customized)
                        add(Item.Section(R.string.per_app_icons_all))
                    }
                    addAll(apps)
                }
            }

            updateApplyButton()
            binding.progressBar.visibility = View.GONE
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    private fun openPicker(app: IconPackStore.LauncherApp) {
        IconFlow.navigating = true
        MainActivity.replaceFragment(
            parentFragmentManager,
            IconPicker().apply {
                arguments = Bundle().apply {
                    putString(IconPicker.ARG_COMPONENT, app.component.flattenToString())
                    putString(IconPicker.ARG_LABEL, app.label)
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

    private inner class ItemAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Section) TYPE_SECTION else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_SECTION) R.layout.view_drawer_tab_section else R.layout.view_icon_preview
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Section -> {
                    holder.itemView.findViewById<TextView>(R.id.title).setText(item.title)
                    holder.itemView.findViewById<TextView>(R.id.summary).visibility = View.GONE
                }

                is Item.App -> {
                    holder.itemView.findViewById<ImageView>(R.id.icon).setImageDrawable(item.icon)
                    holder.itemView.findViewById<ImageView>(R.id.badge).visibility =
                        if (item.customized) View.VISIBLE else View.GONE
                    holder.itemView.findViewById<TextView>(R.id.label).text = item.app.label
                    holder.itemView.setOnClickListener { openPicker(item.app) }
                }
            }
        }
    }

    companion object {
        private const val COLUMNS = 5
        private const val TYPE_SECTION = 0
        private const val TYPE_APP = 1
    }
}
