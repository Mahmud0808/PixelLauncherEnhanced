package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

object ValuePill {

    private const val MAX_WIDTH_FRACTION = 0.35f
    private const val RADIUS_DP = 12f
    private const val PADDING_H_DP = 10f
    private const val PADDING_RESET_END_DP = 8f
    private const val PADDING_V_DP = 4f
    private const val STROKE_DP = 1f
    private const val RESET_ICON_DP = 16f
    private const val RESET_GAP_DP = 8f

    fun bind(view: TextView?, text: CharSequence?, isDefault: Boolean, onReset: ((View) -> Unit)? = null) {
        view ?: return
        if (text.isNullOrEmpty()) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            view.isClickable = false
            return
        }
        view.visibility = View.VISIBLE
        view.text = text

        val density = view.resources.displayMetrics.density
        view.maxWidth = (view.resources.displayMetrics.widthPixels * MAX_WIDTH_FRACTION).toInt()
        view.maxLines = 1
        view.ellipsize = TextUtils.TruncateAt.END
        val endPadding = if (onReset != null) PADDING_RESET_END_DP else PADDING_H_DP
        view.setPaddingRelative(
            (PADDING_H_DP * density).toInt(),
            (PADDING_V_DP * density).toInt(),
            (endPadding * density).toInt(),
            (PADDING_V_DP * density).toInt()
        )

        val textColor = MaterialColors.getColor(
            view,
            if (isDefault) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorOnSecondaryContainer
        )
        val shape = GradientDrawable().apply {
            cornerRadius = RADIUS_DP * density
            if (isDefault) {
                setColor(Color.TRANSPARENT)
                setStroke((STROKE_DP * density).toInt(), MaterialColors.getColor(view, MaterialR.attr.colorOutlineVariant))
            } else {
                setColor(MaterialColors.getColor(view, MaterialR.attr.colorSecondaryContainer))
            }
        }
        view.setTextColor(textColor)

        if (onReset != null) {
            val mask = GradientDrawable().apply {
                cornerRadius = RADIUS_DP * density
                setColor(Color.WHITE)
            }
            view.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 0x29)), shape, mask)
            val size = (RESET_ICON_DP * density).toInt()
            val icon = AppCompatResources.getDrawable(view.context, R.drawable.ic_reset_small)?.mutate()?.apply {
                setBounds(0, 0, size, size)
                setTint(textColor)
            }
            view.setCompoundDrawablesRelative(null, null, icon, null)
            view.compoundDrawablePadding = (RESET_GAP_DP * density).toInt()
            view.contentDescription = view.context.getString(R.string.reset_value_desc, text)
            view.setOnClickListener(onReset)
        } else {
            view.background = shape
            view.setCompoundDrawablesRelative(null, null, null, null)
            view.contentDescription = null
            view.setOnClickListener(null)
            view.isClickable = false
        }
    }
}
