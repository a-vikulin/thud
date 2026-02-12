package io.github.avikulin.thud.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import io.github.avikulin.thud.R
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.util.HeartRateZones
import io.github.avikulin.thud.util.PaceConverter
import io.github.avikulin.thud.util.PowerZones
import io.github.avikulin.thud.util.StepBoundaryParser
import io.github.avikulin.thud.util.StepTimeBoundary
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Custom chart view for displaying workout metrics over time.
 * Shows speed (cyan), incline (yellow), and heart rate (zone-colored) lines.
 * Scales auto-expand as data exceeds current ranges.
 *
 * Can be used in two modes:
 * 1. Live mode (ChartManager overlay): Use setPlannedSegments() + setData() for live telemetry
 * 2. Preview mode (Editor): Use setSteps() for static workout visualization
 */
class WorkoutChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val INITIAL_TIME_RANGE_MINUTES = 30
        private const val TIME_RANGE_INCREMENT_MINUTES = 5

        // Initial scale ranges (will expand as needed)
        private const val SPEED_MIN_KPH = 0.0
        private const val SPEED_INITIAL_MAX_KPH = 6.0
        private const val SPEED_EXPAND_STEP_KPH = 1.0

        private const val INCLINE_INITIAL_MIN_PERCENT = 0.0
        private const val INCLINE_INITIAL_MAX_PERCENT = 5.0
        private const val INCLINE_EXPAND_STEP_PERCENT = 1.0

        private const val HR_DEFAULT_MIN_BPM = 60.0
        private const val HR_EXPAND_STEP_BPM = 10.0

        // Power scale
        private const val POWER_INITIAL_MAX_WATTS = 300.0
        private const val POWER_EXPAND_STEP_WATTS = 50.0
    }

    /**
     * User settings for chart visualization.
     * Consolidates HR/Power zone configuration in one place.
     * Use [applyUserSettings] to apply these to a chart instance.
     */
    data class ChartUserSettings(
        val lthrBpm: Int,
        val ftpWatts: Int,
        val hrZone2Start: Int,
        val hrZone3Start: Int,
        val hrZone4Start: Int,
        val hrZone5Start: Int,
        val powerZone2Start: Int,
        val powerZone3Start: Int,
        val powerZone4Start: Int,
        val powerZone5Start: Int,
        /** HR scale minimum (default 60 BPM for live chart, use 50% of LTHR for compact preview) */
        val hrMinBpm: Int = HR_DEFAULT_MIN_BPM.toInt()
    )

    // Data source
    private var dataPoints: List<WorkoutDataPoint> = emptyList()

    // Cached step boundaries parsed from actual data points (for drawing past steps)
    private var actualStepBoundaries: List<StepTimeBoundary> = emptyList()

    // HR zone thresholds (as absolute BPM values where each zone starts)
    private var hrZone2Start = 136   // Zone 2 starts at ~80% of 170 LTHR
    private var hrZone3Start = 150   // Zone 3 starts at ~88% of 170 LTHR
    private var hrZone4Start = 162   // Zone 4 starts at ~95% of 170 LTHR
    private var hrZone5Start = 173   // Zone 5 starts at ~102% of 170 LTHR

    // Power zone thresholds (as absolute watts where each zone starts)
    private var powerZone2Start = 138  // Zone 2 starts at 55% of 250W FTP
    private var powerZone3Start = 188  // Zone 3 starts at 75% of 250W
    private var powerZone4Start = 225  // Zone 4 starts at 90% of 250W
    private var powerZone5Start = 263  // Zone 5 starts at 105% of 250W

    // Pre-cached zone colors (index 0-5, resolved once in init and on zone config change)
    private val hrZoneColors = IntArray(6)
    private val hrZoneDimmedColors = IntArray(6)
    private val powerZoneColors = IntArray(6)
    private val powerZoneDimmedColors = IntArray(6)

    // Line visibility flags for toggle buttons
    var showSpeedLine = true
    var showInclineLine = true
    var showHrLine = true
    var showPowerLine = true

    // Current time range in minutes
    private var timeRangeMinutes = INITIAL_TIME_RANGE_MINUTES

    // Workout mode - when true, time range is fixed to workout duration
    private var isWorkoutMode = false
    private var workoutDurationMinutes = 0

    // Dynamic scale ranges
    private var speedMinKph = SPEED_MIN_KPH
    private var speedMaxKph = SPEED_INITIAL_MAX_KPH
    private var inclineMinPercent = INCLINE_INITIAL_MIN_PERCENT
    private var inclineMaxPercent = INCLINE_INITIAL_MAX_PERCENT
    private var hrMaxBpm = calculateInitialHrMax()
    private var powerMaxWatts = POWER_INITIAL_MAX_WATTS

    // Target scale values for animation (where we want to be)
    private var targetSpeedMinKph = SPEED_MIN_KPH
    private var targetSpeedMaxKph = SPEED_INITIAL_MAX_KPH
    private var targetInclineMinPercent = INCLINE_INITIAL_MIN_PERCENT
    private var targetInclineMaxPercent = INCLINE_INITIAL_MAX_PERCENT
    private var targetHrMinBpm = HR_DEFAULT_MIN_BPM
    private var targetHrMaxBpm = calculateInitialHrMax()

    // Full scale mode flag (shows all data vs smart auto-fit)
    private var isFullScaleMode = false

    // Chart area bounds (excluding axis labels)
    private var chartLeft = 0f
    private var chartRight = 0f
    private var chartTop = 0f
    private var chartBottom = 0f

    // Cached paths for efficient drawing
    private val speedPath = Path()
    private val inclinePath = Path()
    private val diamondPath = Path()
    private var lastProcessedIndex = -1

    // HR segments (need separate colors per segment)
    private data class HRSegment(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Int  // Resolved color (not resource ID)
    )
    private val hrSegments = mutableListOf<HRSegment>()

    // Power segments (need separate colors per segment, like HR)
    private data class PowerSegment(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Int,       // Resolved color (not resource ID)
        val dimmedColor: Int  // Pre-blended with background for rendering
    )
    private val powerSegments = mutableListOf<PowerSegment>()

    // Reusable list for zone boundary crossings (cleared and reused per segment pair)
    private val crossings = mutableListOf<Pair<Double, Int>>()

    /**
     * Planned segment per spec Section 4.4.
     * Includes pace/incline targets and HR/Power target ranges for visualization.
     * All targets stored as percentages, converted to absolute values at draw time.
     *
     * For distance-based steps, durationType and durationMeters are used to recalculate
     * segment duration dynamically when pace changes (via speed coefficient).
     */
    /** Workout phase for stitched warmup/main/cooldown visualization. */
    enum class WorkoutPhase { WARMUP, MAIN, COOLDOWN }

    data class PlannedSegment(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val stepIndex: Int,
        val stepName: String,
        val paceKph: Double,
        val inclinePercent: Double,
        val hrTargetMinPercent: Double? = null,   // HR target as % of LTHR (1 decimal precision)
        val hrTargetMaxPercent: Double? = null,   // HR target as % of LTHR (1 decimal precision)
        val powerTargetMinPercent: Double? = null, // Power target as % of FTP (1 decimal precision)
        val powerTargetMaxPercent: Double? = null, // Power target as % of FTP (1 decimal precision)
        val autoAdjustMode: AutoAdjustMode = AutoAdjustMode.NONE,
        val durationType: DurationType = DurationType.TIME,  // For dynamic duration recalculation
        val durationMeters: Int? = null,  // Distance for distance-based steps
        val phase: WorkoutPhase = WorkoutPhase.MAIN,
        val stepIdentityKey: String = ""  // For per-step coefficient lookup in ONE_STEP mode
    ) {
        /** Get HR min target in BPM using provided LTHR. */
        fun getHrTargetMinBpm(lthrBpm: Int): Int? =
            hrTargetMinPercent?.let { HeartRateZones.percentToBpm(it, lthrBpm) }

        /** Get HR max target in BPM using provided LTHR. */
        fun getHrTargetMaxBpm(lthrBpm: Int): Int? =
            hrTargetMaxPercent?.let { HeartRateZones.percentToBpm(it, lthrBpm) }

        /** Get Power min target in Watts using provided FTP. */
        fun getPowerTargetMinWatts(ftpWatts: Int): Int? =
            powerTargetMinPercent?.let { PowerZones.percentToWatts(it, ftpWatts) }

        /** Get Power max target in Watts using provided FTP. */
        fun getPowerTargetMaxWatts(ftpWatts: Int): Int? =
            powerTargetMaxPercent?.let { PowerZones.percentToWatts(it, ftpWatts) }
    }
    private var plannedSegments: List<PlannedSegment> = emptyList()

    // Adjustment coefficients for showing adjusted targets
    // Past segments (< currentStepIndex) are not drawn since actual data covers them
    // Current and future segments are drawn with coefficient applied
    private var currentStepIndex: Int = 0
    private var previousStepIndex: Int = -1  // Track step changes to allow timeline shrinking on skip
    private var speedCoefficient: Double = 1.0
    private var inclineCoefficient: Double = 1.0
    private var perStepCoefficients: Map<String, Pair<Double, Double>>? = null  // ONE_STEP mode per-segment lookup
    private var currentStepElapsedMs: Long = 0  // How long we've been in current step
    private var currentStepActualStartMs: Long = 0  // When current step actually started (captured on step change)
    private var currentWorkoutElapsedMs: Long = 0  // Total workout elapsed from engine (more accurate than data points)

    // Threshold values for converting percentages to absolute values
    var lthrBpm: Int = 170  // Lactate Threshold HR for converting HR percentages to BPM
        set(value) {
            field = value
            // Recalculate HR scale based on new LTHR
            val newInitialHrMax = calculateInitialHrMax()
            if (newInitialHrMax > hrMaxBpm) {
                hrMaxBpm = newInitialHrMax
            }
            invalidate()
        }
    var ftpWatts: Int = 250  // Functional Threshold Power for converting Power percentages to Watts
        set(value) {
            field = value
            // Ensure power scale is high enough to show zone boundaries
            ensurePowerScaleShowsZones()
            invalidate()
        }

    /** HR scale minimum BPM (default 60, use higher value like 50% of LTHR for compact preview) */
    // Use backing field so animateScales() can update without corrupting target
    private var _hrMinBpm: Double = HR_DEFAULT_MIN_BPM
    var hrMinBpm: Double
        get() = _hrMinBpm
        set(value) {
            _hrMinBpm = value
            targetHrMinBpm = value
            invalidate()
        }

    /**
     * Apply all user settings to this chart.
     * Use this instead of setting lthrBpm, ftpWatts, setHRZones, setPowerZones separately.
     */
    fun applyUserSettings(settings: ChartUserSettings) {
        // Set scale minimum first
        hrMinBpm = settings.hrMinBpm.toDouble()
        // Set thresholds (they affect zone scale calculations)
        lthrBpm = settings.lthrBpm
        ftpWatts = settings.ftpWatts
        setHRZones(
            settings.hrZone2Start,
            settings.hrZone3Start,
            settings.hrZone4Start,
            settings.hrZone5Start
        )
        setPowerZones(
            settings.powerZone2Start,
            settings.powerZone3Start,
            settings.powerZone4Start,
            settings.powerZone5Start
        )
    }

    // Dimensions from resources
    private val leftAxisWidth: Float
    private val rightAxisWidth: Float
    private val bottomAxisHeight: Float
    private val lineWidth: Float
    private val axisLabelSize: Float
    private val zoneBoundaryWidth: Float
    private val topPadding: Float
    private val gridStrokeWidth: Float
    private val powerStripeWidth: Float

    // Colors from resources
    private val backgroundColor: Int
    private val speedColor: Int
    private val inclineColor: Int
    private val gridColor: Int
    private val axisLabelColor: Int
    private val targetSpeedColor: Int
    private val targetInclineColor: Int
    private val hrTargetRectColor: Int
    private val powerColor: Int
    private val powerTargetRectColor: Int
    private val powerStripeOverlayColor: Int

    // Paints
    private val backgroundPaint: Paint
    private val speedPaint: Paint
    private val inclinePaint: Paint
    private val hrPaint: Paint
    private val gridPaint: Paint
    private val axisLabelPaint: Paint
    private val axisTitlePaint: Paint
    private val hrZoneBoundaryPaint: Paint
    private val targetSpeedPaint: Paint
    private val targetInclinePaint: Paint
    private val hrTargetRectPaint: Paint
    private val powerPaint: Paint
    private val powerTargetRectPaint: Paint
    private val powerStripeOverlayPaint: Paint
    private val markerPaint: Paint
    private val phaseBackgroundPaint: Paint
    private val phaseXAxisPaint: Paint

    // Phase tint colors
    private val phaseWarmupTintColor: Int
    private val phaseCooldownTintColor: Int
    private val phaseWarmupBorderColor: Int
    private val phaseCooldownBorderColor: Int

    // Pre-allocated objects for draw path (avoid GC pressure in onDraw)
    private val clipRect = RectF()
    private val stripeSpacingPx = 8f * resources.displayMetrics.density

    init {
        // Load phase colors
        phaseWarmupTintColor = ContextCompat.getColor(context, R.color.phase_warmup_tint)
        phaseCooldownTintColor = ContextCompat.getColor(context, R.color.phase_cooldown_tint)
        phaseWarmupBorderColor = ContextCompat.getColor(context, R.color.step_warmup)
        phaseCooldownBorderColor = ContextCompat.getColor(context, R.color.step_cooldown)
        // Load dimensions
        leftAxisWidth = resources.getDimensionPixelSize(R.dimen.chart_left_axis_width).toFloat()
        rightAxisWidth = resources.getDimensionPixelSize(R.dimen.chart_right_axis_width).toFloat()
        bottomAxisHeight = resources.getDimensionPixelSize(R.dimen.chart_bottom_axis_height).toFloat()
        lineWidth = resources.getDimensionPixelSize(R.dimen.chart_line_width).toFloat()
        axisLabelSize = resources.getDimension(R.dimen.chart_axis_label_size)
        zoneBoundaryWidth = resources.getDimensionPixelSize(R.dimen.chart_zone_boundary_width).toFloat()
        topPadding = resources.getDimensionPixelSize(R.dimen.chart_top_padding).toFloat()
        gridStrokeWidth = resources.getDimensionPixelSize(R.dimen.chart_grid_stroke_width).toFloat()
        powerStripeWidth = resources.getDimensionPixelSize(R.dimen.chart_power_stripe_width).toFloat()

        // Load colors
        backgroundColor = ContextCompat.getColor(context, R.color.chart_background)
        speedColor = ContextCompat.getColor(context, R.color.chart_speed)
        inclineColor = ContextCompat.getColor(context, R.color.chart_incline)
        gridColor = ContextCompat.getColor(context, R.color.chart_grid)
        axisLabelColor = ContextCompat.getColor(context, R.color.chart_axis_label)
        targetSpeedColor = ContextCompat.getColor(context, R.color.chart_speed_dim)
        targetInclineColor = ContextCompat.getColor(context, R.color.chart_incline_dim)
        hrTargetRectColor = ContextCompat.getColor(context, R.color.chart_hr_target_rect)
        powerColor = ContextCompat.getColor(context, R.color.chart_power)
        powerTargetRectColor = ContextCompat.getColor(context, R.color.chart_power_target_rect)
        powerStripeOverlayColor = ContextCompat.getColor(context, R.color.chart_power_stripe_overlay)

        // Cache zone colors (must be after backgroundColor is set)
        rebuildZoneColorCache()

        // Initialize paints
        backgroundPaint = Paint().apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }

        speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = speedColor
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        inclinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inclineColor
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        hrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth  // 1x thickness, drawn on top of power
            strokeCap = Paint.Cap.ROUND
        }

        gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            style = Paint.Style.STROKE
            strokeWidth = gridStrokeWidth
        }

        axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = axisLabelColor
            textSize = axisLabelSize
            textAlign = Paint.Align.CENTER
        }

        axisTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = axisLabelColor
            textSize = axisLabelSize * 0.9f
            textAlign = Paint.Align.CENTER
        }

        hrZoneBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = zoneBoundaryWidth
        }

        // Target line paints (dotted, same thickness as actual data lines but duller)
        // Short dash pattern for dotted appearance
        val dotLength = lineWidth * 2
        val gapLength = lineWidth * 3
        targetSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = targetSpeedColor
            style = Paint.Style.STROKE
            strokeWidth = lineWidth  // Same as actual data lines
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(dotLength, gapLength), 0f)
        }

        targetInclinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = targetInclineColor
            style = Paint.Style.STROKE
            strokeWidth = lineWidth  // Same as actual data lines
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(dotLength, gapLength), 0f)
        }

        // HR target rectangle paint (filled semi-transparent)
        hrTargetRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hrTargetRectColor
            style = Paint.Style.FILL
        }

        // Power line paint - solid, wider, dimmed colors (drawn under HR)
        powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth * 2f  // 2x thickness
            strokeCap = Paint.Cap.ROUND
        }

        // Power target rectangle paint (filled semi-transparent like HR)
        powerTargetRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = powerTargetRectColor
            style = Paint.Style.FILL
        }

        // Diagonal stripe overlay for power zones (to distinguish from HR zones)
        powerStripeOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = powerStripeOverlayColor
            style = Paint.Style.STROKE
            strokeWidth = powerStripeWidth
        }

        markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        phaseBackgroundPaint = Paint().apply {
            style = Paint.Style.FILL
        }

        phaseXAxisPaint = Paint(gridPaint).apply {
            strokeWidth = gridStrokeWidth * 2.5f
        }
    }

    /**
     * Calculate initial HR max: zone5Start * 1.1, rounded up to nearest 10 BPM.
     * Ensures result is always greater than hrMinBpm to prevent invalid scale.
     */
    private fun calculateInitialHrMax(): Double {
        val raw = hrZone5Start * 1.1
        val calculated = ceil(raw / 10.0) * 10.0
        // Ensure at least 40 BPM above hrMinBpm to create a usable scale
        val minimum = hrMinBpm + 40.0
        return maxOf(calculated, minimum)
    }

    /**
     * Set the workout data to display.
     */
    fun setData(data: List<WorkoutDataPoint>) {
        val oldSize = dataPoints.size
        dataPoints = data

        // Log chart settings when first data arrives (debug)
        if (oldSize == 0 && data.isNotEmpty()) {
            val firstPt = data.first()
            android.util.Log.d("WorkoutChart", "First data: speed=${firstPt.speedKph}, power=${firstPt.powerWatts}, " +
                "settings: lthrBpm=$lthrBpm, ftpWatts=$ftpWatts, " +
                "hrMin=$hrMinBpm, hrMax=$hrMaxBpm, z1=$hrZone2Start, z4=$hrZone5Start, " +
                "speedScale=$speedMinKph-$speedMaxKph")
        }

        // Parse step boundaries from data points for drawing past steps' HR targets
        // Only reparse if we have structured workout data (stepIndex >= 0)
        if (data.isNotEmpty() && data.any { it.stepIndex >= 0 }) {
            actualStepBoundaries = StepBoundaryParser.parseStepBoundaries(data)
        } else if (data.isEmpty()) {
            actualStepBoundaries = emptyList()
        }

        if (data.isEmpty()) {
            // Empty data - clear all paths
            if (oldSize > 0) {
                rebuildAllPaths()
            }
        } else {
            // Check if time range needs to expand (only in free run mode)
            val maxMs = data.maxOf { it.elapsedMs }
            val timeRangeChanged = checkAndExpandTimeRange(maxMs)

            // Calculate live scales based on mode (sets target values)
            if (isFullScaleMode) {
                calculateLiveFullScale()
            } else {
                calculateLiveSmartScale()
            }

            // Check if animated values differ from targets (animation needed)
            val scaleAnimating = speedMinKph != targetSpeedMinKph || speedMaxKph != targetSpeedMaxKph ||
                inclineMinPercent != targetInclineMinPercent || inclineMaxPercent != targetInclineMaxPercent ||
                _hrMinBpm != targetHrMinBpm || hrMaxBpm != targetHrMaxBpm

            // Rebuild paths if scale is animating, time range changed, data shrunk, or first data
            // Must rebuild when scale differs because old points have wrong Y coordinates
            if (scaleAnimating || timeRangeChanged || data.size < oldSize || oldSize == 0) {
                rebuildAllPaths()
            } else {
                updatePaths()
            }
        }

        invalidate()
    }

    /**
     * Clear all data while preserving scale settings in workout mode.
     * In workout mode, scales are preserved to maintain planned segment visualization.
     * In free run mode, scales are reset to initial values.
     */
    fun clearData() {
        dataPoints = emptyList()
        actualStepBoundaries = emptyList()
        speedPath.reset()
        inclinePath.reset()
        hrSegments.clear()
        powerSegments.clear()
        lastProcessedIndex = -1

        // Only reset scales and time range in free run mode.
        // In workout mode, preserve scales set by setPlannedSegments() so planned
        // segment lines remain correctly positioned.
        if (!isWorkoutMode) {
            speedMinKph = SPEED_MIN_KPH
            speedMaxKph = SPEED_INITIAL_MAX_KPH
            inclineMinPercent = INCLINE_INITIAL_MIN_PERCENT
            inclineMaxPercent = INCLINE_INITIAL_MAX_PERCENT
            hrMinBpm = HR_DEFAULT_MIN_BPM
            hrMaxBpm = calculateInitialHrMax()
            powerMaxWatts = POWER_INITIAL_MAX_WATTS
            timeRangeMinutes = INITIAL_TIME_RANGE_MINUTES

            // Reset target values to match
            targetSpeedMinKph = speedMinKph
            targetSpeedMaxKph = speedMaxKph
            targetInclineMinPercent = inclineMinPercent
            targetInclineMaxPercent = inclineMaxPercent
            targetHrMinBpm = hrMinBpm
            targetHrMaxBpm = hrMaxBpm
        }

        invalidate()
    }

    /**
     * Configure HR zone thresholds (zone start boundaries).
     */
    fun setHRZones(z2Start: Int, z3Start: Int, z4Start: Int, z5Start: Int) {
        hrZone2Start = z2Start
        hrZone3Start = z3Start
        hrZone4Start = z4Start
        hrZone5Start = z5Start
        rebuildZoneColorCache()
        // Recalculate initial HR max based on new zone5Start
        val newInitialHrMax = calculateInitialHrMax()
        if (newInitialHrMax > hrMaxBpm) {
            hrMaxBpm = newInitialHrMax
        }
        rebuildAllPaths()
        invalidate()
    }

    /**
     * Configure Power zone thresholds (zone start boundaries as absolute watts).
     */
    fun setPowerZones(z2Start: Int, z3Start: Int, z4Start: Int, z5Start: Int) {
        powerZone2Start = z2Start
        powerZone3Start = z3Start
        powerZone4Start = z4Start
        powerZone5Start = z5Start
        rebuildZoneColorCache()
        // Ensure power scale is high enough to show zone5Start
        ensurePowerScaleShowsZones()
        rebuildAllPaths()
        invalidate()
    }

    /**
     * Ensure power scale is high enough to show all zone boundaries.
     * Zone4max (typically 105% of FTP) must be visible on the scale.
     */
    private fun ensurePowerScaleShowsZones() {
        // Add 10% margin above zone4max, rounded up to nearest POWER_EXPAND_STEP_WATTS
        val requiredMax = powerZone5Start * 1.1
        if (requiredMax > powerMaxWatts) {
            powerMaxWatts = ceil(requiredMax / POWER_EXPAND_STEP_WATTS) * POWER_EXPAND_STEP_WATTS
        }
    }

    /**
     * Calculate optimal scales for editor preview mode.
     * Creates tight-fitting scales for all 4 metrics based on workout structure.
     */
    private fun calculateEditorScales(segments: List<PlannedSegment>) {
        // Speed scale: from workout step targets
        val minSpeed = segments.minOfOrNull { it.paceKph } ?: 5.0
        val maxSpeed = segments.maxOfOrNull { it.paceKph } ?: 15.0
        // Subtract 1 kph from min, floor to multiple of 2 kph
        speedMinKph = floor((minSpeed - 1.0) / 2.0) * 2.0
        // Add 1 kph to max, ceil to multiple of 2 kph
        speedMaxKph = ceil((maxSpeed + 1.0) / 2.0) * 2.0
        // Ensure minimum range of 4 kph
        if (speedMaxKph - speedMinKph < 4.0) {
            speedMaxKph = speedMinKph + 4.0
        }
        targetSpeedMinKph = speedMinKph
        targetSpeedMaxKph = speedMaxKph

        // Incline scale: from workout step targets
        val minIncline = segments.minOfOrNull { it.inclinePercent } ?: 0.0
        val maxIncline = segments.maxOfOrNull { it.inclinePercent } ?: 5.0
        // Add 1% margin, floor/ceil to integers
        inclineMinPercent = floor(minIncline - 1.0)
        inclineMaxPercent = ceil(maxIncline + 1.0)
        // Ensure minimum range of 3%
        if (inclineMaxPercent - inclineMinPercent < 3.0) {
            inclineMaxPercent = inclineMinPercent + 3.0
        }
        targetInclineMinPercent = inclineMinPercent
        targetInclineMaxPercent = inclineMaxPercent

        // HR scale: must show all 4 zone boundary lines + HR targets + Power targets from steps
        // Power targets are converted to normalized BPM: (watts / ftpWatts) * lthrBpm
        val zoneMin = hrZone2Start.toDouble()
        val zoneMax = hrZone5Start.toDouble()
        val hrTargetMin = segments.mapNotNull { it.getHrTargetMinBpm(lthrBpm) }.minOrNull()?.toDouble() ?: zoneMin
        val hrTargetMax = segments.mapNotNull { it.getHrTargetMaxBpm(lthrBpm) }.maxOrNull()?.toDouble() ?: zoneMax

        // Include power targets (converted to normalized BPM)
        val powerTargetMinWatts = segments.mapNotNull { it.getPowerTargetMinWatts(ftpWatts) }.minOrNull()
        val powerTargetMaxWatts = segments.mapNotNull { it.getPowerTargetMaxWatts(ftpWatts) }.maxOrNull()
        val powerTargetMinBpm = powerTargetMinWatts?.let { (it / ftpWatts.toDouble()) * lthrBpm } ?: zoneMin
        val powerTargetMaxBpm = powerTargetMaxWatts?.let { (it / ftpWatts.toDouble()) * lthrBpm } ?: zoneMax

        // Calculate range with small margin
        val dataMin = minOf(zoneMin, hrTargetMin, powerTargetMinBpm)
        val dataMax = maxOf(zoneMax, hrTargetMax, powerTargetMaxBpm)
        val margin = (dataMax - dataMin) * 0.05

        // Add 5% margin above and below, round to nearest 10 BPM
        hrMinBpm = floor((dataMin - margin) / 10.0) * 10.0
        hrMaxBpm = ceil((dataMax + margin) / 10.0) * 10.0
        // Ensure minimum range of 40 BPM
        if (hrMaxBpm - hrMinBpm < 40.0) {
            hrMaxBpm = hrMinBpm + 40.0
        }
        targetHrMinBpm = hrMinBpm
        targetHrMaxBpm = hrMaxBpm

        android.util.Log.d("WorkoutChart", "Editor scales: speed=$speedMinKph-$speedMaxKph, " +
            "incline=$inclineMinPercent-$inclineMaxPercent, hr=$hrMinBpm-$hrMaxBpm")
    }

    /**
     * Calculate smart auto-fit scales for live chart.
     * Shows: workout structure + last 3 minutes of live data + zone boundaries.
     */
    private fun calculateLiveSmartScale() {
        // Get last 3 minutes of data
        val threeMinMs = 180_000L
        val currentTimeMs = dataPoints.lastOrNull()?.elapsedMs ?: 0L
        val recentData = dataPoints.filter { it.elapsedMs >= currentTimeMs - threeMinMs }

        // Speed scale: workout structure + recent data
        val workoutMinSpeed = plannedSegments.minOfOrNull { it.paceKph } ?: SPEED_INITIAL_MAX_KPH
        val workoutMaxSpeed = plannedSegments.maxOfOrNull { it.paceKph } ?: SPEED_INITIAL_MAX_KPH
        val recentMinSpeed = recentData.minOfOrNull { it.speedKph } ?: workoutMinSpeed
        val recentMaxSpeed = recentData.maxOfOrNull { it.speedKph } ?: workoutMaxSpeed

        targetSpeedMinKph = floor((minOf(workoutMinSpeed, recentMinSpeed) - 1.0) / 2.0) * 2.0
        targetSpeedMaxKph = ceil((maxOf(workoutMaxSpeed, recentMaxSpeed) + 1.0) / 2.0) * 2.0
        if (targetSpeedMaxKph - targetSpeedMinKph < 4.0) {
            targetSpeedMaxKph = targetSpeedMinKph + 4.0
        }

        // Incline scale: workout structure + recent data
        val workoutMinIncline = plannedSegments.minOfOrNull { it.inclinePercent } ?: INCLINE_INITIAL_MIN_PERCENT
        val workoutMaxIncline = plannedSegments.maxOfOrNull { it.inclinePercent } ?: INCLINE_INITIAL_MAX_PERCENT
        val recentMinIncline = recentData.minOfOrNull { it.inclinePercent } ?: workoutMinIncline
        val recentMaxIncline = recentData.maxOfOrNull { it.inclinePercent } ?: workoutMaxIncline

        targetInclineMinPercent = floor(minOf(workoutMinIncline, recentMinIncline) - 1.0)
        targetInclineMaxPercent = ceil(maxOf(workoutMaxIncline, recentMaxIncline) + 1.0)
        if (targetInclineMaxPercent - targetInclineMinPercent < 3.0) {
            targetInclineMaxPercent = targetInclineMinPercent + 3.0
        }

        // HR scale: ALWAYS include zone boundaries + workout HR targets + recent HR data + recent Power data
        // Power is mapped to HR scale via: normalizedBpm = (watts / ftpWatts) * lthrBpm
        val zoneMin = hrZone2Start.toDouble()
        val zoneMax = hrZone5Start.toDouble()
        val workoutHrMin = plannedSegments.mapNotNull { it.getHrTargetMinBpm(lthrBpm) }.minOrNull()?.toDouble() ?: zoneMin
        val workoutHrMax = plannedSegments.mapNotNull { it.getHrTargetMaxBpm(lthrBpm) }.maxOrNull()?.toDouble() ?: zoneMax
        val recentHrMin = recentData.minOfOrNull { it.heartRateBpm } ?: zoneMin
        val recentHrMax = recentData.maxOfOrNull { it.heartRateBpm } ?: zoneMax

        // Include power data (converted to normalized BPM) - filter out zero values
        val recentPowerData = recentData.filter { it.powerWatts > 0 }
        val recentPowerMinBpm = recentPowerData.minOfOrNull { (it.powerWatts / ftpWatts.toDouble()) * lthrBpm } ?: zoneMin
        val recentPowerMaxBpm = recentPowerData.maxOfOrNull { (it.powerWatts / ftpWatts.toDouble()) * lthrBpm } ?: zoneMax

        val dataMin = minOf(zoneMin, workoutHrMin, recentHrMin, recentPowerMinBpm)
        val dataMax = maxOf(zoneMax, workoutHrMax, recentHrMax, recentPowerMaxBpm)
        val margin = (dataMax - dataMin) * 0.05

        targetHrMinBpm = floor((dataMin - margin) / 10.0) * 10.0
        targetHrMaxBpm = ceil((dataMax + margin) / 10.0) * 10.0
        if (targetHrMaxBpm - targetHrMinBpm < 40.0) {
            targetHrMaxBpm = targetHrMinBpm + 40.0
        }
    }

    /**
     * Calculate full scale to show ALL data for the entire run.
     * In workout mode, also considers planned segments so workout outline isn't clipped.
     */
    private fun calculateLiveFullScale() {
        if (dataPoints.isEmpty()) return

        // Speed: all data for this run + workout structure (so planned segments aren't clipped)
        val dataMinSpeed = dataPoints.minOf { it.speedKph }
        val dataMaxSpeed = dataPoints.maxOf { it.speedKph }
        val workoutMinSpeed = plannedSegments.minOfOrNull { it.paceKph } ?: dataMinSpeed
        val workoutMaxSpeed = plannedSegments.maxOfOrNull { it.paceKph } ?: dataMaxSpeed
        val minSpeed = minOf(dataMinSpeed, workoutMinSpeed)
        val maxSpeed = maxOf(dataMaxSpeed, workoutMaxSpeed)
        targetSpeedMinKph = floor((minSpeed - 1.0) / 2.0) * 2.0
        targetSpeedMaxKph = ceil((maxSpeed + 1.0) / 2.0) * 2.0
        if (targetSpeedMaxKph - targetSpeedMinKph < 4.0) {
            targetSpeedMaxKph = targetSpeedMinKph + 4.0
        }

        // Incline: all data for this run + workout structure (so planned segments aren't clipped)
        val dataMinIncline = dataPoints.minOf { it.inclinePercent }
        val dataMaxIncline = dataPoints.maxOf { it.inclinePercent }
        val workoutMinIncline = plannedSegments.minOfOrNull { it.inclinePercent } ?: dataMinIncline
        val workoutMaxIncline = plannedSegments.maxOfOrNull { it.inclinePercent } ?: dataMaxIncline
        val minIncline = minOf(dataMinIncline, workoutMinIncline)
        val maxIncline = maxOf(dataMaxIncline, workoutMaxIncline)
        targetInclineMinPercent = floor(minIncline - 1.0)
        targetInclineMaxPercent = ceil(maxIncline + 1.0)
        if (targetInclineMaxPercent - targetInclineMinPercent < 3.0) {
            targetInclineMaxPercent = targetInclineMinPercent + 3.0
        }

        // HR: all data + zone boundaries + power data (converted to normalized BPM)
        val hrDataMin = dataPoints.minOf { it.heartRateBpm }
        val hrDataMax = dataPoints.maxOf { it.heartRateBpm }

        // Include power data (converted to normalized BPM) - filter out zero values
        val powerData = dataPoints.filter { it.powerWatts > 0 }
        val powerMinBpm = powerData.minOfOrNull { (it.powerWatts / ftpWatts.toDouble()) * lthrBpm } ?: hrDataMin
        val powerMaxBpm = powerData.maxOfOrNull { (it.powerWatts / ftpWatts.toDouble()) * lthrBpm } ?: hrDataMax

        val hrMin = minOf(hrZone2Start.toDouble(), hrDataMin, powerMinBpm)
        val hrMax = maxOf(hrZone5Start.toDouble(), hrDataMax, powerMaxBpm)
        val margin = (hrMax - hrMin) * 0.05
        targetHrMinBpm = floor((hrMin - margin) / 10.0) * 10.0
        targetHrMaxBpm = ceil((hrMax + margin) / 10.0) * 10.0
        if (targetHrMaxBpm - targetHrMinBpm < 40.0) {
            targetHrMaxBpm = targetHrMinBpm + 40.0
        }
    }

    /**
     * Animate scales smoothly towards target values.
     * Called from onDraw() to create smooth transitions.
     * Returns true if animation is still in progress.
     */
    private fun animateScales(): Boolean {
        val animationFactor = 0.1  // Move 10% closer each frame (~500ms to settle at 60fps)

        var animating = false
        val threshold = 0.01  // Stop animating when within this threshold (avoids floating-point precision issues)

        // Speed scale
        if (kotlin.math.abs(speedMinKph - targetSpeedMinKph) > threshold) {
            speedMinKph += (targetSpeedMinKph - speedMinKph) * animationFactor
            animating = true
        } else {
            speedMinKph = targetSpeedMinKph
        }
        if (kotlin.math.abs(speedMaxKph - targetSpeedMaxKph) > threshold) {
            speedMaxKph += (targetSpeedMaxKph - speedMaxKph) * animationFactor
            animating = true
        } else {
            speedMaxKph = targetSpeedMaxKph
        }

        // Incline scale
        if (kotlin.math.abs(inclineMinPercent - targetInclineMinPercent) > threshold) {
            inclineMinPercent += (targetInclineMinPercent - inclineMinPercent) * animationFactor
            animating = true
        } else {
            inclineMinPercent = targetInclineMinPercent
        }
        if (kotlin.math.abs(inclineMaxPercent - targetInclineMaxPercent) > threshold) {
            inclineMaxPercent += (targetInclineMaxPercent - inclineMaxPercent) * animationFactor
            animating = true
        } else {
            inclineMaxPercent = targetInclineMaxPercent
        }

        // HR scale (use backing field to avoid corrupting target via setter)
        if (kotlin.math.abs(_hrMinBpm - targetHrMinBpm) > threshold) {
            _hrMinBpm += (targetHrMinBpm - _hrMinBpm) * animationFactor
            animating = true
        } else {
            _hrMinBpm = targetHrMinBpm
        }
        if (kotlin.math.abs(hrMaxBpm - targetHrMaxBpm) > threshold) {
            hrMaxBpm += (targetHrMaxBpm - hrMaxBpm) * animationFactor
            animating = true
        } else {
            hrMaxBpm = targetHrMaxBpm
        }

        return animating
    }

    /**
     * Set full scale mode (shows all data vs smart auto-fit).
     * @param fullScale true for full scale, false for smart auto-fit
     */
    fun setFullScaleMode(fullScale: Boolean) {
        isFullScaleMode = fullScale
        // Recalculate scales based on new mode
        if (dataPoints.isNotEmpty()) {
            if (isFullScaleMode) {
                calculateLiveFullScale()
            } else {
                calculateLiveSmartScale()
            }
        }
        invalidate()
    }

    /**
     * Set planned segments for workout visualization per spec Section 4.4.
     * Segments should be sorted by start time and have valid end times.
     * Includes HR target ranges for drawing HR target rectangles.
     * Enables workout mode which fixes the time range to show the entire workout.
     */
    fun setPlannedSegments(segments: List<PlannedSegment>) {
        plannedSegments = segments
        android.util.Log.d("WorkoutChart", "setPlannedSegments: ${segments.size} segments received")
        // Log first few segments' times for debugging
        segments.take(5).forEachIndexed { i, seg ->
            android.util.Log.d("WorkoutChart", "  segment[$i]: ${seg.startTimeMs}ms - ${seg.endTimeMs}ms (${(seg.endTimeMs - seg.startTimeMs)/1000}s)")
        }

        if (segments.isNotEmpty()) {
            // Clear any existing data when setting up a new workout
            dataPoints = emptyList()
            speedPath.reset()
            inclinePath.reset()
            hrSegments.clear()
            powerSegments.clear()
            lastProcessedIndex = -1

            // Reset step tracking for timeline shrink detection
            currentStepIndex = 0
            previousStepIndex = -1
            speedCoefficient = 1.0
            inclineCoefficient = 1.0
            perStepCoefficients = null

            // Enable workout mode and set fixed time range to workout duration
            isWorkoutMode = true
            val totalDurationMs = segments.maxOf { it.endTimeMs }
            // Round up to nearest 5 minutes, minimum 5 minutes
            // Use ceil to ensure we have enough time to show all data (5:59 needs 6 min, not 5)
            val minMinutes = ceil(totalDurationMs / 60000.0).toInt()
            workoutDurationMinutes = max(5, ((minMinutes + 4) / 5) * 5)
            timeRangeMinutes = workoutDurationMinutes
            android.util.Log.d("WorkoutChart", "Workout mode enabled, duration: ${workoutDurationMinutes}min")

            // Calculate optimal scales for all 4 metrics based on workout structure
            calculateEditorScales(segments)

            // Expand Power scale if needed for Power targets (kept separately from editor scales)
            val maxPowerTarget = segments.mapNotNull { it.getPowerTargetMaxWatts(ftpWatts) }.maxOrNull()
            if (maxPowerTarget != null && maxPowerTarget > powerMaxWatts) {
                powerMaxWatts = ceil(maxPowerTarget.toDouble() / POWER_EXPAND_STEP_WATTS) * POWER_EXPAND_STEP_WATTS
                android.util.Log.d("WorkoutChart", "Expanded Power scale to $powerMaxWatts")
            }

            rebuildAllPaths()
        }

        invalidate()
    }

    /**
     * Update adjustment coefficients for drawing future step targets.
     * Past steps (index < currentStepIndex) are not drawn since actual data covers them.
     * Current and future steps are drawn with coefficient applied and positioned
     * relative to actual execution time (handles overrun/underrun from Prev/Next).
     *
     * @param stepIndex Current step index (0-based)
     * @param speedCoeff Speed adjustment coefficient (1.0 = no adjustment)
     * @param inclineCoeff Incline adjustment coefficient (1.0 = no adjustment)
     * @param stepElapsedMs How long we've been in the current step (for positioning)
     * @param workoutElapsedMs Total elapsed workout time from engine (avoids stale data point timing)
     */
    fun setAdjustmentCoefficients(
        stepIndex: Int,
        speedCoeff: Double,
        inclineCoeff: Double,
        stepElapsedMs: Long = 0,
        workoutElapsedMs: Long = 0,
        perStepCoefficients: Map<String, Pair<Double, Double>>? = null
    ) {
        // Use engine's elapsed time (accurate) rather than data points (can be stale)
        val currentElapsedMs = if (workoutElapsedMs > 0) workoutElapsedMs else dataPoints.lastOrNull()?.elapsedMs ?: 0L

        // Always calculate start time from elapsed times - handles both step changes
        // and step restarts (e.g., pressing Prev on first step)
        currentStepActualStartMs = currentElapsedMs - stepElapsedMs

        // Detect step changes (Next/Prev button) to allow timeline shrinking
        val stepChanged = stepIndex != previousStepIndex
        previousStepIndex = stepIndex

        currentStepIndex = stepIndex
        speedCoefficient = speedCoeff
        inclineCoefficient = inclineCoeff
        this.perStepCoefficients = perStepCoefficients
        currentStepElapsedMs = stepElapsedMs
        currentWorkoutElapsedMs = currentElapsedMs

        // Update timeline - allow shrinking only on step change (not during speed adjustments)
        if (isWorkoutMode && plannedSegments.isNotEmpty()) {
            updateTimelineForActualDuration(allowShrink = stepChanged)
        }

        invalidate()
    }

    /**
     * Resolve speed/incline coefficients for a specific segment.
     * Coefficients never cross phase boundaries (warmup/main/cooldown).
     * In ALL_STEPS mode (perStepCoefficients == null): uses global coefficients within same phase.
     * In ONE_STEP mode: current step uses active coefficients, others use per-step map lookup.
     */
    private fun getSegmentCoefficients(segment: PlannedSegment, index: Int): Pair<Double, Double> {
        // Coefficients don't cross phase boundaries — segments in a different phase use 1.0
        val currentPhase = plannedSegments.getOrNull(currentStepIndex)?.phase
        if (currentPhase != null && segment.phase != currentPhase) return Pair(1.0, 1.0)

        val map = perStepCoefficients ?: return Pair(speedCoefficient, inclineCoefficient)
        // Current step: use active coefficients (freshest from telemetry)
        if (index == currentStepIndex) return Pair(speedCoefficient, inclineCoefficient)
        // Future step: look up by identity key in map
        return map[segment.stepIdentityKey] ?: Pair(1.0, 1.0)
    }

    /**
     * Update the timeline range based on expected actual workout end time.
     * When steps overrun, the workout extends past the original planned duration.
     * For distance-based steps, uses adjusted durations based on speed coefficient.
     *
     * @param allowShrink If true, allows timeline to contract (only on step changes, not speed adjustments)
     */
    private fun updateTimelineForActualDuration(allowShrink: Boolean) {
        val currentSegment = plannedSegments.getOrNull(currentStepIndex) ?: return
        val currentSegmentAdjustedDuration = calculateAdjustedSegmentDurationMs(currentSegment, speedCoefficient)

        // Calculate where current step will actually end (based on adjusted duration)
        val currentStepActualEnd = maxOf(
            currentStepActualStartMs + currentSegmentAdjustedDuration,
            currentWorkoutElapsedMs
        )

        // Calculate remaining adjusted duration from future steps
        val remainingAdjustedMs = plannedSegments.withIndex()
            .drop(currentStepIndex + 1)
            .sumOf { (idx, seg) ->
                val segSpeedCoeff = getSegmentCoefficients(seg, idx).first
                calculateAdjustedSegmentDurationMs(seg, segSpeedCoeff)
            }

        // Expected actual end time (with adjusted durations)
        val expectedEndMs = currentStepActualEnd + remainingAdjustedMs

        // Update timeline if needed (round up to nearest 5 minutes)
        val expectedEndMinutes = (expectedEndMs / 60000.0).toInt() + 1
        val newRangeMinutes = ((expectedEndMinutes + 4) / 5) * 5

        // Expand always; shrink only when step changed (user pressed Next/Prev)
        val shouldExpand = newRangeMinutes > timeRangeMinutes
        val shouldShrink = allowShrink && newRangeMinutes < timeRangeMinutes

        if (shouldExpand || shouldShrink) {
            val action = if (shouldExpand) "expanded" else "contracted"
            timeRangeMinutes = newRangeMinutes
            android.util.Log.d("WorkoutChart", "Timeline $action to ${timeRangeMinutes}min (expected end: ${expectedEndMs/1000}s)")
            // Rebuild paths with new X coordinates
            rebuildAllPaths()
        }
    }

    /**
     * Clear planned segments and return to free run mode.
     * Time range will expand dynamically as the run progresses.
     * Resets all scales to their initial free run values.
     */
    fun clearPlannedSegments() {
        plannedSegments = emptyList()
        isWorkoutMode = false
        workoutDurationMinutes = 0
        // Reset step tracking
        currentStepIndex = 0
        previousStepIndex = -1
        speedCoefficient = 1.0
        inclineCoefficient = 1.0
        perStepCoefficients = null
        // Reset time range and all scales to initial values for free run mode
        timeRangeMinutes = INITIAL_TIME_RANGE_MINUTES
        speedMinKph = SPEED_MIN_KPH
        speedMaxKph = SPEED_INITIAL_MAX_KPH
        inclineMinPercent = INCLINE_INITIAL_MIN_PERCENT
        inclineMaxPercent = INCLINE_INITIAL_MAX_PERCENT
        hrMinBpm = HR_DEFAULT_MIN_BPM
        hrMaxBpm = calculateInitialHrMax()
        powerMaxWatts = POWER_INITIAL_MAX_WATTS

        // Reset target values to match
        targetSpeedMinKph = speedMinKph
        targetSpeedMaxKph = speedMaxKph
        targetInclineMinPercent = inclineMinPercent
        targetInclineMaxPercent = inclineMaxPercent
        targetHrMinBpm = hrMinBpm
        targetHrMaxBpm = hrMaxBpm

        android.util.Log.d("WorkoutChart", "Workout mode disabled, free run mode active")
        invalidate()
    }

    /**
     * Set workout steps directly for preview mode (editor).
     * Converts WorkoutStep list to PlannedSegments internally.
     * This is a convenience method for the workout editor preview.
     */
    fun setSteps(workoutSteps: List<WorkoutStep>) {
        val segments = buildSegmentsFromSteps(workoutSteps)
        if (segments.isNotEmpty()) {
            setPlannedSegments(segments)
        } else {
            clearPlannedSegments()
        }
    }


    /**
     * Build PlannedSegments from WorkoutStep list.
     * Handles repeat blocks by expanding them into individual segments.
     */
    private fun buildSegmentsFromSteps(steps: List<WorkoutStep>): List<PlannedSegment> {
        val result = mutableListOf<PlannedSegment>()
        var currentTimeMs = 0L
        var stepIndex = 0

        while (stepIndex < steps.size) {
            val step = steps[stepIndex]

            if (step.type == StepType.REPEAT) {
                // Get child steps using position-based traversal
                // Children immediately follow the repeat and have non-null parentRepeatStepId
                val childSteps = mutableListOf<WorkoutStep>()
                var childIndex = stepIndex + 1
                while (childIndex < steps.size && steps[childIndex].parentRepeatStepId != null) {
                    childSteps.add(steps[childIndex])
                    childIndex++
                }

                val repeatCount = step.repeatCount ?: 1
                for (rep in 0 until repeatCount) {
                    for ((childStepNum, childStep) in childSteps.withIndex()) {
                        val durationMs = calculateStepDurationMs(childStep)
                        result.add(PlannedSegment(
                            startTimeMs = currentTimeMs,
                            endTimeMs = currentTimeMs + durationMs,
                            stepIndex = result.size,
                            stepName = formatStepName(childStep.type, rep + 1, repeatCount),
                            paceKph = childStep.paceTargetKph,
                            inclinePercent = childStep.inclineTargetPercent,
                            hrTargetMinPercent = childStep.hrTargetMinPercent,
                            hrTargetMaxPercent = childStep.hrTargetMaxPercent,
                            powerTargetMinPercent = childStep.powerTargetMinPercent,
                            powerTargetMaxPercent = childStep.powerTargetMaxPercent,
                            autoAdjustMode = childStep.autoAdjustMode,
                            durationType = childStep.durationType,
                            durationMeters = childStep.durationMeters
                        ))
                        currentTimeMs += durationMs
                    }
                }

                // Skip past all children
                stepIndex = childIndex
            } else if (step.parentRepeatStepId == null) {
                // Top-level non-repeat step
                val durationMs = calculateStepDurationMs(step)
                result.add(PlannedSegment(
                    startTimeMs = currentTimeMs,
                    endTimeMs = currentTimeMs + durationMs,
                    stepIndex = result.size,
                    stepName = formatStepName(step.type, null, null),
                    paceKph = step.paceTargetKph,
                    inclinePercent = step.inclineTargetPercent,
                    hrTargetMinPercent = step.hrTargetMinPercent,
                    hrTargetMaxPercent = step.hrTargetMaxPercent,
                    powerTargetMinPercent = step.powerTargetMinPercent,
                    powerTargetMaxPercent = step.powerTargetMaxPercent,
                    autoAdjustMode = step.autoAdjustMode,
                    durationType = step.durationType,
                    durationMeters = step.durationMeters
                ))
                currentTimeMs += durationMs
                stepIndex++
            } else {
                // This is a substep that wasn't processed as part of a repeat
                // (shouldn't happen, but skip it)
                stepIndex++
            }
        }

        return result
    }

    /**
     * Calculate step duration in milliseconds based on durationType.
     * TIME: uses durationSeconds directly
     * DISTANCE: calculates time from distance and pace
     */
    private fun calculateStepDurationMs(step: WorkoutStep): Long {
        return when (step.durationType) {
            DurationType.TIME -> (step.durationSeconds ?: 60) * 1000L
            DurationType.DISTANCE -> {
                val meters = step.durationMeters ?: 1000
                val paceKph = step.paceTargetKph.coerceAtLeast(1.0)
                (PaceConverter.calculateDurationSeconds(meters, paceKph) * 1000).toLong()
            }
        }
    }

    /**
     * Format step name for display.
     */
    private fun formatStepName(type: StepType, iteration: Int?, total: Int?): String {
        val baseName = when (type) {
            StepType.WARMUP -> resources.getString(R.string.step_type_warmup)
            StepType.RUN -> resources.getString(R.string.step_type_run)
            StepType.RECOVER -> resources.getString(R.string.step_type_recover)
            StepType.REST -> resources.getString(R.string.step_type_rest)
            StepType.COOLDOWN -> resources.getString(R.string.step_type_cooldown)
            StepType.REPEAT -> resources.getString(R.string.step_type_repeat)
        }
        return if (iteration != null && total != null && total > 1) {
            resources.getString(R.string.chart_repeat_iteration_format, baseName, iteration, total)
        } else {
            baseName
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        chartLeft = leftAxisWidth
        chartRight = w - rightAxisWidth
        chartTop = topPadding
        chartBottom = h - bottomAxisHeight

        rebuildAllPaths()
    }

    private fun checkAndExpandTimeRange(maxElapsedMs: Long): Boolean {
        // Expand time range dynamically if elapsed time exceeds current range
        // This applies to both free run mode AND workout mode (when running beyond planned duration)
        val maxMinutes = (maxElapsedMs / 60000).toInt()  // ms to minutes
        if (maxMinutes >= timeRangeMinutes) {
            val newRange = ((maxMinutes / TIME_RANGE_INCREMENT_MINUTES) + 1) * TIME_RANGE_INCREMENT_MINUTES
            if (newRange != timeRangeMinutes) {
                timeRangeMinutes = newRange
                return true
            }
        }
        return false
    }

    private fun rebuildAllPaths() {
        speedPath.reset()
        inclinePath.reset()
        hrSegments.clear()
        powerSegments.clear()
        lastProcessedIndex = -1
        // Only process data if we have some - prevents stale data from being redrawn
        if (dataPoints.isNotEmpty()) {
            updatePaths()
        }
    }

    private fun updatePaths() {
        if (dataPoints.isEmpty() || chartRight <= chartLeft) return

        val startIndex = lastProcessedIndex + 1
        if (startIndex >= dataPoints.size) return

        for (i in startIndex until dataPoints.size) {
            val point = dataPoints[i]
            val x = timeToX(point.elapsedMs)

            // Speed path
            val speedY = speedToY(point.speedKph)
            if (i == 0) {
                speedPath.moveTo(x, speedY)
            } else {
                speedPath.lineTo(x, speedY)
            }

            // Incline path
            val inclineY = inclineToY(point.inclinePercent)
            if (i == 0) {
                inclinePath.moveTo(x, inclineY)
            } else {
                inclinePath.lineTo(x, inclineY)
            }

            // HR segments - split at zone boundaries for accurate coloring
            if (i > 0) {
                val prevPoint = dataPoints[i - 1]
                val prevBpm = prevPoint.heartRateBpm
                val currBpm = point.heartRateBpm
                val prevX = timeToX(prevPoint.elapsedMs)

                addHrSegmentsWithZoneSplits(prevX, prevBpm, x, currBpm)
            }

            // Power segments - split at zone boundaries for accurate coloring
            if (i > 0 && point.powerWatts > 0) {
                val prevPoint = dataPoints[i - 1]
                val prevWatts = prevPoint.powerWatts
                val currWatts = point.powerWatts
                val prevX = timeToX(prevPoint.elapsedMs)

                addPowerSegmentsWithZoneSplits(prevX, prevWatts, x, currWatts)
            }
        }

        lastProcessedIndex = dataPoints.size - 1
    }

    /**
     * Add HR segments between two points, splitting at zone boundaries for accurate coloring.
     * Each resulting segment is colored by the zone it's actually in.
     */
    private fun addHrSegmentsWithZoneSplits(startX: Float, startBpm: Double, endX: Float, endBpm: Double) {
        val startZone = getHeartRateZone(startBpm)
        val endZone = getHeartRateZone(endBpm)

        if (startZone == endZone) {
            // Same zone - single segment
            hrSegments.add(HRSegment(startX, hrToY(startBpm), endX, hrToY(endBpm), hrZoneColors[startZone]))
            return
        }

        // Different zones - find boundaries crossed and split
        val zoneBoundaries = listOf(hrZone2Start, hrZone3Start, hrZone4Start, hrZone5Start)
        crossings.clear()

        val minBpm = minOf(startBpm, endBpm)
        val maxBpm = maxOf(startBpm, endBpm)

        for ((i, boundary) in zoneBoundaries.withIndex()) {
            if (boundary.toDouble() > minBpm && boundary.toDouble() <= maxBpm) {
                crossings.add(Pair(boundary.toDouble(), i + 2))  // zone above boundary
            }
        }

        // Sort crossings in-place by BPM (ascending if going up, descending if going down)
        if (endBpm > startBpm) {
            crossings.sortBy { it.first }
        } else {
            crossings.sortByDescending { it.first }
        }

        // Build segments
        var currentBpm = startBpm
        var currentX = startX

        for ((crossBpm, _) in crossings) {
            // Interpolate X position at crossing
            val t = (crossBpm - startBpm) / (endBpm - startBpm)
            val crossX = startX + t.toFloat() * (endX - startX)

            // Segment from current to crossing
            val segmentZone = getHeartRateZone(currentBpm)
            hrSegments.add(HRSegment(currentX, hrToY(currentBpm), crossX, hrToY(crossBpm), hrZoneColors[segmentZone]))

            currentBpm = crossBpm
            currentX = crossX
        }

        // Final segment from last crossing to end
        val finalZone = getHeartRateZone(endBpm)
        hrSegments.add(HRSegment(currentX, hrToY(currentBpm), endX, hrToY(endBpm), hrZoneColors[finalZone]))
    }

    /**
     * Add Power segments between two points, splitting at zone boundaries for accurate coloring.
     */
    private fun addPowerSegmentsWithZoneSplits(startX: Float, startWatts: Double, endX: Float, endWatts: Double) {
        val startZone = getPowerZone(startWatts)
        val endZone = getPowerZone(endWatts)

        if (startZone == endZone) {
            // Same zone - single segment
            powerSegments.add(PowerSegment(startX, powerToNormalizedY(startWatts), endX, powerToNormalizedY(endWatts), powerZoneColors[startZone], powerZoneDimmedColors[startZone]))
            return
        }

        // Different zones - find boundaries crossed and split
        val zoneBoundaries = listOf(powerZone2Start, powerZone3Start, powerZone4Start, powerZone5Start)
        crossings.clear()

        val minWatts = minOf(startWatts, endWatts)
        val maxWatts = maxOf(startWatts, endWatts)

        for ((i, boundary) in zoneBoundaries.withIndex()) {
            if (boundary.toDouble() > minWatts && boundary.toDouble() <= maxWatts) {
                crossings.add(Pair(boundary.toDouble(), i + 2))
            }
        }

        // Sort crossings in-place
        if (endWatts > startWatts) {
            crossings.sortBy { it.first }
        } else {
            crossings.sortByDescending { it.first }
        }

        var currentWatts = startWatts
        var currentX = startX

        for ((crossWatts, _) in crossings) {
            val t = (crossWatts - startWatts) / (endWatts - startWatts)
            val crossX = startX + t.toFloat() * (endX - startX)

            val segmentZone = getPowerZone(currentWatts)
            powerSegments.add(PowerSegment(currentX, powerToNormalizedY(currentWatts), crossX, powerToNormalizedY(crossWatts), powerZoneColors[segmentZone], powerZoneDimmedColors[segmentZone]))

            currentWatts = crossWatts
            currentX = crossX
        }

        val finalZone = getPowerZone(endWatts)
        powerSegments.add(PowerSegment(currentX, powerToNormalizedY(currentWatts), endX, powerToNormalizedY(endWatts), powerZoneColors[finalZone], powerZoneDimmedColors[finalZone]))
    }

    // ==================== Coordinate Mapping ====================

    private fun timeToX(elapsedMs: Long): Float {
        val maxMs = timeRangeMinutes * 60 * 1000L  // minutes to milliseconds
        val ratio = elapsedMs.toFloat() / maxMs
        return chartLeft + ratio * (chartRight - chartLeft)
    }

    /**
     * Convert speed to Y coordinate using current animated scale values.
     */
    private fun speedToY(kph: Double): Float {
        val range = speedMaxKph - speedMinKph
        if (range <= 0) return chartBottom
        val ratio = (kph - speedMinKph) / range
        return chartBottom - ratio.toFloat() * (chartBottom - chartTop)
    }

    /**
     * Convert incline to Y coordinate using current animated scale values.
     */
    private fun inclineToY(percent: Double): Float {
        val range = inclineMaxPercent - inclineMinPercent
        if (range <= 0) return chartBottom
        val ratio = (percent - inclineMinPercent) / range
        return chartBottom - ratio.toFloat() * (chartBottom - chartTop)
    }

    /**
     * Convert HR to Y coordinate using current animated scale values.
     */
    private fun hrToY(bpm: Double): Float {
        val range = hrMaxBpm - _hrMinBpm
        if (range <= 0) return chartBottom
        val ratio = (bpm - _hrMinBpm) / range
        return chartBottom - ratio.toFloat() * (chartBottom - chartTop)
    }

    /**
     * Convert power watts to Y coordinate synchronized with HR scale.
     */
    private fun powerToNormalizedY(watts: Double): Float {
        val normalizedBpm = (watts / ftpWatts.toDouble()) * lthrBpm
        return hrToY(normalizedBpm)
    }

    // ==================== Color Helpers ====================

    /**
     * Blend a color with the chart background color.
     * @param color The color to blend
     * @param factor How much to blend towards background (0 = original, 1 = fully background)
     */
    private fun blendColorWithBackground(color: Int, factor: Float): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val bgR = (backgroundColor shr 16) and 0xFF
        val bgG = (backgroundColor shr 8) and 0xFF
        val bgB = backgroundColor and 0xFF

        val blendedR = (r + (bgR - r) * factor).toInt().coerceIn(0, 255)
        val blendedG = (g + (bgG - g) * factor).toInt().coerceIn(0, 255)
        val blendedB = (b + (bgB - b) * factor).toInt().coerceIn(0, 255)

        return 0xFF000000.toInt() or (blendedR shl 16) or (blendedG shl 8) or blendedB
    }

    // ==================== HR Zone Helpers ====================

    private fun getHeartRateZone(bpm: Double): Int {
        return HeartRateZones.getZone(bpm, hrZone2Start, hrZone3Start, hrZone4Start, hrZone5Start)
    }

    // ==================== Power Zone Helpers ====================

    private fun getPowerZone(watts: Double): Int {
        return PowerZones.getZone(watts, powerZone2Start, powerZone3Start, powerZone4Start, powerZone5Start)
    }

    /**
     * Rebuild cached zone color arrays. Called from init and when zone config changes.
     * Must be called after backgroundColor is set (needed for dimmed color blending).
     */
    private fun rebuildZoneColorCache() {
        for (zone in 0..5) {
            val resId = HeartRateZones.getZoneColorResId(zone)
            val color = ContextCompat.getColor(context, resId)
            hrZoneColors[zone] = color
            hrZoneDimmedColors[zone] = blendColorWithBackground(color, 0.5f)
            powerZoneColors[zone] = color
            powerZoneDimmedColors[zone] = blendColorWithBackground(color, 0.5f)
        }
    }

    // ==================== Segment Duration Calculation ====================

    /**
     * Calculate the adjusted duration for a segment based on the current speed coefficient.
     * For distance-based steps, this recalculates the expected duration using the adjusted pace.
     * For time-based steps, returns the original planned duration.
     *
     * @param segment The planned segment
     * @param coefficient The speed adjustment coefficient (1.0 = no adjustment)
     * @return Duration in milliseconds
     */
    private fun calculateAdjustedSegmentDurationMs(segment: PlannedSegment, coefficient: Double): Long {
        val plannedDurationMs = segment.endTimeMs - segment.startTimeMs

        return if (segment.durationType == DurationType.DISTANCE && segment.durationMeters != null) {
            // Distance-based: recalculate duration based on adjusted pace
            // Higher pace (higher coefficient) = shorter duration
            val adjustedPaceKph = segment.paceKph * coefficient
            val seconds = PaceConverter.calculateDurationSeconds(segment.durationMeters, adjustedPaceKph)
            if (seconds > 0) (seconds * 1000).toLong() else plannedDurationMs
        } else {
            // Time-based: duration is fixed
            plannedDurationMs
        }
    }

    // ==================== Drawing ====================

    // Track if we were animating on previous frame (to detect animation completion)
    private var wasAnimating = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Animate scales towards targets for smooth transitions
        val stillAnimating = animateScales()

        // Rebuild paths during animation AND on the frame animation completes
        // (when animation completes, values snap to target but paths need one final rebuild)
        val needsRebuild = (stillAnimating || wasAnimating) && dataPoints.isNotEmpty()
        if (needsRebuild) {
            rebuildAllPaths()
        }
        wasAnimating = stillAnimating

        // Per spec Section 4.4, layers are drawn bottom to top:
        // 1. Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // 1.5. Phase backgrounds (subtle tint for warmup/cooldown regions)
        drawPhaseBackgrounds(canvas)

        // 2. HR Target Rectangles (bottommost workout layer - semi-transparent zones)
        drawHrTargetRectangles(canvas)

        // 3. Power Target Rectangles (striped to distinguish from HR)
        drawPowerTargetRectangles(canvas)

        // 4. Grid lines
        drawGrid(canvas)

        // 5. Zone boundary lines (HR or Power based on visibility)
        drawZoneBoundaries(canvas)

        // 6. Workout outline - semi-transparent planned pace/incline lines
        drawWorkoutOutline(canvas)

        // 7. Axes
        drawLeftAxis(canvas)
        drawRightAxis(canvas)
        drawBottomAxis(canvas)

        // 8. Current value markers on axes (diamond indicators)
        drawCurrentValueMarkers(canvas)

        // 9. Actual Data lines (topmost - full opacity, with visibility flags)
        // Clip to chart area so out-of-range data lines are properly clipped
        if (dataPoints.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(chartLeft, chartTop, chartRight, chartBottom)

            if (showInclineLine) {
                canvas.drawPath(inclinePath, inclinePaint)
            }
            // Draw Power, then Speed, then HR (HR on top)
            if (showPowerLine) {
                for (segment in powerSegments) {
                    powerPaint.color = segment.dimmedColor
                    canvas.drawLine(segment.startX, segment.startY, segment.endX, segment.endY, powerPaint)
                }
            }
            if (showSpeedLine) {
                canvas.drawPath(speedPath, speedPaint)
            }
            if (showHrLine) {
                for (segment in hrSegments) {
                    hrPaint.color = segment.color
                    canvas.drawLine(segment.startX, segment.startY, segment.endX, segment.endY, hrPaint)
                }
            }

            canvas.restore()
        }

        // Continue animation if scales are still transitioning
        if (stillAnimating) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Calculate time tick interval to ensure max 11 vertical lines (12 intervals).
     * Rounds up to nearest 5 minutes.
     */
    /**
     * Draw subtle background tints for warmup/cooldown chart regions.
     * Only drawn when the workout has stitched phases.
     */
    private fun drawPhaseBackgrounds(canvas: Canvas) {
        if (plannedSegments.isEmpty()) return

        for (segment in plannedSegments) {
            val tintColor = when (segment.phase) {
                WorkoutPhase.WARMUP -> phaseWarmupTintColor
                WorkoutPhase.COOLDOWN -> phaseCooldownTintColor
                WorkoutPhase.MAIN -> continue
            }
            phaseBackgroundPaint.color = tintColor
            val x1 = timeToX(segment.startTimeMs).coerceIn(chartLeft, chartRight)
            val x2 = timeToX(segment.endTimeMs).coerceIn(chartLeft, chartRight)
            if (x2 > x1) {
                canvas.drawRect(x1, chartTop, x2, chartBottom, phaseBackgroundPaint)
            }
        }
    }

    private fun calculateTimeIntervalMinutes(): Int {
        // We want at most 12 intervals (11 lines + boundaries), so divide by 12 and round up
        val rawInterval = ceil(timeRangeMinutes / 12.0).toInt()
        // Round up to nearest 5 minutes
        return ((rawInterval + 4) / 5) * 5
    }

    private fun drawGrid(canvas: Canvas) {
        // Top border line
        canvas.drawLine(chartLeft, chartTop, chartRight, chartTop, gridPaint)

        // Bottom border line: phase-colored segments if stitched workout, normal otherwise
        drawPhaseColoredXAxis(canvas)

        // Vertical grid lines with variable interval
        val intervalMinutes = calculateTimeIntervalMinutes()
        val numLines = timeRangeMinutes / intervalMinutes
        for (i in 0..numLines) {
            val minutes = i * intervalMinutes
            val x = timeToX(minutes * 60 * 1000L)  // Convert to milliseconds
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }
    }

    /**
     * Draw X-axis with phase-colored segments for stitched workouts.
     * Warmup segment: orange, Main: default grid color, Cooldown: purple.
     * Falls back to single grid-colored line when no phase boundaries exist.
     */
    private fun drawPhaseColoredXAxis(canvas: Canvas) {
        val hasPhases = plannedSegments.any { it.phase != WorkoutPhase.MAIN }
        if (!hasPhases) {
            canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, gridPaint)
            return
        }

        // Draw X-axis as colored segments matching workout phases
        var lastPhase: WorkoutPhase? = null
        var phaseStartX = chartLeft

        for (segment in plannedSegments) {
            if (lastPhase == null) {
                lastPhase = segment.phase
            } else if (segment.phase != lastPhase) {
                // Draw the completed phase segment
                phaseXAxisPaint.color = phaseXAxisColor(lastPhase)
                val endX = timeToX(segment.startTimeMs).coerceIn(chartLeft, chartRight)
                canvas.drawLine(phaseStartX, chartBottom, endX, chartBottom, phaseXAxisPaint)
                phaseStartX = endX
                lastPhase = segment.phase
            }
        }

        // Draw the last phase segment to chartRight
        if (lastPhase != null) {
            phaseXAxisPaint.color = phaseXAxisColor(lastPhase)
            canvas.drawLine(phaseStartX, chartBottom, chartRight, chartBottom, phaseXAxisPaint)
        } else {
            canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, gridPaint)
        }
    }

    private fun phaseXAxisColor(phase: WorkoutPhase): Int = when (phase) {
        WorkoutPhase.WARMUP -> phaseWarmupBorderColor
        WorkoutPhase.COOLDOWN -> phaseCooldownBorderColor
        WorkoutPhase.MAIN -> gridPaint.color
    }

    private fun drawLeftAxis(canvas: Canvas) {
        val tickLength = 10f * resources.displayMetrics.density

        // Speed scale (cyan) - independent scale from workout targets
        // Only show if speed line is visible
        if (showSpeedLine) {
            axisLabelPaint.color = speedColor
            axisLabelPaint.textAlign = Paint.Align.RIGHT
            val speedLabelX = leftAxisWidth * 0.45f

            // Generate speed labels at integer values within independent speed scale
            val speedRange = speedMaxKph - speedMinKph
            val speedStepInt = max(1, (speedRange / 4).toInt())
            var speed = (ceil(speedMinKph / speedStepInt) * speedStepInt).toInt()
            while (speed <= speedMaxKph.toInt()) {
                val y = speedToY(speed.toDouble())
                canvas.drawText("$speed", speedLabelX, y + axisLabelSize / 3, axisLabelPaint)
                // Tick mark extending left from chart edge
                canvas.drawLine(chartLeft - tickLength, y, chartLeft, y, axisLabelPaint)
                speed += speedStepInt
            }

            // Axis title for speed
            val titleX = 16f
            axisTitlePaint.textAlign = Paint.Align.CENTER
            axisTitlePaint.color = speedColor
            canvas.withRotation(-90f, titleX, height / 2f) {
                drawText(resources.getString(R.string.chart_axis_speed), titleX, height / 2f, axisTitlePaint)
            }
        }

        // Incline scale (yellow) - only if incline line is visible
        if (showInclineLine) {
            axisLabelPaint.color = inclineColor
            axisLabelPaint.textAlign = Paint.Align.LEFT
            val inclineLabelX = leftAxisWidth * 0.55f

            // Generate incline labels at integer values (including negative)
            val inclineRange = inclineMaxPercent - inclineMinPercent
            val inclineStepInt = max(1, (inclineRange / 4).toInt())
            var incline = (inclineMinPercent / inclineStepInt).toInt() * inclineStepInt
            if (incline < inclineMinPercent) incline += inclineStepInt
            while (incline <= inclineMaxPercent.toInt()) {
                val y = inclineToY(incline.toDouble())
                canvas.drawText(resources.getString(R.string.chart_axis_incline_format, incline), inclineLabelX, y + axisLabelSize / 3, axisLabelPaint)
                // Tick mark extending left from chart edge
                canvas.drawLine(chartLeft - tickLength, y, chartLeft, y, axisLabelPaint)
                incline += inclineStepInt
            }
        }
    }

    private fun drawRightAxis(canvas: Canvas) {
        axisLabelPaint.textAlign = Paint.Align.LEFT
        val tickLength = 10f * resources.displayMetrics.density
        val labelX = width - rightAxisWidth + tickLength + 4f
        val titleX = width - 16f

        // Labels are drawn at zone start values (where each zone begins)
        // Zone boundaries use "start" semantics: zone N starts at zoneNStart

        if (showHrLine && showPowerLine) {
            // Both HR and Power visible: show zone numbers (synchronized scale)
            val zoneBoundaries = listOf(
                hrZone2Start to 2,
                hrZone3Start to 3,
                hrZone4Start to 4,
                hrZone5Start to 5
            )

            for ((boundary, zone) in zoneBoundaries) {
                if (boundary.toDouble() <= hrMaxBpm && boundary.toDouble() >= hrMinBpm) {
                    val y = hrToY(boundary.toDouble())
                    axisLabelPaint.color = hrZoneColors[zone]
                    canvas.drawText(resources.getString(R.string.chart_axis_zone_format, zone), labelX, y + axisLabelSize / 3, axisLabelPaint)
                    // Tick mark extending right from chart edge
                    canvas.drawLine(chartRight, y, chartRight + tickLength, y, axisLabelPaint)
                }
            }

            // Axis title showing "Zone"
            axisTitlePaint.color = hrZoneColors[3]
            canvas.withRotation(90f, titleX, height / 2f) {
                drawText(resources.getString(R.string.chart_axis_zone), titleX, height / 2f, axisTitlePaint)
            }
        } else if (showHrLine) {
            // Only HR visible: show HR BPM labels at zone boundaries
            val zoneBoundaries = listOf(
                hrZone2Start to 2,
                hrZone3Start to 3,
                hrZone4Start to 4,
                hrZone5Start to 5
            )

            for ((boundary, zoneAbove) in zoneBoundaries) {
                if (boundary.toDouble() <= hrMaxBpm && boundary.toDouble() >= hrMinBpm) {
                    val y = hrToY(boundary.toDouble())
                    axisLabelPaint.color = hrZoneColors[zoneAbove]
                    canvas.drawText("$boundary", labelX, y + axisLabelSize / 3, axisLabelPaint)
                    // Tick mark extending right from chart edge
                    canvas.drawLine(chartRight, y, chartRight + tickLength, y, axisLabelPaint)
                }
            }

            // Axis title for HR
            axisTitlePaint.color = hrZoneColors[5]
            canvas.withRotation(90f, titleX, height / 2f) {
                drawText(resources.getString(R.string.chart_axis_hr), titleX, height / 2f, axisTitlePaint)
            }
        } else if (showPowerLine) {
            // Only Power visible: show Power watts labels at zone boundaries
            val zoneBoundaries = listOf(
                powerZone2Start to 2,
                powerZone3Start to 3,
                powerZone4Start to 4,
                powerZone5Start to 5
            )

            for ((boundary, zone) in zoneBoundaries) {
                // Use target scale so labels stay aligned with data paths
                val y = powerToNormalizedY(boundary.toDouble())
                axisLabelPaint.color = powerZoneColors[zone]
                canvas.drawText("$boundary", labelX, y + axisLabelSize / 3, axisLabelPaint)
                // Tick mark extending right from chart edge
                canvas.drawLine(chartRight, y, chartRight + tickLength, y, axisLabelPaint)
            }

            // Axis title for Power
            axisTitlePaint.color = powerColor
            canvas.withRotation(90f, titleX, height / 2f) {
                drawText(resources.getString(R.string.chart_axis_power), titleX, height / 2f, axisTitlePaint)
            }
        }
    }

    /**
     * Draw zone boundary lines across the chart area.
     * When both HR and Power are visible, uses synchronized HR zone boundaries.
     * When only Power is visible, uses Power zone boundaries mapped to the same Y positions.
     * Lines are colored by the zone that STARTS at that boundary (zone above).
     * Lines are drawn at 50% opacity.
     */
    private fun drawZoneBoundaries(canvas: Canvas) {
        if (showHrLine || showPowerLine) {
            // Use HR zone boundaries (synchronized scale for both HR and Power)
            // Draw at zone start values (where each zone begins)
            // e.g., zone2Start=136 means zone 2 starts at 136 (values >= 136 are zone 2)
            val boundaries = listOf(
                hrZone2Start to 2,
                hrZone3Start to 3,
                hrZone4Start to 4,
                hrZone5Start to 5
            )

            for ((boundary, zone) in boundaries) {
                if (boundary.toDouble() <= targetHrMaxBpm && boundary.toDouble() >= targetHrMinBpm) {
                    val y = hrToY(boundary.toDouble())
                    hrZoneBoundaryPaint.color = (hrZoneColors[zone] and 0x00FFFFFF) or 0x80000000.toInt()
                    canvas.drawLine(chartLeft, y, chartRight, y, hrZoneBoundaryPaint)
                }
            }
        }
    }

    /**
     * Draw HR target rectangles per spec Section 4.4.
     * Semi-transparent zones showing target HR range per step.
     * Each zone portion is colored with its zone's color.
     * Drawn as the bottommost workout layer.
     *
     * Uses same positioning logic as drawWorkoutOutline.
     * For distance-based steps, durations are recalculated based on speed coefficient.
     */
    private fun drawHrTargetRectangles(canvas: Canvas) {
        if (plannedSegments.isEmpty()) return

        // Use engine's elapsed time (more accurate than data points which can be stale)
        val currentElapsedMs = if (currentWorkoutElapsedMs > 0) currentWorkoutElapsedMs else dataPoints.lastOrNull()?.elapsedMs ?: 0L

        // Get current segment info with adjusted duration for distance-based steps
        val currentSegment = plannedSegments.getOrNull(currentStepIndex)
        val currentSegmentAdjustedDuration = if (currentSegment != null) {
            calculateAdjustedSegmentDurationMs(currentSegment, speedCoefficient)
        } else 0L

        // Use stored actual start time (captured when step changed)
        val actualCurrentStepStart = currentStepActualStartMs
        // Where the current step will actually end (based on adjusted duration)
        val currentStepActualEnd = maxOf(actualCurrentStepStart + currentSegmentAdjustedDuration, currentElapsedMs)

        // 1. Draw past steps using actual boundaries from recorded data
        // The "current" boundary is the last one being recorded, BUT only if:
        // - Its stepIndex matches currentStepIndex AND
        // - Its start time is close to actualCurrentStepStart (not a previous occurrence)
        val lastBoundary = actualStepBoundaries.lastOrNull()
        val isLastBoundaryCurrent = if (lastBoundary == null) {
            false
        } else if (lastBoundary.stepIndex != currentStepIndex) {
            // Different step - definitely not current
            false
        } else {
            // Same stepIndex - check if it's the current occurrence by comparing start times
            // The current step's boundary should start around actualCurrentStepStart
            val timeDiff = kotlin.math.abs(lastBoundary.startElapsedMs - actualCurrentStepStart)
            timeDiff < 5000L // Within 5 seconds = current occurrence
        }

        val pastBoundaries = if (isLastBoundaryCurrent) {
            actualStepBoundaries.dropLast(1)
        } else {
            actualStepBoundaries
        }

        for (boundary in pastBoundaries) {
            // Get HR targets from planned segment using the boundary's stepIndex
            val segment = plannedSegments.getOrNull(boundary.stepIndex) ?: continue
            val hrMinBpm = segment.getHrTargetMinBpm(lthrBpm) ?: continue
            val hrMaxBpm = segment.getHrTargetMaxBpm(lthrBpm) ?: continue

            val startX = timeToX(boundary.startElapsedMs)
            val endX = timeToX(boundary.endElapsedMs)

            val clampedStartX = startX.coerceIn(chartLeft, chartRight)
            val clampedEndX = endX.coerceIn(chartLeft, chartRight)

            if (clampedEndX <= clampedStartX) continue

            drawZoneColoredRectangles(canvas, clampedStartX, clampedEndX, hrMinBpm, hrMaxBpm)
        }

        // 2. Draw current and future steps using planned positions with adjustments
        // Track cumulative position for future steps
        var futureStepStartMs = currentStepActualEnd

        for ((index, segment) in plannedSegments.withIndex()) {
            // Skip past steps - already drawn above from actual boundaries
            if (index < currentStepIndex) continue

            // Calculate adjusted duration for this segment (handles distance-based steps)
            val (segSpeedCoeff, segInclineCoeff) = getSegmentCoefficients(segment, index)
            val segmentAdjustedDuration = calculateAdjustedSegmentDurationMs(segment, segSpeedCoeff)

            val (segmentStartMs, segmentEndMs) = if (index == currentStepIndex) {
                // Current step: position at actual start time so live data overlaps
                val endMs = maxOf(actualCurrentStepStart + segmentAdjustedDuration, currentElapsedMs)
                Pair(actualCurrentStepStart, endMs)
            } else {
                // Future steps: position sequentially after previous adjusted step
                val startMs = futureStepStartMs
                val endMs = startMs + segmentAdjustedDuration
                futureStepStartMs = endMs  // Update for next future step
                Pair(startMs, endMs)
            }

            // Only draw if this segment has an HR target (but always track position above)
            val hrMinBpm = segment.getHrTargetMinBpm(lthrBpm)
            val hrMaxBpm = segment.getHrTargetMaxBpm(lthrBpm)
            if (hrMinBpm == null || hrMaxBpm == null) continue

            val startX = timeToX(segmentStartMs)
            val endX = timeToX(segmentEndMs)

            val clampedStartX = startX.coerceIn(chartLeft, chartRight)
            val clampedEndX = endX.coerceIn(chartLeft, chartRight)

            if (clampedEndX <= clampedStartX) continue

            drawZoneColoredRectangles(canvas, clampedStartX, clampedEndX, hrMinBpm, hrMaxBpm)
        }
    }

    /**
     * Draw rectangles for an HR range, coloring each zone portion separately.
     */
    private fun drawZoneColoredRectangles(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        hrMin: Int,
        hrMax: Int
    ) {
        // Get the zone for hrMin and hrMax using integer BPM boundaries
        val minZone = getHeartRateZone(hrMin.toDouble())
        val maxZone = getHeartRateZone(hrMax.toDouble())

        if (minZone == maxZone) {
            // Entire range is within one zone - draw single rectangle
            drawSingleZoneRect(canvas, startX, endX, hrMin, hrMax + 1, minZone)
        } else {
            // Range spans multiple zones - draw each portion
            var currentMin = hrMin

            for (zone in minZone..maxZone) {
                // Where does the next zone start? (exclusive upper bound for this zone)
                val nextZoneStart = when (zone) {
                    1 -> hrZone2Start
                    2 -> hrZone3Start
                    3 -> hrZone4Start
                    4 -> hrZone5Start
                    else -> hrMax + 1  // Zone 5 extends to hrMax
                }

                // This zone's portion ends at the next zone boundary or hrMax
                val portionEnd = minOf(nextZoneStart, hrMax + 1)

                if (portionEnd > currentMin) {
                    drawSingleZoneRect(canvas, startX, endX, currentMin, portionEnd, zone)
                }

                currentMin = portionEnd
                if (currentMin > hrMax) break
            }
        }
    }

    /**
     * Draw a single rectangle for an HR range portion with zone-specific color.
     * @param hrMaxExclusive Exclusive upper bound (first value of next zone, or hrMax+1 for last zone)
     */
    private fun drawSingleZoneRect(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        hrMin: Int,
        hrMaxExclusive: Int,
        zone: Int
    ) {
        // Use target scale so rectangles stay aligned with data paths (built with target values)
        val topY = hrToY(hrMaxExclusive.toDouble())
        val bottomY = hrToY(hrMin.toDouble())

        // Get zone color with 40% opacity for fill
        hrTargetRectPaint.color = (hrZoneColors[zone] and 0x00FFFFFF) or 0x66000000

        canvas.drawRect(startX, topY, endX, bottomY, hrTargetRectPaint)
    }

    /**
     * Draw Power target rectangles with diagonal stripes (to distinguish from HR zones).
     * Similar logic to drawHrTargetRectangles but for Power targets.
     * For distance-based steps, durations are recalculated based on speed coefficient.
     */
    private fun drawPowerTargetRectangles(canvas: Canvas) {
        if (plannedSegments.isEmpty()) return

        // Use engine's elapsed time (more accurate than data points which can be stale)
        val currentElapsedMs = if (currentWorkoutElapsedMs > 0) currentWorkoutElapsedMs else dataPoints.lastOrNull()?.elapsedMs ?: 0L

        // Get current segment info with adjusted duration for distance-based steps
        val currentSegment = plannedSegments.getOrNull(currentStepIndex)
        val currentSegmentAdjustedDuration = if (currentSegment != null) {
            calculateAdjustedSegmentDurationMs(currentSegment, speedCoefficient)
        } else 0L

        val actualCurrentStepStart = currentStepActualStartMs
        val currentStepActualEnd = maxOf(actualCurrentStepStart + currentSegmentAdjustedDuration, currentElapsedMs)

        // 1. Draw past steps using actual boundaries from recorded data
        val lastBoundary = actualStepBoundaries.lastOrNull()
        val isLastBoundaryCurrent = if (lastBoundary == null) {
            false
        } else if (lastBoundary.stepIndex != currentStepIndex) {
            false
        } else {
            val timeDiff = kotlin.math.abs(lastBoundary.startElapsedMs - actualCurrentStepStart)
            timeDiff < 5000L
        }

        val pastBoundaries = if (isLastBoundaryCurrent) {
            actualStepBoundaries.dropLast(1)
        } else {
            actualStepBoundaries
        }

        for (boundary in pastBoundaries) {
            val segment = plannedSegments.getOrNull(boundary.stepIndex) ?: continue
            if (segment.autoAdjustMode != AutoAdjustMode.POWER) continue
            val powerMinWatts = segment.getPowerTargetMinWatts(ftpWatts) ?: continue
            val powerMaxWatts = segment.getPowerTargetMaxWatts(ftpWatts) ?: continue

            val startX = timeToX(boundary.startElapsedMs)
            val endX = timeToX(boundary.endElapsedMs)

            val clampedStartX = startX.coerceIn(chartLeft, chartRight)
            val clampedEndX = endX.coerceIn(chartLeft, chartRight)

            if (clampedEndX <= clampedStartX) continue

            drawStripedPowerRect(canvas, clampedStartX, clampedEndX, powerMinWatts, powerMaxWatts)
        }

        // 2. Draw current and future steps
        // Track cumulative position for future steps
        var futureStepStartMs = currentStepActualEnd

        for ((index, segment) in plannedSegments.withIndex()) {
            if (index < currentStepIndex) continue

            // Calculate adjusted duration for this segment (handles distance-based steps)
            val (segSpeedCoeff, segInclineCoeff) = getSegmentCoefficients(segment, index)
            val segmentAdjustedDuration = calculateAdjustedSegmentDurationMs(segment, segSpeedCoeff)

            val (segmentStartMs, segmentEndMs) = if (index == currentStepIndex) {
                val endMs = maxOf(actualCurrentStepStart + segmentAdjustedDuration, currentElapsedMs)
                Pair(actualCurrentStepStart, endMs)
            } else {
                // Future steps: position sequentially after previous adjusted step
                val startMs = futureStepStartMs
                val endMs = startMs + segmentAdjustedDuration
                futureStepStartMs = endMs  // Update for next future step
                Pair(startMs, endMs)
            }

            // Only draw Power targets for steps with POWER auto-adjust mode (but always track position above)
            if (segment.autoAdjustMode != AutoAdjustMode.POWER) continue

            val powerMinWatts = segment.getPowerTargetMinWatts(ftpWatts)
            val powerMaxWatts = segment.getPowerTargetMaxWatts(ftpWatts)
            if (powerMinWatts == null || powerMaxWatts == null) continue

            val startX = timeToX(segmentStartMs)
            val endX = timeToX(segmentEndMs)

            val clampedStartX = startX.coerceIn(chartLeft, chartRight)
            val clampedEndX = endX.coerceIn(chartLeft, chartRight)

            if (clampedEndX <= clampedStartX) continue

            drawStripedPowerRect(canvas, clampedStartX, clampedEndX, powerMinWatts, powerMaxWatts)
        }
    }

    /**
     * Draw a striped power zone rectangle.
     * Colors each zone portion with zone color and overlays diagonal stripes.
     * Uses normalized Y scale synchronized with HR zones.
     */
    private fun drawStripedPowerRect(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        powerMinWatts: Int,
        powerMaxWatts: Int
    ) {
        val minZone = getPowerZone(powerMinWatts.toDouble())
        val maxZone = getPowerZone(powerMaxWatts.toDouble())

        // Convert watts to Y coordinates using target scale (so workout structure animates with data paths)
        val topY = powerToNormalizedY(powerMaxWatts.toDouble())
        val bottomY = powerToNormalizedY(powerMinWatts.toDouble())

        if (minZone == maxZone) {
            drawSingleStripedPowerZoneRect(canvas, startX, endX, topY, bottomY, minZone)
        } else {
            // Draw each zone portion
            var currentMinWatts = powerMinWatts

            for (zone in minZone..maxZone) {
                // Where does the next zone start? (exclusive upper bound for this zone)
                val nextZoneStart = when (zone) {
                    1 -> powerZone2Start
                    2 -> powerZone3Start
                    3 -> powerZone4Start
                    4 -> powerZone5Start
                    else -> powerMaxWatts + 1  // Zone 5 extends to powerMaxWatts
                }

                val portionEnd = minOf(nextZoneStart, powerMaxWatts + 1)

                if (portionEnd > currentMinWatts) {
                    // Use exclusive bound for Y - rectangles share boundary coordinates
                    val portionTopY = powerToNormalizedY(portionEnd.toDouble())
                    val portionBottomY = powerToNormalizedY(currentMinWatts.toDouble())
                    drawSingleStripedPowerZoneRect(canvas, startX, endX, portionTopY, portionBottomY, zone)
                }

                currentMinWatts = portionEnd
                if (currentMinWatts > powerMaxWatts) break
            }
        }
    }

    /**
     * Draw a single striped power zone rectangle with zone color and diagonal stripes.
     */
    private fun drawSingleStripedPowerZoneRect(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        topY: Float,
        bottomY: Float,
        zone: Int
    ) {
        // Get zone color with 40% opacity for fill (same as HR)
        powerTargetRectPaint.color = (powerZoneColors[zone] and 0x00FFFFFF) or 0x66000000

        // Draw filled background
        canvas.drawRect(startX, topY, endX, bottomY, powerTargetRectPaint)

        // Draw diagonal stripes overlay
        clipRect.set(startX, topY, endX, bottomY)

        canvas.withClip(clipRect) {
            // Draw diagonal lines from bottom-left to top-right
            var x = startX - (bottomY - topY)  // Start before rect to ensure full coverage
            while (x < endX + stripeSpacingPx) {
                drawLine(
                    x, bottomY,
                    x + (bottomY - topY), topY,
                    powerStripeOverlayPaint
                )
                x += stripeSpacingPx
            }
        }
    }

    /**
     * Draw workout outline per spec Section 4.4.
     * Semi-transparent planned pace/incline lines for entire workout.
     * Pace shown as cyan dashed line, incline as yellow dashed line.
     *
     * Past steps (index < currentStepIndex) are skipped since actual data covers them.
     * Current and future steps are drawn with adjustment coefficients applied.
     *
     * Segment positions:
     * - Current step: stays at planned position, extends if overrunning
     * - Future steps: positioned relative to where current step will actually end
     *   (handles both early skips and overruns)
     *
     * For distance-based steps, durations are recalculated based on the current
     * speed coefficient, so the chart reflects actual expected timing.
     */
    private fun drawWorkoutOutline(canvas: Canvas) {
        if (plannedSegments.isEmpty()) return

        // Use engine's elapsed time (more accurate than data points which can be stale)
        val currentElapsedMs = if (currentWorkoutElapsedMs > 0) currentWorkoutElapsedMs else dataPoints.lastOrNull()?.elapsedMs ?: 0L

        // Get current segment info with adjusted duration for distance-based steps
        val currentSegment = plannedSegments.getOrNull(currentStepIndex)
        val currentSegmentAdjustedDuration = if (currentSegment != null) {
            calculateAdjustedSegmentDurationMs(currentSegment, speedCoefficient)
        } else 0L

        // Use stored actual start time (captured when step changed)
        val actualCurrentStepStart = currentStepActualStartMs
        // Where the current step will actually end (based on adjusted duration)
        val currentStepActualEnd = maxOf(actualCurrentStepStart + currentSegmentAdjustedDuration, currentElapsedMs)

        var lastSpeedY = 0f
        var lastInclineY = 0f
        var lastEndX = chartLeft
        var firstDrawnSegment = true

        // For smooth transition to current step, find where the last past step actually ended
        // The LAST boundary is "current", so the second-to-last boundary's end is where past ended
        if (actualStepBoundaries.size > 1) {
            val lastPastBoundary = actualStepBoundaries[actualStepBoundaries.size - 2]
            lastEndX = timeToX(lastPastBoundary.endElapsedMs).coerceIn(chartLeft, chartRight)
        }

        // Track cumulative position for future steps
        var futureStepStartMs = currentStepActualEnd

        for ((index, segment) in plannedSegments.withIndex()) {
            // Skip past segments - actual data covers them
            if (index < currentStepIndex) continue

            // Calculate adjusted duration for this segment (handles distance-based steps)
            val (segSpeedCoeff, segInclineCoeff) = getSegmentCoefficients(segment, index)
            val segmentAdjustedDuration = calculateAdjustedSegmentDurationMs(segment, segSpeedCoeff)

            val (segmentStartMs, segmentEndMs) = if (index == currentStepIndex) {
                // Current step: position at actual start time so live data overlaps
                val endMs = maxOf(actualCurrentStepStart + segmentAdjustedDuration, currentElapsedMs)
                Pair(actualCurrentStepStart, endMs)
            } else {
                // Future steps: position sequentially after previous adjusted step
                val startMs = futureStepStartMs
                val endMs = startMs + segmentAdjustedDuration
                futureStepStartMs = endMs  // Update for next future step
                Pair(startMs, endMs)
            }

            val startX = timeToX(segmentStartMs)
            val endX = timeToX(segmentEndMs)

            // Apply adjustment coefficients to current and future segments
            val adjustedPace = segment.paceKph * segSpeedCoeff
            val adjustedIncline = segment.inclinePercent * segInclineCoeff

            // Use target scale values so workout outline animates with data paths
            val speedY = speedToY(adjustedPace)
            val inclineY = inclineToY(adjustedIncline)

            // Clamp to chart bounds
            val clampedStartX = startX.coerceIn(chartLeft, chartRight)
            val clampedEndX = endX.coerceIn(chartLeft, chartRight)

            if (clampedEndX <= clampedStartX) continue

            if (firstDrawnSegment) {
                // First drawn segment: just draw horizontal lines from its start
                canvas.drawLine(clampedStartX, speedY, clampedEndX, speedY, targetSpeedPaint)
                canvas.drawLine(clampedStartX, inclineY, clampedEndX, inclineY, targetInclinePaint)
                firstDrawnSegment = false
            } else {
                // Draw vertical transition from previous segment, then horizontal
                // Speed: vertical step up/down, then horizontal
                canvas.drawLine(lastEndX, lastSpeedY, clampedStartX, lastSpeedY, targetSpeedPaint) // Continue previous level
                canvas.drawLine(clampedStartX, lastSpeedY, clampedStartX, speedY, targetSpeedPaint) // Vertical step
                canvas.drawLine(clampedStartX, speedY, clampedEndX, speedY, targetSpeedPaint) // New level

                // Incline: vertical step up/down, then horizontal
                canvas.drawLine(lastEndX, lastInclineY, clampedStartX, lastInclineY, targetInclinePaint)
                canvas.drawLine(clampedStartX, lastInclineY, clampedStartX, inclineY, targetInclinePaint)
                canvas.drawLine(clampedStartX, inclineY, clampedEndX, inclineY, targetInclinePaint)
            }

            lastSpeedY = speedY
            lastInclineY = inclineY
            lastEndX = clampedEndX
        }
    }

    private fun drawBottomAxis(canvas: Canvas) {
        axisLabelPaint.color = axisLabelColor
        axisLabelPaint.textAlign = Paint.Align.CENTER
        val labelY = height - bottomAxisHeight / 3

        // Time labels with variable interval (same as grid)
        val intervalMinutes = calculateTimeIntervalMinutes()
        val numLabels = timeRangeMinutes / intervalMinutes
        for (i in 0..numLabels) {
            val minutes = i * intervalMinutes
            val x = timeToX(minutes * 60 * 1000L)  // Convert to milliseconds
            canvas.drawText(resources.getString(R.string.chart_axis_time_format, minutes), x, labelY, axisLabelPaint)
        }
    }

    /**
     * Draw diamond/rhombus markers on axes showing current live values.
     * Markers are drawn regardless of whether the corresponding line is visible.
     * Drawing order: incline, speed, power, hr (HR on top).
     */
    private fun drawCurrentValueMarkers(canvas: Canvas) {
        val lastPoint = dataPoints.lastOrNull() ?: return
        val markerWidth = 8f * resources.displayMetrics.density   // Horizontal size
        val markerHeight = 4f * resources.displayMetrics.density  // Vertical size

        // Draw in order: incline, speed, power, hr (so HR ends up on top)

        // 1. Incline marker (left axis)
        if (lastPoint.inclinePercent > 0 || inclineMinPercent < 0) {
            val y = inclineToY(lastPoint.inclinePercent)
            if (y >= chartTop && y <= chartBottom) {
                markerPaint.color = inclineColor
                drawDiamond(canvas, chartLeft, y, markerWidth, markerHeight)
            }
        }

        // 2. Speed marker (left axis)
        if (lastPoint.speedKph > 0) {
            val y = speedToY(lastPoint.speedKph)
            if (y >= chartTop && y <= chartBottom) {
                markerPaint.color = speedColor
                drawDiamond(canvas, chartLeft, y, markerWidth, markerHeight)
            }
        }

        // 3. Power marker (right axis) - use zone color, dimmed like power line
        if (lastPoint.powerWatts > 0) {
            val y = powerToNormalizedY(lastPoint.powerWatts)
            if (y >= chartTop && y <= chartBottom) {
                val powerZone = PowerZones.getZone(
                    lastPoint.powerWatts,
                    powerZone2Start, powerZone3Start, powerZone4Start, powerZone5Start
                )
                markerPaint.color = powerZoneDimmedColors[powerZone]
                drawDiamond(canvas, chartRight, y, markerWidth, markerHeight)
            }
        }

        // 4. HR marker (right axis) - use zone color
        if (lastPoint.heartRateBpm > 0) {
            val y = hrToY(lastPoint.heartRateBpm)
            if (y >= chartTop && y <= chartBottom) {
                val hrZone = HeartRateZones.getZone(
                    lastPoint.heartRateBpm,
                    hrZone2Start, hrZone3Start, hrZone4Start, hrZone5Start
                )
                markerPaint.color = hrZoneColors[hrZone]
                drawDiamond(canvas, chartRight, y, markerWidth, markerHeight)
            }
        }
    }

    /**
     * Draw a horizontally elongated diamond/rhombus shape centered at (cx, cy).
     */
    private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, halfWidth: Float, halfHeight: Float) {
        diamondPath.reset()
        diamondPath.moveTo(cx, cy - halfHeight)       // Top
        diamondPath.lineTo(cx + halfWidth, cy)        // Right
        diamondPath.lineTo(cx, cy + halfHeight)       // Bottom
        diamondPath.lineTo(cx - halfWidth, cy)        // Left
        diamondPath.close()
        canvas.drawPath(diamondPath, markerPaint)
    }
}
