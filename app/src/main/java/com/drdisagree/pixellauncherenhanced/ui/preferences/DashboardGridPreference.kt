package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.DashboardTile
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.ui.fragments.About
import com.drdisagree.pixellauncherenhanced.ui.fragments.AppDrawerMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.BackupRestore
import com.drdisagree.pixellauncherenhanced.ui.fragments.GesturesMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.HomeScreenMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.IconsMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.MiscellaneousMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.RecentsMods
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.ScreenStyles
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

class DashboardGridPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    private val rows: List<List<DashboardTile>> = listOf(
        listOf(
            DashboardTile("home_screen", ScreenStyles.HOME_SCREEN, R.string.dashboard_home_screen_desc, HomeScreenMods::class.java)
        ),
        listOf(
            DashboardTile("app_drawer", ScreenStyles.APP_DRAWER, R.string.dashboard_app_drawer_desc, AppDrawerMods::class.java),
            DashboardTile("icons", ScreenStyles.ICONS, R.string.dashboard_icons_desc, IconsMods::class.java)
        ),
        listOf(
            DashboardTile("gestures", ScreenStyles.GESTURES, R.string.fragment_gestures_desc, GesturesMods::class.java),
            DashboardTile("recents", ScreenStyles.RECENTS, R.string.dashboard_recents_desc, RecentsMods::class.java)
        ),
        listOf(
            DashboardTile("backup", ScreenStyles.BACKUP, R.string.fragment_backup_desc, BackupRestore::class.java)
        ),
        listOf(
            DashboardTile("advanced", ScreenStyles.ADVANCED, R.string.fragment_advanced_desc, MiscellaneousMods::class.java),
            DashboardTile("about", ScreenStyles.ABOUT, R.string.fragment_about_desc, About::class.java)
        )
    )

    private var hasPlayedEntrance = false

    init {
        layoutResource = R.layout.preference_dashboard_grid
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val grid = holder.itemView as LinearLayout

        if (grid.tag !== this) {
            grid.removeAllViews()
            grid.tag = this
            buildGrid(grid)
        }

        if (!hasPlayedEntrance) {
            hasPlayedEntrance = true
            playEntrance(grid)
        }
    }

    private fun buildGrid(grid: LinearLayout) {
        val inflater = LayoutInflater.from(grid.context)
        val gap = dpToPx(GAP_DP)

        rows.forEachIndexed { rowIndex, row ->
            val rowLayout = LinearLayout(grid.context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = gap }
            }

            row.forEachIndexed { index, tile ->
                val layout = if (row.size == 1) R.layout.view_dashboard_tile_wide else R.layout.view_dashboard_tile
                val card = inflater.inflate(layout, rowLayout, false) as MaterialCardView
                if (index > 0) (card.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                bindTile(card, tile)
                rowLayout.addView(card)
            }

            grid.addView(rowLayout)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTile(card: MaterialCardView, tile: DashboardTile) {
        val cardColor = MaterialColors.getColor(card, tile.style.palette.card)
        val textColor = MaterialColors.getColor(card, tile.style.palette.text)
        val shapeView = card.findViewById<ShapeIconView>(R.id.tile_shape)

        card.tag = tile
        card.transitionName = TRANSITION_PREFIX + tile.key
        card.setCardBackgroundColor(cardColor)
        card.rippleColor = android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 28))

        shapeView.setShapes(tile.style.restShape, tile.style.pressedShape)
        shapeView.setIcon(
            AppCompatResources.getDrawable(card.context, tile.style.icon),
            MaterialColors.getColor(card, tile.style.palette.icon)
        )
        shapeView.setColors(
            MaterialColors.getColor(card, tile.style.palette.shape),
            MaterialColors.getColor(card, tile.style.palette.icon)
        )

        card.findViewById<TextView>(R.id.tile_title).apply {
            setText(tile.style.title)
            setTextColor(textColor)
        }
        card.findViewById<TextView>(R.id.tile_summary).apply {
            setText(tile.summary)
            setTextColor(textColor)
        }

        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    shapeView.setPressedState(true)
                    view.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                        .setDuration(140).setInterpolator(EMPHASIZED).start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    shapeView.setPressedState(false)
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(420).setInterpolator(OvershootInterpolator(2.2f)).start()
                }
            }
            false
        }
        card.setOnClickListener {
            (card.context as? MainActivity)?.openFromDashboard(tile.fragment, card)
        }
    }

    private fun playEntrance(grid: ViewGroup) {
        val offset = dpToPx(ENTRANCE_OFFSET_DP).toFloat()
        var order = 0

        grid.forEachTile { card, _ ->
            card.alpha = 0f
            card.translationY = offset
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(order++ * STAGGER_MS)
                .setDuration(560)
                .setInterpolator(EMPHASIZED)
                .start()
        }
    }

    private inline fun ViewGroup.forEachTile(action: (MaterialCardView, DashboardTile) -> Unit) {
        for (rowIndex in 0 until childCount) {
            val row = getChildAt(rowIndex) as? ViewGroup ?: continue
            for (index in 0 until row.childCount) {
                val card = row.getChildAt(index) as? MaterialCardView ?: continue
                val tile = card.tag as? DashboardTile ?: continue
                action(card, tile)
            }
        }
    }

    companion object {
        private const val TRANSITION_PREFIX = "dashboard_tile_"
        private const val GAP_DP = 12
        private const val ENTRANCE_OFFSET_DP = 28
        private const val STAGGER_MS = 45L
        private const val PRESSED_SCALE = 0.96f
        private val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
