package io.github.avikulin.thud.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.util.HeartRateZones
import io.github.avikulin.thud.util.PowerZones
import io.github.avikulin.thud.R
import io.github.avikulin.thud.util.PaceConverter
import com.ifit.glassos.workout.WorkoutState
import java.util.Locale
import kotlin.math.floor

/**
 * Manages the main HUD overlay lifecycle and display updates.
 */
class HUDDisplayManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val state: ServiceStateHolder
) {
    companion object {
        private const val TAG = "HUDDisplayManager"
    }

    /**
     * Callback interface for HUD button events.
     */
    interface Listener {
        fun onPaceBoxClicked()
        fun onInclineBoxClicked()
        fun onHrBoxClicked()
        fun onDfaBoxClicked()
        fun onFootPodBoxClicked()
        fun onWorkoutsClicked()
        fun onChartClicked()
        fun onCameraClicked()
        fun onBluetoothClicked()
        fun onRemoteClicked()
        fun onSettingsClicked()
        fun onCloseClicked()
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // View references
    private var topView: View? = null
    private var tvIncline: TextView? = null
    private var tvPace: TextView? = null
    private var tvRawSpeed: TextView? = null
    private var tvTime: TextView? = null
    private var tvDistance: TextView? = null
    private var tvHeartRate: TextView? = null
    private var tvHrLabel: TextView? = null
    private var tvHrSubtitle: TextView? = null
    private var tvClimb: TextView? = null
    private var paceBox: View? = null
    private var inclineBox: View? = null
    private var hrBox: View? = null
    private var dfaBox: View? = null
    private var tvDfaLabel: TextView? = null
    private var tvDfaValue: TextView? = null
    private var tvDfaSubtitle: TextView? = null
    private var footPodBox: View? = null
    private var tvFootPodLabel: TextView? = null
    private var tvFootPodValue: TextView? = null
    private var tvFootPodUnit: TextView? = null
    private var tvTrainingMetrics: TextView? = null
    private var btnWorkouts: View? = null
    private var btnChart: ImageView? = null
    private var btnCamera: ImageView? = null
    private var btnBluetooth: View? = null
    private var btnRemote: ImageView? = null
    private var btnSettings: View? = null
    private var btnClose: View? = null

    // Callback for calculated values (distance, elevation)
    var getCalculatedDistance: (() -> Double)? = null
    var getCalculatedElevation: (() -> Double)? = null
    var getWorkoutElapsedSeconds: (() -> Int)? = null

    // Current treadmill workout state for pace box display
    private var currentTreadmillState: WorkoutState = WorkoutState.WORKOUT_STATE_IDLE
    private var lastSpeedKph: Double = 0.0
    private var lastInclinePercent: Double = Double.NaN
    private var lastHeartRateBpm: Double = Double.NaN
    private var lastDistanceKm: Double = Double.NaN
    private var lastElevationM: Double = Double.NaN

    // Cached colors — initialized in showHud() to avoid repeated ContextCompat.getColor lookups
    private var colorTextPrimary = 0
    private var colorTextLabelDim = 0
    private var colorBoxInteractive = 0
    private var colorHrZone3 = 0
    private var colorToggleOnTint = 0
    private var colorToggleOffTint = 0
    private var colorRemoteNoPermissionTint = 0
    private var colorRemoteMode1Tint = 0
    private var colorRemoteMode2Tint = 0
    private var currentRemoteState = RemoteButtonState.OFF
    private var colorDfaAerobic = 0
    private var colorDfaTransition = 0
    private var colorDfaAnaerobic = 0
    private var colorDfaNoData = 0

    enum class RemoteButtonState { NO_PERMISSION, OFF, MODE1, MODE2 }
    private val zoneColors = IntArray(6)  // Zone 0-5 colors (shared by HR and Power zones)

    val isVisible: Boolean
        get() = state.isHudVisible.get()

    // Lightweight formatters to avoid String.format() / Formatter allocation in hot paths.
    // Integer arithmetic splits into integer + fractional parts.
    private fun fmtOneDecimal(v: Double): String {
        val negative = v < 0
        val abs = if (negative) -v else v
        val i = abs.toInt()
        val f = ((abs - i) * 10 + 0.5).toInt()
        return if (f >= 10) {
            if (negative) "-${i + 1}.0" else "${i + 1}.0"
        } else {
            if (negative) "-$i.$f" else "$i.$f"
        }
    }

    private fun fmtTwoDecimal(v: Double): String {
        val i = v.toInt()
        val f = ((v - i) * 100 + 0.5).toInt()
        return if (f >= 100) "${i + 1}.00"
        else if (f < 10) "$i.0$f"
        else "$i.$f"
    }

    /**
     * Show the HUD overlay.
     */
    @SuppressLint("InflateParams")
    fun showHud() {
        // Atomically check if hidden and set to visible; return if already visible
        if (!state.isHudVisible.compareAndSet(false, true)) return

        mainHandler.post {
            val layoutInflater = service.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val resources = service.resources
            val hudWidthFraction = resources.getFloat(R.dimen.hud_width_fraction)
            val hudHeightFraction = resources.getFloat(R.dimen.hud_height_fraction)

            topView = layoutInflater.inflate(R.layout.hud_top, null)
            val hudWidth = OverlayHelper.calculateWidth(state.screenWidth, hudWidthFraction)
            val hudHeight = OverlayHelper.calculateHeight(state.screenHeight, hudHeightFraction)
            val hudParams = OverlayHelper.createOverlayParams(
                width = hudWidth,
                height = hudHeight,
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )

            windowManager.addView(topView, hudParams)

            // Initialize text views
            tvIncline = topView?.findViewById(R.id.tvIncline)
            tvPace = topView?.findViewById(R.id.tvPace)
            tvRawSpeed = topView?.findViewById(R.id.tvRawSpeed)
            tvTime = topView?.findViewById(R.id.tvTime)
            tvDistance = topView?.findViewById(R.id.tvDistance)
            tvHeartRate = topView?.findViewById(R.id.tvHeartRate)
            tvHrLabel = topView?.findViewById(R.id.tvHrLabel)
            tvHrSubtitle = topView?.findViewById(R.id.tvHrSubtitle)
            tvClimb = topView?.findViewById(R.id.tvClimb)

            // Initialize touchable boxes
            paceBox = topView?.findViewById(R.id.paceBox)
            inclineBox = topView?.findViewById(R.id.inclineBox)
            hrBox = topView?.findViewById(R.id.hrBox)
            dfaBox = topView?.findViewById(R.id.dfaBox)
            tvDfaLabel = topView?.findViewById(R.id.tvDfaLabel)
            tvDfaValue = topView?.findViewById(R.id.tvDfaValue)
            tvDfaSubtitle = topView?.findViewById(R.id.tvDfaSubtitle)
            footPodBox = topView?.findViewById(R.id.footPodBox)
            tvFootPodLabel = topView?.findViewById(R.id.tvFootPodLabel)
            tvFootPodValue = topView?.findViewById(R.id.tvFootPodValue)
            tvFootPodUnit = topView?.findViewById(R.id.tvFootPodUnit)
            tvTrainingMetrics = topView?.findViewById(R.id.tvTrainingMetrics)
            btnWorkouts = topView?.findViewById(R.id.btnWorkouts)
            btnChart = topView?.findViewById(R.id.btnChart)
            btnCamera = topView?.findViewById(R.id.btnCamera)
            btnBluetooth = topView?.findViewById(R.id.btnBluetooth)
            btnRemote = topView?.findViewById(R.id.btnRemote)
            btnSettings = topView?.findViewById(R.id.btnSettings)
            btnClose = topView?.findViewById(R.id.btnClose)

            // Cache colors to avoid repeated ContextCompat.getColor lookups in update methods
            colorTextPrimary = ContextCompat.getColor(service, R.color.text_primary)
            colorTextLabelDim = ContextCompat.getColor(service, R.color.text_label_dim)
            colorBoxInteractive = ContextCompat.getColor(service, R.color.box_interactive)
            colorHrZone3 = ContextCompat.getColor(service, R.color.hr_zone_3)
            colorToggleOnTint = ContextCompat.getColor(service, R.color.toggle_button_on_tint)
            colorToggleOffTint = ContextCompat.getColor(service, R.color.toggle_button_off_tint)
            colorRemoteNoPermissionTint = ContextCompat.getColor(service, R.color.remote_no_permission_tint)
            colorRemoteMode1Tint = ContextCompat.getColor(service, R.color.remote_mode1_tint)
            colorRemoteMode2Tint = ContextCompat.getColor(service, R.color.remote_mode2_tint)
            for (zone in 0..5) {
                zoneColors[zone] = ContextCompat.getColor(service, HeartRateZones.getZoneColorResId(zone))
            }
            colorDfaAerobic = ContextCompat.getColor(service, R.color.dfa_zone_aerobic)
            colorDfaTransition = ContextCompat.getColor(service, R.color.dfa_zone_transition)
            colorDfaAnaerobic = ContextCompat.getColor(service, R.color.dfa_zone_anaerobic)
            colorDfaNoData = ContextCompat.getColor(service, R.color.dfa_zone_no_data)

            // Set up touch handlers
            paceBox?.setOnClickListener { listener?.onPaceBoxClicked() }
            inclineBox?.setOnClickListener { listener?.onInclineBoxClicked() }
            hrBox?.setOnClickListener { listener?.onHrBoxClicked() }
            dfaBox?.setOnClickListener { listener?.onDfaBoxClicked() }
            footPodBox?.setOnClickListener { listener?.onFootPodBoxClicked() }
            btnWorkouts?.setOnClickListener { listener?.onWorkoutsClicked() }
            btnChart?.setOnClickListener { listener?.onChartClicked() }
            btnCamera?.setOnClickListener { listener?.onCameraClicked() }
            btnBluetooth?.setOnClickListener { listener?.onBluetoothClicked() }
            btnRemote?.setOnClickListener { listener?.onRemoteClicked() }
            btnSettings?.setOnClickListener { listener?.onSettingsClicked() }
            btnClose?.setOnClickListener { listener?.onCloseClicked() }

            // Refresh display with current values
            refreshHudDisplay()

            Log.d(TAG, "HUD shown")
        }
    }

    /**
     * Hide the HUD overlay.
     */
    fun hideHud() {
        // Atomically check if visible and set to hidden; return if already hidden
        if (!state.isHudVisible.compareAndSet(true, false)) return

        mainHandler.post {
            // Remove HUD view
            topView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing HUD: ${e.message}")
                }
            }

            // Clear references
            topView = null
            tvIncline = null
            tvPace = null
            tvRawSpeed = null
            tvTime = null
            tvDistance = null
            tvHeartRate = null
            tvHrLabel = null
            tvHrSubtitle = null
            tvClimb = null
            paceBox = null
            inclineBox = null
            hrBox = null
            dfaBox = null
            tvDfaLabel = null
            tvDfaValue = null
            tvDfaSubtitle = null
            footPodBox = null
            tvFootPodLabel = null
            tvFootPodValue = null
            tvFootPodUnit = null
            tvTrainingMetrics = null
            btnWorkouts = null
            btnChart = null
            btnCamera = null
            btnBluetooth = null
            btnRemote = null
            btnSettings = null
            btnClose = null

            Log.d(TAG, "HUD hidden")
        }
    }

    /**
     * Refresh all HUD display values with current state.
     */
    fun refreshHudDisplay() {
        // Reset cached values so the full refresh always takes effect
        lastInclinePercent = Double.NaN
        lastHeartRateBpm = Double.NaN
        lastDistanceKm = Double.NaN
        lastElevationM = Double.NaN
        lastSpeedKph = state.currentSpeedKph
        updatePaceDisplay()
        tvRawSpeed?.text = "(${fmtOneDecimal(state.currentSpeedKph)} kph)"
        tvIncline?.text = "${fmtOneDecimal(state.currentInclinePercent)}%"
        tvHeartRate?.text = if (state.currentHeartRateBpm > 0) state.currentHeartRateBpm.toInt().toString() else "--"

        val distance = getCalculatedDistance?.invoke() ?: 0.0
        val elevation = getCalculatedElevation?.invoke() ?: 0.0
        tvDistance?.text = fmtTwoDecimal(distance)
        tvClimb?.text = "${floor(elevation).toInt()}m"

        // Update HR box background color based on current zone
        updateHrBoxColor(state.currentHeartRateBpm)

        // Use workout-relative time from recorder (resets when workout starts)
        val elapsedSeconds = getWorkoutElapsedSeconds?.invoke() ?: state.currentElapsedSeconds
        tvTime?.text = PaceConverter.formatDuration(elapsedSeconds)
    }

    /**
     * Update pace display.
     */
    fun updatePace(kph: Double) {
        if (kph == lastSpeedKph) return
        lastSpeedKph = kph
        mainHandler.post {
            updatePaceDisplay()
            tvRawSpeed?.text = "(${fmtOneDecimal(kph)} kph)"
        }
    }

    /**
     * Update the pace box display based on treadmill workout state.
     */
    fun updatePaceBoxState(workoutState: WorkoutState) {
        currentTreadmillState = workoutState
        mainHandler.post {
            updatePaceDisplay()
        }
    }

    /**
     * Update pace display based on current state:
     * - No run in progress (idle, speed=0): Show play icon ▶ with green background
     * - Running (speed > 0): Show pace with normal background
     * - Paused: Show pause icon ❚❚ with normal background
     */
    private fun updatePaceDisplay() {
        val isIdle = currentTreadmillState != WorkoutState.WORKOUT_STATE_PAUSED &&
                (currentTreadmillState != WorkoutState.WORKOUT_STATE_RUNNING || lastSpeedKph <= 0)

        val displayText = when {
            currentTreadmillState == WorkoutState.WORKOUT_STATE_PAUSED -> "❚❚"
            currentTreadmillState == WorkoutState.WORKOUT_STATE_RUNNING && lastSpeedKph > 0 -> {
                PaceConverter.formatPaceFromSpeed(lastSpeedKph * state.paceCoefficient)
            }
            else -> "▶"  // Idle or stopped
        }
        tvPace?.text = displayText

        // Update paceBox background: green when idle (ready to start), normal otherwise
        paceBox?.setBackgroundColor(if (isIdle) colorHrZone3 else colorBoxInteractive)
    }

    /**
     * Update incline display.
     */
    fun updateIncline(percent: Double) {
        if (percent == lastInclinePercent) return
        lastInclinePercent = percent
        mainHandler.post {
            tvIncline?.text = "${fmtOneDecimal(percent)}%"
        }
    }

    /**
     * Update heart rate display.
     */
    fun updateHeartRate(bpm: Double) {
        if (bpm == lastHeartRateBpm) return
        lastHeartRateBpm = bpm
        mainHandler.post {
            tvHeartRate?.text = if (bpm > 0) bpm.toInt().toString() else "--"
            updateHrBoxColor(bpm)
        }
    }

    /**
     * Update HR sensor connection status (label color).
     *
     * @param connected Whether the HR sensor is connected
     */
    fun updateHrSensorStatus(connected: Boolean) {
        mainHandler.post {
            tvHrLabel?.setTextColor(if (connected) colorTextPrimary else colorTextLabelDim)
        }
    }

    /**
     * Update the HR box subtitle showing the active primary sensor's short name (or "AVERAGE").
     * Hidden when empty (0 or 1 sensors — no need to disambiguate).
     */
    fun updateHrSubtitle(shortName: String) {
        mainHandler.post {
            tvHrSubtitle?.text = shortName
            tvHrSubtitle?.visibility = if (shortName.isEmpty()) View.GONE else View.VISIBLE
            tvHrSubtitle?.setTextColor(colorTextPrimary)
        }
    }

    /**
     * Update elapsed time display.
     * Uses workout-relative time from recorder if available.
     */
    fun updateElapsedTime(seconds: Int) {
        mainHandler.post {
            // Use workout-relative time (resets when workout starts)
            val elapsedSeconds = getWorkoutElapsedSeconds?.invoke() ?: seconds
            tvTime?.text = PaceConverter.formatDuration(elapsedSeconds)
        }
    }

    /**
     * Update distance display.
     */
    fun updateDistance(distanceKm: Double) {
        // Floor to 2 decimal places (never round up)
        val floored = floor(distanceKm * 100) / 100
        if (floored == lastDistanceKm) return
        lastDistanceKm = floored
        mainHandler.post {
            tvDistance?.text = fmtTwoDecimal(floored)
        }
    }

    /**
     * Update climb/elevation display.
     */
    fun updateClimb(elevationM: Double) {
        // Floor to whole meters (never round up)
        val floored = floor(elevationM)
        if (floored == lastElevationM) return
        lastElevationM = floored
        mainHandler.post {
            tvClimb?.text = "${floored.toInt()}m"
        }
    }

    /**
     * Update HR box background color based on heart rate zone.
     */
    private fun updateHrBoxColor(bpm: Double) {
        hrBox?.let { box ->
            val zone = HeartRateZones.getZone(bpm, state.hrZone2Start, state.hrZone3Start, state.hrZone4Start, state.hrZone5Start)
            box.setBackgroundColor(zoneColors[if (zone == 0) 1 else zone])
        }
    }

    /**
     * Update foot pod display based on selected metric and current values.
     *
     * @param metric The selected metric type: "power", "cadence", "stryd_pace"
     * @param connected Whether the foot pod is connected
     */
    fun updateFootPod(metric: String, connected: Boolean) {
        mainHandler.post {
            if (!connected) {
                tvFootPodValue?.text = service.getString(R.string.foot_pod_not_connected)
                tvFootPodLabel?.text = service.getString(R.string.label_foot_pod)
                tvFootPodUnit?.text = ""
                // Use zone 1 color for disconnected state
                footPodBox?.setBackgroundColor(zoneColors[1])
                tvFootPodLabel?.setTextColor(colorTextLabelDim)
                tvFootPodValue?.setTextColor(colorTextPrimary)
                tvFootPodUnit?.setTextColor(colorTextPrimary)
                return@post
            }

            // Update based on selected metric
            val (value, label, unit) = when (metric) {
                "power" -> {
                    // Show breakdown: RAW + INCLINE for calibration
                    val rawPower = state.currentRawPowerWatts
                    val inclinePower = state.currentPowerWatts - rawPower
                    val inclineStr = if (inclinePower >= 0) {
                        "+%.0f"
                    } else {
                        "%.0f"  // Negative already has minus sign
                    }
                    Triple(
                        String.format(Locale.US, "%.0f", state.currentPowerWatts),
                        service.getString(R.string.metric_power),
                        String.format(Locale.US, "%.0f $inclineStr", rawPower, inclinePower)
                    )
                }
                "cadence" -> Triple(
                    // Double strides/min to steps/min for display
                    if (state.currentCadenceSpm > 0) (state.currentCadenceSpm * 2).toString() else "--",
                    service.getString(R.string.metric_cadence),
                    service.getString(R.string.unit_spm)
                )
                "stryd_pace" -> {
                    // Calculate pace from Stryd speed (direct measurement from foot pod)
                    val paceStr = if (state.currentStrydSpeedKph > 0) {
                        PaceConverter.formatPaceFromSpeed(state.currentStrydSpeedKph)
                    } else "--"
                    Triple(
                        paceStr,
                        service.getString(R.string.metric_stryd_pace),
                        "/km"
                    )
                }
                else -> Triple(
                    // Double strides/min to steps/min for display
                    if (state.currentCadenceSpm > 0) (state.currentCadenceSpm * 2).toString() else "--",
                    service.getString(R.string.metric_cadence),
                    service.getString(R.string.unit_spm)
                )
            }

            tvFootPodValue?.text = value
            tvFootPodLabel?.text = label
            tvFootPodUnit?.text = unit

            // Set background color based on metric and zone
            val bgZone = when (metric) {
                "power" -> {
                    val power = state.currentPowerWatts
                    if (power > 0) {
                        val zone = PowerZones.getZone(
                            power,
                            state.powerZone2Start, state.powerZone3Start,
                            state.powerZone4Start, state.powerZone5Start
                        )
                        if (zone == 0) 1 else zone
                    } else 1
                }
                else -> 1  // Zone 1 for cadence/pace (no zone concept)
            }
            footPodBox?.setBackgroundColor(zoneColors[bgZone])
            tvFootPodLabel?.setTextColor(colorTextPrimary)
            tvFootPodValue?.setTextColor(colorTextPrimary)
            tvFootPodUnit?.setTextColor(colorTextPrimary)
        }
    }

    /**
     * Update TSS display.
     *
     * @param tss Training Stress Score (power or HR based)
     */
    fun updateTrainingMetrics(tss: Double) {
        mainHandler.post {
            val tssText = if (tss > 0) {
                tss.toInt().toString()
            } else {
                service.getString(R.string.default_tss)
            }
            tvTrainingMetrics?.text = tssText
        }
    }

    /**
     * Update camera button appearance to reflect enabled/disabled state.
     */
    fun updateCameraButtonState(isEnabled: Boolean) {
        mainHandler.post {
            updateToggleButtonStyle(btnCamera, isEnabled)
        }
    }

    /**
     * Update chart button appearance to reflect enabled/disabled state.
     */
    fun updateChartButtonState(isEnabled: Boolean) {
        mainHandler.post {
            updateToggleButtonStyle(btnChart, isEnabled)
        }
    }

    /**
     * Update remote button to reflect current state:
     * NO_PERMISSION (red), OFF (gray), MODE1/take-over (green), MODE2/pass-through (blue).
     */
    fun updateRemoteButtonState(state: RemoteButtonState) {
        mainHandler.post {
            val btn = btnRemote ?: return@post
            currentRemoteState = state
            when (state) {
                RemoteButtonState.NO_PERMISSION ->
                    setButtonStyle(btn, R.drawable.service_button_remote_no_permission, colorRemoteNoPermissionTint)
                RemoteButtonState.OFF ->
                    setButtonStyle(btn, R.drawable.service_button_border, colorToggleOffTint)
                RemoteButtonState.MODE1 ->
                    setButtonStyle(btn, R.drawable.service_button_remote_mode1, colorRemoteMode1Tint)
                RemoteButtonState.MODE2 ->
                    setButtonStyle(btn, R.drawable.service_button_remote_mode2, colorRemoteMode2Tint)
            }
        }
    }

    /**
     * Flash the remote button briefly to indicate a key press was received.
     * Uses mode-appropriate blink color (green for Mode 1, blue for Mode 2).
     */
    fun blinkRemoteButton() {
        mainHandler.post {
            val btn = btnRemote ?: return@post
            val blinkRes = if (RemoteControlBridge.isActive)
                R.drawable.service_button_remote_mode1_blink else R.drawable.service_button_remote_mode2_blink
            btn.setBackgroundResource(blinkRes)
            mainHandler.postDelayed({
                updateRemoteButtonState(currentRemoteState)
            }, 200L)
        }
    }

    /**
     * Apply button styling: background drawable and icon tint.
     * All service buttons use shape drawables with rounded corners and borders.
     */
    private fun setButtonStyle(button: ImageView, backgroundRes: Int, tintColor: Int) {
        button.setBackgroundResource(backgroundRes)
        button.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    /**
     * Apply toggle button styling (on/off).
     */
    private fun updateToggleButtonStyle(button: ImageView?, isOn: Boolean) {
        button ?: return
        if (isOn) {
            setButtonStyle(button, R.drawable.service_button_toggle_on, colorToggleOnTint)
        } else {
            setButtonStyle(button, R.drawable.service_button_border, colorToggleOffTint)
        }
    }

    /**
     * Keep the screen on while the belt is running. Prevents BLE sensor drops from screen timeout.
     */
    fun setKeepScreenOn(keepOn: Boolean) {
        val view = topView ?: return
        mainHandler.post {
            val params = view.layoutParams as WindowManager.LayoutParams
            if (keepOn) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        mainHandler.removeCallbacksAndMessages(null)

        topView?.let { view ->
            topView = null
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing HUD: ${e.message}")
            }
        }
        state.isHudVisible.set(false)
    }

    /**
     * Get overlay-relative bounds for a HUD box.
     * getLocationOnScreen() returns absolute screen coords (includes status bar),
     * but overlay LayoutParams.y is relative to the overlay coordinate system
     * (starts below status bar). Subtract HUD root's screen y to correct.
     */
    private fun getBoxBounds(box: View?): IntArray? {
        val b = box ?: return null
        val root = topView ?: return null
        val boxLoc = IntArray(2)
        val rootLoc = IntArray(2)
        b.getLocationOnScreen(boxLoc)
        root.getLocationOnScreen(rootLoc)
        return intArrayOf(boxLoc[0], boxLoc[1] - rootLoc[1], b.width, b.height)
    }

    fun getFootPodBoxBounds(): IntArray? = getBoxBounds(footPodBox)

    fun getHrBoxBounds(): IntArray? = getBoxBounds(hrBox)

    fun getDfaBoxBounds(): IntArray? = getBoxBounds(dfaBox)

    /**
     * Update DFA alpha1 display value and zone-colored background.
     * Zone thresholds: > 0.75 aerobic (green), 0.5-0.75 transition (amber), < 0.5 anaerobic (red).
     */
    fun updateDfaAlpha1(alpha1: Double, isValid: Boolean) {
        mainHandler.post {
            if (!isValid) {
                tvDfaValue?.text = "--"
                dfaBox?.setBackgroundColor(colorDfaNoData)
                return@post
            }
            tvDfaValue?.text = String.format(Locale.US, "%.2f", alpha1)
            val bgColor = when {
                alpha1 > 0.75 -> colorDfaAerobic
                alpha1 >= 0.5 -> colorDfaTransition
                else -> colorDfaAnaerobic
            }
            dfaBox?.setBackgroundColor(bgColor)
        }
    }

    /**
     * Update DFA box subtitle showing the active sensor's short name.
     * Hidden when empty (0 or 1 RR-capable sensors — no need to disambiguate).
     */
    fun updateDfaSubtitle(shortName: String) {
        mainHandler.post {
            tvDfaSubtitle?.text = shortName
            tvDfaSubtitle?.visibility = if (shortName.isEmpty()) View.GONE else View.VISIBLE
            tvDfaSubtitle?.setTextColor(colorTextPrimary)
        }
    }

    /**
     * Update DFA sensor connection status (label color).
     *
     * @param connected Whether at least one RR-capable sensor is connected
     */
    fun updateDfaSensorStatus(connected: Boolean) {
        mainHandler.post {
            tvDfaLabel?.setTextColor(if (connected) colorTextPrimary else colorTextLabelDim)
        }
    }
}
