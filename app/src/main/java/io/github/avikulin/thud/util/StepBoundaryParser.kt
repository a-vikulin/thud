package io.github.avikulin.thud.util

import io.github.avikulin.thud.data.model.WorkoutDataPoint

/**
 * Represents the time boundaries of a workout step as determined from actual recorded data.
 * Used for both FIT file lap creation and chart visualization of past steps.
 */
data class StepTimeBoundary(
    val stepIndex: Int,
    val startElapsedMs: Long,
    val endElapsedMs: Long
)

/**
 * Parses workout data points to determine actual step boundaries.
 * Groups consecutive data points by stepIndex and returns the time boundaries.
 *
 * This is useful for:
 * - FitFileExporter: Creating lap messages for each workout step
 * - WorkoutChart: Drawing past steps' HR targets at actual executed positions
 */
object StepBoundaryParser {

    /**
     * Parse step boundaries from recorded data points.
     * Groups consecutive points by stepIndex and returns boundaries with elapsed times.
     * Skips invalid step indices (< 0) and zero-duration boundaries.
     *
     * @param dataPoints List of recorded workout data points (must be sorted by time)
     * @return List of step boundaries, one per valid step that was executed
     */
    fun parseStepBoundaries(dataPoints: List<WorkoutDataPoint>): List<StepTimeBoundary> {
        if (dataPoints.isEmpty()) return emptyList()

        val boundaries = mutableListOf<StepTimeBoundary>()
        var groupStartPoint = dataPoints.first()
        var currentStepIndex = groupStartPoint.stepIndex

        for (i in 1 until dataPoints.size) {
            val point = dataPoints[i]
            if (point.stepIndex != currentStepIndex) {
                // Step changed - record the boundary for the previous step
                // Use the NEW step's start time as the previous step's end time
                // This matches FIT lap calculation: next_start - this_start = duration
                // Eliminates gaps between steps in chart visualization
                val endElapsedMs = point.elapsedMs
                if (currentStepIndex >= 0 && endElapsedMs > groupStartPoint.elapsedMs) {
                    boundaries.add(
                        StepTimeBoundary(
                            stepIndex = currentStepIndex,
                            startElapsedMs = groupStartPoint.elapsedMs,
                            endElapsedMs = endElapsedMs
                        )
                    )
                }
                groupStartPoint = point
                currentStepIndex = point.stepIndex
            }
        }

        // Add final group (skip if invalid or zero-duration)
        val finalEndMs = dataPoints.last().elapsedMs
        if (currentStepIndex >= 0 && finalEndMs > groupStartPoint.elapsedMs) {
            boundaries.add(
                StepTimeBoundary(
                    stepIndex = currentStepIndex,
                    startElapsedMs = groupStartPoint.elapsedMs,
                    endElapsedMs = finalEndMs
                )
            )
        }

        return boundaries
    }

    /**
     * Group data points by step, returning the actual data points for each step.
     * Useful for calculating per-step metrics (avg HR, distance, etc).
     * Skips groups with invalid step indices (< 0).
     *
     * @param dataPoints List of recorded workout data points (must be sorted by time)
     * @return List of data point groups, one per valid step that was executed
     */
    fun groupByStep(dataPoints: List<WorkoutDataPoint>): List<List<WorkoutDataPoint>> {
        if (dataPoints.isEmpty()) return emptyList()

        val groups = mutableListOf<List<WorkoutDataPoint>>()
        var currentGroup = mutableListOf<WorkoutDataPoint>()
        var currentStepIndex = dataPoints.first().stepIndex

        for (dataPoint in dataPoints) {
            if (dataPoint.stepIndex != currentStepIndex && currentGroup.isNotEmpty()) {
                // Only add groups with valid step indices
                if (currentStepIndex >= 0) {
                    groups.add(currentGroup.toList())
                }
                currentGroup = mutableListOf()
                currentStepIndex = dataPoint.stepIndex
            }
            currentGroup.add(dataPoint)
        }

        // Add final group if valid
        if (currentGroup.isNotEmpty() && currentStepIndex >= 0) {
            groups.add(currentGroup.toList())
        }

        return groups
    }
}
