package com.antisdvg.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.antisdvg.R
import com.antisdvg.databinding.FragmentStatsBinding
import com.antisdvg.ui.AppViewModelFactory
import kotlinx.coroutines.launch

/** Stats tab: wallet minutes, emergency tokens, reading stats and achievements. */
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StatsViewModel by viewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private val achievementAdapter = AchievementAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.achievementsList.layoutManager = LinearLayoutManager(requireContext())
        binding.achievementsList.adapter = achievementAdapter

        // Spend an emergency token (+10 min) from the stats card; the minute and
        // token counters update automatically via the observed flows.
        binding.statsSpendToken.setOnClickListener {
            if (viewModel.state.value.tokens.count <= 0) {
                Toast.makeText(requireContext(), R.string.no_tokens, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.useEmergencyToken()
            }
        }

        // Reading-stats period: re-scopes pages/time/format to week, month or all time.
        binding.statsPeriodToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setPeriod(
                when (checkedId) {
                    R.id.stats_period_week -> StatsPeriod.WEEK
                    R.id.stats_period_month -> StatsPeriod.MONTH
                    else -> StatsPeriod.ALL
                }
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state -> render(state) }
        }
    }

    private fun render(state: StatsUiState) {
        // Live value from the fast prefs mirror: reflects real-time spending by
        // the BlockerService without waiting for a Room flush.
        binding.walletMinutes.text = state.liveRemainingMinutes.toString()
        binding.tokenCount.text =
            getString(R.string.stats_token_count, state.tokens.count)

        binding.todayMinutesValue.text = state.todayMinutes.toString()
        binding.todayPagesValue.text = state.todayPages.toString()
        binding.weekChart.setData(state.week)

        binding.statBooksValue.text = state.stats.totalBooksRead.toString()
        binding.statPagesValue.text = state.periodPages.toString()
        binding.statTimeValue.text = state.periodMinutes.toString()
        binding.statStreakValue.text = state.stats.currentStreak.toString()

        binding.formatPaperValue.text = state.paperPages.toString()
        binding.formatElectronicValue.text = state.electronicPages.toString()

        achievementAdapter.submitList(state.achievements)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
