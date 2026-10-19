package com.drdisagree.pixellauncherenhanced.utils

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapes
import com.google.android.material.R as MaterialR

object ExpressiveDialog {

    enum class Tone(@AttrRes val container: Int, @AttrRes val content: Int) {
        PRIMARY(MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer),
        TERTIARY(MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer),
        ERROR(MaterialR.attr.colorErrorContainer, MaterialR.attr.colorOnErrorContainer)
    }

    class Action(val text: CharSequence, val onClick: () -> Unit = {})

    private const val POP_DELAY_MS = 180L
    private const val POP_HOLD_MS = 520L
    private const val ENTER_SCALE = 0.82f
    private const val ENTER_SCALE_MS = 420L
    private const val ENTER_FADE_MS = 220L
    private const val ENTER_TENSION = 1.4f

    fun show(
        context: Context,
        @DrawableRes icon: Int,
        title: CharSequence,
        message: CharSequence,
        positive: Action,
        negative: Action? = null,
        tone: Tone = Tone.PRIMARY,
        cancelable: Boolean = true
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_expressive, null)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(cancelable)
            .create()

        val emblem = view.findViewById<ShapeIconView>(R.id.dialog_emblem).apply {
            val content = MaterialColors.getColor(this, tone.content)
            setShapes(MaterialShapes.COOKIE_12, MaterialShapes.SUNNY)
            setColors(MaterialColors.getColor(this, tone.container), content)
            setIcon(AppCompatResources.getDrawable(context, icon), content)
        }
        view.findViewById<TextView>(R.id.dialog_title).text = title
        view.findViewById<TextView>(R.id.dialog_message).text = message

        bind(view.findViewById(R.id.dialog_positive), positive, dialog)
        bind(view.findViewById(R.id.dialog_negative), negative, dialog)

        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.setWindowAnimations(R.style.Animation_PLEnhanced_Dialog)
        dialog.show()
        dialog.window?.decorView?.let { animateIn(it) }

        emblem.postDelayed({ emblem.setPressedState(true) }, POP_DELAY_MS)
        emblem.postDelayed({ emblem.setPressedState(false) }, POP_DELAY_MS + POP_HOLD_MS)
        return dialog
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
            dialog.dismiss()
            action.onClick()
        }
    }
}
