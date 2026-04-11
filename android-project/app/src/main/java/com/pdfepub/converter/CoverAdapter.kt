package com.pdfepub.converter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.card.MaterialCardView

class CoverAdapter(
    private val onSelected: (String) -> Unit
) : RecyclerView.Adapter<CoverAdapter.VH>() {

    private val items = mutableListOf<String>()
    var selectedUrl: String? = null
        private set

    private val COLOR_SELECTED = Color.parseColor("#2E7D32")  // success green

    fun addItems(urls: List<String>) {
        val start = items.size
        items += urls
        notifyItemRangeInserted(start, urls.size)
    }

    fun clear() {
        val size = items.size
        items.clear()
        selectedUrl = null
        notifyItemRangeRemoved(0, size)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card : MaterialCardView = itemView.findViewById(R.id.cardItem)
        val image: ImageView        = itemView.findViewById(R.id.imgCover)
        val badge: View             = itemView.findViewById(R.id.viewSelected)

        fun bind(url: String) {
            val selected = (url == selectedUrl)

            // Borda verde + badge de check quando selecionado
            if (selected) {
                card.strokeWidth = 6
                card.strokeColor = COLOR_SELECTED
                badge.visibility = View.VISIBLE
            } else {
                card.strokeWidth = 0
                badge.visibility = View.GONE
            }

            itemView.alpha  = if (selected) 1f else 0.80f
            itemView.scaleX = if (selected) 1f else 0.95f
            itemView.scaleY = if (selected) 1f else 0.95f

            Glide.with(image.context)
                .load(url)
                .apply(
                    RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_book_placeholder)
                        .error(R.drawable.ic_book_placeholder)
                        .override(300, 450)
                        .centerCrop()
                )
                .into(image)

            itemView.setOnClickListener {
                val old = selectedUrl
                selectedUrl = url
                val newPos = bindingAdapterPosition
                if (newPos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
                old?.let { o ->
                    val oldPos = items.indexOf(o)
                    if (oldPos >= 0) notifyItemChanged(oldPos)
                }
                notifyItemChanged(newPos)
                onSelected(url)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cover, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size
}
