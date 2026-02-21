package io.github.avikulin.thud.util

import android.content.Context
import android.util.Log
import io.github.avikulin.thud.service.PauseEvent
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import io.github.avikulin.thud.domain.engine.ExecutionStep
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
     * Holds developer field definitions for a single HR BLE sensor.
     * Each connected sensor gets its own devDataIndex (Stryd occupies 0, HR sensors start at 1).
     */
    private data class HrSensorDevField(
        val devDataIdMesg: DeveloperDataIdMesg,
        val recordFieldDesc: FieldDescriptionMesg,
        val dfaFieldDesc: FieldDescriptionMesg?,  // null if no DFA data for this sensor
        val devDataIndex: Short
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
        pauseEvents: List<PauseEvent> = emptyList(),
        executionSteps: List<ExecutionStep>? = null,
        originalSteps: List<WorkoutStep>? = null,
        fitManufacturer: Int = DEFAULT_MANUFACTURER,
        fitProductId: Int = DEFAULT_PRODUCT_ID,
        fitDeviceSerial: Long = DEFAULT_DEVICE_SERIAL,
        fitSoftwareVersion: Int = DEFAULT_SOFTWARE_VERSION,
        hrSensors: List<Pair<String, String>> = emptyList(),
        rrIntervals: List<Pair<Long, Float>>? = null,
        filenameSuffix: String? = null
    ): FitExportResult? {
        if (workoutData.isEmpty()) {
            Log.w(TAG, "No workout data to export")
            return null
        }

        return try {
            val filename = createFilename(workoutName, startTimeMs, filenameSuffix)
            val result = writeFitFileToMediaStore(
                filename, workoutData, workoutName, startTimeMs,
                userHrRest, userLthr, userFtpWatts, thresholdPaceKph, pauseEvents,
                executionSteps, originalSteps,
                fitManufacturer, fitProductId, fitDeviceSerial, fitSoftwareVersion,
                hrSensors, rrIntervals
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
    private fun createFilename(workoutName: String, startTimeMs: Long, suffix: String? = null): String {
        // Create filename: WorkoutName_2026-01-19_14-30-00.fit (or _Suffix.fit)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val dateStr = dateFormat.format(Date(startTimeMs))
        val sanitizedName = workoutName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val suffixPart = if (!suffix.isNullOrEmpty()) "_$suffix" else ""
        return "${sanitizedName}_$dateStr${suffixPart}.fit"
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
        pauseEvents: List<PauseEvent>,
        executionSteps: List<ExecutionStep>?,
        originalSteps: List<WorkoutStep>?,
        fitManufacturer: Int,
        fitProductId: Int,
        fitDeviceSerial: Long,
        fitSoftwareVersion: Int,
        hrSensors: List<Pair<String, String>> = emptyList(),
        rrIntervals: List<Pair<Long, Float>>? = null
    ): FitExportResult {
        // Create temp file for FIT SDK (it requires a File, not OutputStream)
        val tempFile = FileExportHelper.getTempFile(context, filename)
        try {
            // Write FIT file to temp location
            writeFitFile(tempFile, workoutData, workoutName, startTimeMs, userHrRest, userLthr, userFtpWatts, thresholdPaceKph, pauseEvents, executionSteps, originalSteps, fitManufacturer, fitProductId, fitDeviceSerial, fitSoftwareVersion, hrSensors, rrIntervals)

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
        pauseEvents: List<PauseEvent>,
        executionSteps: List<ExecutionStep>?,
        originalSteps: List<WorkoutStep>?,
        fitManufacturer: Int,
        fitProductId: Int,
        fitDeviceSerial: Long,
        fitSoftwareVersion: Int,
        hrSensors: List<Pair<String, String>> = emptyList(),
        rrIntervals: List<Pair<Long, Float>>? = null
    ) {
        val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)

        // Calculate summary data
        val endTimeMs = workoutData.last().timestampMs
        val totalDurationMs = endTimeMs - startTimeMs
        val totalDistanceM = workoutData.last().distanceKm * 1000.0
        val totalAscent = workoutData.last().elevationGainM
        // Single-pass aggregation over all data points
        val agg = aggregateData(workoutData)
        val avgHeartRate = agg.avgHR
        val maxHeartRate = agg.maxHR
        val avgSpeed = if (totalDurationMs > 0) {
            totalDistanceM / (totalDurationMs / 1000.0) // m/s
        } else 0.0
        val maxSpeed = agg.maxSpeedMs
        val avgPower = agg.avgPower
        val maxPower = agg.maxPower
        val avgCadence = agg.avgCadence
        val maxCadence = agg.maxCadence

        // Calories (cumulative, so take the last value)
        val totalCalories = workoutData.last().caloriesKcal

        // Note: TSS, Load, and TE are NOT written to FIT file
        // Let Garmin calculate them using their Firstbeat algorithms

        val avgInclinePower = agg.avgInclinePower
        val avgRawPower = agg.avgRawPower

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

        // 3c. HR sensor developer fields (one per registered sensor)
        // Detect which sensor indices have DFA alpha1 data
        val dfaSensorIndices = workoutData
            .flatMap { it.dfaAlpha1BySensor.keys }
            .filter { idx -> workoutData.any { (it.dfaAlpha1BySensor[idx] ?: -1.0) > 0 } }
            .toSet()
        val hrDevFields = writeHrDeveloperFieldDefinitions(encoder, hrSensors, dfaSensorIndices)
            .takeIf { it.isNotEmpty() }

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

        // 5. Timer START event (required at workout start)
        writeTimerEvent(encoder, startTimeMs, isStart = true)

        // 6. Record messages interleaved with pause/resume events and HRV (in timestamp order)
        writeRecordsWithEvents(encoder, workoutData, pauseEvents, strydDevFields, hrDevFields, hrSensors, rrIntervals)

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
            avgGrade = agg.avgIncline,
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
        strydDevFields: StrydDevFields? = null,
        hrDevFields: Map<Int, HrSensorDevField>? = null,
        hrSensors: List<Pair<String, String>> = emptyList(),
        rrIntervals: List<Pair<Long, Float>>? = null
    ) {
        data class TimestampedItem(
            val timestampMs: Long,
            val isRecord: Boolean,
            val dataPoint: WorkoutDataPoint? = null,
            val pauseEvent: PauseEvent? = null,
            val sensorChangeIndex: Int = -1
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

        // Add sensor-change DeviceInfo events for primary HR transitions (skip -1 = none/average)
        if (hrDevFields != null) {
            var prevIndex = -1
            for (dp in workoutData) {
                val idx = dp.primaryHrIndex
                if (idx != prevIndex && idx >= 0 && hrDevFields.containsKey(idx)) {
                    items.add(TimestampedItem(
                        timestampMs = dp.timestampMs,
                        isRecord = false,
                        sensorChangeIndex = idx
                    ))
                }
                prevIndex = idx
            }
        }

        // Sort by timestamp
        items.sortBy { it.timestampMs }

        // Write in order, interleaving HRV messages with records
        var rrCursor = 0
        for (item in items) {
            when {
                item.sensorChangeIndex >= 0 -> {
                    val devField = hrDevFields!![item.sensorChangeIndex]!!
                    val mac = hrSensors[item.sensorChangeIndex].first
                    writeHrSensorDeviceInfoMessage(
                        encoder, item.timestampMs, mac, devField.devDataIndex
                    )
                }
                item.isRecord -> {
                    // Before each record, drain pending RR intervals up to this timestamp.
                    // This places HrvMesg between the previous and current Record (Garmin pattern).
                    if (rrIntervals != null) {
                        rrCursor = writeInterleavedHrv(encoder, rrIntervals, rrCursor, item.timestampMs)
                    }
                    writeRecordMessage(encoder, item.dataPoint!!, strydDevFields, hrDevFields)
                }
                else -> {
                    val pe = item.pauseEvent!!
                    val eventMesg = EventMesg()
                    eventMesg.setTimestamp(DateTime(toFitTimestamp(pe.timestampMs)))
                    eventMesg.setEvent(Event.TIMER)
                    eventMesg.setEventType(if (pe.isPause) EventType.STOP_ALL else EventType.START)
                    encoder.write(eventMesg)
                }
            }
        }
        // Flush any remaining RR intervals after the last record
        if (rrIntervals != null && rrCursor < rrIntervals.size) {
            rrCursor = writeInterleavedHrv(encoder, rrIntervals, rrCursor, Long.MAX_VALUE)
        }

        val rrCount = rrIntervals?.size ?: 0
        if (pauseEvents.isNotEmpty() || rrCount > 0) {
            Log.d(TAG, "Wrote ${workoutData.size} records interleaved with ${pauseEvents.size} pause/resume events" +
                if (rrCount > 0) " and $rrCount RR intervals" else "")
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
            writeSingleLap(encoder, workoutData, LapTrigger.SESSION_END, pauseEvents, lapIndex = 0, execStep = null, strydDevFields = strydDevFields)
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

            writeSingleLap(encoder, lapData, lapTrigger, pauseEvents, lapIndex = index, wktStepIndex = wktStepIndex, execStep = execStep, nextLapFirstPoint = nextLapFirstPoint, strydDevFields = strydDevFields)
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

        // Single-pass aggregation over lap data points
        val agg = aggregateData(lapData)
        val avgHeartRate = agg.avgHR
        val maxHeartRate = agg.maxHR
        val avgIncline = agg.avgIncline
        val avgSpeedKph = agg.avgSpeedKph

        // Use timer time for speed calculation (excludes pauses) - for FIT file
        val avgSpeed = if (lapTimerMs > 0) {
            lapDistanceM / (lapTimerMs / 1000.0)
        } else 0.0
        val maxSpeed = agg.maxSpeedMs
        val avgPower = agg.avgPower
        val maxPower = agg.maxPower
        val avgCadence = agg.avgCadence
        val maxCadence = agg.maxCadence
        val avgInclinePower = agg.avgInclinePower
        val avgRawPower = agg.avgRawPower

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
            avgGrade = agg.avgIncline,
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

    /**
     * Write developer field definitions for every HR BLE sensor that reported at least one
     * non-zero reading. Each sensor gets its own DeveloperDataIdMesg (devDataIndex starting at 1,
     * since Stryd occupies 0) and field descriptors for BPM (fieldDefNum=0) and optionally
     * DFA alpha1 (fieldDefNum=1, UINT16 scale 1000).
     * Returns sensorIndex → HrSensorDevField map; empty if no multi-sensor HR data present.
     */
    private fun writeHrDeveloperFieldDefinitions(
        encoder: FileEncoder,
        hrSensors: List<Pair<String, String>>,
        dfaSensorIndices: Set<Int> = emptySet()
    ): Map<Int, HrSensorDevField> {
        if (hrSensors.isEmpty()) return emptyMap()

        val result = mutableMapOf<Int, HrSensorDevField>()

        for ((index, pair) in hrSensors.withIndex()) {
            val (mac, name) = pair
            // Stryd occupies devDataIndex 0; HR sensors start at 1
            val devDataIndex = (STRYD_DEV_DATA_INDEX + 1 + index).toShort()

            // Generate a deterministic 16-byte UUID from the MAC address.
            // UUID v3 (MD5-based) ensures a valid UUID that's stable across exports.
            val uuid = UUID.nameUUIDFromBytes("tHUD-HR:$mac".toByteArray())
            val uuidBytes = ByteArray(16)
            val msb = uuid.mostSignificantBits
            val lsb = uuid.leastSignificantBits
            for (i in 0..7) { uuidBytes[i] = (msb shr (56 - i * 8)).toByte() }
            for (i in 0..7) { uuidBytes[8 + i] = (lsb shr (56 - i * 8)).toByte() }

            val devDataId = DeveloperDataIdMesg()
            for (i in uuidBytes.indices) {
                devDataId.setFieldValue(
                    DeveloperDataIdMesg.ApplicationIdFieldNum, i, uuidBytes[i].toInt() and 0xFF
                )
            }
            devDataId.setDeveloperDataIndex(devDataIndex)
            devDataId.setApplicationVersion(1L)
            encoder.write(devDataId)

            // Field 0: HR value in bpm
            val fieldDesc = FieldDescriptionMesg()
            fieldDesc.setDeveloperDataIndex(devDataIndex)
            fieldDesc.setFieldDefinitionNumber(0.toShort())
            fieldDesc.setFitBaseTypeId(FitBaseType.UINT8)
            fieldDesc.setFieldName(0, name.ifEmpty { mac })
            fieldDesc.setUnits(0, "bpm")
            encoder.write(fieldDesc)

            // Field 1: DFA alpha1 (UINT16, scale 1000: 0.750 → 750)
            val dfaDesc = if (index in dfaSensorIndices) {
                val desc = FieldDescriptionMesg()
                desc.setDeveloperDataIndex(devDataIndex)
                desc.setFieldDefinitionNumber(1.toShort())
                desc.setFitBaseTypeId(FitBaseType.UINT16)
                desc.setFieldName(0, "DFA_${name.ifEmpty { mac }}")
                desc.setUnits(0, "")
                encoder.write(desc)
                desc
            } else null

            result[index] = HrSensorDevField(devDataId, fieldDesc, dfaDesc, devDataIndex)
        }

        Log.d(TAG, "Wrote HR developer field definitions for ${result.size} sensors (${dfaSensorIndices.size} with DFA)")
        return result
    }

    /**
     * Write a DeviceInfoMesg at the moment when a BLE HR sensor became the active primary.
     * Only written for actual BLE devices (not for AVERAGE mode transitions).
     */
    private fun writeHrSensorDeviceInfoMessage(
        encoder: FileEncoder,
        timestampMs: Long,
        mac: String,
        deviceIndex: Short
    ) {
        val info = DeviceInfoMesg()
        info.setTimestamp(DateTime(toFitTimestamp(timestampMs)))
        info.setDeviceIndex(deviceIndex)
        info.setManufacturer(Manufacturer.INVALID)
        // Derive a serial from the last 4 MAC bytes for stable identification
        val macParts = mac.split(":")
        val serial = if (macParts.size == 6) {
            macParts.takeLast(4).joinToString("").toLongOrNull(16) ?: 0L
        } else 0L
        info.setSerialNumber(serial)
        info.setSourceType(SourceType.BLUETOOTH_LOW_ENERGY)
        encoder.write(info)
    }

    private fun writeRecordMessage(
        encoder: FileEncoder,
        dataPoint: WorkoutDataPoint,
        strydDevFields: StrydDevFields? = null,
        hrDevFields: Map<Int, HrSensorDevField>? = null
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

        // HR sensor developer fields: BPM + DFA alpha1 per sensor
        hrDevFields?.forEach { (sensorIndex, devField) ->
            // BPM field
            val bpm = dataPoint.allHrSensors[sensorIndex] ?: return@forEach
            if (bpm <= 0) return@forEach
            val field = DeveloperField(devField.recordFieldDesc, devField.devDataIdMesg)
            field.setValue(bpm)
            record.addDeveloperField(field)

            // DFA alpha1 field (UINT16, scale 1000)
            val dfaDesc = devField.dfaFieldDesc
            if (dfaDesc != null) {
                val alpha1 = dataPoint.dfaAlpha1BySensor[sensorIndex]
                if (alpha1 != null && alpha1 > 0) {
                    val dfaField = DeveloperField(dfaDesc, devField.devDataIdMesg)
                    dfaField.setValue((alpha1 * 1000).toInt())
                    record.addDeveloperField(dfaField)
                }
            }
        }

        encoder.write(record)
    }

    /**
     * Write interleaved HRV messages for RR intervals up to (exclusive) a timestamp bound.
     * Each HrvMesg holds up to 5 RR interval values (in seconds).
     * Returns the updated cursor position.
     */
    private fun writeInterleavedHrv(
        encoder: FileEncoder,
        rrIntervals: List<Pair<Long, Float>>,
        fromIndex: Int,
        beforeTimestampMs: Long
    ): Int {
        var i = fromIndex
        while (i < rrIntervals.size && rrIntervals[i].first < beforeTimestampMs) {
            val hrv = HrvMesg()
            val batchEnd = minOf(i + 5, rrIntervals.size)
            var slot = 0
            while (i < batchEnd && rrIntervals[i].first < beforeTimestampMs) {
                hrv.setTime(slot, rrIntervals[i].second)
                i++; slot++
            }
            encoder.write(hrv)
        }
        return i
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
        avgGrade: Double = 0.0,
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
        lap.setAvgGrade(avgGrade.toFloat())

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
        avgGrade: Double = 0.0,
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
        session.setAvgGrade(avgGrade.toFloat())

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

        // Read power zone boundaries once (used for both time calculation and FIT boundary fields)
        val pz2StartPercent: Int
        val pz3StartPercent: Int
        val pz4StartPercent: Int
        val pz5StartPercent: Int
        val pz6StartPercent: Int
        val pz0MaxWatts: Int
        val pz1MaxWatts: Int
        val pz2MaxWatts: Int
        val pz3MaxWatts: Int
        val pz4MaxWatts: Int
        val pz5MaxWatts: Int

        if (hasPowerData) {
            // Read power zone boundaries from user settings (zone START percentages of FTP)
            pz2StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE2_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE2_START_PERCENT
            ).toInt()
            pz3StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE3_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE3_START_PERCENT
            ).toInt()
            pz4StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE4_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE4_START_PERCENT
            ).toInt()
            pz5StartPercent = SettingsManager.Companion.getFloatOrInt(
                prefs, SettingsManager.PREF_POWER_ZONE5_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE5_START_PERCENT
            ).toInt()
            pz6StartPercent = pz5StartPercent + 15  // Typically ~120% FTP

            // Convert zone START percentages to zone HIGH boundaries in watts
            // Zone N high = Zone N+1 start watts - 1
            pz0MaxWatts = PowerZones.percentToWatts((pz2StartPercent - 20).coerceAtLeast(30).toDouble(), userFtpWatts)
            pz1MaxWatts = PowerZones.percentToWatts(pz2StartPercent.toDouble(), userFtpWatts) - 1
            pz2MaxWatts = PowerZones.percentToWatts(pz3StartPercent.toDouble(), userFtpWatts) - 1
            pz3MaxWatts = PowerZones.percentToWatts(pz4StartPercent.toDouble(), userFtpWatts) - 1
            pz4MaxWatts = PowerZones.percentToWatts(pz5StartPercent.toDouble(), userFtpWatts) - 1
            pz5MaxWatts = PowerZones.percentToWatts(pz6StartPercent.toDouble(), userFtpWatts) - 1

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
        } else {
            pz0MaxWatts = 0; pz1MaxWatts = 0; pz2MaxWatts = 0
            pz3MaxWatts = 0; pz4MaxWatts = 0; pz5MaxWatts = 0
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

        // Set power zones if we have power data (reuse boundaries computed above)
        if (hasPowerData) {
            for (i in 0 until 7) {
                timeInZone.setTimeInPowerZone(i, powerZoneTimeMs[i] / 1000.0f)
            }

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
     * Aggregated metrics from a single pass over workout data points.
     * Replaces multiple filter{}.map{}.average() chains.
     */
    private class DataAggregator {
        var sumHR = 0.0; var countHR = 0; var maxHR = 0.0
        var sumSpeed = 0.0; var countSpeed = 0; var maxSpeedKph = 0.0
        var sumPower = 0.0; var countPower = 0; var maxPower = 0.0
        var sumCadence = 0.0; var countCadence = 0; var maxCadence = 0
        var sumIncline = 0.0
        var sumInclinePower = 0.0; var countInclinePower = 0
        var sumRawPower = 0.0; var countRawPower = 0
        var count = 0

        fun accumulate(dp: WorkoutDataPoint) {
            count++
            sumIncline += dp.inclinePercent
            if (dp.heartRateBpm > 0) {
                sumHR += dp.heartRateBpm; countHR++
                if (dp.heartRateBpm > maxHR) maxHR = dp.heartRateBpm
            }
            if (dp.speedKph > 0) {
                sumSpeed += dp.speedKph; countSpeed++
                if (dp.speedKph > maxSpeedKph) maxSpeedKph = dp.speedKph
            }
            if (dp.powerWatts > 0) {
                sumPower += dp.powerWatts; countPower++
                if (dp.powerWatts > maxPower) maxPower = dp.powerWatts
            }
            if (dp.cadenceSpm > 0) {
                sumCadence += dp.cadenceSpm; countCadence++
                if (dp.cadenceSpm > maxCadence) maxCadence = dp.cadenceSpm
            }
            if (dp.inclinePowerWatts != 0.0) {
                sumInclinePower += dp.inclinePowerWatts; countInclinePower++
            }
            if (dp.rawPowerWatts > 0) {
                sumRawPower += dp.rawPowerWatts; countRawPower++
            }
        }

        val avgHR: Double get() = if (countHR > 0) sumHR / countHR else 0.0
        val avgSpeedKph: Double get() = if (countSpeed > 0) sumSpeed / countSpeed else 0.0
        val maxSpeedMs: Double get() = maxSpeedKph / 3.6
        val avgPower: Double get() = if (countPower > 0) sumPower / countPower else 0.0
        val avgCadence: Double get() = if (countCadence > 0) sumCadence / countCadence else 0.0
        val avgIncline: Double get() = if (count > 0) sumIncline / count else 0.0
        val avgInclinePower: Double get() = if (countInclinePower > 0) sumInclinePower / countInclinePower else 0.0
        val avgRawPower: Double get() = if (countRawPower > 0) sumRawPower / countRawPower else 0.0
    }

    private fun aggregateData(data: List<WorkoutDataPoint>): DataAggregator {
        val agg = DataAggregator()
        for (dp in data) agg.accumulate(dp)
        return agg
    }

}
