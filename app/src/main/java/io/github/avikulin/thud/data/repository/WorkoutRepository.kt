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
        val (stepCount, estDuration, estDistance) = computeWorkoutSummary(steps)
        val updatedWorkout = workout.copy(
            updatedAt = System.currentTimeMillis(),
            stepCount = stepCount,
            estimatedDurationSeconds = estDuration,
            estimatedDistanceMeters = estDistance,
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
     * Each check-and-insert is atomic via @Transaction to prevent duplicates.
     */
    suspend fun ensureSystemWorkoutsExist() {
        workoutDao.ensureSystemWorkoutExists(
            type = SYSTEM_TYPE_WARMUP,
            workout = Workout(name = "Default Warmup", systemWorkoutType = SYSTEM_TYPE_WARMUP),
            step = WorkoutStep(
                workoutId = 0, // Replaced by DAO with actual workout ID
                orderIndex = 0,
                type = StepType.WARMUP,
                durationType = DurationType.TIME,
                durationSeconds = 300,
                paceTargetKph = 5.0,
                inclineTargetPercent = 0.0
            )
        )

        workoutDao.ensureSystemWorkoutExists(
            type = SYSTEM_TYPE_COOLDOWN,
            workout = Workout(name = "Default Cooldown", systemWorkoutType = SYSTEM_TYPE_COOLDOWN),
            step = WorkoutStep(
                workoutId = 0, // Replaced by DAO with actual workout ID
                orderIndex = 0,
                type = StepType.COOLDOWN,
                durationType = DurationType.TIME,
                durationSeconds = 300,
                paceTargetKph = 4.0,
                inclineTargetPercent = 0.0
            )
        )
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

        if (steps.isNotEmpty()) {
            workoutDao.remapAndInsertSteps(steps, newWorkoutId)
        }

        return newWorkoutId
    }

    // ==================== Helper Functions ====================

    /**
     * Traverse steps handling REPEAT blocks: calls [block] for each executable step
     * with its effective repeat multiplier (1 for top-level, N for REPEAT children).
     */
    private inline fun forEachEffectiveStep(
        steps: List<WorkoutStep>,
        block: (step: WorkoutStep, repeatCount: Int) -> Unit
    ) {
        var stepIndex = 0
        while (stepIndex < steps.size) {
            val step = steps[stepIndex]
            if (step.type == StepType.REPEAT) {
                val repeatCount = step.repeatCount ?: 1
                var childIndex = stepIndex + 1
                while (childIndex < steps.size && steps[childIndex].parentRepeatStepId != null) {
                    block(steps[childIndex], repeatCount)
                    childIndex++
                }
                stepIndex = childIndex
            } else if (step.parentRepeatStepId == null) {
                block(step, 1)
                stepIndex++
            } else {
                stepIndex++
            }
        }
    }

    /**
     * Compute step count, estimated duration, and estimated distance in a single pass.
     */
    private fun computeWorkoutSummary(steps: List<WorkoutStep>): Triple<Int, Int?, Int?> {
        var count = 0
        var totalSeconds = 0.0; var hasDuration = false
        var totalMeters = 0.0; var hasDistance = false
        forEachEffectiveStep(steps) { step, repeatCount ->
            count += repeatCount
            val sec = calculateStepDuration(step)
            if (sec > 0) { totalSeconds += sec * repeatCount; hasDuration = true }
            val m = calculateStepDistance(step)
            if (m > 0) { totalMeters += m * repeatCount; hasDistance = true }
        }
        return Triple(
            count,
            if (hasDuration && totalSeconds > 0) totalSeconds.toInt() else null,
            if (hasDistance && totalMeters > 0) totalMeters.toInt() else null
        )
    }

    private fun calculateStepDuration(step: WorkoutStep): Double {
        return when (step.durationType) {
            DurationType.TIME -> (step.durationSeconds ?: 0).toDouble()
            DurationType.DISTANCE -> PaceConverter.calculateDurationSeconds(
                step.durationMeters ?: 0,
                step.paceTargetKph
            )
        }
    }

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
