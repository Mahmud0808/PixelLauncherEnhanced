package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.XPOSED_HOOK_CHECK
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.ui.widgets.LauncherPreviewView
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

class DashboardHeroPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_dashboard_hero
        isSelectable = false
    }

    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val root = holder.itemView
        val active = RPrefs.getBoolean(XPOSED_HOOK_CHECK, false)

        bindStatus(root, active)
        bindLauncher(root)
        bindSurface(root, active)

        root.findViewById<TextView>(R.id.status_detail).apply {
            visibility = if (active) View.GONE else View.VISIBLE
            setText(
                if (HookCheckPreference.bootlooped) R.string.xposed_module_bootlooped_desc
                else R.string.xposed_module_disabled_desc
            )
        }
        root.findViewById<MaterialButton>(R.id.restart_button).setOnClickListener {
            context.restartLauncher()
        }
        root.findViewById<ImageButton>(R.id.status_info).apply {
            visibility = if (active) View.GONE else View.VISIBLE
            imageTintList = ColorStateList.valueOf(MaterialColors.getColor(root, MaterialR.attr.colorOnErrorContainer))
            setOnClickListener { HookCheckPreference.showHelp(context) }
        }
        root.findViewById<LauncherPreviewView>(R.id.launcher_preview).refresh()
    }

    private fun bindStatus(root: View, active: Boolean) {
        root.findViewById<TextView>(R.id.status_text).apply {
            setText(if (active) R.string.dashboard_status_active else R.string.dashboard_status_inactive)
            setTextColor(
                MaterialColors.getColor(
                    root,
                    if (active) androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorError
                )
            )
        }
    }

    private fun bindSurface(root: View, active: Boolean) {
        val surface = MaterialColors.getColor(
            root,
            if (active) MaterialR.attr.colorSurfaceContainer else MaterialR.attr.colorErrorContainer
        )
        val content = MaterialColors.getColor(
            root,
            if (active) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnErrorContainer
        )
        val secondary = MaterialColors.getColor(
            root,
            if (active) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorOnErrorContainer
        )

        root.findViewById<MaterialCardView>(R.id.hero_card).apply {
            setCardBackgroundColor(surface)
            if (active) {
                setOnClickListener(null)
                isClickable = false
            } else {
                setOnClickListener { HookCheckPreference.showHelp(context) }
            }
        }
        root.findViewById<TextView>(R.id.launcher_name).setTextColor(content)
        root.findViewById<TextView>(R.id.launcher_version).setTextColor(content)
        root.findViewById<TextView>(R.id.status_detail).setTextColor(secondary)
        if (!active) root.findViewById<TextView>(R.id.status_text).setTextColor(content)
        root.findViewById<LauncherPreviewView>(R.id.launcher_preview).alpha = if (active) 1f else INACTIVE_PREVIEW_ALPHA

        root.findViewById<MaterialButton>(R.id.restart_button).apply {
            if (getTag(R.id.restart_button) == null) {
                setTag(R.id.restart_button, listOf(backgroundTintList, textColors, iconTint))
            }
            @Suppress("UNCHECKED_CAST")
            val defaults = getTag(R.id.restart_button) as List<ColorStateList?>

            if (active) {
                backgroundTintList = defaults[0]
                setTextColor(defaults[1])
                iconTint = defaults[2]
            } else {
                val onError = ColorStateList.valueOf(MaterialColors.getColor(root, MaterialR.attr.colorOnError))
                backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(root, androidx.appcompat.R.attr.colorError))
                setTextColor(onError)
                iconTint = onError
            }
        }
    }

    private fun bindLauncher(root: View) {
        val packageManager = context.packageManager
        val info = listOf(PIXEL_LAUNCHER_PACKAGE, LAUNCHER3_PACKAGE).firstNotNullOfOrNull { packageName ->
            runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        }

        root.findViewById<TextView>(R.id.launcher_name).text = info?.applicationInfo
            ?.loadLabel(packageManager)
            ?: context.getString(R.string.dashboard_launcher_missing)
        root.findViewById<TextView>(R.id.launcher_version).apply {
            visibility = if (info?.versionName != null) View.VISIBLE else View.GONE
            text = context.getString(R.string.dashboard_launcher_version, info?.versionName.orEmpty())
        }
    }

    companion object {
        private const val INACTIVE_PREVIEW_ALPHA = 0.45f
    }
}
