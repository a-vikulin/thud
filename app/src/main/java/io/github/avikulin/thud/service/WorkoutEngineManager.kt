package io.github.avikulin.thud.service

import android.content.Context
import android.util.Log
import android.widget.Toast
import io.github.avikulin.thud.R
import io.github.avikulin.thud.ui.components.WorkoutChart
import io.github.avikulin.thud.data.db.TreadmillHudDatabase
import io.github.avikulin.thud.data.repository.WorkoutRepository
import io.github.avikulin.thud.domain.engine.ExecutionStep
import io.github.avikulin.thud.domain.engine.MetricDataPoint
import io.github.avikulin.thud.domain.engine.WorkoutExecutionEngine
import io.github.avikulin.thud.domain.engine.WorkoutExecutionState
import io.github.avikulin.thud.domain.engine.WorkoutEvent
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.util.PaceConverter
import com.ifit.glassos.workout.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// EnginePersistenceState and AdjustmentControllerState are defined in RunPersistenceManager.kt

/**
 * Manages workout execution lifecycle and treadmill synchronization.
 */
class WorkoutEngineManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: ServiceStateHolder,
    private val getGlassOsClient: () -> GlassOsClient?,
    private val hrDataProvider: () -> List<MetricDataPoint>,
    private val powerDataProvider: () -> List<MetricDataPoint>
) {
    companion object {
        private const val TAG = "WorkoutEngineManager"
    }

    /**
     * Callback interface for workout events.
     */
    interface Listener {
        fun onWorkoutLoaded(workout: Workout, steps: List<ExecutionStep>)
        fun onWorkoutStateChanged(executionState: WorkoutExecutionState)
        fun onWorkoutEvent(event: WorkoutEvent)
        fun onSetTreadmillSpeed(adjustedKph: Double)
        fun onSetTreadmillIncline(percent: Double)
    }

    var listener: Listener? = null
    var chartManager: ChartManager? = null

    private var workoutEngine: WorkoutExecutionEngine? = null
    private val repository: WorkoutRepository by lazy {
        val database = TreadmillHudDatabase.getInstance(context)
        WorkoutRepository(database.workoutDao())
    }

    /**
     * Initialize the workout engine.
     * Preserves existing engine across GlassOS reconnections to maintain workout state.
     */
    fun initializeEngine() {
        // Preserve engine across reconnections - don't recreate if it already exists
        if (workoutEngine != null) {
            Log.d(TAG, "Engine already exists, preserving workout state across reconnection")
            return
        }

        Log.d(TAG, "Creating new workout engine")
        workoutEngine = WorkoutExecutionEngine(repository, hrDataProvider, powerDataProvider, state, scope)

        // Observe workout state changes
        scope.launch {
            workoutEngine?.state?.collect { executionState ->
                listener?.onWorkoutStateChanged(executionState)
            }
        }

        // Observe workout events
        scope.launch {
            workoutEngine?.events?.collect { event ->
                handleWorkoutEvent(event)
            }
        }
    }

    /**
     * Load a workout by ID, stitching warmup/cooldown templates if enabled.
     * If a workout is currently in Completed state, it will be reset first.
     */
    fun loadWorkout(workoutId: Long) {
        scope.launch {
            val engine = workoutEngine
            if (engine == null) {
                Log.e(TAG, "Workout engine not initialized")
                return@launch
            }

            // If engine is in Completed state (workout stopped but not dismissed), reset it first
            val currentState = engine.state.value
            if (currentState is WorkoutExecutionState.Completed) {
                Log.d(TAG, "Resetting completed workout before loading new one")
                engine.reset()
            }

            val loaded = loadWorkoutStitched(engine, workoutId)
            if (loaded) {
                val workout = engine.getCurrentWorkout()
                val steps = engine.getExecutionSteps()
                if (workout != null) {
                    withContext(Dispatchers.Main) {
                        listener?.onWorkoutLoaded(workout, steps)
                    }
                }
                // Update lastExecutedAt for list ordering
                repository.markAsExecuted(workoutId)
                Log.d(TAG, "Workout loaded successfully")
            } else {
                Log.e(TAG, "Failed to load workout $workoutId")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.toast_failed_to_load_workout), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Load a workout with stitched warmup/cooldown templates.
     * If the workout has useDefaultWarmup/useDefaultCooldown enabled,
     * loads the system templates and passes them to the engine.
     */
    private suspend fun loadWorkoutStitched(engine: WorkoutExecutionEngine, workoutId: Long): Boolean {
        val workoutWithSteps = repository.getWorkoutWithSteps(workoutId)
        if (workoutWithSteps == null) {
            Log.e(TAG, "Workout not found: $workoutId")
            return false
        }

        val (workout, mainSteps) = workoutWithSteps
        if (mainSteps.isEmpty()) {
            Log.e(TAG, "Workout has no steps: $workoutId")
            return false
        }

        // Load warmup/cooldown templates if enabled
        val warmupSteps = if (workout.useDefaultWarmup) {
            repository.getSystemWarmup()?.second
        } else null

        val cooldownSteps = if (workout.useDefaultCooldown) {
            repository.getSystemCooldown()?.second
        } else null

        val hasStitching = warmupSteps != null || cooldownSteps != null
        return if (hasStitching) {
            engine.loadStitchedWorkout(workout, mainSteps, warmupSteps, cooldownSteps)
        } else {
            engine.loadWorkout(workoutId)
        }
    }

    /**
     * Load and start a workout in one operation.
     * This is used when starting a workout directly from the editor.
     * Recording is handled by HUDService before calling this method.
     */
    fun loadAndStartWorkout(
        workoutId: Long,
        onStartingWorkout: (Boolean) -> Unit
    ) {
        scope.launch {
            val engine = workoutEngine ?: run {
                withContext(Dispatchers.Main) { onStartingWorkout(false) }
                return@launch
            }
            val loaded = loadWorkoutStitched(engine, workoutId)
            if (loaded) {
                val workout = engine.getCurrentWorkout()
                val steps = engine.getExecutionSteps()
                if (workout != null) {
                    withContext(Dispatchers.Main) {
                        listener?.onWorkoutLoaded(workout, steps)
                    }
                }
                // Update lastExecutedAt for list ordering
                repository.markAsExecuted(workoutId)
                Log.d(TAG, "Workout loaded, now starting...")
                // Now start the workout
                startWorkoutInternal(onStartingWorkout)
            } else {
                Log.e(TAG, "Failed to load workout $workoutId")
                withContext(Dispatchers.Main) { onStartingWorkout(false) }
            }
        }
    }

    /**
     * Start the loaded workout.
     * Recording is handled by HUDService before calling this method.
     */
    fun startWorkout(onStartingWorkout: (Boolean) -> Unit) {
        scope.launch {
            startWorkoutInternal(onStartingWorkout)
        }
    }

    /**
     * Internal method to start the workout (must be called from coroutine).
     * Sets isStartingWorkoutProgrammatically flag to prevent race with handlePhysicalStart.
     * Recording is handled by HUDService - this method only handles engine and treadmill control.
     *
     * If treadmill is already running (e.g., during a free run), we don't stop it -
     * just start the workout engine and it takes over control.
     */
    private suspend fun startWorkoutInternal(onStartingWorkout: (Boolean) -> Unit) {
        val engine = workoutEngine ?: run {
            Log.e(TAG, "startWorkoutInternal: engine is null")
            return
        }

        // Set flag to prevent handlePhysicalStart from also trying to start
        state.isStartingWorkoutProgrammatically.set(true)

        try {
            val client = getGlassOsClient()
            if (client == null) {
                Log.e(TAG, "startWorkoutInternal: GlassOS client is null")
                withContext(Dispatchers.Main) { onStartingWorkout(false) }
                return
            }

            // Get treadmill state on IO thread (gRPC call)
            val currentState = withContext(Dispatchers.IO) {
                client.getWorkoutState()
            }
            Log.d(TAG, "Starting workout programmatically, treadmill state: $currentState")

            if (currentState == WorkoutState.WORKOUT_STATE_RUNNING) {
                // Treadmill already running (e.g., free run in progress)
                // Just start the engine - it will take over control
                Log.d(TAG, "Treadmill already running - starting engine directly")
                withContext(Dispatchers.Main) {
                    engine.start()
                    onStartingWorkout(false)

                    // Set speed and incline for first step
                    val executionState = engine.state.value
                    if (executionState is WorkoutExecutionState.Running) {
                        val step = executionState.currentStep
                        applyStepTargets(step.paceTargetKph, step.inclineTargetPercent)
                    }
                }
            } else {
                // Treadmill not running - need to start it
                withContext(Dispatchers.IO) {
                    // If paused, stop first to reset
                    if (currentState == WorkoutState.WORKOUT_STATE_PAUSED) {
                        Log.d(TAG, "Stopping paused treadmill workout")
                        client?.stopWorkout()
                        delay(500)
                    }

                    // Start fresh treadmill workout
                    Log.d(TAG, "Quick starting fresh treadmill workout...")
                    val success = client?.quickStartWorkout() ?: false
                    if (!success) {
                        Log.e(TAG, "Failed to quick start treadmill, aborting workout start")
                        withContext(Dispatchers.Main) { onStartingWorkout(false) }
                        return@withContext
                    }

                    // Wait for belt to be ready
                    if (!waitForBeltReady()) {
                        Log.e(TAG, "Belt not ready, aborting workout start")
                        withContext(Dispatchers.Main) { onStartingWorkout(false) }
                        return@withContext
                    }

                    // Now start the workout engine on main thread
                    withContext(Dispatchers.Main) {
                        engine.start()
                        onStartingWorkout(false)

                        // Set initial speed and incline for first step
                        val executionState = engine.state.value
                        if (executionState is WorkoutExecutionState.Running) {
                            val step = executionState.currentStep
                            applyStepTargets(step.paceTargetKph, step.inclineTargetPercent)
                        }
                    }
                }
            }
        } finally {
            // Always clear the flag when done
            state.isStartingWorkoutProgrammatically.set(false)
        }
    }

    /**
     * Toggle workout pause/resume.
     */
    fun togglePause() {
        val executionState = workoutEngine?.state?.value
        when (executionState) {
            is WorkoutExecutionState.Running -> workoutEngine?.pause()
            is WorkoutExecutionState.Paused -> workoutEngine?.resume()
            else -> Log.d(TAG, "Cannot toggle pause in state: $executionState")
        }
    }

    /**
     * Resume a paused workout and apply step targets.
     * This handles both the engine state and treadmill control.
     */
    fun resumeWorkoutWithTargets() {
        val engine = workoutEngine ?: return
        val executionState = engine.state.value

        if (executionState !is WorkoutExecutionState.Paused) {
            Log.d(TAG, "Cannot resume - not paused: $executionState")
            return
        }

        scope.launch(Dispatchers.IO) {
            // Resume treadmill first
            val client = getGlassOsClient()
            val treadmillState = client?.getWorkoutState()
            Log.d(TAG, "Resuming workout, treadmill state: $treadmillState")

            if (treadmillState == WorkoutState.WORKOUT_STATE_PAUSED) {
                Log.d(TAG, "Resuming treadmill...")
                client?.resumeWorkout()
            }

            // Resume engine - WorkoutResumed event handler will wait for belt ready
            // and apply step targets automatically.
            // Note: handlePhysicalStart() may have already called resume() via the
            // RUNNING state change callback - that's fine, second call is a no-op.
            withContext(Dispatchers.Main) {
                engine.resume()
            }
        }
    }

    /**
     * Stop the current workout.
     */
    fun stopWorkout() {
        workoutEngine?.stop()
    }

    /**
     * Reset the workout engine.
     */
    fun resetWorkout() {
        workoutEngine?.reset()
    }

    /**
     * Skip to next step.
     */
    fun skipToNextStep() {
        workoutEngine?.skipToNextStep()
    }

    /**
     * Skip to previous step.
     */
    fun skipToPreviousStep() {
        workoutEngine?.skipToPreviousStep()
    }

    /**
     * Check if a workout is loaded.
     */
    fun isWorkoutLoaded(): Boolean = workoutEngine?.isWorkoutLoaded() ?: false

    /**
     * Get current workout state.
     */
    fun getState(): WorkoutExecutionState? = workoutEngine?.state?.value

    /**
     * Get current workout.
     */
    fun getCurrentWorkout(): Workout? = workoutEngine?.getCurrentWorkout()

    /**
     * Get execution steps (flattened for runtime execution).
     */
    fun getExecutionSteps(): List<ExecutionStep> = workoutEngine?.getExecutionSteps() ?: emptyList()

    /**
     * Get phase counts for stitched workouts: (warmup, main, cooldown).
     */
    fun getPhaseCounts(): Triple<Int, Int, Int> = workoutEngine?.getPhaseCounts() ?: Triple(0, 0, 0)

    /**
     * Get original hierarchical steps (for FIT export with proper repeat structure).
     */
    fun getOriginalSteps(): List<io.github.avikulin.thud.data.entity.WorkoutStep> =
        workoutEngine?.getOriginalSteps() ?: emptyList()

    /**
     * Feed telemetry to workout engine.
     */
    fun onTelemetryUpdate(speedKph: Double, inclinePercent: Double, heartRateBpm: Double) {
        workoutEngine?.onTelemetryUpdate(speedKph, inclinePercent, heartRateBpm)
    }

    /**
     * Feed heart rate update to workout engine.
     * This is called when HR updates come in on their own stream.
     */
    fun onHeartRateUpdate(heartRateBpm: Double) {
        workoutEngine?.onHeartRateUpdate(heartRateBpm)
    }

    /**
     * Feed power update to workout engine (for power-based auto-adjustments).
     */
    fun onPowerUpdate(adjustedPowerWatts: Double) {
        workoutEngine?.onPowerUpdate(adjustedPowerWatts)
    }

    /**
     * Feed distance update to workout engine.
     */
    fun onDistanceUpdate(km: Double) {
        workoutEngine?.onDistanceUpdate(km)
    }

    /**
     * Feed elapsed time update to workout engine.
     */
    fun onElapsedTimeUpdate(seconds: Int) {
        workoutEngine?.onElapsedTimeUpdate(seconds)
    }

    /**
     * Handle physical Start button press on treadmill.
     * Manages the workout engine state only - recording is handled by HUDService.
     */
    fun handlePhysicalStart() {
        // Skip if a programmatic start is already in progress (prevents race condition)
        if (state.isStartingWorkoutProgrammatically.get()) {
            Log.d(TAG, "Physical Start pressed - skipping, programmatic start in progress")
            return
        }

        val engine = workoutEngine ?: return
        val engineState = engine.state.value

        when (engineState) {
            is WorkoutExecutionState.Idle -> {
                if (engine.isWorkoutLoaded()) {
                    Log.d(TAG, "Physical Start pressed - starting loaded workout")
                    engine.start()
                }
            }
            is WorkoutExecutionState.Paused -> {
                Log.d(TAG, "Physical Start pressed - resuming paused workout")
                // Resume engine - WorkoutResumed event handler will apply targets after belt is ready
                engine.resume()
            }
            is WorkoutExecutionState.Running -> {
                val step = engineState.currentStep
                Log.d(TAG, "Physical Start pressed - workout already running, applying step targets")
                applyStepTargets(step.paceTargetKph, step.inclineTargetPercent)
            }
            else -> {
                Log.d(TAG, "Physical Start pressed - engine state: $engineState (no action)")
            }
        }
    }

    /**
     * Handle physical Pause button press on treadmill.
     */
    fun handlePhysicalPause() {
        val engine = workoutEngine ?: return
        val engineState = engine.state.value

        when (engineState) {
            is WorkoutExecutionState.Running -> {
                Log.d(TAG, "Physical Pause pressed - pausing workout engine")
                engine.pause()
            }
            else -> {
                Log.d(TAG, "Physical Pause pressed - engine state: $engineState (no action)")
            }
        }
    }

    /**
     * Handle physical Stop button press on treadmill.
     * This is called when treadmill transitions to IDLE state.
     * Manages the workout engine state only - data clearing is handled by HUDService.
     *
     * Two-press behavior:
     * - First Stop (from RUNNING): Treadmill goes to PAUSED (handled by handlePhysicalPause)
     * - Second Stop (from PAUSED): Treadmill goes to IDLE (handled here) - stop engine
     */
    fun handlePhysicalStop(previousState: WorkoutState) {
        val engine = workoutEngine
        val engineState = engine?.state?.value

        // Second stop: coming from PAUSED state means user wants to stop and cleanup
        val isSecondStop = previousState == WorkoutState.WORKOUT_STATE_PAUSED

        when (engineState) {
            is WorkoutExecutionState.Running -> {
                // Belt stopped while engine was running - pause the engine
                Log.d(TAG, "Physical Stop pressed while running - pausing workout engine")
                engine.pause()
            }
            is WorkoutExecutionState.Paused -> {
                if (isSecondStop) {
                    // Second stop: stop engine (data clearing handled by HUDService)
                    Log.d(TAG, "Physical Stop pressed while paused (second stop) - stopping engine")
                    engine.stop()
                }
            }
            else -> {
                Log.d(TAG, "Physical Stop pressed - engine state: $engineState (no action)")
            }
        }
    }

    /**
     * Apply step targets to treadmill.
     */
    fun applyStepTargets(paceKph: Double, inclinePercent: Double) {
        scope.launch(Dispatchers.IO) {
            listener?.onSetTreadmillSpeed(paceKph)
            listener?.onSetTreadmillIncline(inclinePercent)
        }
    }

    /**
     * Apply the current step's target pace and incline to the treadmill.
     * Used when user wants to reset back to the step's defined values after manual adjustment.
     */
    fun applyCurrentStepTargets() {
        val executionState = workoutEngine?.state?.value
        if (executionState is WorkoutExecutionState.Running) {
            val step = executionState.currentStep
            Log.d(TAG, "Resetting to step targets: ${step.paceTargetKph} kph, ${step.inclineTargetPercent}%")
            applyStepTargets(step.paceTargetKph, step.inclineTargetPercent)
        } else if (executionState is WorkoutExecutionState.Paused) {
            val step = executionState.currentStep
            Log.d(TAG, "Resetting to step targets (paused): ${step.paceTargetKph} kph, ${step.inclineTargetPercent}%")
            applyStepTargets(step.paceTargetKph, step.inclineTargetPercent)
        } else {
            Log.d(TAG, "Cannot reset to step targets - not running or paused: $executionState")
        }
    }

    /**
     * Convert execution steps to PlannedSegments for chart.
     * Includes durationType and durationMeters for dynamic segment duration recalculation
     * when pace changes (speed coefficient).
     * Tags each segment with its workout phase (WARMUP/MAIN/COOLDOWN).
     */
    fun convertToPlannedSegments(steps: List<ExecutionStep>): List<WorkoutChart.PlannedSegment> {
        if (steps.isEmpty()) return emptyList()

        val engine = workoutEngine
        val (warmupCount, _, _) = engine?.getPhaseCounts() ?: Triple(0, 0, 0)

        val segments = mutableListOf<WorkoutChart.PlannedSegment>()
        var currentTimeMs = 0L
        val defaultOpenStepDurationMs = 5 * 60 * 1000L

        for ((index, step) in steps.withIndex()) {
            val durationMs = when (step.durationType) {
                DurationType.TIME -> {
                    (step.durationSeconds ?: 60) * 1000L
                }
                DurationType.DISTANCE -> {
                    val meters = step.durationMeters ?: 1000
                    val seconds = PaceConverter.calculateDurationSeconds(meters, step.paceTargetKph)
                    if (seconds > 0) (seconds * 1000).toLong() else defaultOpenStepDurationMs
                }
            }

            // Determine phase based on step index and boundary counts
            val phase = when {
                engine != null && engine.isWarmupStep(index) -> WorkoutChart.WorkoutPhase.WARMUP
                engine != null && engine.isCooldownStep(index) -> WorkoutChart.WorkoutPhase.COOLDOWN
                else -> WorkoutChart.WorkoutPhase.MAIN
            }

            segments.add(
                WorkoutChart.PlannedSegment(
                    startTimeMs = currentTimeMs,
                    endTimeMs = currentTimeMs + durationMs,
                    stepIndex = index,
                    stepName = step.displayName,
                    paceKph = step.paceTargetKph,
                    inclinePercent = step.inclineTargetPercent,
                    hrTargetMinPercent = step.hrTargetMinPercent,
                    hrTargetMaxPercent = step.hrTargetMaxPercent,
                    powerTargetMinPercent = step.powerTargetMinPercent,
                    powerTargetMaxPercent = step.powerTargetMaxPercent,
                    autoAdjustMode = step.autoAdjustMode,
                    durationType = step.durationType,
                    durationMeters = step.durationMeters,
                    phase = phase
                )
            )

            currentTimeMs += durationMs
        }

        return segments
    }

    // ==================== Private Helpers ====================

    private fun handleWorkoutEvent(event: WorkoutEvent) {
        when (event) {
            is WorkoutEvent.StepStarted -> {
                Log.d(TAG, "Step started: ${event.step.displayName}, effective pace=${event.effectivePaceKph}, incline=${event.effectiveInclinePercent}")
                applyStepTargets(event.effectivePaceKph, event.effectiveInclinePercent)
                // Update chart with current step and coefficients
                updateChartCoefficients()
            }
            is WorkoutEvent.WorkoutResumed -> {
                Log.d(TAG, "Workout resumed: ${event.step.displayName}, effective pace=${event.effectivePaceKph}, incline=${event.effectiveInclinePercent}")
                // Wait for belt to be ready before applying targets (belt may still be accelerating)
                scope.launch(Dispatchers.IO) {
                    waitForBeltReady()
                    listener?.onSetTreadmillSpeed(event.effectivePaceKph)
                    listener?.onSetTreadmillIncline(event.effectiveInclinePercent)
                }
                // Refresh chart timing/coefficients after resume so outlines/HR targets realign
                updateChartCoefficients()
            }
            is WorkoutEvent.StepCompleted -> {
                Log.d(TAG, "Step completed: ${event.step.displayName}")
            }
            is WorkoutEvent.SpeedAdjusted -> {
                // Apply speed adjustment through listener (which handles paceCoefficient conversion)
                Log.d(TAG, "Speed adjusted: ${event.newSpeedKph} kph (${event.reason})")
                listener?.onSetTreadmillSpeed(event.newSpeedKph)
            }
            is WorkoutEvent.InclineAdjusted -> {
                // Apply incline adjustment through listener
                Log.d(TAG, "Incline adjusted: ${event.newInclinePercent}% (${event.reason})")
                listener?.onSetTreadmillIncline(event.newInclinePercent)
            }
            is WorkoutEvent.HrOutOfRange -> {
                Log.d(TAG, "HR out of range: ${event.currentHr} (target: ${event.targetMin}-${event.targetMax})")
            }
            is WorkoutEvent.HrBackInRange -> {
                Log.d(TAG, "HR back in range: ${event.currentHr}")
            }
            is WorkoutEvent.HrEarlyEndTriggered -> {
                Log.d(TAG, "HR early end triggered: ${event.currentHr} (target: ${event.targetMin}-${event.targetMax})")
            }
            is WorkoutEvent.WorkoutCompleted -> {
                Log.d(TAG, "Workout completed: ${event.summary.stepsCompleted}/${event.summary.totalSteps} steps")
            }
            is WorkoutEvent.WorkoutPlanFinished -> {
                Log.d(TAG, "Workout plan finished: ${event.stepsCompleted} steps, entering auto-cooldown")
            }
            is WorkoutEvent.Warning -> {
                Log.w(TAG, "Workout warning: ${event.message}")
            }
            is WorkoutEvent.Error -> {
                Log.e(TAG, "Workout error: ${event.message}")
            }
            is WorkoutEvent.EffortAdjusted -> {
                Log.d(TAG, "Effort adjusted: ${event.type} ${event.displayString}")
                // Update chart with new coefficients
                updateChartCoefficients()
            }
        }
        listener?.onWorkoutEvent(event)
    }

    /**
     * Update chart with current step index, adjustment coefficients, and step elapsed time.
     * Step elapsed time is used to position future segments correctly when steps overrun/underrun.
     */
    private fun updateChartCoefficients() {
        val engine = workoutEngine ?: return
        val executionState = engine.state.value

        // Extract step index and timing from Running or Paused state
        val (stepIndex, stepElapsedMs, workoutElapsedMs) = when (executionState) {
            is WorkoutExecutionState.Running ->
                Triple(executionState.currentStepIndex, executionState.stepElapsedMs, executionState.workoutElapsedMs)
            is WorkoutExecutionState.Paused ->
                Triple(executionState.currentStepIndex, executionState.stepElapsedMs, executionState.workoutElapsedMs)
            else -> return
        }

        chartManager?.setAdjustmentCoefficients(
            currentStepIndex = stepIndex,
            speedCoeff = engine.getSpeedAdjustmentCoefficient(),
            inclineCoeff = engine.getInclineAdjustmentCoefficient(),
            stepElapsedMs = stepElapsedMs,
            workoutElapsedMs = workoutElapsedMs
        )
    }

    private suspend fun waitForBeltReady(timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        val pollIntervalMs = 100L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val client = getGlassOsClient()
            val workoutState = client?.getWorkoutState()

            // Only require RUNNING state - speed will be set after this returns
            if (workoutState == WorkoutState.WORKOUT_STATE_RUNNING) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Belt ready after ${elapsed}ms (state=$workoutState)")
                return true
            }

            delay(pollIntervalMs)
        }

        Log.w(TAG, "Timeout waiting for belt to be ready after ${timeoutMs}ms")
        return false
    }

    /**
     * Get current speed adjustment coefficient (default 1.0 when engine not running).
     */
    fun getSpeedAdjustmentCoefficient(): Double {
        val engine = workoutEngine ?: return 1.0
        return try {
            engine.getSpeedAdjustmentCoefficient()
        } catch (e: Exception) {
            1.0
        }
    }

    /**
     * Get current incline adjustment coefficient (default 1.0 when engine not running).
     */
    fun getInclineAdjustmentCoefficient(): Double {
        val engine = workoutEngine ?: return 1.0
        return try {
            engine.getInclineAdjustmentCoefficient()
        } catch (e: Exception) {
            1.0
        }
    }

    // ==================== State Persistence ====================

    /**
     * Export the current engine state for persistence.
     * Returns null if no workout is loaded or workout is idle.
     */
    fun exportEnginePersistenceState(): EnginePersistenceState? {
        return workoutEngine?.exportPersistenceState()
    }

    /**
     * Export the adjustment controller state for persistence.
     */
    fun exportAdjustmentControllerState(): AdjustmentControllerState? {
        return workoutEngine?.exportAdjustmentControllerState()
    }

    /**
     * Restore a structured workout from persisted state.
     * Loads the workout and restores the engine state.
     *
     * @param engineState The persisted engine state
     * @param adjustmentState The persisted adjustment controller state
     * @param onRestored Callback when restoration is complete (success: Boolean)
     */
    fun restoreFromPersistedState(
        engineState: EnginePersistenceState,
        adjustmentState: AdjustmentControllerState?,
        onRestored: (Boolean) -> Unit
    ) {
        scope.launch {
            val engine = workoutEngine
            if (engine == null) {
                Log.e(TAG, "Cannot restore state - engine not initialized")
                withContext(Dispatchers.Main) { onRestored(false) }
                return@launch
            }

            // Check if workout still exists in database
            val workoutWithSteps = repository.getWorkoutWithSteps(engineState.workoutId)
            if (workoutWithSteps == null) {
                Log.w(TAG, "Workout ${engineState.workoutId} no longer exists in database")
                withContext(Dispatchers.Main) { onRestored(false) }
                return@launch
            }

            // Load the workout (with stitched warmup/cooldown if applicable)
            val loaded = loadWorkoutStitched(engine, engineState.workoutId)
            if (!loaded) {
                Log.e(TAG, "Failed to load workout ${engineState.workoutId}")
                withContext(Dispatchers.Main) { onRestored(false) }
                return@launch
            }

            val workout = engine.getCurrentWorkout()
            val steps = engine.getExecutionSteps()
            if (workout != null) {
                withContext(Dispatchers.Main) {
                    listener?.onWorkoutLoaded(workout, steps)
                }
            }

            // Restore engine state
            engine.restoreFromPersistedState(engineState, isPaused = true)

            // Restore adjustment controller state if available
            if (adjustmentState != null) {
                engine.restoreAdjustmentControllerState(adjustmentState)
            }

            Log.d(TAG, "Restored workout state: ${workout?.name}, step ${engineState.currentStepIndex}")
            withContext(Dispatchers.Main) { onRestored(true) }
        }
    }

    /**
     * Get the workout name for a given workout ID (for persistence display).
     */
    fun getWorkoutNameById(workoutId: Long, callback: (String?) -> Unit) {
        scope.launch {
            val name = repository.getWorkoutWithSteps(workoutId)?.first?.name
            withContext(Dispatchers.Main) {
                callback(name)
            }
        }
    }
}
