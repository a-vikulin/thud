package io.github.avikulin.thud.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a saved workout definition.
 */
@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val description: String? = null,

    // Metadata
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // Cached summary (updated on save)
    val estimatedDurationSeconds: Int? = null,
    val estimatedDistanceMeters: Int? = null,
    val stepCount: Int = 0,
    val estimatedTss: Int? = null,

    // System workout type: null=regular, "WARMUP"=warmup template, "COOLDOWN"=cooldown template
    val systemWorkoutType: String? = null,

    // Whether to attach default warmup/cooldown templates at execution time
    val useDefaultWarmup: Boolean = false,
    val useDefaultCooldown: Boolean = false,

    // Timestamp of last execution (for list ordering)
    val lastExecutedAt: Long? = null
) {
    /** Whether this is a system workout (warmup/cooldown template). */
    val isSystemWorkout: Boolean get() = systemWorkoutType != null
}
