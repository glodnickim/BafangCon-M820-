package com.test.bafangcon

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SystemInfoSection(
    val header: String,
    val items: List<SystemInfoItem>
)

data class SystemInfoItem(
    val label: String,
    val value: String,
    val dead: Boolean = false   // stałe podczas jazdy → ukrywane gdy !showDeadBlocks
)

class SystemInfoAdapter(
    private val sections: MutableList<SystemInfoSection> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    fun updateData(newSections: List<SystemInfoSection>) {
        sections.clear()
        sections.addAll(newSections)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        var offset = 0
        for (section in sections) {
            if (position == offset) return TYPE_HEADER
            offset += 1 + section.items.size
            if (position < offset) return TYPE_ITEM
        }
        return TYPE_ITEM
    }

    override fun getItemCount(): Int {
        return sections.sumOf { 1 + it.items.size }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            ItemViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        var offset = 0
        for (section in sections) {
            if (position == offset) {
                (holder as HeaderViewHolder).textView.text = section.header
                return
            }
            offset += 1
            val itemIndex = position - offset
            if (itemIndex < section.items.size) {
                val item = section.items[itemIndex]
                if (holder is ItemViewHolder) {
                    holder.text1.text = item.label
                    holder.text2.text = item.value
                }
                return
            }
            offset += section.items.size
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    private class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(android.R.id.text1)
        val text2: TextView = view.findViewById(android.R.id.text2)
    }
}
