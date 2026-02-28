package io.github.avikulin.thud.util

import io.github.avikulin.thud.data.entity.SpeedCalibrationPoint
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import kotlin.math.abs
import kotlin.math.pow

/**
 * Stateless utility for speed calibration: filters workout data into calibration pairs
 * and computes OLS regression (linear or polynomial up to degree 3).
 *
 * Two independent coefficient sets:
 * - Manual mode: linear (a, b) via [computeRegression] / [computeR2]
 * - Auto mode: polynomial C0..C3 via [computePolynomialRegression] / [computePolynomialR2]
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

    /** Newton-Raphson defaults for polynomial inversion. */
    private const val NR_MAX_ITERATIONS = 10
    private const val NR_TOLERANCE = 1e-6

    data class RegressionResult(val a: Double, val b: Double, val r2: Double, val n: Int)

    /** Result of polynomial regression: coefficients[i] is the coefficient for x^i. */
    data class PolynomialResult(
        val coefficients: DoubleArray,  // C0, C1, ..., Cn (index = power)
        val degree: Int,               // 1, 2, or 3
        val r2: Double,
        val n: Int
    )

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

    // ==================== Polynomial Regression ====================

    /**
     * Compute polynomial regression of given degree: stryd = C0 + C1*x + C2*x² + C3*x³.
     * Uses Vandermonde matrix normal equations solved by Gaussian elimination.
     *
     * For degree 2-3, validates monotonicity over the data range.
     * If non-monotonic, falls back to lower degrees, then returns null.
     *
     * @param points Calibration data (from DB, possibly spanning multiple runs)
     * @param degree Polynomial degree (1, 2, or 3)
     * @return Polynomial result with coefficients, R², and point count; null if insufficient data
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

        val clampedDegree = degree.coerceIn(1, 3)

        // Try requested degree, fall back to lower degrees if monotonicity fails
        for (d in clampedDegree downTo 1) {
            val result = fitPolynomial(points, d, xMin, xMax)
            if (result != null) return result
        }
        return null
    }

    /**
     * Fit a polynomial of exact degree [d] and validate it.
     * Returns null if the system is singular, coefficients are unreasonable,
     * or (for degree >= 2) the polynomial is non-monotonic over [xMin, xMax].
     *
     * Centers and scales x values before building the Vandermonde matrix to avoid
     * ill-conditioning (raw x values like 10-20 kph produce x^6 terms ~10^7 which
     * cause catastrophic cancellation in Gaussian elimination). The resulting
     * coefficients are denormalized back to original x-space before returning.
     */
    private fun fitPolynomial(
        points: List<SpeedCalibrationPoint>,
        d: Int,
        xMin: Double,
        xMax: Double
    ): PolynomialResult? {
        val n = points.size
        val m = d + 1  // number of coefficients

        // Center and scale x values to improve numerical conditioning.
        // Transforms x values to roughly [-1, 1] range so the Vandermonde matrix
        // entries stay O(1) instead of growing to x^(2d).
        val xMean = points.sumOf { it.treadmillKph } / n
        val xStd = kotlin.math.sqrt(points.sumOf { (it.treadmillKph - xMean).pow(2) } / n)
        if (xStd < 1e-10) return null  // all x values identical

        // Build normal equations in normalized space: z = (x - xMean) / xStd
        val ata = Array(m) { DoubleArray(m) }  // V^T V
        val aty = DoubleArray(m)                // V^T y

        for (p in points) {
            val z = (p.treadmillKph - xMean) / xStd
            val y = p.strydKph
            var zPowI = 1.0
            for (i in 0 until m) {
                aty[i] += zPowI * y
                var zPowJ = zPowI
                for (j in i until m) {
                    ata[i][j] += zPowI * zPowJ
                    if (i != j) ata[j][i] = ata[i][j]  // symmetric
                    zPowJ *= z
                }
                zPowI *= z
            }
        }

        // Solve in normalized space
        val normalizedCoeffs = solveLinearSystem(ata, aty) ?: return null

        // Convert coefficients back to original x-space:
        // P(z) = a0 + a1*z + a2*z² + a3*z³ where z = (x - mu) / s
        // Expand using binomial theorem to get Q(x) = c0 + c1*x + c2*x² + c3*x³
        val coefficients = denormalizeCoefficients(normalizedCoeffs, xMean, xStd)

        // Degree-1 sanity check: same bounds as linear regression
        if (d == 1) {
            val a = coefficients[1]
            val b = coefficients[0]
            if (a < MIN_SLOPE || a > MAX_SLOPE || b < MIN_INTERCEPT || b > MAX_INTERCEPT) return null
        }

        // Degree 2-3: validate monotonicity over data range
        // The derivative must be positive everywhere in [xMin, xMax]
        if (d >= 2 && !isMonotonicIncreasing(coefficients, xMin, xMax)) return null

        // Sanity: predicted values at data boundaries should be reasonable
        // (within 0.5x to 1.5x of input speed)
        val yAtMin = evaluatePolynomial(coefficients, xMin)
        val yAtMax = evaluatePolynomial(coefficients, xMax)
        if (yAtMin < xMin * 0.5 || yAtMin > xMin * 1.5) return null
        if (yAtMax < xMax * 0.5 || yAtMax > xMax * 1.5) return null

        // R²
        val meanY = points.sumOf { it.strydKph } / n
        val ssTot = points.sumOf { (it.strydKph - meanY).pow(2) }
        val ssRes = points.sumOf { (it.strydKph - evaluatePolynomial(coefficients, it.treadmillKph)).pow(2) }
        val r2 = if (ssTot > 0) 1.0 - ssRes / ssTot else 0.0

        return PolynomialResult(coefficients, d, r2, n)
    }

    /**
     * Convert polynomial coefficients from normalized space back to original x-space.
     *
     * Given P(z) = a[0] + a[1]*z + a[2]*z² + a[3]*z³ where z = (x - mu) / s,
     * expands to Q(x) = c[0] + c[1]*x + c[2]*x² + c[3]*x³ using binomial theorem.
     *
     * Hardcoded for degrees 1-3 for clarity and correctness.
     */
    private fun denormalizeCoefficients(
        a: DoubleArray,
        mu: Double,
        s: Double
    ): DoubleArray {
        val s2 = s * s
        val mu2 = mu * mu
        return when (a.size) {
            // degree 1: a0 + a1*(x-mu)/s
            2 -> doubleArrayOf(
                a[0] - a[1] * mu / s,
                a[1] / s
            )
            // degree 2: a0 + a1*(x-mu)/s + a2*((x-mu)/s)²
            3 -> doubleArrayOf(
                a[0] - a[1] * mu / s + a[2] * mu2 / s2,
                a[1] / s - 2.0 * a[2] * mu / s2,
                a[2] / s2
            )
            // degree 3: a0 + a1*(x-mu)/s + a2*((x-mu)/s)² + a3*((x-mu)/s)³
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
     * Check that the polynomial's derivative is positive over [xMin, xMax].
     * Samples at 20 evenly-spaced points plus the endpoints.
     */
    private fun isMonotonicIncreasing(coefficients: DoubleArray, xMin: Double, xMax: Double): Boolean {
        val steps = 20
        for (i in 0..steps) {
            val x = xMin + (xMax - xMin) * i / steps
            val derivative = evaluateDerivative(coefficients, x)
            if (derivative <= 0) return false
        }
        return true
    }

    /**
     * Evaluate polynomial: y = C0 + C1*x + C2*x² + C3*x³.
     * Uses Horner's method for numerical stability.
     */
    fun evaluatePolynomial(coefficients: DoubleArray, x: Double): Double {
        var result = 0.0
        for (i in coefficients.indices.reversed()) {
            result = result * x + coefficients[i]
        }
        return result
    }

    /**
     * Evaluate the derivative of the polynomial: dy/dx = C1 + 2*C2*x + 3*C3*x².
     * Uses Horner's method.
     */
    private fun evaluateDerivative(coefficients: DoubleArray, x: Double): Double {
        if (coefficients.size <= 1) return 0.0
        var result = 0.0
        for (i in (coefficients.size - 1) downTo 1) {
            result = result * x + i * coefficients[i]
        }
        return result
    }

    /**
     * Invert the polynomial using Newton-Raphson: find x such that P(x) = targetY.
     * Uses targetY as the initial guess (since adjusted speed ≈ raw speed).
     *
     * For monotonically increasing polynomials (which we validate), converges in:
     * - 1 iteration for linear (exact)
     * - 2-3 iterations for quadratic
     * - 3-5 iterations for cubic
     */
    fun invertPolynomialNewtonRaphson(
        coefficients: DoubleArray,
        targetY: Double,
        initialGuessX: Double = targetY
    ): Double {
        var x = initialGuessX
        for (i in 0 until NR_MAX_ITERATIONS) {
            val fx = evaluatePolynomial(coefficients, x) - targetY
            if (abs(fx) < NR_TOLERANCE) break
            val dfx = evaluateDerivative(coefficients, x)
            if (abs(dfx) < 1e-12) break  // near-zero derivative, bail
            x -= fx / dfx
        }
        return x
    }

    /**
     * Compute R² for a polynomial model against data points.
     * Used in auto mode to show goodness-of-fit for the current polynomial.
     */
    fun computePolynomialR2(points: List<SpeedCalibrationPoint>, coefficients: DoubleArray): Double {
        if (points.isEmpty()) return 0.0
        val meanY = points.sumOf { it.strydKph } / points.size
        val ssTot = points.sumOf { (it.strydKph - meanY).pow(2) }
        val ssRes = points.sumOf {
            (it.strydKph - evaluatePolynomial(coefficients, it.treadmillKph)).pow(2)
        }
        return if (ssTot > 0) 1.0 - ssRes / ssTot else 0.0
    }

    /**
     * Solve a linear system Ax = b using Gaussian elimination with partial pivoting.
     * For the normal equations of polynomial regression (max 4x4 for degree 3).
     *
     * @return Solution vector, or null if the system is singular
     */
    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = a.size
        // Augmented matrix
        val aug = Array(n) { i -> DoubleArray(n + 1) { j ->
            if (j < n) a[i][j] else b[i]
        }}

        for (col in 0 until n) {
            // Partial pivoting: find row with largest absolute value in column
            var maxRow = col
            var maxVal = abs(aug[col][col])
            for (row in col + 1 until n) {
                val v = abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-12) return null  // singular

            // Swap rows
            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }

            // Eliminate below
            val pivot = aug[col][col]
            for (row in col + 1 until n) {
                val factor = aug[row][col] / pivot
                for (j in col until n + 1) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        // Back substitution
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
