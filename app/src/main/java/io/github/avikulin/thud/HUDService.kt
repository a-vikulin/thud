package io.github.avikulin.thud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.avikulin.thud.data.db.TreadmillHudDatabase
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.model.WorkoutDataPoint
import io.github.avikulin.thud.data.repository.WorkoutRepository
import io.github.avikulin.thud.domain.engine.ExecutionStep
import io.github.avikulin.thud.domain.engine.MetricDataPoint
import io.github.avikulin.thud.domain.engine.WorkoutExecutionState
import io.github.avikulin.thud.domain.engine.WorkoutEvent
import io.github.avikulin.thud.service.BluetoothSensorDialogManager
import io.github.avikulin.thud.service.ChartManager
import io.github.avikulin.thud.service.HUDDisplayManager
import io.github.avikulin.thud.service.HrSensorManager
import io.github.avikulin.thud.service.OverlayHelper
import io.github.avikulin.thud.service.PopupManager
import io.github.avikulin.thud.service.ServiceStateHolder
import io.github.avikulin.thud.service.SettingsManager
import io.github.avikulin.thud.service.TelemetryManager
import io.github.avikulin.thud.service.WorkoutEngineManager
import io.github.avikulin.thud.service.WorkoutPanelManager
import io.github.avikulin.thud.service.WorkoutRecorder
import io.github.avikulin.thud.service.StrydManager
import io.github.avikulin.thud.service.RunPersistenceManager
import io.github.avikulin.thud.service.ScreenshotManager
import io.github.avikulin.thud.service.PersistedRunType
import io.github.avikulin.thud.service.PersistedRunState
import io.github.avikulin.thud.ui.editor.WorkoutEditorActivityNew
import io.github.avikulin.thud.service.garmin.GarminConnectUploader
import io.github.avikulin.thud.util.FitFileExporter
import io.github.avikulin.thud.util.HeartRateZones

import io.github.avikulin.thud.util.PaceConverter
import io.github.avikulin.thud.util.TrainingMetricsCalculator
import com.ifit.glassos.workout.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main HUD Service - Coordinates all manager components.
 */
class HUDService : Service(),
    TelemetryManager.Listener,
    SettingsManager.Listener,
    HUDDisplayManager.Listener,
    WorkoutPanelManager.Listener,
    WorkoutEngineManager.Listener,
    StrydManager.Listener,
    HrSensorManager.Listener,
    io.github.avikulin.thud.service.dircon.DirConServer.Listener,
    io.github.avikulin.thud.service.ble.BleFtmsServer.Listener {

    companion object {
        private const val TAG = "HUDService"

        // Service actions
        const val ACTION_START_SERVICE = "io.github.avikulin.thud.START_SERVICE"
        const val ACTION_SHOW_HUD = "io.github.avikulin.thud.SHOW_HUD"
        const val ACTION_HIDE_HUD = "io.github.avikulin.thud.HIDE_HUD"
        const val ACTION_TOGGLE_HUD = "io.github.avikulin.thud.TOGGLE_HUD"
        const val ACTION_STOP_SERVICE = "io.github.avikulin.thud.STOP_SERVICE"

        // Workout actions
        const val ACTION_LOAD_WORKOUT = "io.github.avikulin.thud.LOAD_WORKOUT"
        const val ACTION_START_WORKOUT = "io.github.avikulin.thud.START_WORKOUT"
        const val ACTION_PAUSE_WORKOUT = "io.github.avikulin.thud.PAUSE_WORKOUT"
        const val ACTION_STOP_WORKOUT = "io.github.avikulin.thud.STOP_WORKOUT"
        const val ACTION_RESTORE_PANELS = "io.github.avikulin.thud.RESTORE_PANELS"
        const val EXTRA_WORKOUT_ID = "workout_id"

        // Editor lifecycle actions
        const val ACTION_EDITOR_FOREGROUND = "io.github.avikulin.thud.EDITOR_FOREGROUND"
        const val ACTION_EDITOR_BACKGROUND = "io.github.avikulin.thud.EDITOR_BACKGROUND"
        const val ACTION_EDITOR_CLOSED = "io.github.avikulin.thud.EDITOR_CLOSED"

        // Notification
        private const val NOTIFICATION_CHANNEL_ID = "HUD_SERVICE_CHANNEL"
        private const val NOTIFICATION_ID = 1
    }

    // ==================== Run Export Snapshot ====================
    // Immutable snapshot of ALL data needed for FIT export.
    // Captured BEFORE any cleanup to prevent race conditions.

    /**
     * User settings needed for FIT export metrics calculation.
     */
    data class UserExportSettings(
        val hrRest: Int,           // Resting HR for HR-based TSS
        val lthrBpm: Int,          // Lactate Threshold HR
        val ftpWatts: Int,         // Functional Threshold Power
        val thresholdPaceKph: Double, // Threshold pace (fallback TSS)
        val isMale: Boolean,       // For calorie calculation
        // FIT device identification (for Garmin Training Status compatibility)
        val fitManufacturer: Int,
        val fitProductId: Int,
        val fitDeviceSerial: Long,
        val fitSoftwareVersion: Int
    )

    /**
     * Complete snapshot of run data for FIT export.
     * Captures all data needed BEFORE any cleanup operations.
     */
    data class RunSnapshot(
        val workoutName: String,
        val startTimeMs: Long,
        val workoutData: List<WorkoutDataPoint>,
        val pauseEvents: List<io.github.avikulin.thud.service.PauseEvent>,
        val executionSteps: List<ExecutionStep>?,  // null for free runs (flattened for runtime)
        val originalSteps: List<io.github.avikulin.thud.data.entity.WorkoutStep>?,  // null for free runs (hierarchical for FIT)
        val userSettings: UserExportSettings
    )

    // Coroutine scope for background work
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Shared state holder
    private lateinit var state: ServiceStateHolder

    // Managers
    private lateinit var windowManager: WindowManager
    private lateinit var telemetryManager: TelemetryManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var hudDisplayManager: HUDDisplayManager
    private lateinit var chartManager: ChartManager
    private lateinit var workoutPanelManager: WorkoutPanelManager
    private lateinit var popupManager: PopupManager
    private lateinit var hrSensorManager: HrSensorManager
    private lateinit var workoutEngineManager: WorkoutEngineManager
    private lateinit var strydManager: StrydManager
    private lateinit var bluetoothSensorDialogManager: BluetoothSensorDialogManager
    private var dirConServer: io.github.avikulin.thud.service.dircon.DirConServer? = null
    private var bleFtmsServer: io.github.avikulin.thud.service.ble.BleFtmsServer? = null

    // Workout data recording and export
    private val workoutRecorder = WorkoutRecorder()
    private lateinit var fitFileExporter: FitFileExporter

    private lateinit var garminUploader: GarminConnectUploader
    private var workoutStartTimeMs: Long = 0
    private var workoutDataExported = false  // Tracks if FIT export already happened for current session
    private var lastWorkoutName: String? = null  // Stores workout name before engine stop (since getCurrentWorkout() returns null after stop)

    // Training metrics update throttling (every 5 seconds)
    private var lastTrainingMetricsUpdateMs: Long = 0
    private val TRAINING_METRICS_UPDATE_INTERVAL_MS = 5000L

    // Saved panel visibility state (for restoring after editor activity)
    private var editorPanelStateSaved = false
    private var savedHudVisible = false
    private var savedChartVisible = false
    private var savedWorkoutPanelVisible = false

    // Free run confirmation dialog
    private var freeRunDialogView: LinearLayout? = null

    // Certificate error dialog
    private var certificateErrorDialogView: LinearLayout? = null

    // Garmin login overlay
    private var garminLoginOverlayView: LinearLayout? = null
    private var garminLoginCallback: ((ticket: String?) -> Unit)? = null

    // Run persistence
    private lateinit var runPersistenceManager: RunPersistenceManager

    // Screenshot capture
    private lateinit var screenshotManager: ScreenshotManager
    private var resumeRunDialogView: LinearLayout? = null
    private val persistenceHandler = Handler(Looper.getMainLooper())
    private val persistenceInterval = 15_000L  // 15 seconds

    // Screen state receiver - disconnects BLE sensors when screen off to save battery
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off - disconnecting BLE sensors to save battery")
                    hrSensorManager.disconnect()
                    strydManager.disconnect()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen on - reconnecting BLE sensors")
                    hrSensorManager.autoConnect()
                    strydManager.autoConnect()
                }
            }
        }
    }
    private var screenReceiverRegistered = false
    private val persistRunnable = object : Runnable {
        override fun run() {
            persistCurrentRunState()
            persistenceHandler.postDelayed(this, persistenceInterval)
        }
    }
    private var isPersistenceActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Initialize shared state
        state = ServiceStateHolder()
        state.isRunning = true

        // Initialize window manager and get screen dimensions
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val windowMetrics = windowManager.currentWindowMetrics
        state.screenWidth = windowMetrics.bounds.width()
        state.screenHeight = windowMetrics.bounds.height()

        // Initialize preferences and settings manager
        val prefs = getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        settingsManager = SettingsManager(this, windowManager, prefs, state)
        settingsManager.listener = this
        settingsManager.loadSettings()

        // Initialize run persistence manager
        runPersistenceManager = RunPersistenceManager(this)

        // Initialize screenshot manager
        screenshotManager = ScreenshotManager(this, serviceScope)
        screenshotManager.onStateChanged = { isEnabled ->
            hudDisplayManager.updateCameraButtonState(isEnabled)
        }

        // Pass user profile to workout recorder for calorie calculation
        updateRecorderUserProfile()

        // Initialize FIT file exporter
        fitFileExporter = FitFileExporter(applicationContext)
        garminUploader = GarminConnectUploader(applicationContext)

        // Set up workout recorder callback for UI updates
        workoutRecorder.onMetricsUpdated = { distanceKm, elevationM ->
            mainHandler.post {
                hudDisplayManager.updateDistance(distanceKm)
                hudDisplayManager.updateClimb(elevationM)
            }
        }

        // Initialize managers
        telemetryManager = TelemetryManager(applicationContext, serviceScope, state)
        telemetryManager.listener = this

        hudDisplayManager = HUDDisplayManager(this, windowManager, state)
        hudDisplayManager.listener = this
        hudDisplayManager.getCalculatedDistance = { workoutRecorder.calculatedDistanceKm }
        hudDisplayManager.getCalculatedElevation = { workoutRecorder.calculatedElevationGainM }
        hudDisplayManager.getWorkoutElapsedSeconds = { workoutRecorder.workoutElapsedSeconds }

        chartManager = ChartManager(this, windowManager, state, workoutRecorder)

        workoutPanelManager = WorkoutPanelManager(this, windowManager, state)
        workoutPanelManager.listener = this
        workoutPanelManager.getWorkoutState = { workoutEngineManager.getState() }

        popupManager = PopupManager(
            this, windowManager, serviceScope, state,
            getGlassOsClient = { telemetryManager.getClient() },
            setTreadmillSpeed = { adjustedKph -> telemetryManager.setTreadmillSpeed(adjustedKph) },
            setTreadmillIncline = { percent -> telemetryManager.setTreadmillIncline(percent) },
            isWorkoutLoadedAndIdle = {
                workoutEngineManager.isWorkoutLoaded() &&
                        workoutEngineManager.getState() is WorkoutExecutionState.Idle
            },
            onPaceSelectedWithWorkoutLoaded = { adjustedKph ->
                mainHandler.post { showFreeRunConfirmationDialog(adjustedKph) }
            },
            isStructuredWorkoutPaused = {
                workoutEngineManager.isWorkoutLoaded() &&
                        workoutEngineManager.getState() is WorkoutExecutionState.Paused
            },
            onResumeWorkoutAtStepPace = {
                // Resume workout with treadmill control and step targets
                workoutEngineManager.resumeWorkoutWithTargets()
            }
        )

        // Initialize HR sensor manager (direct BLE, not via GlassOS)
        hrSensorManager = HrSensorManager(this, windowManager, state)
        hrSensorManager.listener = this
        hrSensorManager.initialize()
        hrSensorManager.autoConnect()

        // Initialize Stryd foot pod manager
        strydManager = StrydManager(this, windowManager, serviceScope, state)
        strydManager.listener = this
        strydManager.initialize()
        strydManager.autoConnect()

        // Register screen state receiver to disconnect BLE when screen off (saves sensor battery)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, screenFilter)
        screenReceiverRegistered = true

        // Initialize unified Bluetooth sensors dialog manager
        bluetoothSensorDialogManager = BluetoothSensorDialogManager(
            this, windowManager, state, hrSensorManager, strydManager
        )

        workoutEngineManager = WorkoutEngineManager(
            applicationContext,
            serviceScope,
            state,
            { telemetryManager.getClient() },
            { workoutRecorder.getWorkoutData().map { MetricDataPoint(it.elapsedMs, it.heartRateBpm) } },
            { workoutRecorder.getWorkoutData().map { MetricDataPoint(it.elapsedMs, it.powerWatts) } }
        )
        workoutEngineManager.listener = this
        workoutEngineManager.chartManager = chartManager

        // Start foreground service with appropriate types
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: both special use and media projection
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13: media projection only (special use not available)
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Ensure system workouts exist (Default Warmup, Default Cooldown)
        serviceScope.launch {
            val database = TreadmillHudDatabase.getInstance(applicationContext)
            val repository = WorkoutRepository(database.workoutDao())
            repository.ensureSystemWorkoutsExist()
        }

        // Start connection to GlassOS
        telemetryManager.startConnection()

        // Start DirCon server for Zwift WiFi connectivity
        startDirConServer()

        // Start BLE FTMS server for Bluetooth fitness app connectivity
        startBleFtmsServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "Service started (no HUD)")
            }
            ACTION_SHOW_HUD -> {
                Log.d(TAG, "Showing HUD")
                showHud()
            }
            ACTION_HIDE_HUD -> {
                Log.d(TAG, "Hiding HUD")
                hideHud()
            }
            ACTION_TOGGLE_HUD -> {
                Log.d(TAG, "Toggling HUD")
                if (state.isHudVisible.get()) hideHud() else showHud()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping service")
                stopSelf()
            }
            ACTION_LOAD_WORKOUT, ACTION_START_WORKOUT -> {
                // Both load and start do the same thing: save existing data, then load and start
                val workoutId = intent.getLongExtra(EXTRA_WORKOUT_ID, -1)
                Log.d(TAG, "Loading and starting workout: $workoutId")
                if (workoutId > 0) {
                    startNewRun()
                    workoutEngineManager.loadAndStartWorkout(workoutId) { }
                } else {
                    Log.e(TAG, "No workout ID provided")
                }
            }
            ACTION_PAUSE_WORKOUT -> {
                Log.d(TAG, "Pausing/resuming workout")
                workoutEngineManager.togglePause()
            }
            ACTION_STOP_WORKOUT -> {
                Log.d(TAG, "Stopping workout")
                workoutEngineManager.stopWorkout()
            }
            ACTION_RESTORE_PANELS -> {
                Log.d(TAG, "Restoring panels")
                restorePanels()
            }
            ACTION_EDITOR_FOREGROUND -> {
                Log.d(TAG, "Editor came to foreground")
                onEditorForeground()
            }
            ACTION_EDITOR_BACKGROUND -> {
                Log.d(TAG, "Editor went to background")
                onEditorBackground()
            }
            ACTION_EDITOR_CLOSED -> {
                Log.d(TAG, "Editor closed")
                onEditorClosed()
            }
            else -> {
                // Default: show HUD (for backwards compatibility)
                Log.d(TAG, "Default action: showing HUD")
                showHud()
            }
        }
        return START_STICKY
    }

    // ==================== Run Recording (Single Source of Truth) ====================
    // These methods are the ONLY place where recording state should be modified.
    // All other code should call these methods instead of calling workoutRecorder directly.

    /**
     * Start a new run from scratch. Saves any existing data to FIT first, then clears and starts fresh.
     * Use this when starting a new free run or a new structured workout.
     */
    private fun startNewRun() {
        val existingDataCount = workoutRecorder.getDataPointCount()
        Log.d(TAG, "startNewRun: existingData=$existingDataCount. Caller: ${Thread.currentThread().stackTrace[3].methodName}")

        // Save existing data to FIT before clearing (no run data should ever be lost)
        // CRITICAL: Create snapshot BEFORE resetWorkout() clears executionSteps
        if (existingDataCount > 0 && !workoutDataExported) {
            val workoutName = lastWorkoutName
                ?: workoutEngineManager.getCurrentWorkout()?.name
                ?: getString(R.string.free_run_name)
            Log.d(TAG, "startNewRun: saving existing run as '$workoutName'")
            val snapshot = createRunSnapshot(workoutName)
            // Reset AFTER snapshot captures executionSteps
            workoutEngineManager.resetWorkout()
            if (snapshot != null) {
                exportWorkoutToFit(snapshot)
            }
        }

        // Clear and start fresh
        workoutRecorder.clearData()
        chartManager.clearData()
        workoutRecorder.startRecording()
        workoutStartTimeMs = System.currentTimeMillis()
        workoutDataExported = false  // Reset export flag for new session
        lastWorkoutName = null  // Clear stored workout name
        lastTrainingMetricsUpdateMs = 0  // Reset to trigger immediate update

        // Update screenshot manager with new run info (if enabled, it keeps taking screenshots)
        val workoutName = workoutEngineManager.getCurrentWorkout()?.name ?: getString(R.string.free_run_name)
        screenshotManager.setWorkoutInfo(workoutName, workoutStartTimeMs)
        hudDisplayManager.updateTrainingMetrics(0.0)  // Reset display
        // Clear any old persisted state and start fresh persistence
        runPersistenceManager.clearPersistedRun()
        startPersistence()
    }

    /**
     * Resume recording after a pause (e.g., treadmill resumes from PAUSED state).
     */
    private fun resumeRun() {
        Log.d(TAG, "Resuming run recording")
        workoutRecorder.resumeRecording()
    }

    /**
     * Pause recording (e.g., treadmill enters PAUSED state).
     */
    private fun pauseRun() {
        Log.d(TAG, "Pausing run recording")
        workoutRecorder.pauseRecording()
        // Persist immediately on pause (crash protection)
        persistCurrentRunState()
        // Take screenshot if enabled
        screenshotManager.takeScreenshotIfEnabled("paused")
    }

    /**
     * Reset run state without exporting. Internal use only.
     * IMPORTANT: Caller must ensure data is exported before calling this if needed.
     * Use saveAndClearRun() for the safe public interface that exports first.
     */
    private fun resetRunState() {
        Log.d(TAG, "Resetting run state")
        workoutRecorder.clearData()
        chartManager.clearData()
        chartManager.clearPlannedSegments()
        hudDisplayManager.updateTrainingMetrics(0.0)  // Reset TSS display
        hudDisplayManager.updateDistance(0.0)  // Reset distance display
        screenshotManager.disable()
        hudDisplayManager.updateCameraButtonState(false)
        workoutDataExported = false
        lastWorkoutName = null
        // Clear persisted data and stop persistence
        runPersistenceManager.clearPersistedRun()
        stopPersistence()
    }

    /**
     * Save current run to FIT (if unsaved data exists) and then clear state.
     * This is the safe way to clear run data - ensures no data is ever lost.
     *
     * @param workoutName Name for the FIT file if export is needed
     */
    private fun saveAndClearRun(workoutName: String) {
        val dataCount = workoutRecorder.getDataPointCount()
        Log.d(TAG, "saveAndClearRun: workoutName=$workoutName, dataCount=$dataCount, exported=$workoutDataExported")

        // Export if there's unsaved data
        if (dataCount > 0 && !workoutDataExported) {
            val snapshot = createRunSnapshot(workoutName)
            if (snapshot != null) {
                exportWorkoutToFit(snapshot)
            }
        }

        resetRunState()
    }

    /**
     * Create an immutable snapshot of all data needed for FIT export.
     * MUST be called BEFORE any cleanup operations (engine reset, data clear).
     *
     * @param workoutName Name for the FIT file
     * @return RunSnapshot with all data, or null if no data to export
     */
    private fun createRunSnapshot(workoutName: String): RunSnapshot? {
        val data = workoutRecorder.getWorkoutData()
        if (data.isEmpty()) {
            Log.w(TAG, "createRunSnapshot: no data to export")
            return null
        }

        val pauseEvents = workoutRecorder.getPauseEvents()
        val executionSteps = workoutEngineManager.getExecutionSteps().takeIf { it.isNotEmpty() }
        val originalSteps = workoutEngineManager.getOriginalSteps().takeIf { it.isNotEmpty() }

        Log.d(TAG, "createRunSnapshot: workoutName=$workoutName, dataSize=${data.size}, " +
            "pauseEvents=${pauseEvents.size}, executionSteps=${executionSteps?.size ?: "null"}, " +
            "originalSteps=${originalSteps?.size ?: "null"}")

        return RunSnapshot(
            workoutName = workoutName,
            startTimeMs = workoutStartTimeMs,
            workoutData = data.toList(),  // Defensive copy
            pauseEvents = pauseEvents.toList(),
            executionSteps = executionSteps?.toList(),
            originalSteps = originalSteps?.toList(),
            userSettings = UserExportSettings(
                hrRest = state.userHrRest,
                lthrBpm = state.userLthrBpm,
                ftpWatts = state.userFtpWatts,
                thresholdPaceKph = state.thresholdPaceKph,
                isMale = state.userIsMale,
                fitManufacturer = state.fitManufacturer,
                fitProductId = state.fitProductId,
                fitDeviceSerial = state.fitDeviceSerial,
                fitSoftwareVersion = state.fitSoftwareVersion
            )
        )
    }

    /**
     * Export workout data to FIT file using a pre-captured snapshot.
     * The snapshot must be created BEFORE any cleanup operations.
     *
     * @param snapshot Immutable snapshot containing all export data
     */
    private fun exportWorkoutToFit(snapshot: RunSnapshot) {
        // Check if already exported for this session
        if (workoutDataExported) {
            Log.w(TAG, "Workout data already exported, skipping")
            mainHandler.post {
                Toast.makeText(this, getString(R.string.fit_export_already_exported), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Skip export for runs shorter than 1 minute
        if (snapshot.workoutData.size < 60) {
            Log.d(TAG, "Skipping export - run too short (${snapshot.workoutData.size} seconds)")
            mainHandler.post {
                Toast.makeText(this, getString(R.string.fit_export_too_short), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Log step indices for debugging
        val stepIndices = snapshot.workoutData.map { it.stepIndex }.distinct()
        Log.d(TAG, "exportWorkoutToFit(snapshot): workoutName=${snapshot.workoutName}, " +
            "dataSize=${snapshot.workoutData.size}, pauseEvents=${snapshot.pauseEvents.size}, " +
            "executionSteps=${snapshot.executionSteps?.size ?: "null"}, stepIndices=$stepIndices")

        // Mark as exported BEFORE async operation to prevent race conditions
        workoutDataExported = true

        // Export in background to avoid blocking UI
        serviceScope.launch(Dispatchers.IO) {
            // Export FIT file
            val fitResult = fitFileExporter.exportWorkout(
                workoutData = snapshot.workoutData,
                workoutName = snapshot.workoutName,
                startTimeMs = snapshot.startTimeMs,
                userHrRest = snapshot.userSettings.hrRest,
                userLthr = snapshot.userSettings.lthrBpm,
                userFtpWatts = snapshot.userSettings.ftpWatts,
                thresholdPaceKph = snapshot.userSettings.thresholdPaceKph,
                userIsMale = snapshot.userSettings.isMale,
                pauseEvents = snapshot.pauseEvents,
                executionSteps = snapshot.executionSteps,
                originalSteps = snapshot.originalSteps,
                fitManufacturer = snapshot.userSettings.fitManufacturer,
                fitProductId = snapshot.userSettings.fitProductId,
                fitDeviceSerial = snapshot.userSettings.fitDeviceSerial,
                fitSoftwareVersion = snapshot.userSettings.fitSoftwareVersion
            )

            if (fitResult != null) {
                // Clear persisted data on successful export
                runPersistenceManager.clearPersistedRun()
                stopPersistence()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@HUDService,
                        getString(R.string.fit_export_success, fitResult.displayPath),
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Auto-upload to Garmin Connect if enabled
                if (state.garminAutoUploadEnabled) {
                    uploadToGarminConnect(fitResult, snapshot.workoutName, snapshot.startTimeMs)
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@HUDService,
                        getString(R.string.fit_export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Upload FIT file to Garmin Connect and optionally attach a screenshot.
     * Handles auth failures by launching re-auth WebView.
     * Must be called from a coroutine on Dispatchers.IO.
     */
    /**
     * Upload FIT file to Garmin Connect and optionally attach a screenshot.
     * Handles auth failures by launching re-auth WebView.
     *
     * @param isRetryAfterLogin true when called after re-auth (prevents infinite login loops)
     */
    private suspend fun uploadToGarminConnect(
        fitResult: FitFileExporter.FitExportResult,
        workoutName: String,
        startTimeMs: Long,
        isRetryAfterLogin: Boolean = false
    ) {
        if (!garminUploader.isAuthenticated()) {
            if (isRetryAfterLogin) return
            withContext(Dispatchers.Main) {
                launchGarminLoginForUpload(fitResult, workoutName, startTimeMs)
            }
            return
        }

        val uploadResult = garminUploader.uploadFitFile(fitResult.fitData, fitResult.filename)

        if (uploadResult != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@HUDService,
                    getString(R.string.garmin_upload_success),
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Try to attach screenshot (only if we got an activity ID)
            if (uploadResult.activityId > 0) {
                val screenshot = findLastScreenshot(workoutName, startTimeMs)
                if (screenshot != null) {
                    val photoResult = garminUploader.uploadScreenshot(uploadResult.activityId, screenshot)
                    Log.d(TAG, "Screenshot upload: $photoResult")
                }
            } else {
                Log.d(TAG, "Upload accepted (uploadId=${uploadResult.uploadId}) but no activity ID — skipping screenshot")
            }
        } else if (!isRetryAfterLogin && garminUploader.isOAuth2Expired()) {
            Log.d(TAG, "Garmin upload auth failed, launching re-auth")
            withContext(Dispatchers.Main) {
                launchGarminLoginForUpload(fitResult, workoutName, startTimeMs)
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@HUDService,
                    getString(R.string.garmin_upload_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Launch Garmin login WebView for re-auth, then retry upload on success.
     * Must be called on Main thread.
     */
    private fun launchGarminLoginForUpload(
        fitResult: FitFileExporter.FitExportResult,
        workoutName: String,
        startTimeMs: Long
    ) {
        showGarminLoginOverlay { ticket ->
            if (ticket != null) {
                serviceScope.launch(Dispatchers.IO) {
                    val success = garminUploader.exchangeTicketForTokens(ticket)
                    if (success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@HUDService,
                                getString(R.string.garmin_login_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        uploadToGarminConnect(fitResult, workoutName, startTimeMs, isRetryAfterLogin = true)
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@HUDService,
                                getString(R.string.garmin_login_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } else {
                Toast.makeText(
                    this@HUDService,
                    getString(R.string.garmin_upload_skipped),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Find the last screenshot taken for this workout run.
     * Screenshots are saved to Downloads/tHUD/screenshots/ with format:
     * {sanitizedWorkoutName}_{runStartTimestamp}_{screenshotTimestamp}.png
     *
     * @param workoutName Original workout name (will be sanitized)
     * @param startTimeMs Run start time in milliseconds
     * @return The most recently modified matching screenshot file, or null
     */
    private fun findLastScreenshot(workoutName: String, startTimeMs: Long): java.io.File? {
        return try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val screenshotsDir = java.io.File(downloadsDir, "tHUD/screenshots")
            if (!screenshotsDir.exists()) return null

            val sanitizedName = workoutName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            val runStartStr = dateFormat.format(java.util.Date(startTimeMs))
            val prefix = "${sanitizedName}_${runStartStr}_"

            screenshotsDir.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".png") }
                ?.maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding screenshot: ${e.message}")
            null
        }
    }

    /**
     * Export workout data to FIT file.
     * Creates a snapshot and delegates to the snapshot-based export.
     * Called when a workout ends (either completed or manually stopped).
     *
     * @param workoutName Name for the FIT file (workout name or "Free Run")
     */
    private fun exportWorkoutToFit(workoutName: String) {
        // Check if already exported for this session
        if (workoutDataExported) {
            Log.w(TAG, "Workout data already exported, skipping")
            mainHandler.post {
                Toast.makeText(this, getString(R.string.fit_export_already_exported), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Create snapshot before any potential cleanup
        val snapshot = createRunSnapshot(workoutName)
        if (snapshot == null) {
            Log.w(TAG, "No workout data to export")
            mainHandler.post {
                Toast.makeText(this, getString(R.string.fit_export_no_data), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Delegate to snapshot-based export
        exportWorkoutToFit(snapshot)
    }

    /**
     * Calculate and update training metrics display if enough time has passed.
     * Throttled to avoid expensive calculations on every telemetry update.
     */
    private fun updateTrainingMetricsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastTrainingMetricsUpdateMs < TRAINING_METRICS_UPDATE_INTERVAL_MS) {
            return
        }
        lastTrainingMetricsUpdateMs = now

        // Get workout data from recorder
        val workoutData = workoutRecorder.getWorkoutData()
        if (workoutData.isEmpty()) {
            Log.v(TAG, "Training metrics: no workout data")
            hudDisplayManager.updateTrainingMetrics(0.0)
            return
        }

        // Extract HR samples (elapsedMs, heartRateBpm)
        val hrSamples = workoutData
            .filter { it.heartRateBpm > 0 }
            .map { Pair(it.elapsedMs, it.heartRateBpm) }

        if (hrSamples.isEmpty()) {
            Log.v(TAG, "Training metrics: no HR samples (workoutData=${workoutData.size})")
            hudDisplayManager.updateTrainingMetrics(0.0)
            return
        }

        // Extract power samples (elapsedMs, powerWatts) for power-based TSS
        val powerSamples = workoutData
            .filter { it.powerWatts > 0 }
            .map { Pair(it.elapsedMs, it.powerWatts) }

        // Extract speed samples (elapsedMs, speedKph) for pace-based TSS fallback
        val speedSamples = workoutData
            .filter { it.speedKph > 0 }
            .map { Pair(it.elapsedMs, it.speedKph) }

        // Calculate TSS (Power > HR > Pace priority)
        val metrics = TrainingMetricsCalculator.calculate(
            hrSamples = hrSamples,
            powerSamples = powerSamples,
            speedSamples = speedSamples,
            hrRest = state.userHrRest,
            lthr = state.userLthrBpm,
            ftpWatts = state.userFtpWatts,
            thresholdPaceKph = state.thresholdPaceKph
        )

        Log.v(TAG, "Training metrics: tss=${metrics.tss}, " +
                "tssSource=${metrics.tssSource}, hrSamples=${hrSamples.size}, powerSamples=${powerSamples.size}")

        // Update HUD display with TSS only
        hudDisplayManager.updateTrainingMetrics(metrics.tss)
    }

    // ==================== HUD Visibility ====================

    private fun showHud() {
        hudDisplayManager.showHud()
        updateNotification()

        // Restore previously visible panels
        val chartVisible = settingsManager.getSavedChartVisible()
        if (chartVisible) {
            chartManager.showChart()
        }
        // Only restore workout panel if a workout is actually loaded
        if (settingsManager.getSavedWorkoutPanelVisible() && workoutEngineManager.isWorkoutLoaded()) {
            workoutPanelManager.showPanel()
        }

        // Set initial toggle button states
        hudDisplayManager.updateChartButtonState(chartVisible)
        hudDisplayManager.updateCameraButtonState(screenshotManager.isEnabled)
        // Note: Recording is NOT started here - it's controlled by treadmill state, not HUD visibility
    }

    private fun hideHud() {
        // Close any open popups and dialogs first
        popupManager.closeAllPopups()
        settingsManager.removeDialog()
        hrSensorManager.removeDialog()
        strydManager.removeDialog()
        strydManager.removeMetricSelector()
        bluetoothSensorDialogManager.removeDialog()

        // Hide all visual panels - do NOT stop recording
        // The run continues in the background regardless of HUD visibility
        chartManager.hideChart()
        workoutPanelManager.hidePanel()

        hudDisplayManager.hideHud()
        updateNotification()
    }

    private fun openWorkoutLibrary() {
        savePanelState()
        hideAllPanels()
        Log.d(TAG, "Opening library, saved state: hud=$savedHudVisible, chart=$savedChartVisible, panel=$savedWorkoutPanelVisible")

        val intent = Intent(this, WorkoutEditorActivityNew::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun restorePanels() {
        restorePanelState()
        clearSavedPanelState()
    }

    // ==================== Panel Visibility Helpers ====================

    private fun savePanelState() {
        if (!editorPanelStateSaved) {
            savedHudVisible = state.isHudVisible.get()
            savedChartVisible = chartManager.isVisible
            savedWorkoutPanelVisible = workoutPanelManager.isVisible
            editorPanelStateSaved = true
        }
    }

    private fun hideAllPanels() {
        if (state.isHudVisible.get()) hideHud()
        if (chartManager.isVisible) chartManager.hideChart()
        if (workoutPanelManager.isVisible) workoutPanelManager.hidePanel()
    }

    private fun restorePanelState() {
        Log.d(TAG, "Restoring panels: hud=$savedHudVisible, chart=$savedChartVisible, panel=$savedWorkoutPanelVisible")
        if (savedHudVisible) showHud()
        if (savedChartVisible) chartManager.showChart()
        // Only restore workout panel if a workout is actually loaded
        if (savedWorkoutPanelVisible && workoutEngineManager.isWorkoutLoaded()) {
            workoutPanelManager.showPanel()
        }
    }

    private fun clearSavedPanelState() {
        editorPanelStateSaved = false
        savedHudVisible = false
        savedChartVisible = false
        savedWorkoutPanelVisible = false
    }

    // ==================== Editor Lifecycle ====================

    private fun onEditorForeground() {
        savePanelState()
        if (editorPanelStateSaved) {
            Log.d(TAG, "Editor foreground - saved state: hud=$savedHudVisible, chart=$savedChartVisible, panel=$savedWorkoutPanelVisible")
        }
        hideAllPanels()
    }

    private fun onEditorBackground() {
        restorePanelState()
    }

    private fun onEditorClosed() {
        Log.d(TAG, "Editor closed - clearing saved state flag")
        clearSavedPanelState()
    }

    // ==================== Free Run Confirmation Dialog ====================

    /**
     * Shows a confirmation dialog when the user starts the treadmill via pace popup
     * while a workout is loaded. Gives options to start free run or run the workout.
     *
     * @param selectedSpeedKph The speed selected from the pace popup (used if user chooses free run)
     */
    private fun showFreeRunConfirmationDialog(selectedSpeedKph: Double) {
        if (freeRunDialogView != null) return  // Already showing

        val dialogWidthFraction = resources.getFloat(R.dimen.dialog_width_fraction)

        // Create container using helper
        val container = OverlayHelper.createDialogContainer(this)
        container.addView(OverlayHelper.createDialogTitle(this, getString(R.string.free_run_dialog_title)))
        container.addView(OverlayHelper.createDialogMessage(this, getString(R.string.free_run_dialog_message)))

        // Buttons row
        val buttonsRow = OverlayHelper.createDialogButtonRow(this)

        // Free Run button
        val freeRunBtn = Button(this).apply {
            text = getString(R.string.btn_free_run)
            setOnClickListener {
                Log.d(TAG, "User chose Free Run - dismissing workout and starting belt at $selectedSpeedKph kph")
                dismissFreeRunDialog()
                // Get workout name before reset (for potential FIT export)
                val workoutName = workoutEngineManager.getCurrentWorkout()?.name
                    ?: getString(R.string.free_run_name)
                // Dismiss the loaded workout
                workoutEngineManager.resetWorkout()
                workoutPanelManager.hidePanel()
                // Save any existing data and clear (no run data should ever be lost)
                saveAndClearRun(workoutName)
                // Start the belt at the selected speed
                popupManager.startBeltAtSpeed(selectedSpeedKph)
            }
        }
        buttonsRow.addView(freeRunBtn)

        // Run Workout button
        val runWorkoutBtn = Button(this).apply {
            text = getString(R.string.btn_start_workout)
            setOnClickListener {
                Log.d(TAG, "User chose Run Workout - starting loaded workout")
                dismissFreeRunDialog()
                // Save any existing data and start the loaded workout
                startNewRun()
                workoutEngineManager.startWorkout { }
            }
        }
        buttonsRow.addView(runWorkoutBtn)

        container.addView(buttonsRow)
        freeRunDialogView = container

        // Add to window using helper
        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        val params = OverlayHelper.createOverlayParams(dialogWidth)
        windowManager.addView(container, params)
    }

    private fun dismissFreeRunDialog() {
        freeRunDialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing free run dialog: ${e.message}")
            }
        }
        freeRunDialogView = null
    }

    // ==================== Certificate Error Dialog ====================

    /**
     * Shows a dialog explaining that mTLS certificates are missing.
     * User must install certificates before the app can connect to GlassOS.
     */
    private fun showCertificateErrorDialog(missingFiles: List<String>, certsDirectory: String) {
        if (certificateErrorDialogView != null) return  // Already showing

        val dialogWidthFraction = resources.getFloat(R.dimen.dialog_width_fraction)

        // Create container using helper
        val container = OverlayHelper.createDialogContainer(this)
        container.addView(OverlayHelper.createDialogTitle(this, getString(R.string.certs_error_dialog_title)))

        // Message with missing files and directory
        val message = getString(R.string.certs_error_dialog_message, missingFiles.joinToString(", "), certsDirectory)
        container.addView(OverlayHelper.createDialogMessage(this, message))

        // Buttons row
        val buttonsRow = OverlayHelper.createDialogButtonRow(this)

        // Dismiss button
        val dismissBtn = Button(this).apply {
            text = getString(R.string.btn_dismiss)
            setOnClickListener {
                dismissCertificateErrorDialog()
            }
        }
        buttonsRow.addView(dismissBtn)

        // Retry button
        val retryBtn = Button(this).apply {
            text = getString(R.string.btn_retry)
            setOnClickListener {
                dismissCertificateErrorDialog()
                // Restart connection attempt
                telemetryManager.startConnection()
            }
        }
        buttonsRow.addView(retryBtn)

        container.addView(buttonsRow)
        certificateErrorDialogView = container

        // Add to window using helper
        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        val params = OverlayHelper.createOverlayParams(dialogWidth)
        windowManager.addView(container, params)
    }

    private fun dismissCertificateErrorDialog() {
        certificateErrorDialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing certificate error dialog: ${e.message}")
            }
        }
        certificateErrorDialogView = null
    }

    // ==================== Garmin Login Overlay ====================

    // Garmin SSO URLs — matching garth/garmin-connect library flow
    private val garminSsoEmbed = "https://sso.garmin.com/sso/embed"
    private val garminSsoSignin = "https://sso.garmin.com/sso/signin" +
        "?id=gauth-widget&embedWidget=true&clientId=GarminConnect&locale=en" +
        "&gauthHost=${java.net.URLEncoder.encode("https://sso.garmin.com/sso/embed", "UTF-8")}" +
        "&service=${java.net.URLEncoder.encode("https://sso.garmin.com/sso/embed", "UTF-8")}" +
        "&source=${java.net.URLEncoder.encode("https://sso.garmin.com/sso/embed", "UTF-8")}" +
        "&redirectAfterAccountLoginUrl=${java.net.URLEncoder.encode("https://sso.garmin.com/sso/embed", "UTF-8")}" +
        "&redirectAfterAccountCreationUrl=${java.net.URLEncoder.encode("https://sso.garmin.com/sso/embed", "UTF-8")}"

    /**
     * Show a dialog-sized overlay WebView for Garmin SSO login.
     *
     * Flow (matches garth/garmin-connect libraries):
     * 1. GET /sso/embed — initializes session cookies
     * 2. GET /sso/signin?embedWidget=true... — shows login form
     * 3. User submits → AJAX POST → response contains ticket in HTML
     * 4. onPageFinished extracts ticket via JS: embed?ticket=<TICKET>
     */
    private fun showGarminLoginOverlay(callback: (ticket: String?) -> Unit) {
        if (garminLoginOverlayView != null) return  // Already showing

        garminLoginCallback = callback

        val borderPad = resources.getDimensionPixelSize(R.dimen.popup_padding) // 8dp dark border
        val titlePad = resources.getDimensionPixelSize(R.dimen.dialog_input_padding)
        val formPad = resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical) // 40dp white padding

        // Dark opaque container — padding creates a visible border around the white form area
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@HUDService, R.color.editor_background))
            setPadding(borderPad, borderPad, borderPad, borderPad)
        }

        // Title + cancel row
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(titlePad, 0, titlePad, borderPad)
        }
        val title = android.widget.TextView(this).apply {
            text = getString(R.string.garmin_upload_section)
            setTextColor(ContextCompat.getColor(this@HUDService, R.color.text_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.dialog_title_text_size))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(title)
        val cancelBtn = Button(this).apply {
            text = getString(R.string.btn_cancel)
            setOnClickListener { dismissGarminLoginOverlay(ticket = null) }
        }
        topBar.addView(cancelBtn)
        container.addView(topBar)

        val webView = android.webkit.WebView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false

            // Enable cookies — SSO requires session cookies across requests
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view == null || url == null) return
                    Log.d(TAG, "Garmin SSO page finished: $url")

                    // Step 1 done: cookie init page loaded → navigate to signin form
                    if (url.contains("/sso/embed") && !url.contains("signin")) {
                        Log.d(TAG, "Garmin SSO cookies initialized, loading signin form")
                        view.loadUrl(garminSsoSignin)
                        return
                    }

                    // After form submission, check if page contains a ticket.
                    // On success, Garmin returns HTML with <title>Success</title>
                    // and a URL containing embed?ticket=ST-XXXXX
                    if (url.contains("/sso/signin")) {
                        view.evaluateJavascript(
                            """
                            (function() {
                                var title = document.title || '';
                                if (title.indexOf('Success') >= 0) {
                                    var html = document.documentElement.innerHTML;
                                    var match = html.match(/embed\\?ticket=([^"&]+)/);
                                    if (match) return match[1];
                                }
                                return null;
                            })()
                            """.trimIndent()
                        ) { result ->
                            val ticket = result?.trim('"')
                            if (ticket != null && ticket != "null" && ticket.startsWith("ST-")) {
                                Log.d(TAG, "Garmin SSO ticket extracted from page")
                                dismissGarminLoginOverlay(ticket = ticket)
                            }
                        }
                    }
                }

                // Backup: catch ticket in URL redirects
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return checkGarminTicket(url)
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, url: String?): Boolean {
                    return url != null && checkGarminTicket(url)
                }

                // Backup: catch ticket in sub-resource requests
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString()
                    if (url != null && url.contains("ticket=ST-")) {
                        mainHandler.post { checkGarminTicket(url) }
                    }
                    return null
                }
            }
        }
        // White frame with 40dp padding around SSO form — looks like a clean card
        val whiteFrame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(formPad, formPad, formPad, formPad)
        }
        whiteFrame.addView(webView)
        container.addView(whiteFrame)

        garminLoginOverlayView = container

        // Compact overlay: ~25% width, ~40% height on 1920x1080 @ density 1.0
        val density = resources.displayMetrics.density
        val dialogWidth = (480 * density).toInt()
        val dialogHeight = (430 * density).toInt()
        val params = OverlayHelper.createOverlayParams(
            width = dialogWidth,
            height = dialogHeight,
            focusable = true,
            touchModal = true
        )
        windowManager.addView(container, params)

        // Step 1: Load /sso/embed to initialize session cookies, then
        // onPageFinished redirects to the signin form (Step 2)
        webView.loadUrl("$garminSsoEmbed?clientId=GarminConnect&locale=en" +
            "&service=${java.net.URLEncoder.encode(garminSsoEmbed, "UTF-8")}")
        Log.d(TAG, "Garmin login overlay shown (${dialogWidth}x${dialogHeight})")
    }

    /**
     * Check if a URL contains a Garmin SSO ticket.
     * @return true if ticket found (stops navigation)
     */
    private fun checkGarminTicket(url: String): Boolean {
        if (garminLoginOverlayView == null) return false  // Already dismissed
        val uri = android.net.Uri.parse(url)
        val ticket = uri.getQueryParameter("ticket")
        if (ticket != null && ticket.startsWith("ST-")) {
            Log.d(TAG, "SSO ticket intercepted")
            dismissGarminLoginOverlay(ticket)
            return true
        }
        return false
    }

    private fun dismissGarminLoginOverlay(ticket: String?) {
        garminLoginOverlayView?.let { container ->
            // Destroy WebView to release native rendering resources.
            // Must detach from parent before calling destroy().
            fun findAndDestroyWebView(view: android.view.View) {
                if (view is android.webkit.WebView) {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy()
                    return
                }
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) {
                        findAndDestroyWebView(view.getChildAt(i))
                    }
                }
            }
            findAndDestroyWebView(container)
            try {
                windowManager.removeView(container)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing Garmin login overlay: ${e.message}")
            }
        }
        garminLoginOverlayView = null
        val cb = garminLoginCallback
        garminLoginCallback = null
        cb?.invoke(ticket)
    }

    // ==================== Run Persistence ====================

    /**
     * Check if there's a persisted run and show resume dialog if found.
     */
    private fun checkForPersistedRun() {
        if (!runPersistenceManager.hasPersistedRun()) return

        val persisted = runPersistenceManager.loadPersistedRun() ?: return

        // Check if data is not too old (24 hours handled in manager)
        mainHandler.post {
            showResumeRunDialog(persisted)
        }
    }

    /**
     * Show dialog to resume or discard persisted run.
     */
    private fun showResumeRunDialog(persisted: PersistedRunState) {
        if (resumeRunDialogView != null) return  // Already showing

        val dialogWidthFraction = resources.getFloat(R.dimen.dialog_width_fraction)

        // Calculate run info
        val recorderState = persisted.recorderState
        val durationFormatted = PaceConverter.formatDuration(
            if (recorderState.dataPoints.isNotEmpty()) {
                (recorderState.dataPoints.last().elapsedMs / 1000).toInt()
            } else 0
        )
        val distanceFormatted = PaceConverter.formatDistance((recorderState.calculatedDistanceKm * 1000).toInt())

        // Create container using helper
        val container = OverlayHelper.createDialogContainer(this)
        container.addView(OverlayHelper.createDialogTitle(this, getString(R.string.resume_run_dialog_title)))

        // Message differs for free run vs structured workout
        val message = when (persisted.runType) {
            PersistedRunType.FREE_RUN -> {
                getString(R.string.resume_run_dialog_message_free, durationFormatted, distanceFormatted)
            }
            PersistedRunType.STRUCTURED -> {
                val workoutId = persisted.engineState?.workoutId ?: -1L
                // Get workout name asynchronously and update
                var workoutName = "Workout"
                workoutEngineManager.getWorkoutNameById(workoutId) { name ->
                    if (name != null) {
                        workoutName = name
                    }
                }
                getString(R.string.resume_run_dialog_message_workout, workoutName, durationFormatted, distanceFormatted)
            }
        }
        container.addView(OverlayHelper.createDialogMessage(this, message))

        // Buttons row
        val buttonsRow = OverlayHelper.createDialogButtonRow(this)

        // Discard button
        val discardBtn = Button(this).apply {
            text = getString(R.string.btn_discard)
            setOnClickListener {
                Log.d(TAG, "User chose to discard persisted run")
                dismissResumeRunDialog()
                discardPersistedRun()
            }
        }
        buttonsRow.addView(discardBtn)

        // Resume button
        val resumeBtn = Button(this).apply {
            text = getString(R.string.btn_resume)
            setOnClickListener {
                Log.d(TAG, "User chose to resume persisted run")
                dismissResumeRunDialog()
                resumePersistedRun(persisted)
            }
        }
        buttonsRow.addView(resumeBtn)

        container.addView(buttonsRow)
        resumeRunDialogView = container

        // Add to window using helper
        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        val params = OverlayHelper.createOverlayParams(dialogWidth)
        windowManager.addView(container, params)
    }

    private fun dismissResumeRunDialog() {
        resumeRunDialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing resume run dialog: ${e.message}")
            }
        }
        resumeRunDialogView = null
    }

    /**
     * Resume the persisted run by restoring all state.
     */
    private fun resumePersistedRun(persisted: PersistedRunState) {
        // Restore recorder state
        workoutRecorder.restoreFromState(persisted.recorderState)

        // Update UI with restored distance/elevation
        hudDisplayManager.updateDistance(workoutRecorder.calculatedDistanceKm)
        hudDisplayManager.updateClimb(workoutRecorder.calculatedElevationGainM)

        // Set workflow state flags
        workoutStartTimeMs = persisted.recorderState.workoutStartTimeMs
        workoutDataExported = false
        lastTrainingMetricsUpdateMs = 0

        // Restore chart data
        val dataPoints = persisted.recorderState.dataPoints
        chartManager.setData(dataPoints)

        // If structured workout, restore engine state
        if (persisted.runType == PersistedRunType.STRUCTURED && persisted.engineState != null) {
            lastWorkoutName = null
            workoutEngineManager.restoreFromPersistedState(
                persisted.engineState,
                persisted.adjustmentControllerState
            ) { success ->
                if (success) {
                    // Engine restored - update chart with planned segments
                    val steps = workoutEngineManager.getExecutionSteps()
                    val segments = workoutEngineManager.convertToPlannedSegments(steps)
                    chartManager.setPlannedSegments(segments)

                    // Update workout panel
                    val workout = workoutEngineManager.getCurrentWorkout()
                    if (workout != null) {
                        workoutPanelManager.showPanel()
                        workoutPanelManager.setWorkoutInfo(workout, steps, workoutEngineManager.getPhaseCounts())
                    }

                    // Store workout name for FIT export
                    lastWorkoutName = workout?.name

                    Toast.makeText(this, getString(R.string.toast_run_resumed), Toast.LENGTH_SHORT).show()
                } else {
                    // Workout no longer exists - continue as free run
                    Log.w(TAG, "Failed to restore structured workout - continuing as free run")
                    Toast.makeText(this, getString(R.string.toast_run_resume_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Free run - just show chart
            chartManager.showChart()
            Toast.makeText(this, getString(R.string.toast_run_resumed), Toast.LENGTH_SHORT).show()
        }

        // Clear persisted file (now in memory)
        runPersistenceManager.clearPersistedRun()

        // Start periodic persistence for ongoing protection
        startPersistence()

        Log.d(TAG, "Resumed persisted run: type=${persisted.runType}, dataPoints=${persisted.recorderState.dataPoints.size}")
    }

    /**
     * Discard the persisted run data.
     */
    private fun discardPersistedRun() {
        runPersistenceManager.clearPersistedRun()
        Log.d(TAG, "Discarded persisted run")
    }

    /**
     * Persist the current run state to disk.
     */
    private fun persistCurrentRunState() {
        // Only persist if there's data to save
        if (workoutRecorder.getDataPointCount() == 0) return

        val recorderState = workoutRecorder.exportState()
        val engineState = workoutEngineManager.exportEnginePersistenceState()
        val adjustmentState = workoutEngineManager.exportAdjustmentControllerState()

        val runType = if (engineState != null) {
            PersistedRunType.STRUCTURED
        } else {
            PersistedRunType.FREE_RUN
        }

        runPersistenceManager.persistRunState(recorderState, engineState, adjustmentState, runType)
    }

    /**
     * Start periodic persistence.
     */
    private fun startPersistence() {
        if (isPersistenceActive) return
        isPersistenceActive = true
        persistenceHandler.postDelayed(persistRunnable, persistenceInterval)
        Log.d(TAG, "Started run persistence (every ${persistenceInterval / 1000}s)")
    }

    /**
     * Stop periodic persistence.
     */
    private fun stopPersistence() {
        if (!isPersistenceActive) return
        isPersistenceActive = false
        persistenceHandler.removeCallbacks(persistRunnable)
        Log.d(TAG, "Stopped run persistence")
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Get current workout step info for FIT lap export.
     * Returns (-1, "") for free runs or when no structured workout is active.
     */
    private fun getCurrentStepInfo(): Pair<Int, String> {
        return when (val engineState = workoutEngineManager.getState()) {
            is WorkoutExecutionState.Running -> Pair(engineState.currentStepIndex, engineState.currentStep.displayName)
            is WorkoutExecutionState.Paused -> Pair(engineState.currentStepIndex, engineState.currentStep.displayName)
            else -> Pair(-1, "")
        }
    }

    private fun buildNotification(): android.app.Notification {
        val toggleIntent = Intent(this, HUDService::class.java).apply {
            action = ACTION_TOGGLE_HUD
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HUDService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = getString(
            if (state.isHudVisible.get()) R.string.notification_hud_visible else R.string.notification_hud_hidden
        )
        val toggleText = getString(
            if (state.isHudVisible.get()) R.string.notification_hide_hud else R.string.notification_show_hud
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, toggleText, togglePendingIntent)
            .addAction(0, getString(R.string.notification_stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ==================== TelemetryManager.Listener ====================

    override fun onConnectionEstablished(isReconnection: Boolean) {
        Log.d(TAG, "Connection established (isReconnection=$isReconnection)")
        // Initialize workout engine now that client is available
        mainHandler.post {
            workoutEngineManager.initializeEngine()
            // Only check for persisted run on initial connection
            // On reconnection, run data is still in memory - don't prompt to restore
            if (!isReconnection) {
                checkForPersistedRun()
            } else {
                // On reconnection, set flag to ignore the first IDLE state from GlassOS
                // (GlassOS sends IDLE after restart, but our run is still in memory)
                state.isReconnecting.set(true)
            }
        }
    }

    override fun onConnectionLost() {
        // Connection lost - telemetry will stop updating
    }

    override fun onCertificatesNotFound(missingFiles: List<String>, certsDirectory: String) {
        Log.e(TAG, "Certificates not found: $missingFiles in $certsDirectory")
        mainHandler.post {
            showCertificateErrorDialog(missingFiles, certsDirectory)
        }
    }

    override fun onTelemetryUpdated() {
        // Always record data when treadmill is running, regardless of HUD visibility.
        // Use forceRecord=true to bypass _isRecording flag - ensures continuous
        // data regardless of internal state transitions.
        // The run continues in background even when HUD is hidden.
        val shouldForceRecord = state.currentSpeedKph > 0

        // Get current workout step info for FIT lap export
        val (stepIndex, stepName) = getCurrentStepInfo()

        workoutRecorder.recordDataPoint(
            state.currentSpeedKph,
            state.currentInclinePercent,
            state.currentHeartRateBpm,
            state.paceCoefficient,
            state.currentElapsedSeconds,
            forceRecord = shouldForceRecord,
            powerWatts = state.currentPowerWatts,
            cadenceSpm = state.currentCadenceSpm,
            rawPowerWatts = state.currentRawPowerWatts,
            inclinePowerWatts = state.currentPowerWatts - state.currentRawPowerWatts,
            stepIndex = stepIndex,
            stepName = stepName
        )

        // Update sensor connection status displays
        hudDisplayManager.updateHrSensorStatus(state.hrSensorConnected)
        hudDisplayManager.updateFootPod(strydManager.getSelectedMetric(), state.strydConnected)

        // Update training metrics (throttled to every 5 seconds)
        updateTrainingMetricsIfNeeded()

        // Ensure chart is updating when visible and treadmill is running
        if (shouldForceRecord && chartManager.isVisible) {
            chartManager.ensureRefreshRunning()
        }

        // IMPORTANT: Pass ADJUSTED speed to workout engine, never raw treadmill speed.
        // All workout logic uses adjusted speed (actual pace = treadmill * coefficient).
        val adjustedSpeedKph = state.currentSpeedKph * state.paceCoefficient
        workoutEngineManager.onTelemetryUpdate(
            adjustedSpeedKph,
            state.currentInclinePercent,
            state.currentHeartRateBpm
        )

        // Update engine with calculated distance (from adjusted speed, not raw treadmill)
        workoutEngineManager.onDistanceUpdate(workoutRecorder.calculatedDistanceKm)
    }

    override fun onWorkoutStateChanged(workoutState: WorkoutState, previousState: WorkoutState) {
        // Update the treadmill workout state for UI components
        state.treadmillWorkoutState = workoutState
        // Update pace box display based on new state
        hudDisplayManager.updatePaceBoxState(workoutState)

        // Handle GlassOS reconnection: ignore the initial IDLE state
        // GlassOS sends IDLE when it starts up, but our run data is still in memory
        if (state.isReconnecting.get()) {
            if (workoutState == WorkoutState.WORKOUT_STATE_IDLE) {
                Log.d(TAG, "Ignoring post-reconnection IDLE state (run data preserved in memory)")
                // Clear the flag - we've handled the reconnection
                state.isReconnecting.set(false)
                return
            }
            // Any other state clears the reconnection flag
            state.isReconnecting.set(false)
        }

        when (workoutState) {
            WorkoutState.WORKOUT_STATE_RUNNING -> {
                handleTreadmillRunning(previousState)
            }
            WorkoutState.WORKOUT_STATE_PAUSED -> {
                // Skip if programmatic start in progress - treadmill may briefly pause during stop/start
                if (!state.isStartingWorkoutProgrammatically.get()) {
                    pauseRun()
                    workoutEngineManager.handlePhysicalPause()
                }
            }
            WorkoutState.WORKOUT_STATE_IDLE -> {
                handleTreadmillStopped(previousState)
                // Preload free run workout so physical Start button works
                // Must happen AFTER reaching IDLE, not during RESULTS (which clears segments)
                serviceScope.launch(Dispatchers.IO) {
                    telemetryManager.preloadFreeRunWorkout()
                }
            }
            else -> {
                handleTreadmillStopped(previousState)
            }
        }
    }

    /**
     * Handle treadmill entering RUNNING state.
     * Determines whether this is a fresh start or a resume and handles recording accordingly.
     */
    private fun handleTreadmillRunning(previousState: WorkoutState) {
        val isResume = previousState == WorkoutState.WORKOUT_STATE_PAUSED
        val engineState = workoutEngineManager.getState()
        val isWorkoutLoaded = workoutEngineManager.isWorkoutLoaded()
        val isWorkoutIdle = engineState is WorkoutExecutionState.Idle

        // Skip if a programmatic workout start is already handling recording
        if (state.isStartingWorkoutProgrammatically.get()) {
            Log.d(TAG, "Treadmill RUNNING - skipping recording (programmatic start in progress)")
            workoutEngineManager.handlePhysicalStart()
            return
        }

        when {
            isResume -> {
                // Resuming from PAUSED - just resume recording
                Log.d(TAG, "Treadmill RUNNING - resuming from pause")
                resumeRun()
            }
            isWorkoutLoaded && isWorkoutIdle -> {
                // Starting a fresh structured workout via physical Start button
                Log.d(TAG, "Treadmill RUNNING - starting fresh workout")
                startNewRun()
            }
            !isWorkoutLoaded -> {
                // Starting a free run (via physical Start button)
                Log.d(TAG, "Treadmill RUNNING - starting free run")
                chartManager.clearPlannedSegments()  // Reset to free run mode
                startNewRun()
                // Set default speed and incline for free runs
                telemetryManager.setTreadmillSpeed(3.0)  // 3 kph default
                telemetryManager.setTreadmillIncline(0.0)  // 0% effective
            }
            else -> {
                // Workout is in some other state (Running, Paused, etc.) - resume recording
                Log.d(TAG, "Treadmill RUNNING - resuming (engine state: $engineState)")
                resumeRun()
            }
        }

        workoutEngineManager.handlePhysicalStart()
    }

    /**
     * Handle treadmill entering IDLE or other stopped state.
     */
    private fun handleTreadmillStopped(previousState: WorkoutState) {
        // Skip if a programmatic workout start is in progress - it will handle everything
        if (state.isStartingWorkoutProgrammatically.get()) {
            Log.d(TAG, "Treadmill stopped - skipping (programmatic start in progress)")
            return
        }

        val isSecondStop = previousState == WorkoutState.WORKOUT_STATE_PAUSED
        val isStopWhileStopped = previousState == WorkoutState.WORKOUT_STATE_IDLE
        val dataPointCount = workoutRecorder.getDataPointCount()

        Log.d(TAG, "Treadmill stopped (from $previousState), speed=${state.currentSpeedKph}, " +
            "isSecondStop=$isSecondStop, dataPoints=$dataPointCount")

        // Pause recording only on first stop (RUNNING → PAUSED)
        // On double-stop (PAUSED → IDLE): already paused, no screenshot wanted
        // On stop-while-stopped (IDLE → IDLE): nothing to pause
        if (state.currentSpeedKph <= 0 && !isSecondStop && !isStopWhileStopped) {
            pauseRun()
        }

        // CRITICAL: For double-stop, capture snapshot BEFORE any cleanup.
        // This ensures executionSteps are captured while engine still has them.
        var snapshot: RunSnapshot? = null
        if (isSecondStop && dataPointCount > 0 && !workoutDataExported) {
            val workoutName = workoutEngineManager.getCurrentWorkout()?.name
                ?: lastWorkoutName
                ?: getString(R.string.free_run_name)
            snapshot = createRunSnapshot(workoutName)
            Log.d(TAG, "Created export snapshot: workoutName=$workoutName, " +
                "executionSteps=${snapshot?.executionSteps?.size ?: "null"}")
        }

        // Handle engine state changes (may trigger Completed state)
        // AFTER snapshot is captured - safe to clear engine state now
        workoutEngineManager.handlePhysicalStop(previousState)

        if (isSecondStop) {
            // Double-stop: export from snapshot and clean up
            if (snapshot != null) {
                exportWorkoutToFit(snapshot)
            }
            // Reset state (data already captured in snapshot)
            resetRunState()
            workoutEngineManager.resetWorkout()
            workoutPanelManager.hidePanel()
            // Note: chartManager.clearPlannedSegments() already called by resetRunState()

            // Preload free run workout after delay (wait for GlassOS to reach IDLE)
            // This is a fallback in case we don't receive WORKOUT_STATE_IDLE
            mainHandler.postDelayed({
                serviceScope.launch(Dispatchers.IO) {
                    telemetryManager.preloadFreeRunWorkout()
                }
            }, 2000)
        } else if (isStopWhileStopped) {
            // Stop pressed while already stopped - unload workout if loaded but never started
            val engineState = workoutEngineManager.getState()
            if (engineState is WorkoutExecutionState.Idle && workoutEngineManager.isWorkoutLoaded()) {
                Log.d(TAG, "Stop while stopped - unloading workout")
                workoutEngineManager.resetWorkout()
                workoutPanelManager.hidePanel()
                chartManager.clearPlannedSegments()
            }
            // Note: Preload happens when we reach IDLE state
        } else {
            // First stop: just clear segments if workout was never started
            val engineState = workoutEngineManager.getState()
            if (engineState is WorkoutExecutionState.Idle || engineState == null) {
                chartManager.clearPlannedSegments()
            }
        }
    }

    override fun onSpeedUpdate(kph: Double) {
        hudDisplayManager.updatePace(kph)
        // Recording is handled via onWorkoutStateChanged - no fallback needed here
    }

    override fun onInclineUpdate(percent: Double) {
        hudDisplayManager.updateIncline(percent)
    }

    override fun onElapsedTimeUpdate(seconds: Int) {
        // Skip workout reset check in these scenarios to preserve data:
        // 1. isReconnecting is true (known GlassOS reconnection)
        // 2. Elapsed jumps backwards significantly while we have data (GlassOS restart without channel drop)
        // 3. Recording is not active (treadmill already stopped)
        val dataPointCount = workoutRecorder.getDataPointCount()
        val likelyGlassOsRestart = seconds == 0 && dataPointCount > 30  // 30+ data points = ~30 seconds
        val skipResetCheck = state.isReconnecting.get() || likelyGlassOsRestart || !workoutRecorder.isRecording

        if (!skipResetCheck) {
            workoutRecorder.checkForWorkoutReset(seconds)
        } else if (likelyGlassOsRestart) {
            // GlassOS likely restarted - preserve data and adjust timing
            Log.d(TAG, "GlassOS restart detected (elapsed=0 but $dataPointCount data points) - preserving data")
            workoutRecorder.handleGlassOsRestart(seconds)
            // Set reconnecting flag in case IDLE state hasn't been processed yet
            state.isReconnecting.set(true)
        }

        // IMPORTANT: Update engine FIRST so step completion happens before recording.
        // This ensures boundary data points (e.g., at t=30s when step 0 ends) are assigned
        // to the NEW step, not the old one. Otherwise first/last laps would be 1s too long.
        workoutEngineManager.onElapsedTimeUpdate(seconds)

        // Get current workout step info for FIT lap export (may have changed due to step completion)
        val (stepIndex, stepName) = getCurrentStepInfo()

        workoutRecorder.ensurePeriodicRecord(
            state.currentSpeedKph,
            state.currentInclinePercent,
            state.currentHeartRateBpm,
            state.paceCoefficient,
            seconds,
            powerWatts = state.currentPowerWatts,
            cadenceSpm = state.currentCadenceSpm,
            rawPowerWatts = state.currentRawPowerWatts,
            inclinePowerWatts = state.currentPowerWatts - state.currentRawPowerWatts,
            stepIndex = stepIndex,
            stepName = stepName
        )

        // Update engine with calculated distance (from adjusted speed, not raw treadmill)
        workoutEngineManager.onDistanceUpdate(workoutRecorder.calculatedDistanceKm)

        hudDisplayManager.updateElapsedTime(seconds)

        // Update training metrics periodically (throttled to every 5 seconds internally)
        // This ensures updates even when speed/incline are constant during steady-state running
        updateTrainingMetricsIfNeeded()
    }

    // ==================== SettingsManager.Listener ====================

    override fun onSettingsSaved() {
        chartManager.updateHRZones()
        chartManager.updatePowerZones()
        chartManager.updateThresholds()
        updateRecorderUserProfile()

        // Restart FTMS servers to apply new settings (device names, control permissions)
        restartFtmsServers()
    }

    override fun onGarminLoginRequested() {
        showGarminLoginOverlay { ticket ->
            if (ticket != null) {
                serviceScope.launch(Dispatchers.IO) {
                    val success = garminUploader.exchangeTicketForTokens(ticket)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(
                                this@HUDService,
                                getString(R.string.garmin_login_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            settingsManager.updateGarminLoginButton(true)
                        } else {
                            Toast.makeText(
                                this@HUDService,
                                getString(R.string.garmin_login_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun isGarminAuthenticated(): Boolean = garminUploader.isAuthenticated()

    /**
     * Restart FTMS servers to apply new settings.
     * Stops servers that should be disabled, starts servers that should be enabled.
     */
    private fun restartFtmsServers() {
        // Handle BLE FTMS server
        val bleRunning = bleFtmsServer?.isRunning() ?: false
        val bleShouldRun = state.ftmsBleReadEnabled

        if (bleRunning && !bleShouldRun) {
            Log.d(TAG, "Stopping BLE FTMS server (disabled in settings)")
            stopBleFtmsServer()
        } else if (!bleRunning && bleShouldRun) {
            Log.d(TAG, "Starting BLE FTMS server (enabled in settings)")
            startBleFtmsServer()
        } else if (bleRunning && bleShouldRun) {
            // Server running, but settings may have changed - restart to apply
            Log.d(TAG, "Restarting BLE FTMS server to apply new settings")
            stopBleFtmsServer()
            startBleFtmsServer()
        }

        // Handle DirCon server
        val dirConRunning = dirConServer?.isRunning() ?: false
        val dirConShouldRun = state.ftmsDirConReadEnabled

        if (dirConRunning && !dirConShouldRun) {
            Log.d(TAG, "Stopping DirCon server (disabled in settings)")
            stopDirConServer()
        } else if (!dirConRunning && dirConShouldRun) {
            Log.d(TAG, "Starting DirCon server (enabled in settings)")
            startDirConServer()
        } else if (dirConRunning && dirConShouldRun) {
            // Server running, but settings may have changed - restart to apply
            Log.d(TAG, "Restarting DirCon server to apply new settings")
            stopDirConServer()
            startDirConServer()
        }
    }

    /**
     * Update the workout recorder with current user profile settings for calorie calculation.
     */
    private fun updateRecorderUserProfile() {
        workoutRecorder.userWeightKg = state.userWeightKg
        workoutRecorder.userAge = state.userAge
        workoutRecorder.userIsMale = state.userIsMale
    }

    // ==================== HUDDisplayManager.Listener ====================

    override fun onPaceBoxClicked() {
        popupManager.togglePacePopup()
    }

    override fun onInclineBoxClicked() {
        popupManager.toggleInclinePopup()
    }

    override fun onHrBoxClicked() {
        // Open unified Bluetooth sensors dialog
        popupManager.closeAllPopups()
        settingsManager.removeDialog()
        hrSensorManager.removeDialog()
        strydManager.removeDialog()
        strydManager.removeMetricSelector()
        bluetoothSensorDialogManager.toggleDialog()
    }

    override fun onFootPodBoxClicked() {
        popupManager.closeAllPopups()
        settingsManager.removeDialog()
        hrSensorManager.removeDialog()
        strydManager.removeDialog()
        bluetoothSensorDialogManager.removeDialog()

        if (state.strydConnected) {
            // Show metric selector when connected, aligned with foot pod box
            strydManager.showMetricSelector(hudDisplayManager.getFootPodBoxBounds())
        } else {
            // Open unified Bluetooth sensors dialog when not connected
            strydManager.removeMetricSelector()
            bluetoothSensorDialogManager.toggleDialog()
        }
    }

    override fun onWorkoutsClicked() {
        openWorkoutLibrary()
    }

    override fun onChartClicked() {
        chartManager.toggleChart()
        hudDisplayManager.updateChartButtonState(state.isChartVisible.get())
    }

    override fun onCameraClicked() {
        val workoutName = workoutEngineManager.getCurrentWorkout()?.name
            ?: lastWorkoutName
            ?: getString(R.string.free_run_name)
        val isEnabled = screenshotManager.toggle(workoutName, workoutStartTimeMs)
        hudDisplayManager.updateCameraButtonState(isEnabled)
    }

    override fun onBluetoothClicked() {
        popupManager.closeAllPopups()
        settingsManager.removeDialog()
        hrSensorManager.removeDialog()
        strydManager.removeDialog()
        strydManager.removeMetricSelector()
        bluetoothSensorDialogManager.toggleDialog()
    }

    override fun onSettingsClicked() {
        popupManager.closeAllPopups()
        hrSensorManager.removeDialog()
        settingsManager.showDialog()
    }

    override fun onCloseClicked() {
        // Save panel visibility state before hiding
        settingsManager.savePanelVisibility(chartManager.isVisible, workoutPanelManager.isVisible)
        // Hide all panels and HUD
        chartManager.hideChart()
        workoutPanelManager.hidePanel()
        hideHud()
    }

    // ==================== WorkoutPanelManager.Listener ====================

    override fun onNextStepClicked() {
        workoutEngineManager.skipToNextStep()
    }

    override fun onPrevStepClicked() {
        workoutEngineManager.skipToPreviousStep()
    }

    override fun onResetToStepClicked() {
        val engineState = workoutEngineManager.getState()
        if (engineState is WorkoutExecutionState.Paused) {
            // When paused, this button acts as Resume — apply effective (adjusted) targets
            workoutEngineManager.resumeWorkoutWithTargets()
        } else {
            // When running, reset to step's base targets (undo manual adjustments)
            workoutEngineManager.applyCurrentStepTargets()
        }
    }

    // ==================== WorkoutEngineManager.Listener ====================

    override fun onWorkoutLoaded(workout: Workout, steps: List<ExecutionStep>) {
        // Note: Don't clear data here - startNewRun() handles saving/clearing before load
        workoutPanelManager.showPanel()
        chartManager.showChart()

        workoutPanelManager.setWorkoutInfo(workout, steps, workoutEngineManager.getPhaseCounts())

        val segments = workoutEngineManager.convertToPlannedSegments(steps)
        chartManager.setPlannedSegments(segments)
    }

    override fun onWorkoutStateChanged(executionState: WorkoutExecutionState) {
        mainHandler.post {
            workoutPanelManager.updateState(executionState)

            when (executionState) {
                is WorkoutExecutionState.Idle -> {
                    workoutPanelManager.stopRefresh()
                    // Only clear planned segments if no workout is loaded
                    // (workout might be loaded but not yet started, or reset before loading new one)
                    if (!workoutEngineManager.isWorkoutLoaded()) {
                        chartManager.clearPlannedSegments()
                    }
                }
                is WorkoutExecutionState.Running -> {
                    // Only auto-show panel if HUD is visible (respect user's hide action)
                    if (!workoutPanelManager.isVisible && state.isHudVisible.get()) {
                        workoutPanelManager.showPanel()
                    }
                    // Keep chart timing/future targets in sync with actual execution time
                    chartManager.setAdjustmentCoefficients(
                        currentStepIndex = executionState.currentStepIndex,
                        speedCoeff = workoutEngineManager.getSpeedAdjustmentCoefficient(),
                        inclineCoeff = workoutEngineManager.getInclineAdjustmentCoefficient(),
                        stepElapsedMs = executionState.stepElapsedMs,
                        workoutElapsedMs = executionState.workoutElapsedMs,
                        perStepCoefficients = workoutEngineManager.getPerStepCoefficients()
                    )
                    workoutPanelManager.startRefresh()
                }
                is WorkoutExecutionState.Paused -> {
                    // Update chart step index (Prev/Next can change steps while paused)
                    chartManager.setAdjustmentCoefficients(
                        currentStepIndex = executionState.currentStepIndex,
                        speedCoeff = workoutEngineManager.getSpeedAdjustmentCoefficient(),
                        inclineCoeff = workoutEngineManager.getInclineAdjustmentCoefficient(),
                        stepElapsedMs = executionState.stepElapsedMs,
                        workoutElapsedMs = executionState.workoutElapsedMs,
                        perStepCoefficients = workoutEngineManager.getPerStepCoefficients()
                    )
                    workoutPanelManager.stopRefresh()
                }
                is WorkoutExecutionState.Completed -> {
                    // Workout was stopped (by user double-stop or programmatic stop)
                    // FIT saving is handled in handleTreadmillStopped, not here
                    workoutPanelManager.stopRefresh()
                }
            }
        }
    }

    override fun onWorkoutEvent(event: WorkoutEvent) {
        when (event) {
            is WorkoutEvent.StepStarted -> {
                // Persist immediately on step transition
                persistCurrentRunState()
                // Take screenshot on step start (captures each transition)
                screenshotManager.takeScreenshotIfEnabled("step_started")
            }
            is WorkoutEvent.StepCompleted -> {
                // Persist immediately on step completion
                persistCurrentRunState()
            }
            is WorkoutEvent.WorkoutPlanFinished -> {
                // All planned steps done, now in auto-cooldown
                mainHandler.post {
                    Toast.makeText(this@HUDService, getString(R.string.workout_plan_complete), Toast.LENGTH_LONG).show()
                }
                Log.d(TAG, "Workout plan finished: ${event.stepsCompleted} steps, entering auto-cooldown")
                // Persist the state
                persistCurrentRunState()
            }
            is WorkoutEvent.WorkoutCompleted -> {
                // User stopped the workout (via double-stop)
                Log.d(TAG, "Workout stopped: ${event.summary.workoutName}")
                // Keep screenshot enabled - user may want to capture results
            }
            is WorkoutEvent.Warning -> {
                mainHandler.post {
                    Toast.makeText(this@HUDService, event.message, Toast.LENGTH_SHORT).show()
                }
            }
            is WorkoutEvent.Error -> {
                mainHandler.post {
                    Toast.makeText(this@HUDService, event.message, Toast.LENGTH_LONG).show()
                }
            }
            else -> { /* Other events logged by WorkoutEngineManager */ }
        }
    }

    override fun onSetTreadmillSpeed(adjustedKph: Double) {
        telemetryManager.setTreadmillSpeed(adjustedKph)
    }

    override fun onSetTreadmillIncline(percent: Double) {
        telemetryManager.setTreadmillIncline(percent)
    }

    // ==================== StrydManager.Listener ====================

    override fun onStrydConnected(deviceName: String) {
        Log.d(TAG, "Stryd connected: $deviceName")
        hudDisplayManager.updateFootPod(strydManager.getSelectedMetric(), true)
        bluetoothSensorDialogManager.updateStatus()
    }

    override fun onStrydDisconnected() {
        Log.d(TAG, "Stryd disconnected")
        hudDisplayManager.updateFootPod(strydManager.getSelectedMetric(), false)
        bluetoothSensorDialogManager.updateStatus()
    }

    override fun onStrydData(power: Double, cadence: Int) {
        // State is already updated by StrydManager - update display immediately
        hudDisplayManager.updateFootPod(strydManager.getSelectedMetric(), true)
        // Feed power to workout engine for power-based auto-adjustments
        workoutEngineManager.onPowerUpdate(state.currentPowerWatts)
    }

    // ==================== HrSensorManager.Listener ====================

    override fun onHrSensorConnected(deviceName: String) {
        Log.d(TAG, "HR sensor connected: $deviceName")
        hudDisplayManager.updateHrSensorStatus(true)
        bluetoothSensorDialogManager.updateStatus()
    }

    override fun onHrSensorDisconnected() {
        Log.d(TAG, "HR sensor disconnected")
        hudDisplayManager.updateHrSensorStatus(false)
        bluetoothSensorDialogManager.updateStatus()
    }

    override fun onHeartRateUpdate(bpm: Int) {
        // State is already updated by HrSensorManager, but we need to:
        // 1. Update the HUD display
        hudDisplayManager.updateHeartRate(bpm.toDouble())
        // 2. Feed HR updates to workout engine for HR-based adjustments
        workoutEngineManager.onHeartRateUpdate(bpm.toDouble())
    }

    // ==================== Public API (for WorkoutRecorder delegation) ====================

    fun startRecording() = workoutRecorder.startRecording()
    fun stopRecording() = workoutRecorder.stopRecording()
    fun getWorkoutData(): List<WorkoutDataPoint> = workoutRecorder.getWorkoutData()
    fun clearWorkoutData() = workoutRecorder.clearData()

    /**
     * Returns the heart rate zone (1-5) for a given BPM.
     */
    fun getHeartRateZone(bpm: Double): Int {
        return HeartRateZones.getZone(bpm, state.hrZone2Start, state.hrZone3Start, state.hrZone4Start, state.hrZone5Start)
    }

    /**
     * Returns the color resource ID for a given heart rate zone.
     */
    fun getHeartRateZoneColor(zone: Int): Int {
        return HeartRateZones.getZoneColorResId(zone)
    }

    // ==================== DirCon Server Listener ====================

    override fun onClientConnected(clientAddress: String) {
        Log.i(TAG, "DirCon client connected: $clientAddress")
    }

    override fun onClientDisconnected(clientAddress: String) {
        Log.i(TAG, "DirCon client disconnected: $clientAddress")
    }

    override fun onControlRequested(): Boolean {
        // Allow control if treadmill is connected
        return state.treadmillWorkoutState != null
    }

    override fun onSetTargetSpeed(speedKph: Double) {
        // Reject speeds outside treadmill's valid range (protects against buggy FTMS clients)
        if (speedKph < state.minSpeedKph || speedKph > state.maxSpeedKph) {
            Log.e(TAG, "FTMS: REJECTED invalid speed $speedKph km/h (valid range: ${state.minSpeedKph}-${state.maxSpeedKph})")
            return
        }
        Log.d(TAG, "FTMS: Set speed to $speedKph km/h")
        telemetryManager.setTreadmillSpeed(speedKph)
    }

    override fun onSetTargetIncline(inclinePercent: Double) {
        // Reject inclines outside treadmill's valid range (protects against buggy FTMS clients)
        if (inclinePercent < state.minInclinePercent || inclinePercent > state.maxInclinePercent) {
            Log.e(TAG, "FTMS: REJECTED invalid incline $inclinePercent% (valid range: ${state.minInclinePercent}-${state.maxInclinePercent})")
            return
        }
        Log.d(TAG, "FTMS: Set incline to $inclinePercent%")
        telemetryManager.setTreadmillIncline(inclinePercent)
    }

    override fun onStartResume() {
        Log.d(TAG, "DirCon: Start/resume requested")
        serviceScope.launch(Dispatchers.IO) {
            telemetryManager.ensureTreadmillRunning()
        }
    }

    override fun onStopPause(stop: Boolean) {
        Log.d(TAG, "FTMS: Stop/pause requested (stop=$stop)")
        if (stop) {
            // Double-stop logic: first stop pauses, second stop ends workout
            if (state.treadmillWorkoutState == com.ifit.glassos.workout.WorkoutState.WORKOUT_STATE_PAUSED) {
                Log.d(TAG, "FTMS: Already paused, stopping workout")
                telemetryManager.stopTreadmill()
            } else {
                Log.d(TAG, "FTMS: Pausing treadmill")
                telemetryManager.pauseTreadmill()
            }
        }
    }

    override fun onSimulationParameters(gradePercent: Double, windSpeedMps: Double, crr: Double, cw: Double) {
        // Zwift sends virtual terrain grade - use it to control treadmill incline!
        Log.d(TAG, "DirCon: Simulation params - grade=$gradePercent%, wind=$windSpeedMps m/s")

        // Clamp grade to treadmill's supported range
        val minIncline = state.minInclinePercent
        val maxIncline = state.maxInclinePercent
        val clampedGrade = gradePercent.coerceIn(minIncline, maxIncline)

        if (clampedGrade != gradePercent) {
            Log.d(TAG, "DirCon: Grade $gradePercent% clamped to $clampedGrade% (range: $minIncline-$maxIncline)")
        }

        // Set treadmill incline to match virtual terrain
        telemetryManager.setTreadmillIncline(clampedGrade)
    }

    override fun getCurrentSpeedKph(): Double {
        // Return adjusted speed (what user perceives)
        return state.currentSpeedKph * state.paceCoefficient
    }

    override fun getCurrentInclinePercent(): Double {
        // Clamp to 0% minimum for FTMS - apps like Kinni don't support negative incline
        return state.currentInclinePercent.coerceAtLeast(0.0)
    }

    override fun getTotalDistanceMeters(): Double {
        val data = workoutRecorder.getWorkoutData()
        return if (data.isNotEmpty()) data.last().distanceKm * 1000.0 else 0.0
    }

    override fun getElapsedTimeSeconds(): Int {
        // Use treadmill's elapsed time directly for FTMS
        // This is updated by TelemetryManager from GlassOS telemetry
        return state.currentElapsedSeconds
    }

    override fun getCurrentHeartRateBpm(): Int {
        return state.currentHeartRateBpm.toInt()
    }

    override fun isTreadmillRunning(): Boolean {
        return state.treadmillWorkoutState == com.ifit.glassos.workout.WorkoutState.WORKOUT_STATE_RUNNING
    }

    override fun getSpeedRange(): Pair<Double, Double> {
        return Pair(state.minSpeedKph, state.maxSpeedKph)
    }

    override fun getInclineRange(): Pair<Double, Double> {
        return Pair(state.minInclinePercent, state.maxInclinePercent)
    }

    override fun getAverageSpeedKph(): Double {
        val data = workoutRecorder.getWorkoutData()
        if (data.isEmpty()) return 0.0
        val avgSpeed = data.map { it.speedKph }.average()
        return avgSpeed * state.paceCoefficient  // Apply pace coefficient for adjusted speed
    }

    override fun getPositiveElevationGainMeters(): Double {
        // TODO: Implement elevation tracking if needed
        return 0.0
    }

    override fun getNegativeElevationGainMeters(): Double {
        // TODO: Implement elevation tracking if needed
        return 0.0
    }

    override fun getTotalCaloriesKcal(): Double {
        val data = workoutRecorder.getWorkoutData()
        return if (data.isNotEmpty()) data.last().caloriesKcal else 0.0
    }

    override fun getCaloriesPerHourKcal(): Double {
        val data = workoutRecorder.getWorkoutData()
        if (data.size < 2) return 0.0
        val elapsedSeconds = data.last().timestampMs - data.first().timestampMs
        if (elapsedSeconds <= 0) return 0.0
        val elapsedHours = elapsedSeconds / 1000.0 / 3600.0
        val totalCalories = data.last().caloriesKcal
        return if (elapsedHours > 0) totalCalories / elapsedHours else 0.0
    }

    /**
     * Start the DirCon server for Zwift connectivity if enabled in settings.
     */
    fun startDirConServer(): Boolean {
        if (!state.ftmsDirConReadEnabled) {
            Log.d(TAG, "DirCon server not started - disabled in settings")
            return false
        }
        if (dirConServer != null) {
            Log.w(TAG, "DirCon server already running")
            return true
        }

        // Use custom device name or default (treadmill name + " DirCon")
        val deviceName = state.ftmsDirConDeviceName.ifEmpty {
            val baseName = state.treadmillName.ifEmpty { getString(R.string.ftms_default_device_name) }
            "$baseName DirCon"
        }

        dirConServer = io.github.avikulin.thud.service.dircon.DirConServer(
            this, this, deviceName, state.ftmsDirConControlEnabled
        )
        val success = dirConServer?.start() ?: false
        if (success) {
            val ip = dirConServer?.getWifiIpAddress()
            Log.i(TAG, "DirCon server started as '$deviceName'. WiFi IP: $ip, Control: ${state.ftmsDirConControlEnabled}")
        }
        return success
    }

    /**
     * Stop the DirCon server.
     */
    fun stopDirConServer() {
        dirConServer?.stop()
        dirConServer = null
        Log.i(TAG, "DirCon server stopped")
    }

    /**
     * Check if DirCon server is running.
     */
    fun isDirConServerRunning(): Boolean = dirConServer?.isRunning() ?: false

    /**
     * Get DirCon server WiFi IP address for display.
     */
    fun getDirConWifiIp(): String? = dirConServer?.getWifiIpAddress()

    /**
     * Start the BLE FTMS server for Bluetooth fitness app connectivity if enabled in settings.
     */
    fun startBleFtmsServer(): Boolean {
        if (!state.ftmsBleReadEnabled) {
            Log.d(TAG, "BLE FTMS server not started - disabled in settings")
            return false
        }
        if (bleFtmsServer != null) {
            Log.w(TAG, "BLE FTMS server already running")
            return true
        }

        // Use custom device name or default (treadmill name + " BLE")
        val deviceName = state.ftmsBleDeviceName.ifEmpty {
            val baseName = state.treadmillName.ifEmpty { getString(R.string.ftms_default_device_name) }
            "$baseName BLE"
        }

        bleFtmsServer = io.github.avikulin.thud.service.ble.BleFtmsServer(
            this, this, deviceName, state.ftmsBleControlEnabled
        )
        val success = bleFtmsServer?.start() ?: false
        if (success) {
            Log.i(TAG, "BLE FTMS server started as '$deviceName', Control: ${state.ftmsBleControlEnabled}")
        } else {
            Log.e(TAG, "Failed to start BLE FTMS server")
            bleFtmsServer = null
        }
        return success
    }

    /**
     * Stop the BLE FTMS server.
     */
    fun stopBleFtmsServer() {
        bleFtmsServer?.stop()
        bleFtmsServer = null
        Log.i(TAG, "BLE FTMS server stopped")
    }

    /**
     * Check if BLE FTMS server is running.
     */
    fun isBleFtmsServerRunning(): Boolean = bleFtmsServer?.isRunning() ?: false

    // ==================== Service Lifecycle ====================

    override fun onDestroy() {
        state.isRunning = false
        workoutRecorder.stopRecording()

        // Stop persistence and persist final state if there's data
        stopPersistence()
        if (workoutRecorder.getDataPointCount() > 0 && !workoutDataExported) {
            persistCurrentRunState()
        }

        // Cancel coroutine scope
        serviceScope.cancel()

        // Stop all handlers
        mainHandler.removeCallbacksAndMessages(null)
        persistenceHandler.removeCallbacksAndMessages(null)

        // Unregister screen state receiver
        if (screenReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenReceiverRegistered = false
        }

        // Clean up all managers and dialogs
        dismissFreeRunDialog()
        dismissResumeRunDialog()
        dismissCertificateErrorDialog()
        dismissGarminLoginOverlay(ticket = null)
        screenshotManager.cleanup()
        workoutPanelManager.cleanup()
        chartManager.cleanup()
        popupManager.cleanup()
        settingsManager.removeDialog()
        hrSensorManager.cleanup()
        strydManager.cleanup()
        bluetoothSensorDialogManager.removeDialog()
        hudDisplayManager.cleanup()
        stopDirConServer()
        stopBleFtmsServer()

        // Null all listener references to break circular references and prevent memory leaks
        telemetryManager.listener = null
        settingsManager.listener = null
        hudDisplayManager.listener = null
        workoutPanelManager.listener = null
        workoutEngineManager.listener = null
        hrSensorManager.listener = null
        strydManager.listener = null
        workoutRecorder.onMetricsUpdated = null

        // Disconnect gRPC in background (shutdownScope survives serviceScope cancellation)
        shutdownScope.launch {
            withTimeoutOrNull(5000) { telemetryManager.disconnect() }
        }.invokeOnCompletion { shutdownScope.cancel() }

        Log.d(TAG, "Service destroyed. Workout data points: ${workoutRecorder.getDataPointCount()}")
        super.onDestroy()
    }
}
