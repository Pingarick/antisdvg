package com.antisdvg.ui.reading

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.antisdvg.R
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.progressPercent
import com.antisdvg.databinding.FragmentReadingBinding
import com.antisdvg.ui.AppViewModelFactory
import kotlinx.coroutines.launch

/** Reading flow tab: confirm the pages read, retell, and get AI-verified. */
class ReadingFlowFragment : Fragment(R.layout.fragment_reading) {

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReadingViewModel by viewModels {
        AppViewModelFactory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val bookId = arguments?.getLong("bookId", -1L) ?: -1L
        binding.btnCheckProgress.setOnClickListener {
            viewModel.verify(
                startPageInput = binding.inputReadingStartEdit.text?.toString().orEmpty(),
                endPageInput = binding.inputReadingEndEdit.text?.toString().orEmpty(),
                userText = binding.inputRetellingEdit.text?.toString().orEmpty()
            )
        }
        binding.btnUseEmergency.setOnClickListener { onUseEmergencyClicked() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                render(state)
            }
        }

        viewModel.load(bookId)
    }

    private fun render(state: ReadingUiState) {
        renderBook(state.book)
        binding.readingTokenCount.text = getString(
            R.string.stats_token_count,
            state.tokenCount
        )
        renderVerify(state.verify)
    }

    private fun renderBook(book: Book?) {
        if (book == null) {
            binding.readingBookCard.visibility = View.GONE
            binding.btnCheckProgress.isEnabled = false
            return
        }
        binding.readingBookCard.visibility = View.VISIBLE
        binding.readingBookTitle.text = book.title
        binding.readingBookAuthor.text = book.author
        binding.readingBookProgress.text = getString(
            R.string.book_read_progress_pages,
            book.currentPage,
            book.totalPages,
            book.progressPercent
        )
        binding.btnCheckProgress.isEnabled = true
    }

    private fun renderVerify(verify: VerifyState) {
        val card = binding.readingResultCard
        card.visibility = View.VISIBLE
        when (verify) {
            VerifyState.Idle -> card.visibility = View.GONE
            VerifyState.Loading -> {
                binding.readingResultIcon.text = "…"
                binding.readingResultText.text = getString(R.string.verify_in_progress)
                binding.readingResultMinutes.visibility = View.GONE
            }
            is VerifyState.Success -> {
                binding.readingResultIcon.text = "✓"
                binding.readingResultText.text = getString(R.string.verified_success)
                binding.readingResultMinutes.text = getString(
                    R.string.earned_minutes,
                    verify.earnedMinutes
                )
                binding.readingResultMinutes.visibility = View.VISIBLE
                // Reward reveal: subtle fade + upward motion for the premium feel.
                card.alpha = 0f
                card.translationY = resources.displayMetrics.density * 24f
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(240L)
                    .start()
            }
            is VerifyState.Failure -> {
                binding.readingResultIcon.text = "✗"
                binding.readingResultText.text = verify.feedback
                binding.readingResultMinutes.visibility = View.GONE
            }
            VerifyState.NoKey -> {
                binding.readingResultIcon.text = "🔑"
                binding.readingResultText.text = getString(R.string.reader_error_key)
                binding.readingResultMinutes.visibility = View.GONE
            }
            VerifyState.NoNetwork -> {
                binding.readingResultIcon.text = "⚠"
                binding.readingResultText.text = getString(R.string.reader_error_network)
                binding.readingResultMinutes.visibility = View.GONE
            }
        }
    }

    private fun onUseEmergencyClicked() {
        if (viewModel.state.value.tokenCount <= 0) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.use_token_title)
                .setMessage(R.string.no_tokens)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.use_token_title)
            .setMessage(R.string.use_token_message)
            .setPositiveButton(R.string.use) { _, _ ->
                viewModel.useEmergencyToken()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
