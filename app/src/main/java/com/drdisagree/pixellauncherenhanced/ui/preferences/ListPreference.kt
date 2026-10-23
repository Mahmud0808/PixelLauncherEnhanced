package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.withStyledAttributes
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.applyChoiceColors
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setBackgroundResource
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin
import com.drdisagree.pixellauncherenhanced.utils.HapticUtils.tickVibrate

class ListPreference : ListPreference {

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        initResource(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        initResource(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initResource(attrs)
    }

    constructor(context: Context) : super(context) {
        initResource(null)
    }

    private var summaryWarning = false

    private fun initResource(attrs: AttributeSet?) {
        layoutResource = R.layout.custom_preference_list
        context.withStyledAttributes(attrs, R.styleable.ListPreference) {
            summaryWarning = getBoolean(R.styleable.ListPreference_summaryWarning, false)
        }
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

        bindSummaryWarning(holder.findViewById(android.R.id.summary) as? TextView)

        setFirstAndLastItemMargin(holder)
        setBackgroundResource(holder)
    }

    private fun bindSummaryWarning(summary: TextView?) {
        summary ?: return
        val normalColors = summary.getTag(R.id.tag_summary_colors) as? ColorStateList
            ?: summary.textColors.also { summary.setTag(R.id.tag_summary_colors, it) }

        if (!summaryWarning) {
            summary.setTextColor(normalColors)
            summary.setCompoundDrawablesRelative(null, null, null, null)
            return
        }

        val warning = MaterialColors.harmonizeWithPrimary(summary.context, summary.context.getColor(R.color.warning))
        val size = summary.textSize.toInt()
        val icon = AppCompatResources.getDrawable(summary.context, R.drawable.ic_debug_warning)?.mutate()?.apply {
            setTint(warning)
            setBounds(0, 0, size, size)
        }
        summary.setTextColor(warning)
        summary.compoundDrawablePadding = (summary.resources.displayMetrics.density * 6).toInt()
        summary.setCompoundDrawablesRelative(icon, null, null, null)
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
                val button = (LayoutInflater.from(group.context)
                    .inflate(R.layout.view_choice_button, group, false) as MaterialButton).apply {
                    id = View.generateViewId()
                    text = label
                    tag = index
                    applyChoiceColors(this)
                }
                group.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            group.tag = labels
            group.addOnButtonCheckedListener { toggleGroup, checkedId, isChecked ->
                if (binding || !isChecked) return@addOnButtonCheckedListener
                val index = toggleGroup.findViewById<View>(checkedId)?.tag as? Int ?: return@addOnButtonCheckedListener
                val newValue = entryValues.getOrNull(index)?.toString() ?: return@addOnButtonCheckedListener
                if (newValue != value && callChangeListener(newValue)) {
                    toggleGroup.tickVibrate()
                    value = newValue
                }
            }
        }

        val selected = findIndexOfValue(value)
        var selectedButton: MaterialButton? = null
        for (i in 0 until group.childCount) {
            val button = group.getChildAt(i) as MaterialButton
            button.isEnabled = isEnabled
            if (button.tag == selected) {
                group.check(button.id)
                selectedButton = button
            }
        }
        binding = false

        scroll.post {
            fitChoices(scroll, group)
            scroll.post { revealChoice(scroll, selectedButton ?: return@post) }
        }
    }

    private fun fitChoices(scroll: HorizontalScrollView, group: MaterialButtonToggleGroup) {
        val buttons = (0 until group.childCount).map { group.getChildAt(it) as MaterialButton }
        if (buttons.isEmpty()) return
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val widest = buttons.maxOf { it.measure(unspecified, unspecified); it.measuredWidth }
        val available = scroll.width - scroll.paddingStart - scroll.paddingEnd
        val equal = widest * buttons.size + group.spacing * (buttons.size - 1) <= available

        scroll.isFillViewport = equal
        group.layoutParams = group.layoutParams.apply {
            width = if (equal) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        buttons.forEach { button ->
            button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (equal) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (equal) 1f else 0f
            }
        }
    }

    private fun revealChoice(scroll: HorizontalScrollView, button: MaterialButton) {
        val left = button.left - scroll.horizontalFadingEdgeLength
        val right = button.right + scroll.horizontalFadingEdgeLength - scroll.width
        when {
            button.left < scroll.scrollX -> scroll.scrollTo(left, 0)
            button.right > scroll.scrollX + scroll.width -> scroll.scrollTo(right, 0)
        }
    }

    companion object {
        private const val MAX_INLINE_CHOICES = 5
    }
}
