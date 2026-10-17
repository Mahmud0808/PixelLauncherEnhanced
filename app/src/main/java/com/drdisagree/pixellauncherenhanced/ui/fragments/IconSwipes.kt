package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SWIPE_ACTIONS
import com.drdisagree.pixellauncherenhanced.data.common.IconSwipeActions
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.enums.GestureAction
import com.drdisagree.pixellauncherenhanced.databinding.FragmentHiddenAppsBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.AppEditorActivity
import com.drdisagree.pixellauncherenhanced.utils.IconPackStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class IconSwipes : Fragment() {

    private lateinit var binding: FragmentHiddenAppsBinding
    private var apps: List<IconPackStore.LauncherApp> = emptyList()
    private var shown: List<IconPackStore.LauncherApp> = emptyList()
    private var actions: Map<String, Map<IconSwipeActions.Direction, IconSwipeActions.Action>> = emptyMap()
    private val adapter = SwipeAdapter()

    private val actionLabels by lazy { resources.getStringArray(R.array.gesture_action_str) }
    private val actionValues by lazy { resources.getStringArray(R.array.gesture_action_value) }

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

        val cached = IconPackStore.launcherAppsSnapshot
        if (cached != null) {
            apps = cached.sortedBy { it.label.lowercase(Locale.getDefault()) }
            binding.progressBar.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            refresh()
            return
        }

        binding.recyclerView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) {
                IconPackStore.launcherApps(requireContext().applicationContext).sortedBy { it.label.lowercase(Locale.getDefault()) }
            }
            binding.progressBar.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (apps.isNotEmpty()) refresh()
    }

    private fun refresh() {
        actions = IconSwipeActions.parse(RPrefs.getString(ICON_SWIPE_ACTIONS))
        applyFilter()
    }

    private fun applyFilter() {
        val query = binding.search.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        shown = apps
            .filter { query.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(query) }
            .sortedByDescending { actions[it.component.flattenToString()]?.isNotEmpty() == true }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    private fun summary(app: IconPackStore.LauncherApp): String {
        val entry = actions[app.component.flattenToString()].orEmpty()
        if (entry.isEmpty()) return getString(R.string.icon_swipes_none)
        return IconSwipeActions.Direction.entries.mapNotNull { direction ->
            val action = entry[direction] ?: return@mapNotNull null
            val title = getString(if (direction == IconSwipeActions.Direction.UP) R.string.swipe_up_title else R.string.swipe_down_title)
            "$title: ${actionLabel(action)}"
        }.joinToString(" · ")
    }

    private fun actionLabel(action: IconSwipeActions.Action): String {
        if (action.type == GestureAction.OPEN_APP) {
            val label = action.app?.let { pkg -> apps.firstOrNull { it.component.packageName == pkg }?.label ?: pkg }.orEmpty()
            return getString(R.string.swipe_open_app, label)
        }
        return actionLabels.getOrNull(actionValues.indexOf(action.type.value)).orEmpty()
    }

    companion object {
        private const val SEARCH_HEIGHT_DP = 48
    }

    private inner class SwipeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.view_app_list, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val app = shown[position]
            val view = holder.itemView
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            view.findViewById<MaterialTextView>(R.id.title).text = app.label
            view.findViewById<MaterialTextView>(R.id.summary).text = summary(app)
            view.findViewById<MaterialSwitch>(R.id.switchView).visibility = View.GONE
            view.findViewById<View>(R.id.recommended).visibility = View.GONE
            view.findViewById<View>(R.id.container).setOnClickListener {
                startActivity(AppEditorActivity.intent(requireContext(), app.component, app.label))
            }
            RowBackgrounds.apply(view, position, shown.size)
        }
    }
}
