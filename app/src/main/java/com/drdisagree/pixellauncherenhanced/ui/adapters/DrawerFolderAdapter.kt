package com.drdisagree.pixellauncherenhanced.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.DrawerFolder
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import java.util.Collections

class DrawerFolderAdapter(
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onClick: (DrawerFolder) -> Unit,
    private val onDelete: (DrawerFolder) -> Unit
) : RecyclerView.Adapter<DrawerFolderAdapter.FolderHolder>() {

    private val items = mutableListOf<DrawerFolder>()

    val folders: List<DrawerFolder>
        get() = items.toList()

    fun submit(folders: List<DrawerFolder>) {
        val old = items.toList()
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = folders.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == folders[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == folders[newPos]
        }).also {
            items.clear()
            items.addAll(folders)
        }.dispatchUpdatesTo(this)
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderHolder {
        return FolderHolder(LayoutInflater.from(parent.context).inflate(R.layout.view_drawer_tab, parent, false))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: FolderHolder, position: Int) {
        val folder = items[position]
        val context = holder.itemView.context

        holder.title.text = folder.title
        val count = when {
            folder.apps.isEmpty() -> context.getString(R.string.drawer_folders_empty)
            folder.apps.size < MIN_APPS -> context.getString(R.string.drawer_folders_too_few)
            else -> context.getString(R.string.drawer_tabs_custom_count, folder.apps.size)
        }
        holder.summary.text = count

        holder.dragHandle.visibility = View.VISIBLE
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }

        holder.action.apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = context.getString(R.string.drawer_folders_delete)
            setOnClickListener {
                items.getOrNull(holder.bindingAdapterPosition)?.let(onDelete)
            }
        }

        holder.container.setOnClickListener {
            items.getOrNull(holder.bindingAdapterPosition)?.let(onClick)
        }

        applyShape(holder, position)
    }

    fun updateBackground(holder: RecyclerView.ViewHolder) {
        if (holder !is FolderHolder) return
        val position = holder.bindingAdapterPosition.takeIf { it in items.indices } ?: return
        applyShape(holder, position)
    }

    private fun applyShape(holder: FolderHolder, index: Int) {
        val layoutParams = holder.itemView.layoutParams as MarginLayoutParams

        RowBackgrounds.apply(holder.container, index, items.size)
        layoutParams.bottomMargin = if (index == items.size - 1) 0 else dpToPx(2)
        holder.itemView.layoutParams = layoutParams
        holder.container.clipToOutline = true
    }

    class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.container)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val title: TextView = view.findViewById(R.id.title)
        val summary: TextView = view.findViewById(R.id.summary)
        val action: ImageButton = view.findViewById(R.id.action)
    }

    companion object {
        const val MIN_APPS = 2
    }
}
