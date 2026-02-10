package io.github.avikulin.thud.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.R
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.repository.WorkoutRepository
import io.github.avikulin.thud.util.PaceConverter

/**
 * Adapter for the workout list in the master-detail editor.
 * Shows workout name with stats subtitle (Steps, Distance, Duration, TSS).
 */
class WorkoutListAdapter(
    private val onWorkoutClick: (Long) -> Unit,
    private val onCopyClick: (Long) -> Unit,
    private val onDeleteClick: (Long) -> Unit
) : ListAdapter<Workout, WorkoutListAdapter.WorkoutViewHolder>(WorkoutDiffCallback()) {

    private var selectedWorkoutId: Long = 0L

    fun setSelectedWorkoutId(id: Long) {
        val oldSelected = selectedWorkoutId
        selectedWorkoutId = id

        // Notify only the old and new selected items to update their backgrounds
        val oldIndex = currentList.indexOfFirst { it.id == oldSelected }
        val newIndex = currentList.indexOfFirst { it.id == id }
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkoutViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workout_list, parent, false)
        return WorkoutViewHolder(view)
    }

    override fun onBindViewHolder(holder: WorkoutViewHolder, position: Int) {
        val workout = getItem(position)
        holder.bind(workout, workout.id == selectedWorkoutId)
    }

    inner class WorkoutViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val borderStrip: View = itemView.findViewById(R.id.borderStrip)
        private val tvName: TextView = itemView.findViewById(R.id.tvWorkoutName)
        private val tvStats: TextView = itemView.findViewById(R.id.tvStats)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopy)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(workout: Workout, isSelected: Boolean) {
            tvName.text = workout.name

            // Build stats subtitle using shared formatter
            tvStats.text = PaceConverter.formatWorkoutStats(
                stepCount = workout.stepCount,
                distanceMeters = workout.estimatedDistanceMeters,
                durationSeconds = workout.estimatedDurationSeconds,
                tss = workout.estimatedTss
            )

            // System workout visual treatment
            if (workout.isSystemWorkout) {
                val borderColor = when (workout.systemWorkoutType) {
                    WorkoutRepository.SYSTEM_TYPE_WARMUP -> R.color.sentinel_warmup_border
                    WorkoutRepository.SYSTEM_TYPE_COOLDOWN -> R.color.sentinel_cooldown_border
                    else -> R.color.sentinel_warmup_border
                }
                borderStrip.setBackgroundColor(ContextCompat.getColor(itemView.context, borderColor))
                borderStrip.visibility = View.VISIBLE
                btnCopy.visibility = View.GONE
                btnDelete.visibility = View.GONE
            } else {
                borderStrip.visibility = View.GONE
                btnCopy.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
            }

            // Selection highlight
            if (isSelected) {
                itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.workout_list_item_selected)
                )
            } else {
                itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.workout_list_background)
                )
            }

            // Click handlers
            itemView.setOnClickListener { onWorkoutClick(workout.id) }
            btnCopy.setOnClickListener { onCopyClick(workout.id) }
            btnDelete.setOnClickListener { onDeleteClick(workout.id) }
        }
    }

    class WorkoutDiffCallback : DiffUtil.ItemCallback<Workout>() {
        override fun areItemsTheSame(oldItem: Workout, newItem: Workout): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Workout, newItem: Workout): Boolean {
            return oldItem == newItem
        }
    }
}
