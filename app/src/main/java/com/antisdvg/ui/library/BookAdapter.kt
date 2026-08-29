package com.antisdvg.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.model.progressPercent
import com.antisdvg.databinding.ItemBookBinding
import com.antisdvg.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/** Renders a single [Book] card in the library list. */
class BookAdapter(
    private val onClick: (Book) -> Unit,
    private val onEdit: (Book) -> Unit = {}
) : ListAdapter<Book, BookAdapter.BookHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BookHolder(binding)
    }

    override fun onBindViewHolder(holder: BookHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookHolder(private val binding: ItemBookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(book: Book) {
            binding.root.setOnClickListener { onClick(book) }
            binding.bookEdit.setOnClickListener { onEdit(book) }
            binding.bookCover.text = book.coverEmoji.ifBlank { "📖" }
            if (book.coverUrl.isNotBlank()) {
                binding.bookCoverImage.visibility = android.view.View.VISIBLE
                // If the remote cover can't be downloaded (offline, dead link,
                // blocked host), fall back to the emoji tile instead of showing
                // a blank frame, so a book never looks like it lost its cover.
                Glide.with(binding.root)
                    .load(book.coverUrl)
                    .addListener(object : RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            binding.bookCoverImage.visibility = android.view.View.GONE
                            binding.bookCoverImage.setImageDrawable(null)
                            return false
                        }

                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: Target<android.graphics.drawable.Drawable>,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean = false
                    })
                    .into(binding.bookCoverImage)
            } else {
                binding.bookCoverImage.visibility = android.view.View.GONE
                binding.bookCoverImage.setImageDrawable(null)
            }
            binding.bookTitle.text = book.title
            binding.bookAuthor.text = buildString {
                append(book.author.ifBlank { "—" })
                if (book.publisher.isNotBlank()) {
                    append(" · ")
                    append(book.publisher)
                }
            }

            val finished = book.status == BookStatus.READ
            val started = book.totalPages > 0 && book.currentPage > 0

            binding.bookStatus.text = binding.root.context.getString(
                if (finished) R.string.finished else R.string.in_progress
            )
            // Status pill: green tint while reading, warm muted when finished.
            binding.bookStatus.setBackgroundResource(
                if (finished) R.drawable.bg_status_finished else R.drawable.bg_status_reading
            )
            binding.bookStatus.setTextColor(
                binding.root.context.getColor(
                    if (finished) R.color.gold_dark else R.color.accent_primary_dark
                )
            )
            // "Сейчас читаю" eyebrow only for an in-progress book.
            binding.bookReadingLine.visibility = if (!finished && started) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }

            val progress = book.progressPercent
            binding.bookProgress.progress = progress

            binding.bookProgressText.text = when {
                finished -> binding.root.context.getString(R.string.finished)
                book.totalPages <= 0 -> binding.root.context.getString(R.string.not_started)
                else -> binding.root.context.getString(
                    R.string.book_read_progress_pages,
                    book.currentPage,
                    book.totalPages,
                    progress
                )
            }
        }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(oldItem: Book, newItem: Book) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Book, newItem: Book) = oldItem == newItem
        }
    }
}
