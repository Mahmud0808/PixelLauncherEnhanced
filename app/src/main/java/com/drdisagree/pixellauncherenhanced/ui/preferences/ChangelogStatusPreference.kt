package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.shape.MaterialShapes
import com.google.android.material.R as MaterialR

class ChangelogStatusPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    var failed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    var onRetry: (() -> Unit)? = null

    private var pulse: ValueAnimator? = null

    init {
        layoutResource = R.layout.preference_changelog_status
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val root = holder.itemView

        root.findViewById<LoadingIndicator>(R.id.changelog_loading).visibility =
            if (failed) View.GONE else View.VISIBLE
        root.findViewById<ShapeIconView>(R.id.changelog_error).apply {
            visibility = if (failed) View.VISIBLE else View.GONE
            if (failed) {
                val content = MaterialColors.getColor(this, MaterialR.attr.colorOnErrorContainer)
                setShapes(MaterialShapes.SOFT_BURST, MaterialShapes.COOKIE_9)
                setColors(MaterialColors.getColor(this, MaterialR.attr.colorErrorContainer), content)
                setIcon(AppCompatResources.getDrawable(context, R.drawable.ic_error), content)
            }
        }
        root.findViewById<TextView>(R.id.changelog_title)
            .setText(if (failed) R.string.changelog_failed else R.string.changelog_loading)
        root.findViewById<TextView>(R.id.changelog_message)
            .setText(if (failed) R.string.changelog_failed_desc else R.string.changelog_loading_desc)
        root.findViewById<MaterialButton>(R.id.changelog_retry).apply {
            visibility = if (failed) View.VISIBLE else View.GONE
            setOnClickListener { onRetry?.invoke() }
        }

        val skeleton = root.findViewById<View>(R.id.changelog_skeleton)
        pulse?.cancel()
        pulse = null
        skeleton.visibility = if (failed) View.GONE else View.VISIBLE
        skeleton.alpha = 1f
        if (!failed && ValueAnimator.areAnimatorsEnabled()) {
            pulse = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 1f, PULSE_MIN_ALPHA).apply {
                duration = PULSE_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onDetached() {
        pulse?.cancel()
        pulse = null
        super.onDetached()
    }

    companion object {
        private const val PULSE_MS = 900L
        private const val PULSE_MIN_ALPHA = 0.45f
    }
}
