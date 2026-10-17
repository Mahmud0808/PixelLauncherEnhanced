package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setBackgroundResource
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin

class ListPreference : ListPreference {

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
        layoutResource = R.layout.custom_preference_list
    }

    private var binding = false
    private var defaultEntryValue: String? = null

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? {
        return super.onGetDefaultValue(a, index).also { defaultEntryValue = it as? String }
    }

    private val isInline: Boolean
        get() = entries != null && entries.size in 2..MAX_INLINE_CHOICES

    override fun onClick() {
        if (!isInline) super.onClick()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val inline = isInline
        holder.itemView.isClickable = !inline
        holder.itemView.isFocusable = !inline
        bindChoices(holder.findViewById(R.id.choice_scroll) as? HorizontalScrollView, inline)

        ValuePill.bind(
            holder.findViewById(R.id.output) as? TextView,
            if (inline) null else entry,
            value == defaultEntryValue
        )

        setFirstAndLastItemMargin(holder)
        setBackgroundResource(holder)
    }

    private fun bindChoices(scroll: HorizontalScrollView?, inline: Boolean) {
        scroll ?: return
        if (!inline) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val group = scroll.findViewById<MaterialButtonToggleGroup>(R.id.choice_group) ?: return
        binding = true

        val labels = entries.map { it.toString() }
        if (group.tag != labels) {
            group.clearOnButtonCheckedListeners()
            group.removeAllViews()
            labels.forEachIndexed { index, label ->
                val button = MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = label
                    tag = index
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    insetTop = 0
                    insetBottom = 0
                    minHeight = 0
                    minimumHeight = 0
                    val horizontal = (CHOICE_PADDING_DP * resources.displayMetrics.density).toInt()
                    setPadding(horizontal, paddingTop, horizontal, paddingBottom)
                    applyChoiceColors(this)
                }
                group.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            group.tag = labels
            group.addOnButtonCheckedListener { toggleGroup, checkedId, isChecked ->
                if (binding || !isChecked) return@addOnButtonCheckedListener
                val index = toggleGroup.findViewById<View>(checkedId)?.tag as? Int ?: return@addOnButtonCheckedListener
                val newValue = entryValues.getOrNull(index)?.toString() ?: return@addOnButtonCheckedListener
                if (newValue != value && callChangeListener(newValue)) value = newValue
            }
        }

        val selected = findIndexOfValue(value)
        for (i in 0 until group.childCount) {
            val button = group.getChildAt(i) as MaterialButton
            button.isEnabled = isEnabled
            if (button.tag == selected) {
                group.check(button.id)
                scroll.post {
                    val left = button.left - scroll.horizontalFadingEdgeLength
                    val right = button.right + scroll.horizontalFadingEdgeLength - scroll.width
                    when {
                        left < scroll.scrollX -> scroll.scrollTo(left, 0)
                        right > scroll.scrollX -> scroll.scrollTo(right, 0)
                    }
                }
            }
        }
        binding = false
    }

    private fun applyChoiceColors(button: MaterialButton) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val selectedFill = MaterialColors.getColor(button, MaterialR.attr.colorSecondaryContainer)
        val selectedText = MaterialColors.getColor(button, MaterialR.attr.colorOnSecondaryContainer)
        val idleText = MaterialColors.getColor(button, MaterialR.attr.colorOnSurfaceVariant)
        val outline = MaterialColors.getColor(button, MaterialR.attr.colorOutlineVariant)
        val faded = ColorUtils.setAlphaComponent(idleText, 0x61)
        button.backgroundTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(selectedFill, Color.TRANSPARENT))
        button.setTextColor(ColorStateList(arrayOf(disabled, checked, intArrayOf()), intArrayOf(faded, selectedText, idleText)))
        button.strokeColor = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(selectedFill, outline))
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(selectedText, 0x1F))
    }

    companion object {
        private const val MAX_INLINE_CHOICES = 5
        private const val CHOICE_PADDING_DP = 20
    }
}
