package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.ui.fragments.SettingsSearch
import com.google.android.material.card.MaterialCardView

class DashboardSearchPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_dashboard_search
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<MaterialCardView>(R.id.search_card).apply {
            transitionName = TRANSITION_NAME
            setOnClickListener { (context as? MainActivity)?.openFromDashboard(SettingsSearch::class.java, this) }
        }
    }

    companion object {
        const val TRANSITION_NAME = "dashboard_search"
    }
}
