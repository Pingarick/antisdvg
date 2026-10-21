package com.antisdvg.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.antisdvg.R
import com.antisdvg.data.model.BlockedApp
import com.antisdvg.databinding.ItemBlockedAppBinding

/** Renders a single blocked app row with a remove action in the settings tab. */
class BlockedAppAdapter(
    private val onRemove: (BlockedApp) -> Unit
) : ListAdapter<BlockedApp, BlockedAppAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBlockedAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemBlockedAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: BlockedApp) {
            val label = app.label.ifBlank { app.packageName }
            binding.blockedAppLabel.text = label
            // The package line is noise when it is the only thing we know.
            binding.blockedAppPackage.text = app.packageName
            binding.blockedAppPackage.visibility =
                if (app.packageName == label) android.view.View.GONE else android.view.View.VISIBLE
            binding.btnRemoveBlockedApp.contentDescription =
                binding.root.context.getString(R.string.delete_blocked_app) + ": " + label
            val icon = runCatching {
                binding.root.context.packageManager.getApplicationIcon(app.packageName)
            }.getOrNull()
            if (icon != null) {
                binding.blockedAppIcon.setImageDrawable(icon)
            } else {
                binding.blockedAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            binding.btnRemoveBlockedApp.setOnClickListener { onRemove(app) }
        }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<BlockedApp>() {
            override fun areItemsTheSame(oldItem: BlockedApp, newItem: BlockedApp) =
                oldItem.packageName == newItem.packageName
            override fun areContentsTheSame(oldItem: BlockedApp, newItem: BlockedApp) =
                oldItem == newItem
        }
    }
}
