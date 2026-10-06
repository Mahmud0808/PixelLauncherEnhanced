package com.drdisagree.pixellauncherenhanced.utils

import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.drdisagree.pixellauncherenhanced.data.enums.SegmentPosition
import com.drdisagree.pixellauncherenhanced.ui.drawables.SegmentedRowDrawable

object RowBackgrounds {

    fun apply(view: View, index: Int, count: Int) {
        val position = when {
            count <= 1 -> SegmentPosition.SINGLE
            index == 0 -> SegmentPosition.TOP
            index == count - 1 -> SegmentPosition.BOTTOM
            else -> SegmentPosition.MIDDLE
        }

        val row = view.background as? SegmentedRowDrawable
            ?: SegmentedRowDrawable(view.context).also { view.background = it }
        row.setPosition(position)
        view.clipToOutline = true

        if (view.foreground == null) {
            val ripple = TypedValue()
            view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            view.foreground = ContextCompat.getDrawable(view.context, ripple.resourceId)
        }
    }
}
