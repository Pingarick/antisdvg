package com.antisdvg.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
            binding.blockedAppLabel.text = app.label.ifBlank { app.packageName }
            binding.blockedAppPackage.text = app.packageName
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
