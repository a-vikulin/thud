package io.github.avikulin.thud.service

import android.util.Log
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// RecorderState is defined in RunPersistenceManager.kt

/**
 * Represents a pause or resume event during workout recording.
 * Used to write FIT Event messages for accurate timer time calculation.
 */
data class PauseEvent(
    val timestampMs: Long,
    val isPause: Boolean  // true = pause (stop), false = resume (start)
)

/**
 * Handles workout data recording with millisecond precision.
 * Calculates distance and elevation from adjusted speed and incline.
 * Thread-safe for concurrent access from multiple coroutines.
 */
class WorkoutRecorder {

    companion object {
        private const val TAG = "WorkoutRecorder"
        private const val PERIODIC_RECORD_THRESHOLD_MS = 900L
    }

    // Recorded data points (thread-safe)
    private val workoutData: MutableList<WorkoutDataPoint> =
        Collections.synchronizedList(mutableListOf())

    // Pause/resume events for FIT file export
    private val pauseEvents: MutableList<PauseEvent> =
        Collections.synchronizedList(mutableListOf())

    // HR sensor registry: index-ordered list of (MAC, name) for compact per-data-point storage.
    // Index assigned on first encounter via resolveOrRegister(); stable for the duration of the run.
    private val hrSensors: MutableList<Pair<String, String>> = mutableListOf()
    private val hrSensorMacToIndex: MutableMap<String, Int> = mutableMapOf()

    // Per-sensor RR intervals for FIT HRV export: MAC → list of (timestampMs, rrSeconds)
    private val rrIntervalsBySensor: ConcurrentHashMap<String, MutableList<Pair<Long, Float>>> =
        ConcurrentHashMap()

    // Recording state
    private var _isRecording = false
    val isRecording: Boolean get() = _isRecording

    // Timing
    private var workoutStartTimeMs = 0L
    private var lastRecordTimeMs = 0L
    private var lastRecordedElapsedSeconds = -1

    // Treadmill-based timing for chart alignment
    private var workoutStartTreadmillSeconds = -1
    private var lastTreadmillElapsedSeconds = 0

    // Calculated metrics
    private var _calculatedDistanceKm = 0.0
    val calculatedDistanceKm: Double get() = _calculatedDistanceKm

    private var _calculatedElevationGainM = 0.0
    val calculatedElevationGainM: Double get() = _calculatedElevationGainM

    private var _calculatedCaloriesKcal = 0.0
    val calculatedCaloriesKcal: Double get() = _calculatedCaloriesKcal

    // User profile for calorie calculation (set from service state)
    var userWeightKg: Double = 70.0
    var userAge: Int = 35
    var userIsMale: Boolean = true

    // Workout-relative elapsed time (for HUD display)
    val workoutElapsedSeconds: Int get() =
        if (workoutStartTreadmillSeconds >= 0)
            (lastTreadmillElapsedSeconds - workoutStartTreadmillSeconds).coerceAtLeast(0)
        else 0

    // Callback for UI updates
    var onMetricsUpdated: ((distanceKm: Double, elevationM: Double) -> Unit)? = null

    /**
     * Record a data point immediately.
     * Calculates distance and elevation based on time since last record.
     * Only records if _isRecording is true (use forceRecord=true to bypass).
     *
     * @param speedKph Raw speed from treadmill
     * @param inclinePercent Current incline
     * @param heartRateBpm Current heart rate
     * @param paceCoefficient Pace adjustment coefficient
     * @param treadmillElapsedSeconds Current treadmill elapsed time (for chart alignment)
     * @param forceRecord If true, bypasses _isRecording check (for robust telemetry recording)
     * @param powerWatts Running power from foot pod (0 if not available)
     * @param cadenceSpm Cadence in strides per minute from foot pod (0 if not available). Display layer doubles for steps/min.
     * @param rawPowerWatts Raw power from Stryd before incline adjustment (0 if not available)
     * @param inclinePowerWatts Incline power contribution (adjusted - raw, for calibration)
     * @param stepIndex Current workout step index (-1 if no structured workout)
     * @param stepName Current workout step name (empty if no structured workout)
     */
    fun recordDataPoint(
        speedKph: Double,
        inclinePercent: Double,
        heartRateBpm: Double,
        paceCoefficient: Double,
        treadmillElapsedSeconds: Int = -1,
        forceRecord: Boolean = false,
        powerWatts: Double = 0.0,
        cadenceSpm: Int = 0,
        rawPowerWatts: Double = 0.0,
        inclinePowerWatts: Double = 0.0,
        strydSpeedKph: Double = 0.0,
        stepIndex: Int = -1,
        stepName: String = "",
        connectedHrSensors: Map<String, Pair<String, Int>> = emptyMap(),
        primaryHrMac: String = "",
        dfaAlpha1BySensor: Map<String, Double> = emptyMap()
    ) {
        if (!_isRecording && !forceRecord) return

        // Auto-start recording if forced and not yet recording
        if (forceRecord && !_isRecording && workoutStartTimeMs == 0L) {
            Log.d(TAG, "Auto-starting recording due to forced record")
            startRecording()
        }

        val now = System.currentTimeMillis()
        val timeDeltaMs = now - lastRecordTimeMs

        // Throttle recording to avoid duplicates - max ~1 record per second
        // Skip if less than threshold has passed (unless it's the first record)
        if (lastRecordTimeMs > 0 && timeDeltaMs < PERIODIC_RECORD_THRESHOLD_MS) {
            return
        }

        // Use treadmill elapsed time for chart alignment if provided
        val elapsedMs = if (treadmillElapsedSeconds >= 0) {
            // Initialize workout start time on first data point with treadmill time
            if (workoutStartTreadmillSeconds < 0) {
                workoutStartTreadmillSeconds = treadmillElapsedSeconds
            }
            lastTreadmillElapsedSeconds = treadmillElapsedSeconds
            ((treadmillElapsedSeconds - workoutStartTreadmillSeconds) * 1000L).coerceAtLeast(0)
        } else {
            // Fallback to wall clock time
            now - workoutStartTimeMs
        }

        // Use adjusted speed (with pace coefficient)
        val adjustedSpeedKph = speedKph * paceCoefficient

        // Calculate distance, elevation, and calories traveled since last record
        if (timeDeltaMs > 0 && lastRecordTimeMs > 0) {
            // distance = speed * time, where time is in hours (ms / 3600000)
            val distanceIncrementKm = adjustedSpeedKph * timeDeltaMs / 3600000.0
            _calculatedDistanceKm += distanceIncrementKm

            // elevation = distance_in_meters * (incline_percent / 100)
            // Only add positive incline (climbing), ignore negative (descending)
            if (inclinePercent > 0) {
                val distanceIncrementM = distanceIncrementKm * 1000.0
                _calculatedElevationGainM += distanceIncrementM * (inclinePercent / 100.0)
            }

            // Calculate calories using best available method
            val caloriesIncrement = calculateCaloriesIncrement(
                timeDeltaMs = timeDeltaMs,
                speedKph = adjustedSpeedKph,
                inclinePercent = inclinePercent,
                heartRateBpm = heartRateBpm,
                powerWatts = powerWatts
            )
            _calculatedCaloriesKcal += caloriesIncrement
        }

        lastRecordTimeMs = now

        // Auto-register new sensors and convert MAC→BPM to index→BPM
        val indexedHr = mutableMapOf<Int, Int>()
        for ((mac, pair) in connectedHrSensors) {
            val (name, bpm) = pair
            val idx = resolveOrRegister(mac, name)
            indexedHr[idx] = bpm
        }
        val primaryIdx = hrSensorMacToIndex[primaryHrMac] ?: -1

        // Convert DFA alpha1 MAC-keyed map to sensor-index-keyed map
        val indexedDfa = mutableMapOf<Int, Double>()
        for ((mac, alpha1) in dfaAlpha1BySensor) {
            val idx = hrSensorMacToIndex[mac] ?: continue
            indexedDfa[idx] = alpha1
        }

        // Create data point
        val dataPoint = WorkoutDataPoint(
            timestampMs = now,
            elapsedMs = elapsedMs,
            speedKph = adjustedSpeedKph,
            inclinePercent = inclinePercent,
            heartRateBpm = heartRateBpm,
            distanceKm = _calculatedDistanceKm,
            elevationGainM = _calculatedElevationGainM,
            caloriesKcal = _calculatedCaloriesKcal,
            powerWatts = powerWatts,
            rawPowerWatts = rawPowerWatts,
            inclinePowerWatts = inclinePowerWatts,
            cadenceSpm = cadenceSpm,
            strydSpeedKph = strydSpeedKph,
            stepIndex = stepIndex,
            stepName = stepName,
            allHrSensors = indexedHr,
            primaryHrIndex = primaryIdx,
            dfaAlpha1BySensor = indexedDfa
        )
        workoutData.add(dataPoint)

        // Notify UI
        onMetricsUpdated?.invoke(_calculatedDistanceKm, _calculatedElevationGainM)

        Log.v(TAG, "Recorded: treadmillSecs=$treadmillElapsedSeconds, startSecs=$workoutStartTreadmillSeconds, elapsed=${elapsedMs}ms, speed=${adjustedSpeedKph}kph")
    }

    /**
     * Ensure at least one record per second even if no speed/incline updates came.
     * Call this from elapsed time updates.
     *
     * @return true if a record was made, false otherwise
     */
    fun ensurePeriodicRecord(
        speedKph: Double,
        inclinePercent: Double,
        heartRateBpm: Double,
        paceCoefficient: Double,
        treadmillElapsedSeconds: Int = -1,
        powerWatts: Double = 0.0,
        cadenceSpm: Int = 0,
        rawPowerWatts: Double = 0.0,
        inclinePowerWatts: Double = 0.0,
        strydSpeedKph: Double = 0.0,
        stepIndex: Int = -1,
        stepName: String = "",
        connectedHrSensors: Map<String, Pair<String, Int>> = emptyMap(),
        primaryHrMac: String = "",
        dfaAlpha1BySensor: Map<String, Double> = emptyMap()
    ): Boolean {
        if (!_isRecording) return false

        val now = System.currentTimeMillis()
        val timeSinceLastRecord = now - lastRecordTimeMs

        // If more than 900ms since last record, record now
        if (timeSinceLastRecord >= PERIODIC_RECORD_THRESHOLD_MS) {
            recordDataPoint(
                speedKph, inclinePercent, heartRateBpm, paceCoefficient, treadmillElapsedSeconds,
                forceRecord = false,
                powerWatts = powerWatts,
                cadenceSpm = cadenceSpm,
                rawPowerWatts = rawPowerWatts,
                inclinePowerWatts = inclinePowerWatts,
                strydSpeedKph = strydSpeedKph,
                stepIndex = stepIndex,
                stepName = stepName,
                connectedHrSensors = connectedHrSensors,
                primaryHrMac = primaryHrMac,
                dfaAlpha1BySensor = dfaAlpha1BySensor
            )
            return true
        }
        return false
    }

    /**
     * Check if the treadmill workout was reset (elapsed time went backwards or to zero).
     * If reset detected, clears all recorded data.
     *
     * @param elapsedSeconds Current elapsed time from treadmill
     * @return true if reset was detected and data was cleared
     */
    fun checkForWorkoutReset(elapsedSeconds: Int): Boolean {
        if (elapsedSeconds < lastRecordedElapsedSeconds && lastRecordedElapsedSeconds > 0) {
            Log.d(TAG, "Workout reset detected (elapsed went from $lastRecordedElapsedSeconds to $elapsedSeconds)")
            resetData()
            return true
        }
        lastRecordedElapsedSeconds = elapsedSeconds
        return false
    }

    /**
     * Handle GlassOS restart where elapsed time resets to 0 but we want to preserve data.
     * Adjusts internal timing offset so new data points continue from where we left off.
     *
     * @param newTreadmillElapsedSeconds The new treadmill elapsed time (usually 0 after restart)
     */
    fun handleGlassOsRestart(newTreadmillElapsedSeconds: Int) {
        // Synchronized to prevent TOCTOU race: lastOrNull() is not atomic on synchronizedList
        // (isEmpty + get are two separate locks; a concurrent clear() between them would crash)
        val lastElapsedSeconds = synchronized(workoutData) {
            if (workoutData.isEmpty()) return
            val lastElapsedMs = workoutData.last().elapsedMs
            (lastElapsedMs / 1000).toInt()
        }

        // Adjust the start reference: when treadmill shows newTreadmillElapsedSeconds,
        // our elapsed should be lastElapsedSeconds
        // elapsed = (treadmill - start) → lastElapsedSeconds = (new - newStart)
        // newStart = new - lastElapsedSeconds
        workoutStartTreadmillSeconds = newTreadmillElapsedSeconds - lastElapsedSeconds
        lastTreadmillElapsedSeconds = newTreadmillElapsedSeconds

        Log.d(TAG, "Adjusted timing for GlassOS restart: startTreadmillSecs=$workoutStartTreadmillSeconds, " +
            "lastElapsed=${lastElapsedSeconds}s, treadmill=${newTreadmillElapsedSeconds}s")
    }

    /**
     * Start recording workout data.
     */
    fun startRecording() {
        workoutData.clear()
        hrSensors.clear()
        hrSensorMacToIndex.clear()
        rrIntervalsBySensor.clear()
        _calculatedDistanceKm = 0.0
        _calculatedElevationGainM = 0.0
        _calculatedCaloriesKcal = 0.0
        workoutStartTimeMs = System.currentTimeMillis()
        lastRecordTimeMs = 0L  // Allow first data point to be recorded immediately
        lastRecordedElapsedSeconds = -1
        workoutStartTreadmillSeconds = -1
        lastTreadmillElapsedSeconds = 0
        _isRecording = true
        Log.d(TAG, "Started workout recording")
    }

    /**
     * Stop recording workout data.
     */
    fun stopRecording() {
        _isRecording = false
        Log.d(TAG, "Stopped workout recording. Total points: ${workoutData.size}")
    }

    /**
     * Pause recording (e.g., when workout is paused).
     * Preserves data but stops adding new points.
     * Records a pause event for FIT file export.
     */
    fun pauseRecording() {
        if (_isRecording) {
            _isRecording = false
            pauseEvents.add(PauseEvent(System.currentTimeMillis(), isPause = true))
            Log.d(TAG, "Paused workout recording")
        }
    }

    /**
     * Resume recording after pause.
     * Resets lastRecordTimeMs to avoid counting pause duration in distance/elevation calculations.
     * Records a resume event for FIT file export.
     */
    fun resumeRecording() {
        if (!_isRecording && workoutStartTimeMs > 0) {
            lastRecordTimeMs = System.currentTimeMillis()
            _isRecording = true
            pauseEvents.add(PauseEvent(System.currentTimeMillis(), isPause = false))
            Log.d(TAG, "Resumed workout recording")
        }
    }

    /**
     * Get a copy of all recorded workout data.
     */
    fun getWorkoutData(): List<WorkoutDataPoint> {
        synchronized(workoutData) {
            return workoutData.toList()
        }
    }

    /**
     * Get a copy of all pause/resume events.
     */
    fun getPauseEvents(): List<PauseEvent> {
        synchronized(pauseEvents) {
            return pauseEvents.toList()
        }
    }

    /**
     * Resolve an HR sensor MAC to its stable index, registering it if first seen.
     * This is the only path to assign sensor indices — called lazily from recordDataPoint().
     */
    private fun resolveOrRegister(mac: String, name: String): Int {
        hrSensorMacToIndex[mac]?.let { return it }
        val index = hrSensors.size
        hrSensors.add(Pair(mac, name))
        hrSensorMacToIndex[mac] = index
        Log.d(TAG, "Auto-registered HR sensor: $name ($mac) → index $index")
        return index
    }

    /**
     * Get the index-ordered sensor registry (MAC, name) for FIT export.
     */
    fun getHrSensors(): List<Pair<String, String>> = hrSensors.toList()

    /**
     * Retroactively rebind all recorded data points to use a different HR sensor's readings
     * as the native heartRateBpm. This makes the HR sensor selector a preview/export tool:
     * switching sensors updates the chart, and the last selection determines the FIT native HR.
     *
     * For data points where the selected sensor wasn't connected, keeps existing heartRateBpm.
     *
     * @param primaryMac MAC of the new primary sensor, or HR_PRIMARY_AVERAGE for averaged
     * @return number of data points that were rebound
     */
    fun rebindPrimaryHr(primaryMac: String): Int {
        val isAverage = (primaryMac == SettingsManager.HR_PRIMARY_AVERAGE)
        val sensorIndex = if (!isAverage) hrSensorMacToIndex[primaryMac] else null

        if (!isAverage && sensorIndex == null) {
            Log.w(TAG, "rebindPrimaryHr: sensor $primaryMac not in registry, skipping")
            return 0
        }

        // Pre-compute CALC sensor indices for AVERAGE mode (exclude synthetic sensors)
        val calcIndices: Set<Int> = if (isAverage) {
            hrSensors.withIndex()
                .filter { (_, pair) -> pair.first.startsWith("CALC:") }
                .map { (i, _) -> i }
                .toSet()
        } else emptySet()

        var reboundCount = 0
        synchronized(workoutData) {
            for (i in workoutData.indices) {
                val dp = workoutData[i]
                if (dp.allHrSensors.isEmpty()) continue

                if (isAverage) {
                    val validBpms = dp.allHrSensors
                        .filter { (idx, bpm) -> idx !in calcIndices && bpm > 0 }
                        .values
                    if (validBpms.isEmpty()) continue
                    workoutData[i] = dp.copy(
                        heartRateBpm = validBpms.average(),
                        primaryHrIndex = -1
                    )
                    reboundCount++
                } else {
                    val bpm = dp.allHrSensors[sensorIndex!!]
                    if (bpm != null && bpm > 0) {
                        workoutData[i] = dp.copy(
                            heartRateBpm = bpm.toDouble(),
                            primaryHrIndex = sensorIndex
                        )
                        reboundCount++
                    }
                }
            }
        }
        Log.d(TAG, "rebindPrimaryHr: mac=$primaryMac, rebound=$reboundCount/${workoutData.size}")
        return reboundCount
    }

    /**
     * Retroactively update a sensor's BPM values in all recorded data points.
     * Used when CALC HR parameters change and the entire BPM timeline is recomputed.
     *
     * @param sensorMac MAC of the sensor to update (e.g., "CALC:AA:BB:CC:DD:EE:FF")
     * @param bpmTimeline sorted list of (wall-clock timestampMs, bpm) entries
     * @return number of updated data points
     */
    fun updateSensorBpmInHistory(sensorMac: String, bpmTimeline: List<Pair<Long, Int>>): Int {
        val sensorIndex = hrSensorMacToIndex[sensorMac] ?: return 0
        if (bpmTimeline.isEmpty()) return 0

        var updated = 0
        var timelineIdx = 0
        synchronized(workoutData) {
            for (i in workoutData.indices) {
                val dp = workoutData[i]
                if (!dp.allHrSensors.containsKey(sensorIndex)) continue

                // Advance timeline pointer to latest entry at or before this data point's timestamp
                while (timelineIdx < bpmTimeline.lastIndex &&
                    bpmTimeline[timelineIdx + 1].first <= dp.timestampMs) {
                    timelineIdx++
                }

                // Only update if we have a timeline entry at or before this data point
                if (bpmTimeline[timelineIdx].first <= dp.timestampMs) {
                    val newBpm = bpmTimeline[timelineIdx].second
                    if (dp.allHrSensors[sensorIndex] != newBpm) {
                        val newMap = dp.allHrSensors.toMutableMap()
                        newMap[sensorIndex] = newBpm
                        workoutData[i] = dp.copy(allHrSensors = newMap)
                        updated++
                    }
                }
            }
        }
        Log.d(TAG, "updateSensorBpmInHistory: mac=$sensorMac, updated=$updated/${workoutData.size}")
        return updated
    }

    /**
     * Record RR intervals from a specific sensor for FIT HRV export.
     */
    fun recordRrIntervals(mac: String, intervalsMs: List<Double>) {
        val sensorList = rrIntervalsBySensor.getOrPut(mac) {
            Collections.synchronizedList(mutableListOf())
        }
        val now = System.currentTimeMillis()
        for (rrMs in intervalsMs) {
            sensorList.add(Pair(now, (rrMs / 1000.0).toFloat()))
        }
    }

    /**
     * Get per-sensor RR intervals for FIT export. Returns a snapshot.
     */
    fun getRrIntervalsBySensor(): Map<String, List<Pair<Long, Float>>> =
        rrIntervalsBySensor.mapValues { (_, list) -> synchronized(list) { list.toList() } }

    /**
     * Get the number of recorded data points.
     */
    fun getDataPointCount(): Int {
        synchronized(workoutData) {
            return workoutData.size
        }
    }

    /**
     * Clear all workout data and reset metrics.
     */
    fun clearData() {
        workoutData.clear()
        pauseEvents.clear()
        hrSensors.clear()
        hrSensorMacToIndex.clear()
        rrIntervalsBySensor.clear()
        _calculatedDistanceKm = 0.0
        _calculatedElevationGainM = 0.0
        _calculatedCaloriesKcal = 0.0
        workoutStartTimeMs = System.currentTimeMillis()
        lastRecordTimeMs = 0L
        lastRecordedElapsedSeconds = -1
        workoutStartTreadmillSeconds = -1
        lastTreadmillElapsedSeconds = 0
        Log.d(TAG, "Cleared workout data")
    }

    /**
     * Reset workout data (internal, called on workout reset detection).
     */
    private fun resetData() {
        workoutData.clear()
        pauseEvents.clear()
        rrIntervalsBySensor.clear()
        _calculatedDistanceKm = 0.0
        _calculatedElevationGainM = 0.0
        _calculatedCaloriesKcal = 0.0
        workoutStartTimeMs = System.currentTimeMillis()
        lastRecordTimeMs = 0L
        lastRecordedElapsedSeconds = -1
        Log.d(TAG, "Workout data reset")
    }

    // ==================== State Persistence ====================

    /**
     * Export the current recorder state for persistence.
     */
    fun exportState(): RecorderState {
        synchronized(workoutData) {
            synchronized(pauseEvents) {
                return RecorderState(
                    workoutStartTimeMs = workoutStartTimeMs,
                    lastRecordTimeMs = lastRecordTimeMs,
                    workoutStartTreadmillSeconds = workoutStartTreadmillSeconds,
                    lastTreadmillElapsedSeconds = lastTreadmillElapsedSeconds,
                    isRecording = _isRecording,
                    calculatedDistanceKm = _calculatedDistanceKm,
                    calculatedElevationGainM = _calculatedElevationGainM,
                    calculatedCaloriesKcal = _calculatedCaloriesKcal,
                    dataPoints = workoutData.toList(),
                    pauseEvents = pauseEvents.toList(),
                    hrSensors = hrSensors.toList(),
                    rrIntervalsBySensor = getRrIntervalsBySensor()
                )
            }
        }
    }

    /**
     * Restore recorder state from persisted data.
     */
    fun restoreFromState(state: RecorderState) {
        synchronized(workoutData) {
            synchronized(pauseEvents) {
                workoutData.clear()
                workoutData.addAll(state.dataPoints)
                pauseEvents.clear()
                pauseEvents.addAll(state.pauseEvents)
                workoutStartTimeMs = state.workoutStartTimeMs
                lastRecordTimeMs = state.lastRecordTimeMs
                workoutStartTreadmillSeconds = state.workoutStartTreadmillSeconds
                lastTreadmillElapsedSeconds = state.lastTreadmillElapsedSeconds
                _isRecording = state.isRecording
                _calculatedDistanceKm = state.calculatedDistanceKm
                _calculatedElevationGainM = state.calculatedElevationGainM
                _calculatedCaloriesKcal = state.calculatedCaloriesKcal
                hrSensors.clear()
                hrSensorMacToIndex.clear()
                hrSensors.addAll(state.hrSensors)
                state.hrSensors.forEachIndexed { i, (mac, _) -> hrSensorMacToIndex[mac] = i }
                // Restore per-sensor RR intervals
                rrIntervalsBySensor.clear()
                state.rrIntervalsBySensor.forEach { (mac, intervals) ->
                    rrIntervalsBySensor[mac] = java.util.Collections.synchronizedList(intervals.toMutableList())
                }
                // Set lastRecordedElapsedSeconds based on restored data
                lastRecordedElapsedSeconds = if (state.dataPoints.isNotEmpty()) {
                    (state.dataPoints.last().elapsedMs / 1000).toInt()
                } else {
                    -1
                }
                Log.d(TAG, "Restored recorder state: dataPoints=${state.dataPoints.size}, " +
                    "rrSensors=${rrIntervalsBySensor.size}, isRecording=$_isRecording")
            }
        }
    }

    /**
     * Calculate calories burned for a time interval using the best available method.
     *
     * Priority:
     * 1. Power-based (if Stryd power > 0) - most accurate for running
     * 2. HR-based (if heart rate > 0) - requires age, sex, weight
     * 3. ACSM running equation (fallback) - uses speed, incline, weight
     *
     * @return Calories burned in this interval (kcal)
     */
    private fun calculateCaloriesIncrement(
        timeDeltaMs: Long,
        speedKph: Double,
        inclinePercent: Double,
        heartRateBpm: Double,
        powerWatts: Double
    ): Double {
        val timeMinutes = timeDeltaMs / 60000.0

        // Method 1: Power-based (most accurate when Stryd available)
        // Energy (kJ) = Power (W) × Time (s) / 1000
        // Calories (kcal) = Energy (kJ) / 4.184
        // Running efficiency is ~25%, so gross calories = mechanical work / 0.25
        if (powerWatts > 0) {
            val timeSeconds = timeDeltaMs / 1000.0
            val mechanicalWorkKj = powerWatts * timeSeconds / 1000.0
            val grossCalories = mechanicalWorkKj / 4.184 / 0.25
            return grossCalories
        }

        // Method 2: HR-based (when HR available but no power)
        // Keytel et al. (2005) equations:
        // Men: Cal/min = (-55.0969 + 0.6309×HR + 0.1988×weight + 0.2017×age) / 4.184
        // Women: Cal/min = (-20.4022 + 0.4472×HR - 0.1263×weight + 0.074×age) / 4.184
        if (heartRateBpm > 0) {
            val calPerMin = if (userIsMale) {
                (-55.0969 + 0.6309 * heartRateBpm + 0.1988 * userWeightKg + 0.2017 * userAge) / 4.184
            } else {
                (-20.4022 + 0.4472 * heartRateBpm - 0.1263 * userWeightKg + 0.074 * userAge) / 4.184
            }
            // Ensure non-negative (formula can go negative at very low HR)
            return (calPerMin * timeMinutes).coerceAtLeast(0.0)
        }

        // Method 3: ACSM Running Equation (fallback when no HR or power)
        // VO2 (ml/kg/min) = 3.5 + 0.2 × speed(m/min) + 0.9 × speed(m/min) × grade(decimal)
        // Calories/min = VO2 × weight(kg) / 200
        if (speedKph > 0) {
            val speedMPerMin = speedKph * 1000.0 / 60.0  // Convert km/h to m/min
            val grade = inclinePercent / 100.0  // Convert percent to decimal
            val vo2 = 3.5 + 0.2 * speedMPerMin + 0.9 * speedMPerMin * grade
            val calPerMin = vo2 * userWeightKg / 200.0
            return calPerMin * timeMinutes
        }

        return 0.0
    }
}
