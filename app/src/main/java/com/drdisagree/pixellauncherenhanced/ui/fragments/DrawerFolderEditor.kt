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
import com.drdisagree.pixellauncherenhanced.data.model.DrawerFolder
import com.drdisagree.pixellauncherenhanced.databinding.FragmentHiddenAppsBinding
import com.drdisagree.pixellauncherenhanced.ui.adapters.AppListAdapter
import com.drdisagree.pixellauncherenhanced.utils.DrawerFoldersStore
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DrawerFolderEditor : Fragment() {

    private lateinit var binding: FragmentHiddenAppsBinding
    private var folderId: Int = 0
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
        folderId = requireArguments().getInt(ARG_FOLDER_ID)

        updateTitle()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val item = menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, R.string.drawer_folders_rename)
                    .setIcon(R.drawable.ic_edit)
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                MenuItemCompat.setIconTintList(
                    item,
                    ColorStateList.valueOf(
                        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
                    )
                )

                menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.drawer_folders_delete)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    MENU_RENAME -> {
                        rename()
                        true
                    }

                    MENU_DELETE -> {
                        confirmDelete()
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

    private fun currentFolder(): DrawerFolder? = DrawerFoldersStore.load().firstOrNull { it.id == folderId }

    private fun updateTitle() {
        setupToolbar(
            requireContext() as AppCompatActivity,
            currentFolder()?.title.orEmpty(),
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )
    }

    private fun rename() {
        val folder = currentFolder() ?: return

        DrawerTabs.showTabNameDialog(
            requireContext(),
            R.string.drawer_folders_rename,
            folder.title,
            R.string.drawer_folders_new_hint
        ) { name ->
            DrawerFoldersStore.update(folderId) { it.copy(title = name) }
            updateTitle()
        }
    }

    private fun confirmDelete() {
        val folder = currentFolder() ?: return

        ExpressiveDialog.show(
            context = requireContext(),
            icon = R.drawable.ic_delete,
            title = getString(R.string.drawer_folders_delete_title),
            message = getString(R.string.drawer_folders_delete_desc, folder.title),
            positive = ExpressiveDialog.Action(getString(R.string.drawer_folders_delete)) {
                DrawerFoldersStore.delete(folderId)
                parentFragmentManager.popBackStackImmediate()
            },
            negative = ExpressiveDialog.Action(getString(android.R.string.cancel)),
            tone = ExpressiveDialog.Tone.ERROR
        )
    }

    private fun loadApps() {
        binding.recyclerView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.search.removeTextChangedListener(textWatcher)

        viewLifecycleOwner.lifecycleScope.launch {
            val folders = DrawerFoldersStore.load()
            val current = folders.firstOrNull { it.id == folderId }
            val members = current?.apps.orEmpty()
            val owners = folders.filter { it.id != folderId && it.belongsTo(current?.tabId) }
                .flatMap { folder -> folder.apps.map { it to folder.title } }
                .toMap()

            appList = withContext(Dispatchers.IO) {
                HiddenApps.getAllLaunchableApps(emptySet())
                    .distinctBy { it.packageName }
                    .map { app ->
                        val selected = app.packageName in members
                        app.copy(
                            isSelected = selected,
                            note = owners[app.packageName]
                                ?.takeIf { !selected }
                                ?.let { getString(R.string.drawer_folders_in, it) }
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
            DrawerFoldersStore.toggleApp(folderId, app.packageName, app.isSelected)
            if (app.isSelected) app.note = null
        }
    }

    companion object {
        const val ARG_FOLDER_ID = "folder_id"
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
    }
}
