package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.res.TypedArray
import android.graphics.Color
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.drdisagree.pixellauncherenhanced.data.common.MaterialPalette
import com.drdisagree.pixellauncherenhanced.ui.widgets.FadingEdgeLayout
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.drdisagree.pixellauncherenhanced.R as AppR
import com.google.android.material.R as MaterialR
import androidx.annotation.ColorInt
import androidx.core.content.withStyledAttributes
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setBackgroundResource
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin
import com.jaredrummler.android.colorpicker.ColorPanelView
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.ColorShape
import com.jaredrummler.android.colorpicker.R

class ColorPreference : Preference, ColorPickerDialogListener {
    private var onShowDialogListener: OnShowDialogListener? = null
    private var color = Color.BLACK
    private var showDialog = false
    private var dialogType = 0
    private var colorShape = 0
    private var allowPresets = false
    private var allowCustom = false
    private var showAlphaSlider = false
    private var showColorShades = false
    private var previewSize = 0
    /**
     * Get the colors that will be shown in the [ColorPickerDialog].
     *
     * @return An array of color ints
     */
    /**
     * Set the colors shown in the [ColorPickerDialog].
     *
     * @param presets An array of color ints
     */
    private lateinit var presets: IntArray
    private var dialogTitle = 0

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

        context.withStyledAttributes(attrs, R.styleable.ColorPreference) {
            showDialog = getBoolean(R.styleable.ColorPreference_cpv_showDialog, true)
            dialogType =
                getInt(R.styleable.ColorPreference_cpv_dialogType, ColorPickerDialog.TYPE_PRESETS)
            colorShape = getInt(R.styleable.ColorPreference_cpv_colorShape, ColorShape.CIRCLE)
            allowPresets = getBoolean(R.styleable.ColorPreference_cpv_allowPresets, true)
            allowCustom = getBoolean(R.styleable.ColorPreference_cpv_allowCustom, true)
            showAlphaSlider = getBoolean(R.styleable.ColorPreference_cpv_showAlphaSlider, false)
            showColorShades = getBoolean(R.styleable.ColorPreference_cpv_showColorShades, true)
            previewSize = getInt(R.styleable.ColorPreference_cpv_previewSize, SIZE_NORMAL)
            val presetsResId: Int = getResourceId(R.styleable.ColorPreference_cpv_colorPresets, 0)
            dialogTitle =
                getResourceId(
                    R.styleable.ColorPreference_cpv_dialogTitle,
                    R.string.cpv_default_title
                )
            presets = if (presetsResId != 0) {
                context.resources.getIntArray(presetsResId)
            } else {
                ColorPickerDialog.MATERIAL_COLORS
            }
            widgetLayoutResource = if (colorShape == ColorShape.CIRCLE) {
                if (previewSize == SIZE_LARGE) R.layout.cpv_preference_circle_large else R.layout.cpv_preference_circle
            } else {
                if (previewSize == SIZE_LARGE) R.layout.cpv_preference_square_large else R.layout.cpv_preference_square
            }
        }

        initResource()
    }

    private fun initResource() {
        layoutResource = com.drdisagree.pixellauncherenhanced.R.layout.custom_preference_color
    }

    override fun onClick() {
        super.onClick()
        if (onShowDialogListener != null) {
            onShowDialogListener!!.onShowColorPickerDialog(title as String?, color)
        } else if (showDialog) {
            showSourceDialog()
        }
    }

    private val materialRole: MaterialPalette.Role?
        get() = MaterialPalette.effectiveRole(
            key,
            sharedPreferences?.getString(MaterialPalette.sourceKey(key), null),
            if (sharedPreferences?.contains(key) == true) color else null
        )

    private val isDarkVariant: Boolean
        get() = MaterialPalette.isDarkKey(key)

    private fun showSourceDialog() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val selected = materialRole
        var dialog: AlertDialog? = null
        val outline = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, 0)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
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
                    val swatch = MaterialPalette.resolve(context.resources, role, isDarkVariant) ?: return@forEachIndexed
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
                            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(textColor)
                        }
                        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                            getDrawable(0).also { recycle() }
                        }
                        contentDescription = context.getString(role.label)
                        setOnClickListener {
                            saveMaterial(role, swatch)
                            dialog?.dismiss()
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
        dialog = ExpressiveDialog.show(
            context = context,
            icon = AppR.drawable.ic_palette,
            title = title ?: "",
            content = FadingEdgeLayout(context).apply {
                addView(ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(content)
                })
            },
            fullWidthContent = true,
            positive = ExpressiveDialog.Action(context.getString(AppR.string.color_custom)) { showCustomDialog() },
            negative = ExpressiveDialog.Action(context.getString(android.R.string.cancel))
        )
    }

    private fun showCustomDialog() {
        run {
            val dialog: ColorPickerDialog = ColorPickerDialog.newBuilder()
                .setDialogType(dialogType)
                .setDialogTitle(dialogTitle)
                .setColorShape(colorShape)
                .setPresets(presets)
                .setAllowPresets(allowPresets)
                .setAllowCustom(allowCustom)
                .setShowAlphaSlider(showAlphaSlider)
                .setShowColorShades(showColorShades)
                .setColor(color)
                .create()
            dialog.setColorPickerDialogListener(this)
            activity.supportFragmentManager
                .beginTransaction()
                .add(dialog, fragmentTag)
                .commitAllowingStateLoss()
        }
    }

    val activity: FragmentActivity
        get() {
            val context = context
            if (context is FragmentActivity) {
                return context
            } else if (context is ContextWrapper) {
                val baseContext: Context = context.baseContext
                if (baseContext is FragmentActivity) {
                    return baseContext
                }
            }
            throw IllegalStateException("Error getting activity from context")
        }

    private fun saveMaterial(role: MaterialPalette.Role, @ColorInt resolved: Int) {
        sharedPreferences?.edit()?.putString(MaterialPalette.sourceKey(key), role.id)?.apply()
        saveValue(resolved)
    }

    private fun describeSource() {
        summary = materialRole?.let { context.getString(it.label) } ?: String.format("#%06X", color and 0xFFFFFF)
    }

    override fun onAttached() {
        super.onAttached()
        if (showDialog) {
            val fragment: ColorPickerDialog? =
                activity.supportFragmentManager
                    .findFragmentByTag(fragmentTag) as? ColorPickerDialog
            fragment?.setColorPickerDialogListener(this)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val preview: ColorPanelView? =
            holder.itemView.findViewById(R.id.cpv_preference_preview_color_panel)
        preview?.setColor(materialRole?.let { MaterialPalette.resolve(context.resources, it, isDarkVariant) } ?: color)

        setFirstAndLastItemMargin(holder)
        setBackgroundResource(holder)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        if (defaultValue is Int) {
            color = defaultValue
            persistInt(color)
        } else {
            color = getPersistedInt(-0x1000000)
        }
        describeSource()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInteger(index, Color.BLACK)
    }

    override fun onColorSelected(dialogId: Int, @ColorInt color: Int) {
        sharedPreferences?.edit()?.putString(MaterialPalette.sourceKey(key), MaterialPalette.CUSTOM)?.apply()
        saveValue(color)
    }

    override fun onDialogDismissed(dialogId: Int) {
        // no-op
    }

    /**
     * Set the new color
     *
     * @param color The newly selected color
     */
    private fun saveValue(@ColorInt color: Int) {
        this.color = color
        persistInt(this.color)
        describeSource()
        notifyChanged()
        callChangeListener(color)
    }

    /**
     * The listener used for showing the [ColorPickerDialog].
     * Call [.saveValue] after the user chooses a color.
     * If this is set then it is up to you to show the dialog.
     *
     * @param listener The listener to show the dialog
     */
    fun setOnShowDialogListener(listener: OnShowDialogListener?) {
        onShowDialogListener = listener
    }

    private val fragmentTag: String
        /**
         * The tag used for the [ColorPickerDialog].
         *
         * @return The tag
         */
        get() = "color_$key"

    interface OnShowDialogListener {
        fun onShowColorPickerDialog(title: String?, currentColor: Int)
    }

    companion object {
        private const val SIZE_NORMAL = 0
        private const val SIZE_LARGE = 1
        private const val TILE_HEIGHT_DP = 56
        private const val TILE_RADIUS_DP = 16
    }
}
