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
    val estimatedTss: Int? = null
)
