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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        binding.settingsVersion.text = com.antisdvg.BuildConfig.VERSION_NAME
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
            binding.inputDailyLimitEdit.text?.toString()?.toIntOrNull()?.let { limit ->
                viewModel.saveDailyLimit(limit)
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
                BlockerService.broadcastRefresh(requireContext())
            }
        }

        // The built-in key works out of the box, so replacing it is a rare chore:
        // keep the field folded away until it is actually needed.
        binding.btnApiAdvanced.setOnClickListener {
            val show = binding.inputApiKey.visibility != View.VISIBLE
            binding.inputApiKey.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnSaveApi.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnApiAdvanced.contentDescription =
                getString(if (show) R.string.api_key_hide else R.string.api_key_show)
            if (show) binding.inputApiKeyEdit.requestFocus()
        }

        binding.btnPickApp.setOnClickListener {
            showInstalledAppPicker()
        }

        binding.strictnessGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.saveVerificationStrictness(
                when (checkedId) {
                    R.id.strictness_lenient -> 0
                    R.id.strictness_strict -> 2
                    else -> 1
                }
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun showInstalledAppPicker() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val pm = context.packageManager
            val currentBlocked = viewModel.state.value.blockedApps.map { it.packageName }.toSet()
            val ownPackage = context.packageName

            val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                resolveInfos.mapNotNull { ri ->
                    val pkg = ri.activityInfo.packageName
                    if (pkg == ownPackage || pkg in currentBlocked) return@mapNotNull null
                    val label = ri.loadLabel(pm).toString()
                    val icon = ri.loadIcon(pm)
                    AppItem(label = label, packageName = pkg, icon = icon)
                }.sortedBy { it.label.lowercase() }
            }

            if (!isAdded) return@launch
            if (items.isEmpty()) {
                Toast.makeText(context, "Все найденные приложения уже в списке", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val adapter = object : android.widget.ArrayAdapter<AppItem>(
                context,
                android.R.layout.activity_list_item,
                android.R.id.text1,
                items
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val view = super.getView(position, convertView, parent)
                    val item = getItem(position) ?: return view
                    val text = view.findViewById<android.widget.TextView>(android.R.id.text1)
                    val icon = view.findViewById<android.widget.ImageView>(android.R.id.icon)
                    text.text = "${item.label}\n(${item.packageName})"
                    text.maxLines = 2
                    icon.setImageDrawable(item.icon)
                    return view
                }
            }

            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.pick_app_title)
                .setAdapter(adapter) { _, which ->
                    val chosen = items[which]
                    viewModel.addBlockedApp(packageName = chosen.packageName, label = chosen.label)
                    BlockerService.broadcastRefresh(context)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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

        if (!binding.inputMinutesPerPageEdit.hasFocus()) {
            binding.inputMinutesPerPageEdit.setText(settings.minutesPerPage.toString())
        }
        if (!binding.inputEmergencyMinutesEdit.hasFocus()) {
            binding.inputEmergencyMinutesEdit.setText(settings.emergencyMinutes.toString())
        }
        if (!binding.inputDailyLimitEdit.hasFocus()) {
            binding.inputDailyLimitEdit.setText(settings.dailyLimitMin.toString())
        }
        if (!binding.inputApiKeyEdit.hasFocus()) {
            binding.inputApiKeyEdit.setText(settings.apiKey)
        }

        val strictButton = when (settings.verificationStrictness) {
            0 -> R.id.strictness_lenient
            2 -> R.id.strictness_strict
            else -> R.id.strictness_normal
        }
        if (binding.strictnessGroup.checkedRadioButtonId != strictButton) {
            binding.strictnessGroup.check(strictButton)
        }

        val empty = state.blockedApps.isEmpty()
        binding.blockedListEmpty.visibility =
            if (empty) View.VISIBLE else View.GONE
        blockedAppAdapter.submitList(state.blockedApps)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class AppItem(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable
    )
}
