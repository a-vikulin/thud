package io.github.avikulin.thud.util

import io.github.avikulin.thud.data.entity.SpeedCalibrationPoint
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import kotlin.math.abs
import kotlin.math.pow

/**
 * Stateless utility for speed calibration: filters workout data into calibration pairs
 * and computes OLS regression (linear or polynomial up to degree 3, with incline terms).
 *
 * Two independent coefficient sets:
 * - Manual mode: linear (a, b) via [computeRegression] / [computeR2]
 * - Auto mode: polynomial+incline C0..C5 via [computePolynomialRegression] / [computePolynomialR2]
 *   Model: y = C0 + C1*x + C2*x² + C3*x³ + C4*sin(θ) + C5*x*sin(θ)
 *   where x = raw treadmill speed (kph), sin(θ) = PaceConverter.inclinePercentToSin(rawIncline%)
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

    /** Minimum raw speed change (kph) to trigger settle window.
     *  Set below 0.1 kph so that the smallest real speed command (0.1 kph) always triggers. */
    private const val SPEED_CHANGE_THRESHOLD_KPH = 0.09

    /** Duration (ms) to ignore data after a raw treadmill speed change.
     *  Treadmill belt ramp-up + Stryd smoothing lag ≈ 10s to reach steady state. */
    private const val SETTLE_AFTER_CHANGE_MS = 10_000L

    /** Newton-Raphson defaults for polynomial inversion. */
    private const val NR_MAX_ITERATIONS = 10
    private const val NR_TOLERANCE = 1e-6

    data class RegressionResult(val a: Double, val b: Double, val r2: Double, val n: Int)

    /**
     * Result of polynomial regression.
     * coefficients is always 6 elements: [C0, C1, C2, C3, C4, C5]
     * Model: y = C0 + C1*x + C2*x² + C3*x³ + C4*s + C5*x*s
     * where s = sin(θ) from raw incline %.
     * Unused higher-degree speed terms are 0 (e.g., degree=1 → C2=C3=0).
     */
    data class PolynomialResult(
        val coefficients: DoubleArray,  // always [C0, C1, C2, C3, C4, C5]
        val degree: Int,               // speed polynomial degree: 1, 2, or 3
        val r2: Double,
        val n: Int,
        val inclineMinPercent: Double,  // training data incline range (raw treadmill %)
        val inclineMaxPercent: Double
    )

    /**
     * Extract valid calibration pairs from workout data points.
     * Filters: both speeds > 0, settle window after speed changes, discrepancy ≤ 30%.
     *
     * The settle window rejects data for [SETTLE_AFTER_CHANGE_MS] after any raw treadmill
     * speed change ≥ [SPEED_CHANGE_THRESHOLD_KPH]. This eliminates transition artifacts
     * where the treadmill reports target speed instantly but the belt and Stryd need ~10s
     * to reach steady state.
     *
     * @param dataPoints Recorded workout data
     * @param runId Unique identifier for this run (typically start timestamp)
     * @return List of calibration points ready for DB insertion
     */
    fun extractPairs(
        dataPoints: List<WorkoutDataPoint>,
        runId: Long
    ): List<SpeedCalibrationPoint> {
        // First pass: determine which data points fall within a settle window
        var settleDeadlineMs = Long.MIN_VALUE
        var prevRawSpeed = Double.NaN

        return dataPoints
            .filter { dp ->
                val rawSpeed = dp.rawTreadmillSpeedKph
                val settled = if (rawSpeed <= 0 || dp.strydSpeedKph <= 0) {
                    false
                } else if (prevRawSpeed.isNaN()) {
                    // First valid point — accept it but record speed for next comparison
                    true
                } else if (abs(rawSpeed - prevRawSpeed) >= SPEED_CHANGE_THRESHOLD_KPH) {
                    // Speed changed — start settle window
                    settleDeadlineMs = dp.timestampMs + SETTLE_AFTER_CHANGE_MS
                    false
                } else {
                    // No speed change — check if we're past the settle deadline
                    dp.timestampMs >= settleDeadlineMs
                }
                if (rawSpeed > 0) prevRawSpeed = rawSpeed
                settled
            }
            .filter {
                val diff = abs(it.rawTreadmillSpeedKph - it.strydSpeedKph)
                val max = maxOf(it.rawTreadmillSpeedKph, it.strydSpeedKph)
                diff / max <= MAX_DISCREPANCY_FRACTION
            }
            .map {
                SpeedCalibrationPoint(
                    runId = runId,
                    treadmillKph = it.rawTreadmillSpeedKph,
                    strydKph = it.strydSpeedKph,
                    inclinePercent = it.rawTreadmillInclinePercent
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

    // ==================== Polynomial + Incline Regression ====================

    /**
     * Compute polynomial+incline regression:
     *   y = C0 + C1*x + C2*x² + C3*x³ + C4*s + C5*x*s
     * where x = raw treadmill speed, s = sin(θ) from raw incline %.
     *
     * Uses design matrix normal equations solved by Gaussian elimination (max 6×6).
     * For degree 2-3, validates monotonicity (∂y/∂x > 0) over the data range
     * at representative incline values. Falls back to lower degrees if non-monotonic.
     *
     * @param points Calibration data (from DB, possibly spanning multiple runs)
     * @param degree Speed polynomial degree (1, 2, or 3)
     * @return Result with 6 coefficients [C0..C5], R², and point count; null if insufficient data
     */
    fun computePolynomialRegression(
        points: List<SpeedCalibrationPoint>,
        degree: Int
    ): PolynomialResult? {
        val n = points.size
        if (n < MIN_POINTS_FOR_REGRESSION) return null

        val xMin = points.minOf { it.treadmillKph }
        val xMax = points.maxOf { it.treadmillKph }
        if (xMax - xMin < MIN_SPEED_RANGE_KPH) return null

        // Training data incline range for clamping at evaluation time
        val inclineMin = points.minOf { it.inclinePercent }
        val inclineMax = points.maxOf { it.inclinePercent }

        val clampedDegree = degree.coerceIn(1, 3)

        // Try requested degree, fall back to lower degrees if monotonicity fails
        for (d in clampedDegree downTo 1) {
            val result = fitPolynomialWithIncline(points, d, xMin, xMax, inclineMin, inclineMax)
            if (result != null) return result
        }
        return null
    }

    /**
     * Fit a polynomial+incline model of exact speed degree [d] and validate it.
     *
     * Design matrix basis functions (in normalized space):
     *   [1, zx, zx², zx³, zs, zx*zs]
     * where zx = (x - xMean) / xStd, zs = (s - sMean) / sStd (or raw s if no variation).
     *
     * After solving, coefficients are denormalized back to original (x, s) space,
     * then packed into a 6-element array [C0, C1, C2, C3, C4, C5].
     */
    private fun fitPolynomialWithIncline(
        points: List<SpeedCalibrationPoint>,
        d: Int,
        xMin: Double,
        xMax: Double,
        inclineMinPercent: Double,
        inclineMaxPercent: Double
    ): PolynomialResult? {
        val n = points.size
        // Basis: [1, x, x², ..., x^d, s, x*s] → (d+1) + 2 = d+3 columns
        val m = d + 3

        // Center and scale x and s for numerical conditioning
        val xMean = points.sumOf { it.treadmillKph } / n
        val xStd = kotlin.math.sqrt(points.sumOf { (it.treadmillKph - xMean).pow(2) } / n)
        if (xStd < 1e-10) return null

        // Compute sin(θ) for each point
        val sValues = points.map { PaceConverter.inclinePercentToSin(it.inclinePercent) }
        val sMean = sValues.sum() / n
        val sStd = kotlin.math.sqrt(sValues.sumOf { (it - sMean).pow(2) } / n)
        // If no incline variation, sStd ≈ 0 — normalize to keep s around its mean
        val effectiveSStd = if (sStd > 1e-10) sStd else 1.0

        // Build normal equations: A^T A * coeffs = A^T y
        val ata = Array(m) { DoubleArray(m) }
        val aty = DoubleArray(m)

        for (idx in points.indices) {
            val p = points[idx]
            val zx = (p.treadmillKph - xMean) / xStd
            val zs = (sValues[idx] - sMean) / effectiveSStd
            val y = p.strydKph

            // Build basis vector: [1, zx, zx², ..., zx^d, zs, zx*zs]
            val basis = DoubleArray(m)
            var zxPow = 1.0
            for (i in 0..d) {
                basis[i] = zxPow
                zxPow *= zx
            }
            basis[d + 1] = zs
            basis[d + 2] = zx * zs

            // Accumulate normal equations
            for (i in 0 until m) {
                aty[i] += basis[i] * y
                for (j in i until m) {
                    ata[i][j] += basis[i] * basis[j]
                    if (i != j) ata[j][i] = ata[i][j]
                }
            }
        }

        // Solve in normalized space
        val nc = solveLinearSystem(ata, aty) ?: return null

        // Denormalize: convert from (zx, zs) space back to (x, s) space
        // zx = (x - xMean) / xStd, zs = (s - sMean) / effectiveSStd
        //
        // Speed polynomial part: same binomial expansion as before
        // nc[0..d] are coefficients for [1, zx, zx², ..., zx^d]
        val speedNorm = nc.sliceArray(0..d)
        val speedCoeffs = denormalizeSpeedCoefficients(speedNorm, xMean, xStd)

        // Incline terms: nc[d+1] * zs + nc[d+2] * zx * zs
        // zs = (s - sMean) / sStd
        // nc[d+1] * (s - sMean) / sStd = nc[d+1]/sStd * s - nc[d+1]*sMean/sStd
        // nc[d+2] * (x - xMean)/xStd * (s - sMean)/sStd
        //   = nc[d+2]/(xStd*sStd) * x*s - nc[d+2]*sMean/(xStd*sStd) * x
        //     - nc[d+2]*xMean/(xStd*sStd) * s + nc[d+2]*xMean*sMean/(xStd*sStd)
        val a4Norm = nc[d + 1]
        val a5Norm = nc[d + 2]
        val ss = effectiveSStd

        // C4 (coefficient of s)
        val c4 = a4Norm / ss - a5Norm * xMean / (xStd * ss)
        // C5 (coefficient of x*s)
        val c5 = a5Norm / (xStd * ss)
        // Corrections to C0 and C1 from the incline denormalization
        val c0Correction = -a4Norm * sMean / ss + a5Norm * xMean * sMean / (xStd * ss)
        val c1Correction = -a5Norm * sMean / (xStd * ss)

        // Assemble final 6-element coefficient array
        val coefficients = DoubleArray(6)
        // Pad speed coefficients (may be shorter than 4 for lower degrees)
        for (i in speedCoeffs.indices) coefficients[i] = speedCoeffs[i]
        coefficients[0] += c0Correction
        coefficients[1] += c1Correction
        coefficients[4] = c4
        coefficients[5] = c5

        // Degree-1 sanity check (at zero incline: effectively C0 + C1*x)
        if (d == 1) {
            val effectiveSlope = coefficients[1]  // C1 dominates at s≈0
            val effectiveIntercept = coefficients[0]
            if (effectiveSlope < MIN_SLOPE || effectiveSlope > MAX_SLOPE ||
                effectiveIntercept < MIN_INTERCEPT || effectiveIntercept > MAX_INTERCEPT) return null
        }

        // Monotonicity: ∂y/∂x = C1 + 2*C2*x + 3*C3*x² + C5*s must be > 0
        // Check at representative incline values spanning the data range
        val sMin = sValues.min()
        val sMax = sValues.max()
        if (d >= 2) {
            for (sVal in listOf(sMin, 0.0, sMax)) {
                if (!isMonotonicIncreasing(coefficients, xMin, xMax, sVal)) return null
            }
        } else {
            // Even degree 1 with C5 could theoretically go non-monotonic at extreme s
            if (!isMonotonicIncreasing(coefficients, xMin, xMax, sMin)) return null
            if (!isMonotonicIncreasing(coefficients, xMin, xMax, sMax)) return null
        }

        // Sanity: predicted values at data boundaries (at s=0) should be reasonable
        val yAtMin = evaluatePolynomial(coefficients, xMin, 0.0)
        val yAtMax = evaluatePolynomial(coefficients, xMax, 0.0)
        if (yAtMin < xMin * 0.5 || yAtMin > xMin * 1.5) return null
        if (yAtMax < xMax * 0.5 || yAtMax > xMax * 1.5) return null

        // R²
        val meanY = points.sumOf { it.strydKph } / n
        val ssTot = points.sumOf { (it.strydKph - meanY).pow(2) }
        val ssRes = points.indices.sumOf { i ->
            val predicted = evaluatePolynomial(coefficients, points[i].treadmillKph, sValues[i])
            (points[i].strydKph - predicted).pow(2)
        }
        val r2 = if (ssTot > 0) 1.0 - ssRes / ssTot else 0.0

        return PolynomialResult(coefficients, d, r2, n, inclineMinPercent, inclineMaxPercent)
    }

    /**
     * Denormalize speed-only polynomial coefficients from normalized space.
     * Given P(z) = a[0] + a[1]*z + ... where z = (x - mu) / s,
     * expands to Q(x) = c[0] + c[1]*x + ... using binomial theorem.
     */
    private fun denormalizeSpeedCoefficients(
        a: DoubleArray,
        mu: Double,
        s: Double
    ): DoubleArray {
        val s2 = s * s
        val mu2 = mu * mu
        return when (a.size) {
            2 -> doubleArrayOf(
                a[0] - a[1] * mu / s,
                a[1] / s
            )
            3 -> doubleArrayOf(
                a[0] - a[1] * mu / s + a[2] * mu2 / s2,
                a[1] / s - 2.0 * a[2] * mu / s2,
                a[2] / s2
            )
            4 -> {
                val s3 = s2 * s
                val mu3 = mu2 * mu
                doubleArrayOf(
                    a[0] - a[1] * mu / s + a[2] * mu2 / s2 - a[3] * mu3 / s3,
                    a[1] / s - 2.0 * a[2] * mu / s2 + 3.0 * a[3] * mu2 / s3,
                    a[2] / s2 - 3.0 * a[3] * mu / s3,
                    a[3] / s3
                )
            }
            else -> a
        }
    }

    /**
     * Check that ∂y/∂x > 0 over [xMin, xMax] at a given sin(θ) value.
     * ∂y/∂x = C1 + 2*C2*x + 3*C3*x² + C5*s
     */
    private fun isMonotonicIncreasing(
        coefficients: DoubleArray,
        xMin: Double,
        xMax: Double,
        sinIncline: Double = 0.0
    ): Boolean {
        val steps = 20
        for (i in 0..steps) {
            val x = xMin + (xMax - xMin) * i / steps
            val derivative = evaluateSpeedDerivative(coefficients, x, sinIncline)
            if (derivative <= 0) return false
        }
        return true
    }

    /**
     * Evaluate the full model: y = C0 + C1*x + C2*x² + C3*x³ + C4*s + C5*x*s.
     * coefficients is always 6 elements [C0, C1, C2, C3, C4, C5].
     *
     * @param coefficients 6-element array [C0, C1, C2, C3, C4, C5]
     * @param x Raw treadmill speed (kph)
     * @param sinIncline sin(θ) of raw treadmill incline (from PaceConverter.inclinePercentToSin)
     */
    fun evaluatePolynomial(coefficients: DoubleArray, x: Double, sinIncline: Double): Double {
        // Speed polynomial: C0 + C1*x + C2*x² + C3*x³ (Horner's on first 4 terms)
        var speedPart = 0.0
        for (i in 3 downTo 0) {
            speedPart = speedPart * x + coefficients[i]
        }
        // Incline terms: C4*s + C5*x*s
        val inclinePart = coefficients[4] * sinIncline + coefficients[5] * x * sinIncline
        return speedPart + inclinePart
    }

    /**
     * Evaluate ∂y/∂x = C1 + 2*C2*x + 3*C3*x² + C5*sin(θ).
     */
    private fun evaluateSpeedDerivative(
        coefficients: DoubleArray,
        x: Double,
        sinIncline: Double = 0.0
    ): Double {
        return coefficients[1] +
            2.0 * coefficients[2] * x +
            3.0 * coefficients[3] * x * x +
            coefficients[5] * sinIncline
    }

    /**
     * Invert the model using Newton-Raphson: find x such that f(x, s) = targetY,
     * with s = sin(θ) known and fixed.
     *
     * f(x) = C0 + C1*x + C2*x² + C3*x³ + C4*s + C5*x*s - targetY
     * f'(x) = C1 + 2*C2*x + 3*C3*x² + C5*s
     */
    fun invertPolynomialNewtonRaphson(
        coefficients: DoubleArray,
        targetY: Double,
        sinIncline: Double,
        initialGuessX: Double = targetY
    ): Double {
        var x = initialGuessX
        for (i in 0 until NR_MAX_ITERATIONS) {
            val fx = evaluatePolynomial(coefficients, x, sinIncline) - targetY
            if (abs(fx) < NR_TOLERANCE) break
            val dfx = evaluateSpeedDerivative(coefficients, x, sinIncline)
            if (abs(dfx) < 1e-12) break
            x -= fx / dfx
        }
        return x
    }

    /**
     * Compute R² for the full model (with incline) against data points.
     */
    fun computePolynomialR2(points: List<SpeedCalibrationPoint>, coefficients: DoubleArray): Double {
        if (points.isEmpty()) return 0.0
        val meanY = points.sumOf { it.strydKph } / points.size
        val ssTot = points.sumOf { (it.strydKph - meanY).pow(2) }
        val ssRes = points.sumOf { p ->
            val s = PaceConverter.inclinePercentToSin(p.inclinePercent)
            (p.strydKph - evaluatePolynomial(coefficients, p.treadmillKph, s)).pow(2)
        }
        return if (ssTot > 0) 1.0 - ssRes / ssTot else 0.0
    }

    /**
     * Solve a linear system Ax = b using Gaussian elimination with partial pivoting.
     * Supports up to 6×6 systems (degree 3 + 2 incline terms).
     *
     * @return Solution vector, or null if the system is singular
     */
    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = a.size
        val aug = Array(n) { i -> DoubleArray(n + 1) { j ->
            if (j < n) a[i][j] else b[i]
        }}

        for (col in 0 until n) {
            var maxRow = col
            var maxVal = abs(aug[col][col])
            for (row in col + 1 until n) {
                val v = abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-12) return null

            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }

            val pivot = aug[col][col]
            for (row in col + 1 until n) {
                val factor = aug[row][col] / pivot
                for (j in col until n + 1) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = aug[i][n]
            for (j in i + 1 until n) {
                sum -= aug[i][j] * x[j]
            }
            if (abs(aug[i][i]) < 1e-12) return null
            x[i] = sum / aug[i][i]
        }
        return x
    }
}
