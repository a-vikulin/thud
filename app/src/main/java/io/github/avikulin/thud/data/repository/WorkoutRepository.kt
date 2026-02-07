package io.github.avikulin.thud.data.repository

import io.github.avikulin.thud.data.db.WorkoutDao
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.util.PaceConverter
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing workouts and workout steps.
 * Provides a clean API for the domain layer to access workout data.
 */
class WorkoutRepository(
    private val workoutDao: WorkoutDao
) {
    companion object {
        const val SYSTEM_TYPE_WARMUP = "WARMUP"
        const val SYSTEM_TYPE_COOLDOWN = "COOLDOWN"
    }

    /**
     * Flow of all workouts, ordered: system workouts first, then by most recently updated/executed.
     */
    val allWorkouts: Flow<List<Workout>> = workoutDao.getAllWorkouts()

    /**
     * Get a single workout by ID.
     */
    suspend fun getWorkout(id: Long): Workout? = workoutDao.getWorkoutById(id)

    /**
     * Get steps for a workout as a Flow.
     */
    fun getWorkoutSteps(workoutId: Long): Flow<List<WorkoutStep>> =
        workoutDao.getStepsForWorkout(workoutId)

    /**
     * Get a workout with its steps in a single call.
     */
    suspend fun getWorkoutWithSteps(id: Long): Pair<Workout, List<WorkoutStep>>? {
        val workout = workoutDao.getWorkoutById(id) ?: return null
        val steps = workoutDao.getStepsForWorkoutOnce(id)
        return workout to steps
    }

    /**
     * Create a new empty workout.
     * Returns the ID of the created workout.
     */
    suspend fun createWorkout(name: String, description: String? = null): Long {
        return workoutDao.insertWorkout(Workout(name = name, description = description))
    }

    /**
     * Save a workout with its steps.
     * Updates summary fields (duration, distance, step count) automatically.
     * TSS is passed through from the workout parameter (calculated by ViewModel).
     */
    suspend fun saveWorkout(workout: Workout, steps: List<WorkoutStep>) {
        val updatedWorkout = workout.copy(
            updatedAt = System.currentTimeMillis(),
            stepCount = countExecutableSteps(steps),
            estimatedDurationSeconds = calculateEstimatedDuration(steps),
            estimatedDistanceMeters = calculateEstimatedDistance(steps),
            estimatedTss = workout.estimatedTss
        )
        workoutDao.saveWorkoutWithSteps(updatedWorkout, steps)
    }

    /**
     * Delete a workout (steps are deleted automatically via CASCADE).
     */
    suspend fun deleteWorkout(workout: Workout) = workoutDao.deleteWorkout(workout)

    // ==================== System Workouts ====================

    /**
     * Ensure system workouts (Default Warmup, Default Cooldown) exist.
     * Idempotent — safe to call on every app startup.
     */
    suspend fun ensureSystemWorkoutsExist() {
        if (workoutDao.getSystemWorkout(SYSTEM_TYPE_WARMUP) == null) {
            val warmupId = workoutDao.insertWorkout(Workout(
                name = "Default Warmup",
                systemWorkoutType = SYSTEM_TYPE_WARMUP
            ))
            workoutDao.insertStep(WorkoutStep(
                workoutId = warmupId,
                orderIndex = 0,
                type = StepType.WARMUP,
                durationType = DurationType.TIME,
                durationSeconds = 300,
                paceTargetKph = 5.0,
                inclineTargetPercent = 0.0
            ))
        }

        if (workoutDao.getSystemWorkout(SYSTEM_TYPE_COOLDOWN) == null) {
            val cooldownId = workoutDao.insertWorkout(Workout(
                name = "Default Cooldown",
                systemWorkoutType = SYSTEM_TYPE_COOLDOWN
            ))
            workoutDao.insertStep(WorkoutStep(
                workoutId = cooldownId,
                orderIndex = 0,
                type = StepType.COOLDOWN,
                durationType = DurationType.TIME,
                durationSeconds = 300,
                paceTargetKph = 4.0,
                inclineTargetPercent = 0.0
            ))
        }
    }

    /**
     * Get the system warmup workout with its steps.
     */
    suspend fun getSystemWarmup(): Pair<Workout, List<WorkoutStep>>? {
        val workout = workoutDao.getSystemWorkout(SYSTEM_TYPE_WARMUP) ?: return null
        val steps = workoutDao.getStepsForWorkoutOnce(workout.id)
        return workout to steps
    }

    /**
     * Get the system cooldown workout with its steps.
     */
    suspend fun getSystemCooldown(): Pair<Workout, List<WorkoutStep>>? {
        val workout = workoutDao.getSystemWorkout(SYSTEM_TYPE_COOLDOWN) ?: return null
        val steps = workoutDao.getStepsForWorkoutOnce(workout.id)
        return workout to steps
    }

    /**
     * Update the lastExecutedAt timestamp for a workout.
     */
    suspend fun markAsExecuted(workoutId: Long) {
        workoutDao.updateLastExecutedAt(workoutId, System.currentTimeMillis())
    }

    /**
     * Duplicate a workout with a new name.
     * Returns the ID of the new workout.
     */
    suspend fun duplicateWorkout(workoutId: Long, newName: String): Long? {
        val (original, steps) = getWorkoutWithSteps(workoutId) ?: return null

        val newWorkout = original.copy(
            id = 0,
            name = newName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            systemWorkoutType = null,  // Copies are always regular workouts
            lastExecutedAt = null
        )

        val newWorkoutId = workoutDao.insertWorkout(newWorkout)

        // Copy steps with new workout ID and reset IDs
        val newSteps = steps.map { step ->
            step.copy(id = 0, workoutId = newWorkoutId)
        }
        workoutDao.insertSteps(newSteps)

        return newWorkoutId
    }

    // ==================== Helper Functions ====================

    /**
     * Count executable steps (excludes REPEAT containers).
     * Uses position-based traversal to find children.
     */
    private fun countExecutableSteps(steps: List<WorkoutStep>): Int {
        var count = 0
        var stepIndex = 0

        while (stepIndex < steps.size) {
            val step = steps[stepIndex]

            if (step.type == StepType.REPEAT) {
                // Get child steps using position-based traversal
                var childCount = 0
                var childIndex = stepIndex + 1
                while (childIndex < steps.size && steps[childIndex].parentRepeatStepId != null) {
                    childCount++
                    childIndex++
                }
                count += childCount * (step.repeatCount ?: 1)
                stepIndex = childIndex
            } else if (step.parentRepeatStepId == null) {
                count++
                stepIndex++
            } else {
                stepIndex++
            }
        }
        return count
    }

    /**
     * Calculate estimated duration in seconds.
     * Cross-calculates time from distance-based steps using pace.
     * Uses position-based traversal to find children.
     */
    private fun calculateEstimatedDuration(steps: List<WorkoutStep>): Int? {
        var totalSeconds = 0.0
        var hasSteps = false
        var stepIndex = 0

        while (stepIndex < steps.size) {
            val step = steps[stepIndex]

            if (step.type == StepType.REPEAT) {
                // Get child steps using position-based traversal
                val childSteps = mutableListOf<WorkoutStep>()
                var childIndex = stepIndex + 1
                while (childIndex < steps.size && steps[childIndex].parentRepeatStepId != null) {
                    childSteps.add(steps[childIndex])
                    childIndex++
                }

                val repeatCount = step.repeatCount ?: 1
                for (child in childSteps) {
                    val seconds = calculateStepDuration(child)
                    if (seconds > 0) {
                        totalSeconds += seconds * repeatCount
                        hasSteps = true
                    }
                }
                stepIndex = childIndex
            } else if (step.parentRepeatStepId == null) {
                val seconds = calculateStepDuration(step)
                if (seconds > 0) {
                    totalSeconds += seconds
                    hasSteps = true
                }
                stepIndex++
            } else {
                stepIndex++
            }
        }

        return if (hasSteps && totalSeconds > 0) totalSeconds.toInt() else null
    }

    /**
     * Calculate duration of a single step in seconds.
     * For distance-based steps, calculates time from distance and pace.
     */
    private fun calculateStepDuration(step: WorkoutStep): Double {
        return when (step.durationType) {
            DurationType.TIME -> (step.durationSeconds ?: 0).toDouble()
            DurationType.DISTANCE -> PaceConverter.calculateDurationSeconds(
                step.durationMeters ?: 0,
                step.paceTargetKph
            )
        }
    }

    /**
     * Calculate estimated distance in meters.
     * Cross-calculates distance from time-based steps using pace.
     * Uses position-based traversal to find children.
     */
    private fun calculateEstimatedDistance(steps: List<WorkoutStep>): Int? {
        var totalMeters = 0.0
        var hasSteps = false
        var stepIndex = 0

        while (stepIndex < steps.size) {
            val step = steps[stepIndex]

            if (step.type == StepType.REPEAT) {
                // Get child steps using position-based traversal
                val childSteps = mutableListOf<WorkoutStep>()
                var childIndex = stepIndex + 1
                while (childIndex < steps.size && steps[childIndex].parentRepeatStepId != null) {
                    childSteps.add(steps[childIndex])
                    childIndex++
                }

                val repeatCount = step.repeatCount ?: 1
                for (child in childSteps) {
                    val meters = calculateStepDistance(child)
                    if (meters > 0) {
                        totalMeters += meters * repeatCount
                        hasSteps = true
                    }
                }
                stepIndex = childIndex
            } else if (step.parentRepeatStepId == null) {
                val meters = calculateStepDistance(step)
                if (meters > 0) {
                    totalMeters += meters
                    hasSteps = true
                }
                stepIndex++
            } else {
                stepIndex++
            }
        }

        return if (hasSteps && totalMeters > 0) totalMeters.toInt() else null
    }

    /**
     * Calculate distance of a single step in meters.
     * For time-based steps, calculates distance from time and pace.
     */
    private fun calculateStepDistance(step: WorkoutStep): Double {
        return when (step.durationType) {
            DurationType.DISTANCE -> (step.durationMeters ?: 0).toDouble()
            DurationType.TIME -> PaceConverter.calculateDistanceMeters(
                step.durationSeconds ?: 0,
                step.paceTargetKph
            )
        }
    }
}
