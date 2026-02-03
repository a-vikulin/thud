package io.github.avikulin.thud.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.*

/**
 * Manages Bluetooth LE connection to Stryd foot pods.
 * Handles scanning, connection, characteristic subscriptions, and data parsing.
 */
class StrydManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val state: ServiceStateHolder
) {
    companion object {
        private const val TAG = "StrydManager"
        private const val SCAN_TIMEOUT_MS = 15000L
        private const val STRYD_NAME_PREFIX = "Stryd"

        // BLE Service UUIDs
        val CYCLING_POWER_SERVICE: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
        val RSC_SERVICE: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        val STRYD_CUSTOM_SERVICE: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        // Characteristic UUIDs
        val POWER_MEASUREMENT: UUID = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
        val RSC_MEASUREMENT: UUID = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /**
     * Callback interface for Stryd data events.
     */
    interface Listener {
        fun onStrydConnected(deviceName: String)
        fun onStrydDisconnected()
        fun onStrydData(power: Double, cadence: Int)
    }

    var listener: Listener? = null

    // BLE components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private var connectedDevice: BluetoothDevice? = null

    // Handler for scan timeout
    private val handler = Handler(Looper.getMainLooper())

    // Discovered devices
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    // Dialog views
    private var dialogView: LinearLayout? = null
    private var deviceListContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var scanProgressBar: ProgressBar? = null

    // Metric selector popup
    private var metricSelectorView: LinearLayout? = null
    private var metricSelectorRefreshRunnable: Runnable? = null
    private val metricValueViews = mutableMapOf<String, TextView>()

    // Pending subscriptions (after service discovery)
    private var pendingSubscriptions = mutableListOf<BluetoothGattCharacteristic>()
    private var currentSubscriptionIndex = 0

    // Timestamp of last power data received (for connection diagnostics)
    @Volatile
    var lastDataTimestampMs: Long = 0L
        private set

    // Battery level (0-100, or -1 if not available)
    @Volatile
    var batteryLevelPercent: Int = -1
        private set

    // Flag to track if we're waiting for initial battery read during connection
    private var pendingInitialBatteryRead = false

    /**
     * Initialize Bluetooth adapter.
     */
    fun initialize(): Boolean {
        val bluetoothManager = service.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return false
        }

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        return bluetoothLeScanner != null
    }

    /**
     * Check if Bluetooth permissions are granted.
     */
    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(service, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(service, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Older Android versions don't need runtime permissions for BLE
        }
    }

    /**
     * Auto-connect to saved foot pods, trying each until one connects.
     */
    fun autoConnect() {
        if (!hasPermissions()) {
            Log.w(TAG, "Missing Bluetooth permissions for auto-connect")
            return
        }

        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        val savedDevices = SavedBluetoothDevices.getByType(prefs, SensorDeviceType.FOOT_POD)

        if (savedDevices.isEmpty() || bluetoothAdapter == null) {
            Log.d(TAG, "No saved foot pods to auto-connect")
            return
        }

        // Try each saved device in order (most recently used first)
        for (saved in savedDevices) {
            Log.d(TAG, "Attempting auto-connect to foot pod ${saved.name} (${saved.mac})")
            try {
                @SuppressLint("MissingPermission")
                val device = bluetoothAdapter?.getRemoteDevice(saved.mac)
                if (device != null) {
                    connectToDevice(device)
                    return // Only try to connect to one device at a time
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-connect to ${saved.mac} failed: ${e.message}")
                // Continue to next device
            }
        }
    }

    /**
     * Show the foot pod connection dialog.
     */
    fun showDialog() {
        if (dialogView != null) {
            removeDialog()
            return
        }

        if (!hasPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }

        createDialog()

        // If already connected, show connected state; otherwise start scanning
        if (state.strydConnected && connectedDevice != null) {
            showConnectedState()
        } else {
            startScan()
        }
    }

    /**
     * Toggle dialog visibility.
     */
    fun toggleDialog() {
        if (dialogView != null) {
            removeDialog()
        } else {
            showDialog()
        }
    }

    /**
     * Create the dialog UI.
     */
    @SuppressLint("MissingPermission")
    private fun createDialog() {
        val resources = service.resources
        val dialogPaddingH = resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal)
        val dialogPaddingV = resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical)
        val dialogWidthFraction = resources.getFloat(R.dimen.dialog_width_fraction)
        val rowSpacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(service, R.color.popup_background))
            setPadding(dialogPaddingH, dialogPaddingV, dialogPaddingH, dialogPaddingV)
        }

        // Title row with close button
        val titleRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(service).apply {
            text = service.getString(R.string.foot_pod_dialog_title)
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_title_text_size))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(title)

        val closeBtn = Button(service).apply {
            text = service.getString(R.string.btn_close)
            setOnClickListener { removeDialog() }
        }
        titleRow.addView(closeBtn)

        container.addView(titleRow)

        // Status text
        statusText = TextView(service).apply {
            text = service.getString(R.string.foot_pod_scanning)
            setTextColor(ContextCompat.getColor(service, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = rowSpacing
            }
        }
        container.addView(statusText)

        // Progress bar for scanning
        scanProgressBar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = rowSpacing / 2
            }
        }
        container.addView(scanProgressBar)

        // Device list container
        deviceListContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = rowSpacing
            }
        }
        container.addView(deviceListContainer)

        dialogView = container

        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        val dialogParams = OverlayHelper.createOverlayParams(dialogWidth)
        windowManager.addView(container, dialogParams)
    }

    /**
     * Show connected state in dialog.
     */
    @SuppressLint("MissingPermission")
    private fun showConnectedState() {
        statusText?.text = service.getString(R.string.foot_pod_connected)
        scanProgressBar?.visibility = View.GONE

        deviceListContainer?.removeAllViews()

        val deviceRow = createDeviceRow(connectedDevice?.name ?: "Stryd", true)
        deviceListContainer?.addView(deviceRow)
    }

    /**
     * Create a device row for the list.
     */
    @SuppressLint("MissingPermission")
    private fun createDeviceRow(name: String, connected: Boolean): LinearLayout {
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = rowSpacing / 2
            }

            val nameText = TextView(service).apply {
                text = name
                setTextColor(ContextCompat.getColor(service, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(nameText)

            val actionBtn = Button(service).apply {
                text = if (connected) {
                    service.getString(R.string.btn_disconnect)
                } else {
                    service.getString(R.string.btn_connect)
                }
                setOnClickListener {
                    if (connected) {
                        disconnect()
                        updateDialogForDisconnected()
                    } else {
                        // Find the device by name and connect
                        discoveredDevices.find { it.name == name }?.let { device ->
                            stopScan()
                            connectToDevice(device)
                        }
                    }
                }
            }
            addView(actionBtn)
        }
    }

    /**
     * Update dialog UI after disconnect.
     */
    private fun updateDialogForDisconnected() {
        statusText?.text = service.getString(R.string.foot_pod_disconnected)
        deviceListContainer?.removeAllViews()
        startScan()
    }

    /**
     * Start BLE scanning for Stryd devices.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning || bluetoothLeScanner == null) return

        discoveredDevices.clear()
        deviceListContainer?.removeAllViews()

        statusText?.text = service.getString(R.string.foot_pod_scanning)
        scanProgressBar?.visibility = View.VISIBLE

        val scanFilter = ScanFilter.Builder()
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        // Stop scan after timeout
        handler.postDelayed({
            stopScan()
            if (discoveredDevices.isEmpty()) {
                statusText?.text = service.getString(R.string.foot_pod_no_devices)
            }
        }, SCAN_TIMEOUT_MS)

        Log.d(TAG, "Started BLE scan")
    }

    /**
     * Stop BLE scanning.
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return

        scanning = false
        bluetoothLeScanner?.stopScan(scanCallback)
        scanProgressBar?.visibility = View.GONE
        handler.removeCallbacksAndMessages(null)

        if (discoveredDevices.isNotEmpty()) {
            statusText?.text = service.getString(R.string.foot_pod_found_devices, discoveredDevices.size)
        }

        Log.d(TAG, "Stopped BLE scan")
    }

    /**
     * Scan callback for BLE device discovery.
     */
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: return

            // Only accept Stryd devices
            if (!deviceName.startsWith(STRYD_NAME_PREFIX)) return

            // Avoid duplicates
            if (discoveredDevices.any { it.address == device.address }) return

            discoveredDevices.add(device)
            Log.d(TAG, "Discovered Stryd: $deviceName (${device.address})")

            // Update UI
            handler.post {
                statusText?.text = service.getString(R.string.foot_pod_found_devices, discoveredDevices.size)
                val deviceRow = createDeviceRow(deviceName, false)
                deviceListContainer?.addView(deviceRow)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            scanning = false
            handler.post {
                statusText?.text = service.getString(R.string.foot_pod_scan_failed)
                scanProgressBar?.visibility = View.GONE
            }
        }
    }

    /**
     * Connect to a Stryd device.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.name} (${device.address})")

        statusText?.text = service.getString(R.string.foot_pod_connecting)

        // Disconnect existing connection
        bluetoothGatt?.close()

        connectedDevice = device
        bluetoothGatt = device.connectGatt(service, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Disconnect from current device.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "Disconnecting from Stryd")

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null

        state.strydConnected = false
        state.currentPowerWatts = 0.0
        state.currentRawPowerWatts = 0.0
        state.currentCadenceSpm = 0
        state.currentStrydSpeedKph = 0.0
        batteryLevelPercent = -1

        listener?.onStrydDisconnected()
    }

    /**
     * GATT callback for connection and data events.
     */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    // Save device to unified list for auto-connect
                    val deviceName = gatt.device.name ?: "Stryd"
                    val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
                    SavedBluetoothDevices.save(prefs, SavedBluetoothDevice(
                        mac = gatt.device.address,
                        name = deviceName,
                        type = SensorDeviceType.FOOT_POD
                    ))

                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    handler.post {
                        state.strydConnected = false
                        state.currentPowerWatts = 0.0
                        state.currentRawPowerWatts = 0.0
                        state.currentCadenceSpm = 0
                        state.currentStrydSpeedKph = 0.0
                        batteryLevelPercent = -1
                        listener?.onStrydDisconnected()

                        if (dialogView != null) {
                            updateDialogForDisconnected()
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            Log.d(TAG, "Services discovered")

            // Find characteristics to subscribe to
            pendingSubscriptions.clear()

            // Power Measurement characteristic
            gatt.getService(CYCLING_POWER_SERVICE)?.getCharacteristic(POWER_MEASUREMENT)?.let {
                pendingSubscriptions.add(it)
                Log.d(TAG, "Found Power Measurement characteristic")
            }

            // RSC Measurement characteristic
            gatt.getService(RSC_SERVICE)?.getCharacteristic(RSC_MEASUREMENT)?.let {
                pendingSubscriptions.add(it)
                Log.d(TAG, "Found RSC Measurement characteristic")
            }

            // Subscribe to characteristics one at a time
            currentSubscriptionIndex = 0
            subscribeToNextCharacteristic(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful for ${descriptor.characteristic.uuid}")
                // Subscribe to next characteristic
                currentSubscriptionIndex++
                subscribeToNextCharacteristic(gatt)
            } else {
                Log.e(TAG, "Descriptor write failed: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value
            if (data == null || data.isEmpty()) return

            when (characteristic.uuid) {
                POWER_MEASUREMENT -> parsePowerMeasurement(data)
                RSC_MEASUREMENT -> parseRscMeasurement(data)
            }
        }

        // Legacy callback for Android 12 and below
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL) {
                @Suppress("DEPRECATION")
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    batteryLevelPercent = data[0].toInt() and 0xFF
                    Log.d(TAG, "Battery level: $batteryLevelPercent%")
                }
            }
            // Mark as connected after initial battery read (or failure)
            if (pendingInitialBatteryRead) {
                pendingInitialBatteryRead = false
                notifyConnected()
            }
        }

        // New callback for Android 13+ (API 33)
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL) {
                if (value.isNotEmpty()) {
                    batteryLevelPercent = value[0].toInt() and 0xFF
                    Log.d(TAG, "Battery level: $batteryLevelPercent%")
                }
            }
            // Mark as connected after initial battery read (or failure)
            if (pendingInitialBatteryRead) {
                pendingInitialBatteryRead = false
                notifyConnected()
            }
        }
    }

    /**
     * Subscribe to the next characteristic in the pending list.
     */
    @SuppressLint("MissingPermission")
    private fun subscribeToNextCharacteristic(gatt: BluetoothGatt) {
        if (currentSubscriptionIndex >= pendingSubscriptions.size) {
            // All subscriptions complete - try to read battery level
            Log.d(TAG, "All characteristics subscribed, reading battery level")
            val batteryService = gatt.getService(BATTERY_SERVICE)
            val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL)
            if (batteryChar != null) {
                pendingInitialBatteryRead = true
                gatt.readCharacteristic(batteryChar)
            } else {
                Log.d(TAG, "Battery service not available")
                notifyConnected()
            }
            return
        }

        val characteristic = pendingSubscriptions[currentSubscriptionIndex]
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR)
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
            Log.d(TAG, "Subscribing to ${characteristic.uuid}")
        } else {
            // No descriptor, try next
            currentSubscriptionIndex++
            subscribeToNextCharacteristic(gatt)
        }
    }

    /**
     * Notify that connection is complete.
     */
    private fun notifyConnected() {
        handler.post {
            state.strydConnected = true
            val deviceName = connectedDevice?.name ?: "Stryd"
            listener?.onStrydConnected(deviceName)

            statusText?.text = service.getString(R.string.foot_pod_connected)
            if (dialogView != null) {
                showConnectedState()
            }
        }
    }

    /**
     * Parse Power Measurement characteristic (0x2A63).
     */
    private fun parsePowerMeasurement(data: ByteArray) {
        if (data.size < 4) return

        // Power is at bytes 2-3 (little-endian)
        val power = ((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)

        // Update timestamp for connection diagnostics
        lastDataTimestampMs = System.currentTimeMillis()

        // Store raw power and calculate adjusted power
        state.currentRawPowerWatts = power.toDouble()
        val adjustedPower = calculateAdjustedPower(power.toDouble())
        state.currentPowerWatts = adjustedPower

        notifyDataUpdate()
    }

    /**
     * Parse RSC Measurement characteristic (0x2A53).
     * BLE RSC spec uses "strides per minute" (one foot contact).
     * We store the raw value; HUD display doubles it for "steps per minute" (both feet).
     * FIT files also use strides/min, so raw value is written directly.
     */
    private fun parseRscMeasurement(data: ByteArray) {
        if (data.size < 4) return

        val flags = data[0].toInt() and 0xFF

        // Speed (m/s * 256) at bytes 1-2
        val speedRaw = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val speedMs = speedRaw / 256.0
        val speedKph = speedMs * 3.6  // Convert m/s to km/h

        // Cadence at byte 3 - BLE RSC uses strides/min (one foot)
        // Store raw value; display layer doubles for steps/min
        val cadenceStridesPerMin = data[3].toInt() and 0xFF

        state.currentCadenceSpm = cadenceStridesPerMin
        state.currentStrydSpeedKph = speedKph

        notifyDataUpdate()
    }

    /**
     * Calculate incline-adjusted power.
     * Stryd cannot detect treadmill incline, so we adjust locally.
     *
     * Grade percentage is the tangent of the angle: grade = tan(θ) × 100
     * For accurate calculation at steep inclines (-6% to 40%), we use proper
     * trigonometry instead of the small angle approximation.
     *
     * Power adjustment = coefficient × m × g × vertical_velocity
     * where vertical_velocity = belt_speed × sin(θ)
     *
     * The coefficient (0.0 to 1.0) allows tuning since treadmill incline
     * doesn't produce the same effort as outdoor climbing:
     * - 0.0 = no adjustment (Stryd already captures all incline effort)
     * - 0.5 = half theoretical adjustment (typical for treadmill)
     * - 1.0 = full theoretical adjustment (outdoor equivalent)
     */
    private fun calculateAdjustedPower(measuredPower: Double): Double {
        // If no power measured, user isn't running - don't add phantom incline power
        if (measuredPower <= 0) return 0.0

        val inclinePercent = state.currentInclinePercent
        val userWeightKg = state.userWeightKg
        val speedKph = state.currentSpeedKph
        val beltSpeedMs = speedKph / 3.6
        val coefficient = state.inclinePowerCoefficient

        // state.currentInclinePercent is already the effective incline (treadmill incline - adjustment)
        // So we can use it directly for power calculation
        val effectiveInclinePercent = inclinePercent

        // Convert grade percentage to angle: grade = tan(θ) × 100, so θ = atan(grade/100)
        val angleRadians = kotlin.math.atan(effectiveInclinePercent / 100.0)

        // Gravity power = mass × gravity × vertical velocity
        // Positive incline: runner works against gravity (add power)
        // Negative incline: gravity assists (subtract power)
        // Apply coefficient to allow tuning for treadmill vs outdoor
        val gravityPower = coefficient * 9.8 * userWeightKg * beltSpeedMs * kotlin.math.sin(angleRadians)

        return measuredPower + gravityPower
    }

    /**
     * Notify listener of data update.
     */
    private fun notifyDataUpdate() {
        listener?.onStrydData(
            state.currentPowerWatts,
            state.currentCadenceSpm
        )
    }

    /**
     * Get the selected foot pod metric to display.
     */
    fun getSelectedMetric(): String {
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(SettingsManager.PREF_FOOT_POD_METRIC, SettingsManager.DEFAULT_FOOT_POD_METRIC)
            ?: SettingsManager.DEFAULT_FOOT_POD_METRIC
    }

    /**
     * Set the selected foot pod metric to display.
     */
    fun setSelectedMetric(metric: String) {
        service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsManager.PREF_FOOT_POD_METRIC, metric)
            .apply()
    }

    /**
     * Show the metric selector dropdown with live data.
     * Each item displays the metric value like the HUD box itself.
     *
     * @param footPodBoxBounds Array of [x, y, width, height] for the foot pod box, or null to use fallback positioning
     */
    @SuppressLint("SetTextI18n")
    fun showMetricSelector(footPodBoxBounds: IntArray? = null) {
        // Toggle off if already showing
        if (metricSelectorView != null) {
            removeMetricSelector()
            return
        }

        val resources = service.resources
        val boxPadding = resources.getDimensionPixelSize(R.dimen.box_padding)
        val itemMargin = resources.getDimensionPixelSize(R.dimen.box_margin)
        val labelTextSize = resources.getDimension(R.dimen.text_label) / resources.displayMetrics.density
        val valueTextSize = resources.getDimension(R.dimen.text_value) / resources.displayMetrics.density
        val unitTextSize = resources.getDimension(R.dimen.text_unit) / resources.displayMetrics.density

        // Get foot pod box dimensions (or use fallback)
        val boxX = footPodBoxBounds?.get(0) ?: 0
        val boxY = footPodBoxBounds?.get(1) ?: 0
        val boxWidth = footPodBoxBounds?.get(2) ?: WindowManager.LayoutParams.WRAP_CONTENT
        val boxHeight = footPodBoxBounds?.get(3) ?: 0

        val currentMetric = getSelectedMetric()

        // Clear previous value views
        metricValueViews.clear()

        // All metrics (excluding current one)
        val metrics = listOf(
            "power" to service.getString(R.string.metric_power),
            "cadence" to service.getString(R.string.metric_cadence),
            "stryd_pace" to service.getString(R.string.metric_stryd_pace)
        ).filter { it.first != currentMetric }

        metricSelectorView = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(service, R.color.hud_background))
            setPadding(itemMargin, itemMargin, itemMargin, itemMargin)

            for ((metricKey, metricLabel) in metrics) {
                val (value, unit) = getMetricDisplay(metricKey)

                // Create item styled like HUD box
                val itemContainer = LinearLayout(service).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(ContextCompat.getColor(service, R.color.box_interactive))
                    setPadding(boxPadding * 4, boxPadding * 2, boxPadding * 4, boxPadding * 2)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = itemMargin
                    }

                    // Label (like "POWER", "CADENCE")
                    val labelView = TextView(service).apply {
                        text = metricLabel.uppercase()
                        setTextColor(ContextCompat.getColor(service, R.color.text_label_dim))
                        textSize = labelTextSize
                        gravity = Gravity.CENTER
                    }
                    addView(labelView)

                    // Value (large, bold) - store reference for live updates
                    val valueView = TextView(service).apply {
                        text = value
                        setTextColor(ContextCompat.getColor(service, R.color.text_primary))
                        textSize = valueTextSize * 0.7f  // Slightly smaller than main HUD
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                    }
                    addView(valueView)
                    metricValueViews[metricKey] = valueView

                    // Unit
                    val unitView = TextView(service).apply {
                        text = unit
                        setTextColor(ContextCompat.getColor(service, R.color.text_label))
                        textSize = unitTextSize
                        gravity = Gravity.CENTER
                    }
                    addView(unitView)

                    setOnClickListener {
                        setSelectedMetric(metricKey)
                        removeMetricSelector()
                        // Trigger immediate UI update
                        listener?.onStrydData(
                            state.currentPowerWatts,
                            state.currentCadenceSpm
                        )
                    }
                }
                addView(itemContainer)
            }
        }

        // Position dropdown directly below the foot pod box, aligned to its left edge
        val params = OverlayHelper.createOverlayParams(
            boxWidth,  // Same width as foot pod box
            focusable = true
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = boxX
        params.y = boxY + boxHeight  // Directly below the box
        windowManager.addView(metricSelectorView, params)

        // Start live refresh
        startMetricSelectorRefresh()
    }

    /**
     * Start periodic refresh of metric selector values.
     */
    private fun startMetricSelectorRefresh() {
        metricSelectorRefreshRunnable = object : Runnable {
            override fun run() {
                if (metricSelectorView == null) return

                // Update all metric values
                for ((metricKey, valueView) in metricValueViews) {
                    val (value, _) = getMetricDisplay(metricKey)
                    valueView.text = value
                }

                // Schedule next refresh
                handler.postDelayed(this, 500)  // Update every 500ms
            }
        }
        handler.post(metricSelectorRefreshRunnable!!)
    }

    /**
     * Get the display value and unit for a metric.
     */
    private fun getMetricDisplay(metric: String): Pair<String, String> {
        return when (metric) {
            "power" -> Pair(
                if (state.currentPowerWatts > 0) state.currentPowerWatts.toInt().toString() else "--",
                service.getString(R.string.unit_watts)
            )
            "cadence" -> Pair(
                // Double strides/min to steps/min for display
                if (state.currentCadenceSpm > 0) (state.currentCadenceSpm * 2).toString() else "--",
                service.getString(R.string.unit_spm)
            )
            "stryd_pace" -> {
                val paceStr = if (state.currentStrydSpeedKph > 0) {
                    io.github.avikulin.thud.util.PaceConverter.formatPaceFromSpeed(state.currentStrydSpeedKph)
                } else "--"
                Pair(paceStr, "/km")
            }
            else -> Pair("--", "")
        }
    }

    /**
     * Remove the metric selector popup.
     */
    fun removeMetricSelector() {
        // Stop refresh runnable
        metricSelectorRefreshRunnable?.let { handler.removeCallbacks(it) }
        metricSelectorRefreshRunnable = null
        metricValueViews.clear()

        metricSelectorView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing metric selector: ${e.message}")
            }
        }
        metricSelectorView = null
    }

    /**
     * Remove the dialog from the window.
     */
    fun removeDialog() {
        stopScan()

        dialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dialog: ${e.message}")
            }
        }
        dialogView = null
        deviceListContainer = null
        statusText = null
        scanProgressBar = null
    }

    /**
     * Clean up resources.
     */
    @SuppressLint("MissingPermission")
    fun cleanup() {
        removeDialog()
        removeMetricSelector()
        stopScan()
        disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Forget a specific device by MAC and disconnect if it's currently connected.
     */
    fun forgetDevice(mac: String) {
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)

        // If this is the currently connected device, disconnect first
        if (connectedDevice?.address == mac) {
            disconnect()
            lastDataTimestampMs = 0L
        }

        SavedBluetoothDevices.remove(prefs, mac)
        Log.d(TAG, "Foot pod device forgotten: $mac")
    }

    /**
     * Forget all saved foot pod devices and disconnect if connected.
     */
    fun forgetAllDevices() {
        disconnect()
        lastDataTimestampMs = 0L
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        SavedBluetoothDevices.removeByType(prefs, SensorDeviceType.FOOT_POD)
        Log.d(TAG, "All foot pod devices forgotten")
    }

    /**
     * Reconnect to saved devices.
     */
    fun reconnect() {
        disconnect()
        autoConnect()
    }

    /**
     * Get all saved foot pod devices.
     */
    fun getSavedDevices(): List<SavedBluetoothDevice> {
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        return SavedBluetoothDevices.getByType(prefs, SensorDeviceType.FOOT_POD)
    }

    /**
     * Get the name of the first saved device, if any (for backwards compatibility).
     */
    fun getSavedDeviceName(): String? {
        return getSavedDevices().firstOrNull()?.name
    }

    /**
     * Get the MAC address of the first saved device, if any (for backwards compatibility).
     */
    fun getSavedDeviceMac(): String? {
        return getSavedDevices().firstOrNull()?.mac
    }

    /**
     * Get the name of the currently connected device.
     */
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceName(): String? {
        return if (state.strydConnected) connectedDevice?.name else null
    }

    /**
     * Get the MAC address of the currently connected device.
     */
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceMac(): String? {
        return if (state.strydConnected) connectedDevice?.address else null
    }

    /**
     * Connect to a specific device by MAC address.
     */
    @SuppressLint("MissingPermission")
    fun connectToDeviceByMac(mac: String) {
        if (bluetoothAdapter == null) return
        try {
            val device = bluetoothAdapter?.getRemoteDevice(mac)
            if (device != null) {
                connectToDevice(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $mac: ${e.message}")
        }
    }

    /**
     * Get the current power reading.
     */
    fun getCurrentPower(): Int {
        return state.currentPowerWatts.toInt()
    }

    /**
     * Request a fresh battery level reading from the connected device.
     * Does nothing if not connected.
     */
    @SuppressLint("MissingPermission")
    fun refreshBatteryLevel() {
        val gatt = bluetoothGatt ?: return
        if (!state.strydConnected) return

        val batteryService = gatt.getService(BATTERY_SERVICE)
        val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL)
        if (batteryChar != null) {
            gatt.readCharacteristic(batteryChar)
            Log.d(TAG, "Requested battery level refresh")
        }
    }
}
