package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
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
import com.drdisagree.pixellauncherenhanced.ui.fragments.WidgetMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.FolderMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.DockMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.GesturesMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.HomeScreenMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.IconsMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.MiscellaneousMods
import com.drdisagree.pixellauncherenhanced.ui.fragments.RecentsMods
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.ScreenStyles
import com.google.android.material.card.MaterialCardView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapes

class DashboardGridPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    private val home = DashboardTile("home_screen", ScreenStyles.HOME_SCREEN, R.string.dashboard_home_screen_desc, HomeScreenMods::class.java)
    private val dock = DashboardTile("dock", ScreenStyles.DOCK, R.string.dashboard_dock_short, DockMods::class.java)
    private val folders = DashboardTile("folders", ScreenStyles.FOLDERS, R.string.dashboard_folders_short, FolderMods::class.java)
    private val widgets = DashboardTile("widgets", ScreenStyles.WIDGETS, R.string.dashboard_widgets_short, WidgetMods::class.java)
    private val drawer = DashboardTile("app_drawer", ScreenStyles.APP_DRAWER, R.string.dashboard_app_drawer_short, AppDrawerMods::class.java)
    private val icons = DashboardTile("icons", ScreenStyles.ICONS, R.string.dashboard_icons_desc, IconsMods::class.java)
    private val gestures = DashboardTile("gestures", ScreenStyles.GESTURES, R.string.dashboard_gestures_short, GesturesMods::class.java)
    private val recents = DashboardTile("recents", ScreenStyles.RECENTS, R.string.dashboard_recents_short, RecentsMods::class.java)

    private val utilities = listOf(
        DashboardTile("backup", ScreenStyles.BACKUP, R.string.fragment_backup_desc, BackupRestore::class.java),
        DashboardTile("advanced", ScreenStyles.ADVANCED, R.string.fragment_advanced_desc, MiscellaneousMods::class.java),
        DashboardTile("about", ScreenStyles.ABOUT, R.string.fragment_about_desc, About::class.java)
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
            buildDashboard(grid)
        }

        if (!hasPlayedEntrance) {
            hasPlayedEntrance = true
            playEntrance(grid)
        }
    }

    private fun buildDashboard(grid: LinearLayout) {
        val gap = dpToPx(GAP_DP)
        val short = dpToPx(SHORT_TILE_DP)

        grid.addView(featuredTile(grid, home))

        val middle = row(grid, gap)
        middle.addView(tallTile(grid, icons, short * 2 + gap, Tone.TERTIARY), weighted(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val stack = LinearLayout(grid.context).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(compactTile(grid, drawer, short, Tone.SECONDARY), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stack.addView(compactTile(grid, dock, short, Tone.SECONDARY), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = gap })
        middle.addView(stack, weighted(gap, ViewGroup.LayoutParams.MATCH_PARENT))
        grid.addView(middle)

        val third = row(grid, gap)
        third.addView(compactTile(grid, folders, short), weighted(0, ViewGroup.LayoutParams.MATCH_PARENT))
        third.addView(compactTile(grid, widgets, short), weighted(gap, ViewGroup.LayoutParams.MATCH_PARENT))
        grid.addView(third)

        val fourth = row(grid, gap)
        fourth.addView(compactTile(grid, gestures, short), weighted(0, ViewGroup.LayoutParams.MATCH_PARENT))
        fourth.addView(compactTile(grid, recents, short), weighted(gap, ViewGroup.LayoutParams.MATCH_PARENT))
        grid.addView(fourth)

        grid.addView(buildUtilities(grid).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = gap
                bottomMargin = dpToPx(DASHBOARD_BOTTOM_DP)
            }
        })
    }

    private fun row(parent: ViewGroup, gap: Int) = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap }
    }

    private fun weighted(start: Int, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(0, height, 1f).apply { marginStart = start }

    private fun surfaceCard(parent: ViewGroup, color: Int, cornerDp: Int = TILE_RADIUS_DP): MaterialCardView {
        return MaterialCardView(parent.context).apply {
            radius = dpToPx(cornerDp).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color)
            isClickable = true
            isFocusable = true
        }
    }

    private fun featuredTile(parent: ViewGroup, tile: DashboardTile): View {
        val context = parent.context
        val card = surfaceCard(parent, MaterialColors.getColor(parent, MaterialR.attr.colorPrimaryContainer), FEATURE_RADIUS_DP)
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(FEATURE_HEIGHT_DP))
        val frame = android.widget.FrameLayout(context)
        val shapeSize = dpToPx(FEATURE_SHAPE_DP)
        val shape = ShapeIconView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(shapeSize, shapeSize, Gravity.END or Gravity.CENTER_VERTICAL)
            translationX = shapeSize * 0.55f
            rotation = -12f
            spinSlowly(this)
        }
        val backdrop = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(shape, androidx.appcompat.R.attr.colorPrimary),
            FEATURE_BACKDROP_ALPHA
        )
        shape.setShapes(MaterialShapes.COOKIE_12, MaterialShapes.COOKIE_12)
        shape.setIcon(null, 0)
        shape.setColors(backdrop, backdrop)
        frame.addView(shape)

        val iconSize = dpToPx(TALL_SHAPE_DP)
        val icon = ShapeIconView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(iconSize, iconSize, Gravity.START or Gravity.TOP).apply {
                marginStart = dpToPx(TILE_PADDING_DP)
                topMargin = dpToPx(TILE_PADDING_DP)
            }
        }
        bindShape(icon, tile)
        frame.addView(icon)
        frame.addView(textBlock(context, tile, titleAppearance = MaterialR.attr.textAppearanceHeadlineSmall, tone = Tone.PRIMARY).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM
            ).apply {
                marginStart = dpToPx(TILE_PADDING_DP)
                bottomMargin = dpToPx(TILE_PADDING_DP)
                marginEnd = dpToPx(FEATURE_TEXT_END_DP)
            }
        })
        card.addView(frame)
        attach(card, tile, icon)
        return card
    }

    private fun spinSlowly(view: View) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        val spin = android.animation.ObjectAnimator.ofFloat(view, View.ROTATION, view.rotation, view.rotation + 360f).apply {
            duration = BACKDROP_SPIN_MS
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (spin.isPaused) spin.resume() else if (!spin.isStarted) spin.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                spin.pause()
            }
        })
    }

    private enum class Tone(val container: Int, val onContainer: Int, val onContainerVariant: Int, val accent: Int, val onAccent: Int) {
        SURFACE(
            MaterialR.attr.colorSurfaceContainerHigh, MaterialR.attr.colorOnSurface, MaterialR.attr.colorOnSurfaceVariant,
            androidx.appcompat.R.attr.colorPrimary, MaterialR.attr.colorOnPrimary
        ),
        PRIMARY(
            MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer,
            androidx.appcompat.R.attr.colorPrimary, MaterialR.attr.colorOnPrimary
        ),
        SECONDARY(
            MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer,
            MaterialR.attr.colorSecondary, MaterialR.attr.colorOnSecondary
        ),
        TERTIARY(
            MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer,
            MaterialR.attr.colorTertiary, MaterialR.attr.colorOnTertiary
        )
    }

    private fun tallTile(parent: ViewGroup, tile: DashboardTile, height: Int, tone: Tone = Tone.SURFACE): View {
        val context = parent.context
        val card = surfaceCard(parent, MaterialColors.getColor(parent, tone.container))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(TILE_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            minimumHeight = height
        }
        val shape = ShapeIconView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(TALL_SHAPE_DP), dpToPx(TALL_SHAPE_DP))
        }
        bindShape(shape, tile, tone)
        column.addView(shape)
        column.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        column.addView(textBlock(context, tile, MaterialR.attr.textAppearanceTitleLarge, tone))
        card.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        attach(card, tile, shape)
        return card
    }

    private fun compactTile(parent: ViewGroup, tile: DashboardTile, height: Int, tone: Tone = Tone.SURFACE): View {
        val context = parent.context
        val card = surfaceCard(parent, MaterialColors.getColor(parent, tone.container))
        val rowView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dpToPx(COMPACT_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            minimumHeight = height
        }
        val shape = ShapeIconView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(COMPACT_SHAPE_DP), dpToPx(COMPACT_SHAPE_DP))
        }
        bindShape(shape, tile, tone)
        rowView.addView(shape)
        rowView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setTextAppearance(resolveAppearance(context, MaterialR.attr.textAppearanceTitleMedium))
                setTextColor(MaterialColors.getColor(parent, tone.onContainer))
                setText(tile.style.title)
            })
            addView(TextView(context).apply {
                setTextAppearance(resolveAppearance(context, MaterialR.attr.textAppearanceBodySmall))
                setTextColor(MaterialColors.getColor(parent, tone.onContainerVariant))
                if (tone != Tone.SURFACE) alpha = 0.8f
                setText(tile.summary)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dpToPx(COMPACT_GAP_DP)
        })
        card.addView(rowView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        attach(card, tile, shape)
        return card
    }

    private fun textBlock(context: android.content.Context, tile: DashboardTile, titleAppearance: Int, tone: Tone): LinearLayout {
        val titleColor = MaterialColors.getColor(context, tone.onContainer, 0)
        val summaryColor = MaterialColors.getColor(context, tone.onContainerVariant, 0)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setTextAppearance(resolveAppearance(context, titleAppearance))
                setTextColor(titleColor)
                setText(tile.style.title)
            })
            addView(TextView(context).apply {
                setTextAppearance(resolveAppearance(context, MaterialR.attr.textAppearanceBodyMedium))
                setTextColor(summaryColor)
                if (tone != Tone.SURFACE) alpha = 0.8f
                setText(tile.summary)
            })
        }
    }

    private fun resolveAppearance(context: android.content.Context, attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    private fun bindShape(shape: ShapeIconView, tile: DashboardTile, tone: Tone = Tone.SURFACE) {
        val shapeColor = MaterialColors.getColor(shape, tone.accent)
        val iconColor = MaterialColors.getColor(shape, tone.onAccent)
        shape.setShapes(tile.style.restShape, tile.style.pressedShape)
        shape.setIcon(AppCompatResources.getDrawable(shape.context, tile.style.icon), iconColor)
        shape.setColors(shapeColor, iconColor)
    }

    private fun attach(card: MaterialCardView, tile: DashboardTile, shape: ShapeIconView) {
        card.tag = tile
        card.transitionName = TRANSITION_PREFIX + tile.key
        card.rippleColor = ColorStateList.valueOf(
            MaterialColors.getColor(card, MaterialR.attr.colorOnSurface) and 0x1FFFFFFF
        )
        card.attachPressFeedback(shape)
        card.setOnClickListener {
            (card.context as? MainActivity)?.openFromDashboard(tile.fragment, card)
        }
    }

    private fun buildUtilities(parent: ViewGroup): View {
        val inflater = LayoutInflater.from(parent.context)
        val gap = dpToPx(GAP_DP)
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        utilities.forEachIndexed { index, tile ->
            val pill = inflater.inflate(R.layout.view_dashboard_pill, row, false) as MaterialCardView
            if (index > 0) (pill.layoutParams as LinearLayout.LayoutParams).marginStart = gap
            pill.tag = tile
            pill.transitionName = TRANSITION_PREFIX + tile.key
            pill.findViewById<ImageView>(R.id.pill_icon).apply {
                setImageDrawable(AppCompatResources.getDrawable(context, tile.style.icon))
                imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(pill, MaterialR.attr.colorOnSurfaceVariant)
                )
            }
            pill.findViewById<TextView>(R.id.pill_title).setText(
                if (tile.key == "backup") R.string.dashboard_backup_short else tile.style.title
            )
            pill.attachPressFeedback(null)
            pill.setOnClickListener {
                (pill.context as? MainActivity)?.openFromDashboard(tile.fragment, pill)
            }
            row.addView(pill)
        }
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.attachPressFeedback(shapeView: ShapeIconView?) {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    shapeView?.setPressedState(true)
                    view.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                        .setDuration(140).setInterpolator(EMPHASIZED).start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    shapeView?.setPressedState(false)
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(420).setInterpolator(OvershootInterpolator(2.2f)).start()
                }
            }
            false
        }
    }

    private fun playEntrance(grid: ViewGroup) {
        val offset = dpToPx(ENTRANCE_OFFSET_DP).toFloat()
        var order = 0
        grid.forEachAnimated { view ->
            view.alpha = 0f
            view.translationY = offset
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(order++ * STAGGER_MS)
                .setDuration(560)
                .setInterpolator(EMPHASIZED)
                .start()
        }
    }

    private fun View.forEachAnimated(action: (View) -> Unit) {
        if (tag is DashboardTile) {
            action(this)
            return
        }
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).forEachAnimated(action)
    }

    companion object {
        private const val TRANSITION_PREFIX = "dashboard_tile_"
        private const val GAP_DP = 12
        private const val SHORT_TILE_DP = 76
        private const val TILE_RADIUS_DP = 28
        private const val FEATURE_RADIUS_DP = 32
        private const val FEATURE_HEIGHT_DP = 168
        private const val FEATURE_SHAPE_DP = 240
        private const val FEATURE_TEXT_END_DP = 96
        private const val TALL_SHAPE_DP = 48
        private const val FEATURE_BACKDROP_ALPHA = 40
        private const val BACKDROP_SPIN_MS = 60000L
        private const val DASHBOARD_BOTTOM_DP = 32
        private const val TILE_PADDING_DP = 20
        private const val COMPACT_PADDING_DP = 14
        private const val COMPACT_SHAPE_DP = 40
        private const val COMPACT_GAP_DP = 12
        private const val ENTRANCE_OFFSET_DP = 28
        private const val STAGGER_MS = 45L
        private const val PRESSED_SCALE = 0.97f
        private val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
