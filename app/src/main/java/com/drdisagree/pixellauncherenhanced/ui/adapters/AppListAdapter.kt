package com.drdisagree.pixellauncherenhanced.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.AppInfoModel
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.google.android.material.materialswitch.MaterialSwitch

class AppListAdapter(
    private val appList: List<AppInfoModel>,
    private val onSelectionChanged: (AppInfoModel) -> Unit = ::updateBlockList
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var context: Context? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        context = parent.context
        val view: View = LayoutInflater.from(parent.context).inflate(
            R.layout.view_app_list,
            parent,
            false
        )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val appInfo: AppInfoModel = appList[position]

        holder.appIcon.setImageDrawable(appInfo.appIcon)
        holder.appName.text = appInfo.appName
        holder.packageName.text = appInfo.packageName
        holder.switchView.isChecked = appInfo.isSelected
        holder.recommended.visibility = if (appInfo.recommended) View.VISIBLE else View.GONE

        holder.switchView.setOnCheckedChangeListener { compoundButton, isChecked ->
            if (!compoundButton.isPressed) return@setOnCheckedChangeListener

            appInfo.isSelected = isChecked
            onSelectionChanged(appInfo)
        }

        holder.container.setOnClickListener {
            appInfo.isSelected = !appInfo.isSelected
            holder.switchView.isChecked = appInfo.isSelected
            onSelectionChanged(appInfo)
        }

        setItemBackground(holder)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)

        holder.switchView.isChecked = appList[holder.getBindingAdapterPosition()].isSelected

        setItemBackground(holder)
    }

    override fun getItemCount(): Int {
        return appList.size
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var container: RelativeLayout = view.findViewById(R.id.container)
        var appIcon: ImageView = view.findViewById(R.id.app_icon)
        var appName: TextView = view.findViewById(R.id.title)
        var packageName: TextView = view.findViewById(R.id.summary)
        var switchView: MaterialSwitch = view.findViewById(R.id.switchView)
        var recommended: TextView = view.findViewById(R.id.recommended)
    }

    private fun setItemBackground(holder: ViewHolder) {
        val itemCount = itemCount
        val position = holder.getBindingAdapterPosition()
        val container = holder.itemView.findViewById<ViewGroup>(R.id.container)
        val layoutParams = holder.itemView.layoutParams as MarginLayoutParams

        val baseTop = dpToPx(80)
        val baseBottom = dpToPx(12)
        val midBottom = dpToPx(2)

        when {
            itemCount == 1 -> {
                layoutParams.topMargin = baseTop
                layoutParams.bottomMargin = baseBottom
            }

            position == 0 -> {
                layoutParams.topMargin = baseTop
                layoutParams.bottomMargin = midBottom
            }

            position == itemCount - 1 -> {
                layoutParams.topMargin = 0
                layoutParams.bottomMargin = 0
            }

            else -> {
                layoutParams.topMargin = 0
                layoutParams.bottomMargin = midBottom
            }
        }

        holder.itemView.layoutParams = layoutParams
        RowBackgrounds.apply(container, position, itemCount)
    }

    companion object {
        private fun updateBlockList(appInfo: AppInfoModel) {
            val appBlockList = RPrefs.getStringSet(APP_BLOCK_LIST, emptySet())!!.toMutableSet()

            if (appInfo.isSelected) {
                appBlockList.add(appInfo.packageName)
            } else {
                appBlockList.remove(appInfo.packageName)
            }

            RPrefs.putStringSet(APP_BLOCK_LIST, appBlockList)
        }
    }
}