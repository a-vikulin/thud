package io.github.avikulin.thud.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.avikulin.thud.domain.model.AdjustmentType
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.EarlyEndCondition
import io.github.avikulin.thud.domain.model.StepType

/**
 * Represents a single step within a workout.
 */
@Entity(
    tableName = "workout_steps",
    foreignKeys = [ForeignKey(
        entity = Workout::class,
        parentColumns = ["id"],
        childColumns = ["workoutId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("workoutId")]
)
data class WorkoutStep(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val workoutId: Long,
    val orderIndex: Int,  // Position in workout (0-based)

    // Step type
    val type: StepType,  // WARMUP, RUN, RECOVER, REST, COOLDOWN, REPEAT

    // Duration configuration (used for planning/chart)
    val durationType: DurationType,  // TIME or DISTANCE
    val durationSeconds: Int? = null,
    val durationMeters: Int? = null,

    // Early end condition (optional, used during execution)
    val earlyEndCondition: EarlyEndCondition = EarlyEndCondition.NONE,

    // For HR_RANGE early end - step ends early when HR enters this range (% of LTHR)
    // Stored with 1 decimal precision for integer BPM snapping
    val hrEndTargetMinPercent: Double? = null,
    val hrEndTargetMaxPercent: Double? = null,

    // Pace target (stored as kph, displayed as min:sec /km in UI)
    // Required for all steps - treadmill always needs a speed setting
    val paceTargetKph: Double,

    // Pace progression end target (null = no progression, flat pace)
    // When set, pace gradually changes from paceTargetKph to paceEndTargetKph over the step duration
    val paceEndTargetKph: Double? = null,

    // Incline target (%) - required for all steps
    val inclineTargetPercent: Double,

    // Auto-adjustment mode (NONE, HR, or POWER)
    val autoAdjustMode: AutoAdjustMode = AutoAdjustMode.NONE,

    // What to adjust when metric is out of range (SPEED or INCLINE)
    val adjustmentType: AdjustmentType? = null,

    // HR targets as % of LTHR (Lactate Threshold HR)
    // Used when autoAdjustMode == HR
    // Stored with 1 decimal precision for integer BPM snapping
    val hrTargetMinPercent: Double? = null,
    val hrTargetMaxPercent: Double? = null,

    // Power targets as % of FTP (Functional Threshold Power)
    // Used when autoAdjustMode == POWER
    // Stored with 1 decimal precision for integer watt snapping
    val powerTargetMinPercent: Double? = null,
    val powerTargetMaxPercent: Double? = null,

    // For REPEAT type steps
    val repeatCount: Int? = null,

    // Parent repeat step ID (null if top-level step)
    val parentRepeatStepId: Long? = null
)
