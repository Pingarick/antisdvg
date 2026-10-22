package com.antisdvg.ui.catalog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.antisdvg.R
import com.antisdvg.databinding.ItemCatalogBookBinding

/** One catalogue row: title, author, format, and a single action button. */
class CatalogAdapter(
    private val onAction: (CatalogViewModel.Item) -> Unit
) : ListAdapter<CatalogViewModel.Item, CatalogAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCatalogBookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemCatalogBookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CatalogViewModel.Item) {
            val context = binding.root.context
            binding.catalogTitle.text = item.book.title
            binding.catalogAuthor.text = item.book.author.ifBlank { "—" }
            binding.catalogMeta.text = listOf(
                item.book.language,
                item.book.format
            ).filter { it.isNotBlank() }.joinToString(" · ")

            binding.catalogAction.apply {
                isEnabled = true
                text = when (item.state) {
                    CatalogViewModel.DownloadState.IDLE -> context.getString(R.string.catalog_download)
                    CatalogViewModel.DownloadState.DOWNLOADING -> {
                        isEnabled = false
                        context.getString(R.string.catalog_downloading)
                    }
                    CatalogViewModel.DownloadState.DONE -> context.getString(R.string.catalog_read)
                    CatalogViewModel.DownloadState.FAILED -> context.getString(R.string.catalog_retry)
                }
            }
            binding.catalogAction.setOnClickListener { onAction(item) }
        }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<CatalogViewModel.Item>() {
            override fun areItemsTheSame(
                oldItem: CatalogViewModel.Item,
                newItem: CatalogViewModel.Item
            ) = oldItem.book.id == newItem.book.id

            override fun areContentsTheSame(
                oldItem: CatalogViewModel.Item,
                newItem: CatalogViewModel.Item
            ) = oldItem == newItem
        }
    }
}
