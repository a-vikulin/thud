package io.github.avikulin.thud.util

/**
 * Calculates training metrics based on heart rate, power, and speed data.
 *
 * Implements TSS via TssCalculator (Power > HR > Pace priority).
 * Training Load and TE are left for Garmin/Firstbeat to calculate from raw data.
 */
object TrainingMetricsCalculator {

    /**
     * Training metrics result.
     */
    data class TrainingMetrics(
        val tss: Double,                           // Training Stress Score
        val tssSource: TssCalculator.TssSource     // Source of TSS calculation
    )

    /**
     * Calculate training metrics from HR, power, and speed data points.
     *
     * @param hrSamples List of (elapsedMs, heartRateBpm) pairs
     * @param powerSamples List of (elapsedMs, powerWatts) pairs
     * @param speedSamples List of (elapsedMs, speedKph) pairs
     * @param hrRest User's resting heart rate
     * @param lthr Lactate threshold heart rate (typically zone 4 max)
     * @param ftpWatts Functional Threshold Power
     * @param thresholdPaceKph Lactate threshold pace in kph
     * @return TrainingMetrics with TSS
     */
    fun calculate(
        hrSamples: List<Pair<Long, Double>>,
        powerSamples: List<Pair<Long, Double>> = emptyList(),
        speedSamples: List<Pair<Long, Double>> = emptyList(),
        hrRest: Int,
        lthr: Int,
        ftpWatts: Int = 250,
        thresholdPaceKph: Double = 10.0
    ): TrainingMetrics {
        // Calculate TSS using unified TssCalculator (Power > HR > Pace)
        val tssResult = TssCalculator.calculateFromSamples(
            powerSamples = powerSamples,
            hrSamples = hrSamples,
            speedSamples = speedSamples,
            ftpWatts = ftpWatts,
            lthr = lthr,
            hrRest = hrRest,
            thresholdPaceKph = thresholdPaceKph
        )

        return TrainingMetrics(
            tss = tssResult.tss,
            tssSource = tssResult.source
        )
    }
}
