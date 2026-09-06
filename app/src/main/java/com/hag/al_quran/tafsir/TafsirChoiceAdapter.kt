package com.hag.al_quran.tafsir

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.hag.al_quran.R

data class TafsirChoiceItem(
    val index: Int,
    val name: String,
    val subtitle: String,
    val selected: Boolean,
    val actionIcon: Int
)

/** بطاقات اختيار التفسير بنفس أسلوب بطاقات قائمة القرّاء. */
class TafsirChoiceAdapter(
    private val items: List<TafsirChoiceItem>,
    private val onClick: (TafsirChoiceItem) -> Unit
) : RecyclerView.Adapter<TafsirChoiceAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.tafsirChoiceCard)
        val name: TextView = view.findViewById(R.id.tafsirChoiceName)
        val subtitle: TextView = view.findViewById(R.id.tafsirChoiceSubtitle)
        val action: ImageView = view.findViewById(R.id.tafsirChoiceAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tafsir_picker, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.subtitle.text = item.subtitle
        holder.action.setImageResource(item.actionIcon)

        val context = holder.itemView.context
        holder.card.strokeWidth = if (item.selected) 2 else 1
        holder.card.setStrokeColor(
            ContextCompat.getColor(
                context,
                if (item.selected) R.color.sky_blue_dark else R.color.qariItemBorder
            )
        )
        holder.card.cardElevation = if (item.selected) 8f else 5f
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
