package io.github.avikulin.thud.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import androidx.core.content.edit
import android.os.Looper
import android.util.Log
import com.ifit.glassos.workout.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages GlassOS connection lifecycle and telemetry callbacks.
 * Acts as a wrapper around GlassOsClient and implements TelemetryListener.
 */
class TelemetryManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: ServiceStateHolder
) : GlassOsClient.TelemetryListener {

    companion object {
        private const val TAG = "TelemetryManager"
    }

    /**
     * Callback interface for telemetry events.
     * Note: HR, distance, elevation, and calories are NOT provided by GlassOS.
     * - HR: handled by HrSensorManager (direct BLE to HR sensor)
     * - Distance/Elevation: calculated from adjusted speed in WorkoutRecorder
     */
    interface Listener {
        fun onConnectionEstablished(isReconnection: Boolean)
        fun onConnectionLost()
        fun onCertificatesNotFound(missingFiles: List<String>, certsDirectory: String)
        fun onTelemetryUpdated()
        fun onWorkoutStateChanged(workoutState: WorkoutState, previousState: WorkoutState)
        fun onSpeedUpdate(kph: Double)
        fun onInclineUpdate(percent: Double)
        fun onElapsedTimeUpdate(seconds: Int)
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var glassOsClient: GlassOsClient? = null
    private var currentWorkoutState = WorkoutState.WORKOUT_STATE_UNKNOWN
    private val prefs: SharedPreferences = context.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
    private var hasEverConnected = false  // Tracks if this is initial connection vs reconnection

    /**
     * Get the GlassOS client instance.
     */
    fun getClient(): GlassOsClient? = glassOsClient

    /**
     * Check if connected to GlassOS.
     */
    fun isConnected(): Boolean = glassOsClient?.isConnected() == true

    /**
     * Start the connection to GlassOS (runs in background).
     */
    fun startConnection() {
        scope.launch(Dispatchers.IO) {
            connectAndSubscribe()
        }
    }

    /**
     * Main connection loop with auto-reconnect.
     */
    private suspend fun connectAndSubscribe() {
        while (state.isRunning) {
            try {
                Log.d(TAG, "Connecting to iFit GlassOS...")
                listener?.onConnectionLost() // Show "connecting" status

                glassOsClient = GlassOsClient(context)
                glassOsClient?.setListener(this)

                if (glassOsClient?.connect() == true) {
                    val isReconnection = hasEverConnected
                    hasEverConnected = true
                    state.reconnectAttempts = 0
                    Log.d(TAG, "Connection established (isReconnection=$isReconnection)")
                    listener?.onConnectionEstablished(isReconnection)

                    // Generate dynamic menu values from treadmill ranges
                    state.speedValues = ServiceStateHolder.generateSpeedValues(
                        glassOsClient?.minSpeedKph ?: 1.6,
                        glassOsClient?.maxSpeedKph ?: 20.0
                    )

                    // Get treadmill incline range and convert to effective incline (outdoor equivalent)
                    // Effective incline = treadmill incline - adjustment
                    val treadmillMinIncline = glassOsClient?.minInclinePercent ?: -6.0
                    val treadmillMaxIncline = glassOsClient?.maxInclinePercent ?: 40.0
                    val effectiveMinIncline = treadmillMinIncline - state.inclineAdjustment
                    val effectiveMaxIncline = treadmillMaxIncline - state.inclineAdjustment

                    state.inclineValues = ServiceStateHolder.generateInclineValues(effectiveMinIncline, effectiveMaxIncline)
                    state.minSpeedKph = glassOsClient?.minSpeedKph ?: 1.6
                    state.maxSpeedKph = glassOsClient?.maxSpeedKph ?: 20.0
                    state.minInclinePercent = effectiveMinIncline
                    state.maxInclinePercent = effectiveMaxIncline
                    state.treadmillName = glassOsClient?.treadmillName ?: ""

                    // Save treadmill capabilities to SharedPreferences for workout editor
                    // Note: incline values are stored as EFFECTIVE incline (outdoor equivalent)
                    saveTreadmillCapabilities(
                        state.minSpeedKph, state.maxSpeedKph,
                        state.minInclinePercent, state.maxInclinePercent
                    )

                    Log.d(TAG, "Speed values: ${state.speedValues}")
                    Log.d(TAG, "Incline values: ${state.inclineValues}")

                    // Subscribe to all telemetry streams
                    glassOsClient?.subscribeToAll()

                    // Subscribe to console management (sleep/lockout auto-handling)
                    glassOsClient?.subscribeToConsoleManagement()

                    // Pre-load a free run workout so physical Start button works
                    preloadFreeRunWorkout()

                    // HR auto-connect is now handled by HrSensorManager (direct BLE)

                    // Keep coroutine alive while connected
                    while (state.isRunning && glassOsClient?.isConnected() == true) {
                        delay(1000)
                    }
                } else {
                    throw Exception("Connection failed")
                }
            } catch (e: CertificatesNotFoundError) {
                // Certificate error - notify listener and stop trying to connect
                Log.e(TAG, "Certificates not found: ${e.missingFiles} in ${e.certsDirectory}")
                mainHandler.post {
                    listener?.onCertificatesNotFound(e.missingFiles, e.certsDirectory)
                }
                break  // Don't retry - user needs to install certificates
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                state.reconnectAttempts++

                if (state.maxReconnectAttempts != -1 && state.reconnectAttempts >= state.maxReconnectAttempts) {
                    listener?.onConnectionLost()
                    break
                }

                val delaySeconds = minOf(state.reconnectAttempts * 2, 30)
                delay(delaySeconds * 1000L)
            } finally {
                glassOsClient?.disconnect()
            }
        }
    }

    /**
     * Disconnect from GlassOS.
     */
    fun disconnect() {
        glassOsClient?.disconnectFromConsole()
        glassOsClient?.disconnect()
    }

    /**
     * Save treadmill capabilities to SharedPreferences for use by workout editor.
     */
    private fun saveTreadmillCapabilities(minSpeedKph: Double, maxSpeedKph: Double,
                                          minIncline: Double, maxIncline: Double) {
        prefs.edit {
            putFloat(SettingsManager.PREF_TREADMILL_MIN_SPEED_KPH, minSpeedKph.toFloat())
            putFloat(SettingsManager.PREF_TREADMILL_MAX_SPEED_KPH, maxSpeedKph.toFloat())
            putFloat(SettingsManager.PREF_TREADMILL_MIN_INCLINE, minIncline.toFloat())
            putFloat(SettingsManager.PREF_TREADMILL_MAX_INCLINE, maxIncline.toFloat())
        }
        Log.d(TAG, "Saved treadmill capabilities: speed=$minSpeedKph-$maxSpeedKph kph, incline=$minIncline-$maxIncline%")
    }

    // ==================== Treadmill Control ====================

    /**
     * Set treadmill speed (adjusted speed, will be converted to treadmill speed).
     */
    fun setTreadmillSpeed(adjustedKph: Double): Boolean {
        val treadmillKph = adjustedKph / state.paceCoefficient
        val success = glassOsClient?.setSpeed(treadmillKph) ?: false
        Log.d(TAG, "Set speed: $adjustedKph kph (adjusted) -> $treadmillKph kph (treadmill): $success")
        return success
    }

    /**
     * Set treadmill incline.
     * Converts effective incline (outdoor equivalent) to treadmill incline by adding adjustment.
     * @param effectivePercent The effective incline (0% = flat outdoor equivalent)
     */
    fun setTreadmillIncline(effectivePercent: Double): Boolean {
        // Convert effective incline to treadmill incline
        // e.g., if adjustment=1.0, effective 0% → treadmill 1%, effective 2% → treadmill 3%
        val treadmillPercent = effectivePercent + state.inclineAdjustment
        val success = glassOsClient?.setIncline(treadmillPercent) ?: false
        Log.d(TAG, "Set incline: effective=$effectivePercent%, treadmill=$treadmillPercent%: $success")
        return success
    }

    /**
     * Pre-load a free run workout without starting it.
     * This enables the physical Start button to work.
     */
    fun preloadFreeRunWorkout(): Boolean {
        val success = glassOsClient?.preloadFreeRunWorkout() ?: false
        Log.d(TAG, "Preload free run workout: $success")
        return success
    }

    /**
     * Pause the treadmill workout (belt stops but workout continues).
     */
    fun pauseTreadmill(): Boolean {
        val success = glassOsClient?.pauseWorkout() ?: false
        Log.d(TAG, "Pause treadmill: $success")
        return success
    }

    /**
     * Stop the treadmill workout entirely (ends the session).
     */
    fun stopTreadmill(): Boolean {
        val success = glassOsClient?.stopWorkout() ?: false
        Log.d(TAG, "Stop treadmill: $success")
        return success
    }

    /**
     * Wait for the treadmill belt to be ready (state=RUNNING, speed > 0).
     */
    suspend fun waitForBeltReady(timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        val pollIntervalMs = 100L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val workoutState = glassOsClient?.getWorkoutState()
            val speed = state.currentSpeedKph

            if (workoutState == WorkoutState.WORKOUT_STATE_RUNNING && speed > 0) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Belt ready after ${elapsed}ms (state=$workoutState, speed=$speed kph)")
                return true
            }

            delay(pollIntervalMs)
        }

        Log.w(TAG, "Timeout waiting for belt to be ready after ${timeoutMs}ms")
        return false
    }

    /**
     * Ensure the treadmill is running (start/resume if needed).
     */
    suspend fun ensureTreadmillRunning(): Boolean {
        val workoutState = glassOsClient?.getWorkoutState()
        Log.d(TAG, "Ensuring treadmill running, current state: $workoutState")

        return when (workoutState) {
            WorkoutState.WORKOUT_STATE_PAUSED -> {
                Log.d(TAG, "Treadmill paused, resuming...")
                glassOsClient?.resumeWorkout()
                waitForBeltReady()
            }
            WorkoutState.WORKOUT_STATE_RUNNING -> {
                if (state.currentSpeedKph <= 0) {
                    Log.d(TAG, "Treadmill running but belt stopped, waiting...")
                    waitForBeltReady()
                } else {
                    Log.d(TAG, "Treadmill already running at ${state.currentSpeedKph} kph")
                    true
                }
            }
            else -> {
                Log.d(TAG, "Quick starting treadmill...")
                val success = glassOsClient?.quickStartWorkout() ?: false
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

    // ==================== TelemetryListener Implementation ====================

    override fun onSpeedUpdate(kph: Double, avgKph: Double, maxKph: Double) {
        state.currentSpeedKph = kph
        listener?.onSpeedUpdate(kph)
        listener?.onTelemetryUpdated()
    }

    override fun onInclineUpdate(percent: Double, avgPercent: Double, maxPercent: Double) {
        // Convert treadmill incline to effective incline (outdoor equivalent)
        // e.g., if adjustment=1.0, treadmill 1% → effective 0%, treadmill 3% → effective 2%
        val effectivePercent = percent - state.inclineAdjustment
        state.currentInclinePercent = effectivePercent
        listener?.onInclineUpdate(effectivePercent)
        listener?.onTelemetryUpdated()
    }

    override fun onElapsedTimeUpdate(seconds: Int) {
        state.currentElapsedSeconds = seconds
        listener?.onElapsedTimeUpdate(seconds)
    }

    override fun onConnectionStateChanged(connected: Boolean, message: String) {
        Log.d(TAG, "Connection state: $connected - $message")
        // Note: onConnectionEstablished is called from connectAndSubscribe() with reconnection tracking
        // Only handle disconnection here
        if (!connected) {
            mainHandler.post {
                listener?.onConnectionLost()
            }
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "Error: $error")
    }

    override fun onWorkoutStateChanged(workoutState: WorkoutState) {
        Log.d(TAG, "Workout state changed: $workoutState")
        val previousState = currentWorkoutState
        currentWorkoutState = workoutState
        listener?.onWorkoutStateChanged(workoutState, previousState)
    }

    override fun onManualStartRequested() {
        Log.d(TAG, "Manual start requested (physical Start button)")
        // The GlassOsClient already handles calling Start() on the preloaded workout.
        // The workout state will transition to RUNNING, which HUDService handles.
    }
}
