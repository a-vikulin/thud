package io.github.avikulin.thud.service

import com.ifit.glassos.workout.WorkoutState
import io.github.avikulin.thud.util.DfaAlpha1Calculator
import io.github.avikulin.thud.util.PaceConverter
import io.github.avikulin.thud.util.SpeedCalibrationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Centralized state container for HUDService.
 * Holds telemetry values, settings, and visibility state that are shared across managers.
 */
class ServiceStateHolder {

    // ==================== Treadmill Workout State ====================
    @Volatile var treadmillWorkoutState: WorkoutState = WorkoutState.WORKOUT_STATE_IDLE

    // ==================== Telemetry State ====================
    // Volatile for cross-thread visibility
    // Note: Distance and elevation are NOT stored here - we calculate them from adjusted speed
    @Volatile var currentSpeedKph = 0.0
    @Volatile var currentInclinePercent = 0.0

    /**
     * The intended adjusted (perceived) speed. Set by TelemetryManager.setTreadmillSpeed()
     * when we command a speed, and NOT changed when incline compensation adjusts raw speed
     * to maintain the same perceived speed at a new incline.
     *
     * Use [getDisplayAdjustedSpeed] instead of [rawToAdjustedSpeed] for display/recording
     * to avoid transient wobble during incline transitions with incline-aware calibration.
     */
    @Volatile var targetAdjustedSpeedKph = 0.0
    @Volatile var currentHeartRateBpm = 0.0
    @Volatile var currentElapsedSeconds = 0

    // ==================== Stryd Foot Pod State ====================
    @Volatile var currentPowerWatts = 0.0       // Adjusted power (raw + incline contribution)
    @Volatile var currentRawPowerWatts = 0.0    // Raw power from Stryd (before incline adjustment)
    @Volatile var currentCadenceSpm = 0
    @Volatile var currentStrydSpeedKph = 0.0  // Speed from Stryd foot pod
    @Volatile var strydConnected = false

    // ==================== HR Sensor State ====================
    @Volatile var hrSensorConnected = false   // true if at least one HR sensor is connected

    // Multi-sensor support: MAC → (deviceName, currentBpm)
    val connectedHrSensors: ConcurrentHashMap<String, Pair<String, Int>> = ConcurrentHashMap()
    @Volatile var savedPrimaryHrMac: String = ""   // user's explicit choice (MAC or "AVERAGE"), persisted
    @Volatile var activePrimaryHrMac: String = ""  // currently active primary (may differ during fallback)

    // ==================== Settings State ====================
    // Speed calibration — two independent coefficient sets:
    //
    // Manual mode (auto=false): linear model adjusted = paceCoefficient * raw + speedCalibrationB
    @Volatile var paceCoefficient = 1.0
    @Volatile var speedCalibrationB = 0.0           // intercept (default 0 = backward compatible)
    //
    // Auto mode (auto=true): y = C0 + C1*x + C2*x² + C3*x³ + C4*sin(θ) + C5*x*sin(θ)
    // Always recomputed after every run; only used live when speedCalibrationAuto = true.
    @Volatile var speedCalibrationC0 = 0.0
    @Volatile var speedCalibrationC1 = 1.0          // default = identity (y = x)
    @Volatile var speedCalibrationC2 = 0.0
    @Volatile var speedCalibrationC3 = 0.0
    @Volatile var speedCalibrationC4 = 0.0          // incline term: C4*sin(θ)
    @Volatile var speedCalibrationC5 = 0.0          // cross term: C5*x*sin(θ)
    @Volatile var speedCalibrationDegree = 1        // speed polynomial degree: 1, 2, or 3
    //
    // Training data incline range (raw treadmill %). Incline is clamped to this range
    // before computing sin(θ) to prevent extrapolation beyond training data.
    @Volatile var calibrationInclineMinPercent = 0.0
    @Volatile var calibrationInclineMaxPercent = 0.0
    //
    @Volatile var speedCalibrationAuto = false       // true = use polynomial from Stryd regression
    @Volatile var speedCalibrationRunWindow = 30     // how many recent runs for regression

    /** Get auto-mode coefficients as 6-element array [C0, C1, C2, C3, C4, C5]. */
    fun getPolynomialCoefficients(): DoubleArray = doubleArrayOf(
        speedCalibrationC0, speedCalibrationC1, speedCalibrationC2,
        speedCalibrationC3, speedCalibrationC4, speedCalibrationC5
    )

    /**
     * Clamp raw incline to the training data range before computing sin(θ).
     * Prevents extrapolation beyond the inclines present in calibration data.
     */
    private fun clampedCalibrationSinTheta(rawInclinePercent: Double): Double {
        val clamped = rawInclinePercent.coerceIn(calibrationInclineMinPercent, calibrationInclineMaxPercent)
        return PaceConverter.inclinePercentToSin(clamped)
    }

    /**
     * Convert raw treadmill speed to adjusted (perceived) speed. Single source of truth.
     * @param rawKph Raw treadmill speed
     * @param rawInclinePercent Raw treadmill incline (before adjustment). Used for auto mode incline correction.
     */
    fun rawToAdjustedSpeed(rawKph: Double, rawInclinePercent: Double = 0.0): Double = if (speedCalibrationAuto) {
        SpeedCalibrationManager.evaluatePolynomial(
            getPolynomialCoefficients(), rawKph, clampedCalibrationSinTheta(rawInclinePercent)
        )
    } else {
        rawKph * paceCoefficient + speedCalibrationB
    }

    /**
     * Convert adjusted (perceived) speed back to raw treadmill speed. Single source of truth.
     * @param adjustedKph Adjusted (perceived) speed
     * @param rawInclinePercent Raw treadmill incline (before adjustment). Used for auto mode incline correction.
     */
    fun adjustedToRawSpeed(adjustedKph: Double, rawInclinePercent: Double = 0.0): Double = if (speedCalibrationAuto) {
        SpeedCalibrationManager.invertPolynomialNewtonRaphson(
            getPolynomialCoefficients(), adjustedKph, clampedCalibrationSinTheta(rawInclinePercent)
        )
    } else {
        (adjustedKph - speedCalibrationB) / paceCoefficient
    }

    // Incline adjustment (effective incline = treadmill incline - adjustment)
    // Default 1.0 means 1% treadmill incline = flat outdoor running
    @Volatile var inclineAdjustment = 1.0

    /** Raw treadmill incline = effective incline + adjustment. For speed calibration. */
    val currentRawInclinePercent: Double get() = currentInclinePercent + inclineAdjustment

    /**
     * Get the adjusted speed for display and recording.
     * Prefers [targetAdjustedSpeedKph] (stable during incline transitions) over
     * recomputing from telemetry (which wobbles when incline is changing).
     * Falls back to [rawToAdjustedSpeed] when no target is set (e.g., physical button start).
     */
    fun getDisplayAdjustedSpeed(): Double {
        val target = targetAdjustedSpeedKph
        return if (target > 0 && currentSpeedKph > 0) target
        else rawToAdjustedSpeed(currentSpeedKph, currentRawInclinePercent)
    }

    // Incline power coefficient (0.0 = no adjustment, 1.0 = full theoretical adjustment)
    @Volatile var inclinePowerCoefficient = 0.5

    // User profile for calorie and training metrics calculation
    @Volatile var userWeightKg = 70.0
    @Volatile var userAge = 35
    @Volatile var userIsMale = true
    @Volatile var userHrRest = 60      // Resting HR
    @Volatile var userFtpWatts = 250   // Functional Threshold Power for running (watts)
    @Volatile var userLthrBpm = 170    // Lactate Threshold HR in BPM

    // Heart rate zones as % of LTHR (zone start boundaries)
    // zone2Start = 80% means values >= 80% of LTHR are zone 2
    // Stored with 1 decimal precision for integer BPM snapping
    @Volatile var hrZone2StartPercent = 80.0   // ~136 bpm at LTHR=170
    @Volatile var hrZone3StartPercent = 88.0   // ~150 bpm
    @Volatile var hrZone4StartPercent = 95.0   // ~162 bpm
    @Volatile var hrZone5StartPercent = 102.0  // ~173 bpm (slightly above LTHR)

    // Cached absolute HR zone boundaries (from LTHR and percentages)
    // Call invalidateHrZoneCaches() after changing userLthrBpm or hrZone*StartPercent
    var hrZone2Start: Int = kotlin.math.round(hrZone2StartPercent * userLthrBpm / 100.0).toInt()
        private set
    var hrZone3Start: Int = kotlin.math.round(hrZone3StartPercent * userLthrBpm / 100.0).toInt()
        private set
    var hrZone4Start: Int = kotlin.math.round(hrZone4StartPercent * userLthrBpm / 100.0).toInt()
        private set
    var hrZone5Start: Int = kotlin.math.round(hrZone5StartPercent * userLthrBpm / 100.0).toInt()
        private set

    fun invalidateHrZoneCaches() {
        hrZone2Start = kotlin.math.round(hrZone2StartPercent * userLthrBpm / 100.0).toInt()
        hrZone3Start = kotlin.math.round(hrZone3StartPercent * userLthrBpm / 100.0).toInt()
        hrZone4Start = kotlin.math.round(hrZone4StartPercent * userLthrBpm / 100.0).toInt()
        hrZone5Start = kotlin.math.round(hrZone5StartPercent * userLthrBpm / 100.0).toInt()
    }

    // Power zones as % of FTP (zone start boundaries)
    // zone2Start = 55% means values >= 55% of FTP are zone 2
    // Stored with 1 decimal precision for integer watt snapping
    @Volatile var powerZone2StartPercent = 55.0   // Recovery ends, Endurance begins
    @Volatile var powerZone3StartPercent = 75.0   // Endurance ends, Tempo begins
    @Volatile var powerZone4StartPercent = 90.0   // Tempo ends, Threshold begins
    @Volatile var powerZone5StartPercent = 105.0  // Threshold ends, VO2max begins

    // Cached absolute Power zone boundaries (from FTP and percentages)
    // Call invalidatePowerZoneCaches() after changing userFtpWatts or powerZone*StartPercent
    var powerZone2Start: Int = kotlin.math.round(powerZone2StartPercent * userFtpWatts / 100.0).toInt()
        private set
    var powerZone3Start: Int = kotlin.math.round(powerZone3StartPercent * userFtpWatts / 100.0).toInt()
        private set
    var powerZone4Start: Int = kotlin.math.round(powerZone4StartPercent * userFtpWatts / 100.0).toInt()
        private set
    var powerZone5Start: Int = kotlin.math.round(powerZone5StartPercent * userFtpWatts / 100.0).toInt()
        private set

    fun invalidatePowerZoneCaches() {
        powerZone2Start = kotlin.math.round(powerZone2StartPercent * userFtpWatts / 100.0).toInt()
        powerZone3Start = kotlin.math.round(powerZone3StartPercent * userFtpWatts / 100.0).toInt()
        powerZone4Start = kotlin.math.round(powerZone4StartPercent * userFtpWatts / 100.0).toInt()
        powerZone5Start = kotlin.math.round(powerZone5StartPercent * userFtpWatts / 100.0).toInt()
    }

    // Workout editor defaults
    @Volatile var thresholdPaceKph = 10.0  // User's threshold pace (6:00/km default)

    // ==================== HR Auto-Adjust Settings ====================
    @Volatile var hrTrendWindowSeconds = 30
    @Volatile var hrTrendThreshold = 2.0
    @Volatile var hrSettlingTimeSeconds = 30
    @Volatile var hrMinTimeBetweenAdjSeconds = 10
    @Volatile var hrSpeedAdjustmentKph = 0.2
    @Volatile var hrSpeedUrgentAdjustmentKph = 0.5
    @Volatile var hrInclineAdjustmentPercent = 0.5
    @Volatile var hrInclineUrgentAdjustmentPercent = 1.0
    @Volatile var hrUrgentAboveThresholdBpm = 8
    @Volatile var hrMaxSpeedAdjustmentKph = 3.0
    @Volatile var hrMaxInclineAdjustmentPercent = 5.0

    // ==================== Power Auto-Adjust Settings ====================
    // Power is instant but noisy, needs different tuning than HR
    @Volatile var powerTrendWindowSeconds = 10      // Shorter window (power is instant)
    @Volatile var powerTrendThreshold = 5.0         // Wider threshold (power is noisy)
    @Volatile var powerSettlingTimeSeconds = 5      // Much shorter settling (instant response)
    @Volatile var powerMinTimeBetweenAdjSeconds = 5 // Faster adjustments allowed
    @Volatile var powerSpeedAdjustmentKph = 0.2
    @Volatile var powerSpeedUrgentAdjustmentKph = 0.5
    @Volatile var powerInclineAdjustmentPercent = 0.5
    @Volatile var powerInclineUrgentAdjustmentPercent = 1.0
    @Volatile var powerUrgentAboveThresholdWatts = 20  // Watts above max for urgent
    @Volatile var powerMaxSpeedAdjustmentKph = 3.0
    @Volatile var powerMaxInclineAdjustmentPercent = 5.0

    // ==================== FIT Export Device Identification ====================
    // Configure to match your primary Garmin device for Training Status compatibility
    @Volatile var fitManufacturer: Int = 1              // 1 = Garmin
    @Volatile var fitProductId: Int = 4565             // Forerunner 970
    @Volatile var fitDeviceSerial: Long = 1234567890L
    @Volatile var fitSoftwareVersion: Int = 1552       // 15.52

    // ==================== FIT Export Speed Source ====================
    @Volatile var fitUseStrydSpeed = true   // default ON: use Stryd speed when available

    // ==================== Garmin Connect Upload ====================
    @Volatile var garminAutoUploadEnabled = false

    // ==================== DFA Alpha1 State ====================
    // Per-sensor DFA results: MAC → latest DfaResult (computed for ALL RR-capable sensors)
    val dfaResults: ConcurrentHashMap<String, DfaAlpha1Calculator.DfaResult> = ConcurrentHashMap()

    @Volatile var savedDfaSensorMac: String = ""      // persisted user choice (displayed in HUD box)
    @Volatile var activeDfaSensorMac: String = ""     // currently active primary

    // DFA Alpha1 configuration (user-tunable, persisted in SharedPreferences)
    @Volatile var dfaWindowDurationSec: Int = 120         // time-based window (seconds)
    @Volatile var dfaArtifactThreshold: Double = 20.0     // percent deviation from median
    @Volatile var dfaMedianWindow: Int = 11               // median filter window (beats)
    @Volatile var dfaEmaAlpha: Double = 0.2               // EMA smoothing factor

    // ==================== Calculated HR Settings ====================
    @Volatile var calcHrEnabled: Boolean = false
    @Volatile var calcHrEmaAlpha: Double = 0.1
    @Volatile var calcHrArtifactThreshold: Double = 20.0  // % deviation from median to reject (0 = disabled)
    @Volatile var calcHrMedianWindow: Int = 11               // median filter window (beats)

    // ==================== Chart Settings ====================
    @Volatile var chartZoomTimeframeMinutes = 3  // Default 3 minutes for TIMEFRAME zoom mode

    // ==================== FTMS Server Settings ====================
    // Control BLE and DirCon (WiFi) FTMS server behavior
    @Volatile var ftmsBleReadEnabled = false           // BLE: broadcast treadmill data
    @Volatile var ftmsBleControlEnabled = false        // BLE: allow external apps to control
    @Volatile var ftmsDirConReadEnabled = false        // DirCon: broadcast via WiFi
    @Volatile var ftmsDirConControlEnabled = false     // DirCon: allow control via WiFi
    @Volatile var ftmsBleDeviceName = ""               // Empty = use default (treadmill name + " BLE")
    @Volatile var ftmsDirConDeviceName = ""            // Empty = use default (treadmill name + " DirCon")
    @Volatile var treadmillName = ""                   // Captured from GlassOS ConsoleInfo

    // ==================== Treadmill Capabilities ====================
    // Dynamic speed/incline values populated from treadmill
    var speedValues: List<Double> = emptyList()
    var inclineValues: List<Int> = emptyList()
    var minSpeedKph = 1.6
    var maxSpeedKph = 20.0
    var minInclinePercent = -6.0
    var maxInclinePercent = 40.0

    // ==================== Panel Visibility State ====================
    val isHudVisible = AtomicBoolean(false)
    val isChartVisible = AtomicBoolean(false)
    val isWorkoutPanelVisible = AtomicBoolean(false)

    // ==================== Workout Start State ====================
    // True when a programmatic workout start is in progress (to prevent race with handlePhysicalStart)
    val isStartingWorkoutProgrammatically = AtomicBoolean(false)

    // ==================== GlassOS Reconnection State ====================
    // True briefly after GlassOS reconnects, to ignore the initial IDLE state
    // (GlassOS sends IDLE on startup, but our run data is still in memory)
    val isReconnecting = AtomicBoolean(false)

    // ==================== Connection State ====================
    @Volatile var isRunning = true
    @Volatile var reconnectAttempts = 0
    val maxReconnectAttempts = -1 // -1 = infinite

    // ==================== Screen Dimensions ====================
    var screenWidth = 0
    var screenHeight = 0

    companion object {
        /** Generate speed values at 0.5 kph intervals within the given range. */
        fun generateSpeedValues(minKph: Double, maxKph: Double): List<Double> {
            val values = mutableListOf<Double>()
            val startSpeed = ceil(minKph * 2.0) / 2.0
            val endSpeed = floor(maxKph * 2.0) / 2.0
            var speed = startSpeed
            while (speed <= endSpeed) {
                values.add(speed)
                speed += 0.5
            }
            return values
        }

        /** Generate incline values: 1% steps below 6%, 2% steps at 6% and above. */
        fun generateInclineValues(minPercent: Double, maxPercent: Double): List<Int> {
            val values = mutableListOf<Int>()
            var incline = minPercent.toInt()
            while (incline <= maxPercent) {
                values.add(incline)
                incline += if (incline < 6) 1 else 2
            }
            return values
        }
    }
}
