package io.github.avikulin.thud.service

import android.content.Context
import android.util.Log
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import io.github.avikulin.thud.domain.model.AdjustmentScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Run type for persistence.
 */
enum class PersistedRunType {
    FREE_RUN,
    STRUCTURED
}

/**
 * Data class for persisted recorder state.
 */
data class RecorderState(
    val workoutStartTimeMs: Long,
    val lastRecordTimeMs: Long,
    val workoutStartTreadmillSeconds: Int,
    val lastTreadmillElapsedSeconds: Int,
    val isRecording: Boolean,
    val calculatedDistanceKm: Double,
    val calculatedElevationGainM: Double,
    val calculatedCaloriesKcal: Double,
    val dataPoints: List<WorkoutDataPoint>,
    val pauseEvents: List<PauseEvent>
)

/**
 * Data class for persisted engine state.
 */
data class EnginePersistenceState(
    val workoutId: Long,
    val currentStepIndex: Int,
    val plannedStepsCount: Int,
    val inAutoCooldown: Boolean,
    val workoutStartSeconds: Int,
    val stepStartSeconds: Int,
    val totalPausedSeconds: Int,
    val workoutStartDistanceKm: Double,
    val stepStartDistanceKm: Double,
    val lastDistanceKm: Double,
    val speedAdjustmentCoefficient: Double,
    val inclineAdjustmentCoefficient: Double,
    val hasReachedTargetSpeed: Boolean,
    val adjustmentScope: AdjustmentScope = AdjustmentScope.ALL_STEPS,
    val stepCoefficients: Map<String, Pair<Double, Double>>? = null
)

/**
 * Data class for persisted adjustment controller state.
 */
data class AdjustmentControllerState(
    val stepStartTimeMs: Long,
    val settlingStartTimeMs: Long,
    val lastAdjustmentTimeMs: Long,
    val lastEvaluationTimeMs: Long
)

/**
 * Complete persisted run state.
 */
data class PersistedRunState(
    val version: Int,
    val persistedAt: Long,
    val runType: PersistedRunType,
    val recorderState: RecorderState,
    val engineState: EnginePersistenceState?,
    val adjustmentControllerState: AdjustmentControllerState?
)

/**
 * Manages persistence of run state to disk for crash recovery.
 * Uses atomic writes (temp file + rename) to prevent corruption.
 */
class RunPersistenceManager(private val context: Context) {

    companion object {
        private const val TAG = "RunPersistenceManager"
        private const val PERSISTENCE_FILE = "active_run.json"
        private const val PERSISTENCE_FILE_TEMP = "active_run.json.tmp"
        private const val PERSISTENCE_VERSION = 1

        // Max age before auto-discarding persisted data (24 hours)
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private val persistenceFile: File
        get() = File(context.filesDir, PERSISTENCE_FILE)

    private val tempFile: File
        get() = File(context.filesDir, PERSISTENCE_FILE_TEMP)

    /**
     * Persist the current run state to disk.
     * Uses atomic write pattern: write to temp file, then rename.
     */
    fun persistRunState(
        recorderState: RecorderState,
        engineState: EnginePersistenceState?,
        adjustmentControllerState: AdjustmentControllerState?,
        runType: PersistedRunType
    ) {
        try {
            val json = JSONObject().apply {
                put("v", PERSISTENCE_VERSION)
                put("at", System.currentTimeMillis())
                put("type", runType.name)
                put("rec", serializeRecorderState(recorderState))
                if (engineState != null) {
                    put("eng", serializeEngineState(engineState))
                }
                if (adjustmentControllerState != null) {
                    put("adj", serializeAdjustmentControllerState(adjustmentControllerState))
                }
            }

            // Atomic write: write to temp, then rename
            tempFile.writeText(json.toString())
            tempFile.renameTo(persistenceFile)

            Log.d(TAG, "Persisted run state: type=$runType, dataPoints=${recorderState.dataPoints.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist run state: ${e.message}", e)
        }
    }

    /**
     * Check if there is a persisted run that can be resumed.
     */
    fun hasPersistedRun(): Boolean {
        if (!persistenceFile.exists()) return false

        // Check if file is not too old
        val state = loadPersistedRun()
        if (state == null) return false

        val age = System.currentTimeMillis() - state.persistedAt
        if (age > MAX_AGE_MS) {
            Log.d(TAG, "Persisted run is too old (${age / 3600000}h), discarding")
            clearPersistedRun()
            return false
        }

        return true
    }

    /**
     * Load the persisted run state from disk.
     * Returns null if no valid state exists or if parsing fails.
     */
    fun loadPersistedRun(): PersistedRunState? {
        if (!persistenceFile.exists()) return null

        return try {
            val jsonString = persistenceFile.readText()
            val json = JSONObject(jsonString)

            val version = json.optInt("v", 0)
            if (version != PERSISTENCE_VERSION) {
                Log.w(TAG, "Version mismatch: $version != $PERSISTENCE_VERSION, discarding")
                clearPersistedRun()
                return null
            }

            val persistedAt = json.getLong("at")
            val runType = PersistedRunType.valueOf(json.getString("type"))
            val recorderState = deserializeRecorderState(json.getJSONObject("rec"))
            val engineState = if (json.has("eng")) {
                deserializeEngineState(json.getJSONObject("eng"))
            } else null
            val adjustmentControllerState = if (json.has("adj")) {
                deserializeAdjustmentControllerState(json.getJSONObject("adj"))
            } else null

            Log.d(TAG, "Loaded persisted run: type=$runType, dataPoints=${recorderState.dataPoints.size}")

            PersistedRunState(
                version = version,
                persistedAt = persistedAt,
                runType = runType,
                recorderState = recorderState,
                engineState = engineState,
                adjustmentControllerState = adjustmentControllerState
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted run: ${e.message}", e)
            clearPersistedRun()
            null
        }
    }

    /**
     * Clear the persisted run state.
     */
    fun clearPersistedRun() {
        try {
            if (persistenceFile.exists()) {
                persistenceFile.delete()
                Log.d(TAG, "Cleared persisted run state")
            }
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted run: ${e.message}", e)
        }
    }

    // ==================== Serialization ====================

    private fun serializeRecorderState(state: RecorderState): JSONObject {
        return JSONObject().apply {
            put("wst", state.workoutStartTimeMs)
            put("lrt", state.lastRecordTimeMs)
            put("wsts", state.workoutStartTreadmillSeconds)
            put("ltes", state.lastTreadmillElapsedSeconds)
            put("rec", state.isRecording)
            put("dist", state.calculatedDistanceKm)
            put("elev", state.calculatedElevationGainM)
            put("cal", state.calculatedCaloriesKcal)
            put("dp", serializeDataPoints(state.dataPoints))
            put("pe", serializePauseEvents(state.pauseEvents))
        }
    }

    private fun serializeDataPoints(dataPoints: List<WorkoutDataPoint>): JSONArray {
        return JSONArray().apply {
            dataPoints.forEach { dp ->
                put(JSONObject().apply {
                    put("ts", dp.timestampMs)
                    put("el", dp.elapsedMs)
                    put("sp", dp.speedKph)
                    put("in", dp.inclinePercent)
                    put("hr", dp.heartRateBpm)
                    put("di", dp.distanceKm)
                    put("ev", dp.elevationGainM)
                    put("ca", dp.caloriesKcal)
                    put("pw", dp.powerWatts)
                    put("rp", dp.rawPowerWatts)
                    put("ip", dp.inclinePowerWatts)
                    put("cd", dp.cadenceSpm)
                    put("si", dp.stepIndex)
                    put("sn", dp.stepName)
                })
            }
        }
    }

    private fun serializePauseEvents(events: List<PauseEvent>): JSONArray {
        return JSONArray().apply {
            events.forEach { pe ->
                put(JSONObject().apply {
                    put("ts", pe.timestampMs)
                    put("p", pe.isPause)
                })
            }
        }
    }

    private fun serializeEngineState(state: EnginePersistenceState): JSONObject {
        return JSONObject().apply {
            put("wid", state.workoutId)
            put("csi", state.currentStepIndex)
            put("psc", state.plannedStepsCount)
            put("iac", state.inAutoCooldown)
            put("wss", state.workoutStartSeconds)
            put("sss", state.stepStartSeconds)
            put("tps", state.totalPausedSeconds)
            put("wsd", state.workoutStartDistanceKm)
            put("ssd", state.stepStartDistanceKm)
            put("ldk", state.lastDistanceKm)
            put("sac", state.speedAdjustmentCoefficient)
            put("iac2", state.inclineAdjustmentCoefficient)
            put("hrts", state.hasReachedTargetSpeed)
            put("as", state.adjustmentScope.name)
            if (!state.stepCoefficients.isNullOrEmpty()) {
                val scm = JSONObject()
                state.stepCoefficients.forEach { (key, pair) ->
                    scm.put(key, JSONArray().apply { put(pair.first); put(pair.second) })
                }
                put("scm", scm)
            }
        }
    }

    private fun serializeAdjustmentControllerState(state: AdjustmentControllerState): JSONObject {
        return JSONObject().apply {
            put("sst", state.stepStartTimeMs)
            put("set", state.settlingStartTimeMs)
            put("lat", state.lastAdjustmentTimeMs)
            put("let", state.lastEvaluationTimeMs)
        }
    }

    // ==================== Deserialization ====================

    private fun deserializeRecorderState(json: JSONObject): RecorderState {
        return RecorderState(
            workoutStartTimeMs = json.getLong("wst"),
            lastRecordTimeMs = json.getLong("lrt"),
            workoutStartTreadmillSeconds = json.getInt("wsts"),
            lastTreadmillElapsedSeconds = json.getInt("ltes"),
            isRecording = json.getBoolean("rec"),
            calculatedDistanceKm = json.getDouble("dist"),
            calculatedElevationGainM = json.getDouble("elev"),
            calculatedCaloriesKcal = json.getDouble("cal"),
            dataPoints = deserializeDataPoints(json.getJSONArray("dp")),
            pauseEvents = deserializePauseEvents(json.getJSONArray("pe"))
        )
    }

    private fun deserializeDataPoints(jsonArray: JSONArray): List<WorkoutDataPoint> {
        return (0 until jsonArray.length()).map { i ->
            val dp = jsonArray.getJSONObject(i)
            WorkoutDataPoint(
                timestampMs = dp.getLong("ts"),
                elapsedMs = dp.getLong("el"),
                speedKph = dp.getDouble("sp"),
                inclinePercent = dp.getDouble("in"),
                heartRateBpm = dp.getDouble("hr"),
                distanceKm = dp.getDouble("di"),
                elevationGainM = dp.getDouble("ev"),
                caloriesKcal = dp.optDouble("ca", 0.0),
                powerWatts = dp.optDouble("pw", 0.0),
                rawPowerWatts = dp.optDouble("rp", 0.0),
                inclinePowerWatts = dp.optDouble("ip", 0.0),
                cadenceSpm = dp.optInt("cd", 0),
                stepIndex = dp.optInt("si", -1),
                stepName = dp.optString("sn", "")
            )
        }
    }

    private fun deserializePauseEvents(jsonArray: JSONArray): List<PauseEvent> {
        return (0 until jsonArray.length()).map { i ->
            val pe = jsonArray.getJSONObject(i)
            PauseEvent(
                timestampMs = pe.getLong("ts"),
                isPause = pe.getBoolean("p")
            )
        }
    }

    private fun deserializeEngineState(json: JSONObject): EnginePersistenceState {
        val adjustmentScope = if (json.has("as")) {
            try { AdjustmentScope.valueOf(json.getString("as")) }
            catch (_: Exception) { AdjustmentScope.ALL_STEPS }
        } else AdjustmentScope.ALL_STEPS

        val stepCoefficients = if (json.has("scm")) {
            val scm = json.getJSONObject("scm")
            val map = mutableMapOf<String, Pair<Double, Double>>()
            scm.keys().forEach { key ->
                val arr = scm.getJSONArray(key)
                if (arr.length() == 2) {
                    map[key] = Pair(arr.getDouble(0), arr.getDouble(1))
                }
            }
            map.ifEmpty { null }
        } else null

        return EnginePersistenceState(
            workoutId = json.getLong("wid"),
            currentStepIndex = json.getInt("csi"),
            plannedStepsCount = json.getInt("psc"),
            inAutoCooldown = json.getBoolean("iac"),
            workoutStartSeconds = json.getInt("wss"),
            stepStartSeconds = json.getInt("sss"),
            totalPausedSeconds = json.getInt("tps"),
            workoutStartDistanceKm = json.getDouble("wsd"),
            stepStartDistanceKm = json.getDouble("ssd"),
            lastDistanceKm = json.getDouble("ldk"),
            speedAdjustmentCoefficient = json.getDouble("sac"),
            inclineAdjustmentCoefficient = json.getDouble("iac2"),
            hasReachedTargetSpeed = json.getBoolean("hrts"),
            adjustmentScope = adjustmentScope,
            stepCoefficients = stepCoefficients
        )
    }

    private fun deserializeAdjustmentControllerState(json: JSONObject): AdjustmentControllerState {
        return AdjustmentControllerState(
            stepStartTimeMs = json.getLong("sst"),
            settlingStartTimeMs = json.getLong("set"),
            lastAdjustmentTimeMs = json.getLong("lat"),
            lastEvaluationTimeMs = json.getLong("let")
        )
    }
}
