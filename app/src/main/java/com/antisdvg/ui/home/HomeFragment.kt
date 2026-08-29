package com.antisdvg.ui.home

import android.content.res.Resources
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.antisdvg.R
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.progressPercent
import com.antisdvg.databinding.FragmentHomeBinding
import com.antisdvg.ui.AppViewModelFactory
import com.bumptech.glide.Glide
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/** Home dashboard: greeting, streak, continue-reading, today's stats, wallet, week chart. */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        AppViewModelFactory(requireActivity().application)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)
        binding.homeGreeting.text = greeting()

        binding.homeGoLibraryBtn.setOnClickListener {
            findNavController().navigate(R.id.nav_library)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                renderStats(state)
                renderActiveBook(state.activeBook, state.hasBooks)
            }
        }
    }

    private fun renderStats(state: HomeUiState) {
        binding.homeStreakCount.text = state.stats.currentStreak.toString()
        binding.homeTodayMinutes.text = state.todayMinutes.toString()
        binding.homeTodayPages.text = state.todayPages.toString()
        binding.homeWalletMinutes.text = state.wallet.minutesRemaining.toString()
        renderWeek(state.week.map { it.minutes })
    }

    private var continueCardJustShown = false

    private fun renderActiveBook(book: Book?, hasBooks: Boolean) {
        val showEmpty = !hasBooks
        binding.homeEmptyState.isVisible = showEmpty
        binding.homeContinueCard.isVisible = !showEmpty

        // Subtle entrance animation only on transition from empty → active state.
        if (!showEmpty && !continueCardJustShown) {
            continueCardJustShown = true
            binding.homeContinueCard.apply {
                alpha = 0f
                translationY = resources.displayMetrics.density * 16f
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(260L)
                    .start()
            }
        }
        if (showEmpty) continueCardJustShown = false


        if (book == null) {
            binding.homeBookTitle.text = getString(R.string.home_no_active)
            binding.homeBookAuthor.text = ""
            binding.homeBookPages.text = ""
            binding.homeProgressLabel.text = ""
            binding.homeProgressBar.progress = 0
            binding.homeCoverEmoji.text = "📚"
            binding.homeCoverImage.visibility = View.GONE
            return
        }

        binding.homeBookTitle.text = book.title.ifBlank { getString(R.string.home_no_active) }
        binding.homeBookAuthor.text = book.author
        binding.homeBookPages.text = getString(
            R.string.home_page_progress,
            book.currentPage,
            book.totalPages
        )
        binding.homeProgressLabel.text = getString(R.string.home_progress, book.progressPercent)
        binding.homeProgressBar.progress = book.progressPercent
        binding.homeCoverEmoji.text = book.coverEmoji.takeIf { it.isNotBlank() } ?: "📖"
        if (book.coverUrl.isNotBlank()) {
            binding.homeCoverImage.visibility = View.VISIBLE
            Glide.with(this).load(book.coverUrl).into(binding.homeCoverImage)
        } else {
            binding.homeCoverImage.visibility = View.GONE
            binding.homeCoverImage.setImageDrawable(null)
        }
    }

    private val weekLabels = listOf("П", "В", "С", "Ч", "П", "С", "В")

    private fun renderWeek(minutes: List<Int>) {
        val container = binding.homeWeekChart
        container.removeAllViews()
        if (minutes.isEmpty()) return

        val max = (minutes.maxOrNull() ?: 1).coerceAtLeast(1)
        // Выравниваем неделю вправо: если данных меньше 7 дней — заполняем начало пустотами.
        val offset = (weekLabels.size - minutes.size).coerceAtLeast(0)

        minutes.forEachIndexed { index, value ->
            val dayLabel = weekLabels[offset + index]
            val item = View.inflate(requireContext(), R.layout.item_home_week_day, null)
            val bar = item.findViewById<View>(R.id.home_bar)
            val label = item.findViewById<TextView>(R.id.home_day_label)
            label.text = dayLabel

            val minBar = 4.dpToPx()
            val maxBar = 56.dpToPx()
            val height = if (value <= 0) minBar else (minBar + (maxBar - minBar) * value / max).toInt()
            item.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            bar.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height,
            ).apply { gravity = Gravity.BOTTOM }

            container.addView(item)
        }
    }

    private fun Int.dpToPx(): Int =
        (this * Resources.getSystem().displayMetrics.density).toInt()

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> getString(R.string.home_greeting_morning)
        in 12..17 -> getString(R.string.home_greeting_day)
        in 18..22 -> getString(R.string.home_greeting_evening)
        else -> getString(R.string.home_greeting_night)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
