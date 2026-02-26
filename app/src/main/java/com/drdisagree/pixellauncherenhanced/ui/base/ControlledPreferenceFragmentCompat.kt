package com.drdisagree.pixellauncherenhanced.ui.base

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHARED_PREFERENCES
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar

abstract class ControlledPreferenceFragmentCompat : PreferenceFragmentCompat() {

    private val changeListener =
        OnSharedPreferenceChangeListener { _: SharedPreferences, key: String? ->
            updateScreen(key)
        }

    abstract val title: String

    abstract val backButtonEnabled: Boolean

    abstract val layoutResource: Int

    open val themeResource: Int
        get() = R.style.PrefsThemeToolbar

    abstract val hasMenu: Boolean

    open val menuResource: Int
        get() = R.menu.default_menu

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = SHARED_PREFERENCES
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

        try {
            setPreferencesFromResource(layoutResource, rootKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preference from resource", e)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if (activity != null) {
            val window = requireActivity().window
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        inflater.context.setTheme(themeResource)

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(
            requireContext() as AppCompatActivity,
            title,
            backButtonEnabled,
            view.findViewById(R.id.toolbar),
            view.findViewById(R.id.collapsing_toolbar)
        )

        if (hasMenu) {
            val menuHost: MenuHost = requireActivity()
            menuHost.addMenuProvider(object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menu.clear()
                    menuInflater.inflate(menuResource, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        R.id.force_close_launcher -> {
                            context?.restartLauncher()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }
    }

    public override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        RPrefs.registerOnSharedPreferenceChangeListener(changeListener)

        updateScreen(null)

        return super.onCreateAdapter(preferenceScreen)
    }

    override fun onResume() {
        super.onResume()
        updateScreen(null)
    }

    override fun onDestroy() {
        RPrefs.unregisterOnSharedPreferenceChangeListener(changeListener)

        super.onDestroy()
    }

    open fun updateScreen(key: String?) {
        PrefsHelper.setupAllPreferences(this.preferenceScreen)
    }

    override fun setDivider(divider: Drawable?) {
        super.setDivider(Color.TRANSPARENT.toDrawable())
    }

    override fun setDividerHeight(height: Int) {
        super.setDividerHeight(0)
    }

    companion object {
        private val TAG = ControlledPreferenceFragmentCompat::class.java.simpleName
    }
}
