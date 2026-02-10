package io.github.avikulin.thud.util

import android.content.Context
import android.util.Log
import io.github.avikulin.thud.service.PauseEvent
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import io.github.avikulin.thud.domain.engine.ExecutionStep
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.service.SettingsManager
import com.garmin.fit.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exports workout data to Garmin FIT format files.
 * Files are saved to Downloads/tHUD folder using MediaStore for Android 10+ compatibility.
 * Files are accessible to other apps for manual upload to Garmin Connect.
 */
class FitFileExporter(private val context: Context) {

    /**
     * Result of a successful FIT export, containing both the display path
     * (for user feedback) and the raw bytes (for upload to Garmin Connect).
     * FIT files are typically 50-200KB, so holding bytes in memory is negligible.
     */
    data class FitExportResult(
        val displayPath: String,
        val fitData: ByteArray,
        val filename: String
    )

    /**
     * Holds Stryd developer field definitions for attaching power data as developer fields.
     * These mimic the Stryd Connect IQ app's field format so Stryd PowerCenter recognizes them.
     */
    private data class StrydDevFields(
        val devDataIdMesg: DeveloperDataIdMesg,
        val recordPowerFieldDesc: FieldDescriptionMesg,
        val lapPowerFieldDesc: FieldDescriptionMesg,
        val sessionCpFieldDesc: FieldDescriptionMesg,
        val userFtpWatts: Int
    )

    companion object {
        private const val TAG = "FitFileExporter"
        private const val MIME_TYPE = "application/octet-stream"

        // FIT timestamp epoch: 1989-12-31 00:00:00 UTC
        private const val FIT_EPOCH_OFFSET_MS = 631065600000L

        // Default device info (Forerunner 970)
        // IMPORTANT: Serial MUST differ from user's watch for acute/chronic load sync to work.
        // Manufacturer: 1=Garmin (works with Strava + Stryd via developer fields), 89=Tacx (works with Stryd natively)
        private const val DEFAULT_MANUFACTURER = 1          // Garmin
        private const val DEFAULT_PRODUCT_ID = 4565         // Forerunner 970
        private const val DEFAULT_DEVICE_SERIAL = 1234567890L
        private const val DEFAULT_SOFTWARE_VERSION = 1552   // 15.52

        // Stryd Connect IQ app UUID: 18fb2cf0-1a4b-430d-ad66-988c847421f4
        // Used for developer fields so Stryd PowerCenter recognizes power data.
        private val STRYD_APP_UUID_BYTES = byteArrayOf(
            0x18, 0xFB.toByte(), 0x2C, 0xF0.toByte(),
            0x1A, 0x4B, 0x43, 0x0D,
            0xAD.toByte(), 0x66, 0x98.toByte(), 0x8C.toByte(),
            0x84.toByte(), 0x74, 0x21, 0xF4.toByte()
        )
        private const val STRYD_APP_VERSION = 158L
        private const val STRYD_DEV_DATA_INDEX: Short = 0

        // Stryd field definition numbers (must match Stryd Connect IQ app exactly)
        private const val STRYD_FIELD_POWER: Short = 0          // Record-level power
        private const val STRYD_FIELD_LAP_POWER: Short = 10     // Lap-level avg power
        private const val STRYD_FIELD_CP: Short = 99            // Session-level Critical Power
    }

    /**
     * Export workout data to a FIT file.
     *
     * @param workoutData List of recorded data points
     * @param workoutName Name of the workout (used in filename)
     * @param startTimeMs Workout start time in milliseconds (System.currentTimeMillis())
     * @param userHrRest User's resting heart rate (for training metrics calculation)
     * @param userLthr Lactate threshold HR (for hrTSS calculation, typically zone 4 max)
     * @param userFtpWatts Functional Threshold Power (for power-based TSS)
     * @param thresholdPaceKph Lactate threshold pace in kph (for pace-based TSS fallback)
     * @param userIsMale User's sex (for training metrics calculation)
     * @param pauseEvents List of pause/resume events for timer time calculation
     * @param executionSteps List of flattened execution steps for runtime (null for free runs)
     * @param originalSteps Original hierarchical workout steps for FIT export (preserves REPEAT structure)
     * @return FitExportResult with display path and raw bytes if successful, null otherwise
     */
    fun exportWorkout(
        workoutData: List<WorkoutDataPoint>,
        workoutName: String,
        startTimeMs: Long,
        userHrRest: Int = 45,
        userLthr: Int = 170,
        userFtpWatts: Int = 305,
        thresholdPaceKph: Double = 12.0,
        userIsMale: Boolean = true,
        pauseEvents: List<PauseEvent> = emptyList(),
        executionSteps: List<ExecutionStep>? = null,
        originalSteps: List<WorkoutStep>? = null,
        fitManufacturer: Int = DEFAULT_MANUFACTURER,
        fitProductId: Int = DEFAULT_PRODUCT_ID,
        fitDeviceSerial: Long = DEFAULT_DEVICE_SERIAL,
        fitSoftwareVersion: Int = DEFAULT_SOFTWARE_VERSION
    ): FitExportResult? {
        if (workoutData.isEmpty()) {
            Log.w(TAG, "No workout data to export")
            return null
        }

        return try {
            val filename = createFilename(workoutName, startTimeMs)
            val result = writeFitFileToMediaStore(
                filename, workoutData, workoutName, startTimeMs,
                userHrRest, userLthr, userFtpWatts, thresholdPaceKph, userIsMale, pauseEvents,
                executionSteps, originalSteps,
                fitManufacturer, fitProductId, fitDeviceSerial, fitSoftwareVersion
            )
            Log.i(TAG, "FIT file exported: ${result.displayPath}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export FIT file: ${e.message}", e)
            null
        }
    }

    /**
     * Create filename for the FIT file.
     */
    private fun createFilename(workoutName: String, startTimeMs: Long): String {
        // Create filename: WorkoutName_2026-01-19_14-30-00.fit
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val dateStr = dateFormat.format(Date(startTimeMs))
        val sanitizedName = workoutName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "${sanitizedName}_$dateStr.fit"
    }

    /**
     * Write FIT file to MediaStore Downloads folder.
     * FIT SDK requires a File, so we write to temp first then copy to MediaStore.
     * Returns the display path for user feedback.
     */
    private fun writeFitFileToMediaStore(
        filename: String,
        workoutData: List<WorkoutDataPoint>,
        workoutName: String,
        startTimeMs: Long,
        userHrRest: Int,
        userLthr: Int,
        userFtpWatts: Int,
        thresholdPaceKph: Double,
        userIsMale: Boolean,
        pauseEvents: List<PauseEvent>,
        executionSteps: List<ExecutionStep>?,
        originalSteps: List<WorkoutStep>?,
        fitManufacturer: Int,
        fitProductId: Int,
        fitDeviceSerial: Long,
        fitSoftwareVersion: Int
    ): FitExportResult {
        // Create temp file for FIT SDK (it requires a File, not OutputStream)
        val tempFile = FileExportHelper.getTempFile(context, filename)
        try {
            // Write FIT file to temp location
            writeFitFile(tempFile, workoutData, workoutName, startTimeMs, userHrRest, userLthr, userFtpWatts, thresholdPaceKph, userIsMale, pauseEvents, executionSteps, originalSteps, fitManufacturer, fitProductId, fitDeviceSerial, fitSoftwareVersion)

            // Read bytes before saving to MediaStore (for Garmin Connect upload)
            val fitData = tempFile.readBytes()

            // Copy to MediaStore Downloads using shared helper
            val displayPath = FileExportHelper.saveToDownloads(
                context = context,
                sourceFile = tempFile,
                filename = filename,
                mimeType = MIME_TYPE
            ) ?: throw IllegalStateException("Failed to save to MediaStore")

            return FitExportResult(
                displayPath = displayPath,
                fitData = fitData,
                filename = filename
            )
        } finally {
            // Clean up temp file
            tempFile.delete()
        }
    }

    /**
     * Write FIT file with all required messages.
     */
    private fun writeFitFile(
        file: File,
        workoutData: List<WorkoutDataPoint>,
        workoutName: String,
        startTimeMs: Long,
        userHrRest: Int,
        userLthr: Int,
        userFtpWatts: Int,
        thresholdPaceKph: Double,
        userIsMale: Boolean,
        pauseEvents: List<PauseEvent>,
        executionSteps: List<ExecutionStep>?,
        originalSteps: List<WorkoutStep>?,
        fitManufacturer: Int,
        fitProductId: Int,
        fitDeviceSerial: Long,
        fitSoftwareVersion: Int
    ) {
        val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)

        // Calculate summary data
        val endTimeMs = workoutData.last().timestampMs
        val totalDurationMs = endTimeMs - startTimeMs
        val totalDistanceM = workoutData.last().distanceKm * 1000.0
        val totalAscent = workoutData.last().elevationGainM
        val avgHeartRate = workoutData.filter { it.heartRateBpm > 0 }
            .map { it.heartRateBpm }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val maxHeartRate = workoutData.maxOfOrNull { it.heartRateBpm } ?: 0.0
        val avgSpeed = if (totalDurationMs > 0) {
            totalDistanceM / (totalDurationMs / 1000.0) // m/s
        } else 0.0
        val maxSpeed = workoutData.maxOfOrNull { it.speedKph }?.let { it / 3.6 } ?: 0.0

        // Power metrics from foot pod
        val avgPower = workoutData.filter { it.powerWatts > 0 }
            .map { it.powerWatts }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val maxPower = workoutData.maxOfOrNull { it.powerWatts } ?: 0.0

        // Cadence metrics from foot pod (stored as strides/min, same as FIT format)
        val avgCadence = workoutData.filter { it.cadenceSpm > 0 }
            .map { it.cadenceSpm.toDouble() }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val maxCadence = workoutData.maxOfOrNull { it.cadenceSpm } ?: 0

        // Calories (cumulative, so take the last value)
        val totalCalories = workoutData.last().caloriesKcal

        // Note: TSS, Load, and TE are NOT written to FIT file
        // Let Garmin calculate them using their Firstbeat algorithms

        // Calculate incline power metrics
        val avgInclinePower = workoutData
            .filter { it.inclinePowerWatts != 0.0 }
            .map { it.inclinePowerWatts }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val avgRawPower = workoutData
            .filter { it.rawPowerWatts > 0 }
            .map { it.rawPowerWatts }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        // 1. File ID message (required first)
        writeFileIdMessage(encoder, startTimeMs, fitManufacturer, fitProductId, fitDeviceSerial)

        // 2. File Creator message
        writeFileCreatorMessage(encoder, fitSoftwareVersion)

        // 3. Device Info message
        writeDeviceInfoMessage(encoder, startTimeMs, fitManufacturer, fitProductId, fitDeviceSerial, fitSoftwareVersion)

        // 3b. Stryd developer fields (only if power data exists)
        val hasPowerData = workoutData.any { it.powerWatts > 0 }
        val strydDevFields = if (hasPowerData) {
            writeStrydDeveloperFieldDefinitions(encoder, userFtpWatts)
        } else null

        // 4. Sport message (required for proper workout recognition)
        writeSportMessage(encoder)

        // 4. Workout definition (structured workouts only)
        // Group data by step to get actual executed steps (reflects Prev/Next button usage)
        val hasStructuredWorkout = workoutData.any { it.stepIndex >= 0 }
        val lapGroups = if (hasStructuredWorkout) StepBoundaryParser.groupByStep(workoutData) else emptyList()

        Log.d(TAG, "Workout steps check: hasStructuredWorkout=$hasStructuredWorkout, " +
            "executionSteps=${executionSteps?.size ?: "null"}, originalSteps=${originalSteps?.size ?: "null"}, lapGroups=${lapGroups.size}")

        // Build mapping from flattened step index to original step index for lap references
        val flatToOriginalStepIndex = if (originalSteps != null) {
            buildFlatToOriginalStepIndexMap(originalSteps)
        } else {
            emptyMap()
        }

        // Workout/WorkoutStep messages disabled - nobody displays them meaningfully,
        // and simple laps display is nicer than Garmin's workout display.
        // if (hasStructuredWorkout && originalSteps != null && originalSteps.isNotEmpty()) {
        //     // Use hierarchical structure with REPEAT steps
        //     writeHierarchicalWorkoutSteps(encoder, workoutName, originalSteps, userLthr, userFtpWatts)
        // } else if (hasStructuredWorkout && executionSteps != null && lapGroups.isNotEmpty()) {
        //     // Fallback to flat structure if no original steps available
        //     writeWorkoutWithSteps(encoder, workoutName, lapGroups, executionSteps, userLthr, userFtpWatts)
        // } else {
        //     Log.w(TAG, "Skipping workout steps: hasStructuredWorkout=$hasStructuredWorkout, " +
        //         "executionSteps=${if (executionSteps == null) "null" else "size=${executionSteps.size}"}, " +
        //         "originalSteps=${if (originalSteps == null) "null" else "size=${originalSteps.size}"}, " +
        //         "lapGroups.size=${lapGroups.size}")
        // }

        // 5. Timer START event (required at workout start)
        writeTimerEvent(encoder, startTimeMs, isStart = true)

        // 6. Record messages interleaved with pause/resume events (in timestamp order)
        writeRecordsWithEvents(encoder, workoutData, pauseEvents, strydDevFields)

        // 7. Timer STOP event at workout end (skip if already paused to avoid duplicate STOP_ALL)
        val endedWhilePaused = pauseEvents.isNotEmpty() &&
            pauseEvents.sortedBy { it.timestampMs }.last().isPause
        if (!endedWhilePaused) {
            writeTimerEvent(encoder, endTimeMs, isStart = false)
        }

        // 8. Lap messages - one per workout step, or single lap for free runs
        val numLaps = writeLapMessages(encoder, workoutData, startTimeMs, pauseEvents, executionSteps, flatToOriginalStepIndex, strydDevFields)

        // Calculate session timer time (excludes pauses)
        val totalPausedMs = calculatePausedDuration(pauseEvents, startTimeMs, endTimeMs)
        val totalTimerMs = (totalDurationMs - totalPausedMs).coerceAtLeast(0)

        // 9. Session message (required)
        writeSessionMessage(
            encoder,
            workoutName = workoutName,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            totalElapsedMs = totalDurationMs,
            totalTimerMs = totalTimerMs,
            totalDistanceM = totalDistanceM,
            totalAscent = totalAscent,
            avgHeartRate = avgHeartRate,
            maxHeartRate = maxHeartRate,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            avgPower = avgPower,
            maxPower = maxPower,
            avgCadence = avgCadence,
            maxCadence = maxCadence,
            totalCalories = totalCalories,
            numLaps = numLaps,
            avgInclinePower = avgInclinePower,
            avgRawPower = avgRawPower,
            strydDevFields = strydDevFields
        )

        // 10. Time in Zone message (HR zone time distribution)
        writeTimeInZoneMessage(
            encoder,
            endTimeMs = endTimeMs,
            workoutData = workoutData,
            userLthr = userLthr,
            userHrRest = userHrRest,
            userFtpWatts = userFtpWatts
        )

        // 11. Activity message (required last before close)
        writeActivityMessage(encoder, startTimeMs, totalDurationMs, totalTimerMs)

        encoder.close()
    }

    /**
     * Convert system time to FIT timestamp (seconds since 1989-12-31 00:00 UTC).
     * Clamps to 0 if system clock is set before FIT epoch to prevent negative timestamps.
     */
    private fun toFitTimestamp(systemTimeMs: Long): Long {
        if (systemTimeMs < FIT_EPOCH_OFFSET_MS) {
            Log.w(TAG, "System time before FIT epoch ($systemTimeMs), clamping to 0")
            return 0L
        }
        return (systemTimeMs - FIT_EPOCH_OFFSET_MS) / 1000
    }

    /**
     * Write a timer start or stop event.
     */
    private fun writeTimerEvent(encoder: FileEncoder, timestampMs: Long, isStart: Boolean) {
        val eventMesg = EventMesg()
        eventMesg.setTimestamp(DateTime(toFitTimestamp(timestampMs)))
        eventMesg.setEvent(Event.TIMER)
        eventMesg.setEventType(if (isStart) EventType.START else EventType.STOP_ALL)
        encoder.write(eventMesg)
    }

    /**
     * Write record messages interleaved with pause/resume events in timestamp order.
     * This is required for FIT file compliance - events must be in chronological order.
     */
    private fun writeRecordsWithEvents(
        encoder: FileEncoder,
        workoutData: List<WorkoutDataPoint>,
        pauseEvents: List<PauseEvent>,
        strydDevFields: StrydDevFields? = null
    ) {
        // Create a combined list of timestamped items
        data class TimestampedItem(
            val timestampMs: Long,
            val isRecord: Boolean,
            val dataPoint: WorkoutDataPoint? = null,
            val pauseEvent: PauseEvent? = null
        )

        val items = mutableListOf<TimestampedItem>()

        // Add all records
        for (dp in workoutData) {
            items.add(TimestampedItem(dp.timestampMs, isRecord = true, dataPoint = dp))
        }

        // Add all pause events
        for (pe in pauseEvents) {
            items.add(TimestampedItem(pe.timestampMs, isRecord = false, pauseEvent = pe))
        }

        // Sort by timestamp
        items.sortBy { it.timestampMs }

        // Write in order
        for (item in items) {
            if (item.isRecord) {
                writeRecordMessage(encoder, item.dataPoint!!, strydDevFields)
            } else {
                val pe = item.pauseEvent!!
                val eventMesg = EventMesg()
                eventMesg.setTimestamp(DateTime(toFitTimestamp(pe.timestampMs)))
                eventMesg.setEvent(Event.TIMER)
                eventMesg.setEventType(if (pe.isPause) EventType.STOP_ALL else EventType.START)
                encoder.write(eventMesg)
            }
        }

        if (pauseEvents.isNotEmpty()) {
            Log.d(TAG, "Wrote ${workoutData.size} records interleaved with ${pauseEvents.size} pause/resume events")
        }
    }

    /**
     * Calculate total paused duration (in ms) within a time range.
     * Handles partial overlaps where a pause started before or ended after the range.
     */
    private fun calculatePausedDuration(
        pauseEvents: List<PauseEvent>,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Long {
        if (pauseEvents.isEmpty()) return 0L

        var totalPausedMs = 0L
        var pauseStartMs: Long? = null

        for (event in pauseEvents.sortedBy { it.timestampMs }) {
            if (event.isPause) {
                // Start of a pause
                pauseStartMs = event.timestampMs
            } else if (pauseStartMs != null) {
                // End of a pause - calculate overlap with our range
                val pauseEndMs = event.timestampMs

                // Calculate overlap: max(rangeStart, pauseStart) to min(rangeEnd, pauseEnd)
                val overlapStart = maxOf(rangeStartMs, pauseStartMs)
                val overlapEnd = minOf(rangeEndMs, pauseEndMs)

                if (overlapStart < overlapEnd) {
                    totalPausedMs += overlapEnd - overlapStart
                }

                pauseStartMs = null
            }
        }

        // Handle case where pause is still active at end of range
        if (pauseStartMs != null && pauseStartMs < rangeEndMs) {
            val overlapStart = maxOf(rangeStartMs, pauseStartMs)
            totalPausedMs += rangeEndMs - overlapStart
        }

        return totalPausedMs
    }

    /**
     * Write lap messages - one per workout step, or single lap for free runs.
     * Returns the number of laps written.
     *
     * @param flatToOriginalStepIndex Maps flattened step index to original FIT step index.
     *                                Used so repeated steps reference the same original step.
     */
    private fun writeLapMessages(
        encoder: FileEncoder,
        workoutData: List<WorkoutDataPoint>,
        workoutStartTimeMs: Long,
        pauseEvents: List<PauseEvent>,
        executionSteps: List<ExecutionStep>?,
        flatToOriginalStepIndex: Map<Int, Int> = emptyMap(),
        strydDevFields: StrydDevFields? = null
    ): Int {
        // Group data points by step index
        // stepIndex == -1 means free run (no structured workout)
        val hasStructuredWorkout = workoutData.any { it.stepIndex >= 0 }

        if (!hasStructuredWorkout) {
            // Free run - write single lap for entire workout
            writeSingleLap(encoder, workoutData, workoutStartTimeMs, LapTrigger.SESSION_END, pauseEvents, lapIndex = 0, execStep = null, strydDevFields = strydDevFields)
            return 1
        }

        // Structured workout - create lap for each step using shared parser
        val lapGroups = StepBoundaryParser.groupByStep(workoutData)

        // Write a lap for each group
        lapGroups.forEachIndexed { index, lapData ->
            val isLastLap = index == lapGroups.size - 1
            // Get step index from data - this links the lap to the workout step
            val flatStepIndex = lapData.firstOrNull()?.stepIndex?.takeIf { it >= 0 }
            val execStep = flatStepIndex?.let { executionSteps?.getOrNull(it) }

            // Map flattened step index to original FIT step index
            // This allows repeated steps to reference the same original step
            val wktStepIndex = if (flatStepIndex != null && flatToOriginalStepIndex.isNotEmpty()) {
                flatToOriginalStepIndex[flatStepIndex]
            } else {
                flatStepIndex
            }

            // Determine lap trigger based on step duration type
            // Use TIME for time-based steps, DISTANCE for distance-based, SESSION_END for last
            val lapTrigger = when {
                isLastLap -> LapTrigger.SESSION_END
                execStep?.durationType == DurationType.TIME -> LapTrigger.TIME
                execStep?.durationType == DurationType.DISTANCE -> LapTrigger.DISTANCE
                else -> LapTrigger.MANUAL
            }

            // Get next lap's first data point for duration/distance calculation
            // Lap duration = next_lap_start - this_lap_start (not last_point - first_point + 1)
            val nextLapFirstPoint = if (!isLastLap) lapGroups[index + 1].firstOrNull() else null

            writeSingleLap(encoder, lapData, workoutStartTimeMs, lapTrigger, pauseEvents, lapIndex = index, wktStepIndex = wktStepIndex, execStep = execStep, nextLapFirstPoint = nextLapFirstPoint, strydDevFields = strydDevFields)
        }

        return lapGroups.size
    }

    /**
     * Write a single lap message for the given data points.
     *
     * @param nextLapFirstPoint First data point of the next lap, used to calculate this lap's
     *                          duration and distance. Lap duration = next_start - this_start.
     */
    private fun writeSingleLap(
        encoder: FileEncoder,
        lapData: List<WorkoutDataPoint>,
        workoutStartTimeMs: Long,
        lapTrigger: LapTrigger,
        pauseEvents: List<PauseEvent>,
        lapIndex: Int,
        wktStepIndex: Int? = null,
        execStep: ExecutionStep? = null,
        nextLapFirstPoint: WorkoutDataPoint? = null,
        strydDevFields: StrydDevFields? = null
    ) {
        if (lapData.isEmpty()) return

        val lapStartTimeMs = lapData.first().timestampMs
        // For duration calculation: use next lap's start if available, otherwise use last point
        val lapEndTimeMs = nextLapFirstPoint?.timestampMs ?: lapData.last().timestampMs

        // Lap duration = next_lap_first_timestamp - this_lap_first_timestamp
        // Single source of truth: actual measured duration from recording timestamps
        val lapElapsedMs = if (nextLapFirstPoint != null) {
            lapEndTimeMs - lapStartTimeMs  // next_start - this_start
        } else {
            lapData.last().timestampMs - lapStartTimeMs  // last lap: use last point
        }

        // Calculate timer time by excluding paused duration
        val pausedDurationMs = calculatePausedDuration(pauseEvents, lapStartTimeMs, lapEndTimeMs)
        val lapTimerMs = (lapElapsedMs - pausedDurationMs).coerceAtLeast(0)

        // Calculate distance for this lap
        // Use next lap's first point if available (same logic as duration)
        val lapStartDistanceM = lapData.first().distanceKm * 1000.0
        val lapEndDistanceM = if (nextLapFirstPoint != null) {
            nextLapFirstPoint.distanceKm * 1000.0
        } else {
            lapData.last().distanceKm * 1000.0
        }
        val lapDistanceM = lapEndDistanceM - lapStartDistanceM

        // Calculate elevation for this lap
        val lapStartElevation = lapData.first().elevationGainM
        val lapEndElevation = lapData.last().elevationGainM
        val lapAscent = (lapEndElevation - lapStartElevation).coerceAtLeast(0.0)

        // Calculate averages and max for this lap
        val avgHeartRate = lapData.filter { it.heartRateBpm > 0 }
            .map { it.heartRateBpm }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val maxHeartRate = lapData.maxOfOrNull { it.heartRateBpm } ?: 0.0

        // Average incline for this lap
        val avgIncline = lapData.map { it.inclinePercent }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        // Average speed in kph (from recorded data points, not distance/time)
        val avgSpeedKph = lapData.filter { it.speedKph > 0 }
            .map { it.speedKph }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        // Use timer time for speed calculation (excludes pauses) - for FIT file
        val avgSpeed = if (lapTimerMs > 0) {
            lapDistanceM / (lapTimerMs / 1000.0)
        } else 0.0
        val maxSpeed = lapData.maxOfOrNull { it.speedKph }?.let { it / 3.6 } ?: 0.0

        val avgPower = lapData.filter { it.powerWatts > 0 }
            .map { it.powerWatts }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val maxPower = lapData.maxOfOrNull { it.powerWatts } ?: 0.0

        val avgCadence = lapData.filter { it.cadenceSpm > 0 }
            .map { it.cadenceSpm.toDouble() }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val maxCadence = lapData.maxOfOrNull { it.cadenceSpm } ?: 0

        // Incline power metrics for calibration
        val avgInclinePower = lapData
            .filter { it.inclinePowerWatts != 0.0 }
            .map { it.inclinePowerWatts }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val avgRawPower = lapData
            .filter { it.rawPowerWatts > 0 }
            .map { it.rawPowerWatts }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        // Calories for this lap (difference between first and last point)
        val lapStartCalories = lapData.first().caloriesKcal
        val lapEndCalories = lapData.last().caloriesKcal
        val lapCalories = lapEndCalories - lapStartCalories

        // Get step name if available
        val stepName = lapData.firstOrNull { it.stepName.isNotEmpty() }?.stepName ?: "Lap"

        // Log comprehensive lap stats with unique prefix for filtering
        // Format: TREADMILL_LAP_STATS | lap=N | step=NAME | duration=Xs | distance=Xm | speed=X.Xkph | incline=X.X% | hr=X | power=X(raw=X+incline=X) | cadence=X
        Log.i("TREADMILL_LAP_STATS", "lap=$lapIndex | step=$stepName | " +
            "duration=${lapTimerMs / 1000}s | distance=${lapDistanceM.toInt()}m | " +
            "speed=%.1fkph | incline=%.1f%% | hr=%.0f | ".format(avgSpeedKph, avgIncline, avgHeartRate) +
            "power=%.0f(raw=%.0f+incline=%.0f) | cadence=%.0f".format(avgPower, avgRawPower, avgInclinePower, avgCadence))

        // Determine intensity from step type
        val intensity = when (execStep?.type) {
            StepType.WARMUP -> Intensity.WARMUP
            StepType.COOLDOWN -> Intensity.COOLDOWN
            StepType.REST -> Intensity.REST
            StepType.RECOVER -> Intensity.RECOVERY
            StepType.RUN -> Intensity.ACTIVE
            else -> Intensity.ACTIVE
        }

        writeLapMessage(
            encoder,
            startTimeMs = lapStartTimeMs,
            endTimeMs = lapEndTimeMs,
            totalElapsedMs = lapElapsedMs,
            totalTimerMs = lapTimerMs,
            totalDistanceM = lapDistanceM,
            totalAscent = lapAscent,
            avgHeartRate = avgHeartRate,
            maxHeartRate = maxHeartRate,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            avgPower = avgPower,
            maxPower = maxPower,
            avgCadence = avgCadence,
            maxCadence = maxCadence,
            totalCalories = lapCalories,
            lapTrigger = lapTrigger,
            avgInclinePower = avgInclinePower,
            avgRawPower = avgRawPower,
            wktStepIndex = wktStepIndex,
            intensity = intensity,
            messageIndex = lapIndex,
            strydDevFields = strydDevFields
        )
    }

    private fun writeFileIdMessage(
        encoder: FileEncoder,
        startTimeMs: Long,
        manufacturer: Int,
        productId: Int,
        serialNumber: Long
    ) {
        val fileId = FileIdMesg()
        fileId.setType(com.garmin.fit.File.ACTIVITY)
        fileId.setManufacturer(manufacturer)
        fileId.setProduct(productId)
        fileId.setSerialNumber(serialNumber)
        fileId.setTimeCreated(DateTime(toFitTimestamp(startTimeMs)))
        encoder.write(fileId)
    }

    private fun writeFileCreatorMessage(encoder: FileEncoder, softwareVersion: Int) {
        val fileCreator = FileCreatorMesg()
        fileCreator.setSoftwareVersion(softwareVersion)
        encoder.write(fileCreator)
    }

    private fun writeDeviceInfoMessage(
        encoder: FileEncoder,
        startTimeMs: Long,
        manufacturer: Int,
        productId: Int,
        serialNumber: Long,
        softwareVersion: Int
    ) {
        val deviceInfo = DeviceInfoMesg()
        deviceInfo.setTimestamp(DateTime(toFitTimestamp(startTimeMs)))
        deviceInfo.setManufacturer(manufacturer)
        deviceInfo.setProduct(productId)
        deviceInfo.setSerialNumber(serialNumber)
        deviceInfo.setSoftwareVersion(softwareVersion / 100f)
        deviceInfo.setDeviceIndex(DeviceIndex.CREATOR)
        deviceInfo.setSourceType(SourceType.LOCAL)
        encoder.write(deviceInfo)
    }

    /**
     * Write Sport message - defines the sport/sub-sport for the activity.
     * Required by some platforms for proper workout recognition.
     */
    private fun writeSportMessage(encoder: FileEncoder) {
        val sport = SportMesg()
        sport.setSport(Sport.RUNNING)
        sport.setSubSport(SubSport.TREADMILL)
        sport.setName("Treadmill")
        encoder.write(sport)
    }

    /**
     * Write Stryd developer data ID and field description messages.
     * These must appear after FileId/FileCreator/DeviceInfo but before any Record messages.
     *
     * Mimics the Stryd Connect IQ app's developer field format:
     * - Record-level Power (field 0, uint16, native override for power field 7)
     * - Lap-level Lap Power (field 10, uint16, native override for power field 7)
     * - Session-level CP/Critical Power (field 99, uint16)
     */
    private fun writeStrydDeveloperFieldDefinitions(
        encoder: FileEncoder,
        userFtpWatts: Int
    ): StrydDevFields {
        // Developer Data ID — identifies the Stryd Connect IQ app
        val devDataId = DeveloperDataIdMesg()
        // setApplicationId(int, Byte) corrupts bytes > 127 (signed Byte → 0xFF invalid).
        // Use setFieldValue with unsigned Int values to write raw bytes correctly.
        for (i in STRYD_APP_UUID_BYTES.indices) {
            devDataId.setFieldValue(
                DeveloperDataIdMesg.ApplicationIdFieldNum, i,
                STRYD_APP_UUID_BYTES[i].toInt() and 0xFF
            )
        }
        devDataId.setDeveloperDataIndex(STRYD_DEV_DATA_INDEX)
        devDataId.setApplicationVersion(STRYD_APP_VERSION)
        encoder.write(devDataId)

        // Field: Power (record-level, overrides native power field 7)
        val recordPowerDesc = FieldDescriptionMesg()
        recordPowerDesc.setDeveloperDataIndex(STRYD_DEV_DATA_INDEX)
        recordPowerDesc.setFieldDefinitionNumber(STRYD_FIELD_POWER)
        recordPowerDesc.setFitBaseTypeId(FitBaseType.UINT16)
        recordPowerDesc.setFieldName(0, "Power")
        recordPowerDesc.setUnits(0, "Watts")
        recordPowerDesc.setNativeMesgNum(MesgNum.RECORD)
        recordPowerDesc.setNativeFieldNum(RecordMesg.PowerFieldNum.toShort())
        encoder.write(recordPowerDesc)

        // Field: Lap Power (lap-level, overrides native power field 7)
        val lapPowerDesc = FieldDescriptionMesg()
        lapPowerDesc.setDeveloperDataIndex(STRYD_DEV_DATA_INDEX)
        lapPowerDesc.setFieldDefinitionNumber(STRYD_FIELD_LAP_POWER)
        lapPowerDesc.setFitBaseTypeId(FitBaseType.UINT16)
        lapPowerDesc.setFieldName(0, "Lap Power")
        lapPowerDesc.setUnits(0, "Watts")
        lapPowerDesc.setNativeMesgNum(MesgNum.LAP)
        lapPowerDesc.setNativeFieldNum(RecordMesg.PowerFieldNum.toShort())
        encoder.write(lapPowerDesc)

        // Field: CP / Critical Power (session-level, no native override)
        val sessionCpDesc = FieldDescriptionMesg()
        sessionCpDesc.setDeveloperDataIndex(STRYD_DEV_DATA_INDEX)
        sessionCpDesc.setFieldDefinitionNumber(STRYD_FIELD_CP)
        sessionCpDesc.setFitBaseTypeId(FitBaseType.UINT16)
        sessionCpDesc.setFieldName(0, "CP")
        sessionCpDesc.setUnits(0, "Watts")
        sessionCpDesc.setNativeMesgNum(MesgNum.SESSION)
        encoder.write(sessionCpDesc)

        Log.d(TAG, "Wrote Stryd developer field definitions (Power, Lap Power, CP=${userFtpWatts}W)")

        return StrydDevFields(devDataId, recordPowerDesc, lapPowerDesc, sessionCpDesc, userFtpWatts)
    }

    private fun writeRecordMessage(
        encoder: FileEncoder,
        dataPoint: WorkoutDataPoint,
        strydDevFields: StrydDevFields? = null
    ) {
        val record = RecordMesg()
        record.setTimestamp(DateTime(toFitTimestamp(dataPoint.timestampMs)))

        // Activity type - running for treadmill
        record.setActivityType(ActivityType.RUNNING)

        // Heart rate (bpm)
        if (dataPoint.heartRateBpm > 0) {
            record.setHeartRate(dataPoint.heartRateBpm.toInt().toShort())
        }

        // Speed (m/s) - FIT uses enhanced_speed field
        val speedMs = dataPoint.speedKph / 3.6
        record.setEnhancedSpeed(speedMs.toFloat())

        // Distance (meters) - cumulative
        record.setDistance(dataPoint.distanceKm.toFloat() * 1000f)

        // Grade (percent) - treadmill incline
        record.setGrade(dataPoint.inclinePercent.toFloat())

        // Altitude - calculate cumulative ascent as fake altitude for visualization
        // (Treadmills don't have GPS altitude, but we can show elevation gain)
        record.setEnhancedAltitude(dataPoint.elevationGainM.toFloat())

        // Running power from foot pod (watts)
        if (dataPoint.powerWatts > 0) {
            record.setPower(dataPoint.powerWatts.toInt())
        }

        // Cadence from foot pod (already in strides/min which is what FIT uses)
        if (dataPoint.cadenceSpm > 0) {
            record.setCadence(dataPoint.cadenceSpm.toShort())
        }

        // Stryd developer field: Power (so Stryd PowerCenter recognizes the data)
        if (strydDevFields != null) {
            val devField = DeveloperField(strydDevFields.recordPowerFieldDesc, strydDevFields.devDataIdMesg)
            devField.setValue(dataPoint.powerWatts.toInt())
            record.addDeveloperField(devField)
        }

        encoder.write(record)
    }

    private fun writeLapMessage(
        encoder: FileEncoder,
        startTimeMs: Long,
        endTimeMs: Long,
        totalElapsedMs: Long,
        totalTimerMs: Long,
        totalDistanceM: Double,
        totalAscent: Double,
        avgHeartRate: Double,
        maxHeartRate: Double,
        avgSpeed: Double,
        maxSpeed: Double,
        avgPower: Double = 0.0,
        maxPower: Double = 0.0,
        avgCadence: Double = 0.0,
        maxCadence: Int = 0,
        totalCalories: Double = 0.0,
        lapTrigger: LapTrigger = LapTrigger.SESSION_END,
        avgInclinePower: Double = 0.0,
        avgRawPower: Double = 0.0,
        wktStepIndex: Int? = null,
        intensity: Intensity = Intensity.ACTIVE,
        messageIndex: Int = 0,
        strydDevFields: StrydDevFields? = null
    ) {
        val lap = LapMesg()
        lap.setMessageIndex(messageIndex)
        lap.setTimestamp(DateTime(toFitTimestamp(endTimeMs)))
        lap.setStartTime(DateTime(toFitTimestamp(startTimeMs)))
        lap.setTotalElapsedTime((totalElapsedMs / 1000.0).toFloat())
        lap.setTotalTimerTime((totalTimerMs / 1000.0).toFloat())
        lap.setTotalDistance(totalDistanceM.toFloat())
        lap.setTotalAscent(totalAscent.toInt())

        if (avgHeartRate > 0) {
            lap.setAvgHeartRate(avgHeartRate.toInt().toShort())
        }
        if (maxHeartRate > 0) {
            lap.setMaxHeartRate(maxHeartRate.toInt().toShort())
        }

        lap.setEnhancedAvgSpeed(avgSpeed.toFloat())
        lap.setEnhancedMaxSpeed(maxSpeed.toFloat())

        // Power metrics from foot pod
        if (avgPower > 0) {
            lap.setAvgPower(avgPower.toInt())
        }
        if (maxPower > 0) {
            lap.setMaxPower(maxPower.toInt())
        }

        // Cadence metrics (already in strides/min which is what FIT uses)
        if (avgCadence > 0) {
            lap.setAvgRunningCadence(avgCadence.toInt().toShort())
        }
        if (maxCadence > 0) {
            lap.setMaxRunningCadence(maxCadence.toShort())
        }

        // Calories
        if (totalCalories > 0) {
            lap.setTotalCalories(totalCalories.toInt())
        }

        lap.setEvent(Event.LAP)
        lap.setEventType(EventType.STOP)
        lap.setLapTrigger(lapTrigger)
        lap.setSport(Sport.RUNNING)
        lap.setSubSport(SubSport.TREADMILL)
        lap.setIntensity(intensity)

        // Link lap to workout step (critical for Garmin/Stryd to recognize structured workouts)
        if (wktStepIndex != null) {
            lap.setWktStepIndex(wktStepIndex)
        }

        // Stryd developer field: Lap Power
        if (strydDevFields != null && avgPower > 0) {
            val devField = DeveloperField(strydDevFields.lapPowerFieldDesc, strydDevFields.devDataIdMesg)
            devField.setValue(avgPower.toInt())
            lap.addDeveloperField(devField)
        }

        encoder.write(lap)
    }

    private fun writeSessionMessage(
        encoder: FileEncoder,
        workoutName: String,
        startTimeMs: Long,
        endTimeMs: Long,
        totalElapsedMs: Long,
        totalTimerMs: Long,
        totalDistanceM: Double,
        totalAscent: Double,
        avgHeartRate: Double,
        maxHeartRate: Double,
        avgSpeed: Double,
        maxSpeed: Double,
        avgPower: Double = 0.0,
        maxPower: Double = 0.0,
        avgCadence: Double = 0.0,
        maxCadence: Int = 0,
        totalCalories: Double = 0.0,
        numLaps: Int = 1,
        avgInclinePower: Double = 0.0,
        avgRawPower: Double = 0.0,
        strydDevFields: StrydDevFields? = null
    ) {
        val session = SessionMesg()
        session.setTimestamp(DateTime(toFitTimestamp(endTimeMs)))
        session.setStartTime(DateTime(toFitTimestamp(startTimeMs)))
        session.setTotalElapsedTime((totalElapsedMs / 1000.0).toFloat())
        session.setTotalTimerTime((totalTimerMs / 1000.0).toFloat())
        session.setTotalDistance(totalDistanceM.toFloat())
        session.setTotalAscent(totalAscent.toInt())

        if (avgHeartRate > 0) {
            session.setAvgHeartRate(avgHeartRate.toInt().toShort())
        }
        if (maxHeartRate > 0) {
            session.setMaxHeartRate(maxHeartRate.toInt().toShort())
        }

        session.setEnhancedAvgSpeed(avgSpeed.toFloat())
        session.setEnhancedMaxSpeed(maxSpeed.toFloat())

        // Power metrics from foot pod
        if (avgPower > 0) {
            session.setAvgPower(avgPower.toInt())
        }
        if (maxPower > 0) {
            session.setMaxPower(maxPower.toInt())
        }

        // Cadence metrics (already in strides/min which is what FIT uses)
        if (avgCadence > 0) {
            session.setAvgRunningCadence(avgCadence.toInt().toShort())
        }
        if (maxCadence > 0) {
            session.setMaxRunningCadence(maxCadence.toShort())
        }

        // Calories
        if (totalCalories > 0) {
            session.setTotalCalories(totalCalories.toInt())
        }

        // Note: TSS, Load, and TE are NOT written - let Garmin calculate them
        // Garmin's Firstbeat algorithms produce different (usually higher) values

        if (avgRawPower > 0 || avgInclinePower != 0.0) {
            Log.d(TAG, "Session power breakdown: avg_raw=${avgRawPower.toInt()}W, avg_incline=${avgInclinePower.toInt()}W")
        }

        session.setSport(Sport.RUNNING)
        session.setSubSport(SubSport.TREADMILL)
        session.setFirstLapIndex(0)
        session.setNumLaps(numLaps)
        session.setEvent(Event.SESSION)
        session.setEventType(EventType.STOP)
        session.setTrigger(SessionTrigger.ACTIVITY_END)

        // Stryd developer field: CP (Critical Power ≈ FTP)
        if (strydDevFields != null && avgPower > 0) {
            val devField = DeveloperField(strydDevFields.sessionCpFieldDesc, strydDevFields.devDataIdMesg)
            devField.setValue(strydDevFields.userFtpWatts)
            session.addDeveloperField(devField)
        }

        encoder.write(session)
    }

    /**
     * Write Time in Zone message - records time spent in each HR/Power zone.
     * This data helps Garmin calculate training metrics and display zone distribution.
     *
     * @param endTimeMs Timestamp of the last data point
     * @param workoutData All recorded data points for zone calculation
     * @param userLthr User's Lactate Threshold HR for zone boundaries
     * @param userHrRest User's resting HR
     * @param userFtpWatts User's FTP for power zones
     */
    private fun writeTimeInZoneMessage(
        encoder: FileEncoder,
        endTimeMs: Long,
        workoutData: List<WorkoutDataPoint>,
        userLthr: Int,
        userHrRest: Int,
        userFtpWatts: Int
    ) {
        // Calculate time in each HR zone (7 zones: indices 0-6)
        // We use 5 standard zones (1-5) mapped to indices 0-4, with indices 5-6 unused
        val hrZoneTimeMs = LongArray(7) { 0L }

        // Read zone boundaries from user settings (SharedPreferences)
        val prefs = context.getSharedPreferences("TreadmillHUD", android.content.Context.MODE_PRIVATE)
        val z2StartPercent = SettingsManager.Companion.getFloatOrInt(
            prefs, SettingsManager.PREF_HR_ZONE2_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE2_START_PERCENT
        ).toInt()
        val z3StartPercent = SettingsManager.Companion.getFloatOrInt(
            prefs, SettingsManager.PREF_HR_ZONE3_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE3_START_PERCENT
        ).toInt()
        val z4StartPercent = SettingsManager.Companion.getFloatOrInt(
            prefs, SettingsManager.PREF_HR_ZONE4_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE4_START_PERCENT
        ).toInt()
        val z5StartPercent = SettingsManager.Companion.getFloatOrInt(
            prefs, SettingsManager.PREF_HR_ZONE5_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE5_START_PERCENT
        ).toInt()

        // Max HR derived from LTHR (LTHR is typically ~90% of max HR)
        val userMaxHr = (userLthr * 1.1).toInt()

        // Garmin uses 7 HR zones (indices 0-6) with 6 boundaries defining upper limits.
        // Zone boundaries are in BPM. Each boundary is the HIGH limit of that zone.
        // To get zone N high boundary: convert zone N+1 START percent to BPM, then subtract 1.
        val z0MaxPercent = (z2StartPercent - 20).coerceAtLeast(50).toDouble()  // ~60% LTHR
        val z0MaxBpm = HeartRateZones.percentToBpm(z0MaxPercent, userLthr).toShort()
        val z1MaxBpm = (HeartRateZones.percentToBpm(z2StartPercent.toDouble(), userLthr) - 1).toShort()
        val z2MaxBpm = (HeartRateZones.percentToBpm(z3StartPercent.toDouble(), userLthr) - 1).toShort()
        val z3MaxBpm = (HeartRateZones.percentToBpm(z4StartPercent.toDouble(), userLthr) - 1).toShort()
        val z4MaxBpm = (HeartRateZones.percentToBpm(z5StartPercent.toDouble(), userLthr) - 1).toShort()
        val z5MaxBpm = userMaxHr.toShort()

        // Track time between consecutive samples using Garmin's 7-zone model
        var lastTimestampMs: Long? = null
        for (dp in workoutData) {
            if (lastTimestampMs != null && dp.heartRateBpm > 0) {
                val intervalMs = dp.timestampMs - lastTimestampMs
                val hrBpm = dp.heartRateBpm.toInt()

                // Classify into Garmin's 7-zone model (indices 0-6) using BPM boundaries
                val zoneIndex = when {
                    hrBpm <= z0MaxBpm -> 0  // Zone 0: warmup/rest
                    hrBpm <= z1MaxBpm -> 1  // Zone 1: easy
                    hrBpm <= z2MaxBpm -> 2  // Zone 2: aerobic
                    hrBpm <= z3MaxBpm -> 3  // Zone 3: tempo
                    hrBpm <= z4MaxBpm -> 4  // Zone 4: threshold
                    hrBpm <= z5MaxBpm -> 5  // Zone 5: VO2max
                    else -> 6               // Zone 6: anaerobic (above max)
                }
                hrZoneTimeMs[zoneIndex] += intervalMs
            }
            lastTimestampMs = dp.timestampMs
        }

        // Calculate time in each Power zone (if power data available)
        val powerZoneTimeMs = LongArray(7) { 0L }
        val hasPowerData = workoutData.any { it.powerWatts > 0 }

        if (hasPowerData) {
            // Read power zone boundaries from user settings (zone START percentages of FTP)
            val pz2StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE2_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE2_START_PERCENT
            ).toInt()
            val pz3StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE3_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE3_START_PERCENT
            ).toInt()
            val pz4StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE4_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE4_START_PERCENT
            ).toInt()
            val pz5StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE5_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE5_START_PERCENT
            ).toInt()
            val pz6StartPercent = pz5StartPercent + 15  // Typically ~120% FTP

            // Convert zone START percentages to zone HIGH boundaries in watts
            // Zone N high = Zone N+1 start watts - 1
            val pz0MaxWatts = (userFtpWatts * (pz2StartPercent - 20).coerceAtLeast(30) / 100.0).toInt()
            val pz1MaxWatts = (userFtpWatts * pz2StartPercent / 100.0).toInt() - 1
            val pz2MaxWatts = (userFtpWatts * pz3StartPercent / 100.0).toInt() - 1
            val pz3MaxWatts = (userFtpWatts * pz4StartPercent / 100.0).toInt() - 1
            val pz4MaxWatts = (userFtpWatts * pz5StartPercent / 100.0).toInt() - 1
            val pz5MaxWatts = (userFtpWatts * pz6StartPercent / 100.0).toInt() - 1

            lastTimestampMs = null
            for (dp in workoutData) {
                if (lastTimestampMs != null && dp.powerWatts > 0) {
                    val intervalMs = dp.timestampMs - lastTimestampMs
                    val watts = dp.powerWatts.toInt()

                    // Classify into 7-zone model (indices 0-6) using watts boundaries
                    val zoneIndex = when {
                        watts <= pz0MaxWatts -> 0  // Zone 0: recovery
                        watts <= pz1MaxWatts -> 1  // Zone 1: endurance
                        watts <= pz2MaxWatts -> 2  // Zone 2: tempo
                        watts <= pz3MaxWatts -> 3  // Zone 3: threshold
                        watts <= pz4MaxWatts -> 4  // Zone 4: VO2max
                        watts <= pz5MaxWatts -> 5  // Zone 5: anaerobic
                        else -> 6                  // Zone 6: neuromuscular
                    }
                    powerZoneTimeMs[zoneIndex] += intervalMs
                }
                lastTimestampMs = dp.timestampMs
            }
        }

        val timeInZone = TimeInZoneMesg()
        timeInZone.setTimestamp(DateTime(toFitTimestamp(endTimeMs)))

        // Reference the session message (mesg_num for session = 18)
        timeInZone.setReferenceMesg(MesgNum.SESSION)
        timeInZone.setReferenceIndex(0)  // First (only) session

        // Set time in HR zones (values in seconds with scale 1000 = milliseconds)
        // The SDK handles the scale conversion internally
        for (i in 0 until 7) {
            timeInZone.setTimeInHrZone(i, hrZoneTimeMs[i] / 1000.0f)
        }

        // Set HR zone boundaries (upper boundary for each zone in BPM)
        // Garmin uses 6 boundaries: indices 0-5 for zones 0-5
        timeInZone.setHrZoneHighBoundary(0, z0MaxBpm)
        timeInZone.setHrZoneHighBoundary(1, z1MaxBpm)
        timeInZone.setHrZoneHighBoundary(2, z2MaxBpm)
        timeInZone.setHrZoneHighBoundary(3, z3MaxBpm)
        timeInZone.setHrZoneHighBoundary(4, z4MaxBpm)
        timeInZone.setHrZoneHighBoundary(5, z5MaxBpm)

        // Set HR calculation type (LTHR-based)
        timeInZone.setHrCalcType(HrZoneCalc.PERCENT_LTHR)
        timeInZone.setThresholdHeartRate(userLthr.toShort())
        timeInZone.setRestingHeartRate(userHrRest.toShort())
        timeInZone.setMaxHeartRate(userMaxHr.toShort())

        // Set power zones if we have power data
        if (hasPowerData) {
            for (i in 0 until 7) {
                timeInZone.setTimeInPowerZone(i, powerZoneTimeMs[i] / 1000.0f)
            }

            // Read power zone boundaries from user settings (same as used for time calculation)
            val pz2StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE2_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE2_START_PERCENT
            ).toInt()
            val pz3StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE3_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE3_START_PERCENT
            ).toInt()
            val pz4StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE4_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE4_START_PERCENT
            ).toInt()
            val pz5StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE5_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE5_START_PERCENT
            ).toInt()
            val pz6StartPercent = pz5StartPercent + 15

            // Power zone high boundaries in watts (zone N high = zone N+1 start - 1)
            val pz0MaxWatts = (userFtpWatts * (pz2StartPercent - 20).coerceAtLeast(30) / 100.0).toInt()
            val pz1MaxWatts = (userFtpWatts * pz2StartPercent / 100.0).toInt() - 1
            val pz2MaxWatts = (userFtpWatts * pz3StartPercent / 100.0).toInt() - 1
            val pz3MaxWatts = (userFtpWatts * pz4StartPercent / 100.0).toInt() - 1
            val pz4MaxWatts = (userFtpWatts * pz5StartPercent / 100.0).toInt() - 1
            val pz5MaxWatts = (userFtpWatts * pz6StartPercent / 100.0).toInt() - 1

            timeInZone.setPowerZoneHighBoundary(0, pz0MaxWatts)
            timeInZone.setPowerZoneHighBoundary(1, pz1MaxWatts)
            timeInZone.setPowerZoneHighBoundary(2, pz2MaxWatts)
            timeInZone.setPowerZoneHighBoundary(3, pz3MaxWatts)
            timeInZone.setPowerZoneHighBoundary(4, pz4MaxWatts)
            timeInZone.setPowerZoneHighBoundary(5, pz5MaxWatts)

            timeInZone.setPwrCalcType(PwrZoneCalc.PERCENT_FTP)
            timeInZone.setFunctionalThresholdPower(userFtpWatts)
        }

        encoder.write(timeInZone)

        // Log zone distribution for debugging (7 zones: indices 0-6)
        val totalHrTimeMs = hrZoneTimeMs.sum()
        Log.d(TAG, "Time in HR zones (total ${totalHrTimeMs / 1000}s): " +
            "Z0=${hrZoneTimeMs[0] / 1000}s, Z1=${hrZoneTimeMs[1] / 1000}s, Z2=${hrZoneTimeMs[2] / 1000}s, " +
            "Z3=${hrZoneTimeMs[3] / 1000}s, Z4=${hrZoneTimeMs[4] / 1000}s, Z5=${hrZoneTimeMs[5] / 1000}s, Z6=${hrZoneTimeMs[6] / 1000}s")

        if (hasPowerData) {
            val totalPowerTimeMs = powerZoneTimeMs.sum()
            Log.d(TAG, "Time in Power zones (total ${totalPowerTimeMs / 1000}s): " +
                "Z0=${powerZoneTimeMs[0] / 1000}s, Z1=${powerZoneTimeMs[1] / 1000}s, Z2=${powerZoneTimeMs[2] / 1000}s, " +
                "Z3=${powerZoneTimeMs[3] / 1000}s, Z4=${powerZoneTimeMs[4] / 1000}s, Z5=${powerZoneTimeMs[5] / 1000}s, Z6=${powerZoneTimeMs[6] / 1000}s")
        }
    }

    private fun writeActivityMessage(
        encoder: FileEncoder,
        startTimeMs: Long,
        totalElapsedMs: Long,
        totalTimerMs: Long
    ) {
        val activity = ActivityMesg()
        activity.setTimestamp(DateTime(toFitTimestamp(startTimeMs + totalElapsedMs)))
        activity.setTotalTimerTime((totalTimerMs / 1000.0).toFloat())
        activity.setNumSessions(1)
        activity.setType(Activity.MANUAL)
        activity.setEvent(Event.ACTIVITY)
        activity.setEventType(EventType.STOP)

        // Local timestamp = UTC timestamp + timezone offset
        // This is important for Garmin to correctly display the activity time
        val timezoneOffsetMs = java.util.TimeZone.getDefault().getOffset(startTimeMs + totalElapsedMs).toLong()
        activity.setLocalTimestamp(toFitTimestamp(startTimeMs + totalElapsedMs + timezoneOffsetMs))

        encoder.write(activity)
    }

    /**
     * Write Workout message with WorkoutStep messages for structured workouts.
     * Uses actual executed steps (lap groups) which reflect Prev/Next button usage.
     *
     * @param lapGroups Data points grouped by step (actual execution order)
     * @param executionSteps Planned execution steps (for target values)
     * @param userLthr User's LTHR for converting HR % to BPM
     * @param userFtpWatts User's FTP for converting Power % to Watts
     */
    private fun writeWorkoutWithSteps(
        encoder: FileEncoder,
        workoutName: String,
        lapGroups: List<List<WorkoutDataPoint>>,
        executionSteps: List<ExecutionStep>,
        userLthr: Int,
        userFtpWatts: Int
    ) {
        // Write workout message with step count
        val workout = WorkoutMesg()
        workout.setWktName(workoutName)
        workout.setSport(Sport.RUNNING)
        workout.setSubSport(SubSport.GENERIC)  // Garmin uses generic for workout definition
        workout.setNumValidSteps(lapGroups.size)
        workout.setCapabilities(WorkoutCapabilities.TCX)  // Match Garmin format
        encoder.write(workout)

        // Write each workout step
        lapGroups.forEachIndexed { index, lapData ->
            if (lapData.isEmpty()) return@forEachIndexed

            // Find matching ExecutionStep by stepIndex from first data point
            val stepIndex = lapData.first().stepIndex
            val execStep = executionSteps.getOrNull(stepIndex)

            writeWorkoutStepMessage(
                encoder = encoder,
                messageIndex = index,
                lapData = lapData,
                execStep = execStep,
                userLthr = userLthr,
                userFtpWatts = userFtpWatts
            )
        }

        Log.d(TAG, "Wrote workout with ${lapGroups.size} steps")
    }

    /**
     * Write a single WorkoutStep message.
     *
     * @param messageIndex Step index (0-based)
     * @param lapData Recorded data points for this step
     * @param execStep Execution step with targets (may be null for unknown steps)
     * @param userLthr User's LTHR for HR conversion
     * @param userFtpWatts User's FTP for Power conversion
     */
    private fun writeWorkoutStepMessage(
        encoder: FileEncoder,
        messageIndex: Int,
        lapData: List<WorkoutDataPoint>,
        execStep: ExecutionStep?,
        userLthr: Int,
        userFtpWatts: Int
    ) {
        val step = WorkoutStepMesg()
        step.setMessageIndex(messageIndex)

        // Step name from data or execution step
        val stepName = lapData.firstOrNull { it.stepName.isNotEmpty() }?.stepName
            ?: execStep?.displayName
            ?: "Step ${messageIndex + 1}"
        step.setWktStepName(stepName)

        // Intensity based on step type
        // Note: RECOVER uses Intensity.RECOVERY (4), not REST (1) - per Garmin reference files
        val intensity = when (execStep?.type) {
            StepType.WARMUP -> Intensity.WARMUP
            StepType.COOLDOWN -> Intensity.COOLDOWN
            StepType.REST -> Intensity.REST
            StepType.RECOVER -> Intensity.RECOVERY
            StepType.RUN -> Intensity.ACTIVE
            else -> Intensity.ACTIVE
        }
        step.setIntensity(intensity)

        // Duration - use PLANNED duration from execution step (actual is in lap message)
        // Fall back to actual if planned is not available
        val actualDurationMs = lapData.last().elapsedMs - lapData.first().elapsedMs
        val actualDistanceM = (lapData.last().distanceKm - lapData.first().distanceKm) * 1000

        // Determine duration type from execution step, default to TIME
        when (execStep?.durationType) {
            DurationType.DISTANCE -> {
                step.setDurationType(WktStepDuration.DISTANCE)
                // Use planned distance, fall back to actual
                val distanceM = execStep.durationMeters?.toFloat() ?: actualDistanceM.toFloat()
                step.setDurationDistance(distanceM)
            }
            DurationType.TIME -> {
                step.setDurationType(WktStepDuration.TIME)
                // FIT duration_time is in SECONDS with scale 1000
                // Use planned duration, fall back to actual
                val durationSeconds = execStep.durationSeconds?.toFloat()
                    ?: (actualDurationMs / 1000.0f)
                step.setDurationTime(durationSeconds)
            }
            null -> {
                // Open step or unknown - check if durations are null
                if (execStep?.durationSeconds == null && execStep?.durationMeters == null) {
                    // Open steps have BOTH duration_type and target_type set to OPEN
                    step.setDurationType(WktStepDuration.OPEN)
                    step.setTargetType(WktStepTarget.OPEN)
                } else {
                    // Default to TIME with planned or actual duration
                    step.setDurationType(WktStepDuration.TIME)
                    val durationSeconds = execStep?.durationSeconds?.toFloat()
                        ?: (actualDurationMs / 1000.0f)
                    step.setDurationTime(durationSeconds)
                }
            }
        }

        // Get planned pace target from execution step (more reliable than first data point)
        val plannedSpeedKph = execStep?.paceTargetKph ?: lapData.firstOrNull { it.speedKph > 0 }?.speedKph ?: 0.0
        val plannedSpeedMs = plannedSpeedKph / 3.6

        // Target type and values based on auto-adjust mode
        when (execStep?.autoAdjustMode) {
            AutoAdjustMode.HR -> {
                // HR target - use the configured HR range
                step.setTargetType(WktStepTarget.HEART_RATE)
                step.setTargetValue(0L) // 0 = custom zone (signals device to use customTargetValue fields)

                // HR targets use raw BPM values (e.g., 150 BPM = 150)
                // Garmin reference files confirm no offset is used
                val hrMin = execStep.getHrTargetMinBpm(userLthr)
                val hrMax = execStep.getHrTargetMaxBpm(userLthr)
                if (hrMin != null) {
                    step.setCustomTargetHeartRateLow(hrMin.toLong())
                }
                if (hrMax != null) {
                    step.setCustomTargetHeartRateHigh(hrMax.toLong())
                }

                Log.d(TAG, "Step $messageIndex ($stepName): HR target $hrMin-$hrMax bpm, planned speed ${plannedSpeedKph}kph")
            }

            AutoAdjustMode.POWER -> {
                // Power target - use the configured Power range
                step.setTargetType(WktStepTarget.POWER)
                step.setTargetValue(0L) // 0 = custom zone (signals device to use customTargetValue fields)

                // Power targets use raw watts (e.g., 200W = 200)
                // Similar to HR, Garmin uses raw values not offset encoding
                val powerMin = execStep.getPowerTargetMinWatts(userFtpWatts)
                val powerMax = execStep.getPowerTargetMaxWatts(userFtpWatts)
                if (powerMin != null) {
                    step.setCustomTargetPowerLow(powerMin.toLong())
                }
                if (powerMax != null) {
                    step.setCustomTargetPowerHigh(powerMax.toLong())
                }

                Log.d(TAG, "Step $messageIndex ($stepName): Power target $powerMin-$powerMax W, planned speed ${plannedSpeedKph}kph")
            }

            else -> {
                // No auto-adjust - use speed target from planned pace
                step.setTargetType(WktStepTarget.SPEED)
                step.setTargetValue(0L) // 0 = custom zone

                // Use ±2% margin around planned speed
                val speedLow = (plannedSpeedMs * 0.98).toFloat()
                val speedHigh = (plannedSpeedMs * 1.02).toFloat()
                step.setCustomTargetSpeedLow(speedLow)
                step.setCustomTargetSpeedHigh(speedHigh)

                Log.d(TAG, "Step $messageIndex ($stepName): Speed target ${plannedSpeedKph}kph (${speedLow}-${speedHigh} m/s)")
            }
        }

        encoder.write(step)
    }

    /**
     * Build mapping from flattened execution step index to original FIT step index.
     * This allows repeated steps to reference the correct original step in FIT format.
     *
     * Garmin's FIT format uses hierarchical steps where:
     * - Child steps appear before their REPEAT parent
     * - REPEAT step references back to its children
     * - Laps from repeated iterations reference the same original step index
     *
     * Example: Workout with [Warmup, REPEAT(Run, Recover), Cooldown]
     * FIT steps: [Warmup(0), Run(1), Recover(2), REPEAT(3), Cooldown(4)]
     * Flattened: [Warmup(0), Run 1/2(1), Recover 1/2(2), Run 2/2(3), Recover 2/2(4), Cooldown(5)]
     * Mapping: 0->0, 1->1, 2->2, 3->1, 4->2, 5->4 (Run 2/2 maps back to original Run at FIT index 1)
     */
    private fun buildFlatToOriginalStepIndexMap(originalSteps: List<WorkoutStep>): Map<Int, Int> {
        val mapping = mutableMapOf<Int, Int>()

        // Pre-compute children for each repeat step (by parent ID)
        val childrenByRepeatId = mutableMapOf<Long, MutableList<WorkoutStep>>()
        for (step in originalSteps) {
            step.parentRepeatStepId?.let { parentId ->
                childrenByRepeatId.getOrPut(parentId) { mutableListOf() }.add(step)
            }
        }

        // First, build the FIT step order (children before REPEAT parent)
        // This tells us what FIT index each original step gets
        val originalToFitIndex = mutableMapOf<Long, Int>()  // original step ID -> FIT step index
        var fitIndex = 0

        for (step in originalSteps) {
            if (step.parentRepeatStepId != null) {
                // Skip children - they're added when processing their parent
                continue
            }

            if (step.type == StepType.REPEAT) {
                val childSteps = childrenByRepeatId[step.id] ?: emptyList()

                // Add children first (FIT format: children before parent)
                for (child in childSteps) {
                    originalToFitIndex[child.id] = fitIndex++
                }

                // Add REPEAT step
                originalToFitIndex[step.id] = fitIndex++
            } else {
                // Top-level non-repeat step
                originalToFitIndex[step.id] = fitIndex++
            }
        }

        // Now build the flattened index to FIT index mapping
        var flatIdx = 0
        for (step in originalSteps) {
            if (step.parentRepeatStepId != null) {
                // Skip children - handled with their parent
                continue
            }

            if (step.type == StepType.REPEAT) {
                val childSteps = childrenByRepeatId[step.id] ?: emptyList()
                val repeatCount = step.repeatCount ?: 1

                // Map each iteration's steps to their original FIT indices
                for (iteration in 1..repeatCount) {
                    for (child in childSteps) {
                        val childFitIndex = originalToFitIndex[child.id]
                        if (childFitIndex != null) {
                            mapping[flatIdx] = childFitIndex
                        }
                        flatIdx++
                    }
                }
                // REPEAT step itself is not in flattened list
            } else {
                // Top-level non-repeat step
                val stepFitIndex = originalToFitIndex[step.id]
                if (stepFitIndex != null) {
                    mapping[flatIdx] = stepFitIndex
                }
                flatIdx++
            }
        }

        Log.d(TAG, "Built flat-to-FIT index mapping: $mapping")
        return mapping
    }

    /**
     * Write hierarchical workout steps in Garmin's FIT format.
     * - Child steps appear BEFORE their REPEAT parent
     * - REPEAT uses duration_type=REPEAT_UNTIL_STEPS_CMPLT and references children
     */
    private fun writeHierarchicalWorkoutSteps(
        encoder: FileEncoder,
        workoutName: String,
        originalSteps: List<WorkoutStep>,
        userLthr: Int,
        userFtpWatts: Int
    ) {
        // Build the FIT step list (children before REPEAT parent)
        data class FitStepInfo(
            val step: WorkoutStep,
            val fitIndex: Int,
            val isRepeat: Boolean = false,
            val repeatFirstChildFitIndex: Int? = null,
            val repeatChildCount: Int? = null
        )

        val fitSteps = mutableListOf<FitStepInfo>()

        // Pre-compute children for each repeat step (by parent ID, not list position)
        val childrenByRepeatId = mutableMapOf<Long, MutableList<WorkoutStep>>()
        for (step in originalSteps) {
            step.parentRepeatStepId?.let { parentId ->
                childrenByRepeatId.getOrPut(parentId) { mutableListOf() }.add(step)
            }
        }

        // Debug: log original steps and children mapping
        Log.d(TAG, "=== Building FIT steps from ${originalSteps.size} original steps ===")
        for ((idx, s) in originalSteps.withIndex()) {
            Log.d(TAG, "  Original[$idx]: type=${s.type}, orderIndex=${s.orderIndex}, parentRepeatStepId=${s.parentRepeatStepId}, id=${s.id}")
        }
        for ((repeatId, children) in childrenByRepeatId) {
            Log.d(TAG, "  Repeat $repeatId has ${children.size} children: ${children.map { it.type }}")
        }

        // Process steps - skip children (they'll be added when we process their parent REPEAT)
        for (step in originalSteps) {
            if (step.parentRepeatStepId != null) {
                // Skip - will be added when processing parent REPEAT
                continue
            }

            if (step.type == StepType.REPEAT) {
                // Get children for THIS repeat by ID
                val childSteps = childrenByRepeatId[step.id] ?: emptyList()

                // Record the FIT index where children start
                val firstChildFitIndex = fitSteps.size
                Log.d(TAG, "REPEAT id=${step.id}: firstChildFitIndex=$firstChildFitIndex, children=${childSteps.size}")

                // Add children FIRST (Garmin format: children before repeat)
                for (child in childSteps) {
                    Log.d(TAG, "  Adding child ${child.type} at fitIndex ${fitSteps.size}")
                    fitSteps.add(FitStepInfo(child, fitSteps.size))
                }

                // Add REPEAT step with reference to children
                Log.d(TAG, "  Adding REPEAT at fitIndex ${fitSteps.size}, firstChildFitIndex=$firstChildFitIndex")
                fitSteps.add(FitStepInfo(
                    step = step,
                    fitIndex = fitSteps.size,
                    isRepeat = true,
                    repeatFirstChildFitIndex = firstChildFitIndex,
                    repeatChildCount = childSteps.size
                ))
            } else {
                // Top-level non-repeat step
                Log.d(TAG, "Top-level ${step.type} at fitIndex ${fitSteps.size}")
                fitSteps.add(FitStepInfo(step, fitSteps.size))
            }
        }

        Log.d(TAG, "=== Final FIT steps: ${fitSteps.size} ===")
        for (fs in fitSteps) {
            Log.d(TAG, "  FIT[${fs.fitIndex}]: ${fs.step.type}, isRepeat=${fs.isRepeat}, firstChild=${fs.repeatFirstChildFitIndex}, childCount=${fs.repeatChildCount}")
        }

        // Write workout message with step count
        val workout = WorkoutMesg()
        workout.setWktName(workoutName)
        workout.setSport(Sport.RUNNING)
        workout.setSubSport(SubSport.GENERIC)  // Garmin uses generic for workout definition
        workout.setNumValidSteps(fitSteps.size)
        workout.setCapabilities(WorkoutCapabilities.TCX)  // Match Garmin format
        encoder.write(workout)

        // Write each FIT step
        for (fitStepInfo in fitSteps) {
            if (fitStepInfo.isRepeat) {
                writeRepeatStepMessage(
                    encoder = encoder,
                    messageIndex = fitStepInfo.fitIndex,
                    step = fitStepInfo.step,
                    firstChildFitIndex = fitStepInfo.repeatFirstChildFitIndex!!,
                    childCount = fitStepInfo.repeatChildCount!!
                )
            } else {
                writeHierarchicalStepMessage(
                    encoder = encoder,
                    messageIndex = fitStepInfo.fitIndex,
                    step = fitStepInfo.step,
                    userLthr = userLthr,
                    userFtpWatts = userFtpWatts
                )
            }
        }

        Log.d(TAG, "Wrote hierarchical workout with ${fitSteps.size} FIT steps")
    }

    /**
     * Write a REPEAT step message in Garmin's format.
     * Uses duration_type=REPEAT_UNTIL_STEPS_CMPLT with references to child steps.
     *
     * Garmin format:
     * - duration_type: repeat_until_steps_cmplt
     * - duration_step: first step index to repeat from
     * - repeat_steps: number of steps in the repeat block
     * - duration_value: repeat count (how many times to execute)
     * - target_type: None (not OPEN!)
     * - intensity: None
     */
    private fun writeRepeatStepMessage(
        encoder: FileEncoder,
        messageIndex: Int,
        step: WorkoutStep,
        firstChildFitIndex: Int,
        childCount: Int
    ) {
        val fitStep = WorkoutStepMesg()
        fitStep.setMessageIndex(messageIndex)

        // REPEAT steps don't have a name (Garmin uses None)
        // Don't set wkt_step_name at all - leave it null

        // Duration type: repeat_until_steps_cmplt
        fitStep.setDurationType(WktStepDuration.REPEAT_UNTIL_STEPS_CMPLT)

        // duration_step = first step index to repeat from
        fitStep.setDurationStep(firstChildFitIndex.toLong())

        // repeat_steps = number of steps in the repeat block (CRITICAL!)
        fitStep.setRepeatSteps(childCount.toLong())

        // duration_value = repeat count (how many times to execute the steps)
        val repeatCount = step.repeatCount ?: 1
        fitStep.setDurationValue(repeatCount.toLong())

        // target_type and intensity are None for REPEAT steps (don't set them)

        encoder.write(fitStep)

        Log.d(TAG, "Step $messageIndex: REPEAT duration_step=$firstChildFitIndex, repeat_steps=$childCount, count=$repeatCount")
    }

    /**
     * Write a regular (non-REPEAT) workout step message from original WorkoutStep.
     */
    private fun writeHierarchicalStepMessage(
        encoder: FileEncoder,
        messageIndex: Int,
        step: WorkoutStep,
        userLthr: Int,
        userFtpWatts: Int
    ) {
        val fitStep = WorkoutStepMesg()
        fitStep.setMessageIndex(messageIndex)

        // Step name based on type
        val stepName = when (step.type) {
            StepType.WARMUP -> "Warmup"
            StepType.RUN -> "Run"
            StepType.RECOVER -> "Recover"
            StepType.REST -> "Rest"
            StepType.COOLDOWN -> "Cooldown"
            StepType.REPEAT -> "Repeat"
        }
        fitStep.setWktStepName(stepName)

        // Intensity based on step type
        val intensity = when (step.type) {
            StepType.WARMUP -> Intensity.WARMUP
            StepType.COOLDOWN -> Intensity.COOLDOWN
            StepType.REST -> Intensity.REST
            StepType.RECOVER -> Intensity.RECOVERY
            StepType.RUN -> Intensity.ACTIVE
            else -> Intensity.ACTIVE
        }
        fitStep.setIntensity(intensity)

        // Duration type and value
        val isOpenStep = step.durationSeconds == null && step.durationMeters == null
        when {
            isOpenStep -> {
                fitStep.setDurationType(WktStepDuration.OPEN)
                fitStep.setTargetType(WktStepTarget.OPEN)
            }
            step.durationType == DurationType.DISTANCE -> {
                fitStep.setDurationType(WktStepDuration.DISTANCE)
                fitStep.setDurationDistance(step.durationMeters?.toFloat() ?: 0f)
            }
            else -> {
                // Default to TIME
                fitStep.setDurationType(WktStepDuration.TIME)
                fitStep.setDurationTime(step.durationSeconds?.toFloat() ?: 0f)
            }
        }

        // Target type and values (skip if already set to OPEN for open duration steps)
        // Warmup/Cooldown use OPEN target (per Garmin format) unless they have explicit HR/Power targets
        val useOpenTarget = isOpenStep ||
            (step.type == StepType.WARMUP && step.autoAdjustMode == AutoAdjustMode.NONE) ||
            (step.type == StepType.COOLDOWN && step.autoAdjustMode == AutoAdjustMode.NONE)

        if (!useOpenTarget) {
            val plannedSpeedKph = step.paceTargetKph
            val plannedSpeedMs = plannedSpeedKph / 3.6

            when (step.autoAdjustMode) {
                AutoAdjustMode.HR -> {
                    fitStep.setTargetType(WktStepTarget.HEART_RATE)
                    fitStep.setTargetValue(0L)
                    // Convert % of LTHR to BPM
                    val hrMin = step.hrTargetMinPercent?.let { it * userLthr / 100 }
                    val hrMax = step.hrTargetMaxPercent?.let { it * userLthr / 100 }
                    if (hrMin != null) fitStep.setCustomTargetHeartRateLow(hrMin.toLong())
                    if (hrMax != null) fitStep.setCustomTargetHeartRateHigh(hrMax.toLong())
                    Log.d(TAG, "Step $messageIndex ($stepName): HR target $hrMin-$hrMax bpm")
                }
                AutoAdjustMode.POWER -> {
                    fitStep.setTargetType(WktStepTarget.POWER)
                    fitStep.setTargetValue(0L)
                    // Convert % of FTP to Watts
                    val powerMin = step.powerTargetMinPercent?.let { it * userFtpWatts / 100 }
                    val powerMax = step.powerTargetMaxPercent?.let { it * userFtpWatts / 100 }
                    if (powerMin != null) fitStep.setCustomTargetPowerLow(powerMin.toLong())
                    if (powerMax != null) fitStep.setCustomTargetPowerHigh(powerMax.toLong())
                    Log.d(TAG, "Step $messageIndex ($stepName): Power target $powerMin-$powerMax W")
                }
                else -> {
                    fitStep.setTargetType(WktStepTarget.SPEED)
                    fitStep.setTargetValue(0L)
                    val speedLow = (plannedSpeedMs * 0.98).toFloat()
                    val speedHigh = (plannedSpeedMs * 1.02).toFloat()
                    fitStep.setCustomTargetSpeedLow(speedLow)
                    fitStep.setCustomTargetSpeedHigh(speedHigh)
                    Log.d(TAG, "Step $messageIndex ($stepName): Speed target ${plannedSpeedKph}kph")
                }
            }
        } else if (!isOpenStep) {
            // Warmup/Cooldown with duration but OPEN target
            fitStep.setTargetType(WktStepTarget.OPEN)
            Log.d(TAG, "Step $messageIndex ($stepName): OPEN target (warmup/cooldown)")
        }

        encoder.write(fitStep)
    }
}
