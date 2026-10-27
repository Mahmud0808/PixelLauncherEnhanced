package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.ReleaseInfo
import com.drdisagree.pixellauncherenhanced.ui.widgets.ReleaseNotesView
import com.google.android.material.color.MaterialColors
import java.time.Instant
import com.google.android.material.R as MaterialR

class ReleaseNotesPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    var release: ReleaseInfo? = null
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    var headline: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    var expanded: Boolean = true

    @StringRes
    var badge: Int = 0
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.preference_release_notes
        isSelectable = false
    }

    constructor(context: Context) : this(context, null)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val root = holder.itemView
        val info = release ?: return
        val expandable = !headline

        root.findViewById<TextView>(R.id.notes_title).apply {
            text = if (headline) context.getString(R.string.update_whats_new)
            else context.getString(R.string.update_version, info.version)
            setTextColor(
                MaterialColors.getColor(
                    this,
                    if (headline) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorOnSurface
                )
            )
        }
        root.findViewById<TextView>(R.id.notes_release).text = subtitle(info)

        root.findViewById<TextView>(R.id.notes_badge).apply {
            visibility = if (badge != 0) View.VISIBLE else View.GONE
            if (badge != 0) {
                val pill = MaterialColors.getColor(this, MaterialR.attr.colorTertiaryContainer)
                setText(badge)
                setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnTertiaryContainer))
                background = GradientDrawable().apply {
                    cornerRadius = Float.MAX_VALUE
                    setColor(pill)
                }
            }
        }

        val body = root.findViewById<ReleaseNotesView>(R.id.notes_body).apply { setNotes(info.notes) }
        val chevron = root.findViewById<ImageView>(R.id.notes_chevron)
        val header = root.findViewById<View>(R.id.notes_header)

        chevron.visibility = if (expandable) View.VISIBLE else View.GONE
        header.isClickable = expandable
        header.isFocusable = expandable
        applyExpanded(body, chevron, expanded || !expandable, animate = false)

        header.setOnClickListener(
            if (expandable) View.OnClickListener {
                expanded = !expanded
                (root.parent as? ViewGroup)?.let {
                    TransitionManager.beginDelayedTransition(it, ChangeBounds().setDuration(EXPAND_MS))
                }
                applyExpanded(body, chevron, expanded, animate = true)
            } else null
        )
    }

    private fun applyExpanded(body: View, chevron: ImageView, open: Boolean, animate: Boolean) {
        body.visibility = if (open) View.VISIBLE else View.GONE
        val rotation = if (open) 90f else 0f
        if (animate) chevron.animate().rotation(rotation).setDuration(EXPAND_MS).start()
        else chevron.rotation = rotation
    }

    private fun subtitle(info: ReleaseInfo): String {
        val date = runCatching {
            DateUtils.formatDateTime(
                context,
                Instant.parse(info.publishedAt).toEpochMilli(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
            )
        }.getOrNull()
        return when {
            headline && date != null -> context.getString(R.string.update_notes_release, info.version, date)
            headline -> context.getString(R.string.update_version, info.version)
            else -> date.orEmpty()
        }
    }

    companion object {
        private const val EXPAND_MS = 250L
    }
}
