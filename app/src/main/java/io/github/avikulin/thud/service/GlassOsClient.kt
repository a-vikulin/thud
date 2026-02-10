package io.github.avikulin.thud.service

import android.content.Context
import android.util.Log
import com.ifit.glassos.activitylog.ActivityLogMetadata
import com.ifit.glassos.activitylog.ActivityLogOrigin
import com.ifit.glassos.activitylog.ActivityLogType
import com.ifit.glassos.console.*
import com.ifit.glassos.console.idlelockout.*
import com.ifit.glassos.console.sleep.*
import com.ifit.glassos.util.BooleanResponse
import com.ifit.glassos.util.Empty
import com.ifit.glassos.workout.*
import com.ifit.glassos.workout.data.ItemType
import com.ifit.glassos.workout.data.WorkoutSegmentDescriptor
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Exception thrown when mTLS certificates are not found on the device.
 */
class CertificatesNotFoundError(
    val missingFiles: List<String>,
    val certsDirectory: String
) : Exception("mTLS certificates not found in $certsDirectory")

/**
 * gRPC client for iFit GlassOS Platform Service.
 * Connects to the local gRPC server running on port 54321.
 * Uses mTLS (mutual TLS) authentication with certificates from device storage.
 */
class GlassOsClient(
    private val context: Context,
    private val host: String = "127.0.0.1",
    private val port: Int = 54321
) {
    companion object {
        private const val TAG = "GlassOsClient"
        private const val CERTS_SUBDIR = "certs"
        private const val CA_CERT_FILE = "ca.crt"
        private const val CLIENT_CERT_FILE = "client.crt"
        private const val CLIENT_KEY_FILE = "client.key"

        // Client ID for gRPC authorization - must match the iFit app package name
        private const val CLIENT_ID = "com.ifit.rivendell"

        // Metadata key for client_id header
        private val CLIENT_ID_KEY: Metadata.Key<String> =
            Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER)

        fun getCertsDirectory(context: Context): File {
            val externalFilesDir = context.getExternalFilesDir(null)
                ?: throw IllegalStateException("External files directory not available")
            val certsDir = File(externalFilesDir, CERTS_SUBDIR)
            // Create directory if it doesn't exist (simplifies setup - users can just push certs)
            if (!certsDir.exists()) {
                certsDir.mkdirs()
            }
            return certsDir
        }

        fun getMissingCertificates(context: Context): List<String> {
            val certsDir = getCertsDirectory(context)
            val requiredFiles = listOf(CA_CERT_FILE, CLIENT_CERT_FILE, CLIENT_KEY_FILE)
            return requiredFiles.filter { !File(certsDir, it).exists() }
        }

        fun areCertificatesInstalled(context: Context): Boolean = getMissingCertificates(context).isEmpty()
    }

    /**
     * Interceptor that adds client_id metadata header to all gRPC calls.
     * Required for authorization with GlassOS service.
     */
    private inner class ClientIdInterceptor : ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel
        ): ClientCall<ReqT, RespT> {
            return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)
            ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(CLIENT_ID_KEY, CLIENT_ID)
                    Log.d(TAG, "Adding client_id header: $CLIENT_ID for ${method.fullMethodName}")
                    super.start(responseListener, headers)
                }
            }
        }
    }

    private var channel: ManagedChannel? = null

    /**
     * Creates an SSLContext configured for mTLS authentication.
     * Loads CA certificate, client certificate, and private key from device storage.
     * @throws CertificatesNotFoundError if certificates are not installed
     */
    private fun createMtlsContext(): SSLContext {
        // Check for missing certificates
        val missingCerts = getMissingCertificates(context)
        if (missingCerts.isNotEmpty()) {
            val certsDir = getCertsDirectory(context)
            Log.e(TAG, "Missing certificates in ${certsDir.absolutePath}: $missingCerts")
            throw CertificatesNotFoundError(missingCerts, certsDir.absolutePath)
        }

        val certsDir = getCertsDirectory(context)
        val certificateFactory = CertificateFactory.getInstance("X.509")

        // Load CA certificate for trust
        val caCert = FileInputStream(File(certsDir, CA_CERT_FILE)).use { inputStream ->
            certificateFactory.generateCertificate(BufferedInputStream(inputStream)) as X509Certificate
        }
        Log.d(TAG, "Loaded CA cert: ${caCert.subjectDN}")

        // Create TrustManagerFactory with CA cert
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", caCert)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }

        // Load client certificate
        val clientCert = FileInputStream(File(certsDir, CLIENT_CERT_FILE)).use { inputStream ->
            certificateFactory.generateCertificate(BufferedInputStream(inputStream)) as X509Certificate
        }
        Log.d(TAG, "Loaded client cert: ${clientCert.subjectDN}")

        // Load private key
        val privateKey = FileInputStream(File(certsDir, CLIENT_KEY_FILE)).use { inputStream ->
            val keyPem = inputStream.bufferedReader().readText()
            val keyBase64 = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.getDecoder().decode(keyBase64)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            KeyFactory.getInstance("RSA").generatePrivate(keySpec)
        }
        Log.d(TAG, "Loaded private key")

        // Create KeyManagerFactory with client cert and key
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, charArrayOf(), arrayOf(clientCert))
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, charArrayOf())
        }

        // Create SSLContext with both trust and key managers
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
        }
    }

    private var speedStub: SpeedServiceGrpc.SpeedServiceStub? = null
    private var inclineStub: InclineServiceGrpc.InclineServiceStub? = null
    private var elapsedTimeStub: ElapsedTimeServiceGrpc.ElapsedTimeServiceStub? = null
    private var workoutStub: WorkoutServiceGrpc.WorkoutServiceStub? = null

    // Blocking stubs for one-off calls
    private var speedBlockingStub: SpeedServiceGrpc.SpeedServiceBlockingStub? = null
    private var inclineBlockingStub: InclineServiceGrpc.InclineServiceBlockingStub? = null
    private var workoutBlockingStub: WorkoutServiceGrpc.WorkoutServiceBlockingStub? = null
    private var consoleBlockingStub: ConsoleServiceGrpc.ConsoleServiceBlockingStub? = null
    private var programmedWorkoutStub: ProgrammedWorkoutSessionServiceGrpc.ProgrammedWorkoutSessionServiceBlockingStub? = null
    private var sleepStateBlockingStub: SleepStateServiceGrpc.SleepStateServiceBlockingStub? = null
    private var idleLockoutBlockingStub: IdleModeLockoutServiceGrpc.IdleModeLockoutServiceBlockingStub? = null

    // Async stubs for console state monitoring
    private var consoleStub: ConsoleServiceGrpc.ConsoleServiceStub? = null
    private var sleepStateStub: SleepStateServiceGrpc.SleepStateServiceStub? = null
    private var idleLockoutStub: IdleModeLockoutServiceGrpc.IdleModeLockoutServiceStub? = null

    // Async stub for ProgrammedWorkoutSessionService (manual start subscription)
    private var programmedWorkoutAsyncStub: ProgrammedWorkoutSessionServiceGrpc.ProgrammedWorkoutSessionServiceStub? = null

    // Console state tracking
    private var currentConsoleState: ConsoleState = ConsoleState.CONSOLE_STATE_UNKNOWN
    private var currentSleepState: SleepState = SleepState.SLEEP_STATE_UNKNOWN
    private var currentLockoutState: IdleModeLockoutState = IdleModeLockoutState.LOCK_STATE_UNKNOWN

    // Cached console info (min/max ranges)
    var minSpeedKph: Double = 1.6  // Default ~1 mph
        private set
    var maxSpeedKph: Double = 20.0
        private set
    var minInclinePercent: Double = -6.0
        private set
    var maxInclinePercent: Double = 40.0
        private set
    var treadmillName: String = ""  // From ConsoleInfo.name
        private set

    interface TelemetryListener {
        fun onSpeedUpdate(kph: Double, avgKph: Double, maxKph: Double)
        fun onInclineUpdate(percent: Double, avgPercent: Double, maxPercent: Double)
        fun onElapsedTimeUpdate(seconds: Int)
        fun onWorkoutStateChanged(state: WorkoutState)
        fun onConnectionStateChanged(connected: Boolean, message: String)
        fun onError(error: String)
        fun onManualStartRequested() {}  // Physical Start button pressed
    }

    private var listener: TelemetryListener? = null

    // Cached last values to avoid flooding with unchanged updates
    private var lastSpeedKph: Double = Double.NaN
    private var lastInclinePercent: Double = Double.NaN
    private var lastElapsedSeconds: Int = -1
    private var lastWorkoutState: WorkoutState = WorkoutState.WORKOUT_STATE_UNKNOWN

    fun setListener(listener: TelemetryListener) {
        this.listener = listener
    }

    /**
     * Connect to GlassOS service.
     * @throws CertificatesNotFoundError if mTLS certificates are not installed
     */
    fun connect(): Boolean {
        return try {
            Log.d(TAG, "Connecting to GlassOS at $host:$port with mTLS")

            // Create SSLContext with mTLS certificates
            val sslContext = createMtlsContext()

            // Use OkHttp channel with mTLS and client_id interceptor
            channel = OkHttpChannelBuilder
                .forAddress(host, port)
                .sslSocketFactory(sslContext.socketFactory)
                .hostnameVerifier { _, _ -> true } // Allow localhost
                .intercept(ClientIdInterceptor())
                .build()

            // Create async stubs for streaming
            speedStub = SpeedServiceGrpc.newStub(channel)
            inclineStub = InclineServiceGrpc.newStub(channel)
            elapsedTimeStub = ElapsedTimeServiceGrpc.newStub(channel)
            workoutStub = WorkoutServiceGrpc.newStub(channel)

            // Create blocking stubs for control commands
            speedBlockingStub = SpeedServiceGrpc.newBlockingStub(channel)
            inclineBlockingStub = InclineServiceGrpc.newBlockingStub(channel)
            workoutBlockingStub = WorkoutServiceGrpc.newBlockingStub(channel)
            consoleBlockingStub = ConsoleServiceGrpc.newBlockingStub(channel)
            programmedWorkoutStub = ProgrammedWorkoutSessionServiceGrpc.newBlockingStub(channel)
            sleepStateBlockingStub = SleepStateServiceGrpc.newBlockingStub(channel)
            idleLockoutBlockingStub = IdleModeLockoutServiceGrpc.newBlockingStub(channel)

            // Create async stubs for console state monitoring
            consoleStub = ConsoleServiceGrpc.newStub(channel)
            sleepStateStub = SleepStateServiceGrpc.newStub(channel)
            idleLockoutStub = IdleModeLockoutServiceGrpc.newStub(channel)

            // Create async stub for ProgrammedWorkoutSessionService (manual start subscription)
            programmedWorkoutAsyncStub = ProgrammedWorkoutSessionServiceGrpc.newStub(channel)

            // Connect to the console (critical for proper operation)
            connectToConsole()

            // Query console info for min/max ranges
            queryConsoleInfo()

            // Ensure console is awake and unlocked
            ensureConsoleReady()

            listener?.onConnectionStateChanged(true, "Connected to GlassOS")
            Log.d(TAG, "Connected successfully")
            true
        } catch (e: CertificatesNotFoundError) {
            // Re-throw certificate errors so TelemetryManager can handle them
            Log.e(TAG, "Certificates not found: ${e.missingFiles}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            listener?.onConnectionStateChanged(false, "Connection failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        try {
            channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
            channel = null
            listener?.onConnectionStateChanged(false, "Disconnected")
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
    }

    fun isConnected(): Boolean = channel?.isShutdown == false

    // ==================== Telemetry Subscriptions ====================

    fun subscribeToSpeed() {
        val empty = Empty.getDefaultInstance()
        speedStub?.speedSubscription(empty, object : StreamObserver<SpeedMetric> {
            override fun onNext(value: SpeedMetric) {
                if (value.lastKph != lastSpeedKph) {
                    lastSpeedKph = value.lastKph
                    Log.d(TAG, "Speed update: ${value.lastKph} KPH")
                    listener?.onSpeedUpdate(value.lastKph, value.avgKph, value.maxKph)
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Speed subscription error: ${t.message}")
                listener?.onError("Speed: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Speed subscription completed")
            }
        })
    }

    fun subscribeToIncline() {
        val empty = Empty.getDefaultInstance()
        inclineStub?.inclineSubscription(empty, object : StreamObserver<InclineMetric> {
            override fun onNext(value: InclineMetric) {
                if (value.lastInclinePercent != lastInclinePercent) {
                    lastInclinePercent = value.lastInclinePercent
                    Log.d(TAG, "Incline update: ${value.lastInclinePercent}%")
                    listener?.onInclineUpdate(
                        value.lastInclinePercent,
                        value.avgInclinePercent,
                        value.maxInclinePercent
                    )
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Incline subscription error: ${t.message}")
                listener?.onError("Incline: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Incline subscription completed")
            }
        })
    }

    fun subscribeToElapsedTime() {
        val empty = Empty.getDefaultInstance()
        elapsedTimeStub?.elapsedTimeSubscription(empty, object : StreamObserver<ElapsedTimeMetric> {
            override fun onNext(value: ElapsedTimeMetric) {
                if (value.elapsedSeconds != lastElapsedSeconds) {
                    lastElapsedSeconds = value.elapsedSeconds
                    Log.d(TAG, "Elapsed time update: ${value.elapsedSeconds}s")
                    listener?.onElapsedTimeUpdate(value.elapsedSeconds)
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Elapsed time subscription error: ${t.message}")
                listener?.onError("ElapsedTime: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Elapsed time subscription completed")
            }
        })
    }

    fun subscribeToWorkoutState() {
        val empty = Empty.getDefaultInstance()
        workoutStub?.workoutStateChanged(empty, object : StreamObserver<WorkoutStateMessage> {
            override fun onNext(value: WorkoutStateMessage) {
                val newState = value.workoutState
                // Allow IDLE state through even if duplicate (handles Stop pressed while stopped)
                val isStopWhileStopped = newState == WorkoutState.WORKOUT_STATE_IDLE &&
                        lastWorkoutState == WorkoutState.WORKOUT_STATE_IDLE
                if (newState != lastWorkoutState || isStopWhileStopped) {
                    lastWorkoutState = newState
                    Log.d(TAG, "Workout state changed: $newState${if (isStopWhileStopped) " (stop while stopped)" else ""}")
                    listener?.onWorkoutStateChanged(newState)
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Workout state subscription error: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Workout state subscription completed")
            }
        })
    }

    /**
     * Subscribe to manual start requests (physical Start button presses).
     * When the physical Start button is pressed, GlassOS sends a notification through this subscription.
     * We respond by calling Resume() if paused, or Start() to begin the preloaded workout.
     */
    fun subscribeToManualStart() {
        val empty = Empty.getDefaultInstance()
        programmedWorkoutAsyncStub?.manualStartRequestedSubscription(empty, object : StreamObserver<BooleanResponse> {
            override fun onNext(value: BooleanResponse) {
                if (value.value) {
                    Log.d(TAG, "Manual start requested (physical Start button pressed)")
                    // Notify the listener
                    listener?.onManualStartRequested()
                    try {
                        // Check treadmill state: resume if paused, start if idle
                        val currentState = workoutBlockingStub?.getWorkoutState(empty)?.workoutState
                        if (currentState == WorkoutState.WORKOUT_STATE_PAUSED) {
                            Log.d(TAG, "Manual start: treadmill paused, resuming...")
                            val result = workoutBlockingStub?.resume(empty)
                            val success = result?.hasSuccess() == true && result.success
                            Log.d(TAG, "Manual start: resume result=$success")
                        } else {
                            val startResponse = programmedWorkoutStub?.start(empty)
                            if (startResponse?.hasSuccess() == true && startResponse.success) {
                                Log.d(TAG, "Manual start: workout started successfully")
                            } else {
                                Log.e(TAG, "Manual start: failed to start workout")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Manual start: error: ${e.message}")
                    }
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Manual start subscription error: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Manual start subscription completed")
            }
        })
    }

    fun subscribeToAll() {
        subscribeToSpeed()
        subscribeToIncline()
        subscribeToElapsedTime()
        subscribeToWorkoutState()
        subscribeToManualStart()
    }

    // ==================== Control Commands ====================

    fun setSpeed(kph: Double): Boolean {
        return try {
            val request = SpeedRequest.newBuilder().setKph(kph).build()
            val result = speedBlockingStub?.setSpeed(request)
            val success = result?.hasSuccess() == true && result.success
            Log.d(TAG, "SetSpeed($kph): $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "SetSpeed failed: ${e.message}")
            listener?.onError("SetSpeed: ${e.message}")
            false
        }
    }

    fun setIncline(percent: Double): Boolean {
        return try {
            val request = InclineRequest.newBuilder().setPercent(percent).build()
            val result = inclineBlockingStub?.setIncline(request)
            val success = result?.hasSuccess() == true && result.success
            Log.d(TAG, "SetIncline($percent): $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "SetIncline failed: ${e.message}")
            listener?.onError("SetIncline: ${e.message}")
            false
        }
    }

    // ==================== Workout Control ====================

    fun startWorkout(): String? {
        return try {
            val empty = Empty.getDefaultInstance()
            val response = workoutBlockingStub?.startNewWorkout(empty)
            if (response?.result?.hasSuccess() == true && response.result.success) {
                Log.d(TAG, "StartWorkout: success, workoutID=${response.workoutID}")
                response.workoutID
            } else {
                Log.e(TAG, "StartWorkout: failed")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "StartWorkout failed: ${e.message}")
            listener?.onError("StartWorkout: ${e.message}")
            null
        }
    }

    /**
     * Quick start workout - bypasses the 3-minute warmup countdown.
     * Uses ProgrammedWorkoutSessionService to add only a MAIN segment (no WARM_UP).
     * This mimics the behavior of pressing the physical Start button.
     */
    fun quickStartWorkout(): Boolean {
        return try {
            if (!preloadFreeRunWorkout()) {
                return false
            }

            // Now start the workout
            val empty = Empty.getDefaultInstance()
            val startResponse = programmedWorkoutStub?.start(empty)
            if (startResponse?.hasSuccess() == true && startResponse.success) {
                Log.d(TAG, "QuickStartWorkout: started successfully")
                return true
            } else {
                Log.e(TAG, "QuickStartWorkout: start failed")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "QuickStartWorkout failed: ${e.message}")
            listener?.onError("QuickStartWorkout: ${e.message}")
            false
        }
    }

    /**
     * Pre-load a free run workout without starting it.
     * This enables the physical Start button to work.
     */
    fun preloadFreeRunWorkout(): Boolean {
        return try {
            val empty = Empty.getDefaultInstance()

            // First clear any existing workout segments
            programmedWorkoutStub?.clearRemainingWorkoutSegments(empty)

            // Create workout metadata with a random ID
            val workoutId = java.util.UUID.randomUUID().toString()
            val metadata = ActivityLogMetadata.newBuilder()
                .setWorkoutId(workoutId)
                .setShouldUploadLog(false)
                .setOrigin(ActivityLogOrigin.ACT_LOG_ORIGIN_TREADMILL)
                .setType(ActivityLogType.ACT_LOG_TYPE_RUN)
                .build()

            // Create a MAIN segment only (no WARM_UP or COOL_DOWN)
            // Duration of 43200 seconds = 12 hours (effectively unlimited manual workout)
            val mainSegment = WorkoutSegmentDescriptor.newBuilder()
                .setWorkoutMetadata(metadata)
                .setItemType(ItemType.ITEM_TYPE_MAIN)
                .setManualWorkoutLengthSeconds(43200.0)
                .build()

            // Add the segment(s)
            val request = AddAllWorkoutSegmentsRequest.newBuilder()
                .addWorkoutSegments(mainSegment)
                .build()

            val addResponse = programmedWorkoutStub?.addAllWorkoutSegments(request)
            if (addResponse?.hasSuccess() == true && addResponse.success) {
                Log.d(TAG, "PreloadFreeRunWorkout: segments added successfully")
                return true
            } else {
                Log.e(TAG, "PreloadFreeRunWorkout: addAllWorkoutSegments failed")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "PreloadFreeRunWorkout failed: ${e.message}")
            false
        }
    }

    fun stopWorkout(): Boolean {
        return try {
            val empty = Empty.getDefaultInstance()
            val result = workoutBlockingStub?.stop(empty)
            val success = result?.hasSuccess() == true && result.success
            Log.d(TAG, "StopWorkout: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "StopWorkout failed: ${e.message}")
            listener?.onError("StopWorkout: ${e.message}")
            false
        }
    }

    fun pauseWorkout(): Boolean {
        return try {
            val empty = Empty.getDefaultInstance()
            val result = workoutBlockingStub?.pause(empty)
            val success = result?.hasSuccess() == true && result.success
            Log.d(TAG, "PauseWorkout: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "PauseWorkout failed: ${e.message}")
            listener?.onError("PauseWorkout: ${e.message}")
            false
        }
    }

    fun resumeWorkout(): Boolean {
        return try {
            val empty = Empty.getDefaultInstance()
            val result = workoutBlockingStub?.resume(empty)
            val success = result?.hasSuccess() == true && result.success
            Log.d(TAG, "ResumeWorkout: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "ResumeWorkout failed: ${e.message}")
            listener?.onError("ResumeWorkout: ${e.message}")
            false
        }
    }

    fun getWorkoutState(): WorkoutState {
        return try {
            val empty = Empty.getDefaultInstance()
            val response = workoutBlockingStub?.getWorkoutState(empty)
            val state = response?.workoutState ?: WorkoutState.WORKOUT_STATE_UNKNOWN
            Log.d(TAG, "GetWorkoutState: $state")
            state
        } catch (e: Exception) {
            Log.e(TAG, "GetWorkoutState failed: ${e.message}")
            WorkoutState.WORKOUT_STATE_UNKNOWN
        }
    }

    fun isWorkoutRunning(): Boolean {
        val state = getWorkoutState()
        return state == WorkoutState.WORKOUT_STATE_RUNNING || state == WorkoutState.WORKOUT_STATE_PAUSED
    }

    // ==================== Console Info ====================

    private fun queryConsoleInfo() {
        try {
            val empty = Empty.getDefaultInstance()
            val consoleInfo = consoleBlockingStub?.getConsole(empty)
            if (consoleInfo != null) {
                minSpeedKph = consoleInfo.minKph
                maxSpeedKph = consoleInfo.maxKph
                minInclinePercent = consoleInfo.minInclinePercent
                maxInclinePercent = consoleInfo.maxInclinePercent
                treadmillName = consoleInfo.name ?: ""

                Log.d(TAG, "Console info: name='$treadmillName', speed=${minSpeedKph}-${maxSpeedKph} kph, " +
                        "incline=${minInclinePercent}-${maxInclinePercent}%")
            }
        } catch (e: Exception) {
            Log.e(TAG, "QueryConsoleInfo failed: ${e.message}")
            // Keep default values
        }
    }

    // ==================== Console Connection Management ====================

    /**
     * Connect to the console via ConsoleService.
     * This is critical - without this call, the console may not respond properly.
     */
    private fun connectToConsole(): Boolean {
        return try {
            val empty = Empty.getDefaultInstance()
            val result = consoleBlockingStub?.connect(empty)

            when {
                result?.hasConsoleState() == true -> {
                    currentConsoleState = result.consoleState
                    Log.d(TAG, "Console connected, state: $currentConsoleState")
                    true
                }
                result?.hasError() == true -> {
                    Log.e(TAG, "Console connect error: ${result.error}")
                    false
                }
                else -> {
                    Log.w(TAG, "Console connect returned unknown result")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ConnectToConsole failed: ${e.message}")
            false
        }
    }

    /**
     * Disconnect from the console (call on cleanup).
     */
    fun disconnectFromConsole() {
        try {
            val empty = Empty.getDefaultInstance()
            consoleBlockingStub?.disconnect(empty)
            Log.d(TAG, "Console disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "DisconnectFromConsole failed: ${e.message}")
        }
    }

    /**
     * Ensure the console is ready for operation (awake and unlocked).
     */
    private fun ensureConsoleReady() {
        // Check and fix sleep state
        checkAndWakeConsole()

        // Check and fix lockout state
        checkAndUnlockConsole()

        // Get current console state
        refreshConsoleState()
    }

    /**
     * Get the current console state.
     */
    fun getConsoleState(): ConsoleState {
        return try {
            val empty = Empty.getDefaultInstance()
            val response = consoleBlockingStub?.getConsoleState(empty)
            val state = response?.consoleState ?: ConsoleState.CONSOLE_STATE_UNKNOWN
            currentConsoleState = state
            Log.d(TAG, "Console state: $state")
            state
        } catch (e: Exception) {
            Log.e(TAG, "GetConsoleState failed: ${e.message}")
            ConsoleState.CONSOLE_STATE_UNKNOWN
        }
    }

    /**
     * Refresh the console state.
     */
    private fun refreshConsoleState() {
        currentConsoleState = getConsoleState()
    }

    /**
     * Subscribe to console state changes.
     */
    fun subscribeToConsoleState() {
        val empty = Empty.getDefaultInstance()
        consoleStub?.consoleStateChanged(empty, object : StreamObserver<ConsoleStateMessage> {
            override fun onNext(value: ConsoleStateMessage) {
                val newState = value.consoleState
                if (newState != currentConsoleState) {
                    Log.d(TAG, "Console state changed: $currentConsoleState -> $newState")
                    currentConsoleState = newState

                    // Auto-handle problematic states
                    when (newState) {
                        ConsoleState.SLEEP -> {
                            Log.w(TAG, "Console went to sleep, waking...")
                            wakeConsole()
                        }
                        ConsoleState.LOCKED -> {
                            Log.w(TAG, "Console locked, unlocking...")
                            unlockConsole()
                        }
                        ConsoleState.ERROR -> {
                            Log.e(TAG, "Console in ERROR state!")
                            listener?.onError("Console error state")
                        }
                        else -> {}
                    }
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Console state subscription error: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Console state subscription completed")
            }
        })
    }

    // ==================== Sleep State Management ====================

    /**
     * Get the current sleep state.
     */
    fun getSleepState(): SleepState {
        return try {
            val empty = Empty.getDefaultInstance()
            val result = sleepStateBlockingStub?.getSleepState(empty)
            when {
                result?.hasSleepState() == true -> {
                    currentSleepState = result.sleepState
                    Log.d(TAG, "Sleep state: $currentSleepState")
                    currentSleepState
                }
                result?.hasError() == true -> {
                    Log.e(TAG, "GetSleepState error: ${result.error}")
                    SleepState.SLEEP_STATE_UNKNOWN
                }
                else -> SleepState.SLEEP_STATE_UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "GetSleepState failed: ${e.message}")
            SleepState.SLEEP_STATE_UNKNOWN
        }
    }

    /**
     * Wake the console from sleep.
     */
    fun wakeConsole(): Boolean {
        return try {
            val request = SleepStateMessage.newBuilder()
                .setState(SleepState.SLEEP_STATE_AWAKE)
                .build()
            val result = sleepStateBlockingStub?.setSleepState(request)
            when {
                result?.hasSleepState() == true -> {
                    currentSleepState = result.sleepState
                    val success = result.sleepState == SleepState.SLEEP_STATE_AWAKE
                    Log.d(TAG, "WakeConsole: state=$currentSleepState, success=$success")
                    success
                }
                result?.hasError() == true -> {
                    Log.e(TAG, "WakeConsole error: ${result.error}")
                    false
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeConsole failed: ${e.message}")
            false
        }
    }

    /**
     * Check sleep state and wake if sleeping.
     */
    private fun checkAndWakeConsole() {
        val sleepState = getSleepState()
        if (sleepState == SleepState.SLEEP_STATE_SLEEPING ||
            sleepState == SleepState.SLEEP_STATE_INITIATE_SLEEP) {
            Log.w(TAG, "Console is sleeping, waking up...")
            wakeConsole()
        }
    }

    /**
     * Subscribe to sleep state changes.
     */
    fun subscribeToSleepState() {
        val empty = Empty.getDefaultInstance()
        sleepStateStub?.sleepStateSubscription(empty, object : StreamObserver<SleepStateMessage> {
            override fun onNext(value: SleepStateMessage) {
                val newState = value.state
                if (newState != currentSleepState) {
                    Log.d(TAG, "Sleep state changed: $currentSleepState -> $newState")
                    currentSleepState = newState

                    // Auto-wake if console falls asleep
                    if (newState == SleepState.SLEEP_STATE_SLEEPING ||
                        newState == SleepState.SLEEP_STATE_INITIATE_SLEEP) {
                        Log.w(TAG, "Console falling asleep, waking...")
                        wakeConsole()
                    }
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Sleep state subscription error: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Sleep state subscription completed")
            }
        })
    }

    // ==================== Idle Lockout Management ====================

    /**
     * Get the current lockout state.
     */
    fun getLockoutState(): IdleModeLockoutState {
        return try {
            val empty = Empty.getDefaultInstance()
            val result = idleLockoutBlockingStub?.getIdleModeLockout(empty)
            when {
                result?.hasIdleModeLockoutState() == true -> {
                    currentLockoutState = result.idleModeLockoutState
                    Log.d(TAG, "Lockout state: $currentLockoutState")
                    currentLockoutState
                }
                result?.hasError() == true -> {
                    Log.e(TAG, "GetLockoutState error: ${result.error}")
                    IdleModeLockoutState.LOCK_STATE_UNKNOWN
                }
                else -> IdleModeLockoutState.LOCK_STATE_UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "GetLockoutState failed: ${e.message}")
            IdleModeLockoutState.LOCK_STATE_UNKNOWN
        }
    }

    /**
     * Unlock the console.
     */
    fun unlockConsole(): Boolean {
        return try {
            val request = IdleModeLockoutMessage.newBuilder()
                .setState(IdleModeLockoutState.LOCK_STATE_UNLOCKED)
                .build()
            val result = idleLockoutBlockingStub?.setIdleModeLockout(request)
            when {
                result?.hasIdleModeLockoutState() == true -> {
                    currentLockoutState = result.idleModeLockoutState
                    val success = result.idleModeLockoutState == IdleModeLockoutState.LOCK_STATE_UNLOCKED
                    Log.d(TAG, "UnlockConsole: state=$currentLockoutState, success=$success")
                    success
                }
                result?.hasError() == true -> {
                    Log.e(TAG, "UnlockConsole error: ${result.error}")
                    false
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "UnlockConsole failed: ${e.message}")
            false
        }
    }

    /**
     * Check lockout state and unlock if locked.
     */
    private fun checkAndUnlockConsole() {
        val lockoutState = getLockoutState()
        if (lockoutState == IdleModeLockoutState.LOCK_STATE_LOCKED) {
            Log.w(TAG, "Console is locked, unlocking...")
            unlockConsole()
        }
    }

    /**
     * Subscribe to lockout state changes.
     */
    fun subscribeToLockoutState() {
        val empty = Empty.getDefaultInstance()
        idleLockoutStub?.idleModeLockoutSubscription(empty, object : StreamObserver<IdleModeLockoutMessage> {
            override fun onNext(value: IdleModeLockoutMessage) {
                val newState = value.state
                if (newState != currentLockoutState) {
                    Log.d(TAG, "Lockout state changed: $currentLockoutState -> $newState")
                    currentLockoutState = newState

                    // Auto-unlock if console gets locked
                    if (newState == IdleModeLockoutState.LOCK_STATE_LOCKED) {
                        Log.w(TAG, "Console locked, unlocking...")
                        unlockConsole()
                    }
                }
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "Lockout state subscription error: ${t.message}")
            }

            override fun onCompleted() {
                Log.d(TAG, "Lockout state subscription completed")
            }
        })
    }

    /**
     * Subscribe to all console management state changes.
     * Call this after subscribeToAll() to enable auto-management.
     */
    fun subscribeToConsoleManagement() {
        subscribeToConsoleState()
        subscribeToSleepState()
        subscribeToLockoutState()
    }

}
