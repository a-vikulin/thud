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
 * Algorithm reference: Physionet DFA tutorial + FatMaxxer parameters.
 * Box sizes 4-16 (short-term correlations only).
 *
 * Thread-safe: all public methods are synchronized.
 */
class DfaAlpha1Calculator {

    data class DfaResult(
        val alpha1: Double,
        val artifactPercent: Double,
        val sampleCount: Int,
        val isValid: Boolean
    )

    companion object {
        private const val MAX_BUFFER_SIZE = 400       // ~2 min at 200 BPM
        private const val MIN_INTERVALS_FOR_DFA = 120 // Minimum clean intervals
        private const val ARTIFACT_THRESHOLD = 0.05   // 5% deviation from median
        private const val MEDIAN_WINDOW_SIZE = 5      // Median filter window
        private const val MIN_BOX_SIZE = 4
        private const val MAX_BOX_SIZE = 16
    }

    // Rolling buffer of clean (artifact-filtered) RR intervals in ms
    private val cleanBuffer = ArrayDeque<Double>(MAX_BUFFER_SIZE)

    // Recent intervals for median filter (includes artifacts, for baseline tracking)
    private val recentIntervals = ArrayDeque<Double>(MEDIAN_WINDOW_SIZE)

    // Artifact tracking
    private var totalReceived = 0
    private var totalRejected = 0

    /**
     * Add new RR intervals from a BLE notification.
     * Each interval is artifact-filtered before being added to the clean buffer.
     *
     * @param intervalsMs RR intervals in milliseconds
     * @param currentHrBpm Current heart rate (unused currently, reserved for future HR-aware filtering)
     */
    @Synchronized
    fun addRrIntervals(intervalsMs: List<Double>, currentHrBpm: Int) {
        for (rrMs in intervalsMs) {
            totalReceived++

            // Basic physiological range filter: 200-2000ms (30-300 BPM)
            if (rrMs < 200.0 || rrMs > 2000.0) {
                totalRejected++
                continue
            }

            // Artifact filter: reject if >5% deviation from median of recent intervals
            if (recentIntervals.size >= MEDIAN_WINDOW_SIZE) {
                val median = medianOf(recentIntervals)
                if (abs(rrMs - median) / median > ARTIFACT_THRESHOLD) {
                    totalRejected++
                    // Still update recent window so baseline tracks drift
                    recentIntervals.removeFirst()
                    recentIntervals.addLast(rrMs)
                    continue
                }
            }

            // Update recent window
            if (recentIntervals.size >= MEDIAN_WINDOW_SIZE) {
                recentIntervals.removeFirst()
            }
            recentIntervals.addLast(rrMs)

            // Add to clean buffer (evict oldest if full)
            if (cleanBuffer.size >= MAX_BUFFER_SIZE) {
                cleanBuffer.removeFirst()
            }
            cleanBuffer.addLast(rrMs)
        }
    }

    /**
     * Compute DFA alpha1 if enough clean data is available.
     * Returns null if fewer than [MIN_INTERVALS_FOR_DFA] clean intervals exist.
     */
    @Synchronized
    fun computeIfReady(): DfaResult? {
        if (cleanBuffer.size < MIN_INTERVALS_FOR_DFA) return null

        val alpha1 = computeDfaAlpha1(cleanBuffer.toDoubleArray())
        val artifactPercent = if (totalReceived > 0) {
            totalRejected.toDouble() / totalReceived * 100.0
        } else 0.0

        return DfaResult(
            alpha1 = alpha1,
            artifactPercent = artifactPercent,
            sampleCount = cleanBuffer.size,
            isValid = alpha1.isFinite() && alpha1 > 0
        )
    }

    /**
     * Reset all state. Call when starting a new run or switching sensors.
     */
    @Synchronized
    fun reset() {
        cleanBuffer.clear()
        recentIntervals.clear()
        totalReceived = 0
        totalRejected = 0
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
        if (n < MIN_INTERVALS_FOR_DFA) return Double.NaN

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
