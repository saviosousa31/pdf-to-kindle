package com.pdfepub.converter

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.material.card.MaterialCardView

class CoverAdapter(
    /** Chamado quando o usuário SELECIONA uma nova capa. */
    private val onSelected: (String) -> Unit,
    /** Chamado quando o usuário CLICA NOVAMENTE na capa já selecionada (des-seleciona). */
    private val onDeselected: (() -> Unit)? = null
) : RecyclerView.Adapter<CoverAdapter.VH>() {

    private val items = mutableListOf<String>()
    var selectedUrl: String? = null
        private set

    private val COLOR_SELECTED = Color.parseColor("#1B8A3E")

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

    /** Limpa seleção programaticamente sem disparar callbacks. */
    fun clearSelection() {
        val old = selectedUrl ?: return
        selectedUrl = null
        val pos = items.indexOf(old)
        if (pos >= 0) notifyItemChanged(pos)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card : MaterialCardView = itemView.findViewById(R.id.cardItem)
        val image: ImageView        = itemView.findViewById(R.id.imgCover)
        val badge: View             = itemView.findViewById(R.id.viewSelected)

        fun bind(url: String) {
            val selected = (url == selectedUrl)
            itemView.visibility = View.VISIBLE
            itemView.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            itemView.requestLayout()

            applySelectedStyle(selected)

            val fallbackUrl = url
                .replace("m.media-amazon.com/images/P/", "images-na.ssl-images-amazon.com/images/P/")
                .replace("._SCLZZZZZZZ_SX600_", ".LZZZZZZZ")

            Glide.with(image.context).load(url)
                .apply(RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(300, 450)
                    .centerCrop())
                .error(
                    Glide.with(image.context).load(fallbackUrl)
                )
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?,
                        target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        itemView.visibility = View.GONE
                        itemView.layoutParams = itemView.layoutParams.also { it.width = 0 }
                        itemView.requestLayout()
                        return true
                    }
                    override fun onResourceReady(resource: Drawable, model: Any,
                        target: Target<Drawable>?, dataSource: DataSource,
                        isFirstResource: Boolean): Boolean {
                        itemView.visibility = View.VISIBLE
                        return false
                    }
                }).into(image)

            itemView.setOnClickListener {
                if (itemView.visibility != View.VISIBLE) return@setOnClickListener
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_ID.toInt()) return@setOnClickListener

                if (url == selectedUrl) {
                    // item 6: clicar novamente des-seleciona
                    selectedUrl = null
                    notifyItemChanged(pos)
                    onDeselected?.invoke()
                } else {
                    val old = selectedUrl
                    selectedUrl = url
                    old?.let { o ->
                        val oldPos = items.indexOf(o)
                        if (oldPos >= 0) notifyItemChanged(oldPos)
                    }
                    notifyItemChanged(pos)
                    onSelected(url)
                }
            }
        }

        private fun applySelectedStyle(selected: Boolean) {
            if (selected) {
                card.strokeWidth = 7
                card.strokeColor = COLOR_SELECTED
                badge.visibility = View.VISIBLE
            } else {
                card.strokeWidth = 0
                badge.visibility = View.GONE
            }
            itemView.alpha  = if (selected) 1f else 0.85f
            itemView.scaleX = if (selected) 1f else 0.96f
            itemView.scaleY = if (selected) 1f else 0.96f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cover, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
