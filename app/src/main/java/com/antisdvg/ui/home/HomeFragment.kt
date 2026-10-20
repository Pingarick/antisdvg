package com.antisdvg.ui.home

import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
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
import com.antisdvg.service.BlockerService
import com.antisdvg.service.BlockerWarning
import com.antisdvg.ui.AppViewModelFactory
import com.antisdvg.util.BlockerStatus
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

        binding.homeContinueCard.setOnClickListener { continueReading() }
        // The hero's own button is the front door: the card stays tappable, but
        // the action a person actually reaches for is the one made obvious.
        binding.homeReadBtn.setOnClickListener { continueReading() }

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
                renderBlockerWarning(state.settings.blockerEnabled)
            }
            }
        }
    }

    /**
     * Android 13+ keeps notifications switched off until the app asks, and an app
     * that cannot post them can go silent about its own failure — the exact thing
     * this screen exists to prevent. So the ask is made right where the reason is.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (view != null) renderBlockerWarning(viewModel.state.value.settings.blockerEnabled)
    }

    override fun onResume() {
        super.onResume()
        // The blocker can be switched off (or back on) from a system screen while
        // this app is in the background, and that is not in the database.
        renderBlockerWarning(viewModel.state.value.settings.blockerEnabled)
    }

    /**
     * The fence is down, and saying so is the most useful thing this screen can
     * do: the app is otherwise claiming to protect and doing nothing.
     */
    private fun renderBlockerWarning(enabledInSettings: Boolean) {
        val serviceOn = BlockerStatus.isServiceEnabled(requireContext(), BlockerService::class.java)
        val fenceDown = !enabledInSettings || !serviceOn
        binding.homeBlockerRow.isVisible = fenceDown

        // Notifications are the only way this app can speak up while it is closed,
        // and Android 13+ leaves them off until asked. Asking here means the person
        // can see, in the same breath, what they are being asked for and why.
        val notifOff = !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        binding.homeNotifRow.isVisible = notifOff
        binding.homeBlockerWarning.isVisible = fenceDown || notifOff

        // The strip is for the person looking at the screen; the notification is
        // for the moment they close the app and forget.
        BlockerWarning.sync(requireContext())

        binding.homeBlockerRow.setOnClickListener {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        binding.homeNotifRow.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE,
                            requireContext().packageName
                        )
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun renderStats(state: HomeUiState) {
        binding.homeStreakCount.text = state.stats.currentStreak.toString()
        binding.homeStreakLabel.text = resources.getQuantityString(
            R.plurals.home_streak_days,
            state.stats.currentStreak,
            state.stats.currentStreak
        )
        binding.homeTodayMinutes.text = state.todayMinutes.toString()
        binding.homeTodayPages.text = state.todayPages.toString()
        binding.homeWalletMinutes.text = state.wallet.minutesRemaining.toString()
        // What was spent today belongs next to what is left: one number without
        // the other says nothing about the day.
        val spentToday = state.stats.spentTodayMinutes
        binding.homeWalletSpent.text = if (spentToday > 0) {
            getString(R.string.home_wallet_spent, spentToday)
        } else {
            ""
        }
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
            binding.homeCoverLetter.text = "—"
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
        // No cover: the title's initial, set like a spine label, instead of an
        // emoji standing in for every book in the world.
        binding.homeCoverLetter.text = book.title.trim().firstOrNull()?.uppercase() ?: "—"
        if (book.coverUrl.isNotBlank()) {
            binding.homeCoverImage.visibility = View.VISIBLE
            Glide.with(this).load(book.coverUrl).into(binding.homeCoverImage)
        } else {
            binding.homeCoverImage.visibility = View.GONE
            binding.homeCoverImage.setImageDrawable(null)
        }
    }

    /** Straight into the book: the reader for an imported file, the prompt for paper. */
    private fun continueReading() {
        val book = viewModel.state.value.activeBook ?: return
        if (book.filePath.isNotBlank()) {
            // Electronic books continue in the built-in reader.
            startActivity(
                android.content.Intent(requireContext(), com.antisdvg.ui.reader.ReaderActivity::class.java)
                    .putExtra(com.antisdvg.ui.reader.ReaderActivity.EXTRA_BOOK_ID, book.id)
            )
        } else {
            findNavController().navigate(
                R.id.nav_reading,
                Bundle().apply { putLong("bookId", book.id) }
            )
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
