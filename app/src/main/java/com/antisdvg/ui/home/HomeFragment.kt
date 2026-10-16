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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

        binding.homeContinueCard.setOnClickListener {
            val book = viewModel.state.value.activeBook
            if (book != null) {
                findNavController().navigate(
                    R.id.nav_reading,
                    Bundle().apply { putLong("bookId", book.id) }
                )
            }
        }

        binding.homeStartFocusBtn.setOnClickListener {
            findNavController().navigate(R.id.nav_focus)
        }

        binding.homeGoalDepositBtn.setOnClickListener {
            val state = viewModel.state.value
            when {
                state.settings.goalTargetMin <= 0 -> showGoalDialog()
                state.wallet.minutesRemaining < 10 -> android.widget.Toast.makeText(
                    requireContext(), R.string.home_goal_need_more, android.widget.Toast.LENGTH_SHORT
                ).show()
                else -> viewModel.depositGoal()
            }
        }
        binding.homeGoalEditBtn.setOnClickListener {
            showGoalDialog()
        }
        binding.homeGoalBreakBtn.setOnClickListener {
            viewModel.breakGoal()
            android.widget.Toast.makeText(
                requireContext(), R.string.home_goal_released_simple, android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.state.collectLatest { state ->
                renderStats(state)
                renderActiveBook(state.activeBook, state.hasBooks)
            }
            }
        }
    }

    private fun renderStats(state: HomeUiState) {
        binding.homeStreakCount.text = state.stats.currentStreak.toString()
        binding.homeTodayMinutes.text = state.todayMinutes.toString()
        binding.homeTodayPages.text = state.todayPages.toString()
        binding.homeWalletMinutes.text = state.wallet.minutesRemaining.toString()
        renderWeek(state.week)
        renderGoal(state)
    }

    private fun renderGoal(state: HomeUiState) {
        val settings = state.settings
        val hasGoal = settings.goalTargetMin > 0
        binding.homeGoalTitle.text = if (hasGoal && settings.goalTitle.isNotBlank()) {
            settings.goalTitle
        } else {
            getString(R.string.home_goal_empty)
        }
        val saved = settings.goalSavedMin.coerceAtLeast(0)
        val target = settings.goalTargetMin.coerceAtLeast(0)
        binding.homeGoalProgressLabel.text = if (hasGoal) {
            getString(R.string.home_goal_progress, saved, target)
        } else {
            getString(R.string.home_goal_empty_hint)
        }
        binding.homeGoalBar.max = 100
        binding.homeGoalBar.progress = if (hasGoal && target > 0) {
            (saved * 100 / target).coerceIn(0, 100)
        } else 0
        // Deposit button always responds: explains itself instead of being silently disabled.
        binding.homeGoalDepositBtn.isEnabled = true
        binding.homeGoalBreakBtn.isVisible = hasGoal && saved > 0
        if (hasGoal && target > 0 && saved >= target) {
            binding.homeGoalProgressLabel.text = getString(R.string.home_goal_done)
        }
    }

    private fun showGoalDialog() {
        val context = requireContext()
        val current = viewModel.state.value.settings
        val titleInput = android.widget.EditText(context).apply {
            hint = getString(R.string.home_goal_title_hint)
            setText(current.goalTitle)
            setSingleLine()
        }
        val targetInput = android.widget.EditText(context).apply {
            hint = getString(R.string.home_goal_target_hint)
            setText(if (current.goalTargetMin > 0) current.goalTargetMin.toString() else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(titleInput)
            addView(targetInput)
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.home_goal_edit)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val target = targetInput.text.toString().toIntOrNull() ?: 0
                viewModel.saveGoal(titleInput.text.toString(), target)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun renderWeek(days: List<com.antisdvg.data.model.DaySummary>) {
        val container = binding.homeWeekChart
        container.removeAllViews()
        if (days.isEmpty()) return

        val max = (days.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(1)

        days.forEach { day ->
            val value = day.minutes
            val dayLabel = day.label ?: ""
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
        (this * resources.displayMetrics.density).toInt()

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
