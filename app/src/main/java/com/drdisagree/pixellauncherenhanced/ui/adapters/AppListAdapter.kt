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
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.AppInfoModel
import com.google.android.material.materialswitch.MaterialSwitch

class AppListAdapter(private val appList: List<AppInfoModel>) :
    RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

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

        holder.switchView.setOnCheckedChangeListener { compoundButton, isChecked ->
            if (!compoundButton.isPressed) return@setOnCheckedChangeListener

            appInfo.isSelected = isChecked
            val appBlockList = RPrefs.getStringSet(APP_BLOCK_LIST, emptySet())!!.toMutableList()

            if (isChecked) {
                if (!appBlockList.contains(appInfo.packageName)) {
                    appBlockList.add(appInfo.packageName)
                }
            } else {
                appBlockList.remove(appInfo.packageName)
            }
            RPrefs.putStringSet(APP_BLOCK_LIST, appBlockList.toSet())
        }

        holder.container.setOnClickListener {
            appInfo.isSelected = !appInfo.isSelected
            holder.switchView.isChecked = appInfo.isSelected

            val appBlockList = RPrefs.getStringSet(APP_BLOCK_LIST, emptySet())!!.toMutableList()
            if (appInfo.isSelected) {
                if (!appBlockList.contains(appInfo.packageName)) {
                    appBlockList.add(appInfo.packageName)
                }
            } else {
                appBlockList.remove(appInfo.packageName)
            }
            RPrefs.putStringSet(APP_BLOCK_LIST, appBlockList.toSet())
        }

        updateMargin(holder)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)

        holder.switchView.isChecked = appList[holder.getBindingAdapterPosition()].isSelected

        updateMargin(holder)
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
    }

    private fun updateMargin(holder: ViewHolder) {
        if (context == null) return

        val (topGap, bottomGap) = if (holder.getBindingAdapterPosition() == 0) {
            (80 * context!!.resources.displayMetrics.density).toInt() to 0
        } else if (holder.getBindingAdapterPosition() == itemCount - 1) {
            0 to (48 * context!!.resources.displayMetrics.density).toInt()
        } else {
            0 to 0
        }

        (holder.container.layoutParams as MarginLayoutParams).apply {
            topMargin = topGap
            bottomMargin = bottomGap
        }
    }
}