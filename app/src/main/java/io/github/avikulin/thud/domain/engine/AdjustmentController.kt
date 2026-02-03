package io.github.avikulin.thud.domain.engine

import android.util.Log
import io.github.avikulin.thud.domain.model.AdjustmentType
import io.github.avikulin.thud.service.AdjustmentControllerState
import io.github.avikulin.thud.service.ServiceStateHolder

/**
 * Data point for metric trend calculation (HR or Power).
 */
data class MetricDataPoint(
    val elapsedMs: Long,
    val value: Double  // BPM for HR, Watts for Power
)

/**
 * Configuration for auto-adjustment behavior.
 * Pass HR config or Power config based on the step's autoAdjustMode.
 */
data class AdjustmentConfig(
    val trendWindowSeconds: Int,
    val trendThreshold: Double,
    val settlingTimeSeconds: Int,
    val minTimeBetweenAdjSeconds: Int,
    val speedAdjustmentKph: Double,
    val speedUrgentAdjustmentKph: Double,
    val inclineAdjustmentPercent: Double,
    val inclineUrgentAdjustmentPercent: Double,
    val urgentAboveThreshold: Double,  // BPM or Watts depending on metric
    val maxSpeedAdjustmentKph: Double,
    val maxInclineAdjustmentPercent: Double,
    // Treadmill limits
    val minSpeedKph: Double,
    val maxSpeedKph: Double,
    val minInclinePercent: Double,
    val maxInclinePercent: Double
) {
    companion object {
        /**
         * Build HR adjustment config from ServiceStateHolder.
         */
        fun forHr(state: ServiceStateHolder): AdjustmentConfig = AdjustmentConfig(
            trendWindowSeconds = state.hrTrendWindowSeconds,
            trendThreshold = state.hrTrendThreshold,
            settlingTimeSeconds = state.hrSettlingTimeSeconds,
            minTimeBetweenAdjSeconds = state.hrMinTimeBetweenAdjSeconds,
            speedAdjustmentKph = state.hrSpeedAdjustmentKph,
            speedUrgentAdjustmentKph = state.hrSpeedUrgentAdjustmentKph,
            inclineAdjustmentPercent = state.hrInclineAdjustmentPercent,
            inclineUrgentAdjustmentPercent = state.hrInclineUrgentAdjustmentPercent,
            urgentAboveThreshold = state.hrUrgentAboveThresholdBpm.toDouble(),
            maxSpeedAdjustmentKph = state.hrMaxSpeedAdjustmentKph,
            maxInclineAdjustmentPercent = state.hrMaxInclineAdjustmentPercent,
            minSpeedKph = state.minSpeedKph,
            maxSpeedKph = state.maxSpeedKph,
            minInclinePercent = state.minInclinePercent,
            maxInclinePercent = state.maxInclinePercent
        )

        /**
         * Build Power adjustment config from ServiceStateHolder.
         */
        fun forPower(state: ServiceStateHolder): AdjustmentConfig = AdjustmentConfig(
            trendWindowSeconds = state.powerTrendWindowSeconds,
            trendThreshold = state.powerTrendThreshold,
            settlingTimeSeconds = state.powerSettlingTimeSeconds,
            minTimeBetweenAdjSeconds = state.powerMinTimeBetweenAdjSeconds,
            speedAdjustmentKph = state.powerSpeedAdjustmentKph,
            speedUrgentAdjustmentKph = state.powerSpeedUrgentAdjustmentKph,
            inclineAdjustmentPercent = state.powerInclineAdjustmentPercent,
            inclineUrgentAdjustmentPercent = state.powerInclineUrgentAdjustmentPercent,
            urgentAboveThreshold = state.powerUrgentAboveThresholdWatts.toDouble(),
            maxSpeedAdjustmentKph = state.powerMaxSpeedAdjustmentKph,
            maxInclineAdjustmentPercent = state.powerMaxInclineAdjustmentPercent,
            minSpeedKph = state.minSpeedKph,
            maxSpeedKph = state.maxSpeedKph,
            minInclinePercent = state.minInclinePercent,
            maxInclinePercent = state.maxInclinePercent
        )
    }
}

/**
 * Controls automatic speed/incline adjustments based on a metric (HR or Power).
 *
 * Uses trend-aware logic: considers not just whether metric is in/out of range,
 * but also whether metric is moving toward or away from the target range.
 * This prevents over-correction when metric is naturally recovering.
 *
 * Decision Matrix:
 * | Metric Position | Trend    | Action |
 * |-----------------|----------|--------|
 * | Above max       | Falling  | Wait (recovering naturally) |
 * | Above max       | Stable/Rising | Reduce speed/incline |
 * | Below min       | Rising   | Wait (increasing naturally) |
 * | Below min       | Stable/Falling | Increase speed/incline |
 * | In range        | Any      | No action |
 *
 */
class AdjustmentController {
    companion object {
        private const val TAG = "AdjustController"
        private const val EVALUATION_INTERVAL_MS = 1_000L
    }

    // State - timing only, engine owns adjusted values via coefficient
    private var lastAdjustmentTimeMs: Long = 0
    private var lastEvaluationTimeMs: Long = 0
    private var stepStartTimeMs: Long = 0
    private var settlingStartTimeMs: Long = 0  // When to start counting settling time (reset on resume)

    enum class MetricTrend { RISING, FALLING, STABLE }

    sealed class AdjustmentResult {
        object NoAdjustment : AdjustmentResult()

        data class Waiting(
            val reason: String
        ) : AdjustmentResult()

        data class AdjustSpeed(
            val newSpeedKph: Double,
            val direction: String,
            val reason: String
        ) : AdjustmentResult()

        data class AdjustIncline(
            val newInclinePercent: Double,
            val direction: String,
            val reason: String
        ) : AdjustmentResult()
    }

    /**
     * Called when a new step starts to reset timing state.
     * @param currentElapsedMs Current elapsed time in milliseconds (from treadmill timer)
     */
    fun onStepStarted(currentElapsedMs: Long) {
        Log.d(TAG, "onStepStarted: elapsedMs=$currentElapsedMs")
        stepStartTimeMs = currentElapsedMs
        settlingStartTimeMs = currentElapsedMs
        lastAdjustmentTimeMs = 0
        lastEvaluationTimeMs = 0
    }

    /**
     * Called when workout is resumed from pause.
     * Resets settling timer to prevent immediate HR adjustments based on HR that dropped during pause.
     * @param currentElapsedMs Current elapsed time in milliseconds (from treadmill timer)
     */
    fun onWorkoutResumed(currentElapsedMs: Long) {
        Log.d(TAG, "onWorkoutResumed: elapsedMs=$currentElapsedMs, resetting settling timer")
        settlingStartTimeMs = currentElapsedMs
        lastAdjustmentTimeMs = 0
        lastEvaluationTimeMs = 0
    }

    /**
     * Calculate metric trend over the configured window using data from dataProvider.
     * Returns value change per minute (positive = rising, negative = falling).
     *
     * Note: Uses the data points' own time base (elapsedMs from latest point) to ensure
     * consistency, since data points may use workout-relative time while the caller
     * may pass raw treadmill elapsed time.
     *
     * @param trendWindowSeconds Window size for trend calculation
     * @param dataProvider Function that returns recent data points for trend calculation
     */
    private fun calculateTrend(
        currentElapsedMs: Long,
        trendWindowSeconds: Int,
        dataProvider: () -> List<MetricDataPoint>
    ): Double {
        val allData = dataProvider()
        Log.d(TAG, "calculateTrend: allData.size=${allData.size}, currentElapsedMs=$currentElapsedMs")

        if (allData.size < 2) {
            Log.d(TAG, "calculateTrend: not enough data points")
            return 0.0
        }

        // Use the latest data point's elapsed time as reference (matches data's time base)
        // This avoids mismatch when caller passes raw treadmill time but data uses workout-relative time
        val latestDataElapsedMs = allData.last().elapsedMs
        val cutoff = latestDataElapsedMs - (trendWindowSeconds * 1000L)
        val recentData = allData.filter { it.elapsedMs >= cutoff }
        Log.d(TAG, "calculateTrend: latestDataMs=$latestDataElapsedMs, cutoff=$cutoff, recentData.size=${recentData.size}")

        if (recentData.size < 2) {
            Log.d(TAG, "calculateTrend: not enough recent data")
            return 0.0
        }

        val oldest = recentData.first()
        val newest = recentData.last()
        val timeDeltaMinutes = (newest.elapsedMs - oldest.elapsedMs) / 60_000.0

        if (timeDeltaMinutes < 0.1) {
            Log.d(TAG, "calculateTrend: time delta too small: $timeDeltaMinutes min")
            return 0.0
        }

        val trend = (newest.value - oldest.value) / timeDeltaMinutes
        Log.d(TAG, "calculateTrend: oldest=${oldest.value}@${oldest.elapsedMs}, newest=${newest.value}@${newest.elapsedMs}, trend=$trend per min")
        return trend
    }

    private fun getTrendDirection(trendPerMin: Double, threshold: Double): MetricTrend {
        return when {
            trendPerMin > threshold -> MetricTrend.RISING
            trendPerMin < -threshold -> MetricTrend.FALLING
            else -> MetricTrend.STABLE
        }
    }

    /**
     * Check metric (HR or Power) against target range and determine if adjustment is needed.
     * This is the generalized version that accepts configuration.
     *
     * @param currentValue Current metric value (BPM for HR, Watts for Power)
     * @param targetMin Minimum target value
     * @param targetMax Maximum target value
     * @param adjustmentType What to adjust (SPEED or INCLINE)
     * @param config Configuration for this metric type (HR or Power)
     * @param currentPace Current pace in kph
     * @param currentIncline Current incline percent
     * @param basePace Original step target pace
     * @param baseIncline Original step target incline
     * @param currentTimeMs Current timestamp
     * @param metricName Name for logging (e.g., "HR", "Power")
     * @return AdjustmentResult indicating what action to take
     */
    fun checkTargetRange(
        currentValue: Double,
        targetMin: Int,
        targetMax: Int,
        adjustmentType: AdjustmentType,
        config: AdjustmentConfig,
        currentPace: Double,
        currentIncline: Double,
        basePace: Double,
        baseIncline: Double,
        currentTimeMs: Long,
        metricName: String = "HR",
        dataProvider: () -> List<MetricDataPoint>
    ): AdjustmentResult {
        Log.d(TAG, "checkTargetRange: $metricName=${currentValue.toInt()}, target=$targetMin-$targetMax, type=$adjustmentType, timeMs=$currentTimeMs")

        // Don't evaluate too frequently
        if (currentTimeMs - lastEvaluationTimeMs < EVALUATION_INTERVAL_MS) {
            return AdjustmentResult.NoAdjustment
        }
        lastEvaluationTimeMs = currentTimeMs

        // Allow settling time at start of step or after resume
        val settlingTimeMs = config.settlingTimeSeconds * 1000L
        val timeSinceSettlingStart = currentTimeMs - settlingStartTimeMs
        if (timeSinceSettlingStart < settlingTimeMs) {
            return AdjustmentResult.Waiting(
                "$metricName settling: ${timeSinceSettlingStart / 1000}s / ${config.settlingTimeSeconds}s"
            )
        }

        // Calculate trend using the provided data source (HR or Power)
        val trendPerMin = calculateTrend(currentTimeMs, config.trendWindowSeconds, dataProvider)
        val trend = getTrendDirection(trendPerMin, config.trendThreshold)
        val trendStr = "%.1f/min".format(trendPerMin)

        // Check position relative to range
        val valueAboveMax = currentValue - targetMax
        val valueBelowMin = targetMin - currentValue

        // In range - no action needed
        if (valueAboveMax <= 0 && valueBelowMin <= 0) {
            return AdjustmentResult.NoAdjustment
        }

        val minTimeBetweenAdjMs = config.minTimeBetweenAdjSeconds * 1000L

        // Value above max
        if (valueAboveMax > 0) {
            // If value is falling toward range, wait
            if (trend == MetricTrend.FALLING) {
                return AdjustmentResult.Waiting(
                    "$metricName ${currentValue.toInt()} > $targetMax, but falling ($trendStr) - waiting"
                )
            }

            // Value above max and not falling - need to reduce
            if (lastAdjustmentTimeMs > 0 && currentTimeMs - lastAdjustmentTimeMs < minTimeBetweenAdjMs) {
                return AdjustmentResult.Waiting(
                    "$metricName ${currentValue.toInt()} > $targetMax, cooldown"
                )
            }

            val isUrgent = valueAboveMax > config.urgentAboveThreshold
            lastAdjustmentTimeMs = currentTimeMs

            return when (adjustmentType) {
                AdjustmentType.SPEED -> reduceSpeedWithConfig(
                    currentValue.toInt(), targetMax, currentPace, basePace, isUrgent, trendStr, metricName, config
                )
                AdjustmentType.INCLINE -> reduceInclineWithConfig(
                    currentValue.toInt(), targetMax, currentIncline, baseIncline, isUrgent, trendStr, metricName, config
                )
            }
        }

        // Value below min
        if (valueBelowMin > 0) {
            // If value is rising toward range, wait
            if (trend == MetricTrend.RISING) {
                return AdjustmentResult.Waiting(
                    "$metricName ${currentValue.toInt()} < $targetMin, but rising ($trendStr) - waiting"
                )
            }

            // Value below min and not rising - need to increase
            if (lastAdjustmentTimeMs > 0 && currentTimeMs - lastAdjustmentTimeMs < minTimeBetweenAdjMs) {
                return AdjustmentResult.Waiting(
                    "$metricName ${currentValue.toInt()} < $targetMin, cooldown"
                )
            }

            lastAdjustmentTimeMs = currentTimeMs

            return when (adjustmentType) {
                AdjustmentType.SPEED -> increaseSpeedWithConfig(
                    currentValue.toInt(), targetMin, currentPace, basePace, trendStr, metricName, config
                )
                AdjustmentType.INCLINE -> increaseInclineWithConfig(
                    currentValue.toInt(), targetMin, currentIncline, baseIncline, trendStr, metricName, config
                )
            }
        }

        return AdjustmentResult.NoAdjustment
    }

    private fun reduceSpeedWithConfig(
        currentValue: Int, targetMax: Int, currentPace: Double, basePace: Double,
        isUrgent: Boolean, trendStr: String, metricName: String, config: AdjustmentConfig
    ): AdjustmentResult {
        val minAllowed = maxOf(config.minSpeedKph, basePace - config.maxSpeedAdjustmentKph)
        val reduction = if (isUrgent) config.speedUrgentAdjustmentKph else config.speedAdjustmentKph
        val newSpeed = maxOf(minAllowed, currentPace - reduction)

        if (newSpeed >= currentPace) {
            return AdjustmentResult.Waiting("Already at minimum speed")
        }

        val urgentStr = if (isUrgent) " (urgent)" else ""
        return AdjustmentResult.AdjustSpeed(
            newSpeedKph = newSpeed,
            direction = "reducing",
            reason = "$metricName $currentValue > $targetMax, trend $trendStr$urgentStr"
        )
    }

    private fun increaseSpeedWithConfig(
        currentValue: Int, targetMin: Int, currentPace: Double, basePace: Double,
        trendStr: String, metricName: String, config: AdjustmentConfig
    ): AdjustmentResult {
        val maxAllowed = minOf(config.maxSpeedKph, basePace + config.maxSpeedAdjustmentKph)
        val newSpeed = minOf(maxAllowed, currentPace + config.speedAdjustmentKph)

        if (newSpeed <= currentPace) {
            return AdjustmentResult.Waiting("Already at maximum speed")
        }

        return AdjustmentResult.AdjustSpeed(
            newSpeedKph = newSpeed,
            direction = "increasing",
            reason = "$metricName $currentValue < $targetMin, trend $trendStr"
        )
    }

    private fun reduceInclineWithConfig(
        currentValue: Int, targetMax: Int, currentIncline: Double, baseIncline: Double,
        isUrgent: Boolean, trendStr: String, metricName: String, config: AdjustmentConfig
    ): AdjustmentResult {
        val minAllowed = maxOf(config.minInclinePercent, baseIncline - config.maxInclineAdjustmentPercent)
        val reduction = if (isUrgent) config.inclineUrgentAdjustmentPercent else config.inclineAdjustmentPercent
        val newIncline = maxOf(minAllowed, currentIncline - reduction)

        if (newIncline >= currentIncline) {
            return AdjustmentResult.Waiting("Already at minimum incline")
        }

        val urgentStr = if (isUrgent) " (urgent)" else ""
        return AdjustmentResult.AdjustIncline(
            newInclinePercent = newIncline,
            direction = "reducing",
            reason = "$metricName $currentValue > $targetMax, trend $trendStr$urgentStr"
        )
    }

    private fun increaseInclineWithConfig(
        currentValue: Int, targetMin: Int, currentIncline: Double, baseIncline: Double,
        trendStr: String, metricName: String, config: AdjustmentConfig
    ): AdjustmentResult {
        val maxAllowed = minOf(config.maxInclinePercent, baseIncline + config.maxInclineAdjustmentPercent)
        val newIncline = minOf(maxAllowed, currentIncline + config.inclineAdjustmentPercent)

        if (newIncline <= currentIncline) {
            return AdjustmentResult.Waiting("Already at maximum incline")
        }

        return AdjustmentResult.AdjustIncline(
            newInclinePercent = newIncline,
            direction = "increasing",
            reason = "$metricName $currentValue < $targetMin, trend $trendStr"
        )
    }

    /**
     * Reset the controller timing state.
     */
    fun reset() {
        lastAdjustmentTimeMs = 0
        lastEvaluationTimeMs = 0
        stepStartTimeMs = 0
        settlingStartTimeMs = 0
    }

    // ==================== State Persistence ====================

    /**
     * Export the current controller state for persistence.
     */
    fun exportState(): AdjustmentControllerState {
        return AdjustmentControllerState(
            stepStartTimeMs = stepStartTimeMs,
            settlingStartTimeMs = settlingStartTimeMs,
            lastAdjustmentTimeMs = lastAdjustmentTimeMs,
            lastEvaluationTimeMs = lastEvaluationTimeMs
        )
    }

    /**
     * Restore controller state from persisted data.
     */
    fun restoreFromState(state: AdjustmentControllerState) {
        stepStartTimeMs = state.stepStartTimeMs
        settlingStartTimeMs = state.settlingStartTimeMs
        lastAdjustmentTimeMs = state.lastAdjustmentTimeMs
        lastEvaluationTimeMs = state.lastEvaluationTimeMs
        Log.d(TAG, "Restored adjustment controller state")
    }
}
