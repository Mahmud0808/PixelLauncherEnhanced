package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.data.common.MaterialPalette
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.applyChoiceColors
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setBackgroundResource
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin
import com.drdisagree.pixellauncherenhanced.ui.widgets.ExpressiveColorPicker
import com.drdisagree.pixellauncherenhanced.ui.widgets.FadingEdgeLayout
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.drdisagree.pixellauncherenhanced.utils.HapticUtils.tickVibrate
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapes
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.drdisagree.pixellauncherenhanced.R as AppR
import com.google.android.material.R as MaterialR

class ColorPreference : Preference {
    private var color = Color.BLACK
    private var showAlphaSlider = false
    private var darkKey: String? = null
    private var darkDefault = Color.BLACK
    private var darkColor = Color.BLACK
    private var editingDark = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init(attrs)
    }

    private fun init(attrs: AttributeSet?) {
        isPersistent = true
        context.withStyledAttributes(attrs, AppR.styleable.ColorPreference) {
            showAlphaSlider = getBoolean(AppR.styleable.ColorPreference_showAlphaSlider, false)
            darkKey = getString(AppR.styleable.ColorPreference_darkKey)
            darkDefault = getColor(AppR.styleable.ColorPreference_darkDefaultValue, Color.BLACK)
        }
        widgetLayoutResource = if (darkKey != null) AppR.layout.preference_color_swatch_pair else AppR.layout.preference_color_swatch
        initResource()
    }

    private fun initResource() {
        layoutResource = com.drdisagree.pixellauncherenhanced.R.layout.custom_preference_color
    }

    override fun onClick() {
        super.onClick()
        editingDark = darkKey != null &&
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        showSourceDialog()
    }

    private fun keyFor(dark: Boolean): String = if (dark) darkKey ?: key else key

    private fun colorFor(dark: Boolean): Int = if (dark && darkKey != null) darkColor else color

    private fun roleFor(dark: Boolean): MaterialPalette.Role? {
        val variantKey = keyFor(dark)
        return MaterialPalette.effectiveRole(
            variantKey,
            sharedPreferences?.getString(MaterialPalette.sourceKey(variantKey), null),
            if (sharedPreferences?.contains(variantKey) == true) colorFor(dark) else null
        )
    }

    private fun isDarkFor(dark: Boolean): Boolean = if (darkKey != null) dark else MaterialPalette.isDarkKey(key)

    private fun shownColor(dark: Boolean): Int =
        roleFor(dark)?.let { MaterialPalette.resolve(context.resources, it, isDarkFor(dark)) } ?: colorFor(dark)

    private fun showSourceDialog() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        var dialog: AlertDialog? = null
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fillPalette(content) { dialog?.dismiss() }

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (darkKey != null) {
            val group = LayoutInflater.from(context)
                .inflate(AppR.layout.view_color_variant_toggle, body, false) as MaterialButtonToggleGroup
            listOf(false to AppR.string.color_variant_light, true to AppR.string.color_variant_dark).forEach { (dark, label) ->
                val button = (LayoutInflater.from(context)
                    .inflate(AppR.layout.view_choice_button, group, false) as MaterialButton).apply {
                    id = View.generateViewId()
                    setText(label)
                    tag = dark
                    applyChoiceColors(this)
                }
                group.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (dark == editingDark) group.check(button.id)
            }
            group.addOnButtonCheckedListener { toggleGroup, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val dark = toggleGroup.findViewById<View>(checkedId)?.tag as? Boolean ?: return@addOnButtonCheckedListener
                if (dark == editingDark) return@addOnButtonCheckedListener
                toggleGroup.tickVibrate()
                editingDark = dark
                content.removeAllViews()
                fillPalette(content) { dialog?.dismiss() }
            }
            body.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(24)
                marginEnd = dp(24)
                bottomMargin = dp(4)
            })
        }
        body.addView(FadingEdgeLayout(context).apply {
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog = ExpressiveDialog.show(
            context = context,
            icon = AppR.drawable.ic_palette,
            title = title ?: "",
            content = body,
            fullWidthContent = true,
            positive = ExpressiveDialog.Action(context.getString(AppR.string.color_custom)) { showCustomDialog() },
            negative = ExpressiveDialog.Action(context.getString(android.R.string.cancel))
        )
    }

    private fun fillPalette(content: LinearLayout, dismiss: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val selected = roleFor(editingDark)
        val dark = isDarkFor(editingDark)
        val outline = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, 0)
        MaterialPalette.Group.entries.forEach { group ->
            content.addView(MaterialTextView(context).apply {
                setText(group.label)
                setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
                setTextColor(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0))
                setPadding(dp(4), dp(14), 0, dp(8))
            })
            MaterialPalette.Role.entries.filter { it.group == group }.chunked(2).forEach { pair ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                }
                pair.forEachIndexed { index, role ->
                    val swatch = MaterialPalette.resolve(context.resources, role, dark) ?: return@forEachIndexed
                    val textColor = if (ColorUtils.calculateLuminance(swatch) > 0.5) 0xDE000000.toInt() else Color.WHITE
                    val chosen = role == selected
                    row.addView(MaterialTextView(context).apply {
                        setText(role.label)
                        setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelLarge)
                        setTextColor(textColor)
                        gravity = Gravity.CENTER_VERTICAL
                        minHeight = dp(TILE_HEIGHT_DP)
                        setPadding(dp(14), dp(10), dp(10), dp(10))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(TILE_RADIUS_DP).toFloat()
                            setColor(swatch)
                            if (chosen) setStroke(dp(3), outline) else setStroke(dp(1), ColorUtils.setAlphaComponent(outline, 0x26))
                        }
                        if (chosen) {
                            setCompoundDrawablesRelativeWithIntrinsicBounds(
                                0, 0, AppR.drawable.ic_check, 0
                            )
                            compoundDrawableTintList = ColorStateList.valueOf(textColor)
                        }
                        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                            getDrawable(0).also { recycle() }
                        }
                        contentDescription = context.getString(role.label)
                        setOnClickListener {
                            saveMaterial(role, swatch)
                            dismiss()
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (index == 0 && pair.size > 1) marginEnd = dp(8)
                        bottomMargin = dp(8)
                    })
                }
                if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(8) })
                content.addView(row)
            }
        }
    }

    private fun showCustomDialog() {
        val view = LayoutInflater.from(context).inflate(AppR.layout.dialog_color_picker, null)
        val picker = view.findViewById<ExpressiveColorPicker>(AppR.id.picker)
        val preview = view.findViewById<ShapeIconView>(AppR.id.preview)
        val hex = view.findViewById<TextInputEditText>(AppR.id.hex)
        var syncing = false

        preview.setShapes(MaterialShapes.COOKIE_9, MaterialShapes.SUNNY)
        preview.setOutline(MaterialColors.getColor(preview, MaterialR.attr.colorOutline), context.resources.displayMetrics.density * OUTLINE_DP)
        preview.setCheckerboard((context.resources.displayMetrics.density * CHECKER_DP).toInt())
        fun showColor(value: Int, fromText: Boolean) {
            preview.setColors(value, value)
            if (fromText) return
            syncing = true
            hex.setText(value.toHex())
            hex.setSelection(hex.length())
            syncing = false
        }

        picker.showAlpha = showAlphaSlider
        picker.color = colorFor(editingDark)
        showColor(picker.color, fromText = false)
        picker.onColorChanged = { showColor(it, fromText = false) }
        hex.filters = arrayOf(InputFilter.LengthFilter(if (showAlphaSlider) 8 else 6))
        hex.doAfterTextChanged { text ->
            if (syncing) return@doAfterTextChanged
            val parsed = text?.toString()?.parseHex() ?: return@doAfterTextChanged
            picker.color = parsed
            showColor(picker.color, fromText = true)
        }

        ExpressiveDialog.show(
            context = context,
            icon = AppR.drawable.ic_palette,
            title = context.getString(AppR.string.color_custom),
            content = view,
            fullWidthContent = true,
            positive = ExpressiveDialog.Action(context.getString(AppR.string.color_select)) {
                sharedPreferences?.edit()?.putString(MaterialPalette.sourceKey(keyFor(editingDark)), MaterialPalette.CUSTOM)?.apply()
                saveValue(picker.color)
            },
            negative = ExpressiveDialog.Action(context.getString(android.R.string.cancel))
        )
    }

    private fun Int.toHex(): String =
        if (showAlphaSlider) String.format("%08X", this) else String.format("%06X", this and 0xFFFFFF)

    private fun String.parseHex(): Int? {
        val digits = when (length) {
            6 -> "FF$this"
            8 -> if (showAlphaSlider) this else return null
            else -> return null
        }
        return digits.toLongOrNull(16)?.toInt()
    }

    private fun saveMaterial(role: MaterialPalette.Role, @ColorInt resolved: Int) {
        sharedPreferences?.edit()?.putString(MaterialPalette.sourceKey(keyFor(editingDark)), role.id)?.apply()
        saveValue(resolved)
    }

    private fun describe(dark: Boolean): String =
        roleFor(dark)?.let { context.getString(it.label) } ?: String.format("#%06X", colorFor(dark) and 0xFFFFFF)

    private fun describeSource() {
        summary = if (darkKey != null) {
            context.getString(AppR.string.color_variant_summary, describe(false), describe(true))
        } else {
            describe(false)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.findViewById<ShapeIconView>(AppR.id.color_swatch)?.showSwatch(shownColor(false))
        if (darkKey != null) holder.itemView.findViewById<ShapeIconView>(AppR.id.color_swatch_dark)?.showSwatch(shownColor(true))

        setFirstAndLastItemMargin(holder)
        setBackgroundResource(holder)
    }

    private fun ShapeIconView.showSwatch(shown: Int) {
        setShapes(MaterialShapes.COOKIE_9, MaterialShapes.SUNNY)
        setColors(shown, shown)
        setOutline(MaterialColors.getColor(this, MaterialR.attr.colorOutline), resources.displayMetrics.density * OUTLINE_DP)
        setCheckerboard(if (Color.alpha(shown) < 255) (resources.displayMetrics.density * CHECKER_DP).toInt() else 0)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        if (defaultValue is Int) {
            color = defaultValue
            persistInt(color)
        } else {
            color = getPersistedInt(-0x1000000)
        }
        darkKey?.let { darkColor = sharedPreferences?.getInt(it, darkDefault) ?: darkDefault }
        describeSource()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInteger(index, Color.BLACK)
    }

    /**
     * Set the new color
     *
     * @param color The newly selected color
     */
    private fun saveValue(@ColorInt color: Int) {
        val dark = darkKey?.takeIf { editingDark }
        if (dark != null) {
            darkColor = color
            sharedPreferences?.edit()?.putInt(dark, color)?.apply()
        } else {
            this.color = color
            persistInt(this.color)
        }
        describeSource()
        notifyChanged()
        callChangeListener(color)
    }

    companion object {
        private const val TILE_HEIGHT_DP = 56
        private const val OUTLINE_DP = 1.5f
        private const val CHECKER_DP = 5
        private const val TILE_RADIUS_DP = 16
    }
}
