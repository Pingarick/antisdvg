package com.antisdvg.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.antisdvg.R
import com.antisdvg.data.model.Book
import com.antisdvg.databinding.FragmentLibraryBinding
import com.antisdvg.ui.AppViewModelFactory
import com.antisdvg.ui.activities.BookDetailActivity
import com.antisdvg.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

/** Library tab: the reader's books with progress, plus an add-book action. */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LibraryViewModel by viewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private val adapter = BookAdapter(
        onClick = { book -> openBook(book) },
        onEdit = { book -> openBookEditor(book) }
    )

    /** Storage Access Framework picker for importing FB2/TXT into the reader. */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importBook(it) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.libraryRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.libraryRecycler.adapter = adapter

        binding.fabAddBook.setOnClickListener { openBookEditor(null) }
        binding.emptyAddBtn.setOnClickListener { openBookEditor(null) }
        binding.importBookBtn.setOnClickListener {
            importLauncher.launch(arrayOf("application/octet-stream", "text/plain", "text/xml"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.books.collect { books ->
                adapter.submitList(books)
                binding.emptyState.visibility =
                    if (books.isEmpty()) View.VISIBLE else View.GONE
                binding.librarySubtitle.text = pluralBooks(books.size)
            }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.importEvents.collect { event -> handleImport(event) }
            }
        }
    }

    private fun handleImport(event: ImportEvent) {
        when (event) {
            is ImportEvent.Imported -> {
                toast(getString(R.string.reader_import_done, event.title))
                openReader(event.bookId)
            }
            ImportEvent.Unsupported -> toast(getString(R.string.reader_import_unsupported))
            ImportEvent.Failed -> toast(getString(R.string.reader_import_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openBook(book: Book) {
        // Imported e-books open in the built-in reader; paper books keep the
        // classic log-and-verify flow.
        if (book.filePath.isNotBlank()) {
            openReader(book.id)
            return
        }
        findNavController().navigate(
            R.id.nav_reading,
            Bundle().apply { putLong("bookId", book.id) }
        )
    }

    private fun openReader(bookId: Long) {
        startActivity(
            Intent(requireContext(), ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
        )
    }

    private fun openBookEditor(book: Book?) {
        startActivity(
            Intent(requireContext(), BookDetailActivity::class.java)
                .putExtra(BookDetailActivity.EXTRA_BOOK_ID, book?.id ?: -1L)
        )
    }

    private fun pluralBooks(count: Int): String =
        if (count == 0) "" else resources.getQuantityString(R.plurals.book_count, count, count)
}
