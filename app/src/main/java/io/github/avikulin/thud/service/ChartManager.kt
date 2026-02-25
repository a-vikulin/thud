package io.github.avikulin.thud.service

import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.github.avikulin.thud.R
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import io.github.avikulin.thud.ui.components.WorkoutChart

/**
 * Manages the workout chart overlay lifecycle and updates.
 */
class ChartManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val state: ServiceStateHolder,
    private val workoutRecorder: WorkoutRecorder
) {
    companion object {
        private const val TAG = "ChartManager"
        private const val CHART_UPDATE_INTERVAL_MS = 1000L
        private const val PREF_SHOW_SPEED = "chart_show_speed"
        private const val PREF_SHOW_INCLINE = "chart_show_incline"
        private const val PREF_SHOW_HR = "chart_show_hr"
        private const val PREF_SHOW_POWER = "chart_show_power"
        private const val PREF_CHART_ZOOM_MODE = "chart_zoom_mode"
    }

    private val prefs: SharedPreferences = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)

    private var containerView: LinearLayout? = null
    private var chartView: WorkoutChart? = null
    private var toggleSpeedBtn: TextView? = null
    private var toggleInclineBtn: TextView? = null
    private var toggleHrBtn: TextView? = null
    private var togglePowerBtn: TextView? = null
    private var toggleFullScaleBtn: TextView? = null
    private val chartUpdateHandler = Handler(Looper.getMainLooper())
    private var chartTimerActive = false
    private val chartUpdateRunnable = object : Runnable {
        override fun run() {
            updateChart()
            if (state.isChartVisible.get()) {
                chartUpdateHandler.postDelayed(this, CHART_UPDATE_INTERVAL_MS)
            } else {
                chartTimerActive = false
                Log.d(TAG, "Chart update timer stopped - chart no longer visible")
            }
        }
    }

    // Visibility states (persisted to SharedPreferences)
    private var showSpeed = true
    private var showIncline = true
    private var showHr = true
    private var showPower = true
    private var zoomMode = WorkoutChart.ChartZoomMode.TIMEFRAME

    // For detecting phase transitions (MAIN→COOLDOWN auto-switches zoom from MAIN_PHASE)
    private var lastStepIndex = -1

    // Cached drawables for zoom mode icons
    private var zoomTimeframeDrawable: android.graphics.drawable.Drawable? = null
    private var zoomMainPhaseDrawable: android.graphics.drawable.Drawable? = null
    private var zoomFullDrawable: android.graphics.drawable.Drawable? = null
    private var zoomIconSize = 0
    private var toggleButtonHeight = 0

    // Cached toggle button colors (static + per-series)
    private val colorToggleActiveStroke = ContextCompat.getColor(service, R.color.chart_toggle_active_stroke)
    private val colorToggleInactiveText = ContextCompat.getColor(service, R.color.chart_toggle_inactive_text)
    private val colorToggleInactiveBg = ContextCompat.getColor(service, R.color.chart_toggle_inactive_background)
    private val colorToggleInactiveStroke = ContextCompat.getColor(service, R.color.chart_toggle_inactive_stroke)
    private val resolvedColorCache = HashMap<Int, Int>(12)

    val isVisible: Boolean
        get() = state.isChartVisible.get()

    /**
     * Show the chart overlay.
     * Re-uses existing view if available to preserve data.
     */
    fun showChart() {
        if (state.isChartVisible.get()) return

        // Set flag immediately to prevent duplicate calls
        state.isChartVisible.set(true)

        // Load visibility preferences
        loadVisibilityPreferences()

        val resources = service.resources
        val chartWidthFraction = resources.getFloat(R.dimen.chart_width_fraction)
        val chartHeightFraction = resources.getFloat(R.dimen.chart_height_fraction)
        val totalWidth = OverlayHelper.calculateWidth(state.screenWidth, chartWidthFraction)
        val chartHeight = OverlayHelper.calculateHeight(state.screenHeight, chartHeightFraction)
        val toggleButtonWidth = resources.getDimensionPixelSize(R.dimen.chart_toggle_button_width)

        val chartParams = OverlayHelper.createOverlayParams(
            width = totalWidth,
            height = chartHeight,
            gravity = Gravity.BOTTOM or Gravity.START
        )

        // Create or re-use container
        val container = containerView ?: createChartContainer(
            totalWidth,
            chartHeight,
            toggleButtonWidth
        ).also { containerView = it }

        windowManager.addView(container, chartParams)

        // Apply visibility states to chart
        applyVisibilityToChart()

        // Start periodic chart updates
        scheduleChartUpdate()
        Log.d(TAG, "Chart shown")
    }

    /**
     * Create the chart container with toggle buttons and chart.
     */
    private fun createChartContainer(
        totalWidth: Int,
        chartHeight: Int,
        toggleWidth: Int
    ): LinearLayout {
        val context = service

        // Main horizontal container
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(totalWidth, chartHeight)
        }

        // Left side: vertical toggle buttons
        val toggleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(toggleWidth, chartHeight)
        }

        // Create 5 toggle buttons with symbols
        // Account for margins (2dp each side × 5 buttons = 20dp total)
        val buttonMargin = context.resources.getDimensionPixelSize(R.dimen.chart_toggle_button_margin)
        val marginPerButton = buttonMargin * 2  // top + bottom
        val availableHeight = chartHeight - (marginPerButton * 5)
        val buttonHeight = availableHeight / 5

        val baseTextSizePx = context.resources.getDimension(R.dimen.chart_toggle_button_text_size)
        val iconSize = (baseTextSizePx * 1.28f).toInt()
        toggleButtonHeight = buttonHeight

        // Speed: runner drawable (tinted to speed color)
        toggleSpeedBtn = createToggleButton(context, "", R.color.chart_speed, R.color.chart_speed_dim, buttonHeight, showSpeed) {
            showSpeed = !showSpeed
            saveVisibilityPreferences()
            applyVisibilityToChart()
            updateToggleButtonState(toggleSpeedBtn!!, showSpeed, R.color.chart_speed, R.color.chart_speed_dim)
        }
        setToggleDrawableIcon(toggleSpeedBtn!!, R.drawable.ic_runner, iconSize, buttonHeight, showSpeed, R.color.chart_speed)
        toggleContainer.addView(toggleSpeedBtn)

        // Incline: mountains drawable (tinted to incline color)
        toggleInclineBtn = createToggleButton(context, "", R.color.chart_incline, R.color.chart_incline_dim, buttonHeight, showIncline) {
            showIncline = !showIncline
            saveVisibilityPreferences()
            applyVisibilityToChart()
            updateToggleButtonState(toggleInclineBtn!!, showIncline, R.color.chart_incline, R.color.chart_incline_dim)
        }
        setToggleDrawableIcon(toggleInclineBtn!!, R.drawable.ic_mountains, iconSize, buttonHeight, showIncline, R.color.chart_incline)
        toggleContainer.addView(toggleInclineBtn)

        // HR: heart pulse drawable (tinted to HR color)
        toggleHrBtn = createToggleButton(context, "", R.color.chart_hr, R.color.chart_hr_dim, buttonHeight, showHr) {
            showHr = !showHr
            saveVisibilityPreferences()
            applyVisibilityToChart()
            updateToggleButtonState(toggleHrBtn!!, showHr, R.color.chart_hr, R.color.chart_hr_dim)
        }
        setToggleDrawableIcon(toggleHrBtn!!, R.drawable.ic_heart_pulse, iconSize, buttonHeight, showHr, R.color.chart_hr)
        toggleContainer.addView(toggleHrBtn)

        // Power: lightning bolt drawable (tinted to power color)
        togglePowerBtn = createToggleButton(context, "", R.color.chart_power, R.color.chart_power_dim, buttonHeight, showPower) {
            showPower = !showPower
            saveVisibilityPreferences()
            applyVisibilityToChart()
            updateToggleButtonState(togglePowerBtn!!, showPower, R.color.chart_power, R.color.chart_power_dim)
        }
        setToggleDrawableIcon(togglePowerBtn!!, R.drawable.ic_lightning, iconSize, buttonHeight, showPower, R.color.chart_power)
        toggleContainer.addView(togglePowerBtn)

        // Zoom mode: cycles TIMEFRAME(stopwatch) → MAIN_PHASE(bar chart) → FULL(⇕), always chart_fit_dim background
        zoomTimeframeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_stopwatch)
        zoomMainPhaseDrawable = ContextCompat.getDrawable(context, R.drawable.ic_chart_bar)
        zoomFullDrawable = ContextCompat.getDrawable(context, R.drawable.ic_zoom_vertical)
        zoomIconSize = iconSize
        toggleFullScaleBtn = createToggleButton(context, "", R.color.chart_fit, R.color.chart_fit_dim, buttonHeight, true) {
            zoomMode = chartView?.cycleZoomMode() ?: zoomMode
            saveVisibilityPreferences()
            updateZoomButtonAppearance()
        }
        updateZoomButtonAppearance()
        toggleContainer.addView(toggleFullScaleBtn)

        container.addView(toggleContainer)

        // Right side: chart
        val chart = chartView ?: WorkoutChart(context).apply {
            applyUserSettings(WorkoutChart.ChartUserSettings(
                lthrBpm = state.userLthrBpm,
                ftpWatts = state.userFtpWatts,
                hrZone2Start = state.hrZone2Start,
                hrZone3Start = state.hrZone3Start,
                hrZone4Start = state.hrZone4Start,
                hrZone5Start = state.hrZone5Start,
                powerZone2Start = state.powerZone2Start,
                powerZone3Start = state.powerZone3Start,
                powerZone4Start = state.powerZone4Start,
                powerZone5Start = state.powerZone5Start
                // hrMinBpm uses default (60 BPM) for live chart - needs wide range
            ))
            setZoomMode(zoomMode)
            setZoomTimeframeMinutes(state.chartZoomTimeframeMinutes)
        }.also { chartView = it }

        val chartParams = LinearLayout.LayoutParams(
            totalWidth - toggleWidth,
            chartHeight
        )
        chart.layoutParams = chartParams
        container.addView(chart)

        return container
    }

    /**
     * Create a toggle button for line visibility.
     */
    private fun createToggleButton(
        context: Context,
        symbol: String,
        colorResId: Int,
        dimColorResId: Int,
        height: Int,
        initialState: Boolean,
        onClick: () -> Unit
    ): TextView {
        val resources = context.resources
        val margin = resources.getDimensionPixelSize(R.dimen.chart_toggle_button_margin)
        val textSizePx = resources.getDimension(R.dimen.chart_toggle_button_text_size)
        return TextView(context).apply {
            text = symbol
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
            params.setMargins(margin, margin, margin, margin)
            layoutParams = params
            // Make it look like a button with rounded corners
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
            updateToggleButtonState(this, initialState, colorResId, dimColorResId)
        }
    }

    /**
     * Update toggle button visual state.
     * Uses bright color for symbol, dim color for background when active.
     */
    private fun updateToggleButtonState(button: TextView, isActive: Boolean, colorResId: Int, dimColorResId: Int) {
        val resources = service.resources
        val color = resolvedColorCache.getOrPut(colorResId) { ContextCompat.getColor(service, colorResId) }
        val dimColor = resolvedColorCache.getOrPut(dimColorResId) { ContextCompat.getColor(service, dimColorResId) }
        val cornerRadius = resources.getDimension(R.dimen.chart_toggle_button_corner_radius)
        val strokeWidth = resources.getDimensionPixelSize(R.dimen.chart_toggle_button_stroke_width)

        if (isActive) {
            button.setTextColor(color)
            button.compoundDrawables.forEach { d -> d?.let { DrawableCompat.setTint(it, color) } }
            button.background = createRoundedDrawable(dimColor, cornerRadius, colorToggleActiveStroke, strokeWidth)
            button.alpha = 1.0f
        } else {
            button.setTextColor(colorToggleInactiveText)
            button.compoundDrawables.forEach { d -> d?.let { DrawableCompat.setTint(it, colorToggleInactiveText) } }
            button.background = createRoundedDrawable(colorToggleInactiveBg, cornerRadius, colorToggleInactiveStroke, strokeWidth)
            button.alpha = 0.7f
        }
    }

    /**
     * Set a vector drawable icon on a toggle button, centered vertically and tinted to match state.
     * Must be called AFTER createToggleButton (which sets the initial toggle state colors).
     */
    private fun setToggleDrawableIcon(
        button: TextView,
        drawableResId: Int,
        iconSize: Int,
        buttonHeight: Int,
        isActive: Boolean,
        colorResId: Int
    ) {
        val drawable = ContextCompat.getDrawable(service, drawableResId)?.mutate() ?: return
        drawable.setBounds(0, 0, iconSize, iconSize)
        // Tint to match current toggle state (fixes white-on-first-draw)
        val tintColor = if (isActive) {
            resolvedColorCache.getOrPut(colorResId) { ContextCompat.getColor(service, colorResId) }
        } else {
            colorToggleInactiveText
        }
        DrawableCompat.setTint(drawable, tintColor)
        button.text = ""
        button.setCompoundDrawables(null, drawable, null, null)
        button.compoundDrawablePadding = 0
        // Center vertically: pad top so drawable sits in the middle
        val topPad = (buttonHeight - iconSize) / 2
        button.setPadding(0, topPad, 0, 0)
    }

    /**
     * Create a rounded rectangle drawable for button background.
     */
    private fun createRoundedDrawable(color: Int, cornerRadius: Float, strokeColor: Int, strokeWidth: Int): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            setCornerRadius(cornerRadius)
            setStroke(strokeWidth, strokeColor)
        }
    }

    /**
     * Apply visibility states to chart.
     */
    private fun applyVisibilityToChart() {
        chartView?.apply {
            showSpeedLine = showSpeed
            showInclineLine = showIncline
            showHrLine = showHr
            showPowerLine = showPower
            setZoomMode(zoomMode)
            setZoomTimeframeMinutes(state.chartZoomTimeframeMinutes)
            invalidate()
        }
    }

    /**
     * Load visibility preferences from SharedPreferences.
     */
    private fun loadVisibilityPreferences() {
        showSpeed = prefs.getBoolean(PREF_SHOW_SPEED, true)
        showIncline = prefs.getBoolean(PREF_SHOW_INCLINE, true)
        showHr = prefs.getBoolean(PREF_SHOW_HR, true)
        showPower = prefs.getBoolean(PREF_SHOW_POWER, true)
        val zoomModeOrdinal = prefs.getInt(PREF_CHART_ZOOM_MODE, WorkoutChart.ChartZoomMode.TIMEFRAME.ordinal)
        zoomMode = WorkoutChart.ChartZoomMode.entries.getOrElse(zoomModeOrdinal) { WorkoutChart.ChartZoomMode.TIMEFRAME }
    }

    /**
     * Save visibility preferences to SharedPreferences.
     */
    private fun saveVisibilityPreferences() {
        prefs.edit()
            .putBoolean(PREF_SHOW_SPEED, showSpeed)
            .putBoolean(PREF_SHOW_INCLINE, showIncline)
            .putBoolean(PREF_SHOW_HR, showHr)
            .putBoolean(PREF_SHOW_POWER, showPower)
            .putInt(PREF_CHART_ZOOM_MODE, zoomMode.ordinal)
            .apply()
    }

    /** Update zoom button drawable icon for the current mode. */
    private fun updateZoomButtonAppearance() {
        val btn = toggleFullScaleBtn ?: return
        val sourceDrawable = when (zoomMode) {
            WorkoutChart.ChartZoomMode.TIMEFRAME -> zoomTimeframeDrawable
            WorkoutChart.ChartZoomMode.MAIN_PHASE -> zoomMainPhaseDrawable
            WorkoutChart.ChartZoomMode.FULL -> zoomFullDrawable
        } ?: return
        btn.text = ""
        val drawable = sourceDrawable.mutate()
        drawable.setBounds(0, 0, zoomIconSize, zoomIconSize)
        DrawableCompat.setTint(drawable, btn.currentTextColor)
        btn.setCompoundDrawables(null, drawable, null, null)
        btn.compoundDrawablePadding = 0
        val topPad = (toggleButtonHeight - zoomIconSize) / 2
        btn.setPadding(0, topPad, 0, 0)
    }

    /**
     * Update the zoom timeframe from settings.
     */
    fun updateZoomTimeframe(minutes: Int) {
        chartView?.setZoomTimeframeMinutes(minutes)
    }

    /**
     * Hide the chart overlay.
     * Removes from window manager but keeps the view so data is preserved.
     */
    fun hideChart() {
        if (!state.isChartVisible.get() && containerView == null) return

        // Set flag immediately to prevent race conditions
        state.isChartVisible.set(false)

        // Stop chart updates
        chartUpdateHandler.removeCallbacks(chartUpdateRunnable)
        chartTimerActive = false

        // Remove from window manager but keep view reference (preserves data)
        containerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing chart: ${e.message}")
            }
        }
        Log.d(TAG, "Chart hidden")
    }

    /**
     * Toggle chart visibility.
     */
    fun toggleChart() {
        if (state.isChartVisible.get()) {
            hideChart()
        } else {
            showChart()
        }
    }

    /**
     * Schedule periodic chart updates.
     */
    private fun scheduleChartUpdate() {
        chartTimerActive = true
        chartUpdateHandler.postDelayed(chartUpdateRunnable, CHART_UPDATE_INTERVAL_MS)
    }

    /**
     * Ensure the refresh timer is running. Called from telemetry updates
     * to self-heal if the timer stopped unexpectedly.
     */
    fun ensureRefreshRunning() {
        if (!chartTimerActive && state.isChartVisible.get() && containerView != null) {
            Log.d(TAG, "Restarting chart refresh timer (self-heal)")
            scheduleChartUpdate()
        }
    }

    /**
     * Update chart with current workout data.
     * Chart only reads data - recording is done by HUDService.onTelemetryUpdated()
     * which has access to proper step index from the workout engine.
     */
    fun updateChart() {
        chartView?.setData(workoutRecorder.getWorkoutData())
    }

    /**
     * Clear chart paths and feed fresh data, forcing a full rebuild.
     * Use after data point values change retroactively (e.g., HR sensor rebinding).
     */
    fun forceFullUpdate() {
        chartView?.clearData()
        updateChart()
    }

    /**
     * Update chart with new HR zone settings.
     */
    fun updateHRZones() {
        chartView?.setHRZones(state.hrZone2Start, state.hrZone3Start, state.hrZone4Start, state.hrZone5Start)
    }

    /**
     * Update chart with new Power zone settings.
     */
    fun updatePowerZones() {
        chartView?.setPowerZones(
            state.powerZone2Start,
            state.powerZone3Start,
            state.powerZone4Start,
            state.powerZone5Start
        )
    }

    /**
     * Update chart threshold values (LTHR, FTP).
     * Should be called when user settings change.
     */
    fun updateThresholds() {
        chartView?.apply {
            lthrBpm = state.userLthrBpm
            ftpWatts = state.userFtpWatts
        }
    }

    /**
     * Set planned workout segments on the chart.
     * Posts to main thread for thread safety.
     */
    fun setPlannedSegments(segments: List<WorkoutChart.PlannedSegment>) {
        chartUpdateHandler.post {
            chartView?.setPlannedSegments(segments)
        }
    }

    /**
     * Clear planned segments from the chart.
     * Posts to main thread for thread safety.
     */
    fun clearPlannedSegments() {
        chartUpdateHandler.post {
            lastStepIndex = -1
            chartView?.clearPlannedSegments()
            // MAIN_PHASE is meaningless without segments — downgrade to TIMEFRAME
            if (zoomMode == WorkoutChart.ChartZoomMode.MAIN_PHASE) {
                zoomMode = WorkoutChart.ChartZoomMode.TIMEFRAME
                chartView?.setZoomMode(zoomMode)
                updateZoomButtonAppearance()
                saveVisibilityPreferences()
            }
        }
    }

    /**
     * Update adjustment coefficients for drawing future step targets.
     * Past steps are not drawn since actual data covers them.
     * Current and future steps are drawn with coefficient applied and positioned
     * relative to actual execution time (handles overrun/underrun from Prev/Next).
     * Posts to main thread for thread safety.
     *
     * @param currentStepIndex Which step we're currently on
     * @param speedCoeff Speed adjustment coefficient
     * @param inclineCoeff Incline adjustment coefficient
     * @param stepElapsedMs How long we've been in the current step (for positioning future segments)
     * @param workoutElapsedMs Total workout elapsed time from engine (for accurate timing)
     */
    fun setAdjustmentCoefficients(
        currentStepIndex: Int,
        speedCoeff: Double,
        inclineCoeff: Double,
        stepElapsedMs: Long = 0,
        workoutElapsedMs: Long = 0,
        perStepCoefficients: Map<String, Pair<Double, Double>>? = null
    ) {
        chartUpdateHandler.post {
            val chart = chartView ?: return@post

            // Auto-switch from MAIN_PHASE to TIMEFRAME when entering cooldown
            if (zoomMode == WorkoutChart.ChartZoomMode.MAIN_PHASE && currentStepIndex != lastStepIndex) {
                val prevPhase = chart.getStepPhase(lastStepIndex)
                val newPhase = chart.getStepPhase(currentStepIndex)
                if (prevPhase == WorkoutChart.WorkoutPhase.MAIN && newPhase == WorkoutChart.WorkoutPhase.COOLDOWN) {
                    zoomMode = WorkoutChart.ChartZoomMode.TIMEFRAME
                    chart.setZoomMode(zoomMode)
                    saveVisibilityPreferences()
                    updateZoomButtonAppearance()
                    Log.d(TAG, "Auto-switched zoom from MAIN_PHASE to TIMEFRAME on cooldown entry")
                }
            }
            lastStepIndex = currentStepIndex

            chart.setAdjustmentCoefficients(currentStepIndex, speedCoeff, inclineCoeff, stepElapsedMs, workoutElapsedMs, perStepCoefficients)
        }
    }

    /**
     * Clear all chart data.
     * Posts to main thread for thread safety.
     */
    fun clearData() {
        chartUpdateHandler.post {
            chartView?.clearData()
        }
    }

    /**
     * Set chart data directly.
     * Posts to main thread for thread safety.
     */
    fun setData(data: List<WorkoutDataPoint>) {
        chartUpdateHandler.post {
            chartView?.setData(data)
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        chartUpdateHandler.removeCallbacks(chartUpdateRunnable)
        chartTimerActive = false

        containerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing chart: ${e.message}")
            }
        }
        containerView = null
        chartView = null
        toggleSpeedBtn = null
        toggleInclineBtn = null
        toggleHrBtn = null
        togglePowerBtn = null
        toggleFullScaleBtn = null
        state.isChartVisible.set(false)
    }
}
