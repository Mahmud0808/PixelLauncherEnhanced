package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setBackgroundResource
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin
import com.drdisagree.pixellauncherenhanced.ui.views.RoundedBackgroundSpan
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.drdisagree.pixellauncherenhanced.utils.HapticUtils.toggleVibrate
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.google.android.material.color.MaterialColors
import java.lang.ref.WeakReference
import com.google.android.material.R as MaterialR

class SwitchPreference : SwitchPreferenceCompat {

    private var showUnstableBadge = false
    private var unstableText: String = "Unstable"
    private var boundView: WeakReference<View>? = null

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        readAttrs(context, attrs)
        initResource()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
            : super(context, attrs, defStyleAttr) {
        readAttrs(context, attrs)
        initResource()
    }

    constructor(context: Context, attrs: AttributeSet?)
            : super(context, attrs) {
        readAttrs(context, attrs)
        initResource()
    }

    constructor(context: Context) : super(context) {
        initResource()
    }

    private fun readAttrs(context: Context, attrs: AttributeSet?) {
        attrs ?: return
        context.withStyledAttributes(attrs, R.styleable.SwitchPreference) {
            showUnstableBadge = getBoolean(
                R.styleable.SwitchPreference_showUnstableBadge,
                false
            )
            unstableText = getString(R.styleable.SwitchPreference_unstableText)
                ?: context.getString(R.string.unstable)
        }
    }

    private fun initResource() {
        layoutResource = R.layout.custom_preference_switch
        widgetLayoutResource = R.layout.preference_material_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        boundView = WeakReference(holder.itemView)
        setFirstAndLastItemMargin(holder)
        setBackgroundResource(holder)

        if (!showUnstableBadge) return

        val titleView = holder.findViewById(android.R.id.title) as? TextView ?: return
        val titleText = title?.toString() ?: return

        titleView.movementMethod = LinkMovementMethod.getInstance()
        titleView.highlightColor = Color.TRANSPARENT

        val badgeText = unstableText
        val start = titleText.length + 1
        val end = start + badgeText.length

        val spannable = SpannableString("$titleText $badgeText")

        spannable.setSpan(
            RoundedBackgroundSpan(
                backgroundColor = MaterialColors.getColor(titleView, MaterialR.attr.colorErrorContainer),
                textColor = MaterialColors.getColor(titleView, MaterialR.attr.colorOnErrorContainer),
                paddingH = dpToPx(6),
                paddingV = dpToPx(2),
                radius = dpToPx(6),
                textScale = 0.75f,
                yOffsetPx = dpToPx(1)
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    showUnstableDialog()
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                    ds.color = Color.TRANSPARENT
                }
            },
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        titleView.text = spannable
    }

    override fun onClick() {
        super.onClick()
        boundView?.get()?.toggleVibrate(isChecked)
    }

    private fun showUnstableDialog() {
        ExpressiveDialog.show(
            context = context,
            icon = R.drawable.ic_info,
            title = context.getString(R.string.unstable_dialog_title),
            message = context.getString(R.string.unstable_dialog_desc),
            positive = ExpressiveDialog.Action(context.getString(android.R.string.ok)),
            tone = ExpressiveDialog.Tone.TERTIARY
        )
    }
}