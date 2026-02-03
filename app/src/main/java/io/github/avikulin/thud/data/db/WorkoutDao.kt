package io.github.avikulin.thud.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.entity.WorkoutStep
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for workouts and workout steps.
 */
@Dao
interface WorkoutDao {

    // ==================== Workouts ====================

    @Query("SELECT * FROM workouts ORDER BY updatedAt DESC")
    fun getAllWorkouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts ORDER BY updatedAt DESC")
    suspend fun getAllWorkoutsOnce(): List<Workout>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getWorkoutById(id: Long): Workout?

    @Insert
    suspend fun insertWorkout(workout: Workout): Long

    @Update
    suspend fun updateWorkout(workout: Workout)

    @Delete
    suspend fun deleteWorkout(workout: Workout)

    // ==================== Workout Steps ====================

    @Query("SELECT * FROM workout_steps WHERE workoutId = :workoutId ORDER BY orderIndex")
    fun getStepsForWorkout(workoutId: Long): Flow<List<WorkoutStep>>

    @Query("SELECT * FROM workout_steps WHERE workoutId = :workoutId ORDER BY orderIndex")
    suspend fun getStepsForWorkoutOnce(workoutId: Long): List<WorkoutStep>

    @Insert
    suspend fun insertStep(step: WorkoutStep): Long

    @Insert
    suspend fun insertSteps(steps: List<WorkoutStep>)

    @Update
    suspend fun updateStep(step: WorkoutStep)

    @Delete
    suspend fun deleteStep(step: WorkoutStep)

    @Query("DELETE FROM workout_steps WHERE workoutId = :workoutId")
    suspend fun deleteAllStepsForWorkout(workoutId: Long)

    // ==================== Transactions ====================

    /**
     * Save a workout with its steps in a single transaction.
     * Deletes existing steps and replaces with new ones.
     *
     * IMPORTANT: REPEAT steps must be inserted first to get their new IDs,
     * then substeps are updated with the correct parentRepeatStepId.
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

        if (steps.isEmpty()) return

        // Separate REPEAT steps from substeps
        val repeatSteps = steps.filter { it.type == io.github.avikulin.thud.domain.model.StepType.REPEAT }
        val otherSteps = steps.filter { it.type != io.github.avikulin.thud.domain.model.StepType.REPEAT }

        // Build mapping from old temp ID to new database ID for REPEAT steps
        val oldIdToNewId = mutableMapOf<Long, Long>()

        // Insert REPEAT steps first (one by one to get new IDs)
        for (repeatStep in repeatSteps) {
            val oldId = repeatStep.id
            val newId = insertStep(repeatStep.copy(id = 0, workoutId = workoutId))
            oldIdToNewId[oldId] = newId
        }

        // Insert other steps with updated parentRepeatStepId
        val stepsToInsert = otherSteps.map { step ->
            val newParentId = step.parentRepeatStepId?.let { oldIdToNewId[it] }
            step.copy(id = 0, workoutId = workoutId, parentRepeatStepId = newParentId)
        }
        if (stepsToInsert.isNotEmpty()) {
            insertSteps(stepsToInsert)
        }
    }
}
