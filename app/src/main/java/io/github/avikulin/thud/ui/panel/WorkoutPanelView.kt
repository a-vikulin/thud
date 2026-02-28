package io.github.avikulin.thud.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.util.HeartRateZones
import io.github.avikulin.thud.R
import io.github.avikulin.thud.domain.engine.ExecutionStep
import io.github.avikulin.thud.domain.engine.WorkoutExecutionState
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.EarlyEndCondition
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.util.PaceConverter

/**
 * Custom view that displays workout execution state on the left side of the screen.
 * Shows workout name, step list, current step details, and control buttons.
 */
class WorkoutPanelView(context: Context) : View(context) {

    // ==================== State ====================

    private var workoutName: String = ""
    private var steps: List<ExecutionStep> = emptyList()
    private var currentStepIndex: Int = -1
    private var warmupStepCount: Int = 0
    private var mainStepCount: Int = 0
    private var cooldownStepCount: Int = 0
    private var stepElapsedMs: Long = 0
    private var stepDistanceMeters: Double = 0.0
    private var isRunning: Boolean = false
    private var isPaused: Boolean = false
    private var isTransitioning: Boolean = false
    private var countdownSeconds: Int = 0
    private var previousCountdownSeconds: Int? = null  // Track previous to detect changes
    private var showGoText: Boolean = false  // Show "GO!" at step transition
    private val goTextHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isHrAdjustmentActive: Boolean = false
    private var hrAdjustmentDirection: String? = null
    private var isWorkoutLoaded: Boolean = false

    /** Lactate Threshold HR for converting percentages to BPM. */
    var userLthrBpm: Int = 170

    // Audio for countdown beeps - pre-generated 4-second sequence (3-2-1-GO)
    private val sampleRate = 44100
    private var countdownAudioTrack: AudioTrack? = null
    private var countdownSamples: ShortArray? = null  // Lazy-initialized

    // ==================== Callbacks ====================

    var onNextStepClicked: (() -> Unit)? = null
    var onPrevStepClicked: (() -> Unit)? = null
    var onResetToStepClicked: (() -> Unit)? = null

    // ==================== Dimensions (from resources) ====================

    private val panelPadding = resources.getDimensionPixelSize(R.dimen.workout_panel_padding)
    private val headerHeight = resources.getDimensionPixelSize(R.dimen.workout_panel_header_height)
    private val stepItemHeight = resources.getDimensionPixelSize(R.dimen.step_item_height)
    private val stepItemMargin = resources.getDimensionPixelSize(R.dimen.step_item_margin)
    private val stepIndicatorSize = resources.getDimensionPixelSize(R.dimen.step_indicator_size)
    private val controlButtonSize = resources.getDimensionPixelSize(R.dimen.control_button_size)
    private val controlButtonMargin = resources.getDimensionPixelSize(R.dimen.control_button_margin)
    private val progressBarHeight = resources.getDimensionPixelSize(R.dimen.progress_bar_height)
    private val progressBarCornerRadius = resources.getDimensionPixelSize(R.dimen.progress_bar_corner_radius).toFloat()
    private val cornerRadius = resources.getDimensionPixelSize(R.dimen.workout_panel_corner_radius).toFloat()

    // ==================== Text Sizes ====================

    private val titleTextSize = resources.getDimensionPixelSize(R.dimen.text_workout_title).toFloat()
    private val stepNameTextSize = resources.getDimensionPixelSize(R.dimen.text_step_name).toFloat()
    private val stepDetailTextSize = resources.getDimensionPixelSize(R.dimen.text_step_detail).toFloat()
    private val stepTimeTextSize = resources.getDimensionPixelSize(R.dimen.text_step_time).toFloat()
    private val controlButtonTextSize = resources.getDimensionPixelSize(R.dimen.text_control_button).toFloat()
    private val countdownTextSize = resources.getDimensionPixelSize(R.dimen.text_countdown).toFloat()

    // ==================== Colors ====================

    private val backgroundColor = ContextCompat.getColor(context, R.color.workout_panel_background)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textLabel = ContextCompat.getColor(context, R.color.text_label)
    private val stepPendingColor = ContextCompat.getColor(context, R.color.step_pending)
    private val stepActiveColor = ContextCompat.getColor(context, R.color.step_active)
    private val stepCompletedColor = ContextCompat.getColor(context, R.color.step_completed)
    private val progressBgColor = ContextCompat.getColor(context, R.color.progress_bar_background)
    private val progressFillColor = ContextCompat.getColor(context, R.color.progress_bar_fill)
    private val controlButtonBg = ContextCompat.getColor(context, R.color.control_button_background)
    private val hrWarningColor = ContextCompat.getColor(context, R.color.hr_target_warning)

    // ==================== Cached Strings ====================

    private val defaultWorkoutName = context.getString(R.string.workout_panel_default_workout_name)
    private val btnPrevText = context.getString(R.string.btn_prev)
    private val btnPlayText = context.getString(R.string.btn_play)
    private val btnResetToStepText = context.getString(R.string.btn_reset_to_step)
    private val btnNextText = context.getString(R.string.btn_next)
    private val openStepIndicatorText = context.getString(R.string.workout_panel_open_step_indicator)
    private val pausedText = context.getString(R.string.workout_panel_paused)

    /** Get step type color using centralized utility. */
    private fun getStepTypeColor(type: StepType): Int =
        ContextCompat.getColor(context, HeartRateZones.getStepTypeColorResId(type))

    private val phaseBarWidth = resources.getDimensionPixelSize(R.dimen.phase_bar_width).toFloat()

    private fun isWarmupStep(index: Int): Boolean = index < warmupStepCount
    private fun isCooldownStep(index: Int): Boolean =
        cooldownStepCount > 0 && index >= warmupStepCount + mainStepCount

    // ==================== Paints ====================

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPrimary
        textSize = titleTextSize
        typeface = Typeface.DEFAULT_BOLD
    }

    private val stepNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPrimary
        textSize = stepNameTextSize
    }

    private val stepDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textLabel
        textSize = stepDetailTextSize
    }

    private val stepTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textLabel
        textSize = stepTimeTextSize
        textAlign = Paint.Align.RIGHT
    }

    private val controlButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = controlButtonBg
        style = Paint.Style.FILL
    }

    private val controlTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPrimary
        textSize = controlButtonTextSize
        textAlign = Paint.Align.CENTER
    }

    private val progressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = progressBgColor
        style = Paint.Style.FILL
    }

    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = progressFillColor
        style = Paint.Style.FILL
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPrimary
        textSize = countdownTextSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val loadingSpinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPrimary
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val linePaint = Paint().apply {
        color = stepPendingColor
        strokeWidth = 1f
    }

    private val overlayPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // ==================== Reusable Drawing Objects ====================

    private val bgRect = RectF()
    private val buttonRect = RectF()
    private val progressBgRect = RectF()
    private val progressFillRect = RectF()

    // ==================== Touch Handling ====================

    private val buttonBounds = mutableMapOf<String, RectF>()

    // ==================== Public Methods ====================

    /**
     * Update the panel with current workout execution state.
     */
    fun updateState(state: WorkoutExecutionState) {
        when (state) {
            is WorkoutExecutionState.Idle -> {
                // Keep workout name and steps if loaded (for showing "ready to start")
                isRunning = false
                isPaused = false
                isTransitioning = false
                currentStepIndex = -1
                previousCountdownSeconds = null
                stopCountdownAudio()
            }
            is WorkoutExecutionState.Running -> {
                workoutName = state.workout.name

                // Save old values BEFORE updating for transition detection
                val oldStepIndex = currentStepIndex
                val oldCountdownSeconds = previousCountdownSeconds

                // Update state values
                currentStepIndex = state.currentStepIndex
                stepElapsedMs = state.stepElapsedMs
                stepDistanceMeters = state.stepDistanceMeters
                isRunning = true
                isPaused = false
                isHrAdjustmentActive = state.isHrAdjustmentActive
                hrAdjustmentDirection = state.hrAdjustmentDirection

                // Countdown shown in last 3 seconds before step ends (3-2-1-GO pattern)
                val newCountdown = state.countdownSeconds ?: 0

                // Detect step transition with countdown ending (GO! moment)
                val stepChanged = state.currentStepIndex != oldStepIndex && oldStepIndex >= 0
                val countdownJustEnded = oldCountdownSeconds != null && state.countdownSeconds == null

                if (stepChanged && countdownJustEnded) {
                    // Step transitioned after countdown - show GO! and let audio finish
                    showGoText = true
                    goTextHandler.removeCallbacksAndMessages(null)
                    goTextHandler.postDelayed({
                        showGoText = false
                        isTransitioning = false
                        invalidate()
                    }, 600)  // Show GO! for 600ms (matches GO beep duration)
                    // Reset for next step's countdown detection
                    previousCountdownSeconds = null
                } else if (stepChanged) {
                    // Step changed without countdown (manual skip, etc.) - stop audio
                    stopCountdownAudio()
                    previousCountdownSeconds = null
                } else {
                    // Same step - check if countdown should start
                    val skipStartupBeeps = state.currentStepIndex == 0 && state.stepElapsedMs < 2000
                    if (state.countdownSeconds != null && oldCountdownSeconds == null && !skipStartupBeeps) {
                        playCountdownSequence()
                    }
                    previousCountdownSeconds = if (state.countdownSeconds != null) newCountdown else null
                }

                countdownSeconds = newCountdown
                isTransitioning = state.countdownSeconds != null || showGoText
            }
            is WorkoutExecutionState.Paused -> {
                workoutName = state.workout.name
                currentStepIndex = state.currentStepIndex
                stepElapsedMs = state.stepElapsedMs
                stepDistanceMeters = state.stepDistanceMeters
                isRunning = false
                isPaused = true
                isTransitioning = false
                previousCountdownSeconds = null
                stopCountdownAudio()
            }
            is WorkoutExecutionState.Completed -> {
                // Workout completed - panel will be hidden by HUDService
                // FIT export happens automatically on double-stop
                workoutName = state.workout.name
                isRunning = false
                isPaused = false
                isTransitioning = false
                currentStepIndex = steps.size // All completed
                previousCountdownSeconds = null
                stopCountdownAudio()
            }
        }
        invalidate()
    }

    /**
     * Set the list of execution steps with optional phase counts for stitched workouts.
     */
    fun setSteps(executionSteps: List<ExecutionStep>, phaseCounts: Triple<Int, Int, Int>? = null) {
        steps = executionSteps
        isWorkoutLoaded = executionSteps.isNotEmpty()
        warmupStepCount = phaseCounts?.first ?: 0
        mainStepCount = phaseCounts?.second ?: executionSteps.size
        cooldownStepCount = phaseCounts?.third ?: 0
        invalidate()
    }

    /**
     * Set the workout name (for displaying before workout starts).
     */
    fun setWorkoutName(name: String) {
        workoutName = name
        invalidate()
    }

    // ==================== Drawing ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, backgroundPaint)

        // Calculate reserved heights for fixed areas
        val buttonHeight = (controlButtonSize * 1.5f).toInt()
        val buttonAreaHeight = buttonHeight + panelPadding * 2  // Buttons + padding
        val currentStepDetailHeight = calculateCurrentStepDetailHeight()

        // Calculate available height for step list
        val stepListStartY = panelPadding + headerHeight
        val stepListEndY = height - buttonAreaHeight - currentStepDetailHeight
        val stepListAvailableHeight = (stepListEndY - stepListStartY).coerceAtLeast(0f)

        var yOffset = panelPadding.toFloat()

        // Draw header with centered workout name
        yOffset = drawHeader(canvas, yOffset)

        // Draw step list (limited to available height)
        yOffset = drawStepList(canvas, yOffset, stepListAvailableHeight)

        // Draw current step detail (in reserved area above buttons)
        if (currentStepIndex >= 0 && currentStepIndex < steps.size) {
            // Position current step detail at fixed location above buttons
            val currentStepDetailY = height - buttonAreaHeight - currentStepDetailHeight + stepItemMargin
            drawCurrentStepDetail(canvas, currentStepDetailY)
        }

        // Draw control buttons at bottom
        drawControlButtons(canvas)

        // Draw overlays
        if (isTransitioning) {
            drawCountdownOverlay(canvas)
        }
        // Note: No start overlay - workout auto-starts when loaded
        // Note: No completion overlay - FIT export happens on double-stop with Toast
    }

    /**
     * Calculate the height needed for current step detail section.
     * Includes separator, step name, pace/incline, optional HR target, optional OPEN indicator,
     * progress bar, and progress text.
     */
    private fun calculateCurrentStepDetailHeight(): Float {
        if (currentStepIndex < 0 || currentStepIndex >= steps.size) return 0f

        val step = steps.getOrNull(currentStepIndex) ?: return 0f

        var height = stepItemMargin * 2f  // Top margin + separator
        height += stepItemMargin * 2f  // After separator
        height += titleTextSize + stepItemMargin  // Step name
        height += stepDetailTextSize + stepItemMargin  // Pace/incline

        if (step.hasHrTarget) {
            height += stepDetailTextSize + stepItemMargin  // HR target
        }

        if (step.earlyEndCondition == EarlyEndCondition.OPEN) {
            height += stepDetailTextSize + stepItemMargin  // OPEN indicator
        }

        height += stepItemMargin  // Before progress bar
        height += progressBarHeight + stepItemMargin  // Progress bar
        height += stepDetailTextSize  // Progress text

        if (isPaused) {
            height += stepItemMargin * 2 + titleTextSize  // Paused indicator
        }

        return height
    }

    private fun drawHeader(canvas: Canvas, startY: Float): Float {
        // Draw centered workout name
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            workoutName.ifEmpty { defaultWorkoutName },
            width / 2f,
            startY + titleTextSize,
            titlePaint
        )
        titlePaint.textAlign = Paint.Align.LEFT

        return startY + headerHeight
    }

    private fun drawControlButtons(canvas: Canvas) {
        // Buttons are enabled only when running or paused
        val buttonsEnabled = isRunning || isPaused

        // Button dimensions - 50% taller than default
        val buttonHeight = (controlButtonSize * 1.5f).toInt()
        val buttonY = height - panelPadding - buttonHeight.toFloat()

        // 3 buttons evenly distributed across full width (removed play/pause and stop - use physical buttons)
        val numButtons = 3
        val totalWidth = width - 2 * panelPadding
        val buttonWidth = (totalWidth - (numButtons - 1) * controlButtonMargin) / numButtons

        var buttonX = panelPadding.toFloat()

        // Prev button
        drawControlButton(canvas, buttonX, buttonY, "prev", btnPrevText, buttonWidth.toFloat(), buttonHeight.toFloat(), buttonsEnabled)
        buttonX += buttonWidth + controlButtonMargin

        // Center button: Play when paused, Reset to Step when running
        val centerIcon = if (isPaused) btnPlayText else btnResetToStepText
        drawControlButton(canvas, buttonX, buttonY, "resetToStep", centerIcon, buttonWidth.toFloat(), buttonHeight.toFloat(), buttonsEnabled)
        buttonX += buttonWidth + controlButtonMargin

        // Next button
        drawControlButton(canvas, buttonX, buttonY, "next", btnNextText, buttonWidth.toFloat(), buttonHeight.toFloat(), buttonsEnabled)
    }

    private fun drawControlButton(canvas: Canvas, x: Float, y: Float, id: String, icon: String, buttonWidth: Float, buttonHeight: Float, enabled: Boolean = true) {
        buttonRect.set(x, y, x + buttonWidth, y + buttonHeight)
        buttonBounds.getOrPut(id) { RectF() }.set(buttonRect)  // Reuse for touch hit-testing

        // Draw button background with reduced alpha when disabled
        val originalAlpha = controlButtonPaint.alpha
        if (!enabled) {
            controlButtonPaint.alpha = 80  // ~30% opacity when disabled
        }
        canvas.drawRoundRect(buttonRect, 8f, 8f, controlButtonPaint)
        controlButtonPaint.alpha = originalAlpha

        // Draw button text with reduced alpha when disabled
        val originalTextAlpha = controlTextPaint.alpha
        if (!enabled) {
            controlTextPaint.alpha = 80
        }
        val textY = y + buttonHeight / 2 + controlTextPaint.textSize / 3
        canvas.drawText(icon, x + buttonWidth / 2, textY, controlTextPaint)
        controlTextPaint.alpha = originalTextAlpha
    }

    private fun drawStepList(canvas: Canvas, startY: Float, availableHeight: Float): Float {
        if (steps.isEmpty()) return startY

        var yOffset = startY + stepItemMargin

        // Calculate max visible steps based on available height
        val moreStepsIndicatorHeight = stepItemHeight / 2
        val heightPerStep = stepItemHeight
        val maxStepsFromHeight = ((availableHeight - stepItemMargin * 2 - moreStepsIndicatorHeight) / heightPerStep).toInt()
        val maxVisibleSteps = maxStepsFromHeight.coerceIn(1, 8)  // At least 1, at most 8

        val startIdx = maxOf(0, currentStepIndex - 2)
        val endIdx = minOf(steps.size, startIdx + maxVisibleSteps)

        for (i in startIdx until endIdx) {
            val step = steps[i]
            val isCurrentStep = i == currentStepIndex
            val isCompleted = i < currentStepIndex

            yOffset = drawStepItem(canvas, yOffset, step, i, isCurrentStep, isCompleted)
        }

        // Show "..." if there are more steps
        if (endIdx < steps.size) {
            stepDetailPaint.color = textLabel
            canvas.drawText(
                context.getString(R.string.workout_panel_more_steps, steps.size - endIdx),
                panelPadding + stepIndicatorSize + 8f,
                yOffset + stepDetailTextSize,
                stepDetailPaint
            )
            yOffset += stepItemHeight / 2
        }

        return yOffset + stepItemMargin
    }

    private fun drawStepItem(
        canvas: Canvas,
        y: Float,
        step: ExecutionStep,
        index: Int,
        isCurrent: Boolean,
        isCompleted: Boolean
    ): Float {
        val indicatorX = panelPadding.toFloat()
        val textX = indicatorX + stepIndicatorSize + 8f

        // Draw indicator
        indicatorPaint.color = when {
            isCompleted -> stepCompletedColor
            isCurrent -> stepActiveColor
            else -> stepPendingColor
        }

        val indicatorCenterY = y + stepItemHeight / 2
        val indicatorRadius = stepIndicatorSize / 3f

        if (isCompleted) {
            // Checkmark indicator
            indicatorPaint.style = Paint.Style.STROKE
            indicatorPaint.strokeWidth = 3f
            canvas.drawCircle(indicatorX + stepIndicatorSize / 2, indicatorCenterY, indicatorRadius, indicatorPaint)
            // Draw checkmark
            canvas.drawLine(
                indicatorX + stepIndicatorSize / 2 - 6,
                indicatorCenterY,
                indicatorX + stepIndicatorSize / 2 - 2,
                indicatorCenterY + 5,
                indicatorPaint
            )
            canvas.drawLine(
                indicatorX + stepIndicatorSize / 2 - 2,
                indicatorCenterY + 5,
                indicatorX + stepIndicatorSize / 2 + 7,
                indicatorCenterY - 6,
                indicatorPaint
            )
            indicatorPaint.style = Paint.Style.FILL
        } else if (isCurrent) {
            // Filled circle for current
            canvas.drawCircle(indicatorX + stepIndicatorSize / 2, indicatorCenterY, indicatorRadius, indicatorPaint)
        } else {
            // Empty circle for pending
            indicatorPaint.style = Paint.Style.STROKE
            indicatorPaint.strokeWidth = 2f
            canvas.drawCircle(indicatorX + stepIndicatorSize / 2, indicatorCenterY, indicatorRadius, indicatorPaint)
            indicatorPaint.style = Paint.Style.FILL
        }

        // Draw left phase color bar for warmup/cooldown steps
        if (isWarmupStep(index) || isCooldownStep(index)) {
            indicatorPaint.color = getStepTypeColor(step.type)
            canvas.drawRect(0f, y, phaseBarWidth, y + stepItemHeight, indicatorPaint)
            // Reset indicator paint style after borrowing it
            indicatorPaint.style = Paint.Style.FILL
        }

        // Draw step name with type color
        stepNamePaint.color = getStepTypeColor(step.type)
        if (isCurrent) {
            stepNamePaint.typeface = Typeface.DEFAULT_BOLD
        } else {
            stepNamePaint.typeface = Typeface.DEFAULT
        }

        // Phase-aware step numbering: W1, C1 for warmup/cooldown steps
        val stepNumber = when {
            isWarmupStep(index) -> "W${index + 1}. "
            isCooldownStep(index) -> "C${index - warmupStepCount - mainStepCount + 1}. "
            else -> "${index - warmupStepCount + 1}. "
        }
        val openSuffix = if (step.earlyEndCondition == EarlyEndCondition.OPEN) " ∞" else ""
        canvas.drawText(
            stepNumber + step.displayName + openSuffix,
            textX,
            y + stepItemHeight / 2 + stepNameTextSize / 3,
            stepNamePaint
        )

        // Draw duration on the right
        val durationText = formatStepDuration(step)
        stepTimePaint.color = if (isCurrent) textPrimary else textLabel
        canvas.drawText(
            durationText,
            width - panelPadding.toFloat(),
            y + stepItemHeight / 2 + stepTimeTextSize / 3,
            stepTimePaint
        )

        return y + stepItemHeight
    }

    private fun drawCurrentStepDetail(canvas: Canvas, startY: Float) {
        val step = steps.getOrNull(currentStepIndex) ?: return
        var yOffset = startY + stepItemMargin * 2

        // Separator line
        canvas.drawLine(
            panelPadding.toFloat(),
            yOffset,
            width - panelPadding.toFloat(),
            yOffset,
            linePaint
        )
        yOffset += stepItemMargin * 2

        // Step name (larger)
        titlePaint.color = getStepTypeColor(step.type)
        canvas.drawText(step.displayName, panelPadding.toFloat(), yOffset + titleTextSize, titlePaint)
        titlePaint.color = textPrimary
        yOffset += titleTextSize + stepItemMargin

        // Pace and incline
        val paceText = PaceConverter.formatPaceFromSpeed(step.paceTargetKph)
        val inclineText = context.getString(R.string.workout_panel_incline_detail, step.inclineTargetPercent)
        stepDetailPaint.color = textLabel
        canvas.drawText(
            context.getString(R.string.workout_panel_pace_incline, paceText, inclineText),
            panelPadding.toFloat(),
            yOffset + stepDetailTextSize,
            stepDetailPaint
        )
        yOffset += stepDetailTextSize + stepItemMargin

        // HR target if configured
        if (step.hasHrTarget) {
            val hrMinBpm = step.getHrTargetMinBpm(userLthrBpm)
            val hrMaxBpm = step.getHrTargetMaxBpm(userLthrBpm)
            val hrText = context.getString(R.string.workout_panel_hr_target_detail, hrMinBpm, hrMaxBpm)
            if (isHrAdjustmentActive) {
                stepDetailPaint.color = hrWarningColor
                val adjText = hrAdjustmentDirection?.let { " ($it)" } ?: ""
                canvas.drawText(
                    hrText + adjText,
                    panelPadding.toFloat(),
                    yOffset + stepDetailTextSize,
                    stepDetailPaint
                )
            } else {
                stepDetailPaint.color = textLabel
                canvas.drawText(hrText, panelPadding.toFloat(), yOffset + stepDetailTextSize, stepDetailPaint)
            }
            yOffset += stepDetailTextSize + stepItemMargin
        }

        // OPEN indicator - step won't auto-end, user must press Next
        if (step.earlyEndCondition == EarlyEndCondition.OPEN) {
            stepDetailPaint.color = ContextCompat.getColor(context, R.color.hr_zone_3)  // Green
            canvas.drawText(
                openStepIndicatorText,
                panelPadding.toFloat(),
                yOffset + stepDetailTextSize,
                stepDetailPaint
            )
            yOffset += stepDetailTextSize + stepItemMargin
        }

        // Progress bar
        yOffset += stepItemMargin
        val progressWidth = width - 2 * panelPadding
        progressBgRect.set(
            panelPadding.toFloat(),
            yOffset,
            panelPadding + progressWidth.toFloat(),
            yOffset + progressBarHeight
        )
        canvas.drawRoundRect(progressBgRect, progressBarCornerRadius, progressBarCornerRadius, progressBgPaint)

        // Fill based on progress
        val progress = calculateStepProgress(step)
        val fillWidth = progressWidth * progress
        progressFillRect.set(
            panelPadding.toFloat(),
            yOffset,
            panelPadding + fillWidth,
            yOffset + progressBarHeight
        )
        canvas.drawRoundRect(progressFillRect, progressBarCornerRadius, progressBarCornerRadius, progressFillPaint)

        yOffset += progressBarHeight + stepItemMargin

        // Progress text
        val progressText = formatProgressText(step)
        stepDetailPaint.color = textPrimary
        canvas.drawText(progressText, panelPadding.toFloat(), yOffset + stepDetailTextSize, stepDetailPaint)
        yOffset += stepDetailTextSize

        // Paused indicator
        if (isPaused) {
            yOffset += stepItemMargin * 2
            titlePaint.color = hrWarningColor
            canvas.drawText(pausedText, panelPadding.toFloat(), yOffset + titleTextSize, titlePaint)
            titlePaint.color = textPrimary
        }
    }

    private fun drawCountdownOverlay(canvas: Canvas) {
        // Semi-transparent overlay
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

        // Countdown number or GO!
        val centerX = width / 2f
        val centerY = height / 2f
        val displayText = if (showGoText) "GO!" else countdownSeconds.toString()
        canvas.drawText(displayText, centerX, centerY + countdownTextSize / 3, countdownPaint)

        // Step name - show current step during GO!, next step during countdown
        val stepToShow = if (showGoText) {
            steps.getOrNull(currentStepIndex)  // Current step (just started)
        } else {
            steps.getOrNull(currentStepIndex + 1)  // Next step (upcoming)
        }
        if (stepToShow != null) {
            stepNamePaint.color = textPrimary
            stepNamePaint.textAlign = Paint.Align.CENTER
            val stepText = if (showGoText) {
                stepToShow.displayName  // Just the step name during GO!
            } else {
                context.getString(R.string.workout_panel_next_step, stepToShow.displayName)
            }
            canvas.drawText(
                stepText,
                centerX,
                centerY + countdownTextSize + stepNameTextSize,
                stepNamePaint
            )
            stepNamePaint.textAlign = Paint.Align.LEFT
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Generate the 4-second countdown audio sequence (lazy initialization).
     * 3-2-1-GO pattern: beeps at 0s, 1s, 2s (1000Hz, 200ms) and GO beep at 3s (1200Hz, 500ms).
     * Sequence starts when 3 seconds remain, so GO beep plays exactly at step transition.
     */
    private fun getCountdownSamples(): ShortArray {
        countdownSamples?.let { return it }

        // Total duration: 4 seconds (3-2-1-GO)
        val totalSamples = sampleRate * 4
        val samples = ShortArray(totalSamples)

        // Beep timings (in samples from start)
        // Sequence starts at 3 seconds remaining, GO beep at 3.0s = step transition
        val beepParams = listOf(
            Triple(0.0, 1000, 200),      // Beep 3: 0s, 1000Hz, 200ms (3 sec remaining)
            Triple(1.0, 1000, 200),      // Beep 2: 1s, 1000Hz, 200ms (2 sec remaining)
            Triple(2.0, 1000, 200),      // Beep 1: 2s, 1000Hz, 200ms (1 sec remaining)
            Triple(3.0, 1200, 500)       // GO beep: 3s, 1200Hz, 500ms (step transition)
        )

        val fadeSamples = sampleRate / 100  // 10ms fade

        for ((startTimeSec, freqHz, durationMs) in beepParams) {
            val startSample = (startTimeSec * sampleRate).toInt()
            val numSamples = (sampleRate * durationMs) / 1000
            val twoPiF = 2.0 * Math.PI * freqHz / sampleRate

            for (i in 0 until numSamples) {
                val sampleIndex = startSample + i
                if (sampleIndex >= totalSamples) break

                val envelope = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > numSamples - fadeSamples -> (numSamples - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                samples[sampleIndex] = (sin(twoPiF * i) * Short.MAX_VALUE * envelope).toInt().toShort()
            }
        }

        countdownSamples = samples
        return samples
    }

    /**
     * Play the pre-generated 4-second countdown sequence (3-2-1-GO).
     * Audio timing is handled by the audio system, not by Handler delays.
     */
    private fun playCountdownSequence() {
        // Stop any existing playback
        stopCountdownAudio()

        Thread {
            try {
                val samples = getCountdownSamples()

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                synchronized(this@WorkoutPanelView) {
                    countdownAudioTrack = audioTrack
                }

                audioTrack.write(samples, 0, samples.size)
                audioTrack.play()

                // Wait for playback to complete (4 seconds + buffer)
                Thread.sleep(4100)

                synchronized(this@WorkoutPanelView) {
                    if (countdownAudioTrack == audioTrack) {
                        audioTrack.stop()
                        audioTrack.release()
                        countdownAudioTrack = null
                    }
                }
            } catch (e: Exception) {
                // Ignore audio errors
            }
        }.start()
    }

    /**
     * Stop countdown audio playback (called when step changes or workout stops).
     */
    private fun stopCountdownAudio() {
        synchronized(this) {
            countdownAudioTrack?.let { track ->
                try {
                    track.stop()
                    track.release()
                } catch (e: Exception) {
                    // Ignore
                }
                countdownAudioTrack = null
            }
        }
    }

    private fun formatStepDuration(step: ExecutionStep): String {
        return when (step.durationType) {
            DurationType.TIME -> {
                step.durationSeconds?.let { PaceConverter.formatDuration(it) } ?: "--:--"
            }
            DurationType.DISTANCE -> {
                step.durationMeters?.let { PaceConverter.formatDistance(it) } ?: "-- m"
            }
        }
    }

    private fun calculateStepProgress(step: ExecutionStep): Float {
        return when (step.durationType) {
            DurationType.TIME -> {
                val targetMs = (step.durationSeconds ?: 0) * 1000L
                if (targetMs > 0) (stepElapsedMs.toFloat() / targetMs).coerceIn(0f, 1f)
                else 0f
            }
            DurationType.DISTANCE -> {
                val targetMeters = step.durationMeters ?: 0
                if (targetMeters > 0) (stepDistanceMeters.toFloat() / targetMeters).coerceIn(0f, 1f)
                else 0f
            }
        }
    }

    private fun formatProgressText(step: ExecutionStep): String {
        return when (step.durationType) {
            DurationType.TIME -> {
                val elapsed = PaceConverter.formatDuration((stepElapsedMs / 1000).toInt())
                val total = step.durationSeconds?.let { PaceConverter.formatDuration(it) } ?: "--:--"
                "$elapsed / $total"
            }
            DurationType.DISTANCE -> {
                val elapsed = PaceConverter.formatDistance(stepDistanceMeters.toInt())
                val total = step.durationMeters?.let { PaceConverter.formatDistance(it) } ?: "-- m"
                "$elapsed / $total"
            }
        }
    }

    // ==================== Touch Handling ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            // Control buttons only work when running or paused
            if (isRunning || isPaused) {
                buttonBounds["next"]?.let {
                    if (it.contains(x, y)) {
                        onNextStepClicked?.invoke()
                        return true
                    }
                }

                buttonBounds["prev"]?.let {
                    if (it.contains(x, y)) {
                        onPrevStepClicked?.invoke()
                        return true
                    }
                }

                buttonBounds["resetToStep"]?.let {
                    if (it.contains(x, y)) {
                        onResetToStepClicked?.invoke()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
