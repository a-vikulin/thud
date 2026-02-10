package io.github.avikulin.thud.service

import android.app.Service
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R
import io.github.avikulin.thud.util.PaceConverter
import com.ifit.glassos.workout.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Manages pace and incline adjustment popup UI.
 */
class PopupManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val state: ServiceStateHolder,
    private val getGlassOsClient: () -> GlassOsClient?,
    private val setTreadmillSpeed: (adjustedKph: Double) -> Boolean,
    private val setTreadmillIncline: (percent: Double) -> Boolean,
    private val isWorkoutLoadedAndIdle: () -> Boolean = { false },
    private val onPaceSelectedWithWorkoutLoaded: (adjustedKph: Double) -> Unit = {},
    private val isStructuredWorkoutPaused: () -> Boolean = { false },
    private val onResumeWorkoutAtStepPace: () -> Unit = {}
) {
    companion object {
        private const val TAG = "PopupManager"
        private const val INCLINE_GRID_COLUMNS = 6
    }

    private var pacePopupView: View? = null
    private var inclinePopupView: View? = null

    val isPacePopupVisible: Boolean
        get() = pacePopupView != null

    val isInclinePopupVisible: Boolean
        get() = inclinePopupView != null

    // ==================== Pace Popup ====================

    fun togglePacePopup() {
        if (pacePopupView != null) {
            closePacePopup()
        } else {
            closeInclinePopup()
            showPacePopup()
        }
    }

    fun showPacePopup() {
        val client = getGlassOsClient()
        val resources = service.resources

        // Get treadmill range and convert to adjusted (perceived) range
        val rawMinKph = client?.minSpeedKph ?: 1.6
        val rawMaxKph = client?.maxSpeedKph ?: 20.0
        val treadmillMinKph = if (rawMinKph > 0) max(rawMinKph, 1.6) else 1.6
        val treadmillMaxKph = if (rawMaxKph > treadmillMinKph) rawMaxKph else 20.0
        val adjustedMinKph = treadmillMinKph * state.paceCoefficient
        val adjustedMaxKph = treadmillMaxKph * state.paceCoefficient

        // Generate ADJUSTED speeds at 0.5 kph steps
        var adjustedSpeeds = ServiceStateHolder.generateSpeedValues(adjustedMinKph, adjustedMaxKph)
        if (adjustedSpeeds.isEmpty()) {
            Log.w(TAG, "Pace popup: generated empty speeds from $adjustedMinKph-$adjustedMaxKph, using defaults")
            adjustedSpeeds = ServiceStateHolder.generateSpeedValues(1.6, 20.0)
        }

        // Check if a structured workout is paused - add resume button as first item if so
        val showResumeButton = isStructuredWorkoutPaused()

        // Total items in grid (resume button + speed buttons if paused, just speeds otherwise)
        val itemCount = if (showResumeButton) adjustedSpeeds.size + 1 else adjustedSpeeds.size

        // Calculate dynamic grid: columns >= rows
        val gridRows = floor(sqrt(itemCount.toDouble())).toInt().coerceAtLeast(1)
        val gridColumns = ceil(itemCount.toDouble() / gridRows).toInt().coerceAtLeast(1)

        Log.d(TAG, "Pace popup: treadmill=${treadmillMinKph}-${treadmillMaxKph}, adjusted=${adjustedMinKph}-${adjustedMaxKph}, coef=${state.paceCoefficient}")
        Log.d(TAG, "Pace popup: $itemCount items (${adjustedSpeeds.size} speeds + ${if (showResumeButton) "resume" else "no resume"}), grid=${gridColumns}x${gridRows}")

        val popupWidthFraction = resources.getFloat(R.dimen.popup_width_fraction)
        val popupHeightFraction = resources.getFloat(R.dimen.popup_height_fraction)
        val popupWidth = (state.screenWidth * popupWidthFraction).toInt()
        val popupHeight = (state.screenHeight * popupHeightFraction).toInt()
        val popupPadding = resources.getDimensionPixelSize(R.dimen.popup_padding)
        val buttonMargin = resources.getDimensionPixelSize(R.dimen.popup_button_margin)
        val buttonTextSize = resources.getDimension(R.dimen.text_popup_button) / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

        // Colors from resources
        val popupBgColor = ContextCompat.getColor(service, R.color.popup_background)
        val buttonTextColor = ContextCompat.getColor(service, R.color.popup_button_text)
        val buttonActiveColor = ContextCompat.getColor(service, R.color.popup_button_active)
        val buttonInactiveColor = ContextCompat.getColor(service, R.color.popup_button_inactive)
        val resumeButtonColor = ContextCompat.getColor(service, R.color.hr_zone_3)  // Green color

        // Current adjusted speed rounded to nearest 0.5 for highlighting
        val currentAdjustedKph = state.currentSpeedKph * state.paceCoefficient
        val roundedCurrentAdjustedKph = round(currentAdjustedKph * 2) / 2

        // Create popup container with dynamic grid size
        val container = GridLayout(service).apply {
            columnCount = gridColumns
            rowCount = gridRows
            alignmentMode = GridLayout.ALIGN_BOUNDS  // Align by cell bounds, not text baseline
            setBackgroundColor(popupBgColor)
            setPadding(popupPadding, popupPadding, popupPadding, popupPadding)
        }

        // Calculate button size based on dynamic grid dimensions
        // Each button has margins on all sides, so total margin per row/column = gridRows/Columns * 2 * buttonMargin
        val buttonWidth = (popupWidth - popupPadding * 2 - gridColumns * buttonMargin * 2) / gridColumns
        val buttonHeight = (popupHeight - popupPadding * 2 - gridRows * buttonMargin * 2) / gridRows

        // If workout is paused, add resume button as first item in grid
        if (showResumeButton) {
            val density = resources.displayMetrics.density
            val playButtonBottomPadding = (8 * density).toInt()  // Nudge symbol up
            val resumeButton = TextView(service).apply {
                text = service.getString(R.string.btn_play)
                textSize = buttonTextSize * 3
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                setTextColor(buttonTextColor)
                setBackgroundColor(resumeButtonColor)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 0, 0, playButtonBottomPadding)
                isClickable = true
                isFocusable = true

                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0  // Let GridLayout calculate based on weight
                    height = 0
                    setMargins(buttonMargin, buttonMargin, buttonMargin, buttonMargin)
                }

                setOnClickListener {
                    Log.d(TAG, "Resume workout button clicked")
                    closePacePopup()
                    onResumeWorkoutAtStepPace()
                }
            }
            container.addView(resumeButton)
        }

        // Create buttons showing all adjusted speeds
        for (adjustedKph in adjustedSpeeds) {
            val paceStr = PaceConverter.formatPaceFromSpeed(adjustedKph)
            val speedStr = if (adjustedKph == adjustedKph.toLong().toDouble()) {
                "${adjustedKph.toLong()}"
            } else {
                String.format(Locale.US, "%.1f", adjustedKph)
            }
            // Create spannable with second line at 70% size
            val fullText = service.getString(R.string.pace_button_format, paceStr, speedStr)
            val newlineIndex = fullText.indexOf('\n')
            val spannable = SpannableString(fullText)
            if (newlineIndex >= 0) {
                spannable.setSpan(
                    RelativeSizeSpan(0.7f),
                    newlineIndex + 1,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val button = TextView(service).apply {
                text = spannable
                textSize = buttonTextSize
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                setTextColor(buttonTextColor)
                setBackgroundColor(
                    if (abs(roundedCurrentAdjustedKph - adjustedKph) < 0.01) buttonActiveColor else buttonInactiveColor
                )
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true

                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0  // Let GridLayout calculate based on weight
                    height = 0
                    setMargins(buttonMargin, buttonMargin, buttonMargin, buttonMargin)
                }

                setOnClickListener {
                    setSpeedAndClose(adjustedKph)
                }
            }
            container.addView(button)
        }

        pacePopupView = container

        // Add popup to window using helper
        val params = OverlayHelper.createOverlayParams(popupWidth, popupHeight, focusable = true)
        windowManager.addView(pacePopupView, params)
    }

    private fun setSpeedAndClose(adjustedKph: Double) {
        closePacePopup()

        // Check if a workout is loaded - if so, show confirmation dialog first
        if (isWorkoutLoadedAndIdle()) {
            Log.d(TAG, "Workout loaded - showing confirmation dialog before starting belt")
            onPaceSelectedWithWorkoutLoaded(adjustedKph)
            return
        }

        // No workout loaded - proceed with starting the belt
        startBeltAtSpeed(adjustedKph)
    }

    /**
     * Start the treadmill belt at the specified speed.
     * Called directly when no workout is loaded, or after user confirms free run.
     */
    fun startBeltAtSpeed(adjustedKph: Double) {
        scope.launch(Dispatchers.IO) {
            ensureTreadmillRunning()
            setTreadmillSpeed(adjustedKph)
        }
    }

    fun closePacePopup() {
        pacePopupView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing pace popup: ${e.message}")
            }
            pacePopupView = null
        }
    }

    // ==================== Incline Popup ====================

    fun toggleInclinePopup() {
        if (inclinePopupView != null) {
            closeInclinePopup()
        } else {
            closePacePopup()
            showInclinePopup()
        }
    }

    fun showInclinePopup() {
        val client = getGlassOsClient()
        val resources = service.resources

        // Use dynamic incline values from treadmill (already in effective incline), or generate defaults
        // All values shown are EFFECTIVE incline (outdoor equivalent), not raw treadmill incline
        var inclines = if (state.inclineValues.isNotEmpty()) {
            state.inclineValues
        } else {
            // Fallback: get treadmill range and convert to effective incline
            val treadmillMinIncline = client?.minInclinePercent?.takeIf { it.isFinite() } ?: -6.0
            val treadmillMaxIncline = client?.maxInclinePercent?.takeIf { it.isFinite() && it > treadmillMinIncline } ?: 40.0
            val effectiveMinIncline = treadmillMinIncline - state.inclineAdjustment
            val effectiveMaxIncline = treadmillMaxIncline - state.inclineAdjustment
            ServiceStateHolder.generateInclineValues(effectiveMinIncline, effectiveMaxIncline)
        }
        if (inclines.isEmpty()) {
            Log.w(TAG, "Incline popup: generated empty inclines, using defaults")
            // Default values also as effective incline (with 1% adjustment: -7 to 39)
            inclines = ServiceStateHolder.generateInclineValues(-6.0 - state.inclineAdjustment, 40.0 - state.inclineAdjustment)
        }
        val numValues = inclines.size
        val numRows = (numValues + INCLINE_GRID_COLUMNS - 1) / INCLINE_GRID_COLUMNS

        val popupWidthFraction = resources.getFloat(R.dimen.popup_width_fraction)
        val popupHeightFraction = resources.getFloat(R.dimen.popup_height_fraction)
        val popupWidth = (state.screenWidth * popupWidthFraction).toInt()
        val popupHeight = (state.screenHeight * popupHeightFraction).toInt()
        val popupPadding = resources.getDimensionPixelSize(R.dimen.popup_padding)
        val buttonMargin = resources.getDimensionPixelSize(R.dimen.popup_button_margin)
        val buttonTextSize = resources.getDimension(R.dimen.text_popup_button) / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

        // Colors from resources
        val popupBgColor = ContextCompat.getColor(service, R.color.popup_background)
        val buttonTextColor = ContextCompat.getColor(service, R.color.popup_button_text)
        val buttonActiveColor = ContextCompat.getColor(service, R.color.popup_button_active)
        val buttonInactiveColor = ContextCompat.getColor(service, R.color.popup_button_inactive)

        // Create popup container
        val container = GridLayout(service).apply {
            columnCount = INCLINE_GRID_COLUMNS
            rowCount = numRows
            alignmentMode = GridLayout.ALIGN_BOUNDS  // Align by cell bounds, not text baseline
            setBackgroundColor(popupBgColor)
            setPadding(popupPadding, popupPadding, popupPadding, popupPadding)
        }

        // Calculate button size to fill uniformly
        // Each button has margins on all sides, so total margin per row/column = numRows/Columns * 2 * buttonMargin
        val buttonWidth = (popupWidth - popupPadding * 2 - INCLINE_GRID_COLUMNS * buttonMargin * 2) / INCLINE_GRID_COLUMNS
        val buttonHeight = (popupHeight - popupPadding * 2 - numRows * buttonMargin * 2) / numRows

        // Round current incline to nearest integer to match grid values
        val roundedCurrentIncline = round(state.currentInclinePercent).toInt()

        // Create buttons for dynamic incline values
        for (incline in inclines) {
            val buttonText = if (incline > 0) {
                service.getString(R.string.incline_button_positive_format, incline)
            } else {
                service.getString(R.string.incline_button_format, incline)
            }
            val button = TextView(service).apply {
                text = buttonText
                textSize = buttonTextSize
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                setTextColor(buttonTextColor)
                setBackgroundColor(
                    if (roundedCurrentIncline == incline) buttonActiveColor else buttonInactiveColor
                )
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true

                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0  // Let GridLayout calculate based on weight
                    height = 0
                    setMargins(buttonMargin, buttonMargin, buttonMargin, buttonMargin)
                }

                setOnClickListener {
                    setInclineAndClose(incline.toDouble())
                }
            }
            container.addView(button)
        }

        inclinePopupView = container

        // Add popup to window using helper
        val params = OverlayHelper.createOverlayParams(popupWidth, popupHeight, focusable = true)
        windowManager.addView(inclinePopupView, params)
    }

    private fun setInclineAndClose(percent: Double) {
        scope.launch(Dispatchers.IO) {
            val client = getGlassOsClient()
            val workoutState = client?.getWorkoutState()
            Log.d(TAG, "setInclineAndClose: state=$workoutState, percent=$percent")

            when (workoutState) {
                WorkoutState.WORKOUT_STATE_PAUSED -> {
                    Log.d(TAG, "Workout paused, using resume->setIncline->pause cycle")
                    client?.resumeWorkout()
                    delay(100)
                    val success = setTreadmillIncline(percent)
                    Log.d(TAG, "Set incline to $percent%: $success")
                    delay(100)
                    client?.pauseWorkout()
                    Log.d(TAG, "Re-paused workout after incline change")
                }
                WorkoutState.WORKOUT_STATE_RUNNING -> {
                    val success = setTreadmillIncline(percent)
                    Log.d(TAG, "Set incline to $percent%: $success")
                }
                else -> {
                    // Allow incline change even when idle - hardware buttons work at all times
                    Log.d(TAG, "Workout idle, setting incline anyway (like hardware buttons)")
                    val success = setTreadmillIncline(percent)
                    Log.d(TAG, "Set incline to $percent%: $success")
                }
            }
        }
        closeInclinePopup()
    }

    fun closeInclinePopup() {
        inclinePopupView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing incline popup: ${e.message}")
            }
            inclinePopupView = null
        }
    }

    /**
     * Close all popups.
     */
    fun closeAllPopups() {
        closePacePopup()
        closeInclinePopup()
    }

    // ==================== Treadmill Control Helpers ====================

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

    private suspend fun ensureTreadmillRunning(): Boolean {
        val client = getGlassOsClient()
        val workoutState = client?.getWorkoutState()
        Log.d(TAG, "Ensuring treadmill running, current state: $workoutState")

        return when (workoutState) {
            WorkoutState.WORKOUT_STATE_PAUSED -> {
                Log.d(TAG, "Treadmill paused, resuming...")
                client?.resumeWorkout()
                waitForBeltReady()
            }
            WorkoutState.WORKOUT_STATE_RUNNING -> {
                Log.d(TAG, "Treadmill already running at ${state.currentSpeedKph} kph")
                true
            }
            else -> {
                Log.d(TAG, "Quick starting treadmill...")
                val success = client?.quickStartWorkout() ?: false
                if (success) {
                    Log.d(TAG, "Quick start successful, waiting for belt...")
                    waitForBeltReady()
                } else {
                    Log.e(TAG, "Failed to quick start treadmill")
                    false
                }
            }
        }
    }

    // NOTE: Speed setting is done via the setTreadmillSpeed callback passed to constructor.
    // This ensures ALL speed changes go through TelemetryManager.setTreadmillSpeed()
    // which applies the paceCoefficient conversion in ONE place.

    /**
     * Clean up resources.
     */
    fun cleanup() {
        pacePopupView?.let {
            pacePopupView = null
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing pace popup: ${e.message}")
            }
        }

        inclinePopupView?.let {
            inclinePopupView = null
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing incline popup: ${e.message}")
            }
        }
    }
}
