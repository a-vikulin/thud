package io.github.avikulin.thud.util

/**
 * Converts between pace (min:sec per km) and speed (kph).
 * UI displays pace for runner convenience, database stores speed for calculations.
 *
 * Examples:
 *   5:00 /km = 12.0 kph
 *   6:00 /km = 10.0 kph
 *   7:30 /km = 8.0 kph
 */
object PaceConverter {

    /**
     * Convert speed (kph) to pace in total seconds per km.
     * @param speedKph Speed in kilometers per hour
     * @return Pace in seconds per kilometer
     */
    fun speedToPaceSeconds(speedKph: Double): Int {
        if (speedKph <= 0) return 0
        return (3600.0 / speedKph).toInt()  // 60 min * 60 sec / kph
    }

    /**
     * Convert pace (seconds per km) to speed (kph).
     * @param paceSeconds Pace in seconds per kilometer
     * @return Speed in kilometers per hour
     */
    fun paceSecondsToSpeed(paceSeconds: Int): Double {
        if (paceSeconds <= 0) return 0.0
        return 3600.0 / paceSeconds
    }

    /**
     * Format pace seconds as "M:SS" string.
     * @param paceSeconds Pace in seconds per kilometer
     * @return Formatted string like "5:30"
     */
    fun formatPace(paceSeconds: Int): String {
        val minutes = paceSeconds / 60
        val seconds = paceSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /**
     * Format speed (kph) as pace string "M:SS" without suffix.
     * @param speedKph Speed in kilometers per hour
     * @return Formatted string like "5:30" or "--:--" if speed is zero
     */
    fun formatPaceFromSpeed(speedKph: Double): String {
        if (speedKph <= 0) return "--:--"
        return formatPace(speedToPaceSeconds(speedKph))
    }

    /**
     * Format speed (kph) as pace string "M:SS /km" with suffix.
     * @param speedKph Speed in kilometers per hour
     * @return Formatted string like "5:30 /km"
     */
    fun formatPaceFromSpeedWithSuffix(speedKph: Double): String {
        if (speedKph <= 0) return "--:--"
        return "${formatPace(speedToPaceSeconds(speedKph))} /km"
    }

    /**
     * Parse "M:SS" string to pace seconds.
     * @param paceString String in format "M:SS" (e.g., "5:30")
     * @return Pace in seconds, or null if parsing fails
     */
    fun parsePace(paceString: String): Int? {
        val parts = paceString.trim().split(":")
        if (parts.size != 2) return null
        val minutes = parts[0].toIntOrNull() ?: return null
        val seconds = parts[1].toIntOrNull() ?: return null
        if (seconds < 0 || seconds >= 60) return null
        return minutes * 60 + seconds
    }

    /**
     * Parse "M:SS" string and convert to speed (kph).
     * @param paceString String in format "M:SS" (e.g., "5:30")
     * @return Speed in kph, or null if parsing fails
     */
    fun parseSpeedFromPace(paceString: String): Double? {
        val paceSeconds = parsePace(paceString) ?: return null
        return paceSecondsToSpeed(paceSeconds)
    }

    /**
     * Format duration in seconds as "M:SS" or "H:MM:SS".
     * @param durationSeconds Duration in seconds
     * @return Formatted string
     */
    fun formatDuration(durationSeconds: Int): String {
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60

        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } else {
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    /**
     * Format distance in meters as a human-readable string.
     * @param distanceMeters Distance in meters
     * @return Formatted string like "1.5 km" or "800 m"
     */
    fun formatDistance(distanceMeters: Int): String {
        return if (distanceMeters >= 1000) {
            val km = distanceMeters / 1000.0
            "%.1f km".format(km)
        } else {
            "$distanceMeters m"
        }
    }

    // ==================== Duration/Distance Cross-Calculations ====================

    /**
     * Calculate distance in meters from duration and speed.
     * @param durationSeconds Duration in seconds
     * @param speedKph Speed in km/h
     * @return Distance in meters
     */
    fun calculateDistanceMeters(durationSeconds: Int, speedKph: Double): Double {
        if (speedKph <= 0 || durationSeconds <= 0) return 0.0
        return speedKph * (durationSeconds / 3600.0) * 1000.0
    }

    /**
     * Calculate duration in seconds from distance and speed.
     * @param distanceMeters Distance in meters
     * @param speedKph Speed in km/h
     * @return Duration in seconds
     */
    fun calculateDurationSeconds(distanceMeters: Int, speedKph: Double): Double {
        if (speedKph <= 0 || distanceMeters <= 0) return 0.0
        return (distanceMeters / 1000.0) / speedKph * 3600.0
    }

    // ==================== Workout Stats Formatting ====================

    /**
     * Format workout stats as a single-line summary string.
     * @param stepCount Number of executable steps
     * @param distanceMeters Estimated distance in meters (nullable)
     * @param durationSeconds Estimated duration in seconds (nullable)
     * @param tss Estimated Training Stress Score (nullable)
     * @return Formatted string like "Steps: 5  Distance: 4.5 km  Duration: 30:00  TSS: 45"
     */
    fun formatWorkoutStats(
        stepCount: Int,
        distanceMeters: Int?,
        durationSeconds: Int?,
        tss: Int?
    ): String {
        val distance = distanceMeters?.let { formatDistance(it) } ?: "--"
        val duration = durationSeconds?.let { formatDuration(it) } ?: "--:--"
        val tssStr = tss?.toString() ?: "--"
        return "Steps: $stepCount  Distance: $distance  Duration: $duration  TSS: $tssStr"
    }
}
