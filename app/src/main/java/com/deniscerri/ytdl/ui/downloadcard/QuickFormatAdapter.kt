package com.deniscerri.ytdl.ui.downloadcard

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.Format
import com.google.android.material.card.MaterialCardView

data class QuickFormatOption(
    val id: String,
    val title: String,
    val badge: String? = null,
    val size: String,
    val ext: String,
    val isAudio: Boolean,
    val format: Format? = null,
    val audioBitrate: String? = null,
    val videoResolution: String? = null,
    val container: String = if (isAudio) "mp3" else "mp4"
)

class QuickFormatAdapter(
    private val onItemClick: (QuickFormatOption) -> Unit
) : RecyclerView.Adapter<QuickFormatAdapter.ViewHolder>() {

    private val items = mutableListOf<QuickFormatOption>()

    fun submitList(newItems: List<QuickFormatOption>) {
        if (items.isEmpty()) {
            items.addAll(newItems)
            notifyDataSetChanged()
            return
        }

        if (items.size == newItems.size && items.map { it.id } == newItems.map { it.id }) {
            for (i in newItems.indices) {
                if (items[i] != newItems[i]) {
                    items[i] = newItems[i]
                    notifyItemChanged(i)
                }
            }
        } else {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.quick_card_view)
        val title: TextView = itemView.findViewById(R.id.format_title)
        val badge: TextView = itemView.findViewById(R.id.format_badge)
        val size: TextView = itemView.findViewById(R.id.format_size)
        val ext: TextView = itemView.findViewById(R.id.format_ext)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_format_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.size.text = item.size
        holder.ext.text = item.ext.uppercase()

        if (!item.badge.isNullOrBlank()) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = item.badge
            when (item.badge.uppercase()) {
                "FAST" -> {
                    holder.badge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32")) // Green
                }
                "HQ" -> {
                    holder.badge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E65100")) // Deep Orange
                }
                "4K", "2K" -> {
                    holder.badge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6A1B9A")) // Purple
                }
                "FHD" -> {
                    holder.badge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1565C0")) // Deep Blue
                }
                "HD" -> {
                    holder.badge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0277BD")) // Light Blue
                }
                else -> {
                    holder.badge.backgroundTintList = null
                }
            }
        } else {
            holder.badge.visibility = View.GONE
        }

        holder.card.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}
