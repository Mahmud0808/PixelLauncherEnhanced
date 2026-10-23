package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.GLASS_EDITOR_TARGET
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.glass.GlassTarget
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.SliderPreference
import com.drdisagree.pixellauncherenhanced.ui.widgets.GlassPreviewView
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx

class GlassEditor : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.glass_editor_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.glass_editor

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeGlassEditor

    private var preview: GlassPreviewView? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (savedInstanceState == null) {
            GlassTarget.forId(arguments?.getString(ARG_TARGET))?.let {
                RPrefs.putString(GLASS_EDITOR_TARGET, it.id)
            }
        }
        super.onCreatePreferences(savedInstanceState, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val progress = view.findViewById<View>(R.id.glassPreviewProgress)
        preview = view.findViewById<GlassPreviewView>(R.id.glassPreview).apply {
            target = PrefsHelper.selectedGlassTarget()
            onLoaded = {
                progress.animate().alpha(0f).withEndAction { progress.visibility = View.GONE }.start()
            }
        }
        preferenceScreen?.let { bindSliders(it) }

        val fade = view.findViewById<View>(R.id.glassListFade)
        listView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                fade.alpha = (recyclerView.computeVerticalScrollOffset() / dpToPx(FADE_DISTANCE_DP).toFloat()).coerceIn(0f, 1f)
            }
        })
    }

    private fun bindSliders(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            when (val pref = group.getPreference(i)) {
                is SliderPreference -> pref.onSlide = { value -> preview?.preview(pref.key, value) }
                is PreferenceGroup -> bindSliders(pref)
            }
        }
    }

    override fun onDestroyView() {
        preview = null
        super.onDestroyView()
    }

    override fun updateScreen(key: String?) {
        super.updateScreen(key)
        preview?.clearPreview(key)
    }

    companion object {
        const val ARG_TARGET = "glass_target"
        private const val FADE_DISTANCE_DP = 24
    }
}
