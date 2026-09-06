package com.hag.al_quran

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QariAdapter(
    private val items: List<QariItem>,
    private val onClick: (QariItem) -> Unit
) : RecyclerView.Adapter<QariAdapter.Holder>() {

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val qariName: TextView = v.findViewById(R.id.qariName)
        val qariQuality: TextView = v.findViewById(R.id.qariQuality)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_qari, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = items[position]
        h.qariName.text = item.name
        h.qariQuality.text = item.quality
        // ✅ مرّر العنصر نفسه للّامبدا (ليس الـ View)
        h.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
