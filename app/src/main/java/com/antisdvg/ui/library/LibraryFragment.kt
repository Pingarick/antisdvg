package com.antisdvg.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.antisdvg.R
import com.antisdvg.data.model.Book
import com.antisdvg.databinding.FragmentLibraryBinding
import com.antisdvg.ui.AppViewModelFactory
import com.antisdvg.ui.activities.BookDetailActivity
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.books.collect { books ->
                adapter.submitList(books)
                binding.emptyState.visibility =
                    if (books.isEmpty()) View.VISIBLE else View.GONE
                binding.librarySubtitle.text = pluralBooks(books.size)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openBook(book: Book) {
        findNavController().navigate(
            R.id.nav_reading,
            Bundle().apply { putLong("bookId", book.id) }
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
