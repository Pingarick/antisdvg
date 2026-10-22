package com.antisdvg.ui.catalog

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.antisdvg.R
import com.antisdvg.data.remote.CatalogSource
import com.antisdvg.databinding.FragmentCatalogBinding
import com.antisdvg.ui.AppViewModelFactory
import com.antisdvg.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

/** The free catalogue: search Project Gutenberg and put books on the shelf. */
class CatalogFragment : Fragment(R.layout.fragment_catalog) {

    private var _binding: FragmentCatalogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CatalogViewModel by viewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private val adapter = CatalogAdapter { item -> onItemAction(item) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCatalogBinding.bind(view)

        binding.catalogBack.setOnClickListener { findNavController().navigateUp() }
        binding.catalogList.layoutManager = LinearLayoutManager(requireContext())
        binding.catalogList.adapter = adapter

        binding.catalogSearch.doAfterTextChanged { text ->
            viewModel.onQueryChanged(text?.toString().orEmpty())
        }

        binding.catalogSourceWs.setOnClickListener {
            viewModel.setSource(CatalogSource.WIKISOURCE)
        }
        binding.catalogSourceGb.setOnClickListener {
            viewModel.setSource(CatalogSource.GUTENBERG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: CatalogViewModel.UiState) {
        adapter.submitList(state.items)
        binding.catalogSourceWs.isChecked = state.source == CatalogSource.WIKISOURCE
        binding.catalogSourceGb.isChecked = state.source == CatalogSource.GUTENBERG
        binding.catalogStatus.isVisible = state.error || state.loading || state.items.isEmpty()
        binding.catalogStatus.text = when {
            state.error -> getString(R.string.catalog_error)
            state.loading -> getString(R.string.catalog_loading)
            state.items.isEmpty() && state.query.isBlank() -> getString(R.string.catalog_type_to_search)
            else -> getString(R.string.catalog_empty)
        }
    }

    private fun onItemAction(item: CatalogViewModel.Item) {
        when (item.state) {
            CatalogViewModel.DownloadState.DONE -> {
                startActivity(
                    Intent(requireContext(), ReaderActivity::class.java)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, item.bookId)
                )
            }
            else -> viewModel.download(item.book)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
