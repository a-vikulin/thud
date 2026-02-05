package io.github.avikulin.thud.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import io.github.avikulin.thud.domain.engine.ExecutionStep
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.service.PauseEvent
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exports workout data to Garmin TCX (Training Center XML) format.
 * Output format matches Garmin Connect's TCX export for maximum compatibility.
 * Files are saved to Downloads/tHUD folder alongside FIT files.
 */
class TcxFileExporter(private val context: Context) {

    companion object {
        private const val TAG = "TcxFileExporter"
        private const val SUBFOLDER = "tHUD"
        // Use octet-stream to prevent Android adding .xml extension
        private const val MIME_TYPE = "application/octet-stream"
    }

    // Timestamp format with milliseconds to match Garmin's format
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Export workout data to a TCX file.
     *
     * @param workoutData List of recorded data points
     * @param workoutName Name of the workout (used in filename)
     * @param startTimeMs Workout start time in milliseconds
     * @param pauseEvents List of pause/resume events
     * @param executionSteps Execution steps for lap information (null for free runs)
     * @return File path if successful, null otherwise
     */
    fun exportWorkout(
        workoutData: List<WorkoutDataPoint>,
        workoutName: String,
        startTimeMs: Long,
        pauseEvents: List<PauseEvent> = emptyList(),
        executionSteps: List<ExecutionStep>? = null
    ): String? {
        if (workoutData.isEmpty()) {
            Log.w(TAG, "No workout data to export")
            return null
        }

        return try {
            val filename = createFilename(workoutName, startTimeMs)
            val tcxContent = buildTcxContent(workoutData, startTimeMs, pauseEvents, executionSteps)
            val displayPath = writeTcxFileToMediaStore(filename, tcxContent)
            Log.i(TAG, "TCX file exported: $displayPath (${tcxContent.length} bytes)")
            displayPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export TCX file: ${e.message}", e)
            null
        }
    }

    private fun createFilename(workoutName: String, startTimeMs: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val dateStr = dateFormat.format(Date(startTimeMs))
        val sanitizedName = workoutName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "${sanitizedName}_$dateStr.tcx"
    }

    private fun buildTcxContent(
        workoutData: List<WorkoutDataPoint>,
        startTimeMs: Long,
        pauseEvents: List<PauseEvent>,
        executionSteps: List<ExecutionStep>?
    ): String {
        val writer = StringWriter()

        // XML header and root element with namespaces (matching Garmin's format)
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.append("<TrainingCenterDatabase\n")
        writer.append("  xsi:schemaLocation=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\"\n")
        writer.append("  xmlns:ns5=\"http://www.garmin.com/xmlschemas/ActivityGoals/v1\"\n")
        writer.append("  xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\"\n")
        writer.append("  xmlns:ns2=\"http://www.garmin.com/xmlschemas/UserProfile/v2\"\n")
        writer.append("  xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\"\n")
        writer.append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        writer.append("  xmlns:ns4=\"http://www.garmin.com/xmlschemas/ProfileExtension/v1\">\n")

        writer.append("  <Activities>\n")
        writer.append("    <Activity Sport=\"Running\">\n")

        // Activity ID is the start time in ISO format
        val startTimeIso = isoDateFormat.format(Date(startTimeMs))
        writer.append("      <Id>$startTimeIso</Id>\n")

        // Determine if we have structured workout with laps
        val hasStructuredWorkout = workoutData.any { it.stepIndex >= 0 }

        if (hasStructuredWorkout && executionSteps != null) {
            // Write multiple laps based on workout steps
            val lapGroups = StepBoundaryParser.groupByStep(workoutData)
            writeLaps(writer, lapGroups, pauseEvents, executionSteps)
        } else {
            // Single lap for free run
            writeSingleLap(writer, workoutData, startTimeMs, pauseEvents, null)
        }

        // Creator (device info) - no Notes element to match Garmin
        writeCreator(writer)

        writer.append("    </Activity>\n")
        writer.append("  </Activities>\n")

        // Author section
        writeAuthor(writer)

        writer.append("</TrainingCenterDatabase>")

        return writer.toString()
    }

    /**
     * Write multiple laps for structured workouts.
     */
    private fun writeLaps(
        writer: StringWriter,
        lapGroups: List<List<WorkoutDataPoint>>,
        pauseEvents: List<PauseEvent>,
        executionSteps: List<ExecutionStep>
    ) {
        lapGroups.forEachIndexed { index, lapData ->
            if (lapData.isEmpty()) return@forEachIndexed

            val stepIndex = lapData.first().stepIndex
            val execStep = if (stepIndex >= 0) executionSteps.getOrNull(stepIndex) else null

            // Calculate next lap start for duration/distance
            val nextLapFirstPoint = if (index < lapGroups.size - 1) {
                lapGroups[index + 1].firstOrNull()
            } else null

            writeSingleLap(writer, lapData, lapData.first().timestampMs, pauseEvents, execStep, nextLapFirstPoint)
        }
    }

    /**
     * Write a single lap with all trackpoints and summary data.
     */
    private fun writeSingleLap(
        writer: StringWriter,
        lapData: List<WorkoutDataPoint>,
        lapStartTimeMs: Long,
        pauseEvents: List<PauseEvent>,
        execStep: ExecutionStep?,
        nextLapFirstPoint: WorkoutDataPoint? = null
    ) {
        if (lapData.isEmpty()) return

        val lapStartIso = isoDateFormat.format(Date(lapStartTimeMs))

        // Calculate lap end time and duration
        val lapEndTimeMs = nextLapFirstPoint?.timestampMs ?: lapData.last().timestampMs
        val lapElapsedMs = if (nextLapFirstPoint != null) {
            lapEndTimeMs - lapStartTimeMs
        } else {
            lapData.last().timestampMs - lapStartTimeMs
        }

        // Calculate timer time (excluding pauses)
        val pausedDurationMs = calculatePausedDuration(pauseEvents, lapStartTimeMs, lapEndTimeMs)
        val lapTimerMs = (lapElapsedMs - pausedDurationMs).coerceAtLeast(0)

        // Calculate distance
        val lapStartDistanceM = lapData.first().distanceKm * 1000.0
        val lapEndDistanceM = if (nextLapFirstPoint != null) {
            nextLapFirstPoint.distanceKm * 1000.0
        } else {
            lapData.last().distanceKm * 1000.0
        }
        val lapDistanceM = lapEndDistanceM - lapStartDistanceM

        // Calculate calories
        val lapStartCalories = lapData.first().caloriesKcal
        val lapEndCalories = lapData.last().caloriesKcal
        val lapCalories = (lapEndCalories - lapStartCalories).coerceAtLeast(0.0)

        // Heart rate stats
        val hrData = lapData.filter { it.heartRateBpm > 0 }
        val avgHeartRate = hrData.map { it.heartRateBpm }.average().takeIf { !it.isNaN() }?.toInt()
        val maxHeartRate = hrData.maxOfOrNull { it.heartRateBpm }?.toInt()

        // Cadence stats (strides per minute)
        val cadenceData = lapData.filter { it.cadenceSpm > 0 }
        val avgCadence = cadenceData.map { it.cadenceSpm }.average().takeIf { !it.isNaN() }?.toInt()
        val maxCadence = cadenceData.maxOfOrNull { it.cadenceSpm }

        // Speed stats (m/s)
        val speedData = lapData.filter { it.speedKph > 0 }
        val avgSpeedMs = speedData.map { it.speedKph / 3.6 }.average().takeIf { !it.isNaN() }
        val maxSpeedMs = speedData.maxOfOrNull { it.speedKph }?.let { it / 3.6 }

        // Power stats
        val powerData = lapData.filter { it.powerWatts > 0 }
        val avgPower = powerData.map { it.powerWatts }.average().takeIf { !it.isNaN() }?.toInt()
        val maxPower = powerData.maxOfOrNull { it.powerWatts }?.toInt()

        // Determine intensity from step type
        val intensity = when (execStep?.type) {
            StepType.WARMUP, StepType.COOLDOWN, StepType.REST, StepType.RECOVER -> "Resting"
            else -> "Active"
        }

        writer.append("      <Lap StartTime=\"$lapStartIso\">\n")
        writer.append("        <TotalTimeSeconds>${lapTimerMs / 1000.0}</TotalTimeSeconds>\n")
        writer.append("        <DistanceMeters>$lapDistanceM</DistanceMeters>\n")
        writer.append("        <MaximumSpeed>${maxSpeedMs ?: 0.0}</MaximumSpeed>\n")
        writer.append("        <Calories>${lapCalories.toInt()}</Calories>\n")

        if (avgHeartRate != null && avgHeartRate > 0) {
            writer.append("        <AverageHeartRateBpm>\n")
            writer.append("          <Value>$avgHeartRate</Value>\n")
            writer.append("        </AverageHeartRateBpm>\n")
        }
        if (maxHeartRate != null && maxHeartRate > 0) {
            writer.append("        <MaximumHeartRateBpm>\n")
            writer.append("          <Value>$maxHeartRate</Value>\n")
            writer.append("        </MaximumHeartRateBpm>\n")
        }

        writer.append("        <Intensity>$intensity</Intensity>\n")
        // No <Cadence> element in Lap - Garmin doesn't include it
        writer.append("        <TriggerMethod>Manual</TriggerMethod>\n")

        // Track with all trackpoints
        writer.append("        <Track>\n")
        for (dataPoint in lapData) {
            writeTrackpoint(writer, dataPoint)
        }
        writer.append("        </Track>\n")

        // Lap extensions with ns3 prefix (matching Garmin format)
        writer.append("        <Extensions>\n")
        writer.append("          <ns3:LX>\n")
        writer.append("            <ns3:AvgSpeed>${avgSpeedMs ?: 0.0}</ns3:AvgSpeed>\n")
        writer.append("            <ns3:AvgRunCadence>${avgCadence ?: 0}</ns3:AvgRunCadence>\n")
        writer.append("            <ns3:MaxRunCadence>${maxCadence ?: 0}</ns3:MaxRunCadence>\n")
        writer.append("            <ns3:AvgWatts>${avgPower ?: 0}</ns3:AvgWatts>\n")
        writer.append("            <ns3:MaxWatts>${maxPower ?: 0}</ns3:MaxWatts>\n")
        writer.append("          </ns3:LX>\n")
        writer.append("        </Extensions>\n")

        writer.append("      </Lap>\n")

        // Log lap stats
        val stepName = execStep?.displayName ?: "Free Run"
        Log.d(TAG, "TCX Lap: $stepName, duration=${lapTimerMs/1000}s, distance=${lapDistanceM.toInt()}m, " +
            "hr=$avgHeartRate/$maxHeartRate, cadence=$avgCadence, power=$avgPower")
    }

    /**
     * Write a single trackpoint with all available data (matching Garmin format).
     */
    private fun writeTrackpoint(writer: StringWriter, dataPoint: WorkoutDataPoint) {
        val timeIso = isoDateFormat.format(Date(dataPoint.timestampMs))
        val distanceM = dataPoint.distanceKm * 1000.0
        val speedMs = dataPoint.speedKph / 3.6
        val altitudeM = dataPoint.elevationGainM

        writer.append("          <Trackpoint>\n")
        writer.append("            <Time>$timeIso</Time>\n")

        // Always include AltitudeMeters (Garmin includes it even when 0.0)
        writer.append("            <AltitudeMeters>$altitudeM</AltitudeMeters>\n")

        // Cumulative distance
        writer.append("            <DistanceMeters>$distanceM</DistanceMeters>\n")

        // Heart rate
        if (dataPoint.heartRateBpm > 0) {
            writer.append("            <HeartRateBpm>\n")
            writer.append("              <Value>${dataPoint.heartRateBpm.toInt()}</Value>\n")
            writer.append("            </HeartRateBpm>\n")
        }

        // No <Cadence> element in Trackpoint - Garmin doesn't include it

        // Extensions with ns3 prefix - always include all fields (Garmin format)
        writer.append("            <Extensions>\n")
        writer.append("              <ns3:TPX>\n")
        writer.append("                <ns3:Speed>$speedMs</ns3:Speed>\n")
        writer.append("                <ns3:RunCadence>${dataPoint.cadenceSpm}</ns3:RunCadence>\n")
        writer.append("                <ns3:Watts>${dataPoint.powerWatts.toInt()}</ns3:Watts>\n")
        writer.append("              </ns3:TPX>\n")
        writer.append("            </Extensions>\n")

        writer.append("          </Trackpoint>\n")
    }

    /**
     * Write Creator element (device info).
     * Uses Forerunner 970. Note: TE/Load requires syncing watch TWICE after upload.
     * Serial must differ from user's watch for sync to work.
     */
    private fun writeCreator(writer: StringWriter) {
        writer.append("      <Creator xsi:type=\"Device_t\">\n")
        writer.append("        <Name>Forerunner 970</Name>\n")
        writer.append("        <UnitId>1234567890</UnitId>\n")
        writer.append("        <ProductID>4565</ProductID>\n")
        writer.append("        <Version>\n")
        writer.append("          <VersionMajor>15</VersionMajor>\n")
        writer.append("          <VersionMinor>52</VersionMinor>\n")
        writer.append("          <BuildMajor>0</BuildMajor>\n")
        writer.append("          <BuildMinor>0</BuildMinor>\n")
        writer.append("        </Version>\n")
        writer.append("      </Creator>\n")
    }

    /**
     * Write Author element (matching Garmin Connect Api format).
     */
    private fun writeAuthor(writer: StringWriter) {
        writer.append("  <Author xsi:type=\"Application_t\">\n")
        writer.append("    <Name>Connect Api</Name>\n")
        writer.append("    <Build>\n")
        writer.append("      <Version>\n")
        writer.append("        <VersionMajor>25</VersionMajor>\n")
        writer.append("        <VersionMinor>24</VersionMinor>\n")
        writer.append("        <BuildMajor>0</BuildMajor>\n")
        writer.append("        <BuildMinor>0</BuildMinor>\n")
        writer.append("      </Version>\n")
        writer.append("    </Build>\n")
        writer.append("    <LangID>en</LangID>\n")
        writer.append("    <PartNumber>006-D2449-00</PartNumber>\n")
        writer.append("  </Author>\n")
    }

    /**
     * Calculate total paused duration within a time range.
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
                pauseStartMs = event.timestampMs
            } else if (pauseStartMs != null) {
                val pauseEndMs = event.timestampMs
                val overlapStart = maxOf(rangeStartMs, pauseStartMs)
                val overlapEnd = minOf(rangeEndMs, pauseEndMs)
                if (overlapStart < overlapEnd) {
                    totalPausedMs += overlapEnd - overlapStart
                }
                pauseStartMs = null
            }
        }

        // Handle pause still active at end of range
        if (pauseStartMs != null && pauseStartMs < rangeEndMs) {
            val overlapStart = maxOf(rangeStartMs, pauseStartMs)
            totalPausedMs += rangeEndMs - overlapStart
        }

        return totalPausedMs
    }

    /**
     * Write TCX file to MediaStore Downloads folder.
     */
    private fun writeTcxFileToMediaStore(filename: String, content: String): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Failed to open output stream")

        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        return "Downloads/$SUBFOLDER/$filename"
    }
}
