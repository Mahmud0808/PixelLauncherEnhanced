package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.view.ViewGroup.MarginLayoutParams
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.PLEnhanced.Companion.appContext
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx

object Utils {

    fun setFirstAndLastItemMargin(holder: PreferenceViewHolder) {
        val layoutParams = holder.itemView.layoutParams as MarginLayoutParams

        if (holder.getBindingAdapterPosition() == 0) {
            // Set margin for the first item
            //            layoutParams.topMargin = appContext.toPx(12)
        } else {
            if (holder.bindingAdapter != null) {
                if (holder.getBindingAdapterPosition() == holder.bindingAdapter!!.itemCount - 1) {
                    // Set margin for the last item
                    layoutParams.bottomMargin = appContext.toPx(48)
                } else {
                    //                    layoutParams.topMargin = 0
                    layoutParams.bottomMargin = appContext.toPx(0)
                }
            }
        }

        holder.itemView.setLayoutParams(layoutParams)
    }

    fun setBackgroundResource(preference: Preference, holder: PreferenceViewHolder) {
        val parent = preference.parent

        if (parent != null) {
            val visiblePreferences: MutableList<Preference?> = ArrayList<Preference?>()

            for (i in 0..<parent.preferenceCount) {
                val pref = parent.getPreference(i)
                if (pref.key != null && PrefsHelper.isVisible(pref.key) && pref !is MasterSwitchPreference) {
                    visiblePreferences.add(pref)
                }
            }

            val itemCount = visiblePreferences.size
            val position = visiblePreferences.indexOf(preference)

            //            if (itemCount == 1) {
            //                holder.itemView.setBackgroundResource(R.drawable.container_single)
            //            } else if (itemCount > 1) {
            //                if (position == 0) {
            //                    holder.itemView.setBackgroundResource(R.drawable.container_top)
            //                } else if (position == itemCount - 1) {
            //                    holder.itemView.setBackgroundResource(R.drawable.container_bottom)
            //                } else {
            //                    holder.itemView.setBackgroundResource(R.drawable.container_mid)
            //                }
            //            }

            holder.itemView.setClipToOutline(true)
            holder.isDividerAllowedAbove = false
            holder.isDividerAllowedBelow = false
        }
    }
}