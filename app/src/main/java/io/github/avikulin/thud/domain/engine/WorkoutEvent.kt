package io.github.avikulin.thud.domain.engine

/**
 * Events emitted by the workout execution engine.
 * Consumed by UI to show feedback and notifications.
 */
sealed class WorkoutEvent {

    /**
     * A step has started execution.
     * Includes effective pace/incline with adjustment coefficients applied.
     */
    data class StepStarted(
        val step: ExecutionStep,
        val effectivePaceKph: Double,
        val effectiveInclinePercent: Double
    ) : WorkoutEvent()

    /**
     * A step has completed.
     */
    data class StepCompleted(val step: ExecutionStep) : WorkoutEvent()

    /**
     * Workout was resumed from paused state.
     * Includes effective pace/incline (HR-adjusted if active, otherwise base step values).
     */
    data class WorkoutResumed(
        val step: ExecutionStep,
        val effectivePaceKph: Double,
        val effectiveInclinePercent: Double
    ) : WorkoutEvent()

    /**
     * Speed was automatically adjusted due to HR target.
     */
    data class SpeedAdjusted(
        val newSpeedKph: Double,
        val direction: String,
        val reason: String
    ) : WorkoutEvent()

    /**
     * Incline was automatically adjusted due to HR target.
     */
    data class InclineAdjusted(
        val newInclinePercent: Double,
        val direction: String,
        val reason: String
    ) : WorkoutEvent()

    /**
     * Heart rate is outside the target range.
     */
    data class HrOutOfRange(
        val currentHr: Int,
        val targetMin: Int,
        val targetMax: Int
    ) : WorkoutEvent()

    /**
     * Heart rate has returned to target range.
     */
    data class HrBackInRange(
        val currentHr: Int,
        val targetMin: Int,
        val targetMax: Int
    ) : WorkoutEvent()

    /**
     * HR early end condition was triggered - step ends early because HR entered target range.
     */
    data class HrEarlyEndTriggered(
        val currentHr: Int,
        val targetMin: Int,
        val targetMax: Int
    ) : WorkoutEvent()

    /**
     * All planned steps have finished, entering auto-cooldown.
     * Workout continues running until user stops.
     */
    data class WorkoutPlanFinished(val stepsCompleted: Int) : WorkoutEvent()

    /**
     * Workout has completed (user stopped or called stop()).
     */
    data class WorkoutCompleted(val summary: WorkoutSummary) : WorkoutEvent()

    /**
     * An error occurred during workout execution.
     */
    data class Error(val message: String) : WorkoutEvent()

    /**
     * Effort coefficient was changed by user pressing physical buttons.
     */
    data class EffortAdjusted(
        val type: String,
        val coefficient: Double,
        val displayString: String
    ) : WorkoutEvent()
}

/**
 * Summary data for a completed workout.
 */
data class WorkoutSummary(
    val workoutName: String,
    val stepsCompleted: Int,
    val totalSteps: Int
)
