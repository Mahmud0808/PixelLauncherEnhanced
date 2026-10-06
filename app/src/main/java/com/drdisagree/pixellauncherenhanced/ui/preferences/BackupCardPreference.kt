package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.withStyledAttributes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.enums.TilePalette
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapes

class BackupCardPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    var onBackup: (() -> Unit)? = null
    var onRestore: (() -> Unit)? = null

    private var palette = TilePalette.PRIMARY

    init {
        layoutResource = R.layout.preference_backup_card
        isSelectable = false
        context.withStyledAttributes(attrs, R.styleable.BackupCardPreference) {
            palette = TilePalette.entries[getInt(R.styleable.BackupCardPreference_cardPalette, 0)]
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val root = holder.itemView

        root.findViewById<ShapeIconView>(R.id.backup_emblem).apply {
            val iconColor = MaterialColors.getColor(this, palette.icon)
            setShapes(SHAPES[palette.ordinal % SHAPES.size], MaterialShapes.COOKIE_12)
            setIcon(icon, iconColor)
            setColors(MaterialColors.getColor(this, palette.shape), iconColor)
        }
        root.findViewById<MaterialButton>(R.id.backup_button).setOnClickListener { onBackup?.invoke() }
        root.findViewById<MaterialButton>(R.id.restore_button).setOnClickListener { onRestore?.invoke() }
    }

    companion object {
        private val SHAPES = listOf(MaterialShapes.COOKIE_9, MaterialShapes.SUNNY, MaterialShapes.CLOVER_4)
    }
}
