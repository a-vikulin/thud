package io.github.avikulin.thud.ui.editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.HUDService
import io.github.avikulin.thud.R
import io.github.avikulin.thud.domain.model.AdjustmentScope
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.service.SettingsManager
import io.github.avikulin.thud.ui.components.WorkoutChart
import io.github.avikulin.thud.util.PaceConverter
import kotlinx.coroutines.launch

/**
 * New unified workout editor with master-detail layout.
 * Left pane (25%): Searchable workout list
 * Right pane (75%): Workout editor with preview chart and inline step editing
 */
class WorkoutEditorActivityNew : AppCompatActivity() {

    companion object {
        const val EXTRA_WORKOUT_ID = "workout_id"
    }

    private val viewModel: WorkoutEditorViewModel by viewModels()

    // Left pane views
    private lateinit var btnBack: View
    private lateinit var etSearch: EditText
    private lateinit var rvWorkoutList: RecyclerView
    private lateinit var btnAddWorkout: Button

    // Right pane views
    private lateinit var btnRun: Button
    private lateinit var etWorkoutName: EditText
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var chartContainer: FrameLayout
    private lateinit var rvSteps: RecyclerView

    // Stats bar
    private lateinit var tvStats: TextView
    private lateinit var spinnerAdjustmentScope: Spinner

    // Components
    private lateinit var workoutListAdapter: WorkoutListAdapter
    private lateinit var stepAdapter: InlineStepAdapter
    private var previewChart: WorkoutChart? = null

    // Settings (HR zones as percentages of LTHR, Power zones as percentages of FTP)
    // Stored with 1 decimal precision for integer BPM/watt snapping
    private var hrZone2StartPercent = 80.0
    private var hrZone3StartPercent = 88.0
    private var hrZone4StartPercent = 95.0
    private var hrZone5StartPercent = 102.0
    private var powerZone2StartPercent = 55.0
    private var powerZone3StartPercent = 75.0
    private var powerZone4StartPercent = 90.0
    private var powerZone5StartPercent = 105.0
    private var userLthrBpm = 170
    private var userFtpWatts = 250

    // Treadmill capabilities (from GlassOS via SharedPreferences)
    private var treadmillMinPaceSeconds = 180   // 3:00/km (20 kph default)
    private var treadmillMaxPaceSeconds = 3600  // 60:00/km (1 kph default)
    private var treadmillMinIncline = SettingsManager.DEFAULT_TREADMILL_MIN_INCLINE
    private var treadmillMaxIncline = SettingsManager.DEFAULT_TREADMILL_MAX_INCLINE
    private var treadmillInclineStep = SettingsManager.DEFAULT_TREADMILL_INCLINE_STEP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workout_editor_new)

        loadSettings()
        initViews()
        setupAdapters()
        setupListeners()
        observeViewModel()

        // Load initial workout if specified
        val workoutId = intent.getLongExtra(EXTRA_WORKOUT_ID, 0L)
        if (workoutId > 0L) {
            viewModel.selectWorkout(workoutId)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        // Load LTHR and zone percentages from settings
        userLthrBpm = prefs.getInt(SettingsManager.PREF_USER_LTHR_BPM, SettingsManager.DEFAULT_USER_LTHR_BPM)
        userFtpWatts = prefs.getInt(SettingsManager.PREF_USER_FTP_WATTS, SettingsManager.DEFAULT_USER_FTP_WATTS)
        // Migration: older versions stored zones as Int, use SettingsManager helper
        hrZone2StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_HR_ZONE2_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE2_START_PERCENT)
        hrZone3StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_HR_ZONE3_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE3_START_PERCENT)
        hrZone4StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_HR_ZONE4_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE4_START_PERCENT)
        hrZone5StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_HR_ZONE5_START_PERCENT, SettingsManager.DEFAULT_HR_ZONE5_START_PERCENT)
        powerZone2StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_POWER_ZONE2_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE2_START_PERCENT)
        powerZone3StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_POWER_ZONE3_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE3_START_PERCENT)
        powerZone4StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_POWER_ZONE4_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE4_START_PERCENT)
        powerZone5StartPercent = SettingsManager.getFloatOrInt(prefs, SettingsManager.PREF_POWER_ZONE5_START_PERCENT, SettingsManager.DEFAULT_POWER_ZONE5_START_PERCENT)

        // Treadmill capabilities (populated by HUDService from GlassOS)
        val minSpeedKph = prefs.getFloat(SettingsManager.PREF_TREADMILL_MIN_SPEED_KPH,
            SettingsManager.DEFAULT_TREADMILL_MIN_SPEED_KPH.toFloat()).toDouble()
        val maxSpeedKph = prefs.getFloat(SettingsManager.PREF_TREADMILL_MAX_SPEED_KPH,
            SettingsManager.DEFAULT_TREADMILL_MAX_SPEED_KPH.toFloat()).toDouble()
        treadmillMinPaceSeconds = PaceConverter.speedToPaceSeconds(maxSpeedKph)
        treadmillMaxPaceSeconds = PaceConverter.speedToPaceSeconds(minSpeedKph)
        treadmillMinIncline = prefs.getFloat(SettingsManager.PREF_TREADMILL_MIN_INCLINE,
            SettingsManager.DEFAULT_TREADMILL_MIN_INCLINE.toFloat()).toDouble()
        treadmillMaxIncline = prefs.getFloat(SettingsManager.PREF_TREADMILL_MAX_INCLINE,
            SettingsManager.DEFAULT_TREADMILL_MAX_INCLINE.toFloat()).toDouble()
        treadmillInclineStep = prefs.getFloat(SettingsManager.PREF_TREADMILL_INCLINE_STEP,
            SettingsManager.DEFAULT_TREADMILL_INCLINE_STEP.toFloat()).toDouble()
    }

    private fun initViews() {
        // Left pane
        btnBack = findViewById(R.id.btnBack)
        findViewById<View>(R.id.tvBackLabel).setOnClickListener { navigateBack() }
        etSearch = findViewById(R.id.etSearch)
        rvWorkoutList = findViewById(R.id.rvWorkoutList)
        btnAddWorkout = findViewById(R.id.btnAddWorkout)

        // Right pane
        btnRun = findViewById(R.id.btnRun)
        etWorkoutName = findViewById(R.id.etWorkoutName)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        chartContainer = findViewById(R.id.chartContainer)
        rvSteps = findViewById(R.id.rvSteps)

        // Stats bar
        tvStats = findViewById(R.id.tvStats)
        spinnerAdjustmentScope = findViewById(R.id.spinnerAdjustmentScope)

        // Adjustment scope spinner
        val scopeLabels = arrayOf(
            getString(R.string.adjustment_scope_all_steps),
            getString(R.string.adjustment_scope_one_step)
        )
        spinnerAdjustmentScope.adapter = ArrayAdapter(
            this, R.layout.spinner_item, scopeLabels
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        // Create preview chart (reuses WorkoutChart from overlay)
        previewChart = WorkoutChart(this).apply {
            applyUserSettings(WorkoutChart.ChartUserSettings(
                lthrBpm = userLthrBpm,
                ftpWatts = userFtpWatts,
                hrZone2Start = kotlin.math.round(hrZone2StartPercent * userLthrBpm / 100.0).toInt(),
                hrZone3Start = kotlin.math.round(hrZone3StartPercent * userLthrBpm / 100.0).toInt(),
                hrZone4Start = kotlin.math.round(hrZone4StartPercent * userLthrBpm / 100.0).toInt(),
                hrZone5Start = kotlin.math.round(hrZone5StartPercent * userLthrBpm / 100.0).toInt(),
                powerZone2Start = kotlin.math.round(powerZone2StartPercent * userFtpWatts / 100.0).toInt(),
                powerZone3Start = kotlin.math.round(powerZone3StartPercent * userFtpWatts / 100.0).toInt(),
                powerZone4Start = kotlin.math.round(powerZone4StartPercent * userFtpWatts / 100.0).toInt(),
                powerZone5Start = kotlin.math.round(powerZone5StartPercent * userFtpWatts / 100.0).toInt()
                // hrMinBpm uses default - editor scales are now calculated from workout structure
            ))
        }
        chartContainer.addView(previewChart)
    }

    private fun setupAdapters() {
        // Workout list adapter
        workoutListAdapter = WorkoutListAdapter(
            onWorkoutClick = { workoutId -> viewModel.selectWorkout(workoutId) },
            onCopyClick = { workoutId -> viewModel.duplicateWorkout(workoutId) },
            onDeleteClick = { workoutId -> confirmDeleteWorkout(workoutId) }
        )
        rvWorkoutList.apply {
            layoutManager = LinearLayoutManager(this@WorkoutEditorActivityNew)
            adapter = workoutListAdapter
        }

        // Step adapter
        stepAdapter = InlineStepAdapter(
            onStepChanged = { index, step -> viewModel.updateStep(index, step) },
            onMoveUp = { index -> viewModel.moveStepUp(index) },
            onMoveDown = { index -> viewModel.moveStepDown(index) },
            onAddBelow = { index, _ -> showStepTypeSelector { selectedType ->
                viewModel.addStepBelow(index, selectedType)
            }},
            onDuplicate = { index -> viewModel.duplicateStep(index) },
            onDelete = { index -> viewModel.deleteStep(index) },
            onAddSubstep = { index, type -> viewModel.addSubstepToRepeat(index, type) },
            onAddStep = { _ -> showStepTypeSelector { type -> viewModel.addStep(type) }},
            onAddRepeat = { viewModel.addStep(StepType.REPEAT) },
            onWarmupToggled = { enabled -> viewModel.setWarmupEnabled(enabled) },
            onCooldownToggled = { enabled -> viewModel.setCooldownEnabled(enabled) }
        ).apply {
            hrZone2StartPercent = this@WorkoutEditorActivityNew.hrZone2StartPercent
            hrZone3StartPercent = this@WorkoutEditorActivityNew.hrZone3StartPercent
            hrZone4StartPercent = this@WorkoutEditorActivityNew.hrZone4StartPercent
            hrZone5StartPercent = this@WorkoutEditorActivityNew.hrZone5StartPercent
            powerZone2StartPercent = this@WorkoutEditorActivityNew.powerZone2StartPercent
            powerZone3StartPercent = this@WorkoutEditorActivityNew.powerZone3StartPercent
            powerZone4StartPercent = this@WorkoutEditorActivityNew.powerZone4StartPercent
            powerZone5StartPercent = this@WorkoutEditorActivityNew.powerZone5StartPercent
            userLthrBpm = this@WorkoutEditorActivityNew.userLthrBpm
            userFtpWatts = this@WorkoutEditorActivityNew.userFtpWatts
            // Treadmill capabilities from GlassOS
            treadmillMinPaceSeconds = this@WorkoutEditorActivityNew.treadmillMinPaceSeconds
            treadmillMaxPaceSeconds = this@WorkoutEditorActivityNew.treadmillMaxPaceSeconds
            treadmillMinIncline = this@WorkoutEditorActivityNew.treadmillMinIncline
            treadmillMaxIncline = this@WorkoutEditorActivityNew.treadmillMaxIncline
            treadmillInclineStep = this@WorkoutEditorActivityNew.treadmillInclineStep
        }
        rvSteps.apply {
            layoutManager = LinearLayoutManager(this@WorkoutEditorActivityNew)
            adapter = stepAdapter
        }
    }

    private fun setupListeners() {
        // Back button
        btnBack.setOnClickListener { navigateBack() }

        // Search
        etSearch.addTextChangedListener {
            viewModel.setSearchQuery(it?.toString() ?: "")
        }

        // Add workout
        btnAddWorkout.setOnClickListener {
            viewModel.createNewWorkout()
        }

        // Run button
        btnRun.setOnClickListener {
            launchWorkout()
        }

        // Workout name
        etWorkoutName.addTextChangedListener {
            val newName = it?.toString() ?: ""
            if (newName != viewModel.workoutName.value) {
                viewModel.setWorkoutName(newName)
            }
        }
        etWorkoutName.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                // Hide keyboard
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                // Clear focus
                view.clearFocus()
                true
            } else {
                false
            }
        }

        // Adjustment scope spinner
        spinnerAdjustmentScope.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val scope = if (position == 0) AdjustmentScope.ALL_STEPS else AdjustmentScope.ONE_STEP
                viewModel.setAdjustmentScope(scope)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Undo/Redo
        btnUndo.setOnClickListener { viewModel.undo() }
        btnRedo.setOnClickListener { viewModel.redo() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Filtered workouts
                launch {
                    viewModel.filteredWorkouts.collect { workouts ->
                        workoutListAdapter.submitList(workouts)
                    }
                }

                // Selected workout
                launch {
                    viewModel.selectedWorkoutId.collect { id ->
                        workoutListAdapter.setSelectedWorkoutId(id)
                    }
                }

                // Workout name
                launch {
                    viewModel.workoutName.collect { name ->
                        if (etWorkoutName.text.toString() != name) {
                            etWorkoutName.setText(name)
                        }
                    }
                }

                // Steps
                launch {
                    viewModel.steps.collect { steps ->
                        stepAdapter.submitList(steps)
                        previewChart?.setSteps(steps)
                    }
                }

                // Undo/Redo state
                launch {
                    viewModel.canUndo.collect { canUndo ->
                        btnUndo.isEnabled = canUndo
                        btnUndo.alpha = if (canUndo) 1f else 0.5f
                    }
                }
                launch {
                    viewModel.canRedo.collect { canRedo ->
                        btnRedo.isEnabled = canRedo
                        btnRedo.alpha = if (canRedo) 1f else 0.5f
                    }
                }

                // Summary
                launch {
                    viewModel.summary.collect { summary ->
                        updateSummary(summary)
                    }
                }

                // System workout restrictions
                launch {
                    viewModel.isSystemWorkout.collect { isSystem ->
                        // Disable rename for system workouts
                        etWorkoutName.isEnabled = !isSystem
                        etWorkoutName.alpha = if (isSystem) 0.6f else 1.0f
                        // Hide sentinels in system workout editor (no self-referencing)
                        stepAdapter.showSentinels = !isSystem
                        // Hide adjustment scope for system workouts (they're templates)
                        spinnerAdjustmentScope.visibility = if (isSystem) View.GONE else View.VISIBLE
                    }
                }

                // Adjustment scope
                launch {
                    viewModel.adjustmentScope.collect { scope ->
                        val position = if (scope == AdjustmentScope.ALL_STEPS) 0 else 1
                        if (spinnerAdjustmentScope.selectedItemPosition != position) {
                            spinnerAdjustmentScope.setSelection(position)
                        }
                    }
                }

                // Warmup/cooldown sentinel state
                launch {
                    viewModel.warmupEnabled.collect { enabled ->
                        stepAdapter.warmupEnabled = enabled
                    }
                }
                launch {
                    viewModel.cooldownEnabled.collect { enabled ->
                        stepAdapter.cooldownEnabled = enabled
                    }
                }
                launch {
                    viewModel.warmupSummary.collect { summary ->
                        stepAdapter.warmupSummary = summary
                    }
                }
                launch {
                    viewModel.cooldownSummary.collect { summary ->
                        stepAdapter.cooldownSummary = summary
                    }
                }
            }
        }
    }

    private fun updateSummary(summary: WorkoutEditorViewModel.WorkoutSummary) {
        tvStats.text = PaceConverter.formatWorkoutStats(
            stepCount = summary.stepCount,
            distanceMeters = summary.estimatedDistanceMeters,
            durationSeconds = summary.estimatedDurationSeconds,
            tss = summary.estimatedTss
        )
    }

    private fun showStepTypeSelector(onTypeSelected: (StepType) -> Unit) {
        val stepTypes = arrayOf(
            getString(R.string.step_type_warmup),
            getString(R.string.step_type_run),
            getString(R.string.step_type_recover),
            getString(R.string.step_type_rest),
            getString(R.string.step_type_cooldown)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.step_editor_type_label)
            .setItems(stepTypes) { _, which ->
                val type = when (which) {
                    0 -> StepType.WARMUP
                    1 -> StepType.RUN
                    2 -> StepType.RECOVER
                    3 -> StepType.REST
                    4 -> StepType.COOLDOWN
                    else -> StepType.RUN
                }
                onTypeSelected(type)
            }
            .show()
    }

    private fun confirmDeleteWorkout(workoutId: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_workout_title)
            .setMessage(R.string.editor_unsaved_changes_message)  // Reusing message
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteWorkout(workoutId)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun launchWorkout() {
        val workoutId = viewModel.selectedWorkoutId.value
        if (workoutId <= 0) {
            Toast.makeText(this, getString(R.string.toast_no_workout_selected), Toast.LENGTH_SHORT).show()
            return
        }

        // Send intent to HUDService to load the workout (user will press physical Start to begin)
        val intent = Intent(this, HUDService::class.java).apply {
            action = HUDService.ACTION_LOAD_WORKOUT
            putExtra(HUDService.EXTRA_WORKOUT_ID, workoutId)
        }
        startService(intent)

        // Close the editor
        finish()
    }

    private fun navigateBack() {
        // Just finish - onPause will restore panels, onDestroy will clear saved state
        finish()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        navigateBack()
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemWorkoutSummaries()
        HUDService.notifyActivityForeground(this)
    }

    override fun onPause() {
        super.onPause()
        HUDService.notifyActivityBackground(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        HUDService.notifyActivityClosed(this)
    }
}
