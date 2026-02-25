package io.github.avikulin.thud.ui.editor

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.avikulin.thud.R
import io.github.avikulin.thud.data.db.TreadmillHudDatabase
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.data.repository.WorkoutRepository
import io.github.avikulin.thud.domain.model.AdjustmentScope
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.service.SettingsManager
import io.github.avikulin.thud.util.PaceConverter
import io.github.avikulin.thud.util.TssCalculator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * ViewModel for the unified Workout Editor screen (master-detail layout).
 * Manages workout list, selected workout editing, undo/redo, and auto-save.
 */
@OptIn(FlowPreview::class)
class WorkoutEditorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Counter for generating unique temporary IDs for unsaved steps
        // Negative IDs distinguish from saved steps (positive IDs from DB)
        private val tempIdCounter = AtomicLong(-1)
        fun nextTempId(): Long = tempIdCounter.decrementAndGet()
    }

    private val repository: WorkoutRepository
    private val prefs = application.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)

    // ==================== Workout List State ====================

    private val _allWorkouts = MutableStateFlow<List<Workout>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedWorkoutId = MutableStateFlow(0L)
    val selectedWorkoutId: StateFlow<Long> = _selectedWorkoutId.asStateFlow()

    /**
     * Filtered workouts based on search query.
     * Always includes the selected workout even if it doesn't match the filter.
     */
    val filteredWorkouts: StateFlow<List<Workout>> = combine(
        _allWorkouts,
        _searchQuery,
        _selectedWorkoutId
    ) { workouts, query, selectedId ->
        if (query.isBlank()) {
            workouts
        } else {
            workouts.filter { workout ->
                workout.isSystemWorkout ||
                workout.id == selectedId ||
                workout.name.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ==================== Selected Workout State ====================

    private val _workoutName = MutableStateFlow("")
    val workoutName: StateFlow<String> = _workoutName.asStateFlow()

    private val _workoutDescription = MutableStateFlow("")
    val workoutDescription: StateFlow<String> = _workoutDescription.asStateFlow()

    private val _steps = MutableStateFlow<List<WorkoutStep>>(emptyList())
    val steps: StateFlow<List<WorkoutStep>> = _steps.asStateFlow()

    // ==================== Undo/Redo ====================

    private val undoRedoManager = UndoRedoManager<EditorState>(maxHistory = 50)

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    // ==================== Loading/Saving State ====================

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var autoSaveJob: Job? = null
    private val autoSaveDelayMs = 500L

    // ==================== Summary ====================

    private val _summary = MutableStateFlow(WorkoutSummary())
    val summary: StateFlow<WorkoutSummary> = _summary.asStateFlow()

    // ==================== Default Warmup/Cooldown ====================

    private val _warmupEnabled = MutableStateFlow(false)
    val warmupEnabled: StateFlow<Boolean> = _warmupEnabled.asStateFlow()

    private val _cooldownEnabled = MutableStateFlow(false)
    val cooldownEnabled: StateFlow<Boolean> = _cooldownEnabled.asStateFlow()

    private val _adjustmentScope = MutableStateFlow(AdjustmentScope.ALL_STEPS)
    val adjustmentScope: StateFlow<AdjustmentScope> = _adjustmentScope.asStateFlow()

    private val _warmupSummary = MutableStateFlow("")
    val warmupSummary: StateFlow<String> = _warmupSummary.asStateFlow()

    private val _cooldownSummary = MutableStateFlow("")
    val cooldownSummary: StateFlow<String> = _cooldownSummary.asStateFlow()

    /** Whether the selected workout is a system workout (no rename/delete). */
    private val _isSystemWorkout = MutableStateFlow(false)
    val isSystemWorkout: StateFlow<Boolean> = _isSystemWorkout.asStateFlow()

    // ==================== Settings ====================

    private var thresholdPaceKph: Double = SettingsManager.DEFAULT_THRESHOLD_PACE_KPH
    private var defaultIncline: Double = SettingsManager.DEFAULT_INCLINE
    private var userLthrBpm: Int = SettingsManager.DEFAULT_USER_LTHR_BPM
    private var hrRest: Int = SettingsManager.DEFAULT_USER_HR_REST

    init {
        val database = TreadmillHudDatabase.getActiveInstance(application)
        repository = WorkoutRepository(database.workoutDao())

        // Load settings
        loadSettings()

        // Ensure system workouts exist and load summaries
        viewModelScope.launch {
            repository.ensureSystemWorkoutsExist()
            loadSystemWorkoutSummaries()
        }

        // Observe all workouts from repository
        viewModelScope.launch {
            repository.allWorkouts.collect { workouts ->
                _allWorkouts.value = workouts

                // Auto-select most recently used/edited regular workout if none selected
                if (_selectedWorkoutId.value == 0L && workouts.isNotEmpty()) {
                    val firstRegular = workouts.firstOrNull { !it.isSystemWorkout }
                    selectWorkout((firstRegular ?: workouts.first()).id)
                }
            }
        }
    }

    private fun loadSettings() {
        thresholdPaceKph = prefs.getFloat(
            SettingsManager.PREF_THRESHOLD_PACE_KPH,
            SettingsManager.DEFAULT_THRESHOLD_PACE_KPH.toFloat()
        ).toDouble()
        defaultIncline = prefs.getFloat(
            SettingsManager.PREF_DEFAULT_INCLINE,
            SettingsManager.DEFAULT_INCLINE.toFloat()
        ).toDouble()
        userLthrBpm = prefs.getInt(
            SettingsManager.PREF_USER_LTHR_BPM,
            SettingsManager.DEFAULT_USER_LTHR_BPM
        )
        hrRest = prefs.getInt(
            SettingsManager.PREF_USER_HR_REST,
            SettingsManager.DEFAULT_USER_HR_REST
        )
    }

    // ==================== Workout List Operations ====================

    /**
     * Update search query for filtering workout list.
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query

        // Auto-select first matching workout if current selection doesn't match
        val filtered = filteredWorkouts.value
        if (filtered.isNotEmpty() && filtered.none { it.id == _selectedWorkoutId.value }) {
            selectWorkout(filtered.first().id)
        }
    }

    /**
     * Select a workout for editing.
     * Clears undo history when switching workouts.
     */
    fun selectWorkout(workoutId: Long) {
        if (workoutId == _selectedWorkoutId.value) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val workoutWithSteps = repository.getWorkoutWithSteps(workoutId)
                if (workoutWithSteps != null) {
                    val (workout, steps) = workoutWithSteps
                    _selectedWorkoutId.value = workout.id
                    _workoutName.value = workout.name
                    _workoutDescription.value = workout.description ?: ""
                    _steps.value = steps.sortedBy { it.orderIndex }
                    _isSystemWorkout.value = workout.isSystemWorkout
                    _warmupEnabled.value = workout.useDefaultWarmup
                    _cooldownEnabled.value = workout.useDefaultCooldown
                    _adjustmentScope.value = workout.adjustmentScope

                    // Refresh sentinel summaries when switching to a regular workout
                    // (covers returning from editing a system workout)
                    if (!workout.isSystemWorkout) {
                        loadSystemWorkoutSummaries()
                    }

                    // Clear undo history when switching workouts
                    undoRedoManager.clear()
                    updateUndoRedoState()
                    updateSummary()
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Create a new workout with a default warmup step.
     */
    fun createNewWorkout() {
        viewModelScope.launch {
            val workoutId = repository.createWorkout("New Workout")

            // Create default warmup step
            val warmupStep = createDefaultStep(StepType.WARMUP).copy(
                workoutId = workoutId,
                durationSeconds = 600 // 10 minutes
            )
            repository.saveWorkout(
                Workout(id = workoutId, name = "New Workout"),
                listOf(warmupStep)
            )

            // Select the new workout
            selectWorkout(workoutId)
        }
    }

    /**
     * Create a new workout with a custom name (called when user starts typing name with no workout selected).
     */
    private fun createNewWorkoutWithName(name: String) {
        viewModelScope.launch {
            val workoutId = repository.createWorkout(name)

            // Create default warmup step
            val warmupStep = createDefaultStep(StepType.WARMUP).copy(
                workoutId = workoutId,
                durationSeconds = 600 // 10 minutes
            )
            repository.saveWorkout(
                Workout(id = workoutId, name = name),
                listOf(warmupStep)
            )

            // Select the new workout
            selectWorkout(workoutId)
        }
    }

    /**
     * Create a new workout then add a step of the given type (called when user adds step with no workout selected).
     */
    private fun createNewWorkoutThenAddStep(type: StepType) {
        viewModelScope.launch {
            val workoutId = repository.createWorkout("New Workout")

            // Create the requested step type instead of default warmup
            val step = createDefaultStep(type).copy(
                workoutId = workoutId
            )
            repository.saveWorkout(
                Workout(id = workoutId, name = "New Workout"),
                listOf(step)
            )

            // Select the new workout
            selectWorkout(workoutId)
        }
    }

    /**
     * Duplicate a workout with "(Copy N)" prefix.
     */
    fun duplicateWorkout(workoutId: Long) {
        viewModelScope.launch {
            val workout = _allWorkouts.value.find { it.id == workoutId } ?: return@launch

            // Generate copy name with incrementing number
            val baseName = workout.name
            val existingNames = _allWorkouts.value.map { it.name }
            var copyNum = 1
            var newName: String
            do {
                newName = "(Copy $copyNum) $baseName"
                copyNum++
            } while (existingNames.contains(newName))

            val newId = repository.duplicateWorkout(workoutId, newName)
            if (newId != null) {
                selectWorkout(newId)
            }
        }
    }

    /**
     * Delete a workout after confirmation.
     * System workouts cannot be deleted.
     */
    fun deleteWorkout(workoutId: Long) {
        viewModelScope.launch {
            val workout = _allWorkouts.value.find { it.id == workoutId } ?: return@launch
            if (workout.isSystemWorkout) return@launch  // System workouts are permanent
            repository.deleteWorkout(workout)

            // If deleted workout was selected, select another one
            if (_selectedWorkoutId.value == workoutId) {
                val remaining = _allWorkouts.value.filter { it.id != workoutId }
                if (remaining.isNotEmpty()) {
                    selectWorkout(remaining.first().id)
                } else {
                    // No workouts left - create a new one
                    createNewWorkout()
                }
            }
        }
    }

    // ==================== Workout Editing ====================

    /**
     * Update workout name.
     * If no workout is selected, creates a new one with the given name.
     */
    fun setWorkoutName(name: String) {
        if (name == _workoutName.value) return

        // Auto-create workout if editing with none selected
        if (_selectedWorkoutId.value == 0L && name.isNotBlank()) {
            createNewWorkoutWithName(name)
            return
        }

        saveCurrentStateForUndo()
        _workoutName.value = name
        triggerAutoSave()
    }

    /**
     * Update workout description.
     */
    fun setWorkoutDescription(description: String) {
        if (description == _workoutDescription.value) return
        saveCurrentStateForUndo()
        _workoutDescription.value = description
        triggerAutoSave()
    }

    // ==================== Default Warmup/Cooldown Operations ====================

    /**
     * Toggle the "Use Default Warmup" flag.
     */
    fun setWarmupEnabled(enabled: Boolean) {
        if (enabled == _warmupEnabled.value) return
        _warmupEnabled.value = enabled
        triggerAutoSave()
    }

    /**
     * Toggle the "Use Default Cooldown" flag.
     */
    fun setCooldownEnabled(enabled: Boolean) {
        if (enabled == _cooldownEnabled.value) return
        _cooldownEnabled.value = enabled
        triggerAutoSave()
    }

    fun setAdjustmentScope(scope: AdjustmentScope) {
        if (scope == _adjustmentScope.value) return
        _adjustmentScope.value = scope
        triggerAutoSave()
    }

    /**
     * Load summaries for system warmup/cooldown workouts.
     */
    private suspend fun loadSystemWorkoutSummaries() {
        val warmup = repository.getSystemWarmup()
        _warmupSummary.value = if (warmup != null) formatStepSummary(warmup.second) else ""

        val cooldown = repository.getSystemCooldown()
        _cooldownSummary.value = if (cooldown != null) formatStepSummary(cooldown.second) else ""
    }

    /**
     * Reload system workout summaries (called after editing a system workout).
     */
    fun refreshSystemWorkoutSummaries() {
        viewModelScope.launch {
            loadSystemWorkoutSummaries()
        }
    }

    /**
     * Format a step list into a summary string like "3 steps, 5:00".
     */
    private fun formatStepSummary(steps: List<WorkoutStep>): String {
        if (steps.isEmpty()) {
            return getApplication<android.app.Application>().getString(R.string.sentinel_empty_summary)
        }

        var totalSeconds = 0.0
        var stepCount = 0
        forEachEffectiveStep(steps) { step, repeatMultiplier ->
            stepCount += repeatMultiplier
            totalSeconds += calculateStepDurationAndDistance(step).first * repeatMultiplier
        }

        val formatted = PaceConverter.formatDuration(totalSeconds.toInt())
        return getApplication<android.app.Application>().getString(
            R.string.sentinel_warmup_summary, stepCount, formatted
        )
    }

    // ==================== Step Operations ====================

    /**
     * Add a new step at the end of the list.
     * If no workout is selected, creates a new one first.
     */
    fun addStep(type: StepType) {
        // Auto-create workout if adding step with none selected
        if (_selectedWorkoutId.value == 0L) {
            createNewWorkoutThenAddStep(type)
            return
        }

        saveCurrentStateForUndo()

        val step = createDefaultStep(type)
        val currentSteps = _steps.value.toMutableList()

        if (type == StepType.REPEAT) {
            // Add repeat with 2 default substeps (Run 5min, Recover 2min)
            // step already has unique temp ID from createDefaultStep
            val repeatStep = step.copy(orderIndex = currentSteps.size)
            currentSteps.add(repeatStep)

            // Add Run substep (5 minutes)
            val runSubstep = createDefaultStep(StepType.RUN).copy(
                orderIndex = currentSteps.size,
                durationSeconds = 300, // 5 minutes
                parentRepeatStepId = repeatStep.id
            )
            currentSteps.add(runSubstep)

            // Add Recover substep (2 minutes)
            val recoverSubstep = createDefaultStep(StepType.RECOVER).copy(
                orderIndex = currentSteps.size,
                durationSeconds = 120, // 2 minutes
                parentRepeatStepId = repeatStep.id
            )
            currentSteps.add(recoverSubstep)
        } else {
            currentSteps.add(step.copy(orderIndex = currentSteps.size))
        }

        _steps.value = currentSteps
        updateSummary()
        triggerAutoSave()
    }

    /**
     * Add a step below the specified index.
     */
    fun addStepBelow(index: Int, type: StepType) {
        saveCurrentStateForUndo()

        val currentSteps = _steps.value.toMutableList()
        val existingStep = currentSteps.getOrNull(index) ?: return

        val newStep = createDefaultStep(type)

        // Determine insert position and parent
        val insertIndex: Int
        val parentId: Long?

        if (existingStep.parentRepeatStepId != null) {
            // Adding below a substep - add as sibling within the same repeat
            insertIndex = index + 1
            parentId = existingStep.parentRepeatStepId
        } else if (existingStep.type == StepType.REPEAT) {
            // Adding below a repeat - add after all its substeps
            val lastSubstepIndex = currentSteps.indexOfLast { it.parentRepeatStepId == existingStep.id }
            insertIndex = if (lastSubstepIndex >= 0) lastSubstepIndex + 1 else index + 1
            parentId = null
        } else {
            // Adding below a regular step
            insertIndex = index + 1
            parentId = null
        }

        val stepToInsert = newStep.copy(parentRepeatStepId = parentId)
        currentSteps.add(insertIndex, stepToInsert)

        // Re-index all steps
        _steps.value = reindexSteps(currentSteps)
        updateSummary()
        triggerAutoSave()
    }

    /**
     * Add a substep to a repeat block.
     */
    fun addSubstepToRepeat(repeatIndex: Int, type: StepType) {
        saveCurrentStateForUndo()

        val currentSteps = _steps.value.toMutableList()
        val repeatStep = currentSteps.getOrNull(repeatIndex) ?: return
        if (repeatStep.type != StepType.REPEAT) return

        val newStep = createDefaultStep(type).copy(
            workoutId = _selectedWorkoutId.value,
            parentRepeatStepId = repeatStep.id
        )

        // Find position after last substep of this repeat
        val lastSubstepIndex = currentSteps.indexOfLast { it.parentRepeatStepId == repeatStep.id }
        val insertIndex = if (lastSubstepIndex >= 0) lastSubstepIndex + 1 else repeatIndex + 1

        currentSteps.add(insertIndex, newStep)
        _steps.value = reindexSteps(currentSteps)
        updateSummary()
        triggerAutoSave()
    }

    /**
     * Update an existing step.
     */
    fun updateStep(index: Int, step: WorkoutStep) {
        saveCurrentStateForUndo()

        val currentSteps = _steps.value.toMutableList()
        if (index in currentSteps.indices) {
            currentSteps[index] = step.copy(orderIndex = index)
            _steps.value = currentSteps
            updateSummary()
            triggerAutoSave()
        }
    }

    /**
     * Delete a step at the given index.
     * If deleting a repeat, also deletes all its substeps.
     */
    fun deleteStep(index: Int) {
        saveCurrentStateForUndo()

        val currentSteps = _steps.value.toMutableList()
        val step = currentSteps.getOrNull(index) ?: return

        if (step.type == StepType.REPEAT) {
            // Remove the repeat and all its substeps
            currentSteps.removeAll { it.parentRepeatStepId == step.id }
        }
        currentSteps.removeAt(index)

        _steps.value = reindexSteps(currentSteps)
        updateSummary()
        triggerAutoSave()
    }

    /**
     * Duplicate a step (and its substeps if it's a repeat).
     */
    fun duplicateStep(index: Int) {
        saveCurrentStateForUndo()

        val currentSteps = _steps.value.toMutableList()
        val step = currentSteps.getOrNull(index) ?: return

        if (step.type == StepType.REPEAT) {
            // Find substeps by position (immediately following the repeat step)
            val substeps = mutableListOf<WorkoutStep>()
            for (i in (index + 1) until currentSteps.size) {
                val s = currentSteps[i]
                if (s.parentRepeatStepId != null) {
                    substeps.add(s)
                } else {
                    break  // Hit a non-substep, stop
                }
            }

            val insertIndex = index + 1 + substeps.size

            // Generate a unique temporary ID for the new repeat
            val newRepeatId = nextTempId()

            // Duplicate repeat with new unique ID
            val newRepeat = step.copy(id = newRepeatId)
            currentSteps.add(insertIndex, newRepeat)

            // Duplicate substeps with new unique IDs and reference to the new repeat
            var subInsertIndex = insertIndex + 1
            for (substep in substeps) {
                currentSteps.add(subInsertIndex++, substep.copy(id = nextTempId(), parentRepeatStepId = newRepeatId))
            }
        } else if (step.parentRepeatStepId != null) {
            // Duplicate substep within the same repeat (new unique ID, same parent)
            currentSteps.add(index + 1, step.copy(id = nextTempId()))
        } else {
            // Duplicate regular step (new unique ID)
            currentSteps.add(index + 1, step.copy(id = nextTempId()))
        }

        _steps.value = reindexSteps(currentSteps)
        updateSummary()
        triggerAutoSave()
    }

    /**
     * Move a step up in the list.
     * - Substeps can only move within their parent repeat
     * - Regular steps jump over entire repeat blocks
     * - Repeat steps move with all their substeps
     */
    fun moveStepUp(index: Int) {
        if (index <= 0) return

        val currentSteps = _steps.value.toMutableList()
        val step = currentSteps.getOrNull(index) ?: return

        // Substeps can only swap with siblings within the same repeat
        if (step.parentRepeatStepId != null) {
            val prevStep = currentSteps.getOrNull(index - 1) ?: return
            if (prevStep.parentRepeatStepId != step.parentRepeatStepId) return

            saveCurrentStateForUndo()
            // Simple swap with sibling
            currentSteps[index] = prevStep
            currentSteps[index - 1] = step
            _steps.value = reindexSteps(currentSteps)
            triggerAutoSave()
            return
        }

        // Find where to insert (skip over repeat blocks)
        var targetIndex = index - 1
        val prevStep = currentSteps.getOrNull(targetIndex) ?: return

        // If previous step is a substep, find the repeat header and go before it
        if (prevStep.parentRepeatStepId != null) {
            // Find the repeat header
            for (i in (targetIndex - 1) downTo 0) {
                if (currentSteps[i].id == prevStep.parentRepeatStepId) {
                    targetIndex = i
                    break
                }
            }
        }

        if (targetIndex < 0) return

        saveCurrentStateForUndo()

        if (step.type == StepType.REPEAT) {
            // Move repeat with all its substeps
            val substeps = currentSteps.filter { it.parentRepeatStepId == step.id }
            val allToMove = listOf(step) + substeps

            currentSteps.removeAll(allToMove.toSet())
            currentSteps.addAll(targetIndex, allToMove)
        } else {
            // Move single step
            currentSteps.removeAt(index)
            currentSteps.add(targetIndex, step)
        }

        _steps.value = reindexSteps(currentSteps)
        triggerAutoSave()
    }

    /**
     * Move a step down in the list.
     * - Substeps can only move within their parent repeat
     * - Regular steps jump over entire repeat blocks
     * - Repeat steps move with all their substeps
     */
    fun moveStepDown(index: Int) {
        val currentSteps = _steps.value.toMutableList()
        val step = currentSteps.getOrNull(index) ?: return

        // Substeps can only swap with siblings within the same repeat
        if (step.parentRepeatStepId != null) {
            val nextStep = currentSteps.getOrNull(index + 1) ?: return
            if (nextStep.parentRepeatStepId != step.parentRepeatStepId) return

            saveCurrentStateForUndo()
            // Simple swap with sibling
            currentSteps[index] = nextStep
            currentSteps[index + 1] = step
            _steps.value = reindexSteps(currentSteps)
            triggerAutoSave()
            return
        }

        // Find the end position of current step/repeat block
        val currentEndIndex = if (step.type == StepType.REPEAT) {
            val lastSubstepIndex = currentSteps.indexOfLast { it.parentRepeatStepId == step.id }
            if (lastSubstepIndex >= 0) lastSubstepIndex else index
        } else {
            index
        }

        if (currentEndIndex >= currentSteps.size - 1) return

        // Find where to insert (after the next item, skipping over repeat blocks)
        val nextStep = currentSteps.getOrNull(currentEndIndex + 1) ?: return
        var targetIndex = currentEndIndex + 1

        // If next step is a repeat, find the end of its substeps
        if (nextStep.type == StepType.REPEAT) {
            val lastSubstepIndex = currentSteps.indexOfLast { it.parentRepeatStepId == nextStep.id }
            targetIndex = if (lastSubstepIndex >= 0) lastSubstepIndex + 1 else targetIndex + 1
        } else {
            targetIndex = currentEndIndex + 2
        }

        saveCurrentStateForUndo()

        if (step.type == StepType.REPEAT) {
            // Move repeat with all its substeps
            val substeps = currentSteps.filter { it.parentRepeatStepId == step.id }
            val allToMove = listOf(step) + substeps

            // Insert at target position first (before removal shifts indices)
            val insertIndex = minOf(targetIndex, currentSteps.size)
            currentSteps.addAll(insertIndex, allToMove)
            // Remove originals (now at original positions)
            for (i in (index + allToMove.size - 1) downTo index) {
                currentSteps.removeAt(i)
            }
        } else {
            // Move single step
            val insertIndex = minOf(targetIndex, currentSteps.size)
            currentSteps.add(insertIndex, step)
            currentSteps.removeAt(index)
        }

        _steps.value = reindexSteps(currentSteps)
        triggerAutoSave()
    }

    // ==================== Undo/Redo ====================

    private fun saveCurrentStateForUndo() {
        val state = EditorState(
            name = _workoutName.value,
            description = _workoutDescription.value,
            steps = _steps.value.toList()
        )
        undoRedoManager.saveState(state)
        updateUndoRedoState()
    }

    /**
     * Undo the last change.
     */
    fun undo() {
        val currentState = EditorState(
            name = _workoutName.value,
            description = _workoutDescription.value,
            steps = _steps.value.toList()
        )
        val previousState = undoRedoManager.undo(currentState) ?: return

        applyState(previousState)
        updateUndoRedoState()
        triggerAutoSave()
    }

    /**
     * Redo a previously undone change.
     */
    fun redo() {
        val currentState = EditorState(
            name = _workoutName.value,
            description = _workoutDescription.value,
            steps = _steps.value.toList()
        )
        val nextState = undoRedoManager.redo(currentState) ?: return

        applyState(nextState)
        updateUndoRedoState()
        triggerAutoSave()
    }

    private fun applyState(state: EditorState) {
        _workoutName.value = state.name
        _workoutDescription.value = state.description
        _steps.value = state.steps
        updateSummary()
    }

    private fun updateUndoRedoState() {
        _canUndo.value = undoRedoManager.canUndo()
        _canRedo.value = undoRedoManager.canRedo()
    }

    // ==================== Auto-Save ====================

    private fun triggerAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(autoSaveDelayMs)
            saveWorkoutInternal()
        }
    }

    private suspend fun saveWorkoutInternal() {
        val workoutId = _selectedWorkoutId.value
        if (workoutId == 0L) return

        val name = _workoutName.value.trim()
        if (name.isEmpty()) return

        // Preserve existing fields from the loaded workout
        val existing = repository.getWorkout(workoutId)
        val workout = Workout(
            id = workoutId,
            name = name,
            description = _workoutDescription.value.trim().ifEmpty { null },
            estimatedTss = _summary.value.estimatedTss,
            systemWorkoutType = existing?.systemWorkoutType,
            useDefaultWarmup = _warmupEnabled.value,
            useDefaultCooldown = _cooldownEnabled.value,
            adjustmentScope = _adjustmentScope.value,
            lastExecutedAt = existing?.lastExecutedAt
        )

        repository.saveWorkout(workout, _steps.value)
    }

    // ==================== Helper Functions ====================

    private fun reindexSteps(steps: MutableList<WorkoutStep>): List<WorkoutStep> {
        return steps.mapIndexed { i, step ->
            step.copy(orderIndex = i)
        }
    }

    /**
     * Create a default step with sensible defaults based on threshold pace.
     * All new steps get unique negative temp IDs for proper DiffUtil tracking.
     */
    fun createDefaultStep(type: StepType): WorkoutStep {
        val pace = getDefaultPaceForType(type)
        val tempId = nextTempId()
        return when (type) {
            StepType.WARMUP -> WorkoutStep(
                id = tempId,
                workoutId = _selectedWorkoutId.value,
                orderIndex = _steps.value.size,
                type = StepType.WARMUP,
                durationType = DurationType.TIME,
                durationSeconds = 300,
                paceTargetKph = pace,
                inclineTargetPercent = defaultIncline
            )
            StepType.RUN -> WorkoutStep(
                id = tempId,
                workoutId = _selectedWorkoutId.value,
                orderIndex = _steps.value.size,
                type = StepType.RUN,
                durationType = DurationType.TIME,
                durationSeconds = 60,
                paceTargetKph = pace,
                inclineTargetPercent = defaultIncline
            )
            StepType.RECOVER -> WorkoutStep(
                id = tempId,
                workoutId = _selectedWorkoutId.value,
                orderIndex = _steps.value.size,
                type = StepType.RECOVER,
                durationType = DurationType.TIME,
                durationSeconds = 60,
                paceTargetKph = pace,
                inclineTargetPercent = 0.0
            )
            StepType.REST -> WorkoutStep(
                id = tempId,
                workoutId = _selectedWorkoutId.value,
                orderIndex = _steps.value.size,
                type = StepType.REST,
                durationType = DurationType.TIME,
                durationSeconds = 60,
                paceTargetKph = 0.0,
                inclineTargetPercent = 0.0
            )
            StepType.COOLDOWN -> WorkoutStep(
                id = tempId,
                workoutId = _selectedWorkoutId.value,
                orderIndex = _steps.value.size,
                type = StepType.COOLDOWN,
                durationType = DurationType.TIME,
                durationSeconds = 300,
                paceTargetKph = pace,
                inclineTargetPercent = 0.0
            )
            StepType.REPEAT -> WorkoutStep(
                id = tempId,
                workoutId = _selectedWorkoutId.value,
                orderIndex = _steps.value.size,
                type = StepType.REPEAT,
                durationType = DurationType.TIME,
                paceTargetKph = 0.0,
                inclineTargetPercent = 0.0,
                repeatCount = 4
            )
        }
    }

    /**
     * Get default pace for step type based on threshold pace.
     */
    private fun getDefaultPaceForType(type: StepType): Double {
        return when (type) {
            StepType.WARMUP, StepType.COOLDOWN -> thresholdPaceKph * 0.65  // Zone 1
            StepType.RECOVER -> thresholdPaceKph * 0.75                     // Zone 2
            StepType.RUN -> thresholdPaceKph * 0.88                         // Zone 3
            StepType.REST -> 0.0                                            // Treadmill minimum
            StepType.REPEAT -> 0.0                                          // N/A
        }
    }

    /**
     * Traverse a step list, expanding REPEAT blocks into effective steps.
     * Calls [action] for each executable step with its repeat multiplier
     * (1 for top-level steps, repeatCount for children of REPEAT blocks).
     */
    private inline fun forEachEffectiveStep(
        steps: List<WorkoutStep>,
        action: (step: WorkoutStep, repeatMultiplier: Int) -> Unit
    ) {
        var stepIndex = 0
        while (stepIndex < steps.size) {
            val step = steps[stepIndex]
            if (step.type == StepType.REPEAT) {
                val childSteps = mutableListOf<WorkoutStep>()
                var childIndex = stepIndex + 1
                while (childIndex < steps.size && steps[childIndex].parentRepeatStepId != null) {
                    childSteps.add(steps[childIndex])
                    childIndex++
                }
                val repeatCount = step.repeatCount ?: 1
                for (child in childSteps) {
                    action(child, repeatCount)
                }
                stepIndex = childIndex
            } else if (step.parentRepeatStepId == null) {
                action(step, 1)
                stepIndex++
            } else {
                stepIndex++
            }
        }
    }

    private fun updateSummary() {
        val steps = _steps.value
        var totalSeconds = 0.0
        var totalMeters = 0.0
        var stepCount = 0
        var totalTss = 0.0
        var hasValidTssSteps = false

        forEachEffectiveStep(steps) { step, repeatMultiplier ->
            stepCount += repeatMultiplier
            val (seconds, meters) = calculateStepDurationAndDistance(step)
            totalSeconds += seconds * repeatMultiplier
            totalMeters += meters * repeatMultiplier

            // Accumulate TSS in the same pass (Power > HR > Pace priority)
            val tss = calculateStepTss(step, repeatMultiplier)
            if (tss != null) {
                totalTss += tss
                hasValidTssSteps = true
            }
        }

        _summary.value = WorkoutSummary(
            stepCount = stepCount,
            estimatedDurationSeconds = if (totalSeconds > 0) totalSeconds.toInt() else null,
            estimatedDistanceMeters = if (totalMeters > 0) totalMeters.toInt() else null,
            estimatedTss = if (hasValidTssSteps) totalTss.toInt() else null
        )
    }

    /**
     * Calculate both duration and distance for a step by cross-calculating using pace.
     * TIME-based steps: calculate distance from pace
     * DISTANCE-based steps: calculate time from pace
     */
    private fun calculateStepDurationAndDistance(step: WorkoutStep): Pair<Double, Double> {
        return when (step.durationType) {
            DurationType.TIME -> {
                val seconds = (step.durationSeconds ?: 0).toDouble()
                val meters = PaceConverter.calculateDistanceMeters(
                    step.durationSeconds ?: 0,
                    step.paceTargetKph
                )
                Pair(seconds, meters)
            }
            DurationType.DISTANCE -> {
                val meters = (step.durationMeters ?: 0).toDouble()
                val seconds = PaceConverter.calculateDurationSeconds(
                    step.durationMeters ?: 0,
                    step.paceTargetKph
                )
                Pair(seconds, meters)
            }
        }
    }

    /**
     * Calculate TSS for a single step using TssCalculator.
     * Priority: HR targets (if defined) > Pace target
     */
    private fun calculateStepTss(step: WorkoutStep, repeatCount: Int): Double? {
        if (step.type == StepType.REPEAT) return null

        // Get duration in seconds
        val durationSeconds = when (step.durationType) {
            DurationType.TIME -> step.durationSeconds ?: return null
            DurationType.DISTANCE -> {
                val meters = step.durationMeters ?: return null
                val seconds = PaceConverter.calculateDurationSeconds(meters, step.paceTargetKph)
                if (seconds > 0) seconds.toInt() else return null
            }
        }

        // Get HR if targets are defined (convert % to BPM using LTHR)
        val hrAvg = if (step.hrTargetMinPercent != null && step.hrTargetMaxPercent != null) {
            (step.hrTargetMinPercent + step.hrTargetMaxPercent) / 2.0 * userLthrBpm / 100.0
        } else {
            null
        }

        // Use TssCalculator for unified calculation (HR if available, else pace)
        val result = TssCalculator.calculateIntervalTss(
            durationSeconds = durationSeconds,
            powerWatts = null,  // Editor doesn't have power data
            heartRateBpm = hrAvg,
            speedKph = step.paceTargetKph,
            ftpWatts = 250,  // Not used since we don't have power
            lthr = userLthrBpm,
            hrRest = hrRest,
            thresholdPaceKph = thresholdPaceKph
        )

        return result.tss * repeatCount
    }

    // ==================== Data Classes ====================

    private data class EditorState(
        val name: String,
        val description: String,
        val steps: List<WorkoutStep>
    )

    data class WorkoutSummary(
        val stepCount: Int = 0,
        val estimatedDurationSeconds: Int? = null,
        val estimatedDistanceMeters: Int? = null,
        val estimatedTss: Int? = null
    )
}
