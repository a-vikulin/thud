package io.github.avikulin.thud.util

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Real-time DFA (Detrended Fluctuation Analysis) alpha1 calculator.
 *
 * Computes the short-term fractal scaling exponent from RR intervals.
 * Alpha1 thresholds: >0.75 = aerobic, 0.5-0.75 = transition, <0.5 = anaerobic.
 *
 * Algorithm reference: Physionet DFA tutorial + Kubios/FatMaxxer parameters.
 * Box sizes 4-16 (short-term correlations only).
 *
 * Thread-safe: all public methods are synchronized.
 *
 * @param windowDurationMs Time-based analysis window in milliseconds (default 120s).
 *        Naturally adapts to heart rate: ~360 beats at 180 BPM, ~240 at 120 BPM.
 * @param artifactThresholdPercent Percent deviation from median to reject as artifact (default 20%).
 *        Matches Kubios "Medium" level. Lower = more aggressive filtering.
 * @param medianWindowSize Number of recent clean intervals for median baseline (default 11).
 *        Larger = more robust to bursts of consecutive artifacts.
 * @param emaAlpha EMA smoothing factor for output (default 0.2). 0 = maximum smoothing, 1 = no smoothing.
 */
class DfaAlpha1Calculator(
    private val windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS,
    private val artifactThresholdPercent: Double = DEFAULT_ARTIFACT_THRESHOLD_PERCENT,
    private val medianWindowSize: Int = DEFAULT_MEDIAN_WINDOW_SIZE,
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA
) {

    data class DfaResult(
        val alpha1: Double,
        val rawAlpha1: Double,
        val artifactPercent: Double,
        val sampleCount: Int,
        val isValid: Boolean
    )

    companion object {
        // Configurable defaults (used by Settings UI as default values)
        const val DEFAULT_WINDOW_DURATION_SEC = 120
        const val DEFAULT_WINDOW_DURATION_MS = 120_000L
        const val DEFAULT_ARTIFACT_THRESHOLD_PERCENT = 20.0
        const val DEFAULT_MEDIAN_WINDOW_SIZE = 11
        const val DEFAULT_EMA_ALPHA = 0.2f

        // Not configurable — safety floor to prevent degenerate computation
        private const val MIN_INTERVALS_SAFETY = 50
        private const val MIN_BOX_SIZE = 4
        private const val MAX_BOX_SIZE = 16
    }

    // Rolling buffer of clean (artifact-filtered) RR intervals in ms
    private val cleanBuffer = ArrayDeque<Double>()

    // Running sum of all RR intervals in cleanBuffer (for time-based eviction)
    private var bufferTotalMs = 0.0

    // Recent clean intervals for median filter (only updated with accepted intervals)
    private val recentIntervals = ArrayDeque<Double>()

    // Time-windowed artifact tracking: (wasRejected, rrMs)
    private val artifactFlags = ArrayDeque<Pair<Boolean, Double>>()
    private var artifactRrTotalMs = 0.0

    // EMA smoothed output
    private var smoothedAlpha1 = Double.NaN

    /**
     * Add new RR intervals from a BLE notification.
     * Each interval is artifact-filtered before being added to the clean buffer.
     *
     * @param intervalsMs RR intervals in milliseconds
     * @param currentHrBpm Current heart rate (unused currently, reserved for future HR-aware filtering)
     */
    @Synchronized
    fun addRrIntervals(intervalsMs: List<Double>, currentHrBpm: Int) {
        val thresholdFraction = artifactThresholdPercent / 100.0

        for (rrMs in intervalsMs) {
            // Basic physiological range filter: 200-2000ms (30-300 BPM)
            if (rrMs < 200.0 || rrMs > 2000.0) {
                trackArtifact(rejected = true, rrMs = rrMs)
                continue
            }

            // Artifact filter: reject if deviation from median exceeds threshold
            if (recentIntervals.size >= medianWindowSize) {
                val median = medianOf(recentIntervals)
                if (abs(rrMs - median) / median > thresholdFraction) {
                    // Do NOT update recentIntervals with rejected artifacts —
                    // prevents baseline from drifting toward artifact values
                    trackArtifact(rejected = true, rrMs = rrMs)
                    continue
                }
            }

            // Accepted: update recent clean window
            if (recentIntervals.size >= medianWindowSize) {
                recentIntervals.removeFirst()
            }
            recentIntervals.addLast(rrMs)

            // Add to clean buffer with time-based eviction
            cleanBuffer.addLast(rrMs)
            bufferTotalMs += rrMs
            while (bufferTotalMs > windowDurationMs && cleanBuffer.size > MIN_INTERVALS_SAFETY) {
                bufferTotalMs -= cleanBuffer.removeFirst()
            }

            trackArtifact(rejected = false, rrMs = rrMs)
        }
    }

    /**
     * Track accept/reject decision for windowed artifact percentage.
     */
    private fun trackArtifact(rejected: Boolean, rrMs: Double) {
        artifactFlags.addLast(Pair(rejected, rrMs))
        artifactRrTotalMs += rrMs
        while (artifactRrTotalMs > windowDurationMs && artifactFlags.size > 1) {
            artifactRrTotalMs -= artifactFlags.removeFirst().second
        }
    }

    /**
     * Compute DFA alpha1 if enough clean data is available.
     * Returns null if the buffer hasn't filled the time window or has too few intervals.
     */
    @Synchronized
    fun computeIfReady(): DfaResult? {
        if (bufferTotalMs < windowDurationMs || cleanBuffer.size < MIN_INTERVALS_SAFETY) return null

        val rawAlpha1 = computeDfaAlpha1(cleanBuffer.toDoubleArray())

        // Apply EMA smoothing
        val smoothed = if (smoothedAlpha1.isNaN()) {
            rawAlpha1  // first value: no smoothing
        } else {
            emaAlpha * rawAlpha1 + (1 - emaAlpha) * smoothedAlpha1
        }
        smoothedAlpha1 = smoothed

        val artifactPercent = if (artifactFlags.isNotEmpty()) {
            artifactFlags.count { it.first } * 100.0 / artifactFlags.size
        } else 0.0

        return DfaResult(
            alpha1 = smoothed,
            rawAlpha1 = rawAlpha1,
            artifactPercent = artifactPercent,
            sampleCount = cleanBuffer.size,
            isValid = smoothed.isFinite() && smoothed > 0
        )
    }

    /**
     * Reset all state. Call when starting a new run or switching sensors.
     */
    @Synchronized
    fun reset() {
        cleanBuffer.clear()
        bufferTotalMs = 0.0
        recentIntervals.clear()
        artifactFlags.clear()
        artifactRrTotalMs = 0.0
        smoothedAlpha1 = Double.NaN
    }

    // ==================== DFA Algorithm ====================

    /**
     * Standard DFA alpha1 computation.
     *
     * Steps:
     * 1. Subtract mean from RR series
     * 2. Integrate (cumulative sum)
     * 3. For each box size n in [4..16]:
     *    a. Divide into non-overlapping boxes
     *    b. Linear detrend each box
     *    c. Compute RMS of residuals → F(n)
     * 4. Linear regression of log(F(n)) vs log(n) → slope = alpha1
     */
    private fun computeDfaAlpha1(rrIntervals: DoubleArray): Double {
        val n = rrIntervals.size
        if (n < MIN_INTERVALS_SAFETY) return Double.NaN

        // Step 1: Subtract mean
        val mean = rrIntervals.average()

        // Step 2: Integrate (cumulative sum of deviations from mean)
        val integrated = DoubleArray(n)
        var cumulativeSum = 0.0
        for (i in 0 until n) {
            cumulativeSum += rrIntervals[i] - mean
            integrated[i] = cumulativeSum
        }

        // Step 3: Compute F(n) for each box size
        val logN = mutableListOf<Double>()
        val logF = mutableListOf<Double>()

        for (boxSize in MIN_BOX_SIZE..MAX_BOX_SIZE) {
            val numBoxes = n / boxSize
            if (numBoxes < 2) continue

            var totalVariance = 0.0
            var totalPoints = 0

            for (box in 0 until numBoxes) {
                val start = box * boxSize
                val end = start + boxSize

                // Linear detrend: least-squares fit within box
                // y = a + b*x, where x = 0, 1, ..., boxSize-1
                var sumX = 0.0
                var sumY = 0.0
                var sumXY = 0.0
                var sumX2 = 0.0
                for (i in start until end) {
                    val x = (i - start).toDouble()
                    sumX += x
                    sumY += integrated[i]
                    sumXY += x * integrated[i]
                    sumX2 += x * x
                }
                val bsD = boxSize.toDouble()
                val denominator = bsD * sumX2 - sumX * sumX
                if (denominator == 0.0) continue

                val slope = (bsD * sumXY - sumX * sumY) / denominator
                val intercept = (sumY - slope * sumX) / bsD

                // Compute sum of squared residuals
                for (i in start until end) {
                    val x = (i - start).toDouble()
                    val trend = intercept + slope * x
                    val residual = integrated[i] - trend
                    totalVariance += residual * residual
                    totalPoints++
                }
            }

            if (totalPoints > 0) {
                val fN = sqrt(totalVariance / totalPoints)
                if (fN > 0) {
                    logN.add(ln(boxSize.toDouble()))
                    logF.add(ln(fN))
                }
            }
        }

        // Step 4: Linear regression of log(F(n)) vs log(n)
        if (logN.size < 3) return Double.NaN
        return linearRegressionSlope(logN, logF)
    }

    // ==================== Utility Functions ====================

    /**
     * Compute the slope of a linear regression: y = a + b*x, returns b.
     */
    private fun linearRegressionSlope(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        if (n < 2) return Double.NaN

        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        for (i in 0 until n) {
            sumX += x[i]
            sumY += y[i]
            sumXY += x[i] * y[i]
            sumX2 += x[i] * x[i]
        }

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return Double.NaN

        return (n * sumXY - sumX * sumY) / denominator
    }

    /**
     * Compute the median of the values in a deque.
     */
    private fun medianOf(values: ArrayDeque<Double>): Double {
        val sorted = values.toDoubleArray().also { it.sort() }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }
}
