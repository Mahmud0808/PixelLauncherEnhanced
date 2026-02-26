package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setBackgroundResource
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin

class PreferenceMainMenu : Preference {

    private var showArrow = true

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context) : super(context) {
        init(null)
    }

    private fun init(attrs: AttributeSet?) {
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.PreferenceMenu) {
                showArrow = getBoolean(R.styleable.PreferenceMenu_showArrow, true)
            }
        }
        layoutResource = R.layout.custom_preference_main_menu
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.findViewById(R.id.end_arrow)?.visibility = if (showArrow) View.VISIBLE else View.GONE

        setFirstAndLastItemMargin(holder)
        setBackgroundResource(holder)
    }

    fun setShowArrow(showArrow: Boolean) {
        this.showArrow = showArrow
        notifyChanged()
    }
}
