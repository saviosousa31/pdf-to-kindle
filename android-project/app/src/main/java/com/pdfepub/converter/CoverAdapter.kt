package com.pdfepub.converter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

class CoverAdapter(
    private val onSelected: (String) -> Unit
) : RecyclerView.Adapter<CoverAdapter.VH>() {

    private val items = mutableListOf<String>()
    var selectedUrl: String? = null
        private set

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
        val image: ImageView = itemView.findViewById(R.id.imgCover)
        val check: View      = itemView.findViewById(R.id.viewSelected)

        fun bind(url: String) {
            val selected = (url == selectedUrl)
            check.visibility = if (selected) View.VISIBLE else View.GONE
            itemView.alpha  = if (selected) 1f else 0.75f
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
                // [I] CORRIGIDO: bindingAdapterPosition substitui adapterPosition (deprecated)
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
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cover, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size
}
