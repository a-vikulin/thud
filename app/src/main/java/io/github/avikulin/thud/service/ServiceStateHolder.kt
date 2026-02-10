package io.github.avikulin.thud.service

import com.ifit.glassos.workout.WorkoutState
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
    @Volatile var currentHeartRateBpm = 0.0
    @Volatile var currentElapsedSeconds = 0

    // ==================== Stryd Foot Pod State ====================
    @Volatile var currentPowerWatts = 0.0       // Adjusted power (raw + incline contribution)
    @Volatile var currentRawPowerWatts = 0.0    // Raw power from Stryd (before incline adjustment)
    @Volatile var currentCadenceSpm = 0
    @Volatile var currentStrydSpeedKph = 0.0  // Speed from Stryd foot pod
    @Volatile var strydConnected = false

    // ==================== HR Sensor State ====================
    @Volatile var hrSensorConnected = false

    // ==================== Settings State ====================
    // Pace adjustment coefficient (actual pace = treadmill pace * coefficient)
    @Volatile var paceCoefficient = 1.0

    // Incline adjustment (effective incline = treadmill incline - adjustment)
    // Default 1.0 means 1% treadmill incline = flat outdoor running
    @Volatile var inclineAdjustment = 1.0

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

    // Computed absolute HR zone boundaries (from LTHR and percentages)
    val hrZone2Start: Int get() = kotlin.math.round(hrZone2StartPercent * userLthrBpm / 100.0).toInt()
    val hrZone3Start: Int get() = kotlin.math.round(hrZone3StartPercent * userLthrBpm / 100.0).toInt()
    val hrZone4Start: Int get() = kotlin.math.round(hrZone4StartPercent * userLthrBpm / 100.0).toInt()
    val hrZone5Start: Int get() = kotlin.math.round(hrZone5StartPercent * userLthrBpm / 100.0).toInt()

    // Power zones as % of FTP (zone start boundaries)
    // zone2Start = 55% means values >= 55% of FTP are zone 2
    // Stored with 1 decimal precision for integer watt snapping
    @Volatile var powerZone2StartPercent = 55.0   // Recovery ends, Endurance begins
    @Volatile var powerZone3StartPercent = 75.0   // Endurance ends, Tempo begins
    @Volatile var powerZone4StartPercent = 90.0   // Tempo ends, Threshold begins
    @Volatile var powerZone5StartPercent = 105.0  // Threshold ends, VO2max begins

    // Computed absolute Power zone boundaries (from FTP and percentages)
    val powerZone2Start: Int get() = kotlin.math.round(powerZone2StartPercent * userFtpWatts / 100.0).toInt()
    val powerZone3Start: Int get() = kotlin.math.round(powerZone3StartPercent * userFtpWatts / 100.0).toInt()
    val powerZone4Start: Int get() = kotlin.math.round(powerZone4StartPercent * userFtpWatts / 100.0).toInt()
    val powerZone5Start: Int get() = kotlin.math.round(powerZone5StartPercent * userFtpWatts / 100.0).toInt()

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

    // ==================== Garmin Connect Upload ====================
    @Volatile var garminAutoUploadEnabled = false

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
