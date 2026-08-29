package com.antisdvg.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import com.antisdvg.R
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookFormat
import com.antisdvg.databinding.ActivityBookDetailBinding
import com.antisdvg.ui.AppViewModelFactory
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

/** Full-screen form to add a new book or edit an existing one. */
class BookDetailActivity : AppCompatActivity() {

    private var _binding: ActivityBookDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BookEditorViewModel by viewModels {
        AppViewModelFactory(application)
    }

    // Whether the in-flight lookup state belongs to the Yandex Books button
    // (btnFindYandex) or the ISBN button (btnFindIsbn). They share lookupState.
    private var yandexLookup = false

    // Cover URL captured from the most recent successful lookup / scan; applied on save.
    private var pendingCover: String = ""

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityBookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bookId = intent?.getLongExtra(EXTRA_BOOK_ID, -1L) ?: -1L
        viewModel.load(bookId)

        binding.btnSaveBook.setOnClickListener { onSave() }
        binding.btnCancelBook.setOnClickListener { finish() }
        binding.btnFindIsbn.setOnClickListener { doIsbnLookup() }
        binding.btnScanIsbn.setOnClickListener { startBarcodeScan() }
        binding.btnFindYandex.setOnClickListener { doYandexLookup() }
        binding.btnDeleteBook.setOnClickListener { confirmDelete() }

        lifecycleScope.launch {
            viewModel.book.collect { book -> if (book != null) populate(book) }
        }
        lifecycleScope.launch {
            viewModel.lookupState.collect { state ->
                when (state) {
                    IsbnLookupState.LOADING ->
                        if (yandexLookup) {
                            binding.btnFindYandex.text = getString(R.string.yandex_looking_up)
                        } else {
                            binding.btnFindIsbn.text = getString(R.string.isbn_looking_up)
                        }
                    IsbnLookupState.FOUND -> {
                        viewModel.consumeLookupResult()?.let { applyLookup(it) }
                        if (yandexLookup) {
                            // A Yandex Books link is always an e-book.
                            binding.formatGroup.check(binding.formatElectronic.id)
                            binding.btnFindYandex.text = getString(R.string.yandex_find)
                            yandexLookup = false
                        } else {
                            binding.btnFindIsbn.text = getString(R.string.isbn_find)
                        }
                    }
                    IsbnLookupState.NOT_FOUND -> {
                        val msg = if (yandexLookup) R.string.yandex_not_found else R.string.isbn_not_found
                        if (yandexLookup) binding.btnFindYandex.text = getString(R.string.yandex_find)
                        else binding.btnFindIsbn.text = getString(R.string.isbn_find)
                        yandexLookup = false
                        Toast.makeText(this@BookDetailActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                    IsbnLookupState.ERROR -> {
                        val msg = if (yandexLookup) R.string.yandex_not_found else R.string.isbn_error
                        if (yandexLookup) binding.btnFindYandex.text = getString(R.string.yandex_find)
                        else binding.btnFindIsbn.text = getString(R.string.isbn_find)
                        yandexLookup = false
                        Toast.makeText(this@BookDetailActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                    IsbnLookupState.IDLE ->
                        if (yandexLookup) binding.btnFindYandex.text = getString(R.string.yandex_find)
                        else binding.btnFindIsbn.text = getString(R.string.isbn_find)
                }
            }
        }
    }

    private fun doIsbnLookup() {
        yandexLookup = false
        viewModel.lookupIsbn(binding.editIsbn.text.toString())
    }

    /** Looks up a Yandex Books share link and pre-fills title/author/publisher. */
    private fun doYandexLookup() {
        yandexLookup = true
        viewModel.lookupYandex(binding.editYandex.text.toString())
    }

    /** Launches the camera barcode scanner; applies the scanned ISBN + metadata. */
    private fun startBarcodeScan() {
        barcodeLauncher.launch(Intent(this, BarcodeScanActivity::class.java))
    }

    private val barcodeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val isbn = data.getStringExtra(BarcodeScanActivity.EXTRA_ISBN).orEmpty()
            binding.editIsbn.setText(isbn)
            if (isbn.isNotBlank()) {
                binding.editTitle.setText(data.getStringExtra(BarcodeScanActivity.EXTRA_TITLE).orEmpty())
                binding.editAuthor.setText(data.getStringExtra(BarcodeScanActivity.EXTRA_AUTHOR).orEmpty())
                binding.editPublisher.setText(data.getStringExtra(BarcodeScanActivity.EXTRA_PUBLISHER).orEmpty())
                val pages = data.getIntExtra(BarcodeScanActivity.EXTRA_PAGES, 0)
                if (pages > 0) binding.editTotalPages.setText(pages.toString())
                val cover = data.getStringExtra(BarcodeScanActivity.EXTRA_COVER).orEmpty()
                if (cover.isNotBlank()) {
                    pendingCover = cover
                }
            }
        }
    }

    /** Fills the form from a successful Google Books lookup. */
    private fun applyLookup(r: IsbnLookupResult) {
        val b = binding
        if (b.editTitle.text.isNullOrBlank()) b.editTitle.setText(r.title)
        if (b.editAuthor.text.isNullOrBlank()) b.editAuthor.setText(r.author)
        if (b.editPublisher.text.isNullOrBlank()) b.editPublisher.setText(r.publisher)
        if (r.totalPages > 0 && b.editTotalPages.text.isNullOrBlank()) {
            b.editTotalPages.setText(r.totalPages.toString())
        }
        if (r.coverUrl.isNotBlank()) {
            pendingCover = r.coverUrl
            binding.imgCoverPreview.visibility = android.view.View.VISIBLE
            Glide.with(this).load(r.coverUrl).into(binding.imgCoverPreview)
        }
    }

    private fun populate(book: Book) {
        // The delete action only makes sense for an already-saved book.
        binding.btnDeleteBook.visibility =
            if (book.id > 0L) android.view.View.VISIBLE else android.view.View.GONE
        binding.editTitle.setText(book.title)
        binding.editAuthor.setText(book.author)
        binding.editPublisher.setText(book.publisher)
        binding.editIsbn.setText(book.isbn)
        binding.editTotalPages.setText(if (book.totalPages > 0) book.totalPages.toString() else "")
        binding.editCurrentPage.setText(if (book.currentPage > 0) book.currentPage.toString() else "")
        binding.formatGroup.check(
            if (book.format == BookFormat.ELECTRONIC) binding.formatElectronic.id
            else binding.formatPaper.id
        )
        pendingCover = book.coverUrl
        if (book.coverUrl.isNotBlank()) {
            binding.imgCoverPreview.visibility = android.view.View.VISIBLE
            Glide.with(this).load(book.coverUrl).into(binding.imgCoverPreview)
        } else {
            binding.imgCoverPreview.visibility = android.view.View.GONE
        }
    }

    private fun onSave() {
        if (binding.editTitle.text.isNullOrBlank()) {
            binding.editTitle.requestFocus()
            val msg = getString(R.string.editor_title_required)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        val format = if (binding.formatElectronic.isChecked) BookFormat.ELECTRONIC else BookFormat.PAPER
        viewModel.save(
            title = binding.editTitle.text.toString(),
            author = binding.editAuthor.text.toString(),
            publisher = binding.editPublisher.text.toString(),
            isbn = binding.editIsbn.text.toString(),
            totalPages = binding.editTotalPages.text.toString().toIntOrNull() ?: 0,
            currentPage = binding.editCurrentPage.text.toString().toIntOrNull() ?: 0,
            format = format,
            coverUrl = pendingCover,
            onDone = { finish() }
        )
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_book)
            .setMessage(R.string.delete_book_confirm)
            .setPositiveButton(R.string.delete_book) { _, _ ->
                viewModel.delete(onDone = { finish() })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
