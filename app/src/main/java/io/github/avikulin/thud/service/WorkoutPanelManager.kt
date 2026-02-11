package io.github.avikulin.thud.service

import android.app.Service
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import io.github.avikulin.thud.R
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.domain.engine.ExecutionStep
import io.github.avikulin.thud.domain.engine.WorkoutExecutionState
import io.github.avikulin.thud.ui.panel.WorkoutPanelView

/**
 * Manages the workout panel overlay lifecycle and updates.
 */
class WorkoutPanelManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val state: ServiceStateHolder
) {
    companion object {
        private const val TAG = "WorkoutPanelManager"
        private const val REFRESH_INTERVAL_MS = 500L
    }

    /**
     * Callback interface for workout panel control events.
     */
    interface Listener {
        fun onNextStepClicked()
        fun onPrevStepClicked()
        fun onResetToStepClicked()
    }

    var listener: Listener? = null

    private var workoutPanelView: WorkoutPanelView? = null
    private var refreshTimerActive = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            getWorkoutState?.invoke()?.let { state ->
                workoutPanelView?.updateState(state)
            }
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    // Callback to get current workout state for refresh
    var getWorkoutState: (() -> WorkoutExecutionState?)? = null

    val isVisible: Boolean
        get() = state.isWorkoutPanelVisible.get()

    /**
     * Show the workout panel overlay.
     * Re-uses existing view if available to preserve data.
     */
    fun showPanel() {
        if (state.isWorkoutPanelVisible.get()) return

        val resources = service.resources
        val panelWidthFraction = resources.getFloat(R.dimen.workout_panel_width_fraction)
        val panelHeightFraction = resources.getFloat(R.dimen.workout_panel_height_fraction)
        val panelTopOffsetFraction = resources.getFloat(R.dimen.workout_panel_top_offset_fraction)

        val panelWidth = OverlayHelper.calculateWidth(state.screenWidth, panelWidthFraction)
        val panelHeight = OverlayHelper.calculateHeight(state.screenHeight, panelHeightFraction)
        val panelYOffset = OverlayHelper.calculateHeight(state.screenHeight, panelTopOffsetFraction)
        val panelParams = OverlayHelper.createOverlayParams(
            width = panelWidth,
            height = panelHeight,
            gravity = Gravity.START or Gravity.TOP,
            y = panelYOffset
        )

        // Re-use existing view if available, otherwise create new one
        val view = workoutPanelView ?: WorkoutPanelView(service).apply {
            // Set threshold values for HR display
            userLthrBpm = state.userLthrBpm
            // Set up control callbacks
            onNextStepClicked = { listener?.onNextStepClicked() }
            onPrevStepClicked = { listener?.onPrevStepClicked() }
            onResetToStepClicked = { listener?.onResetToStepClicked() }
        }.also { workoutPanelView = it }

        windowManager.addView(view, panelParams)
        state.isWorkoutPanelVisible.set(true)
        Log.d(TAG, "Workout panel shown")
    }

    /**
     * Hide the workout panel overlay.
     * Removes from window manager but keeps the view so data is preserved.
     */
    fun hidePanel() {
        if (!state.isWorkoutPanelVisible.get()) return

        // Stop refresh timer
        stopRefresh()

        // Remove from window manager but keep view reference (preserves data)
        workoutPanelView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing workout panel: ${e.message}")
            }
        }
        state.isWorkoutPanelVisible.set(false)
        Log.d(TAG, "Workout panel hidden")
    }

    /**
     * Update the panel with workout info.
     */
    fun setWorkoutInfo(workout: Workout, steps: List<ExecutionStep>, phaseCounts: Triple<Int, Int, Int>? = null) {
        workoutPanelView?.apply {
            userLthrBpm = state.userLthrBpm
            setWorkoutName(workout.name)
            setSteps(steps, phaseCounts)
        }
    }

    /**
     * Update the panel with workout name and steps.
     */
    fun setWorkoutName(name: String) {
        workoutPanelView?.setWorkoutName(name)
    }

    /**
     * Update the panel steps.
     */
    fun setSteps(steps: List<ExecutionStep>, phaseCounts: Triple<Int, Int, Int>? = null) {
        workoutPanelView?.setSteps(steps, phaseCounts)
    }

    /**
     * Update panel state.
     */
    fun updateState(executionState: WorkoutExecutionState) {
        workoutPanelView?.updateState(executionState)
    }

    /**
     * Start periodic panel refresh.
     */
    fun startRefresh() {
        if (refreshTimerActive) return
        refreshTimerActive = true
        refreshHandler.post(refreshRunnable)
        Log.d(TAG, "Started workout panel refresh timer")
    }

    /**
     * Stop periodic panel refresh.
     */
    fun stopRefresh() {
        if (!refreshTimerActive) return
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshTimerActive = false
        Log.d(TAG, "Stopped workout panel refresh timer")
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        stopRefresh()
        refreshHandler.removeCallbacksAndMessages(null)

        workoutPanelView?.let {
            workoutPanelView = null
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing workout panel: ${e.message}")
            }
        }
        state.isWorkoutPanelVisible.set(false)
    }
}
