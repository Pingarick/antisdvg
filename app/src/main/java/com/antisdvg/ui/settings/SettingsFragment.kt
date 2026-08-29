package com.antisdvg.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.provider.Settings.canDrawOverlays
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.antisdvg.R
import com.antisdvg.databinding.FragmentSettingsBinding
import com.antisdvg.service.BlockerService
import com.antisdvg.ui.AppViewModelFactory
import kotlinx.coroutines.launch

/** Settings tab: blocker toggle, reading rewards, AI key, blocked-apps list. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private lateinit var blockedAppAdapter: BlockedAppAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        blockedAppAdapter = BlockedAppAdapter { app ->
            viewModel.removeBlockedApp(app.packageName)
        }
        binding.blockedAppsList.layoutManager = LinearLayoutManager(requireContext())
        binding.blockedAppsList.adapter = blockedAppAdapter

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        binding.btnOpenOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
        }

        binding.btnSaveRewards.setOnClickListener {
            val perPage = binding.inputMinutesPerPageEdit.text.toString()
                .toIntOrNull() ?: 0
            val emergency = binding.inputEmergencyMinutesEdit.text.toString()
                .toIntOrNull() ?: 0
            if (perPage <= 0 && emergency <= 0) {
                // Leave the fields as-is rather than clobbering valid values.
                Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.saveRewards(perPage, emergency)
            }
        }

        binding.btnSaveApi.setOnClickListener {
            val key = binding.inputApiKeyEdit.text?.toString() ?: ""
            if (key.isNotBlank()) {
                viewModel.saveApiKey(key)
                Toast.makeText(requireContext(), R.string.api_saved_toast, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddBlockedApp.setOnClickListener {
            val pkg = binding.inputBlockedAppEdit.text?.toString()?.trim().orEmpty()
            if (pkg.isNotEmpty()) {
                viewModel.addBlockedApp(packageName = pkg, label = pkg)
                binding.inputBlockedAppEdit.setText("")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state -> render(state) }
        }
    }

    private fun render(state: SettingsUiState) {
        val settings = state.settings
        // Avoid re-triggering the change listener while syncing from state.
        binding.blockerEnabled.setOnCheckedChangeListener(null)
        binding.blockerEnabled.isChecked = settings.blockerEnabled
        binding.blockerEnabled.setOnCheckedChangeListener { _, checked ->
            viewModel.setBlockerEnabled(checked)
            BlockerService.broadcastRefresh(requireContext())
        }

        binding.blockerStatus.text = getString(
            if (settings.blockerEnabled) R.string.blocker_status_on
            else R.string.blocker_status_off
        )

        // Show the overlay-permission button only while permission is missing.
        binding.btnOpenOverlay.visibility =
            if (canDrawOverlays(requireContext())) View.GONE else View.VISIBLE

        binding.inputMinutesPerPageEdit.setText(settings.minutesPerPage.toString())
        binding.inputEmergencyMinutesEdit.setText(settings.emergencyMinutes.toString())
        binding.inputApiKeyEdit.setText(settings.apiKey)

        val empty = state.blockedApps.isEmpty()
        binding.blockedListEmpty.visibility =
            if (empty) View.VISIBLE else View.GONE
        blockedAppAdapter.submitList(state.blockedApps)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
