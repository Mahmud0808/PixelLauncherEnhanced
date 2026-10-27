package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.widgets.PageIllustrationView

class PageHeaderPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_page_header
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        val container = holder.itemView as? FrameLayout ?: return
        val scene = scene() ?: return
        val existing = container.getChildAt(0) as? PageIllustrationView
        if (existing?.tag == scene) return
        container.removeAllViews()
        container.addView(
            PageIllustrationView(context, scene).apply { tag = scene },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun scene(): PageIllustrationView.Scene? {
        val name = key?.removePrefix(KEY_PREFIX)?.uppercase() ?: return null
        return PageIllustrationView.Scene.entries.firstOrNull { it.name == name }
    }

    companion object {
        private const val KEY_PREFIX = "page_header_"
    }
}
