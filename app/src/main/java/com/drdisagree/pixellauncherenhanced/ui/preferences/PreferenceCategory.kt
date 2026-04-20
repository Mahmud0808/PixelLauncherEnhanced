package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx


class PreferenceCategory : PreferenceCategory {

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        initResource()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        initResource()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initResource()
    }

    constructor(context: Context) : super(context) {
        initResource()
    }

    private fun initResource() {
        layoutResource = R.layout.custom_preference_category
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        if (key == null || PrefsHelper.isVisible(key)) {
            val layoutParams = holder.itemView.layoutParams as MarginLayoutParams
            layoutParams.marginStart = dpToPx(12)

            if (holder.getBindingAdapterPosition() == 0) {
                layoutParams.topMargin = dpToPx(12)
                setMinHeight(true, holder.itemView)
            } else {
                if (holder.bindingAdapter != null) {
                    if (holder.getBindingAdapterPosition() == holder.bindingAdapter
                        !!.itemCount - 1
                    ) {
                        layoutParams.bottomMargin = dpToPx(0)
                    }
                }
                layoutParams.topMargin = dpToPx(24)
                setMinHeight(true, holder.itemView)
            }

            holder.itemView.layoutParams = layoutParams
        }
    }

    private fun setMinHeight(zero: Boolean, view: View) {
        if (zero) {
            view.minimumHeight = 0
        } else {
            val typedValue = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.listPreferredItemHeight,
                    typedValue,
                    true
                )
            ) {
                val minHeight = TypedValue.complexToDimensionPixelSize(
                    typedValue.data,
                    context.resources.displayMetrics
                )
                view.minimumHeight = minHeight
            }
        }
    }
}
