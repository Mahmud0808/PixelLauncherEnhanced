package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOUBLE_TAP_TO_SLEEP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.enums.GestureAction
import com.drdisagree.pixellauncherenhanced.data.enums.HomeGesture
import com.drdisagree.pixellauncherenhanced.ui.adapters.AppPickerAdapter
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.ListPreference
import com.drdisagree.pixellauncherenhanced.ui.preferences.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GesturesMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_gestures_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.gestures_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        migrateDoubleTapToSleep()

        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<SwitchPreference>(HIDE_GESTURE_PILL)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference, newValue ->
                if (newValue == false && RPrefs.getBoolean(HIDE_NAVIGATION_SPACE)) {
                    RPrefs.putBoolean(HIDE_NAVIGATION_SPACE, false)
                    findPreference<SwitchPreference>(HIDE_NAVIGATION_SPACE)?.isChecked = false
                }
                true
            }

        HomeGesture.entries.forEach { gesture ->
            findPreference<ListPreference>(gesture.key)?.apply {
                updateSummary(this, gesture, value)

                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    if (newValue == GestureAction.OPEN_APP.value) {
                        pickApp(this, gesture)
                        false
                    } else {
                        updateSummary(this, gesture, newValue as String)
                        true
                    }
                }
            }
        }
    }

    private fun migrateDoubleTapToSleep() {
        if (!RPrefs.getBoolean(DOUBLE_TAP_TO_SLEEP)) return

        if (!RPrefs.contains(HomeGesture.DOUBLE_TAP.key)) {
            RPrefs.putString(HomeGesture.DOUBLE_TAP.key, GestureAction.SLEEP.value)
        }
        RPrefs.clearPref(DOUBLE_TAP_TO_SLEEP)
    }

    private fun updateSummary(preference: ListPreference, gesture: HomeGesture, value: String?) {
        val appLabel = RPrefs.getString(gesture.appKey)
            ?.takeIf { value == GestureAction.OPEN_APP.value }
            ?.let { packageName ->
                runCatching {
                    val packageManager = requireContext().packageManager
                    packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager)
                }.getOrNull()
            }

        preference.summary = if (appLabel != null) {
            getString(R.string.gesture_opens_app, appLabel)
        } else {
            getString(gesture.descriptionRes())
        }
    }

    private fun pickApp(preference: ListPreference, gesture: HomeGesture) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_app_picker, null)
        val list = view.findViewById<RecyclerView>(R.id.app_list)
        val search = view.findViewById<EditText>(R.id.search)
        val progress = view.findViewById<View>(R.id.progress)

        list.layoutManager = LinearLayoutManager(requireContext())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.gesture_choose_app)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                HiddenApps.getAllLaunchableApps(emptySet()).distinctBy { it.packageName }
            }

            val adapter = AppPickerAdapter(apps) { app ->
                RPrefs.putString(gesture.appKey, app.packageName)
                preference.value = GestureAction.OPEN_APP.value
                updateSummary(preference, gesture, GestureAction.OPEN_APP.value)
                dialog.dismiss()
            }

            progress.visibility = View.GONE
            list.adapter = adapter
            search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
        }
    }

    private fun HomeGesture.descriptionRes(): Int = when (this) {
        HomeGesture.DOUBLE_TAP -> R.string.gesture_double_tap_desc
        HomeGesture.PINCH_IN -> R.string.gesture_pinch_in_desc
        HomeGesture.PINCH_OUT -> R.string.gesture_pinch_out_desc
    }
}
