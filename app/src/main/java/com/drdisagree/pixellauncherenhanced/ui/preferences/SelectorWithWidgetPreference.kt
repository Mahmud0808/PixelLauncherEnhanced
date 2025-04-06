package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin

class SelectorWithWidgetPreference : CheckBoxPreference {
    /**
     * Interface definition for a callback to be invoked when the preference is clicked.
     */
    interface OnClickListener {
        /**
         * Called when a preference has been clicked.
         *
         * @param emiter The clicked preference
         */
        fun onRadioButtonClicked(emiter: SelectorWithWidgetPreference?)
    }

    private var mListener: OnClickListener? = null
    private var mAppendix: View? = null
    private var mAppendixVisibility = -1

    private var mExtraWidgetContainer: View? = null
    private var mExtraWidget: ImageView? = null

    /**
     * Returns whether this preference is a checkbox.
     */
    var isCheckBox: Boolean = false // whether to display this button as a checkbox
        private set

    private var mExtraWidgetOnClickListener: View.OnClickListener? = null

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context  The [Context] this is associated with, through which it can
     * access the current theme, resources, etc.
     * @param attrs    The attributes of the XML tag that is inflating the preference
     * @param defStyle An attribute in the current theme that contains a reference to a style
     * resource that supplies default values for the view. Can be 0 to not
     * look for defaults.
     */
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init()
    }

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The [Context] this is associated with, through which it can
     * access the current theme, resources, etc.
     * @param attrs   The attributes of the XML tag that is inflating the preference
     */
    /**
     * Constructor to create a preference.
     *
     * @param context The Context this is associated with.
     */
    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null) : super(context, attrs) {
        init()
    }

    /**
     * Constructor to create a preference, which will display with a checkbox style.
     *
     * @param context    The [Context] this is associated with.
     * @param isCheckbox Whether this preference should display as a checkbox.
     */
    constructor(context: Context, isCheckbox: Boolean) : super(context, null) {
        isCheckBox = isCheckbox
        init()
    }

    /**
     * Sets the callback to be invoked when this preference is clicked by the user.
     *
     * @param listener The callback to be invoked
     */
    fun setOnClickListener(listener: OnClickListener?) {
        mListener = listener
    }

    /**
     * Processes a click on the preference.
     */
    public override fun onClick() {
        if (mListener != null) {
            mListener!!.onRadioButtonClicked(this)
        }
    }

    /**
     * Binds the created View to the data for this preference.
     *
     *
     * This is a good place to grab references to custom Views in the layout and set
     * properties on them.
     *
     *
     * Make sure to call through to the superclass's implementation.
     *
     * @param holder The ViewHolder that provides references to the views to fill in. These views
     * will be recycled, so you should not hold a reference to them after this method
     * returns.
     */
    @SuppressLint("WrongConstant")
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val summaryContainer = holder.findViewById(R.id.summary_container)
        if (summaryContainer != null) {
            summaryContainer.visibility =
                if (TextUtils.isEmpty(summary)) View.GONE else View.VISIBLE
            mAppendix = holder.findViewById(R.id.appendix)
            if (mAppendix != null && mAppendixVisibility != -1) {
                mAppendix!!.visibility = mAppendixVisibility
            }
        }

        mExtraWidget = holder.findViewById(R.id.selector_extra_widget) as ImageView
        mExtraWidgetContainer = holder.findViewById(R.id.selector_extra_widget_container)

        setExtraWidgetOnClickListener(mExtraWidgetOnClickListener)

        setFirstAndLastItemMargin(holder)
    }

    /**
     * Set the visibility state of appendix view.
     *
     * @param visibility One of [View.VISIBLE], [View.INVISIBLE], or [View.GONE].
     */
    fun setAppendixVisibility(visibility: Int) {
        if (mAppendix != null) {
            mAppendix!!.visibility = visibility
        }
        mAppendixVisibility = visibility
    }

    /**
     * Sets the callback to be invoked when extra widget is clicked by the user.
     *
     * @param listener The callback to be invoked
     */
    fun setExtraWidgetOnClickListener(listener: View.OnClickListener?) {
        mExtraWidgetOnClickListener = listener

        if (mExtraWidget == null || mExtraWidgetContainer == null) {
            return
        }

        mExtraWidget!!.setOnClickListener(mExtraWidgetOnClickListener)

        mExtraWidgetContainer!!.visibility = if ((mExtraWidgetOnClickListener != null))
            View.VISIBLE
        else
            View.GONE
    }

    private fun init() {
        widgetLayoutResource = if (isCheckBox) {
            R.layout.preference_widget_checkbox
        } else {
            R.layout.preference_widget_radiobutton
        }
        layoutResource = R.layout.preference_selector_with_widget
        isIconSpaceReserved = false
    }
}
