package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuItemCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.AppInfoModel
import com.drdisagree.pixellauncherenhanced.data.model.DrawerTab
import com.drdisagree.pixellauncherenhanced.databinding.FragmentHiddenAppsBinding
import com.drdisagree.pixellauncherenhanced.ui.adapters.AppListAdapter
import com.drdisagree.pixellauncherenhanced.utils.DrawerTabsStore
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DrawerTabEditor : Fragment() {

    private lateinit var binding: FragmentHiddenAppsBinding
    private lateinit var tabId: String
    private var appList: List<AppInfoModel> = emptyList()

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            val query = s.toString().trim()
            binding.clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            showApps(query)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHiddenAppsBinding.inflate(inflater, container, false)
        tabId = requireArguments().getString(ARG_TAB_ID)!!

        updateTitle()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                if (currentTab()?.isBuiltIn == true) {
                    menu.add(Menu.NONE, MENU_RESET, Menu.NONE, R.string.drawer_tabs_reset)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    return
                }

                val item = menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, R.string.drawer_tabs_rename)
                    .setIcon(R.drawable.ic_edit)
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                MenuItemCompat.setIconTintList(
                    item,
                    ColorStateList.valueOf(
                        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
                    )
                )
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    MENU_RENAME -> {
                        rename()
                        true
                    }

                    MENU_RESET -> {
                        DrawerTabsStore.update(tabId) { it.copy(apps = emptySet(), excluded = emptySet()) }
                        loadApps()
                        true
                    }

                    android.R.id.home -> {
                        parentFragmentManager.popBackStackImmediate()
                        true
                    }

                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { list, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dpToPx(16) + navBarInset)
            insets
        }
        ViewCompat.requestApplyInsets(binding.recyclerView)

        binding.clear.setOnClickListener { binding.search.setText("") }
        loadApps()
    }

    private fun currentTab(): DrawerTab? = DrawerTabsStore.load().firstOrNull { it.id == tabId }

    private fun updateTitle() {
        setupToolbar(
            requireContext() as AppCompatActivity,
            currentTab()?.displayName(resources).orEmpty(),
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )
    }

    private fun rename() {
        val tab = currentTab() ?: return

        DrawerTabs.showTabNameDialog(requireContext(), R.string.drawer_tabs_rename, tab.name) { name ->
            DrawerTabsStore.update(tabId) { it.copy(name = name) }
            updateTitle()
        }
    }

    private fun loadApps() {
        binding.recyclerView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.search.removeTextChangedListener(textWatcher)

        viewLifecycleOwner.lifecycleScope.launch {
            val tab = currentTab()
            val packageManager = requireContext().packageManager
            appList = withContext(Dispatchers.IO) {
                HiddenApps.getAllLaunchableApps(emptySet())
                    .distinctBy { it.packageName }
                    .map { app ->
                        val info = runCatching { packageManager.getApplicationInfo(app.packageName, 0) }.getOrNull()
                        app.copy(
                            isSelected = tab?.matches(app.packageName, info) == true,
                            recommended = tab?.recommends(info) == true
                        )
                    }
                    .sortedWith(compareBy<AppInfoModel> { !it.isSelected }.thenBy { it.appName.lowercase() })
            }

            binding.progressBar.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.search.addTextChangedListener(textWatcher)
            showApps(binding.search.text.toString().trim())
        }
    }

    private fun showApps(query: String) {
        val lowerQuery = query.lowercase(Locale.getDefault())
        val filtered = if (lowerQuery.isEmpty()) {
            appList
        } else {
            appList.filter {
                it.appName.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                        it.packageName.lowercase(Locale.getDefault()).contains(lowerQuery)
            }.sortedBy { !it.appName.lowercase(Locale.getDefault()).startsWith(lowerQuery) }
        }

        binding.recyclerView.adapter = AppListAdapter(filtered) { app ->
            DrawerTabsStore.update(tabId) { tab -> tab.withApp(app) }
        }
    }

    private fun DrawerTab.withApp(app: AppInfoModel): DrawerTab {
        val pkg = app.packageName

        if (!isBuiltIn) return copy(apps = if (app.isSelected) apps + pkg else apps - pkg)

        return if (app.isSelected) {
            copy(apps = if (app.recommended) apps - pkg else apps + pkg, excluded = excluded - pkg)
        } else {
            copy(apps = apps - pkg, excluded = if (app.recommended) excluded + pkg else excluded - pkg)
        }
    }

    companion object {
        const val ARG_TAB_ID = "tab_id"
        private const val MENU_RENAME = 1
        private const val MENU_RESET = 2
    }
}
