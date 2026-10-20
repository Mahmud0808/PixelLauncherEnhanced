package com.drdisagree.pixellauncherenhanced.utils

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.updateLayoutParams
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.shape.MaterialShapes
import com.google.android.material.R as MaterialR

object ExpressiveDialog {

    enum class Tone(@AttrRes val container: Int, @AttrRes val content: Int) {
        PRIMARY(MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer),
        TERTIARY(MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer),
        ERROR(MaterialR.attr.colorErrorContainer, MaterialR.attr.colorOnErrorContainer)
    }

    class Action(
        val text: CharSequence,
        val dismiss: Boolean = true,
        val onClick: (AlertDialog) -> Unit = {}
    )

    private const val POP_DELAY_MS = 180L
    private const val POP_HOLD_MS = 520L
    private const val ENTER_SCALE = 0.82f
    private const val ENTER_SCALE_MS = 420L
    private const val ENTER_FADE_MS = 220L
    private const val ENTER_TENSION = 1.4f
    private const val COMPACT_EMBLEM = 72
    private const val ROW_HEIGHT = 56
    private const val ROW_GAP = 3
    private const val ROW_OUTER_RADIUS = 20
    private const val ROW_INNER_RADIUS = 6

    fun show(
        context: Context,
        @DrawableRes icon: Int,
        title: CharSequence,
        message: CharSequence? = null,
        positive: Action? = null,
        negative: Action? = null,
        tone: Tone = Tone.PRIMARY,
        cancelable: Boolean = true,
        content: View? = null,
        fullWidthContent: Boolean = false,
        loading: Boolean = false,
        onDismiss: (() -> Unit)? = null
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_expressive, null)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(cancelable)
            .create()

        val emblem = view.findViewById<ShapeIconView>(R.id.dialog_emblem).apply {
            val color = MaterialColors.getColor(this, tone.content)
            setShapes(MaterialShapes.COOKIE_12, MaterialShapes.SUNNY)
            setColors(MaterialColors.getColor(this, tone.container), color)
            setIcon(AppCompatResources.getDrawable(context, icon), color)
            visibility = if (loading) View.INVISIBLE else View.VISIBLE
        }
        view.findViewById<LoadingIndicator>(R.id.dialog_loading).visibility = if (loading) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.dialog_title).text = title
        view.findViewById<TextView>(R.id.dialog_message).apply {
            text = message
            visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        if (content != null) {
            val host = view.findViewById<FrameLayout>(if (fullWidthContent) R.id.dialog_content else R.id.dialog_inner_content)
            host.visibility = View.VISIBLE
            host.addView(content)
            if (fullWidthContent) {
                view.findViewById<ScrollView>(R.id.dialog_scroll).updateLayoutParams<LinearLayout.LayoutParams> { weight = 0f }
                host.updateLayoutParams<LinearLayout.LayoutParams> { weight = 1f }
                view.findViewById<View>(R.id.dialog_emblem_frame).updateLayoutParams<ViewGroup.LayoutParams> {
                    width = dpToPx(COMPACT_EMBLEM)
                    height = dpToPx(COMPACT_EMBLEM)
                }
                view.findViewById<View>(R.id.dialog_buttons).setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(20))
            }
        }

        bind(view.findViewById(R.id.dialog_positive), positive, dialog)
        bind(view.findViewById(R.id.dialog_negative), negative, dialog)
        if (positive == null) {
            view.findViewById<View>(R.id.dialog_negative).updateLayoutParams<LinearLayout.LayoutParams> { topMargin = 0 }
        }
        if (positive == null && negative == null) {
            view.findViewById<View>(R.id.dialog_buttons).visibility = View.GONE
            view.findViewById<View>(R.id.dialog_scroll).setPadding(0, 0, 0, dpToPx(28))
        }

        onDismiss?.let { callback -> dialog.setOnDismissListener { callback() } }
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.setWindowAnimations(R.style.Animation_PLEnhanced_Dialog)
        dialog.show()
        dialog.window?.decorView?.let { animateIn(it) }

        if (!loading) {
            emblem.postDelayed({ emblem.setPressedState(true) }, POP_DELAY_MS)
            emblem.postDelayed({ emblem.setPressedState(false) }, POP_DELAY_MS + POP_HOLD_MS)
        }
        return dialog
    }

    fun showChoices(
        context: Context,
        @DrawableRes icon: Int,
        title: CharSequence,
        items: List<CharSequence>,
        checked: Int,
        negative: Action? = null,
        onPick: (Int) -> Unit
    ): AlertDialog {
        lateinit var dialog: AlertDialog
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, label ->
            list.addView(
                choiceRow(context, label, index == checked, index, items.size) {
                    dialog.dismiss()
                    onPick(index)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = dpToPx(ROW_GAP)
                }
            )
        }
        dialog = show(context, icon, title, content = list, negative = negative)
        return dialog
    }

    fun toggleRow(context: Context, label: CharSequence, checked: Boolean, onChange: (Boolean) -> Unit): View {
        return MaterialSwitch(context).apply {
            text = label
            isChecked = checked
            minHeight = dpToPx(ROW_HEIGHT)
            setTextAppearance(resolve(context, MaterialR.attr.textAppearanceBodyLarge))
            setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSurface))
            setPadding(dpToPx(20), 0, dpToPx(16), 0)
            background = rowBackground(this, MaterialR.attr.colorSurfaceContainerHigh, 0, 1)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
    }

    private fun choiceRow(
        context: Context,
        label: CharSequence,
        selected: Boolean,
        index: Int,
        count: Int,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpToPx(ROW_HEIGHT)
        setPadding(dpToPx(20), dpToPx(8), dpToPx(16), dpToPx(8))
        isClickable = true
        isFocusable = true
        val surface = if (selected) MaterialR.attr.colorSecondaryContainer else MaterialR.attr.colorSurfaceContainerHigh
        val text = if (selected) MaterialR.attr.colorOnSecondaryContainer else MaterialR.attr.colorOnSurface
        background = rowBackground(this, surface, index, count)
        addView(TextView(context).apply {
            this.text = label
            setTextAppearance(resolve(context, MaterialR.attr.textAppearanceBodyLarge))
            setTextColor(MaterialColors.getColor(this, text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, text))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply { marginStart = dpToPx(12) })
        }
        isSelected = selected
        setOnClickListener { onClick() }
    }

    private fun rowBackground(view: View, @AttrRes surface: Int, index: Int, count: Int): RippleDrawable {
        val outer = dpToPx(ROW_OUTER_RADIUS).toFloat()
        val inner = dpToPx(ROW_INNER_RADIUS).toFloat()
        val top = if (index == 0) outer else inner
        val bottom = if (index == count - 1) outer else inner
        val shape = GradientDrawable().apply {
            cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            setColor(MaterialColors.getColor(view, surface))
        }
        val ripple = ColorStateList.valueOf(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight))
        return RippleDrawable(ripple, shape, null)
    }

    private fun resolve(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    private fun animateIn(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.alpha = 0f
        view.scaleX = ENTER_SCALE
        view.scaleY = ENTER_SCALE
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ENTER_SCALE_MS)
            .setInterpolator(OvershootInterpolator(ENTER_TENSION))
            .start()
        ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
            duration = ENTER_FADE_MS
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun bind(button: MaterialButton, action: Action?, dialog: AlertDialog) {
        if (action == null) {
            button.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        button.text = action.text
        button.setOnClickListener {
            if (action.dismiss) dialog.dismiss()
            action.onClick(dialog)
        }
    }
}
