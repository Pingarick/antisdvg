package com.antisdvg.ui.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antisdvg.R
import com.antisdvg.databinding.FragmentFocusBinding
import com.antisdvg.ui.AppViewModelFactory
import kotlinx.coroutines.launch

/** Focus tab: pomodoro-style 25/5 timer that earns entertainment minutes. */
class FocusFragment : Fragment(R.layout.fragment_focus) {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FocusViewModel by viewModels {
        AppViewModelFactory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnFocusStartPause.setOnClickListener {
            if (viewModel.state.value.running) viewModel.pause() else viewModel.start()
        }
        binding.btnFocusStop.setOnClickListener { viewModel.stop() }
        binding.btnFocusSkipBreak.setOnClickListener { viewModel.skipBreak() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: FocusUiState) {
        binding.focusTimerText.text = viewModel.formattedTime()
        binding.focusCyclesText.text = getString(R.string.focus_cycles, state.cyclesDone)
        binding.focusPhaseLabel.text = getString(
            when (state.phase) {
                FocusPhase.FOCUS -> R.string.focus_phase_focus
                FocusPhase.BREAK -> R.string.focus_phase_break
                FocusPhase.DONE -> R.string.focus_phase_done
                FocusPhase.IDLE -> R.string.focus_phase_idle
            }
        )
        binding.btnFocusStartPause.text = getString(
            if (state.running) R.string.focus_pause else R.string.focus_start
        )
        binding.btnFocusSkipBreak.isVisible = state.phase == FocusPhase.BREAK
        binding.focusEarnedText.isVisible = state.lastEarned > 0 && state.phase == FocusPhase.BREAK
        if (state.lastEarned > 0) {
            binding.focusEarnedText.text = getString(R.string.focus_earned, state.lastEarned)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
