package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapes
import com.google.android.material.R as MaterialR

class UpdateBannerPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    var onOpen: (() -> Unit)? = null
    var onSkip: (() -> Unit)? = null

    init {
        layoutResource = R.layout.preference_update_banner
        isSelectable = false
    }

    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val root = holder.itemView
        val release = UpdateManager.pendingUpdate()

        root.findViewById<ShapeIconView>(R.id.banner_emblem).apply {
            val content = MaterialColors.getColor(this, MaterialR.attr.colorOnPrimary)
            setShapes(MaterialShapes.COOKIE_12, MaterialShapes.SUNNY)
            setColors(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary), content)
            setIcon(AppCompatResources.getDrawable(context, R.drawable.ic_update), content)
        }
        root.findViewById<TextView>(R.id.banner_summary).text =
            release?.let { context.getString(R.string.update_banner_desc, it.version) }
        root.findViewById<MaterialCardView>(R.id.banner_card).setOnClickListener { onOpen?.invoke() }
        root.findViewById<ImageButton>(R.id.banner_skip).setOnClickListener { onSkip?.invoke() }
    }
}
