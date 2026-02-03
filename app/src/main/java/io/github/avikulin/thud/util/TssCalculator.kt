package io.github.avikulin.thud.util

/**
 * Unified TSS (Training Stress Score) calculator.
 * Single source of truth for TSS calculation with priority: Power > HR > Pace.
 *
 * Used by:
 * - Workout editor (estimated TSS from planned targets)
 * - HUD live metrics display (actual TSS from recorded data)
 * - FIT file export (final TSS for the workout)
 */
object TssCalculator {

    /**
     * Source of TSS calculation.
     */
    enum class TssSource {
        POWER,  // Calculated from running power (most accurate)
        HR,     // Calculated from heart rate reserve
        PACE    // Calculated from pace/speed (fallback)
    }

    /**
     * Result of TSS calculation.
     */
    data class TssResult(
        val tss: Double,
        val source: TssSource
    )

    /**
     * Calculate TSS for a single interval/step.
     * Used by workout editor for estimated TSS.
     *
     * Priority: Power > HR > Pace
     *
     * @param durationSeconds Duration of the interval in seconds
     * @param powerWatts Average power for the interval (null if not available)
     * @param heartRateBpm Average heart rate for the interval (null if not available)
     * @param speedKph Average speed for the interval (null if not available)
     * @param ftpWatts Functional Threshold Power
     * @param lthr Lactate Threshold Heart Rate
     * @param hrRest Resting Heart Rate
     * @param thresholdPaceKph Lactate Threshold Pace in kph
     * @return TssResult with calculated TSS and source
     */
    fun calculateIntervalTss(
        durationSeconds: Int,
        powerWatts: Double? = null,
        heartRateBpm: Double? = null,
        speedKph: Double? = null,
        ftpWatts: Int,
        lthr: Int,
        hrRest: Int,
        thresholdPaceKph: Double
    ): TssResult {
        if (durationSeconds <= 0) {
            return TssResult(0.0, TssSource.PACE)
        }

        // Power-based TSS (most accurate)
        if (powerWatts != null && powerWatts > 0 && ftpWatts > 0) {
            val pIF = (powerWatts / ftpWatts).coerceIn(0.0, 2.0)
            val tss = (durationSeconds * pIF * pIF) / 36.0
            return TssResult(tss, TssSource.POWER)
        }

        // HR-based TSS
        if (heartRateBpm != null && heartRateBpm > hrRest) {
            val lthrReserve = (lthr - hrRest).toDouble().coerceAtLeast(1.0)
            val hrIF = ((heartRateBpm - hrRest) / lthrReserve).coerceIn(0.0, 2.0)
            val tss = (durationSeconds * hrIF * hrIF) / 36.0
            return TssResult(tss, TssSource.HR)
        }

        // Pace-based TSS (fallback)
        if (speedKph != null && speedKph > 0 && thresholdPaceKph > 0) {
            val paceIF = (speedKph / thresholdPaceKph).coerceIn(0.0, 2.0)
            val tss = (durationSeconds * paceIF * paceIF) / 36.0
            return TssResult(tss, TssSource.PACE)
        }

        return TssResult(0.0, TssSource.PACE)
    }

    /**
     * Calculate TSS from time-series samples.
     * Used by HUD live metrics and FIT file export.
     *
     * Priority: Power > HR > Pace
     *
     * @param powerSamples List of (elapsedMs, powerWatts) pairs
     * @param hrSamples List of (elapsedMs, heartRateBpm) pairs
     * @param speedSamples List of (elapsedMs, speedKph) pairs
     * @param ftpWatts Functional Threshold Power
     * @param lthr Lactate Threshold Heart Rate
     * @param hrRest Resting Heart Rate
     * @param thresholdPaceKph Lactate Threshold Pace in kph
     * @return TssResult with calculated TSS and source
     */
    fun calculateFromSamples(
        powerSamples: List<Pair<Long, Double>> = emptyList(),
        hrSamples: List<Pair<Long, Double>> = emptyList(),
        speedSamples: List<Pair<Long, Double>> = emptyList(),
        ftpWatts: Int,
        lthr: Int,
        hrRest: Int,
        thresholdPaceKph: Double
    ): TssResult {
        // Try power first (most accurate)
        val validPowerSamples = powerSamples.filter { it.second > 0 }
        if (validPowerSamples.size >= 2 && ftpWatts > 0) {
            val tss = calculatePowerTssFromSamples(validPowerSamples, ftpWatts)
            return TssResult(tss, TssSource.POWER)
        }

        // Try HR
        val validHrSamples = hrSamples.filter { it.second > 0 }
        if (validHrSamples.size >= 2 && lthr > hrRest) {
            val tss = calculateHrTssFromSamples(validHrSamples, lthr, hrRest)
            return TssResult(tss, TssSource.HR)
        }

        // Fall back to pace
        val validSpeedSamples = speedSamples.filter { it.second > 0 }
        if (validSpeedSamples.size >= 2 && thresholdPaceKph > 0) {
            val tss = calculatePaceTssFromSamples(validSpeedSamples, thresholdPaceKph)
            return TssResult(tss, TssSource.PACE)
        }

        return TssResult(0.0, TssSource.PACE)
    }

    /**
     * Calculate power-based TSS from time-series samples.
     * TSS = Σ (interval_seconds × (power/FTP)²) / 36
     */
    private fun calculatePowerTssFromSamples(
        powerSamples: List<Pair<Long, Double>>,
        ftpWatts: Int
    ): Double {
        if (powerSamples.size < 2 || ftpWatts <= 0) return 0.0

        var totalTss = 0.0

        for (i in 1 until powerSamples.size) {
            val (prevTime, _) = powerSamples[i - 1]
            val (currTime, currPower) = powerSamples[i]

            if (currPower <= 0) continue

            val intervalSeconds = (currTime - prevTime) / 1000.0
            if (intervalSeconds <= 0) continue

            val pIF = (currPower / ftpWatts).coerceIn(0.0, 2.0)
            totalTss += (intervalSeconds * pIF * pIF) / 36.0
        }

        return totalTss
    }

    /**
     * Calculate HR-based TSS from time-series samples.
     * hrIF = (HR - HRrest) / (LTHR - HRrest)
     * TSS = Σ (interval_seconds × hrIF²) / 36
     */
    private fun calculateHrTssFromSamples(
        hrSamples: List<Pair<Long, Double>>,
        lthr: Int,
        hrRest: Int
    ): Double {
        if (hrSamples.size < 2) return 0.0

        val lthrReserve = (lthr - hrRest).toDouble().coerceAtLeast(1.0)
        var totalTss = 0.0

        for (i in 1 until hrSamples.size) {
            val (prevTime, _) = hrSamples[i - 1]
            val (currTime, currHr) = hrSamples[i]

            if (currHr <= 0) continue

            val intervalSeconds = (currTime - prevTime) / 1000.0
            if (intervalSeconds <= 0) continue

            val hrIF = ((currHr - hrRest) / lthrReserve).coerceIn(0.0, 2.0)
            totalTss += (intervalSeconds * hrIF * hrIF) / 36.0
        }

        return totalTss
    }

    /**
     * Calculate pace-based TSS from time-series samples.
     * paceIF = speed / thresholdSpeed
     * TSS = Σ (interval_seconds × paceIF²) / 36
     */
    private fun calculatePaceTssFromSamples(
        speedSamples: List<Pair<Long, Double>>,
        thresholdPaceKph: Double
    ): Double {
        if (speedSamples.size < 2 || thresholdPaceKph <= 0) return 0.0

        var totalTss = 0.0

        for (i in 1 until speedSamples.size) {
            val (prevTime, _) = speedSamples[i - 1]
            val (currTime, currSpeed) = speedSamples[i]

            if (currSpeed <= 0) continue

            val intervalSeconds = (currTime - prevTime) / 1000.0
            if (intervalSeconds <= 0) continue

            val paceIF = (currSpeed / thresholdPaceKph).coerceIn(0.0, 2.0)
            totalTss += (intervalSeconds * paceIF * paceIF) / 36.0
        }

        return totalTss
    }
}
