package io.github.avikulin.thud.domain.engine

import android.util.Log
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.data.repository.WorkoutRepository
import io.github.avikulin.thud.domain.model.AdjustmentScope
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.EarlyEndCondition
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.service.AdjustmentControllerState
import io.github.avikulin.thud.service.EnginePersistenceState
import io.github.avikulin.thud.service.ServiceStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Executes structured workouts with step progression, HR-based adjustments,
 * and treadmill control integration.
 *
 * State Flow:
 * ```
 * Idle -> Running <-> Paused -> Completed
 * ```
 *
 * Countdown (3-2-1) is shown during the last 3 seconds of each step while
 * still in Running state. Next step starts immediately when current ends.
 */
class WorkoutExecutionEngine(
    private val repository: WorkoutRepository,
    private val hrDataProvider: () -> List<MetricDataPoint>,
    private val powerDataProvider: () -> List<MetricDataPoint>,
    private val stateHolder: ServiceStateHolder,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "WorkoutEngine"

        // Auto-cooldown settings when planned steps complete
        private const val AUTO_COOLDOWN_SPEED_KPH = 4.0
        private const val AUTO_COOLDOWN_INCLINE_PERCENT = 0.0
    }

    // State
    private val _state = MutableStateFlow<WorkoutExecutionState>(WorkoutExecutionState.Idle)
    val state: StateFlow<WorkoutExecutionState> = _state.asStateFlow()

    // Events
    private val _events = MutableSharedFlow<WorkoutEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<WorkoutEvent> = _events.asSharedFlow()

    // Current workout data
    private var currentWorkout: Workout? = null
    private var originalSteps: List<WorkoutStep> = emptyList()  // Original hierarchical steps for FIT export
    private var executionSteps: MutableList<ExecutionStep> = mutableListOf()
    private var currentStepIndex: Int = -1
    private var plannedStepsCount: Int = 0  // Original count before auto-cooldown added
    private var inAutoCooldown: Boolean = false

    // Phase boundary tracking for stitched workouts (warmup + main + cooldown)
    private var warmupStepCount: Int = 0
    private var mainStepCount: Int = 0
    private var cooldownStepCount: Int = 0

    // Timing - uses treadmill's elapsed time as single source of truth
    private var treadmillElapsedSeconds: Int = 0
    private var workoutStartSeconds: Int = -1  // -1 means not yet initialized
    private var stepStartSeconds: Int = -1     // -1 means not yet initialized
    private var pausedAtSeconds: Int = 0
    private var totalPausedSeconds: Int = 0
    private var timingInitialized: Boolean = false

    // Cached timing values - these are the source of truth for UI display
    // Only updated by onElapsedTimeUpdate(), used by all state updates
    private var cachedStepElapsedMs: Long = 0
    private var cachedWorkoutElapsedMs: Long = 0

    // Distance tracking
    private var workoutStartDistanceKm: Double = 0.0
    private var stepStartDistanceKm: Double = 0.0
    private var lastDistanceKm: Double = 0.0

    // Current telemetry
    private var currentSpeedKph: Double = 0.0
    private var currentInclinePercent: Double = 0.0
    private var currentHeartRateBpm: Double = 0.0
    private var currentPowerWatts: Double = 0.0  // Incline-adjusted power from Stryd

    // Adjustment coefficients - apply to all step targets when executing
    // Calculated from reported speed/incline vs step target
    // Modified by HR auto-adjust or manual button presses
    private var speedAdjustmentCoefficient: Double = 1.0
    private var inclineAdjustmentCoefficient: Double = 1.0
    private var hasReachedTargetSpeed: Boolean = false  // Don't calc coefficient during initial acceleration

    // Per-step coefficient scoping (ONE_STEP mode)
    // Key: stepIdentityKey, Value: (speedCoeff, inclineCoeff)
    private var adjustmentScope: AdjustmentScope = AdjustmentScope.ALL_STEPS
    private val stepCoefficients: MutableMap<String, Pair<Double, Double>> = mutableMapOf()

    // Adjustment controller (HR and Power)
    private val adjustmentController = AdjustmentController()
    private var wasHrOutOfRange = false

    // ==================== Control Methods ====================

    /**
     * Load a workout from the database.
     * Must be called before start().
     *
     * @param workoutId ID of the workout to load
     * @return true if loaded successfully
     */
    suspend fun loadWorkout(workoutId: Long): Boolean {
        if (_state.value !is WorkoutExecutionState.Idle) {
            Log.w(TAG, "Cannot load workout while another is active")
            return false
        }

        val workoutWithSteps = repository.getWorkoutWithSteps(workoutId)
        if (workoutWithSteps == null) {
            Log.e(TAG, "Workout not found: $workoutId")
            emitEvent(WorkoutEvent.Error("Workout not found"))
            return false
        }

        val (workout, steps) = workoutWithSteps
        if (steps.isEmpty()) {
            Log.e(TAG, "Workout has no steps: $workoutId")
            emitEvent(WorkoutEvent.Error("Workout has no steps"))
            return false
        }

        currentWorkout = workout
        originalSteps = steps  // Store original hierarchical steps for FIT export
        executionSteps = WorkoutStepFlattener.flatten(steps).toMutableList()
        plannedStepsCount = executionSteps.size
        inAutoCooldown = false
        currentStepIndex = -1
        adjustmentScope = workout.adjustmentScope
        stepCoefficients.clear()

        Log.d(TAG, "Loaded workout: ${workout.name} with ${executionSteps.size} steps (${steps.size} original)")
        return true
    }

    /**
     * Load a stitched workout with optional warmup/cooldown phases.
     * Each phase's steps are flattened independently, then concatenated.
     * Phase boundaries are tracked for coefficient reset logic.
     *
     * @param workout The main workout entity
     * @param mainSteps The main workout's steps (from DB)
     * @param warmupSteps System warmup steps (null = no warmup)
     * @param cooldownSteps System cooldown steps (null = no cooldown)
     * @return true if loaded successfully
     */
    fun loadStitchedWorkout(
        workout: Workout,
        mainSteps: List<WorkoutStep>,
        warmupSteps: List<WorkoutStep>?,
        cooldownSteps: List<WorkoutStep>?
    ): Boolean {
        if (_state.value !is WorkoutExecutionState.Idle) {
            Log.w(TAG, "Cannot load workout while another is active")
            return false
        }

        if (mainSteps.isEmpty()) {
            Log.e(TAG, "Workout has no steps: ${workout.id}")
            emitEvent(WorkoutEvent.Error("Workout has no steps"))
            return false
        }

        // Flatten each phase independently
        val warmupExecSteps = if (!warmupSteps.isNullOrEmpty()) {
            WorkoutStepFlattener.flatten(warmupSteps)
        } else emptyList()

        val mainExecSteps = WorkoutStepFlattener.flatten(mainSteps)

        val cooldownExecSteps = if (!cooldownSteps.isNullOrEmpty()) {
            WorkoutStepFlattener.flatten(cooldownSteps)
        } else emptyList()

        // Store phase counts
        warmupStepCount = warmupExecSteps.size
        mainStepCount = mainExecSteps.size
        cooldownStepCount = cooldownExecSteps.size

        // Concatenate all phases
        executionSteps = (warmupExecSteps + mainExecSteps + cooldownExecSteps).toMutableList()

        currentWorkout = workout
        originalSteps = mainSteps  // Only main workout's original steps for FIT export
        plannedStepsCount = executionSteps.size
        inAutoCooldown = false
        currentStepIndex = -1
        adjustmentScope = workout.adjustmentScope
        stepCoefficients.clear()

        Log.d(TAG, "Loaded stitched workout: ${workout.name} — " +
            "warmup=$warmupStepCount, main=$mainStepCount, cooldown=$cooldownStepCount " +
            "(total=${executionSteps.size} steps)")
        return true
    }

    /**
     * Start the loaded workout from the beginning.
     */
    fun start() {
        val workout = currentWorkout
        if (workout == null) {
            Log.e(TAG, "No workout loaded")
            emitEvent(WorkoutEvent.Error("No workout loaded"))
            return
        }

        if (_state.value !is WorkoutExecutionState.Idle) {
            Log.w(TAG, "Workout already running")
            return
        }

        if (executionSteps.isEmpty()) {
            Log.e(TAG, "No steps to execute")
            emitEvent(WorkoutEvent.Error("No steps to execute"))
            return
        }

        // Initialize timing immediately using current treadmill elapsed time
        // This ensures workout timer starts in sync with HUD timer
        workoutStartSeconds = treadmillElapsedSeconds
        stepStartSeconds = treadmillElapsedSeconds
        timingInitialized = true
        totalPausedSeconds = 0
        cachedStepElapsedMs = 0
        cachedWorkoutElapsedMs = 0
        workoutStartDistanceKm = lastDistanceKm

        Log.d(TAG, "Starting workout: ${workout.name}, treadmillElapsed=$treadmillElapsedSeconds")

        // Start first step
        startStep(0)
    }

    /**
     * Pause the running workout.
     */
    fun pause() {
        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) {
            Log.w(TAG, "Cannot pause - not running")
            return
        }

        pausedAtSeconds = treadmillElapsedSeconds

        _state.value = WorkoutExecutionState.Paused(
            workout = currentState.workout,
            currentStepIndex = currentState.currentStepIndex,
            currentStep = currentState.currentStep,
            stepElapsedMs = currentState.stepElapsedMs,
            stepDistanceMeters = currentState.stepDistanceMeters,
            workoutElapsedMs = currentState.workoutElapsedMs,
            workoutDistanceMeters = currentState.workoutDistanceMeters
        )

        Log.d(TAG, "Workout paused at step ${currentState.currentStepIndex}")
    }

    /**
     * Resume a paused workout.
     */
    fun resume() {
        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Paused) {
            Log.w(TAG, "Cannot resume - not paused")
            return
        }

        // Add pause duration to total (using treadmill time)
        if (pausedAtSeconds > 0) {
            totalPausedSeconds += treadmillElapsedSeconds - pausedAtSeconds
            pausedAtSeconds = 0
        }

        val step = currentState.currentStep

        // Reset HR adjustment settling timer (HR dropped during pause, need time to stabilize)
        adjustmentController.onWorkoutResumed(treadmillElapsedSeconds * 1000L)

        // Reset acceleration flag — belt restarts from 0 after pause and needs time
        // to reach target speed before coefficient tracking resumes
        hasReachedTargetSpeed = false

        // Calculate effective pace/incline using the adjustment coefficients
        val effectivePace = getEffectiveSpeed(step)
        val effectiveIncline = getEffectiveIncline(step)
        val isAdjustmentActive = speedAdjustmentCoefficient != 1.0 || inclineAdjustmentCoefficient != 1.0

        _state.value = WorkoutExecutionState.Running(
            workout = currentState.workout,
            currentStepIndex = currentState.currentStepIndex,
            currentStep = step,
            stepElapsedMs = currentState.stepElapsedMs,
            stepDistanceMeters = currentState.stepDistanceMeters,
            workoutElapsedMs = currentState.workoutElapsedMs,
            workoutDistanceMeters = currentState.workoutDistanceMeters,
            currentPaceKph = currentSpeedKph,
            currentIncline = currentInclinePercent,
            isHrAdjustmentActive = isAdjustmentActive,
            hrAdjustmentDirection = null
        )

        // Emit resume event with effective pace/incline (coefficient applied)
        emitEvent(WorkoutEvent.WorkoutResumed(step, effectivePace, effectiveIncline))

        Log.d(TAG, "Workout resumed with effective pace=$effectivePace, incline=$effectiveIncline")
    }

    /**
     * Stop the workout (cannot be resumed).
     */
    fun stop() {
        val currentState = _state.value
        val workout = currentWorkout

        if (workout == null || currentState is WorkoutExecutionState.Idle) {
            Log.w(TAG, "No workout to stop")
            return
        }

        // Calculate total duration using treadmill time (convert seconds to ms)
        val totalDurationMs = if (workoutStartSeconds > 0) {
            ((treadmillElapsedSeconds - workoutStartSeconds - totalPausedSeconds) * 1000L)
        } else 0L

        val totalDistanceMeters = (lastDistanceKm - workoutStartDistanceKm) * 1000.0

        _state.value = WorkoutExecutionState.Completed(
            workout = workout,
            totalDurationMs = totalDurationMs,
            totalDistanceMeters = totalDistanceMeters
        )

        // Emit completion event
        emitEvent(WorkoutEvent.WorkoutCompleted(
            WorkoutSummary(
                workoutName = workout.name,
                stepsCompleted = currentStepIndex + 1,
                totalSteps = executionSteps.size
            )
        ))

        Log.d(TAG, "Workout stopped. Steps completed: ${currentStepIndex + 1}/${executionSteps.size}")

        // Note: Don't reset here - keep data available for FIT export
        // Caller should call reset() after export is complete
    }

    /**
     * Skip to next step immediately.
     * If on last planned step, starts auto-cooldown.
     */
    fun skipToNextStep() {
        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running && currentState !is WorkoutExecutionState.Paused) {
            Log.w(TAG, "Cannot skip - not in active state")
            return
        }

        if (currentStepIndex >= executionSteps.size - 1) {
            if (!inAutoCooldown) {
                // On last planned step - start auto-cooldown
                Log.d(TAG, "Skipping from last step - starting auto-cooldown")
                startAutoCooldown()
            } else {
                Log.d(TAG, "Already in auto-cooldown")
            }
            return
        }

        Log.d(TAG, "Skipping to next step")
        startStep(currentStepIndex + 1)
    }

    /**
     * Skip to previous step.
     * If in auto-cooldown, exits cooldown mode and returns to last planned step.
     */
    fun skipToPreviousStep() {
        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running && currentState !is WorkoutExecutionState.Paused) {
            Log.w(TAG, "Cannot skip - not in active state")
            return
        }

        // If in auto-cooldown, restart the cooldown step (no going back to main)
        if (inAutoCooldown) {
            Log.d(TAG, "In auto-cooldown, restarting cooldown step")
            startStep(currentStepIndex)
            return
        }

        if (currentStepIndex <= 0) {
            // On first step - restart it instead of doing nothing
            Log.d(TAG, "Restarting first step")
            startStep(0)
            return
        }

        val targetIndex = currentStepIndex - 1

        // Phase boundary check: don't cross backward into a previous phase
        if (isMainStep(currentStepIndex) && isWarmupStep(targetIndex)) {
            Log.d(TAG, "At phase boundary (main→warmup), restarting first main step")
            startStep(warmupStepCount)
            return
        }
        if (isCooldownStep(currentStepIndex) && isMainStep(targetIndex)) {
            Log.d(TAG, "At phase boundary (cooldown→main), restarting first cooldown step")
            startStep(warmupStepCount + mainStepCount)
            return
        }

        Log.d(TAG, "Skipping to previous step")
        startStep(targetIndex)
    }

    /**
     * Reset to idle state (for starting a new workout).
     */
    fun reset() {
        resetState()
        _state.value = WorkoutExecutionState.Idle
    }

    // ==================== Telemetry Updates ====================

    /**
     * Called when telemetry is received from the treadmill.
     * This handles speed/incline changes only.
     *
     * NOTE: HR target checks are NOT done here - they are only done in onHeartRateUpdate()
     * when fresh HR data arrives. This prevents duplicate HR adjustments since HR updates
     * come through a separate callback.
     */
    fun onTelemetryUpdate(speedKph: Double, inclinePercent: Double, heartRateBpm: Double) {
        currentSpeedKph = speedKph
        currentInclinePercent = inclinePercent
        currentHeartRateBpm = heartRateBpm

        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) return

        val step = currentState.currentStep

        // Update adjustment coefficients based on actual vs target speed/incline
        // This captures both HR auto-adjustments and manual button presses
        updateAdjustmentCoefficients(step, speedKph, inclinePercent)

        // NOTE: HR checks moved to onHeartRateUpdate() to prevent duplicate processing.
        // HR data comes through both telemetry and a separate HR stream, so checking here
        // would cause duplicate adjustments.

        // Update state using cached timing values
        updateRunningStateFromTelemetry()
    }

    /**
     * Update speed/incline adjustment coefficients based on actual telemetry.
     * Coefficients are recalculated whenever the treadmill reports speed/incline,
     * capturing both HR auto-adjustments and manual button presses.
     */
    private fun updateAdjustmentCoefficients(step: ExecutionStep, actualSpeedKph: Double, actualInclinePercent: Double) {
        // Don't update when belt is stopped — speed=0 during pause/deceleration isn't a real adjustment
        if (actualSpeedKph <= 0) return

        // Don't calculate coefficient during initial acceleration
        // Wait until belt has reached close to target speed
        val effectiveTargetSpeed = step.paceTargetKph * speedAdjustmentCoefficient
        if (!hasReachedTargetSpeed) {
            // Check if we've reached 90% of target
            if (actualSpeedKph >= effectiveTargetSpeed * 0.9) {
                hasReachedTargetSpeed = true
                Log.d(TAG, "Belt reached target speed: actual=$actualSpeedKph, target=$effectiveTargetSpeed")
            } else {
                return  // Still accelerating, don't update coefficient
            }
        }

        // Calculate new coefficient: actual / base target
        if (step.paceTargetKph > 0) {
            val oldCoefficient = speedAdjustmentCoefficient
            speedAdjustmentCoefficient = actualSpeedKph / step.paceTargetKph

            // Emit event if coefficient changed significantly (more than 1%)
            if (kotlin.math.abs(speedAdjustmentCoefficient - oldCoefficient) > 0.01) {
                val percentChange = ((speedAdjustmentCoefficient - 1.0) * 100)
                val displayStr = if (percentChange >= 0) "+%.0f%%".format(percentChange) else "%.0f%%".format(percentChange)
                emitEvent(WorkoutEvent.EffortAdjusted("speed", speedAdjustmentCoefficient, displayStr))
                Log.d(TAG, "Speed coefficient changed: $oldCoefficient -> $speedAdjustmentCoefficient ($displayStr)")
            }
        }

        // For incline, handle the case where base is 0%
        if (step.inclineTargetPercent > 0) {
            val oldCoefficient = inclineAdjustmentCoefficient
            inclineAdjustmentCoefficient = actualInclinePercent / step.inclineTargetPercent

            if (kotlin.math.abs(inclineAdjustmentCoefficient - oldCoefficient) > 0.01) {
                val percentChange = ((inclineAdjustmentCoefficient - 1.0) * 100)
                val displayStr = if (percentChange >= 0) "+%.0f%%".format(percentChange) else "%.0f%%".format(percentChange)
                emitEvent(WorkoutEvent.EffortAdjusted("incline", inclineAdjustmentCoefficient, displayStr))
                Log.d(TAG, "Incline coefficient changed: $oldCoefficient -> $inclineAdjustmentCoefficient ($displayStr)")
            }
        }
    }

    /**
     * Called when heart rate updates are received from the treadmill.
     * HR updates come on their own stream and may arrive when speed/incline are stable.
     * This is the primary trigger for HR-based adjustments.
     */
    fun onHeartRateUpdate(heartRateBpm: Double) {
        currentHeartRateBpm = heartRateBpm

        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) return

        val step = currentState.currentStep

        // Check HR target if configured
        if (step.hasAutoAdjustTarget) {
            checkAutoAdjustTarget(step, treadmillElapsedSeconds * 1000L)
        }

        // Check HR_RANGE early end condition: step ends early when HR enters target range
        if (step.hasHrEndTarget) {
            checkHrEarlyEndCondition(step)
        }

        // Update state using cached timing values
        updateRunningStateFromTelemetry()
    }

    /**
     * Called when power updates are received from the Stryd foot pod.
     * Power updates come through StrydManager and arrive independently of HR.
     * This is the primary trigger for Power-based adjustments.
     *
     * NOTE: The power value should be the incline-adjusted power from StrydManager,
     * NOT raw Stryd power.
     */
    fun onPowerUpdate(adjustedPowerWatts: Double) {
        currentPowerWatts = adjustedPowerWatts

        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) return

        val step = currentState.currentStep

        // Check Power target if this step uses Power-based auto-adjust
        if (step.hasPowerTarget) {
            checkPowerTarget(step, treadmillElapsedSeconds * 1000L)
        }

        // Update state using cached timing values
        updateRunningStateFromTelemetry()
    }

    /**
     * Check if HR has entered the early end target range.
     * If so, complete the step early.
     */
    private fun checkHrEarlyEndCondition(step: ExecutionStep) {
        if (!step.hasHrEndTarget) return

        // SAFETY: Don't trigger early end if HR sensor is not working
        if (currentHeartRateBpm <= 0) return

        // Convert percentage targets to BPM using LTHR
        val targetMinBpm = step.getHrEndTargetMinBpm(stateHolder.userLthrBpm) ?: return
        val targetMaxBpm = step.getHrEndTargetMaxBpm(stateHolder.userLthrBpm) ?: return

        val hrInRange = currentHeartRateBpm >= targetMinBpm &&
                currentHeartRateBpm <= targetMaxBpm

        if (hrInRange) {
            Log.d(TAG, "HR early end condition met: ${currentHeartRateBpm.toInt()} BPM in range " +
                    "$targetMinBpm-$targetMaxBpm")
            emitEvent(WorkoutEvent.HrEarlyEndTriggered(
                currentHr = currentHeartRateBpm.toInt(),
                targetMin = targetMinBpm,
                targetMax = targetMaxBpm
            ))
            completeCurrentStep()
        }
    }

    /**
     * Called when distance updates.
     */
    fun onDistanceUpdate(distanceKm: Double) {
        lastDistanceKm = distanceKm

        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) return

        val step = currentState.currentStep

        // Check distance-based step completion
        // OPEN early end condition: step can extend beyond distance, user must manually skip
        if (step.durationType == DurationType.DISTANCE && step.durationMeters != null) {
            val stepDistanceMeters = (distanceKm - stepStartDistanceKm) * 1000.0
            if (stepDistanceMeters >= step.durationMeters &&
                step.earlyEndCondition != EarlyEndCondition.OPEN) {
                completeCurrentStep()
            }
        }

        // Update state using cached timing values
        updateRunningStateFromTelemetry()
    }

    /**
     * Called when elapsed time updates from the treadmill.
     * This is the single source of truth for timing.
     *
     * NOTE: HR target checks are NOT done here - they are only done in onTelemetryUpdate()
     * when fresh HR data arrives. This prevents using stale HR values for adjustment decisions.
     */
    fun onElapsedTimeUpdate(seconds: Int) {
        // Store the treadmill's elapsed time as our source of truth
        treadmillElapsedSeconds = seconds

        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) return

        val step = currentState.currentStep

        // Check time-based step completion using treadmill time
        // OPEN early end condition: step can extend beyond duration, user must manually skip
        if (step.durationType == DurationType.TIME && step.durationSeconds != null) {
            val stepElapsedSeconds = treadmillElapsedSeconds - stepStartSeconds
            if (stepElapsedSeconds >= step.durationSeconds &&
                step.earlyEndCondition != EarlyEndCondition.OPEN) {
                completeCurrentStep()
            }
        }

        // NOTE: HR target checks moved to onTelemetryUpdate() to ensure fresh HR values
        // HR_RANGE early end condition also moved to onTelemetryUpdate()

        // Update state
        updateRunningState()
    }

    // ==================== Phase Boundary Helpers ====================

    /** Whether the step at the given index is in the warmup phase. */
    fun isWarmupStep(index: Int): Boolean = index < warmupStepCount

    /** Whether the step at the given index is in the main workout phase. */
    fun isMainStep(index: Int): Boolean = index >= warmupStepCount && index < warmupStepCount + mainStepCount

    /** Whether the step at the given index is in the cooldown phase. */
    fun isCooldownStep(index: Int): Boolean = index >= warmupStepCount + mainStepCount

    /** Get phase boundary counts for chart visualization. */
    fun getPhaseCounts(): Triple<Int, Int, Int> = Triple(warmupStepCount, mainStepCount, cooldownStepCount)

    // ==================== Internal Methods ====================

    private fun startStep(index: Int) {
        val workout = currentWorkout ?: return

        if (index < 0 || index >= executionSteps.size) {
            Log.e(TAG, "Invalid step index: $index")
            return
        }

        // Coefficient reset at phase boundaries
        val previousIndex = currentStepIndex
        if (previousIndex >= 0) {
            val crossingWarmupToMain = isWarmupStep(previousIndex) && isMainStep(index)
            val crossingMainToCooldown = isMainStep(previousIndex) && isCooldownStep(index)
            if (crossingWarmupToMain || crossingMainToCooldown) {
                val fromPhase = if (crossingWarmupToMain) "warmup" else "main"
                val toPhase = if (crossingWarmupToMain) "main" else "cooldown"
                Log.d(TAG, "Phase boundary crossed ($fromPhase -> $toPhase): resetting coefficients")
                speedAdjustmentCoefficient = 1.0
                inclineAdjustmentCoefficient = 1.0
                hasReachedTargetSpeed = false
                stepCoefficients.clear()
            }
        }

        // ONE_STEP mode: save current step's coefficients, load next step's
        if (adjustmentScope == AdjustmentScope.ONE_STEP && previousIndex >= 0) {
            val prevStep = executionSteps.getOrNull(previousIndex)
            if (prevStep != null && prevStep.stepIdentityKey.isNotEmpty()) {
                stepCoefficients[prevStep.stepIdentityKey] = Pair(
                    speedAdjustmentCoefficient, inclineAdjustmentCoefficient
                )
            }
            val nextStep = executionSteps[index]
            if (nextStep.stepIdentityKey.isNotEmpty()) {
                val saved = stepCoefficients[nextStep.stepIdentityKey]
                if (saved != null) {
                    speedAdjustmentCoefficient = saved.first
                    inclineAdjustmentCoefficient = saved.second
                    Log.d(TAG, "ONE_STEP: restored coefficients for '${nextStep.stepIdentityKey}': " +
                        "speed=${"%.2f".format(saved.first)}, incline=${"%.2f".format(saved.second)}")
                } else {
                    speedAdjustmentCoefficient = 1.0
                    inclineAdjustmentCoefficient = 1.0
                    Log.d(TAG, "ONE_STEP: fresh coefficients for '${nextStep.stepIdentityKey}'")
                }
            }
        }

        currentStepIndex = index
        val step = executionSteps[index]

        // Reset step timing - use current treadmill time if initialized, otherwise wait for first update
        if (timingInitialized) {
            stepStartSeconds = treadmillElapsedSeconds
            cachedStepElapsedMs = 0  // Reset cached step time for new step
        }
        // else: stepStartSeconds and cachedStepElapsedMs will be set when timing is initialized
        stepStartDistanceKm = lastDistanceKm

        // Reset flags for new step
        hasReachedTargetSpeed = false  // Wait for belt to reach target before tracking coefficient

        // Reset HR adjustment for new step (pass current treadmill elapsed time)
        adjustmentController.onStepStarted(treadmillElapsedSeconds * 1000L)
        wasHrOutOfRange = false

        // Apply step targets to treadmill
        applyStepTargets(step)

        // Update state - preserve Paused if we were Paused (Prev/Next while paused)
        val wasPaused = _state.value is WorkoutExecutionState.Paused
        if (wasPaused) {
            _state.value = WorkoutExecutionState.Paused(
                workout = workout,
                currentStepIndex = index,
                currentStep = step,
                stepElapsedMs = 0,
                stepDistanceMeters = 0.0,
                workoutElapsedMs = getWorkoutElapsedMs(),
                workoutDistanceMeters = getWorkoutDistanceMeters()
            )
        } else {
            _state.value = WorkoutExecutionState.Running(
                workout = workout,
                currentStepIndex = index,
                currentStep = step,
                stepElapsedMs = 0,
                stepDistanceMeters = 0.0,
                workoutElapsedMs = getWorkoutElapsedMs(),
                workoutDistanceMeters = getWorkoutDistanceMeters(),
                currentPaceKph = currentSpeedKph,
                currentIncline = currentInclinePercent,
                isHrAdjustmentActive = false,
                hrAdjustmentDirection = null
            )
        }

        // Calculate effective targets with adjustment coefficients
        val effectivePaceKph = step.paceTargetKph * speedAdjustmentCoefficient
        val effectiveInclinePercent = step.inclineTargetPercent * inclineAdjustmentCoefficient

        // Emit event with effective values
        emitEvent(WorkoutEvent.StepStarted(step, effectivePaceKph, effectiveInclinePercent))

        Log.d(TAG, "Started step ${index + 1}/${executionSteps.size}: ${step.displayName}, " +
            "effective pace=${"%.1f".format(effectivePaceKph)} kph (coef=${"%.2f".format(speedAdjustmentCoefficient)}), " +
            "effective incline=${"%.1f".format(effectiveInclinePercent)}% (coef=${"%.2f".format(inclineAdjustmentCoefficient)})")
    }

    private fun applyStepTargets(step: ExecutionStep) {
        // Speed/incline setting is handled by WorkoutEngineManager via the StepStarted event.
        // This ensures the paceCoefficient conversion is applied correctly.
        Log.d(TAG, "Step targets - Speed: ${step.paceTargetKph} kph, Incline: ${step.inclineTargetPercent}%")
    }

    private fun completeCurrentStep() {
        if (currentStepIndex < 0 || currentStepIndex >= executionSteps.size) return

        val step = executionSteps[currentStepIndex]
        emitEvent(WorkoutEvent.StepCompleted(step))

        Log.d(TAG, "Completed step ${currentStepIndex + 1}/${executionSteps.size}: ${step.displayName}")

        // Check if more steps - start next step immediately (no transition delay)
        if (currentStepIndex < executionSteps.size - 1) {
            startStep(currentStepIndex + 1)
        } else if (!inAutoCooldown) {
            // All planned steps complete - enter auto-cooldown mode
            startAutoCooldown()
        }
        // If already in auto-cooldown, do nothing (cooldown is OPEN-ended)
    }

    /**
     * Start auto-cooldown after all planned steps complete.
     * Uses last cooldown step's pace when a default cooldown is attached,
     * otherwise falls back to hardcoded constants.
     */
    private fun startAutoCooldown() {
        Log.d(TAG, "All planned steps complete - entering auto-cooldown")
        inAutoCooldown = true

        // Use last cooldown step's pace if a default cooldown was attached
        val autoCooldownSpeed = if (cooldownStepCount > 0) {
            executionSteps.last().paceTargetKph
        } else {
            AUTO_COOLDOWN_SPEED_KPH
        }
        val autoCooldownIncline = if (cooldownStepCount > 0) {
            executionSteps.last().inclineTargetPercent
        } else {
            AUTO_COOLDOWN_INCLINE_PERCENT
        }

        // Create auto-cooldown step (OPEN = runs until user stops)
        val cooldownStep = ExecutionStep(
            stepId = -1,  // Not from database
            type = StepType.COOLDOWN,
            durationType = DurationType.TIME,
            durationSeconds = null,
            durationMeters = null,
            paceTargetKph = autoCooldownSpeed,
            inclineTargetPercent = autoCooldownIncline,
            earlyEndCondition = EarlyEndCondition.OPEN,
            repeatIteration = null,
            repeatTotal = null,
            displayName = "Cooldown"
        )

        executionSteps.add(cooldownStep)

        // Emit plan finished event (so UI can show "Plan Complete" message)
        emitEvent(WorkoutEvent.WorkoutPlanFinished(plannedStepsCount))

        // Start the cooldown step
        startStep(currentStepIndex + 1)
    }

    /**
     * Check auto-adjust target (HR or Power) and make adjustments as needed.
     * Works with percentage-based targets, converts to absolute values using LTHR/FTP.
     */
    private fun checkAutoAdjustTarget(step: ExecutionStep, currentTimeMs: Long) {
        when (step.autoAdjustMode) {
            AutoAdjustMode.HR -> checkHrTarget(step, currentTimeMs)
            AutoAdjustMode.POWER -> checkPowerTarget(step, currentTimeMs)
            AutoAdjustMode.NONE -> { /* No auto-adjust */ }
        }
    }

    private fun checkHrTarget(step: ExecutionStep, currentTimeMs: Long) {
        if (!step.hasHrTarget) return

        // SAFETY: Don't adjust if HR sensor is not working (HR = 0 means disconnected/invalid)
        if (currentHeartRateBpm <= 0) {
            Log.v(TAG, "HR adjustment skipped: no valid HR data (hr=$currentHeartRateBpm)")
            return
        }

        // Convert percentage targets to BPM using LTHR
        val targetMin = step.getHrTargetMinBpm(stateHolder.userLthrBpm) ?: return
        val targetMax = step.getHrTargetMaxBpm(stateHolder.userLthrBpm) ?: return
        val adjustmentType = step.adjustmentType ?: return

        Log.d(TAG, "checkHrTarget: step=${step.displayName}, hrMin=$targetMin, hrMax=$targetMax, adjustType=$adjustmentType, currentHr=$currentHeartRateBpm")

        // Use effective targets (with coefficient applied) as base for HR adjustment
        val effectiveBasePace = getEffectiveSpeed(step)
        val effectiveBaseIncline = getEffectiveIncline(step)

        // Build config for HR adjustment
        val config = AdjustmentConfig.forHr(stateHolder)

        val result = adjustmentController.checkTargetRange(
            currentValue = currentHeartRateBpm,
            targetMin = targetMin,
            targetMax = targetMax,
            adjustmentType = adjustmentType,
            config = config,
            currentPace = currentSpeedKph,
            currentIncline = currentInclinePercent,
            basePace = effectiveBasePace,
            baseIncline = effectiveBaseIncline,
            currentTimeMs = currentTimeMs,
            metricName = "HR",
            dataProvider = hrDataProvider
        )

        handleAdjustmentResult(result, targetMin, targetMax, currentHeartRateBpm.toInt(), "HR")
    }

    private fun checkPowerTarget(step: ExecutionStep, currentTimeMs: Long) {
        if (!step.hasPowerTarget) return

        // SAFETY: Don't adjust if power sensor is not working (power = 0 means disconnected/invalid)
        if (currentPowerWatts <= 0) {
            Log.v(TAG, "Power adjustment skipped: no valid power data (power=$currentPowerWatts)")
            return
        }

        // Convert percentage targets to Watts using FTP
        val targetMin = step.getPowerTargetMinWatts(stateHolder.userFtpWatts) ?: return
        val targetMax = step.getPowerTargetMaxWatts(stateHolder.userFtpWatts) ?: return
        val adjustmentType = step.adjustmentType ?: return

        Log.d(TAG, "checkPowerTarget: step=${step.displayName}, powerMin=$targetMin, powerMax=$targetMax, adjustType=$adjustmentType, currentPower=$currentPowerWatts")

        // Use effective targets (with coefficient applied) as base for Power adjustment
        val effectiveBasePace = getEffectiveSpeed(step)
        val effectiveBaseIncline = getEffectiveIncline(step)

        // Build config for Power adjustment
        val config = AdjustmentConfig.forPower(stateHolder)

        val result = adjustmentController.checkTargetRange(
            currentValue = currentPowerWatts,
            targetMin = targetMin,
            targetMax = targetMax,
            adjustmentType = adjustmentType,
            config = config,
            currentPace = currentSpeedKph,
            currentIncline = currentInclinePercent,
            basePace = effectiveBasePace,
            baseIncline = effectiveBaseIncline,
            currentTimeMs = currentTimeMs,
            metricName = "Power",
            dataProvider = powerDataProvider
        )

        handleAdjustmentResult(result, targetMin, targetMax, currentPowerWatts.toInt(), "Power")
    }

    private fun handleAdjustmentResult(
        result: AdjustmentController.AdjustmentResult,
        targetMin: Int,
        targetMax: Int,
        currentValue: Int,
        metricName: String
    ) {
        when (result) {
            is AdjustmentController.AdjustmentResult.AdjustSpeed -> {
                emitEvent(WorkoutEvent.SpeedAdjusted(
                    newSpeedKph = result.newSpeedKph,
                    direction = result.direction,
                    reason = result.reason
                ))
                Log.d(TAG, "$metricName adjustment: ${result.reason} -> speed ${result.newSpeedKph}")
            }
            is AdjustmentController.AdjustmentResult.AdjustIncline -> {
                emitEvent(WorkoutEvent.InclineAdjusted(
                    newInclinePercent = result.newInclinePercent,
                    direction = result.direction,
                    reason = result.reason
                ))
                Log.d(TAG, "$metricName adjustment: ${result.reason} -> incline ${result.newInclinePercent}")
            }
            is AdjustmentController.AdjustmentResult.Waiting -> {
                Log.v(TAG, "$metricName adjustment waiting: ${result.reason}")
            }
            is AdjustmentController.AdjustmentResult.NoAdjustment -> {
                // Check if metric returned to range (HR-specific for backward compat)
                if (metricName == "HR" && wasHrOutOfRange) {
                    if (currentValue >= targetMin && currentValue <= targetMax) {
                        wasHrOutOfRange = false
                        emitEvent(WorkoutEvent.HrBackInRange(
                            currentHr = currentValue,
                            targetMin = targetMin,
                            targetMax = targetMax
                        ))
                    }
                }
            }
        }

        // Track if metric is out of range (HR-specific for backward compat)
        if (metricName == "HR") {
            val hrOutOfRange = currentValue < targetMin || currentValue > targetMax
            if (hrOutOfRange && !wasHrOutOfRange) {
                wasHrOutOfRange = true
                emitEvent(WorkoutEvent.HrOutOfRange(
                    currentHr = currentValue,
                    targetMin = targetMin,
                    targetMax = targetMax
                ))
            }
        }
    }

    /**
     * Update state with new timing from treadmill elapsed time.
     * Called from onElapsedTimeUpdate - the ONLY place that calculates timing.
     * Updates cached timing values that are used by all other state updates.
     */
    private fun updateRunningState() {
        val workout = currentWorkout ?: return
        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) return

        val step = executionSteps[currentStepIndex]

        // Calculate and CACHE timing values - this is the only place timing is calculated
        cachedStepElapsedMs = if (timingInitialized && stepStartSeconds >= 0) {
            ((treadmillElapsedSeconds - stepStartSeconds) * 1000L).coerceAtLeast(0)
        } else {
            0
        }
        cachedWorkoutElapsedMs = getWorkoutElapsedMs()

        val isAdjustmentActive = speedAdjustmentCoefficient != 1.0 || inclineAdjustmentCoefficient != 1.0

        // Calculate countdown (3, 2, 1) for the last 3 seconds before step ends
        val countdownSeconds = calculateCountdownSeconds(step)

        _state.value = WorkoutExecutionState.Running(
            workout = workout,
            currentStepIndex = currentStepIndex,
            currentStep = step,
            stepElapsedMs = cachedStepElapsedMs,
            stepDistanceMeters = (lastDistanceKm - stepStartDistanceKm) * 1000.0,
            workoutElapsedMs = cachedWorkoutElapsedMs,
            workoutDistanceMeters = getWorkoutDistanceMeters(),
            currentPaceKph = currentSpeedKph,
            currentIncline = currentInclinePercent,
            isHrAdjustmentActive = isAdjustmentActive,
            hrAdjustmentDirection = getAdjustmentDirection(),
            countdownSeconds = countdownSeconds
        )
    }

    /**
     * Update state from telemetry/distance using CACHED timing values.
     * Never reads timing from existing state - always uses instance variable cache.
     * This prevents any race conditions with state reads.
     */
    private fun updateRunningStateFromTelemetry() {
        val workout = currentWorkout ?: return
        val currentState = _state.value
        if (currentState !is WorkoutExecutionState.Running) return

        // Capture index locally to prevent race with concurrent step transitions
        val stepIdx = currentStepIndex
        if (stepIdx !in executionSteps.indices) return
        val step = executionSteps[stepIdx]
        val isAdjustmentActive = speedAdjustmentCoefficient != 1.0 || inclineAdjustmentCoefficient != 1.0

        // Calculate countdown (3, 2, 1) for the last 3 seconds before step ends
        val countdownSeconds = calculateCountdownSeconds(step)

        _state.value = WorkoutExecutionState.Running(
            workout = workout,
            currentStepIndex = stepIdx,
            currentStep = step,
            // Use CACHED timing values - never read from existing state
            stepElapsedMs = cachedStepElapsedMs,
            stepDistanceMeters = (lastDistanceKm - stepStartDistanceKm) * 1000.0,
            workoutElapsedMs = cachedWorkoutElapsedMs,
            workoutDistanceMeters = getWorkoutDistanceMeters(),
            // Update current telemetry values
            currentPaceKph = currentSpeedKph,
            currentIncline = currentInclinePercent,
            isHrAdjustmentActive = isAdjustmentActive,
            hrAdjustmentDirection = getAdjustmentDirection(),
            countdownSeconds = countdownSeconds
        )
    }

    /**
     * Get direction of adjustment based on coefficient.
     * Returns "reducing" if coefficient < 1.0, "increasing" if > 1.0, null if no adjustment.
     */
    private fun getAdjustmentDirection(): String? {
        return when {
            speedAdjustmentCoefficient < 0.99 -> "reducing"
            speedAdjustmentCoefficient > 1.01 -> "increasing"
            inclineAdjustmentCoefficient < 0.99 -> "reducing"
            inclineAdjustmentCoefficient > 1.01 -> "increasing"
            else -> null
        }
    }

    /**
     * Calculate countdown seconds for the last 3 seconds before step ends.
     * Returns 3, 2, or 1 when within the final 3 seconds.
     * Audio plays 3-2-1-GO pattern with GO beep at exact step transition.
     * Returns null if:
     * - Step is OPEN (doesn't auto-end)
     * - More than 3 seconds remaining
     */
    private fun calculateCountdownSeconds(step: ExecutionStep): Int? {
        // No countdown for OPEN steps (they don't auto-end)
        if (step.earlyEndCondition == EarlyEndCondition.OPEN) return null

        val remainingSeconds: Int = when (step.durationType) {
            DurationType.TIME -> {
                if (step.durationSeconds == null) return null
                val stepElapsedSeconds = treadmillElapsedSeconds - stepStartSeconds
                step.durationSeconds - stepElapsedSeconds
            }
            DurationType.DISTANCE -> {
                if (step.durationMeters == null) return null
                val stepDistanceMeters = (lastDistanceKm - stepStartDistanceKm) * 1000.0
                val remainingMeters = step.durationMeters - stepDistanceMeters
                if (remainingMeters <= 0) return null
                // Calculate time to completion based on current speed
                if (currentSpeedKph <= 0) return null
                val speedMps = currentSpeedKph / 3.6
                (remainingMeters / speedMps).toInt()
            }
        }

        // Return countdown value (3, 2, 1) if within last 3 seconds
        // Audio sequence plays 3-2-1-GO with GO beep at exact step transition
        return when {
            remainingSeconds <= 0 -> null  // Step has ended
            remainingSeconds <= 3 -> remainingSeconds
            else -> null
        }
    }

    private fun getWorkoutElapsedMs(): Long {
        if (!timingInitialized || workoutStartSeconds < 0) return 0
        return ((treadmillElapsedSeconds - workoutStartSeconds - totalPausedSeconds) * 1000L).coerceAtLeast(0)
    }

    private fun getWorkoutDistanceMeters(): Double {
        return (lastDistanceKm - workoutStartDistanceKm) * 1000.0
    }

    private fun resetState() {
        currentWorkout = null
        originalSteps = emptyList()
        executionSteps = mutableListOf()
        currentStepIndex = -1
        plannedStepsCount = 0
        inAutoCooldown = false
        warmupStepCount = 0
        mainStepCount = 0
        cooldownStepCount = 0
        workoutStartSeconds = -1
        stepStartSeconds = -1
        totalPausedSeconds = 0
        pausedAtSeconds = 0
        timingInitialized = false
        cachedStepElapsedMs = 0
        cachedWorkoutElapsedMs = 0
        workoutStartDistanceKm = 0.0
        stepStartDistanceKm = 0.0
        speedAdjustmentCoefficient = 1.0
        inclineAdjustmentCoefficient = 1.0
        hasReachedTargetSpeed = false
        adjustmentScope = AdjustmentScope.ALL_STEPS
        stepCoefficients.clear()
        adjustmentController.reset()
        wasHrOutOfRange = false
    }

    private fun emitEvent(event: WorkoutEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    // ==================== Query Methods ====================

    /**
     * Get the list of execution steps for UI display.
     */
    fun getExecutionSteps(): List<ExecutionStep> = executionSteps

    /**
     * Get the original hierarchical steps for FIT export.
     * These preserve the original structure including REPEAT blocks.
     */
    fun getOriginalSteps(): List<WorkoutStep> = originalSteps

    /**
     * Get the currently loaded workout.
     */
    fun getCurrentWorkout(): Workout? = currentWorkout

    /**
     * Check if a workout is loaded and ready to start.
     */
    fun isWorkoutLoaded(): Boolean = currentWorkout != null && executionSteps.isNotEmpty()

    /**
     * Get the current speed adjustment coefficient.
     * Used by UI/chart to show adjusted targets for remaining steps.
     */
    fun getSpeedAdjustmentCoefficient(): Double = speedAdjustmentCoefficient

    /**
     * Get the current incline adjustment coefficient.
     * Used by UI/chart to show adjusted targets for remaining steps.
     */
    fun getInclineAdjustmentCoefficient(): Double = inclineAdjustmentCoefficient

    /**
     * Get effective speed for a step (base target * coefficient).
     */
    fun getEffectiveSpeed(step: ExecutionStep): Double = step.paceTargetKph * speedAdjustmentCoefficient

    /**
     * Get effective incline for a step (base target * coefficient).
     */
    fun getEffectiveIncline(step: ExecutionStep): Double = step.inclineTargetPercent * inclineAdjustmentCoefficient

    /**
     * Reset both adjustment coefficients to 1.0.
     * In ONE_STEP mode, also updates the map entry for the current step's identity key.
     */
    fun resetAdjustmentCoefficients() {
        speedAdjustmentCoefficient = 1.0
        inclineAdjustmentCoefficient = 1.0
        if (adjustmentScope == AdjustmentScope.ONE_STEP && currentStepIndex >= 0) {
            val step = executionSteps.getOrNull(currentStepIndex)
            if (step != null && step.stepIdentityKey.isNotEmpty()) {
                stepCoefficients[step.stepIdentityKey] = Pair(1.0, 1.0)
            }
        }
        Log.d(TAG, "Reset adjustment coefficients to 1.0")
    }

    /**
     * Get the adjustment scope for this workout.
     */
    fun getAdjustmentScope(): AdjustmentScope = adjustmentScope

    /**
     * Get the per-step coefficient map (ONE_STEP mode).
     * Returns a snapshot copy that includes the current step's live coefficients,
     * so the chart can apply them to all segments sharing the same identity key.
     */
    fun getStepCoefficients(): Map<String, Pair<Double, Double>> {
        val snapshot = stepCoefficients.toMutableMap()
        // Overlay the current step's live coefficients into the map
        // so other segments with the same identity key (e.g., Run 3/4 while on Run 2/4)
        // see the up-to-date values, not the stale value from last departure
        if (currentStepIndex >= 0) {
            val currentStep = executionSteps.getOrNull(currentStepIndex)
            if (currentStep != null && currentStep.stepIdentityKey.isNotEmpty()) {
                snapshot[currentStep.stepIdentityKey] = Pair(
                    speedAdjustmentCoefficient, inclineAdjustmentCoefficient
                )
            }
        }
        return snapshot
    }

    // ==================== State Persistence ====================

    /**
     * Export the current engine state for persistence.
     * Returns null if no workout is loaded or workout is idle.
     */
    fun exportPersistenceState(): EnginePersistenceState? {
        val workout = currentWorkout ?: return null
        if (_state.value is WorkoutExecutionState.Idle) return null
        if (currentStepIndex < 0) return null

        return EnginePersistenceState(
            workoutId = workout.id,
            currentStepIndex = currentStepIndex,
            plannedStepsCount = plannedStepsCount,
            inAutoCooldown = inAutoCooldown,
            workoutStartSeconds = workoutStartSeconds,
            stepStartSeconds = stepStartSeconds,
            totalPausedSeconds = totalPausedSeconds,
            workoutStartDistanceKm = workoutStartDistanceKm,
            stepStartDistanceKm = stepStartDistanceKm,
            lastDistanceKm = lastDistanceKm,
            speedAdjustmentCoefficient = speedAdjustmentCoefficient,
            inclineAdjustmentCoefficient = inclineAdjustmentCoefficient,
            hasReachedTargetSpeed = hasReachedTargetSpeed,
            adjustmentScope = adjustmentScope,
            stepCoefficients = if (adjustmentScope == AdjustmentScope.ONE_STEP) stepCoefficients.toMap() else null
        )
    }

    /**
     * Export the adjustment controller state for persistence.
     */
    fun exportAdjustmentControllerState(): AdjustmentControllerState {
        return adjustmentController.exportState()
    }

    /**
     * Restore engine state from persisted data.
     * Must be called after loadWorkout() to restore the execution state.
     *
     * @param state The persisted engine state
     * @param isPaused Whether the workout was paused when persisted
     */
    suspend fun restoreFromPersistedState(state: EnginePersistenceState, isPaused: Boolean = true) {
        val workout = currentWorkout
        if (workout == null || workout.id != state.workoutId) {
            Log.e(TAG, "Cannot restore state - workout not loaded or ID mismatch")
            return
        }

        // Restore internal state
        currentStepIndex = state.currentStepIndex
        plannedStepsCount = state.plannedStepsCount
        inAutoCooldown = state.inAutoCooldown
        workoutStartSeconds = state.workoutStartSeconds
        stepStartSeconds = state.stepStartSeconds
        totalPausedSeconds = state.totalPausedSeconds
        workoutStartDistanceKm = state.workoutStartDistanceKm
        stepStartDistanceKm = state.stepStartDistanceKm
        lastDistanceKm = state.lastDistanceKm
        speedAdjustmentCoefficient = state.speedAdjustmentCoefficient
        inclineAdjustmentCoefficient = state.inclineAdjustmentCoefficient
        hasReachedTargetSpeed = state.hasReachedTargetSpeed
        adjustmentScope = state.adjustmentScope
        if (state.stepCoefficients != null) {
            stepCoefficients.clear()
            stepCoefficients.putAll(state.stepCoefficients)
        }
        timingInitialized = true

        // Calculate cached timing from restored state
        cachedStepElapsedMs = if (stepStartSeconds >= 0) {
            ((treadmillElapsedSeconds - stepStartSeconds) * 1000L).coerceAtLeast(0)
        } else 0
        cachedWorkoutElapsedMs = getWorkoutElapsedMs()

        // Ensure step index is valid
        if (currentStepIndex < 0 || currentStepIndex >= executionSteps.size) {
            Log.e(TAG, "Invalid restored step index: $currentStepIndex")
            return
        }

        val step = executionSteps[currentStepIndex]

        // Set state to paused (user must resume explicitly)
        if (isPaused) {
            _state.value = WorkoutExecutionState.Paused(
                workout = workout,
                currentStepIndex = currentStepIndex,
                currentStep = step,
                stepElapsedMs = cachedStepElapsedMs,
                stepDistanceMeters = (lastDistanceKm - stepStartDistanceKm) * 1000.0,
                workoutElapsedMs = cachedWorkoutElapsedMs,
                workoutDistanceMeters = getWorkoutDistanceMeters()
            )
        } else {
            val isAdjustmentActive = speedAdjustmentCoefficient != 1.0 || inclineAdjustmentCoefficient != 1.0
            _state.value = WorkoutExecutionState.Running(
                workout = workout,
                currentStepIndex = currentStepIndex,
                currentStep = step,
                stepElapsedMs = cachedStepElapsedMs,
                stepDistanceMeters = (lastDistanceKm - stepStartDistanceKm) * 1000.0,
                workoutElapsedMs = cachedWorkoutElapsedMs,
                workoutDistanceMeters = getWorkoutDistanceMeters(),
                currentPaceKph = currentSpeedKph,
                currentIncline = currentInclinePercent,
                isHrAdjustmentActive = isAdjustmentActive,
                hrAdjustmentDirection = getAdjustmentDirection()
            )
        }

        Log.d(TAG, "Restored engine state: step=$currentStepIndex/${executionSteps.size}, " +
            "speedCoeff=$speedAdjustmentCoefficient, inclineCoeff=$inclineAdjustmentCoefficient")
    }

    /**
     * Restore the adjustment controller state from persisted data.
     */
    fun restoreAdjustmentControllerState(state: AdjustmentControllerState) {
        adjustmentController.restoreFromState(state)
    }
}
