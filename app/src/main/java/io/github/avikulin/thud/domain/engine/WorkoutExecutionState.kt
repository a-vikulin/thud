package io.github.avikulin.thud.domain.engine

import io.github.avikulin.thud.data.entity.Workout

/**
 * Represents the current state of workout execution.
 * The workout engine transitions between these states during execution.
 */
sealed class WorkoutExecutionState {

    /**
     * No workout is loaded or active.
     */
    object Idle : WorkoutExecutionState()

    /**
     * Workout is actively running.
     *
     * @param countdownSeconds Countdown to next step (3, 2, 1) when step is about to end.
     *                        Null when not in countdown phase.
     */
    data class Running(
        val workout: Workout,
        val currentStepIndex: Int,
        val currentStep: ExecutionStep,
        val stepElapsedMs: Long,
        val stepDistanceMeters: Double,
        val workoutElapsedMs: Long,
        val workoutDistanceMeters: Double,
        val currentPaceKph: Double,
        val currentIncline: Double,
        val isHrAdjustmentActive: Boolean,
        val hrAdjustmentDirection: String?,
        val countdownSeconds: Int? = null
    ) : WorkoutExecutionState()

    /**
     * Workout is paused.
     */
    data class Paused(
        val workout: Workout,
        val currentStepIndex: Int,
        val currentStep: ExecutionStep,
        val stepElapsedMs: Long,
        val stepDistanceMeters: Double,
        val workoutElapsedMs: Long,
        val workoutDistanceMeters: Double
    ) : WorkoutExecutionState()

    /**
     * Workout has completed.
     */
    data class Completed(
        val workout: Workout,
        val totalDurationMs: Long,
        val totalDistanceMeters: Double
    ) : WorkoutExecutionState()
}
