package io.github.avikulin.thud.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.domain.model.StepType
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for workouts and workout steps.
 */
@Dao
interface WorkoutDao {

    // ==================== Workouts ====================

    /**
     * Get all workouts ordered: system workouts first (Warmup, Cooldown),
     * then user workouts by most recently edited or executed.
     */
    @Query("""
        SELECT * FROM workouts ORDER BY
            CASE systemWorkoutType
                WHEN 'WARMUP' THEN 0
                WHEN 'COOLDOWN' THEN 1
                ELSE 2
            END,
            MAX(updatedAt, COALESCE(lastExecutedAt, 0)) DESC
    """)
    fun getAllWorkouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getWorkoutById(id: Long): Workout?

    @Query("SELECT * FROM workouts WHERE systemWorkoutType = :type LIMIT 1")
    suspend fun getSystemWorkout(type: String): Workout?

    @Query("UPDATE workouts SET lastExecutedAt = :timestamp WHERE id = :workoutId")
    suspend fun updateLastExecutedAt(workoutId: Long, timestamp: Long)

    @Insert
    suspend fun insertWorkout(workout: Workout): Long

    @Update
    suspend fun updateWorkout(workout: Workout)

    @Delete
    suspend fun deleteWorkout(workout: Workout)

    // ==================== Workout Steps ====================

    @Query("SELECT * FROM workout_steps WHERE workoutId = :workoutId ORDER BY orderIndex")
    suspend fun getStepsForWorkoutOnce(workoutId: Long): List<WorkoutStep>

    @Insert
    suspend fun insertStep(step: WorkoutStep): Long

    @Insert
    suspend fun insertSteps(steps: List<WorkoutStep>)

    @Query("DELETE FROM workout_steps WHERE workoutId = :workoutId")
    suspend fun deleteAllStepsForWorkout(workoutId: Long)

    // ==================== Transactions ====================

    /**
     * Atomically check if a system workout exists and create it if not.
     * Prevents duplicate system workouts from concurrent calls.
     */
    @Transaction
    suspend fun ensureSystemWorkoutExists(type: String, workout: Workout, step: WorkoutStep) {
        if (getSystemWorkout(type) == null) {
            val id = insertWorkout(workout)
            insertStep(step.copy(workoutId = id))
        }
    }

    /**
     * Save a workout with its steps in a single transaction.
     * Deletes existing steps and replaces with new ones.
     */
    @Transaction
    suspend fun saveWorkoutWithSteps(workout: Workout, steps: List<WorkoutStep>) {
        val workoutId = if (workout.id == 0L) {
            insertWorkout(workout)
        } else {
            updateWorkout(workout)
            deleteAllStepsForWorkout(workout.id)
            workout.id
        }

        if (steps.isNotEmpty()) {
            remapAndInsertSteps(steps, workoutId)
        }
    }

    /**
     * Insert steps with REPEAT ID remapping.
     * REPEAT steps are inserted first (one by one) to capture their new DB IDs,
     * then children are batch-inserted with remapped parentRepeatStepId.
     */
    suspend fun remapAndInsertSteps(steps: List<WorkoutStep>, workoutId: Long) {
        val repeatSteps = steps.filter { it.type == StepType.REPEAT }
        val otherSteps = steps.filter { it.type != StepType.REPEAT }

        val oldIdToNewId = mutableMapOf<Long, Long>()
        for (repeatStep in repeatSteps) {
            oldIdToNewId[repeatStep.id] = insertStep(repeatStep.copy(id = 0, workoutId = workoutId))
        }

        val stepsToInsert = otherSteps.map { step ->
            step.copy(
                id = 0, workoutId = workoutId,
                parentRepeatStepId = step.parentRepeatStepId?.let { oldIdToNewId[it] }
            )
        }
        if (stepsToInsert.isNotEmpty()) {
            insertSteps(stepsToInsert)
        }
    }
}
