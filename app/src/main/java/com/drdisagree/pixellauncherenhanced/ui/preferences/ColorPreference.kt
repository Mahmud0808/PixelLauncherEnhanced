package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.res.TypedArray
import android.graphics.Color
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
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

        val a: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.ColorPreference)
        showDialog = a.getBoolean(R.styleable.ColorPreference_cpv_showDialog, true)
        dialogType =
            a.getInt(R.styleable.ColorPreference_cpv_dialogType, ColorPickerDialog.TYPE_PRESETS)
        colorShape = a.getInt(R.styleable.ColorPreference_cpv_colorShape, ColorShape.CIRCLE)
        allowPresets = a.getBoolean(R.styleable.ColorPreference_cpv_allowPresets, true)
        allowCustom = a.getBoolean(R.styleable.ColorPreference_cpv_allowCustom, true)
        showAlphaSlider = a.getBoolean(R.styleable.ColorPreference_cpv_showAlphaSlider, false)
        showColorShades = a.getBoolean(R.styleable.ColorPreference_cpv_showColorShades, true)
        previewSize = a.getInt(R.styleable.ColorPreference_cpv_previewSize, SIZE_NORMAL)
        val presetsResId: Int = a.getResourceId(R.styleable.ColorPreference_cpv_colorPresets, 0)
        dialogTitle =
            a.getResourceId(R.styleable.ColorPreference_cpv_dialogTitle, R.string.cpv_default_title)
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
        a.recycle()

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
        preview?.setColor(color)

        setFirstAndLastItemMargin(holder)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        if (defaultValue is Int) {
            color = defaultValue
            persistInt(color)
        } else {
            color = getPersistedInt(-0x1000000)
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInteger(index, Color.BLACK)
    }

    override fun onColorSelected(dialogId: Int, @ColorInt color: Int) {
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
    }
}
