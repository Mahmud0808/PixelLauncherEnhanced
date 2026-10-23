package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.res.ColorStateList
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.data.enums.SegmentPosition
import com.drdisagree.pixellauncherenhanced.ui.drawables.SegmentedRowDrawable
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

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
                layoutParams.bottomMargin = 0
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
            val index = visiblePreferences.indexOf(this)
            val position = when {
                itemCount <= 1 -> SegmentPosition.SINGLE
                index == 0 -> SegmentPosition.TOP
                index == itemCount - 1 -> SegmentPosition.BOTTOM
                else -> SegmentPosition.MIDDLE
            }

            val existing = holder.itemView.background as? SegmentedRowDrawable
            val row = existing ?: SegmentedRowDrawable(context).also { holder.itemView.background = it }
            row.setPosition(position)

            holder.itemView.clipToOutline = true
            holder.isDividerAllowedAbove = false
            holder.isDividerAllowedBelow = false
        }
    }

    fun applyChoiceColors(button: MaterialButton) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val selectedFill = MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary)
        val selectedText = MaterialColors.getColor(button, MaterialR.attr.colorOnPrimary)
        val idleFill = MaterialColors.getColor(button, MaterialR.attr.colorSecondaryContainer)
        val idleText = MaterialColors.getColor(button, MaterialR.attr.colorOnSecondaryContainer)
        val faded = ColorUtils.setAlphaComponent(idleText, 0x61)
        val fadedFill = ColorUtils.setAlphaComponent(idleFill, 0x61)
        button.backgroundTintList = ColorStateList(
            arrayOf(disabled, checked, intArrayOf()),
            intArrayOf(fadedFill, selectedFill, idleFill)
        )
        button.setTextColor(ColorStateList(arrayOf(disabled, checked, intArrayOf()), intArrayOf(faded, selectedText, idleText)))
        button.strokeWidth = 0
        button.rippleColor = ColorStateList(
            arrayOf(checked, intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(selectedText, 0x1F), ColorUtils.setAlphaComponent(idleText, 0x1F))
        )
    }
}
