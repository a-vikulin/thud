package io.github.avikulin.thud.service

import android.app.Service
import android.content.SharedPreferences
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import io.github.avikulin.thud.R
import io.github.avikulin.thud.ui.components.OneKnobSlider
import io.github.avikulin.thud.ui.components.ZoneSlider
import io.github.avikulin.thud.ui.components.TouchSpinner
import io.github.avikulin.thud.util.PaceConverter

/**
 * Manages the settings dialog UI and preference persistence.
 */
class SettingsManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val prefs: SharedPreferences,
    private val state: ServiceStateHolder
) {
    companion object {
        private const val TAG = "SettingsManager"

        // SharedPreferences name
        const val PREFS_NAME = "TreadmillHUD"

        // SharedPreferences keys
        const val PREF_PACE_COEFFICIENT = "pace_coefficient"
        const val PREF_INCLINE_ADJUSTMENT = "incline_adjustment"
        const val PREF_INCLINE_POWER_COEFFICIENT = "incline_power_coefficient"
        const val PREF_CHART_VISIBLE = "chart_visible"
        const val PREF_WORKOUT_PANEL_VISIBLE = "workout_panel_visible"
        const val PREF_THRESHOLD_PACE_KPH = "threshold_pace_kph"
        const val PREF_DEFAULT_INCLINE = "default_incline"

        // Treadmill capability settings
        const val PREF_TREADMILL_MIN_SPEED_KPH = "treadmill_min_speed_kph"
        const val PREF_TREADMILL_MAX_SPEED_KPH = "treadmill_max_speed_kph"
        const val PREF_TREADMILL_MIN_INCLINE = "treadmill_min_incline"
        const val PREF_TREADMILL_MAX_INCLINE = "treadmill_max_incline"
        const val PREF_TREADMILL_INCLINE_STEP = "treadmill_incline_step"

        // User profile settings
        const val PREF_USER_WEIGHT_KG = "user_weight_kg"
        const val PREF_USER_AGE = "user_age"
        const val PREF_USER_IS_MALE = "user_is_male"
        const val PREF_USER_HR_REST = "user_hr_rest"
        const val PREF_USER_FTP_WATTS = "user_ftp_watts"

        // Foot pod settings
        const val PREF_FOOT_POD_METRIC = "foot_pod_metric"

        // LTHR and zone percentage settings (zone start boundaries)
        const val PREF_USER_LTHR_BPM = "user_lthr_bpm"
        // Keys use legacy names for backward compatibility with saved user settings
        const val PREF_HR_ZONE2_START_PERCENT = "hr_zone1_max_percent"
        const val PREF_HR_ZONE3_START_PERCENT = "hr_zone2_max_percent"
        const val PREF_HR_ZONE4_START_PERCENT = "hr_zone3_max_percent"
        const val PREF_HR_ZONE5_START_PERCENT = "hr_zone4_max_percent"
        const val PREF_POWER_ZONE2_START_PERCENT = "power_zone1_max_percent"
        const val PREF_POWER_ZONE3_START_PERCENT = "power_zone2_max_percent"
        const val PREF_POWER_ZONE4_START_PERCENT = "power_zone3_max_percent"
        const val PREF_POWER_ZONE5_START_PERCENT = "power_zone4_max_percent"

        // HR auto-adjust settings
        const val PREF_HR_TREND_WINDOW_SECONDS = "hr_trend_window_seconds"
        const val PREF_HR_TREND_THRESHOLD = "hr_trend_threshold"
        const val PREF_HR_SETTLING_TIME_SECONDS = "hr_settling_time_seconds"
        const val PREF_HR_MIN_TIME_BETWEEN_ADJ_SECONDS = "hr_min_time_between_adj_seconds"
        const val PREF_HR_SPEED_ADJUSTMENT_KPH = "hr_speed_adjustment_kph"
        const val PREF_HR_SPEED_URGENT_ADJUSTMENT_KPH = "hr_speed_urgent_adjustment_kph"
        const val PREF_HR_INCLINE_ADJUSTMENT_PERCENT = "hr_incline_adjustment_percent"
        const val PREF_HR_INCLINE_URGENT_ADJUSTMENT_PERCENT = "hr_incline_urgent_adjustment_percent"
        const val PREF_HR_URGENT_ABOVE_THRESHOLD_BPM = "hr_urgent_above_threshold_bpm"
        const val PREF_HR_MAX_SPEED_ADJUSTMENT_KPH = "hr_max_speed_adjustment_kph"
        const val PREF_HR_MAX_INCLINE_ADJUSTMENT_PERCENT = "hr_max_incline_adjustment_percent"

        // Power auto-adjust settings
        const val PREF_POWER_TREND_WINDOW_SECONDS = "power_trend_window_seconds"
        const val PREF_POWER_TREND_THRESHOLD = "power_trend_threshold"
        const val PREF_POWER_SETTLING_TIME_SECONDS = "power_settling_time_seconds"
        const val PREF_POWER_MIN_TIME_BETWEEN_ADJ_SECONDS = "power_min_time_between_adj_seconds"
        const val PREF_POWER_SPEED_ADJUSTMENT_KPH = "power_speed_adjustment_kph"
        const val PREF_POWER_SPEED_URGENT_ADJUSTMENT_KPH = "power_speed_urgent_adjustment_kph"
        const val PREF_POWER_INCLINE_ADJUSTMENT_PERCENT = "power_incline_adjustment_percent"
        const val PREF_POWER_INCLINE_URGENT_ADJUSTMENT_PERCENT = "power_incline_urgent_adjustment_percent"
        const val PREF_POWER_URGENT_ABOVE_THRESHOLD_WATTS = "power_urgent_above_threshold_watts"
        const val PREF_POWER_MAX_SPEED_ADJUSTMENT_KPH = "power_max_speed_adjustment_kph"
        const val PREF_POWER_MAX_INCLINE_ADJUSTMENT_PERCENT = "power_max_incline_adjustment_percent"

        // FIT Export device identification
        const val PREF_FIT_MANUFACTURER = "fit_manufacturer"
        const val PREF_FIT_PRODUCT_ID = "fit_product_id"
        const val PREF_FIT_DEVICE_SERIAL = "fit_device_serial"
        const val PREF_FIT_SOFTWARE_VERSION = "fit_software_version"

        // Garmin Connect upload
        const val PREF_GARMIN_AUTO_UPLOAD = "garmin_auto_upload"

        // FTMS Server settings
        const val PREF_FTMS_BLE_READ_ENABLED = "ftms_ble_read_enabled"
        const val PREF_FTMS_BLE_CONTROL_ENABLED = "ftms_ble_control_enabled"
        const val PREF_FTMS_DIRCON_READ_ENABLED = "ftms_dircon_read_enabled"
        const val PREF_FTMS_DIRCON_CONTROL_ENABLED = "ftms_dircon_control_enabled"
        const val PREF_FTMS_BLE_DEVICE_NAME = "ftms_ble_device_name"
        const val PREF_FTMS_DIRCON_DEVICE_NAME = "ftms_dircon_device_name"

        // Chart settings
        const val PREF_CHART_ZOOM_TIMEFRAME_MINUTES = "chart_zoom_timeframe_minutes"

        // Remote control settings
        const val PREF_REMOTE_BINDINGS = "remote_bindings"

        // Multi-sensor HR: primary sensor MAC or sentinel
        const val PREF_PRIMARY_HR_MAC = "primary_hr_mac"
        const val HR_PRIMARY_AVERAGE = "AVERAGE"  // sentinel: compute mean of all connected sensors

        // DFA alpha1: primary sensor MAC for HUD display + Garmin upload
        const val PREF_DFA_SENSOR_MAC = "dfa_sensor_mac"

        // DFA alpha1 algorithm configuration
        const val PREF_DFA_WINDOW_DURATION_SEC = "dfa_window_duration_sec"
        const val PREF_DFA_ARTIFACT_THRESHOLD = "dfa_artifact_threshold"
        const val PREF_DFA_MEDIAN_WINDOW = "dfa_median_window"
        const val PREF_DFA_EMA_ALPHA = "dfa_ema_alpha"

        // Defaults
        const val DEFAULT_PACE_COEFFICIENT = 1.0
        const val DEFAULT_INCLINE_ADJUSTMENT = 1.0  // 1% treadmill = flat outdoor
        const val DEFAULT_INCLINE_POWER_COEFFICIENT = 0.5
        const val DEFAULT_THRESHOLD_PACE_KPH = 10.0  // 6:00/km pace
        const val DEFAULT_INCLINE = 0.0

        // User profile defaults
        const val DEFAULT_USER_WEIGHT_KG = 70.0
        const val DEFAULT_USER_AGE = 35
        const val DEFAULT_USER_IS_MALE = true
        const val DEFAULT_USER_HR_REST = 60
        const val DEFAULT_USER_FTP_WATTS = 250 // Running FTP in watts

        // Chart defaults
        const val DEFAULT_CHART_ZOOM_TIMEFRAME_MINUTES = 3

        // DFA alpha1 defaults (matching DfaAlpha1Calculator constructor defaults)
        const val DEFAULT_DFA_WINDOW_DURATION_SEC = 120
        const val DEFAULT_DFA_ARTIFACT_THRESHOLD = 20.0
        const val DEFAULT_DFA_MEDIAN_WINDOW = 11
        const val DEFAULT_DFA_EMA_ALPHA = 0.2

        // Foot pod defaults
        const val DEFAULT_FOOT_POD_METRIC = "cadence"  // cadence, power, gct, vo, stryd_pace

        // Treadmill capability defaults (typical home treadmill)
        const val DEFAULT_TREADMILL_MIN_SPEED_KPH = 2.0    // ~60:00/km
        const val DEFAULT_TREADMILL_MAX_SPEED_KPH = 20.0   // 3:00/km
        const val DEFAULT_TREADMILL_MIN_INCLINE = 0.0
        const val DEFAULT_TREADMILL_MAX_INCLINE = 15.0
        const val DEFAULT_TREADMILL_INCLINE_STEP = 0.5

        // LTHR and zone percentage defaults (zone start boundaries)
        const val DEFAULT_USER_LTHR_BPM = 170
        const val DEFAULT_HR_ZONE2_START_PERCENT = 80   // Zone 2 starts at 80% of LTHR
        const val DEFAULT_HR_ZONE3_START_PERCENT = 88
        const val DEFAULT_HR_ZONE4_START_PERCENT = 95
        const val DEFAULT_HR_ZONE5_START_PERCENT = 102
        const val DEFAULT_POWER_ZONE2_START_PERCENT = 55  // Zone 2 starts at 55% of FTP
        const val DEFAULT_POWER_ZONE3_START_PERCENT = 75
        const val DEFAULT_POWER_ZONE4_START_PERCENT = 90
        const val DEFAULT_POWER_ZONE5_START_PERCENT = 105

        // HR auto-adjust defaults (matching AdjustmentController.Config)
        const val DEFAULT_HR_TREND_WINDOW_SECONDS = 30
        const val DEFAULT_HR_TREND_THRESHOLD = 2.0
        const val DEFAULT_HR_SETTLING_TIME_SECONDS = 30
        const val DEFAULT_HR_MIN_TIME_BETWEEN_ADJ_SECONDS = 10
        const val DEFAULT_HR_SPEED_ADJUSTMENT_KPH = 0.1
        const val DEFAULT_HR_SPEED_URGENT_ADJUSTMENT_KPH = 0.5
        const val DEFAULT_HR_INCLINE_ADJUSTMENT_PERCENT = 0.5
        const val DEFAULT_HR_INCLINE_URGENT_ADJUSTMENT_PERCENT = 2.0
        const val DEFAULT_HR_URGENT_ABOVE_THRESHOLD_BPM = 8
        const val DEFAULT_HR_MAX_SPEED_ADJUSTMENT_KPH = 3.0
        const val DEFAULT_HR_MAX_INCLINE_ADJUSTMENT_PERCENT = 5.0

        // Power auto-adjust defaults (faster response than HR)
        const val DEFAULT_POWER_TREND_WINDOW_SECONDS = 20
        const val DEFAULT_POWER_TREND_THRESHOLD = 10.0
        const val DEFAULT_POWER_SETTLING_TIME_SECONDS = 20
        const val DEFAULT_POWER_MIN_TIME_BETWEEN_ADJ_SECONDS = 5
        const val DEFAULT_POWER_SPEED_ADJUSTMENT_KPH = 0.1
        const val DEFAULT_POWER_SPEED_URGENT_ADJUSTMENT_KPH = 0.5
        const val DEFAULT_POWER_INCLINE_ADJUSTMENT_PERCENT = 0.5
        const val DEFAULT_POWER_INCLINE_URGENT_ADJUSTMENT_PERCENT = 2.0
        const val DEFAULT_POWER_URGENT_ABOVE_THRESHOLD_WATTS = 30
        const val DEFAULT_POWER_MAX_SPEED_ADJUSTMENT_KPH = 3.0
        const val DEFAULT_POWER_MAX_INCLINE_ADJUSTMENT_PERCENT = 5.0

        // FIT Export device identification defaults (Forerunner 970)
        const val DEFAULT_FIT_MANUFACTURER = 1              // Garmin
        const val DEFAULT_FIT_PRODUCT_ID = 4565             // Forerunner 970
        const val DEFAULT_FIT_DEVICE_SERIAL = 1234567890L
        const val DEFAULT_FIT_SOFTWARE_VERSION = 1552       // 15.52

        /**
         * Helper to read a value that may have been stored as Int (old) or Float (new).
         * Handles migration from Int to Float storage for zone percentages.
         */
        fun getFloatOrInt(prefs: android.content.SharedPreferences, key: String, default: Int): Double {
            return try {
                prefs.getFloat(key, default.toFloat()).toDouble()
            } catch (e: ClassCastException) {
                // Old value stored as Int, read and convert
                prefs.getInt(key, default).toDouble()
            }
        }
    }

    /**
     * Callback interface for settings events.
     */
    interface Listener {
        fun onSettingsSaved()
        fun onGarminLoginRequested()
        fun isGarminAuthenticated(): Boolean
    }

    var listener: Listener? = null

    private var settingsDialogView: View? = null
    private var settingsOneKnobSlider: OneKnobSlider? = null
    private var settingsInclinePowerSlider: OneKnobSlider? = null
    private var settingsHrZoneSlider: ZoneSlider? = null
    private var settingsPowerZoneSlider: ZoneSlider? = null

    // Tab content containers
    private var dynamicsContent: View? = null
    private var zonesContent: View? = null
    private var autoAdjustContent: View? = null
    private var fitExportContent: View? = null
    private var ftmsContent: View? = null
    private var currentTabIndex = 0
    private val tabButtons = mutableListOf<Button>()

    // Dynamics tab spinners
    private var spinnerInclineAdjustment: TouchSpinner? = null
    private var spinnerWeight: TouchSpinner? = null
    private var spinnerAge: TouchSpinner? = null
    private var spinnerHrRest: TouchSpinner? = null
    private var spinnerThresholdPace: TouchSpinner? = null
    private var buttonMale: Button? = null
    private var buttonFemale: Button? = null
    private var selectedIsMale: Boolean = true

    // Zones tab spinners
    private var spinnerLthr: TouchSpinner? = null
    private var spinnerFtp: TouchSpinner? = null

    // HR auto-adjust spinners
    private var spinnerHrTrendWindow: TouchSpinner? = null
    private var spinnerHrTrendThreshold: TouchSpinner? = null
    private var spinnerHrSettlingTime: TouchSpinner? = null
    private var spinnerHrAdjCooldown: TouchSpinner? = null
    private var spinnerHrSpeedAdj: TouchSpinner? = null
    private var spinnerHrSpeedUrgentAdj: TouchSpinner? = null
    private var spinnerHrInclineAdj: TouchSpinner? = null
    private var spinnerHrInclineUrgentAdj: TouchSpinner? = null
    private var spinnerHrUrgentThreshold: TouchSpinner? = null
    private var spinnerHrMaxSpeedAdj: TouchSpinner? = null
    private var spinnerHrMaxInclineAdj: TouchSpinner? = null

    // Power auto-adjust spinners
    private var spinnerPowerTrendWindow: TouchSpinner? = null
    private var spinnerPowerTrendThreshold: TouchSpinner? = null
    private var spinnerPowerSettlingTime: TouchSpinner? = null
    private var spinnerPowerAdjCooldown: TouchSpinner? = null
    private var spinnerPowerSpeedAdj: TouchSpinner? = null
    private var spinnerPowerSpeedUrgentAdj: TouchSpinner? = null
    private var spinnerPowerInclineAdj: TouchSpinner? = null
    private var spinnerPowerInclineUrgentAdj: TouchSpinner? = null
    private var spinnerPowerUrgentThreshold: TouchSpinner? = null
    private var spinnerPowerMaxSpeedAdj: TouchSpinner? = null
    private var spinnerPowerMaxInclineAdj: TouchSpinner? = null

    // FIT Export tab fields - Garmin Connect
    private var checkGarminAutoUpload: CheckBox? = null
    private var btnGarminLogin: Button? = null

    // FIT Export tab fields
    private var editFitManufacturer: EditText? = null
    private var editFitProductId: EditText? = null
    private var editFitDeviceSerial: EditText? = null
    private var editFitSoftwareVersion: EditText? = null

    // DFA Alpha1 tab fields
    private var dfaAlpha1Content: View? = null
    private var spinnerDfaWindowDuration: TouchSpinner? = null
    private var spinnerDfaArtifactThreshold: TouchSpinner? = null
    private var spinnerDfaMedianWindow: TouchSpinner? = null
    private var spinnerDfaEmaAlpha: TouchSpinner? = null

    // Chart tab fields
    private var chartContent: View? = null
    private var spinnerZoomTimeframe: TouchSpinner? = null

    // FTMS tab fields
    private var checkFtmsBleRead: CheckBox? = null
    private var checkFtmsBleControl: CheckBox? = null
    private var checkFtmsDirConRead: CheckBox? = null
    private var checkFtmsDirConControl: CheckBox? = null
    private var editFtmsBleDeviceName: EditText? = null
    private var editFtmsDirConDeviceName: EditText? = null

    val isDialogVisible: Boolean
        get() = settingsDialogView != null

    /**
     * Load settings from SharedPreferences into state holder.
     */
    fun loadSettings() {
        state.paceCoefficient = prefs.getFloat(PREF_PACE_COEFFICIENT, DEFAULT_PACE_COEFFICIENT.toFloat()).toDouble()
        state.inclineAdjustment = prefs.getFloat(PREF_INCLINE_ADJUSTMENT, DEFAULT_INCLINE_ADJUSTMENT.toFloat()).toDouble()
        state.inclinePowerCoefficient = prefs.getFloat(PREF_INCLINE_POWER_COEFFICIENT, DEFAULT_INCLINE_POWER_COEFFICIENT.toFloat()).toDouble()
        state.thresholdPaceKph = prefs.getFloat(PREF_THRESHOLD_PACE_KPH, DEFAULT_THRESHOLD_PACE_KPH.toFloat()).toDouble()
        Log.d(TAG, "Loaded thresholdPaceKph=${state.thresholdPaceKph} from SharedPreferences")

        // User profile settings
        state.userWeightKg = prefs.getFloat(PREF_USER_WEIGHT_KG, DEFAULT_USER_WEIGHT_KG.toFloat()).toDouble()
        state.userAge = prefs.getInt(PREF_USER_AGE, DEFAULT_USER_AGE)
        state.userIsMale = prefs.getBoolean(PREF_USER_IS_MALE, DEFAULT_USER_IS_MALE)
        state.userHrRest = prefs.getInt(PREF_USER_HR_REST, DEFAULT_USER_HR_REST)

        // LTHR, FTP, and zone percentages
        state.userLthrBpm = prefs.getInt(PREF_USER_LTHR_BPM, DEFAULT_USER_LTHR_BPM)
        state.userFtpWatts = prefs.getInt(PREF_USER_FTP_WATTS, DEFAULT_USER_FTP_WATTS)
        // Zone percentages stored as Float for 1 decimal precision (integer BPM/watt snapping)
        // Migration: older versions stored as Int, so handle ClassCastException
        state.hrZone2StartPercent = getFloatOrInt(prefs, PREF_HR_ZONE2_START_PERCENT, DEFAULT_HR_ZONE2_START_PERCENT)
        state.hrZone3StartPercent = getFloatOrInt(prefs, PREF_HR_ZONE3_START_PERCENT, DEFAULT_HR_ZONE3_START_PERCENT)
        state.hrZone4StartPercent = getFloatOrInt(prefs, PREF_HR_ZONE4_START_PERCENT, DEFAULT_HR_ZONE4_START_PERCENT)
        state.hrZone5StartPercent = getFloatOrInt(prefs, PREF_HR_ZONE5_START_PERCENT, DEFAULT_HR_ZONE5_START_PERCENT)
        state.powerZone2StartPercent = getFloatOrInt(prefs, PREF_POWER_ZONE2_START_PERCENT, DEFAULT_POWER_ZONE2_START_PERCENT)
        state.powerZone3StartPercent = getFloatOrInt(prefs, PREF_POWER_ZONE3_START_PERCENT, DEFAULT_POWER_ZONE3_START_PERCENT)
        state.powerZone4StartPercent = getFloatOrInt(prefs, PREF_POWER_ZONE4_START_PERCENT, DEFAULT_POWER_ZONE4_START_PERCENT)
        state.powerZone5StartPercent = getFloatOrInt(prefs, PREF_POWER_ZONE5_START_PERCENT, DEFAULT_POWER_ZONE5_START_PERCENT)

        // Recompute cached absolute zone boundaries from updated percentages/thresholds
        state.invalidateHrZoneCaches()
        state.invalidatePowerZoneCaches()

        // HR auto-adjust settings
        state.hrTrendWindowSeconds = prefs.getInt(PREF_HR_TREND_WINDOW_SECONDS, DEFAULT_HR_TREND_WINDOW_SECONDS)
        state.hrTrendThreshold = prefs.getFloat(PREF_HR_TREND_THRESHOLD, DEFAULT_HR_TREND_THRESHOLD.toFloat()).toDouble()
        state.hrSettlingTimeSeconds = prefs.getInt(PREF_HR_SETTLING_TIME_SECONDS, DEFAULT_HR_SETTLING_TIME_SECONDS)
        state.hrMinTimeBetweenAdjSeconds = prefs.getInt(PREF_HR_MIN_TIME_BETWEEN_ADJ_SECONDS, DEFAULT_HR_MIN_TIME_BETWEEN_ADJ_SECONDS)
        state.hrSpeedAdjustmentKph = prefs.getFloat(PREF_HR_SPEED_ADJUSTMENT_KPH, DEFAULT_HR_SPEED_ADJUSTMENT_KPH.toFloat()).toDouble()
        state.hrSpeedUrgentAdjustmentKph = prefs.getFloat(PREF_HR_SPEED_URGENT_ADJUSTMENT_KPH, DEFAULT_HR_SPEED_URGENT_ADJUSTMENT_KPH.toFloat()).toDouble()
        state.hrInclineAdjustmentPercent = prefs.getFloat(PREF_HR_INCLINE_ADJUSTMENT_PERCENT, DEFAULT_HR_INCLINE_ADJUSTMENT_PERCENT.toFloat()).toDouble()
        state.hrInclineUrgentAdjustmentPercent = prefs.getFloat(PREF_HR_INCLINE_URGENT_ADJUSTMENT_PERCENT, DEFAULT_HR_INCLINE_URGENT_ADJUSTMENT_PERCENT.toFloat()).toDouble()
        state.hrUrgentAboveThresholdBpm = prefs.getInt(PREF_HR_URGENT_ABOVE_THRESHOLD_BPM, DEFAULT_HR_URGENT_ABOVE_THRESHOLD_BPM)
        state.hrMaxSpeedAdjustmentKph = prefs.getFloat(PREF_HR_MAX_SPEED_ADJUSTMENT_KPH, DEFAULT_HR_MAX_SPEED_ADJUSTMENT_KPH.toFloat()).toDouble()
        state.hrMaxInclineAdjustmentPercent = prefs.getFloat(PREF_HR_MAX_INCLINE_ADJUSTMENT_PERCENT, DEFAULT_HR_MAX_INCLINE_ADJUSTMENT_PERCENT.toFloat()).toDouble()

        // Power auto-adjust settings
        state.powerTrendWindowSeconds = prefs.getInt(PREF_POWER_TREND_WINDOW_SECONDS, DEFAULT_POWER_TREND_WINDOW_SECONDS)
        state.powerTrendThreshold = prefs.getFloat(PREF_POWER_TREND_THRESHOLD, DEFAULT_POWER_TREND_THRESHOLD.toFloat()).toDouble()
        state.powerSettlingTimeSeconds = prefs.getInt(PREF_POWER_SETTLING_TIME_SECONDS, DEFAULT_POWER_SETTLING_TIME_SECONDS)
        state.powerMinTimeBetweenAdjSeconds = prefs.getInt(PREF_POWER_MIN_TIME_BETWEEN_ADJ_SECONDS, DEFAULT_POWER_MIN_TIME_BETWEEN_ADJ_SECONDS)
        state.powerSpeedAdjustmentKph = prefs.getFloat(PREF_POWER_SPEED_ADJUSTMENT_KPH, DEFAULT_POWER_SPEED_ADJUSTMENT_KPH.toFloat()).toDouble()
        state.powerSpeedUrgentAdjustmentKph = prefs.getFloat(PREF_POWER_SPEED_URGENT_ADJUSTMENT_KPH, DEFAULT_POWER_SPEED_URGENT_ADJUSTMENT_KPH.toFloat()).toDouble()
        state.powerInclineAdjustmentPercent = prefs.getFloat(PREF_POWER_INCLINE_ADJUSTMENT_PERCENT, DEFAULT_POWER_INCLINE_ADJUSTMENT_PERCENT.toFloat()).toDouble()
        state.powerInclineUrgentAdjustmentPercent = prefs.getFloat(PREF_POWER_INCLINE_URGENT_ADJUSTMENT_PERCENT, DEFAULT_POWER_INCLINE_URGENT_ADJUSTMENT_PERCENT.toFloat()).toDouble()
        state.powerUrgentAboveThresholdWatts = prefs.getInt(PREF_POWER_URGENT_ABOVE_THRESHOLD_WATTS, DEFAULT_POWER_URGENT_ABOVE_THRESHOLD_WATTS)
        state.powerMaxSpeedAdjustmentKph = prefs.getFloat(PREF_POWER_MAX_SPEED_ADJUSTMENT_KPH, DEFAULT_POWER_MAX_SPEED_ADJUSTMENT_KPH.toFloat()).toDouble()
        state.powerMaxInclineAdjustmentPercent = prefs.getFloat(PREF_POWER_MAX_INCLINE_ADJUSTMENT_PERCENT, DEFAULT_POWER_MAX_INCLINE_ADJUSTMENT_PERCENT.toFloat()).toDouble()

        // FIT Export device identification
        state.fitManufacturer = prefs.getInt(PREF_FIT_MANUFACTURER, DEFAULT_FIT_MANUFACTURER)
        state.fitProductId = prefs.getInt(PREF_FIT_PRODUCT_ID, DEFAULT_FIT_PRODUCT_ID)
        state.fitDeviceSerial = prefs.getLong(PREF_FIT_DEVICE_SERIAL, DEFAULT_FIT_DEVICE_SERIAL)
        state.fitSoftwareVersion = prefs.getInt(PREF_FIT_SOFTWARE_VERSION, DEFAULT_FIT_SOFTWARE_VERSION)

        // Garmin Connect upload
        state.garminAutoUploadEnabled = prefs.getBoolean(PREF_GARMIN_AUTO_UPLOAD, false)

        // Chart settings
        state.chartZoomTimeframeMinutes = prefs.getInt(PREF_CHART_ZOOM_TIMEFRAME_MINUTES, DEFAULT_CHART_ZOOM_TIMEFRAME_MINUTES)

        // FTMS Server settings (all disabled by default)
        state.ftmsBleReadEnabled = prefs.getBoolean(PREF_FTMS_BLE_READ_ENABLED, false)
        state.ftmsBleControlEnabled = prefs.getBoolean(PREF_FTMS_BLE_CONTROL_ENABLED, false)
        state.ftmsDirConReadEnabled = prefs.getBoolean(PREF_FTMS_DIRCON_READ_ENABLED, false)
        state.ftmsDirConControlEnabled = prefs.getBoolean(PREF_FTMS_DIRCON_CONTROL_ENABLED, false)
        state.ftmsBleDeviceName = prefs.getString(PREF_FTMS_BLE_DEVICE_NAME, "") ?: ""
        state.ftmsDirConDeviceName = prefs.getString(PREF_FTMS_DIRCON_DEVICE_NAME, "") ?: ""

        // Multi-sensor HR primary selection
        state.savedPrimaryHrMac = prefs.getString(PREF_PRIMARY_HR_MAC, HR_PRIMARY_AVERAGE) ?: HR_PRIMARY_AVERAGE

        // DFA alpha1 sensor selection
        state.savedDfaSensorMac = prefs.getString(PREF_DFA_SENSOR_MAC, "") ?: ""

        // DFA alpha1 algorithm configuration
        state.dfaWindowDurationSec = prefs.getInt(PREF_DFA_WINDOW_DURATION_SEC, DEFAULT_DFA_WINDOW_DURATION_SEC)
        state.dfaArtifactThreshold = prefs.getFloat(PREF_DFA_ARTIFACT_THRESHOLD, DEFAULT_DFA_ARTIFACT_THRESHOLD.toFloat()).toDouble()
        state.dfaMedianWindow = prefs.getInt(PREF_DFA_MEDIAN_WINDOW, DEFAULT_DFA_MEDIAN_WINDOW)
        state.dfaEmaAlpha = prefs.getFloat(PREF_DFA_EMA_ALPHA, DEFAULT_DFA_EMA_ALPHA.toFloat()).toDouble()

        Log.d(TAG, "Settings loaded: LTHR=${state.userLthrBpm}, FTP=${state.userFtpWatts}")
    }

    /**
     * Persist the user's primary HR sensor choice (MAC or HR_PRIMARY_AVERAGE sentinel).
     */
    fun savePrimaryHrMac(value: String) {
        prefs.edit { putString(PREF_PRIMARY_HR_MAC, value) }
        state.savedPrimaryHrMac = value
    }

    /**
     * Persist the user's DFA alpha1 sensor choice (MAC).
     */
    fun saveDfaSensorMac(value: String) {
        prefs.edit { putString(PREF_DFA_SENSOR_MAC, value) }
        state.savedDfaSensorMac = value
    }

    /**
     * Get threshold pace from preferences (for workout editor).
     */
    fun getThresholdPaceKph(): Double =
        prefs.getFloat(PREF_THRESHOLD_PACE_KPH, DEFAULT_THRESHOLD_PACE_KPH.toFloat()).toDouble()

    /**
     * Set threshold pace in preferences.
     */
    fun setThresholdPaceKph(kph: Double) {
        prefs.edit { putFloat(PREF_THRESHOLD_PACE_KPH, kph.toFloat()) }
        state.thresholdPaceKph = kph
    }

    // ==================== Treadmill Capabilities ====================
    // These values are populated by TelemetryManager when it connects to GlassOS

    /** Minimum speed the treadmill supports (kph) */
    fun getTreadmillMinSpeedKph(): Double =
        prefs.getFloat(PREF_TREADMILL_MIN_SPEED_KPH, DEFAULT_TREADMILL_MIN_SPEED_KPH.toFloat()).toDouble()

    /** Maximum speed the treadmill supports (kph) */
    fun getTreadmillMaxSpeedKph(): Double =
        prefs.getFloat(PREF_TREADMILL_MAX_SPEED_KPH, DEFAULT_TREADMILL_MAX_SPEED_KPH.toFloat()).toDouble()

    /** Minimum incline the treadmill supports (%) */
    fun getTreadmillMinIncline(): Double =
        prefs.getFloat(PREF_TREADMILL_MIN_INCLINE, DEFAULT_TREADMILL_MIN_INCLINE.toFloat()).toDouble()

    /** Maximum incline the treadmill supports (%) */
    fun getTreadmillMaxIncline(): Double =
        prefs.getFloat(PREF_TREADMILL_MAX_INCLINE, DEFAULT_TREADMILL_MAX_INCLINE.toFloat()).toDouble()

    /** Incline adjustment step size (%) - treadmill only supports multiples of this */
    fun getTreadmillInclineStep(): Double =
        prefs.getFloat(PREF_TREADMILL_INCLINE_STEP, DEFAULT_TREADMILL_INCLINE_STEP.toFloat()).toDouble()

    /** Save treadmill capabilities from GlassOS (called by TelemetryManager) */
    fun saveTreadmillCapabilities(minSpeedKph: Double, maxSpeedKph: Double,
                                   minIncline: Double, maxIncline: Double) {
        prefs.edit {
            putFloat(PREF_TREADMILL_MIN_SPEED_KPH, minSpeedKph.toFloat())
            putFloat(PREF_TREADMILL_MAX_SPEED_KPH, maxSpeedKph.toFloat())
            putFloat(PREF_TREADMILL_MIN_INCLINE, minIncline.toFloat())
            putFloat(PREF_TREADMILL_MAX_INCLINE, maxIncline.toFloat())
        }
    }

    /**
     * Show the settings dialog. If already visible, closes it.
     */
    fun showDialog() {
        // If dialog already open, close it
        if (settingsDialogView != null) {
            removeDialog()
            return
        }

        val resources = service.resources

        // Get dimensions from resources
        val dialogPaddingH = resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal)
        val dialogPaddingV = resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical)
        val buttonsTopMargin = resources.getDimensionPixelSize(R.dimen.dialog_buttons_top_margin)
        val dialogWidthFraction = resources.getFloat(R.dimen.dialog_width_fraction)
        val tabHeight = resources.getDimensionPixelSize(R.dimen.settings_tab_height)
        val tabMargin = resources.getDimensionPixelSize(R.dimen.settings_tab_margin)
        val contentHeight = resources.getDimensionPixelSize(R.dimen.settings_content_height)

        // Create main container
        val container = LinearLayout(service)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(ContextCompat.getColor(service, R.color.popup_background))
        container.setPadding(dialogPaddingH, dialogPaddingV, dialogPaddingH, dialogPaddingV)

        // ===== Tab Row =====
        val tabRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        tabButtons.clear()
        val tabNames = listOf(
            service.getString(R.string.settings_tab_dynamics),
            service.getString(R.string.settings_tab_zones),
            service.getString(R.string.settings_tab_auto_adjust),
            service.getString(R.string.settings_tab_fit_export),
            service.getString(R.string.settings_tab_ftms),
            service.getString(R.string.settings_tab_dfa_alpha1),
            service.getString(R.string.settings_tab_chart)
        )

        tabNames.forEachIndexed { index, name ->
            val tabBtn = Button(service).apply {
                text = name
                setTextColor(ContextCompat.getColor(service, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    tabHeight
                ).apply {
                    setMargins(tabMargin, 0, tabMargin, 0)
                }
                setOnClickListener { selectTab(index) }
            }
            tabButtons.add(tabBtn)
            tabRow.addView(tabBtn)
        }
        container.addView(tabRow)

        // ===== Content Area =====
        val contentContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                contentHeight
            ).apply {
                topMargin = buttonsTopMargin / 2
            }
        }

        // Create tab contents
        dynamicsContent = createDynamicsContent()
        zonesContent = createZonesContent()
        autoAdjustContent = createAutoAdjustContent()
        fitExportContent = createFitExportContent()
        ftmsContent = createFtmsContent()
        dfaAlpha1Content = createDfaAlpha1Content()
        chartContent = createChartContent()

        contentContainer.addView(dynamicsContent)
        contentContainer.addView(zonesContent)
        contentContainer.addView(autoAdjustContent)
        contentContainer.addView(fitExportContent)
        contentContainer.addView(ftmsContent)
        contentContainer.addView(dfaAlpha1Content)
        contentContainer.addView(chartContent)

        container.addView(contentContainer)

        // ===== Buttons Row =====
        val buttonsRow = LinearLayout(service)
        buttonsRow.orientation = LinearLayout.HORIZONTAL
        buttonsRow.gravity = Gravity.END
        buttonsRow.setPadding(0, buttonsTopMargin, 0, 0)

        // Cancel button
        val cancelBtn = Button(service)
        cancelBtn.text = service.getString(R.string.btn_cancel)
        cancelBtn.setOnClickListener {
            Log.d(TAG, "Cancel clicked")
            removeDialog()
        }
        buttonsRow.addView(cancelBtn)

        // Save button
        val saveBtn = Button(service)
        saveBtn.text = service.getString(R.string.btn_save)
        saveBtn.setOnClickListener {
            Log.d(TAG, "Save clicked")
            saveSettings()
            removeDialog()
        }
        buttonsRow.addView(saveBtn)

        container.addView(buttonsRow)

        settingsDialogView = container

        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        // Make dialog focusable so EditText fields can show keyboard (FIT Export tab)
        val dialogParams = OverlayHelper.createOverlayParams(dialogWidth, focusable = true)
        windowManager.addView(container, dialogParams)

        // Select first tab
        selectTab(0)
    }

    /**
     * Select a tab by index.
     */
    private fun selectTab(index: Int) {
        currentTabIndex = index

        // Update tab button appearances
        tabButtons.forEachIndexed { i, btn ->
            val isSelected = i == index
            btn.setBackgroundColor(
                ContextCompat.getColor(service,
                    if (isSelected) R.color.spinner_button_pressed else R.color.spinner_value_background
                )
            )
        }

        // Show/hide content
        dynamicsContent?.visibility = if (index == 0) View.VISIBLE else View.GONE
        zonesContent?.visibility = if (index == 1) View.VISIBLE else View.GONE
        autoAdjustContent?.visibility = if (index == 2) View.VISIBLE else View.GONE
        fitExportContent?.visibility = if (index == 3) View.VISIBLE else View.GONE
        ftmsContent?.visibility = if (index == 4) View.VISIBLE else View.GONE
        dfaAlpha1Content?.visibility = if (index == 5) View.VISIBLE else View.GONE
        chartContent?.visibility = if (index == 6) View.VISIBLE else View.GONE
    }

    /**
     * Create the Dynamics tab content.
     * Contains pace coefficient slider and user profile settings in 2-column layout.
     */
    private fun createDynamicsContent(): View {
        val textColor = ContextCompat.getColor(service, R.color.text_primary)
        val inputPadding = service.resources.getDimensionPixelSize(R.dimen.dialog_input_padding)
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val labelWidth = service.resources.getDimensionPixelSize(R.dimen.settings_label_width)
        val spinnerWidth = service.resources.getDimensionPixelSize(R.dimen.settings_spinner_width)

        // Helper to create a single row with label and control
        fun createSettingsRow(labelText: String, control: View): LinearLayout {
            return LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }

                val label = TextView(service).apply {
                    text = labelText
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(label)
                addView(control)
            }
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            // Pace coefficient label
            val paceLabel = TextView(service).apply {
                text = String.format(service.getString(R.string.settings_pace_coefficient_label))
                setTextColor(textColor)
            }
            addView(paceLabel)

            // Pace coefficient slider
            settingsOneKnobSlider = OneKnobSlider(service).apply {
                setValue(state.paceCoefficient)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = inputPadding
                }
            }
            addView(settingsOneKnobSlider)

            // Incline power coefficient label
            val inclinePowerLabel = TextView(service).apply {
                text = service.getString(R.string.settings_incline_power_coefficient_label)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }
            }
            addView(inclinePowerLabel)

            // Incline power coefficient slider (0.0 to 1.0)
            settingsInclinePowerSlider = OneKnobSlider(service).apply {
                minValue = 0.0
                maxValue = 1.0
                setValue(state.inclinePowerCoefficient)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = inputPadding
                }
            }
            addView(settingsInclinePowerSlider)

            // Incline adjustment label
            val inclineAdjLabel = TextView(service).apply {
                text = service.getString(R.string.settings_incline_adjustment_label)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }
            }
            addView(inclineAdjLabel)

            // Incline adjustment spinner (0% to 2% at 0.5% step)
            spinnerInclineAdjustment = TouchSpinner(service).apply {
                minValue = 0.0
                maxValue = 2.0
                step = 0.5
                format = TouchSpinner.Format.DECIMAL
                suffix = "%"
                value = state.inclineAdjustment
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = inputPadding
                }
            }
            addView(spinnerInclineAdjustment)

            // ===== Two-column layout for user profile =====
            val columnsContainer = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing * 2
                }
            }

            // Left column: Weight, Age, Sex
            val leftColumn = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Weight spinner
            spinnerWeight = TouchSpinner(service).apply {
                minValue = 40.0
                maxValue = 150.0
                step = 0.1
                format = TouchSpinner.Format.DECIMAL
                suffix = service.getString(R.string.unit_kg)
                value = state.userWeightKg
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            leftColumn.addView(createSettingsRow(service.getString(R.string.settings_user_weight_label), spinnerWeight!!))

            // Age spinner
            spinnerAge = TouchSpinner(service).apply {
                minValue = 18.0
                maxValue = 80.0
                step = 1.0
                format = TouchSpinner.Format.INTEGER
                suffix = ""
                value = state.userAge.toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            leftColumn.addView(createSettingsRow(service.getString(R.string.settings_user_age_label), spinnerAge!!))

            // Sex buttons
            val sexButtonContainer = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            buttonMale = Button(service).apply {
                text = service.getString(R.string.settings_sex_male)
                setTextColor(ContextCompat.getColor(service, R.color.text_primary))
                setOnClickListener { updateSexSelection(true) }
            }
            sexButtonContainer.addView(buttonMale)
            buttonFemale = Button(service).apply {
                text = service.getString(R.string.settings_sex_female)
                setTextColor(ContextCompat.getColor(service, R.color.text_primary))
                setOnClickListener { updateSexSelection(false) }
            }
            sexButtonContainer.addView(buttonFemale)
            leftColumn.addView(createSettingsRow(service.getString(R.string.settings_user_sex_label), sexButtonContainer))

            columnsContainer.addView(leftColumn)

            // Right column: HR Rest, Threshold Pace
            val rightColumn = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // HR Rest spinner
            spinnerHrRest = TouchSpinner(service).apply {
                minValue = 30.0
                maxValue = 100.0
                step = 1.0
                format = TouchSpinner.Format.INTEGER
                suffix = service.getString(R.string.unit_bpm)
                value = state.userHrRest.toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            rightColumn.addView(createSettingsRow(service.getString(R.string.settings_user_hr_rest_label), spinnerHrRest!!))

            // Threshold Pace spinner (for pace-based TSS)
            spinnerThresholdPace = TouchSpinner(service).apply {
                minValue = 180.0  // 3:00/km (very fast)
                maxValue = 600.0  // 10:00/km (slow)
                step = 5.0
                format = TouchSpinner.Format.PACE_MMSS
                suffix = "/km"
                value = PaceConverter.speedToPaceSeconds(state.thresholdPaceKph).toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            rightColumn.addView(createSettingsRow(service.getString(R.string.settings_threshold_pace_label), spinnerThresholdPace!!))

            columnsContainer.addView(rightColumn)

            addView(columnsContainer)

            // Set initial sex selection
            updateSexSelection(state.userIsMale)
        }
    }

    /**
     * Update sex button selection visuals and track selected value.
     */
    private fun updateSexSelection(isMale: Boolean) {
        selectedIsMale = isMale
        buttonMale?.setBackgroundColor(
            ContextCompat.getColor(service,
                if (isMale) R.color.spinner_button_pressed else R.color.spinner_value_background
            )
        )
        buttonFemale?.setBackgroundColor(
            ContextCompat.getColor(service,
                if (!isMale) R.color.spinner_button_pressed else R.color.spinner_value_background
            )
        )
    }

    /**
     * Create the Zones tab content.
     * Contains LTHR spinner + HR zones slider, and FTP spinner + Power zones slider.
     */
    private fun createZonesContent(): View {
        val textColor = ContextCompat.getColor(service, R.color.text_primary)
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val labelWidth = service.resources.getDimensionPixelSize(R.dimen.settings_label_width)
        val spinnerWidth = service.resources.getDimensionPixelSize(R.dimen.settings_spinner_width)

        // Helper to create a row with label and control
        fun createRow(labelText: String, control: View): LinearLayout {
            return LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }

                val label = TextView(service).apply {
                    text = labelText
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(label)
                addView(control)
            }
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            // ===== HR Zones Section =====
            val hrTitle = TextView(service).apply {
                text = service.getString(R.string.settings_hr_zones_title)
                setTextColor(textColor)
            }
            addView(hrTitle)

            // LTHR spinner
            spinnerLthr = TouchSpinner(service).apply {
                minValue = 120.0
                maxValue = 200.0
                step = 1.0
                format = TouchSpinner.Format.INTEGER
                suffix = service.getString(R.string.unit_bpm)
                value = state.userLthrBpm.toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                onValueChanged = { newValue ->
                    // Update HR zone slider's threshold for absolute value display
                    settingsHrZoneSlider?.thresholdValue = newValue.toInt()
                }
            }
            addView(createRow(service.getString(R.string.settings_lthr_label), spinnerLthr!!))

            // HR Zone Slider (percentage-based)
            settingsHrZoneSlider = ZoneSlider(service).apply {
                mode = ZoneSlider.Mode.HR
                thresholdValue = state.userLthrBpm
                setZonesPercent(
                    state.hrZone2StartPercent,
                    state.hrZone3StartPercent,
                    state.hrZone4StartPercent,
                    state.hrZone5StartPercent
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing / 2
                }
            }
            addView(settingsHrZoneSlider)

            // ===== Power Zones Section =====
            val powerTitle = TextView(service).apply {
                text = service.getString(R.string.settings_power_zones_title)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing * 2
                }
            }
            addView(powerTitle)

            // FTP spinner
            spinnerFtp = TouchSpinner(service).apply {
                minValue = 100.0
                maxValue = 400.0
                step = 5.0
                format = TouchSpinner.Format.INTEGER
                suffix = service.getString(R.string.unit_watts)
                value = state.userFtpWatts.toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                onValueChanged = { newValue ->
                    // Update Power zone slider's threshold for absolute value display
                    settingsPowerZoneSlider?.thresholdValue = newValue.toInt()
                }
            }
            addView(createRow(service.getString(R.string.settings_ftp_label), spinnerFtp!!))

            // Power Zone Slider (percentage-based)
            settingsPowerZoneSlider = ZoneSlider(service).apply {
                mode = ZoneSlider.Mode.POWER
                thresholdValue = state.userFtpWatts
                setZonesPercent(
                    state.powerZone2StartPercent,
                    state.powerZone3StartPercent,
                    state.powerZone4StartPercent,
                    state.powerZone5StartPercent
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing / 2
                }
            }
            addView(settingsPowerZoneSlider)
        }
    }

    /**
     * Create the Auto-Adjust tab content.
     * Two-column layout: HR parameters on left, Power parameters on right.
     */
    private fun createAutoAdjustContent(): View {
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val textColor = ContextCompat.getColor(service, R.color.text_primary)

        val scrollView = ScrollView(service).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Helper to create a compact row with label and spinner
        fun createCompactRow(labelText: String, spinner: TouchSpinner): LinearLayout {
            return LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = rowSpacing / 2
                }

                val label = TextView(service).apply {
                    text = labelText
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.settings_spinner_label_size))
                }
                addView(label)
                addView(spinner)
            }
        }

        // Helper to create a two-column row
        fun createDualRow(
            hrLabel: String, hrSpinner: TouchSpinner,
            powerLabel: String, powerSpinner: TouchSpinner
        ): LinearLayout {
            return LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                // HR column
                val hrColumn = LinearLayout(service).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(0, 0, rowSpacing / 2, 0)
                }
                hrColumn.addView(createCompactRow(hrLabel, hrSpinner))
                addView(hrColumn)

                // Power column
                val powerColumn = LinearLayout(service).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(rowSpacing / 2, 0, 0, 0)
                }
                powerColumn.addView(createCompactRow(powerLabel, powerSpinner))
                addView(powerColumn)
            }
        }

        // Column headers
        val headerRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = rowSpacing
            }

            val hrHeader = TextView(service).apply {
                text = service.getString(R.string.settings_hr_adjust_header)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(hrHeader)

            val powerHeader = TextView(service).apply {
                text = service.getString(R.string.settings_power_adjust_header)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(powerHeader)
        }
        content.addView(headerRow)

        // 1. Trend Window
        spinnerHrTrendWindow = TouchSpinner(service).apply {
            minValue = 5.0; maxValue = 60.0; step = 5.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_seconds)
            value = state.hrTrendWindowSeconds.toDouble()
        }
        spinnerPowerTrendWindow = TouchSpinner(service).apply {
            minValue = 5.0; maxValue = 60.0; step = 5.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_seconds)
            value = state.powerTrendWindowSeconds.toDouble()
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_trend_window), spinnerHrTrendWindow!!,
            service.getString(R.string.settings_trend_window), spinnerPowerTrendWindow!!
        ))

        // 2. Trend Threshold
        spinnerHrTrendThreshold = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 5.0; step = 1.0
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.hrTrendThreshold
        }
        spinnerPowerTrendThreshold = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 10.0; step = 1.0
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.powerTrendThreshold
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_trend_threshold), spinnerHrTrendThreshold!!,
            service.getString(R.string.settings_trend_threshold), spinnerPowerTrendThreshold!!
        ))

        // 3. Settling Time
        spinnerHrSettlingTime = TouchSpinner(service).apply {
            minValue = 5.0; maxValue = 120.0; step = 5.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_seconds)
            value = state.hrSettlingTimeSeconds.toDouble()
        }
        spinnerPowerSettlingTime = TouchSpinner(service).apply {
            minValue = 5.0; maxValue = 60.0; step = 5.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_seconds)
            value = state.powerSettlingTimeSeconds.toDouble()
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_settling_time), spinnerHrSettlingTime!!,
            service.getString(R.string.settings_settling_time), spinnerPowerSettlingTime!!
        ))

        // 4. Adjustment Cooldown
        spinnerHrAdjCooldown = TouchSpinner(service).apply {
            minValue = 5.0; maxValue = 60.0; step = 5.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_seconds)
            value = state.hrMinTimeBetweenAdjSeconds.toDouble()
        }
        spinnerPowerAdjCooldown = TouchSpinner(service).apply {
            minValue = 5.0; maxValue = 60.0; step = 5.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_seconds)
            value = state.powerMinTimeBetweenAdjSeconds.toDouble()
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_adj_cooldown), spinnerHrAdjCooldown!!,
            service.getString(R.string.settings_adj_cooldown), spinnerPowerAdjCooldown!!
        ))

        // 5. Speed Adjustment
        spinnerHrSpeedAdj = TouchSpinner(service).apply {
            minValue = 0.1; maxValue = 1.0; step = 0.1
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.hrSpeedAdjustmentKph
        }
        spinnerPowerSpeedAdj = TouchSpinner(service).apply {
            minValue = 0.1; maxValue = 1.0; step = 0.1
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.powerSpeedAdjustmentKph
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_speed_adj), spinnerHrSpeedAdj!!,
            service.getString(R.string.settings_speed_adj), spinnerPowerSpeedAdj!!
        ))

        // 6. Speed Urgent Adjustment
        spinnerHrSpeedUrgentAdj = TouchSpinner(service).apply {
            minValue = 0.2; maxValue = 2.0; step = 0.1
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.hrSpeedUrgentAdjustmentKph
        }
        spinnerPowerSpeedUrgentAdj = TouchSpinner(service).apply {
            minValue = 0.2; maxValue = 2.0; step = 0.1
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.powerSpeedUrgentAdjustmentKph
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_speed_urgent), spinnerHrSpeedUrgentAdj!!,
            service.getString(R.string.settings_speed_urgent), spinnerPowerSpeedUrgentAdj!!
        ))

        // 7. Incline Adjustment
        spinnerHrInclineAdj = TouchSpinner(service).apply {
            minValue = 0.5; maxValue = 2.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.hrInclineAdjustmentPercent
        }
        spinnerPowerInclineAdj = TouchSpinner(service).apply {
            minValue = 0.5; maxValue = 2.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.powerInclineAdjustmentPercent
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_incline_adj), spinnerHrInclineAdj!!,
            service.getString(R.string.settings_incline_adj), spinnerPowerInclineAdj!!
        ))

        // 8. Incline Urgent Adjustment
        spinnerHrInclineUrgentAdj = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 5.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.hrInclineUrgentAdjustmentPercent
        }
        spinnerPowerInclineUrgentAdj = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 5.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.powerInclineUrgentAdjustmentPercent
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_incline_urgent), spinnerHrInclineUrgentAdj!!,
            service.getString(R.string.settings_incline_urgent), spinnerPowerInclineUrgentAdj!!
        ))

        // 9. Urgent Threshold (different units: bpm vs watts)
        spinnerHrUrgentThreshold = TouchSpinner(service).apply {
            minValue = 3.0; maxValue = 15.0; step = 1.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_bpm)
            value = state.hrUrgentAboveThresholdBpm.toDouble()
        }
        spinnerPowerUrgentThreshold = TouchSpinner(service).apply {
            minValue = 5.0; maxValue = 50.0; step = 5.0
            format = TouchSpinner.Format.INTEGER
            suffix = service.getString(R.string.unit_watts)
            value = state.powerUrgentAboveThresholdWatts.toDouble()
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_urgent_threshold), spinnerHrUrgentThreshold!!,
            service.getString(R.string.settings_urgent_threshold), spinnerPowerUrgentThreshold!!
        ))

        // 10. Max Speed Adjustment
        spinnerHrMaxSpeedAdj = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 5.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.hrMaxSpeedAdjustmentKph
        }
        spinnerPowerMaxSpeedAdj = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 5.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.powerMaxSpeedAdjustmentKph
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_max_speed_adj), spinnerHrMaxSpeedAdj!!,
            service.getString(R.string.settings_max_speed_adj), spinnerPowerMaxSpeedAdj!!
        ))

        // 11. Max Incline Adjustment
        spinnerHrMaxInclineAdj = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 10.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.hrMaxInclineAdjustmentPercent
        }
        spinnerPowerMaxInclineAdj = TouchSpinner(service).apply {
            minValue = 1.0; maxValue = 10.0; step = 0.5
            format = TouchSpinner.Format.DECIMAL; suffix = ""
            value = state.powerMaxInclineAdjustmentPercent
        }
        content.addView(createDualRow(
            service.getString(R.string.settings_max_incline_adj), spinnerHrMaxInclineAdj!!,
            service.getString(R.string.settings_max_incline_adj), spinnerPowerMaxInclineAdj!!
        ))

        scrollView.addView(content)
        return scrollView
    }

    /**
     * Create the FIT Export tab content.
     * Contains text fields for device identification to match user's primary training device.
     */
    private fun createFitExportContent(): View {
        val textColor = ContextCompat.getColor(service, R.color.text_primary)
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val labelWidth = service.resources.getDimensionPixelSize(R.dimen.settings_label_width)
        val inputPadding = service.resources.getDimensionPixelSize(R.dimen.dialog_input_padding)

        // Helper to create a row with label and EditText
        fun createInputRow(labelText: String, editText: EditText): LinearLayout {
            return LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }

                val label = TextView(service).apply {
                    text = labelText
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(label)
                addView(editText)
            }
        }

        // Helper to create a numeric EditText
        fun createNumericEditText(initialValue: String): EditText {
            return EditText(service).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(initialValue)
                setTextColor(textColor)
                setBackgroundColor(ContextCompat.getColor(service, R.color.spinner_value_background))
                setPadding(inputPadding, inputPadding / 2, inputPadding, inputPadding / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    minWidth = service.resources.getDimensionPixelSize(R.dimen.settings_spinner_width)
                }
            }
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            // Description text
            val description = TextView(service).apply {
                text = service.getString(R.string.fit_export_description)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = rowSpacing
                }
            }
            addView(description)

            // Manufacturer ID: 1=Garmin (Strava shows Run), 89=Tacx (Stryd PowerCenter). Do NOT use 95.
            editFitManufacturer = createNumericEditText(state.fitManufacturer.toString())
            addView(createInputRow(service.getString(R.string.fit_manufacturer), editFitManufacturer!!))

            // Product ID (device-specific, e.g., 3990 = FR255, 4315 = FR965)
            editFitProductId = createNumericEditText(state.fitProductId.toString())
            addView(createInputRow(service.getString(R.string.fit_product_id), editFitProductId!!))

            // Device Serial (Unit ID) - can be very large numbers
            editFitDeviceSerial = createNumericEditText(state.fitDeviceSerial.toString())
            addView(createInputRow(service.getString(R.string.fit_device_serial), editFitDeviceSerial!!))

            // Software Version (e.g., 1552 = version 15.52)
            editFitSoftwareVersion = createNumericEditText(state.fitSoftwareVersion.toString())
            addView(createInputRow(service.getString(R.string.fit_software_version), editFitSoftwareVersion!!))

            // ===== Garmin Connect Section =====
            val garminHeader = TextView(service).apply {
                text = service.getString(R.string.garmin_upload_section)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing * 2
                }
            }
            addView(garminHeader)

            // Auto-upload checkbox
            checkGarminAutoUpload = CheckBox(service).apply {
                text = service.getString(R.string.garmin_auto_upload)
                setTextColor(textColor)
                isChecked = state.garminAutoUploadEnabled
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }
            }
            addView(checkGarminAutoUpload)

            // Sign in button
            val isLoggedIn = listener?.isGarminAuthenticated() ?: false
            btnGarminLogin = Button(service).apply {
                text = service.getString(
                    if (isLoggedIn) R.string.garmin_logged_in else R.string.garmin_login
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }
                setOnClickListener {
                    listener?.onGarminLoginRequested()
                }
            }
            addView(btnGarminLogin)
        }
    }

    /**
     * Create the DFA Alpha1 tab content.
     * Contains spinners for algorithm tuning parameters.
     */
    private fun createDfaAlpha1Content(): View {
        val textColor = ContextCompat.getColor(service, R.color.text_primary)
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val labelWidth = service.resources.getDimensionPixelSize(R.dimen.settings_label_width)
        val spinnerWidth = service.resources.getDimensionPixelSize(R.dimen.settings_spinner_width)

        fun createSettingsRow(labelText: String, control: View): LinearLayout {
            return LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }

                val label = TextView(service).apply {
                    text = labelText
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(label)
                addView(control)
            }
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            // Window Duration spinner (30-300 sec, step 10)
            spinnerDfaWindowDuration = TouchSpinner(service).apply {
                minValue = 30.0
                maxValue = 300.0
                step = 10.0
                format = TouchSpinner.Format.INTEGER
                suffix = service.getString(R.string.unit_seconds)
                value = state.dfaWindowDurationSec.toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(createSettingsRow(
                service.getString(R.string.settings_dfa_window_duration),
                spinnerDfaWindowDuration!!
            ))

            // Artifact Threshold spinner (1-50%, step 1)
            spinnerDfaArtifactThreshold = TouchSpinner(service).apply {
                minValue = 1.0
                maxValue = 50.0
                step = 1.0
                format = TouchSpinner.Format.INTEGER
                suffix = "%"
                value = state.dfaArtifactThreshold
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(createSettingsRow(
                service.getString(R.string.settings_dfa_artifact_threshold),
                spinnerDfaArtifactThreshold!!
            ))

            // Median Window spinner (3-21, step 2 — odd numbers only)
            spinnerDfaMedianWindow = TouchSpinner(service).apply {
                minValue = 3.0
                maxValue = 21.0
                step = 2.0
                format = TouchSpinner.Format.INTEGER
                suffix = ""
                value = state.dfaMedianWindow.toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(createSettingsRow(
                service.getString(R.string.settings_dfa_median_window),
                spinnerDfaMedianWindow!!
            ))

            // EMA Smoothing spinner (0.0-1.0, step 0.05)
            spinnerDfaEmaAlpha = TouchSpinner(service).apply {
                minValue = 0.05
                maxValue = 1.0
                step = 0.05
                format = TouchSpinner.Format.DECIMAL_2
                suffix = ""
                value = state.dfaEmaAlpha
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(createSettingsRow(
                service.getString(R.string.settings_dfa_ema_alpha),
                spinnerDfaEmaAlpha!!
            ))
        }
    }

    /**
     * Create the Chart tab content.
     * Contains zoom timeframe spinner.
     */
    private fun createChartContent(): View {
        val textColor = ContextCompat.getColor(service, R.color.text_primary)
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val labelWidth = service.resources.getDimensionPixelSize(R.dimen.settings_label_width)
        val spinnerWidth = service.resources.getDimensionPixelSize(R.dimen.settings_spinner_width)

        fun createSettingsRow(labelText: String, control: View): LinearLayout {
            return LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }

                val label = TextView(service).apply {
                    text = labelText
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(label)
                addView(control)
            }
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            spinnerZoomTimeframe = TouchSpinner(service).apply {
                minValue = 1.0
                maxValue = 60.0
                step = 1.0
                format = TouchSpinner.Format.INTEGER
                suffix = service.getString(R.string.settings_chart_zoom_timeframe_suffix)
                value = state.chartZoomTimeframeMinutes.toDouble()
                layoutParams = LinearLayout.LayoutParams(spinnerWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(createSettingsRow(
                service.getString(R.string.settings_chart_zoom_timeframe),
                spinnerZoomTimeframe!!
            ))
        }
    }

    /**
     * Create the FTMS tab content.
     * Contains checkboxes for BLE/DirCon read/control and device name fields.
     */
    private fun createFtmsContent(): View {
        val textColor = ContextCompat.getColor(service, R.color.text_primary)
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val inputPadding = service.resources.getDimensionPixelSize(R.dimen.dialog_input_padding)

        // Helper to get default device name with suffix
        fun getDefaultDeviceName(suffix: String): String {
            val baseName = state.treadmillName.ifEmpty { service.getString(R.string.ftms_default_device_name) }
            return "$baseName $suffix"
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            // Description text
            val description = TextView(service).apply {
                text = service.getString(R.string.ftms_settings_description)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = rowSpacing
                }
            }
            addView(description)

            // ===== BLE Section =====
            val bleHeader = TextView(service).apply {
                text = service.getString(R.string.ftms_ble_section)
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }
            }
            addView(bleHeader)

            // BLE Checkboxes row
            val bleCheckboxRow = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing / 2
                }
            }

            checkFtmsBleRead = CheckBox(service).apply {
                text = service.getString(R.string.ftms_broadcast)
                setTextColor(textColor)
                isChecked = state.ftmsBleReadEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    // Enable control checkbox only if broadcast is enabled
                    checkFtmsBleControl?.isEnabled = isChecked
                    if (!isChecked) checkFtmsBleControl?.isChecked = false
                }
            }
            bleCheckboxRow.addView(checkFtmsBleRead)

            checkFtmsBleControl = CheckBox(service).apply {
                text = service.getString(R.string.ftms_control)
                setTextColor(textColor)
                isChecked = state.ftmsBleControlEnabled
                isEnabled = state.ftmsBleReadEnabled  // Only enabled if broadcast is on
            }
            bleCheckboxRow.addView(checkFtmsBleControl)

            addView(bleCheckboxRow)

            // BLE Device Name
            val bleNameLabel = TextView(service).apply {
                text = service.getString(R.string.ftms_device_name)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing / 2
                }
            }
            addView(bleNameLabel)

            editFtmsBleDeviceName = EditText(service).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(state.ftmsBleDeviceName)
                hint = service.getString(R.string.ftms_device_name_placeholder, getDefaultDeviceName("BLE"))
                setTextColor(textColor)
                setHintTextColor(ContextCompat.getColor(service, R.color.text_secondary))
                setBackgroundColor(ContextCompat.getColor(service, R.color.spinner_value_background))
                setPadding(inputPadding, inputPadding / 2, inputPadding, inputPadding / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            addView(editFtmsBleDeviceName)

            // ===== DirCon Section =====
            val dirConHeader = TextView(service).apply {
                text = service.getString(R.string.ftms_dircon_section)
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing * 2
                }
            }
            addView(dirConHeader)

            // DirCon Checkboxes row
            val dirConCheckboxRow = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing / 2
                }
            }

            checkFtmsDirConRead = CheckBox(service).apply {
                text = service.getString(R.string.ftms_broadcast)
                setTextColor(textColor)
                isChecked = state.ftmsDirConReadEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    // Enable control checkbox only if broadcast is enabled
                    checkFtmsDirConControl?.isEnabled = isChecked
                    if (!isChecked) checkFtmsDirConControl?.isChecked = false
                }
            }
            dirConCheckboxRow.addView(checkFtmsDirConRead)

            checkFtmsDirConControl = CheckBox(service).apply {
                text = service.getString(R.string.ftms_control)
                setTextColor(textColor)
                isChecked = state.ftmsDirConControlEnabled
                isEnabled = state.ftmsDirConReadEnabled  // Only enabled if broadcast is on
            }
            dirConCheckboxRow.addView(checkFtmsDirConControl)

            addView(dirConCheckboxRow)

            // DirCon Device Name
            val dirConNameLabel = TextView(service).apply {
                text = service.getString(R.string.ftms_device_name)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing / 2
                }
            }
            addView(dirConNameLabel)

            editFtmsDirConDeviceName = EditText(service).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(state.ftmsDirConDeviceName)
                hint = service.getString(R.string.ftms_device_name_placeholder, getDefaultDeviceName("DirCon"))
                setTextColor(textColor)
                setHintTextColor(ContextCompat.getColor(service, R.color.text_secondary))
                setBackgroundColor(ContextCompat.getColor(service, R.color.spinner_value_background))
                setPadding(inputPadding, inputPadding / 2, inputPadding, inputPadding / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            addView(editFtmsDirConDeviceName)
        }
    }

    /**
     * Save current slider values to SharedPreferences and state.
     */
    private fun saveSettings() {
        // Save pace coefficient from slider
        settingsOneKnobSlider?.getValue()?.let { value ->
            state.paceCoefficient = value
            Log.d(TAG, "Pace coefficient saved: ${state.paceCoefficient}")
        }

        // Save incline adjustment from spinner
        spinnerInclineAdjustment?.let { spinner ->
            state.inclineAdjustment = spinner.value
            Log.d(TAG, "Incline adjustment saved: ${state.inclineAdjustment}%")
        }

        settingsInclinePowerSlider?.getValue()?.let { value ->
            state.inclinePowerCoefficient = value
            Log.d(TAG, "Incline power coefficient saved: ${state.inclinePowerCoefficient}")
        }

        // Save user profile from spinners
        spinnerWeight?.let { state.userWeightKg = it.value }
        spinnerAge?.let { state.userAge = it.value.toInt() }
        state.userIsMale = selectedIsMale
        spinnerHrRest?.let { state.userHrRest = it.value.toInt() }
        spinnerThresholdPace?.let {
            val paceSeconds = it.value.toInt()
            val kph = PaceConverter.paceSecondsToSpeed(paceSeconds)
            Log.d(TAG, "Saving thresholdPace: spinner=${it.value} paceSeconds=$paceSeconds → kph=$kph")
            state.thresholdPaceKph = kph
        }

        // Save LTHR and FTP from Zones tab
        spinnerLthr?.let { state.userLthrBpm = it.value.toInt() }
        spinnerFtp?.let { state.userFtpWatts = it.value.toInt() }

        // Save HR zones from slider (zone start percentages)
        settingsHrZoneSlider?.getZonesPercent()?.let { zones ->
            state.hrZone2StartPercent = zones[0]
            state.hrZone3StartPercent = zones[1]
            state.hrZone4StartPercent = zones[2]
            state.hrZone5StartPercent = zones[3]
        }

        // Save Power zones from slider (zone start percentages)
        settingsPowerZoneSlider?.getZonesPercent()?.let { zones ->
            state.powerZone2StartPercent = zones[0]
            state.powerZone3StartPercent = zones[1]
            state.powerZone4StartPercent = zones[2]
            state.powerZone5StartPercent = zones[3]
        }

        // Recompute cached absolute zone boundaries from updated percentages/thresholds
        state.invalidateHrZoneCaches()
        state.invalidatePowerZoneCaches()

        // Save HR auto-adjust parameters from spinners
        spinnerHrTrendWindow?.let { state.hrTrendWindowSeconds = it.value.toInt() }
        spinnerHrTrendThreshold?.let { state.hrTrendThreshold = it.value }
        spinnerHrSettlingTime?.let { state.hrSettlingTimeSeconds = it.value.toInt() }
        spinnerHrAdjCooldown?.let { state.hrMinTimeBetweenAdjSeconds = it.value.toInt() }
        spinnerHrSpeedAdj?.let { state.hrSpeedAdjustmentKph = it.value }
        spinnerHrSpeedUrgentAdj?.let { state.hrSpeedUrgentAdjustmentKph = it.value }
        spinnerHrInclineAdj?.let { state.hrInclineAdjustmentPercent = it.value }
        spinnerHrInclineUrgentAdj?.let { state.hrInclineUrgentAdjustmentPercent = it.value }
        spinnerHrUrgentThreshold?.let { state.hrUrgentAboveThresholdBpm = it.value.toInt() }
        spinnerHrMaxSpeedAdj?.let { state.hrMaxSpeedAdjustmentKph = it.value }
        spinnerHrMaxInclineAdj?.let { state.hrMaxInclineAdjustmentPercent = it.value }

        // Save Power auto-adjust parameters from spinners
        spinnerPowerTrendWindow?.let { state.powerTrendWindowSeconds = it.value.toInt() }
        spinnerPowerTrendThreshold?.let { state.powerTrendThreshold = it.value }
        spinnerPowerSettlingTime?.let { state.powerSettlingTimeSeconds = it.value.toInt() }
        spinnerPowerAdjCooldown?.let { state.powerMinTimeBetweenAdjSeconds = it.value.toInt() }
        spinnerPowerSpeedAdj?.let { state.powerSpeedAdjustmentKph = it.value }
        spinnerPowerSpeedUrgentAdj?.let { state.powerSpeedUrgentAdjustmentKph = it.value }
        spinnerPowerInclineAdj?.let { state.powerInclineAdjustmentPercent = it.value }
        spinnerPowerInclineUrgentAdj?.let { state.powerInclineUrgentAdjustmentPercent = it.value }
        spinnerPowerUrgentThreshold?.let { state.powerUrgentAboveThresholdWatts = it.value.toInt() }
        spinnerPowerMaxSpeedAdj?.let { state.powerMaxSpeedAdjustmentKph = it.value }
        spinnerPowerMaxInclineAdj?.let { state.powerMaxInclineAdjustmentPercent = it.value }

        // Save Garmin Connect upload setting
        state.garminAutoUploadEnabled = checkGarminAutoUpload?.isChecked ?: state.garminAutoUploadEnabled

        // Save FIT Export device identification from text fields
        editFitManufacturer?.text?.toString()?.toIntOrNull()?.let { state.fitManufacturer = it }
        editFitProductId?.text?.toString()?.toIntOrNull()?.let { state.fitProductId = it }
        editFitDeviceSerial?.text?.toString()?.toLongOrNull()?.let { state.fitDeviceSerial = it }
        editFitSoftwareVersion?.text?.toString()?.toIntOrNull()?.let { state.fitSoftwareVersion = it }

        // Save DFA alpha1 settings from spinners
        spinnerDfaWindowDuration?.let { state.dfaWindowDurationSec = it.value.toInt() }
        spinnerDfaArtifactThreshold?.let { state.dfaArtifactThreshold = it.value }
        spinnerDfaMedianWindow?.let { state.dfaMedianWindow = it.value.toInt() }
        spinnerDfaEmaAlpha?.let { state.dfaEmaAlpha = it.value }

        // Save chart settings from spinner
        spinnerZoomTimeframe?.let { state.chartZoomTimeframeMinutes = it.value.toInt() }

        // Save FTMS settings from checkboxes and text fields
        state.ftmsBleReadEnabled = checkFtmsBleRead?.isChecked ?: false
        state.ftmsBleControlEnabled = checkFtmsBleControl?.isChecked ?: false
        state.ftmsDirConReadEnabled = checkFtmsDirConRead?.isChecked ?: false
        state.ftmsDirConControlEnabled = checkFtmsDirConControl?.isChecked ?: false
        state.ftmsBleDeviceName = editFtmsBleDeviceName?.text?.toString() ?: ""
        state.ftmsDirConDeviceName = editFtmsDirConDeviceName?.text?.toString() ?: ""

        prefs.edit {
            putFloat(PREF_PACE_COEFFICIENT, state.paceCoefficient.toFloat())
            putFloat(PREF_INCLINE_ADJUSTMENT, state.inclineAdjustment.toFloat())
            putFloat(PREF_INCLINE_POWER_COEFFICIENT, state.inclinePowerCoefficient.toFloat())
            putFloat(PREF_USER_WEIGHT_KG, state.userWeightKg.toFloat())
            putInt(PREF_USER_AGE, state.userAge)
            putBoolean(PREF_USER_IS_MALE, state.userIsMale)
            putInt(PREF_USER_HR_REST, state.userHrRest)
            putFloat(PREF_THRESHOLD_PACE_KPH, state.thresholdPaceKph.toFloat())
            Log.d(TAG, "Persisted thresholdPaceKph=${state.thresholdPaceKph} to SharedPreferences")

            // LTHR, FTP, and zone percentages
            putInt(PREF_USER_LTHR_BPM, state.userLthrBpm)
            putInt(PREF_USER_FTP_WATTS, state.userFtpWatts)
            putFloat(PREF_HR_ZONE2_START_PERCENT, state.hrZone2StartPercent.toFloat())
            putFloat(PREF_HR_ZONE3_START_PERCENT, state.hrZone3StartPercent.toFloat())
            putFloat(PREF_HR_ZONE4_START_PERCENT, state.hrZone4StartPercent.toFloat())
            putFloat(PREF_HR_ZONE5_START_PERCENT, state.hrZone5StartPercent.toFloat())
            putFloat(PREF_POWER_ZONE2_START_PERCENT, state.powerZone2StartPercent.toFloat())
            putFloat(PREF_POWER_ZONE3_START_PERCENT, state.powerZone3StartPercent.toFloat())
            putFloat(PREF_POWER_ZONE4_START_PERCENT, state.powerZone4StartPercent.toFloat())
            putFloat(PREF_POWER_ZONE5_START_PERCENT, state.powerZone5StartPercent.toFloat())

            // HR auto-adjust parameters
            putInt(PREF_HR_TREND_WINDOW_SECONDS, state.hrTrendWindowSeconds)
            putFloat(PREF_HR_TREND_THRESHOLD, state.hrTrendThreshold.toFloat())
            putInt(PREF_HR_SETTLING_TIME_SECONDS, state.hrSettlingTimeSeconds)
            putInt(PREF_HR_MIN_TIME_BETWEEN_ADJ_SECONDS, state.hrMinTimeBetweenAdjSeconds)
            putFloat(PREF_HR_SPEED_ADJUSTMENT_KPH, state.hrSpeedAdjustmentKph.toFloat())
            putFloat(PREF_HR_SPEED_URGENT_ADJUSTMENT_KPH, state.hrSpeedUrgentAdjustmentKph.toFloat())
            putFloat(PREF_HR_INCLINE_ADJUSTMENT_PERCENT, state.hrInclineAdjustmentPercent.toFloat())
            putFloat(PREF_HR_INCLINE_URGENT_ADJUSTMENT_PERCENT, state.hrInclineUrgentAdjustmentPercent.toFloat())
            putInt(PREF_HR_URGENT_ABOVE_THRESHOLD_BPM, state.hrUrgentAboveThresholdBpm)
            putFloat(PREF_HR_MAX_SPEED_ADJUSTMENT_KPH, state.hrMaxSpeedAdjustmentKph.toFloat())
            putFloat(PREF_HR_MAX_INCLINE_ADJUSTMENT_PERCENT, state.hrMaxInclineAdjustmentPercent.toFloat())

            // Power auto-adjust parameters
            putInt(PREF_POWER_TREND_WINDOW_SECONDS, state.powerTrendWindowSeconds)
            putFloat(PREF_POWER_TREND_THRESHOLD, state.powerTrendThreshold.toFloat())
            putInt(PREF_POWER_SETTLING_TIME_SECONDS, state.powerSettlingTimeSeconds)
            putInt(PREF_POWER_MIN_TIME_BETWEEN_ADJ_SECONDS, state.powerMinTimeBetweenAdjSeconds)
            putFloat(PREF_POWER_SPEED_ADJUSTMENT_KPH, state.powerSpeedAdjustmentKph.toFloat())
            putFloat(PREF_POWER_SPEED_URGENT_ADJUSTMENT_KPH, state.powerSpeedUrgentAdjustmentKph.toFloat())
            putFloat(PREF_POWER_INCLINE_ADJUSTMENT_PERCENT, state.powerInclineAdjustmentPercent.toFloat())
            putFloat(PREF_POWER_INCLINE_URGENT_ADJUSTMENT_PERCENT, state.powerInclineUrgentAdjustmentPercent.toFloat())
            putInt(PREF_POWER_URGENT_ABOVE_THRESHOLD_WATTS, state.powerUrgentAboveThresholdWatts)
            putFloat(PREF_POWER_MAX_SPEED_ADJUSTMENT_KPH, state.powerMaxSpeedAdjustmentKph.toFloat())
            putFloat(PREF_POWER_MAX_INCLINE_ADJUSTMENT_PERCENT, state.powerMaxInclineAdjustmentPercent.toFloat())

            // FIT Export device identification
            putInt(PREF_FIT_MANUFACTURER, state.fitManufacturer)
            putInt(PREF_FIT_PRODUCT_ID, state.fitProductId)
            putLong(PREF_FIT_DEVICE_SERIAL, state.fitDeviceSerial)
            putInt(PREF_FIT_SOFTWARE_VERSION, state.fitSoftwareVersion)

            // Garmin Connect upload
            putBoolean(PREF_GARMIN_AUTO_UPLOAD, state.garminAutoUploadEnabled)

            // DFA alpha1 settings
            putInt(PREF_DFA_WINDOW_DURATION_SEC, state.dfaWindowDurationSec)
            putFloat(PREF_DFA_ARTIFACT_THRESHOLD, state.dfaArtifactThreshold.toFloat())
            putInt(PREF_DFA_MEDIAN_WINDOW, state.dfaMedianWindow)
            putFloat(PREF_DFA_EMA_ALPHA, state.dfaEmaAlpha.toFloat())

            // Chart settings
            putInt(PREF_CHART_ZOOM_TIMEFRAME_MINUTES, state.chartZoomTimeframeMinutes)

            // FTMS Server settings
            putBoolean(PREF_FTMS_BLE_READ_ENABLED, state.ftmsBleReadEnabled)
            putBoolean(PREF_FTMS_BLE_CONTROL_ENABLED, state.ftmsBleControlEnabled)
            putBoolean(PREF_FTMS_DIRCON_READ_ENABLED, state.ftmsDirConReadEnabled)
            putBoolean(PREF_FTMS_DIRCON_CONTROL_ENABLED, state.ftmsDirConControlEnabled)
            putString(PREF_FTMS_BLE_DEVICE_NAME, state.ftmsBleDeviceName)
            putString(PREF_FTMS_DIRCON_DEVICE_NAME, state.ftmsDirConDeviceName)
        }
        Log.d(TAG, "HR zones saved: Z2>=${state.hrZone2StartPercent}%, Z3>=${state.hrZone3StartPercent}%, Z4>=${state.hrZone4StartPercent}%, Z5>=${state.hrZone5StartPercent}%")
        Log.d(TAG, "Power zones saved: Z2>=${state.powerZone2StartPercent}%, Z3>=${state.powerZone3StartPercent}%, Z4>=${state.powerZone4StartPercent}%, Z5>=${state.powerZone5StartPercent}%")

        listener?.onSettingsSaved()
    }

    /**
     * Update the Garmin login button text (e.g., after successful authentication).
     */
    fun updateGarminLoginButton(isLoggedIn: Boolean) {
        btnGarminLogin?.text = service.getString(
            if (isLoggedIn) R.string.garmin_logged_in else R.string.garmin_login
        )
    }

    /**
     * Remove the settings dialog from the window.
     */
    fun removeDialog() {
        settingsDialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing settings dialog: ${e.message}")
            }
        }
        settingsDialogView = null
        settingsOneKnobSlider = null
        settingsInclinePowerSlider = null
        settingsHrZoneSlider = null
        settingsPowerZoneSlider = null

        // Clean up tab-related references
        dynamicsContent = null
        zonesContent = null
        autoAdjustContent = null
        fitExportContent = null
        ftmsContent = null
        dfaAlpha1Content = null
        chartContent = null
        tabButtons.clear()

        // Clean up dynamics tab controls
        spinnerWeight = null
        spinnerAge = null
        spinnerHrRest = null
        spinnerThresholdPace = null
        buttonMale = null
        buttonFemale = null

        // Clean up zones tab controls
        spinnerLthr = null
        spinnerFtp = null

        // Clean up HR auto-adjust spinners
        spinnerHrTrendWindow = null
        spinnerHrTrendThreshold = null
        spinnerHrSettlingTime = null
        spinnerHrAdjCooldown = null
        spinnerHrSpeedAdj = null
        spinnerHrSpeedUrgentAdj = null
        spinnerHrInclineAdj = null
        spinnerHrInclineUrgentAdj = null
        spinnerHrUrgentThreshold = null
        spinnerHrMaxSpeedAdj = null
        spinnerHrMaxInclineAdj = null

        // Clean up Power auto-adjust spinners
        spinnerPowerTrendWindow = null
        spinnerPowerTrendThreshold = null
        spinnerPowerSettlingTime = null
        spinnerPowerAdjCooldown = null
        spinnerPowerSpeedAdj = null
        spinnerPowerSpeedUrgentAdj = null
        spinnerPowerInclineAdj = null
        spinnerPowerInclineUrgentAdj = null
        spinnerPowerUrgentThreshold = null
        spinnerPowerMaxSpeedAdj = null
        spinnerPowerMaxInclineAdj = null

        // Clean up FIT Export tab controls
        editFitManufacturer = null
        editFitProductId = null
        editFitDeviceSerial = null
        editFitSoftwareVersion = null
        checkGarminAutoUpload = null
        btnGarminLogin = null

        // Clean up DFA Alpha1 tab controls
        spinnerDfaWindowDuration = null
        spinnerDfaArtifactThreshold = null
        spinnerDfaMedianWindow = null
        spinnerDfaEmaAlpha = null

        // Clean up Chart tab controls
        spinnerZoomTimeframe = null

        // Clean up FTMS tab controls
        checkFtmsBleRead = null
        checkFtmsBleControl = null
        checkFtmsDirConRead = null
        checkFtmsDirConControl = null
        editFtmsBleDeviceName = null
        editFtmsDirConDeviceName = null
    }

    /**
     * Save panel visibility state to preferences.
     */
    fun savePanelVisibility(chartVisible: Boolean, workoutPanelVisible: Boolean) {
        prefs.edit {
            putBoolean(PREF_CHART_VISIBLE, chartVisible)
            putBoolean(PREF_WORKOUT_PANEL_VISIBLE, workoutPanelVisible)
        }
    }

    /**
     * Get saved chart visibility preference.
     */
    fun getSavedChartVisible(): Boolean = prefs.getBoolean(PREF_CHART_VISIBLE, false)

    /**
     * Get saved workout panel visibility preference.
     */
    fun getSavedWorkoutPanelVisible(): Boolean = prefs.getBoolean(PREF_WORKOUT_PANEL_VISIBLE, false)
}
