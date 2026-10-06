package com.drdisagree.pixellauncherenhanced.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.AppInfoModel
import java.util.Locale

class AppPickerAdapter(
    private val apps: List<AppInfoModel>,
    private val onPick: (AppInfoModel) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var visibleApps: List<AppInfoModel> = apps

    @SuppressLint("NotifyDataSetChanged")
    fun filter(query: String) {
        val needle = query.trim().lowercase(Locale.getDefault())
        visibleApps = if (needle.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.appName.lowercase(Locale.getDefault()).contains(needle) ||
                        it.packageName.lowercase(Locale.getDefault()).contains(needle)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_app_picker_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = visibleApps[position]

        holder.icon.setImageDrawable(app.appIcon)
        holder.title.text = app.appName
        holder.summary.text = app.packageName
        holder.itemView.setOnClickListener { onPick(app) }
    }

    override fun getItemCount(): Int = visibleApps.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val title: TextView = view.findViewById(R.id.title)
        val summary: TextView = view.findViewById(R.id.summary)
    }
}
