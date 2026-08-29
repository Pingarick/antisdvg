package com.antisdvg.ui.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.antisdvg.data.model.Achievement
import com.antisdvg.databinding.ItemAchievementBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a single unlocked [Achievement] row in the stats tab. */
class AchievementAdapter : ListAdapter<Achievement, AchievementAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAchievementBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemAchievementBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun bind(achievement: Achievement) {
            binding.achievementTitle.text = achievement.title
            binding.achievementDate.text = dateFormat.format(Date(achievement.unlockedAt))
        }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<Achievement>() {
            override fun areItemsTheSame(oldItem: Achievement, newItem: Achievement) =
                oldItem.type == newItem.type
            override fun areContentsTheSame(oldItem: Achievement, newItem: Achievement) =
                oldItem == newItem
        }
    }
}
