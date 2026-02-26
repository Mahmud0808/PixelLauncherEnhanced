package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx

object Utils {

    fun setFirstAndLastItemMargin(holder: PreferenceViewHolder) {
        val itemView = holder.itemView
        val layoutParams = itemView.layoutParams as MarginLayoutParams

        val position = holder.bindingAdapterPosition
        val itemCount = holder.bindingAdapter?.itemCount ?: return

        val baseTop = dpToPx(12)
        val baseBottom = dpToPx(12)
        val midBottom = dpToPx(2)

        when (position) {
            0 -> {
                layoutParams.topMargin = baseTop
                layoutParams.bottomMargin = midBottom
            }

            itemCount - 1 -> {
                layoutParams.topMargin = 0
                layoutParams.bottomMargin = baseBottom

                itemView.doOnAttach {
                    ViewCompat.setOnApplyWindowInsetsListener(itemView) { view, insets ->
                        val navBarInset = insets
                            .getInsets(WindowInsetsCompat.Type.navigationBars())
                            .bottom
                        layoutParams.bottomMargin = baseBottom + navBarInset
                        view.layoutParams = layoutParams
                        insets
                    }
                    ViewCompat.requestApplyInsets(itemView)
                }
            }

            else -> {
                layoutParams.topMargin = 0
                layoutParams.bottomMargin = midBottom
            }
        }

        itemView.layoutParams = layoutParams
    }

    fun Preference.setBackgroundResource(holder: PreferenceViewHolder) {
        parent?.let { parent ->
            val visiblePreferences: MutableList<Preference?> = ArrayList()

            for (i in 0..<parent.preferenceCount) {
                val pref: Preference = parent.getPreference(i)
                if (pref.key != null && PrefsHelper.isVisible(pref.key)
                    && pref !is MasterSwitchPreference
                    && pref !is PreferenceCategory
                    && pref !is HookCheckPreference
                ) {
                    visiblePreferences.add(pref)
                }
            }

            val itemCount = visiblePreferences.size
            val position = visiblePreferences.indexOf(this)

            if (itemCount == 1) {
                holder.itemView.setBackgroundResource(R.drawable.container_single)
            } else if (itemCount > 1) {
                when (position) {
                    0 -> holder
                        .itemView
                        .setBackgroundResource(R.drawable.container_top)

                    itemCount - 1 -> holder
                        .itemView
                        .setBackgroundResource(R.drawable.container_bottom)

                    else -> holder
                        .itemView
                        .setBackgroundResource(R.drawable.container_mid)
                }
            }

            holder.itemView.clipToOutline = true
            holder.isDividerAllowedAbove = false
            holder.isDividerAllowedBelow = false
        }
    }
}