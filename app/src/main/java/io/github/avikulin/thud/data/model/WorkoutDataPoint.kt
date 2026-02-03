package io.github.avikulin.thud.data.model

/**
 * Data point for workout recording (for GPX/charting)
 */
data class WorkoutDataPoint(
    val timestampMs: Long,
    val elapsedMs: Long,
    val speedKph: Double,
    val inclinePercent: Double,
    val heartRateBpm: Double,
    val distanceKm: Double,
    val elevationGainM: Double,
    val caloriesKcal: Double = 0.0,  // Cumulative calories burned
    // Stryd foot pod metrics
    val powerWatts: Double = 0.0,           // Total adjusted power (raw + incline)
    val rawPowerWatts: Double = 0.0,        // Raw power from Stryd
    val inclinePowerWatts: Double = 0.0,    // Incline power contribution (for calibration)
    val cadenceSpm: Int = 0,
    // Workout step tracking for FIT lap export
    val stepIndex: Int = -1,
    val stepName: String = ""
)
