package com.drdisagree.pixellauncherenhanced.ui.base

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.core.animation.doOnEnd
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHARED_PREFERENCES
import com.drdisagree.pixellauncherenhanced.data.config.PrefsHelper
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.ui.drawables.SegmentedRowDrawable
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar

abstract class ControlledPreferenceFragmentCompat : PreferenceFragmentCompat() {

    private val changeListener =
        OnSharedPreferenceChangeListener { _: SharedPreferences, key: String? ->
            updateScreen(key)
        }

    abstract val title: String

    abstract val backButtonEnabled: Boolean

    abstract val layoutResource: Int

    open val themeResource: Int
        get() = R.style.PrefsThemeToolbar

    abstract val hasMenu: Boolean

    open val menuResource: Int
        get() = R.menu.default_menu

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = SHARED_PREFERENCES
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

        try {
            setPreferencesFromResource(layoutResource, rootKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preference from resource", e)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if (activity != null) {
            val window = requireActivity().window
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        inflater.context.setTheme(themeResource)

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(
            requireContext() as AppCompatActivity,
            title,
            backButtonEnabled,
            view.findViewById(R.id.toolbar),
            view.findViewById(R.id.collapsing_toolbar)
        )

        if (hasMenu) {
            val menuHost: MenuHost = requireActivity()
            menuHost.addMenuProvider(object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menu.clear()
                    menuInflater.inflate(menuResource, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        R.id.force_close_launcher -> {
                            context?.restartLauncher()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

        val recyclerView = view.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            ?: return

        recyclerView.clipToPadding = false
        recyclerView.isVerticalScrollBarEnabled = false

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val navBarInset = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            val baseBottomPadding = dpToPx(16)

            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                baseBottomPadding + navBarInset
            )

            insets
        }
        ViewCompat.requestApplyInsets(recyclerView)

        arguments?.getString(MainActivity.ARG_HIGHLIGHT_KEY)?.let { key ->
            arguments?.remove(MainActivity.ARG_HIGHLIGHT_KEY)
            recyclerView.postDelayed({ scrollToHighlight(view, recyclerView, key) }, ENTER_SETTLE_MS)
        }
    }

    private fun scrollToHighlight(root: View, recyclerView: RecyclerView, key: String) {
        if (!isAdded) return
        val position = (recyclerView.adapter as? PreferenceGroup.PreferencePositionCallback)
            ?.getPreferenceAdapterPosition(key)
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

        val visible = position in layoutManager.findFirstCompletelyVisibleItemPosition()..
                layoutManager.findLastCompletelyVisibleItemPosition()
        if (!visible) root.findViewById<AppBarLayout>(R.id.appBarLayout)?.setExpanded(false, true)

        val scroller = object : LinearSmoothScroller(recyclerView.context) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return SCROLL_SPEED_MS_PER_INCH / displayMetrics.densityDpi
            }

            override fun onStop() {
                super.onStop()
                recyclerView.postDelayed({ highlightPreference(recyclerView, key) }, HIGHLIGHT_DELAY_MS)
            }
        }
        scroller.targetPosition = position
        layoutManager.startSmoothScroll(scroller)
    }

    private fun highlightPreference(recyclerView: RecyclerView, key: String) {
        if (!isAdded) return
        val position = (recyclerView.adapter as? PreferenceGroup.PreferencePositionCallback)
            ?.getPreferenceAdapterPosition(key)
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: return
        val holderView = recyclerView.findViewHolderForAdapterPosition(position)?.itemView ?: return
        val card = if (holderView.background is SegmentedRowDrawable) null else holderView.findFirstCard()
        val target = card ?: holderView

        val overlay = GradientDrawable().apply {
            cornerRadius = card?.radius ?: 0f
            setColor(MaterialColors.getColor(target, androidx.appcompat.R.attr.colorPrimary))
            setBounds(0, 0, target.width, target.height)
            alpha = 0
        }
        target.overlay.add(overlay)
        val itemView = target

        ValueAnimator.ofInt(0, HIGHLIGHT_ALPHA, 0, HIGHLIGHT_ALPHA, 0).apply {
            duration = HIGHLIGHT_DURATION_MS
            addUpdateListener { overlay.alpha = it.animatedValue as Int }
            doOnEnd { itemView.overlay.remove(overlay) }
            start()
        }
    }

    public override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        RPrefs.registerOnSharedPreferenceChangeListener(changeListener)

        updateScreen(null)

        return super.onCreateAdapter(preferenceScreen)
    }

    override fun onResume() {
        super.onResume()
        updateScreen(null)
    }

    override fun onDestroy() {
        RPrefs.unregisterOnSharedPreferenceChangeListener(changeListener)

        super.onDestroy()
    }

    open fun updateScreen(key: String?) {
        PrefsHelper.setupAllPreferences(this.preferenceScreen)
    }

    override fun setDivider(divider: Drawable?) {
        super.setDivider(Color.TRANSPARENT.toDrawable())
    }

    override fun setDividerHeight(height: Int) {
        super.setDividerHeight(0)
    }

    private fun View.findFirstCard(): MaterialCardView? {
        if (this is MaterialCardView) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) getChildAt(i).findFirstCard()?.let { return it }
        return null
    }

    companion object {
        private val TAG = ControlledPreferenceFragmentCompat::class.java.simpleName
        private const val HIGHLIGHT_DELAY_MS = 120L
        private const val ENTER_SETTLE_MS = 460L
        private const val SCROLL_SPEED_MS_PER_INCH = 70f
        private const val HIGHLIGHT_DURATION_MS = 1600L
        private const val HIGHLIGHT_ALPHA = 64
    }
}
