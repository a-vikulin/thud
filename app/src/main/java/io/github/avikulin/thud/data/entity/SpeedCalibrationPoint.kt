package io.github.avikulin.thud.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single speed calibration data point pairing treadmill speed with Stryd foot pod speed.
 * Grouped by runId (run start timestamp) for per-run windowing.
 */
@Entity(
    tableName = "speed_calibration_points",
    indices = [Index("runId")]
)
data class SpeedCalibrationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,            // run start timestamp (groups points by run)
    val treadmillKph: Double,   // raw treadmill speed
    val strydKph: Double        // Stryd foot pod speed
)
