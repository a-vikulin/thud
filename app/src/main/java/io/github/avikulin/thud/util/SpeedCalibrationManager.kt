package io.github.avikulin.thud.util

import io.github.avikulin.thud.data.entity.SpeedCalibrationPoint
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import kotlin.math.abs
import kotlin.math.pow

/**
 * Stateless utility for speed calibration: filters workout data into calibration pairs
 * and computes OLS linear regression (stryd = a * treadmill + b).
 *
 * No file I/O — just filtering + math.
 */
object SpeedCalibrationManager {

    /** Minimum data points required for a meaningful regression. */
    private const val MIN_POINTS_FOR_REGRESSION = 10

    /** Minimum treadmill speed range (kph) for regression to be meaningful.
     *  Data clustered at one speed can fit any slope — need spread for leverage. */
    private const val MIN_SPEED_RANGE_KPH = 2.0

    /** Coefficient sanity bounds — reject wildly unreasonable models. */
    private const val MIN_SLOPE = 0.50
    private const val MAX_SLOPE = 1.50
    private const val MIN_INTERCEPT = -5.0
    private const val MAX_INTERCEPT = 5.0

    /** Maximum allowed speed discrepancy (30%) — rejects transition artifacts and glitches. */
    private const val MAX_DISCREPANCY_FRACTION = 0.30

    data class RegressionResult(val a: Double, val b: Double, val r2: Double, val n: Int)

    /**
     * Extract valid calibration pairs from workout data points.
     * Filters: both speeds > 0, discrepancy ≤ 30%.
     *
     * @param dataPoints Recorded workout data
     * @param runId Unique identifier for this run (typically start timestamp)
     * @return List of calibration points ready for DB insertion
     */
    fun extractPairs(
        dataPoints: List<WorkoutDataPoint>,
        runId: Long
    ): List<SpeedCalibrationPoint> {
        return dataPoints
            .filter { it.rawTreadmillSpeedKph > 0 && it.strydSpeedKph > 0 }
            .filter {
                val diff = abs(it.rawTreadmillSpeedKph - it.strydSpeedKph)
                val max = maxOf(it.rawTreadmillSpeedKph, it.strydSpeedKph)
                diff / max <= MAX_DISCREPANCY_FRACTION
            }
            .map {
                SpeedCalibrationPoint(
                    runId = runId,
                    treadmillKph = it.rawTreadmillSpeedKph,
                    strydKph = it.strydSpeedKph
                )
            }
    }

    /**
     * Compute OLS linear regression: stryd = a * treadmill + b.
     *
     * @param points Calibration data (from DB, possibly spanning multiple runs)
     * @return Regression result with slope (a), intercept (b), R², and point count; null if insufficient data
     */
    fun computeRegression(points: List<SpeedCalibrationPoint>): RegressionResult? {
        val n = points.size
        if (n < MIN_POINTS_FOR_REGRESSION) return null

        val sumX = points.sumOf { it.treadmillKph }
        val sumY = points.sumOf { it.strydKph }
        val sumXY = points.sumOf { it.treadmillKph * it.strydKph }
        val sumXX = points.sumOf { it.treadmillKph * it.treadmillKph }

        // Speed range check: need spread for full linear model (a*x + b)
        val xMin = points.minOf { it.treadmillKph }
        val xMax = points.maxOf { it.treadmillKph }

        val a: Double
        val b: Double

        if (xMax - xMin >= MIN_SPEED_RANGE_KPH) {
            // Full OLS regression: stryd = a * treadmill + b
            val denom = n * sumXX - sumX * sumX
            if (abs(denom) < 1e-10) return null
            a = (n * sumXY - sumX * sumY) / denom
            b = (sumY - a * sumX) / n
        } else {
            // Narrow speed range — can't determine intercept, fall back to ratio model (b=0)
            // OLS through origin: a = Σ(x*y) / Σ(x²)
            if (sumXX < 1e-10) return null
            a = sumXY / sumXX
            b = 0.0
        }

        // Sanity check: reject wildly unreasonable coefficients
        if (a < MIN_SLOPE || a > MAX_SLOPE || b < MIN_INTERCEPT || b > MAX_INTERCEPT) return null

        // R² = 1 - SS_res / SS_tot
        val meanY = sumY / n
        val ssTot = points.sumOf { (it.strydKph - meanY).pow(2) }
        val ssRes = points.sumOf { (it.strydKph - (a * it.treadmillKph + b)).pow(2) }
        val r2 = if (ssTot > 0) 1.0 - ssRes / ssTot else 0.0

        return RegressionResult(a, b, r2, n)
    }

    /**
     * Compute R² for a given (a, b) model against data points.
     * Used in manual mode to show goodness-of-fit for user-chosen slider values.
     */
    fun computeR2(points: List<SpeedCalibrationPoint>, a: Double, b: Double): Double {
        if (points.isEmpty()) return 0.0
        val meanY = points.sumOf { it.strydKph } / points.size
        val ssTot = points.sumOf { (it.strydKph - meanY).pow(2) }
        val ssRes = points.sumOf { (it.strydKph - (a * it.treadmillKph + b)).pow(2) }
        return if (ssTot > 0) 1.0 - ssRes / ssTot else 0.0
    }
}
