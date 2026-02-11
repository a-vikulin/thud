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
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R
import java.util.*

/**
 * Manages Bluetooth LE connection to heart rate sensors.
 * Handles scanning, connection, characteristic subscriptions, and data parsing.
 */
class HrSensorManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val state: ServiceStateHolder
) {
    companion object {
        private const val TAG = "HrSensorManager"
        private const val SCAN_TIMEOUT_MS = 30000L

        // BLE Service UUID - Heart Rate Service
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

        // BLE Service UUID - Battery Service
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        // Characteristic UUIDs
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /**
     * Callback interface for HR sensor events.
     */
    interface Listener {
        fun onHrSensorConnected(deviceName: String)
        fun onHrSensorDisconnected()
        fun onHeartRateUpdate(bpm: Int)
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
    private val discoveredMacs = mutableSetOf<String>()

    // Dialog views
    private var dialogView: LinearLayout? = null
    private var deviceListContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var scanProgressBar: ProgressBar? = null

    val isConnected: Boolean
        get() = state.hrSensorConnected

    val isDialogVisible: Boolean
        get() = dialogView != null

    // Timestamp of last HR data received (for connection diagnostics)
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
     * Auto-connect to saved HR sensors, trying each until one connects.
     */
    fun autoConnect() {
        if (!hasPermissions()) {
            Log.w(TAG, "Missing Bluetooth permissions for auto-connect")
            return
        }

        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        val savedDevices = SavedBluetoothDevices.getByType(prefs, SensorDeviceType.HR_SENSOR)

        if (savedDevices.isEmpty() || bluetoothAdapter == null) {
            Log.d(TAG, "No saved HR sensors to auto-connect")
            return
        }

        // Try each saved device in order (most recently used first)
        for (saved in savedDevices) {
            Log.d(TAG, "Attempting auto-connect to HR sensor ${saved.name} (${saved.mac})")
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
     * Show the HR sensor connection dialog.
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
        if (state.hrSensorConnected && connectedDevice != null) {
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
     * Remove dialog from screen.
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
     * Create the dialog UI.
     */
    @SuppressLint("MissingPermission")
    private fun createDialog() {
        val resources = service.resources
        val dialogPaddingH = resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal)
        val dialogPaddingV = resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical)
        val dialogWidthFraction = resources.getFloat(R.dimen.hr_dialog_width_fraction)
        val rowSpacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(service, R.color.popup_background))
            setPadding(dialogPaddingH, dialogPaddingV, dialogPaddingH, dialogPaddingV)
        }

        // Title row with close button
        val titleRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(service).apply {
            text = service.getString(R.string.hr_dialog_title)
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            textSize = resources.getDimension(R.dimen.dialog_title_text_size) / resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleText)

        val closeButton = Button(service).apply {
            text = service.getString(R.string.btn_close)
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            backgroundTintList = ContextCompat.getColorStateList(service, R.color.button_secondary)
            setOnClickListener { removeDialog() }
        }
        titleRow.addView(closeButton)
        container.addView(titleRow)

        // Status text
        statusText = TextView(service).apply {
            text = service.getString(R.string.hr_dialog_scanning)
            setTextColor(ContextCompat.getColor(service, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
        }
        container.addView(statusText)

        // Scan progress bar
        scanProgressBar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing / 2 }
        }
        container.addView(scanProgressBar)

        // Device list container
        deviceListContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
        }
        container.addView(deviceListContainer)

        // Disconnect button (hidden initially)
        val disconnectButton = Button(service).apply {
            text = service.getString(R.string.btn_disconnect)
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            backgroundTintList = ContextCompat.getColorStateList(service, R.color.button_secondary)
            visibility = View.GONE
            tag = "disconnectButton"
            setOnClickListener { disconnect() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
        }
        container.addView(disconnectButton)

        // Show dialog
        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        val dialogParams = OverlayHelper.createOverlayParams(dialogWidth)
        windowManager.addView(container, dialogParams)
        dialogView = container
    }

    /**
     * Start BLE scan for HR sensors.
     */
    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning || bluetoothLeScanner == null) return

        discoveredDevices.clear()
        discoveredMacs.clear()
        deviceListContainer?.removeAllViews()

        statusText?.text = service.getString(R.string.hr_dialog_scanning)
        scanProgressBar?.visibility = View.VISIBLE

        // Scan filter for Heart Rate Service
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE))
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
                statusText?.text = service.getString(R.string.hr_dialog_no_discovered)
            }
        }, SCAN_TIMEOUT_MS)

        Log.d(TAG, "Started HR sensor scan")
    }

    /**
     * Stop BLE scan.
     */
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }

        handler.removeCallbacksAndMessages(null)
        scanProgressBar?.visibility = View.GONE

        Log.d(TAG, "Stopped HR sensor scan")
    }

    /**
     * Scan callback for discovered devices.
     */
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown HR Sensor"

            // Avoid duplicates (O(1) HashSet check instead of O(n) linear scan)
            if (!discoveredMacs.add(device.address)) return

            discoveredDevices.add(device)
            Log.d(TAG, "Discovered HR sensor: $deviceName (${device.address})")

            // Update UI
            handler.post {
                statusText?.text = service.getString(R.string.hr_dialog_found_devices, discoveredDevices.size)
                val deviceRow = createDeviceRow(device, deviceName)
                deviceListContainer?.addView(deviceRow)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            scanning = false
            handler.post {
                statusText?.text = service.getString(R.string.hr_dialog_scan_failed)
                scanProgressBar?.visibility = View.GONE
            }
        }
    }

    /**
     * Create a device row for the dialog.
     */
    @SuppressLint("MissingPermission")
    private fun createDeviceRow(device: BluetoothDevice, deviceName: String): LinearLayout {
        val resources = service.resources
        val rowSpacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing / 2 }
        }

        val nameText = TextView(service).apply {
            text = "$deviceName\n${device.address}"
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameText)

        val connectButton = Button(service).apply {
            text = service.getString(R.string.btn_connect)
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            backgroundTintList = ContextCompat.getColorStateList(service, R.color.button_success)
            setOnClickListener {
                stopScan()
                connectToDevice(device)
            }
        }
        row.addView(connectButton)

        return row
    }

    /**
     * Connect to an HR sensor device.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.name} (${device.address})")

        statusText?.text = service.getString(R.string.hr_device_connecting)

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
        Log.d(TAG, "Disconnecting from HR sensor")

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null

        state.hrSensorConnected = false
        state.currentHeartRateBpm = 0.0
        batteryLevelPercent = -1

        listener?.onHrSensorDisconnected()

        // Update dialog if visible
        if (dialogView != null) {
            updateDialogForDisconnected()
        }
    }

    /**
     * Show connected state in dialog.
     */
    @SuppressLint("MissingPermission")
    private fun showConnectedState() {
        val deviceName = connectedDevice?.name ?: "HR Sensor"
        statusText?.text = service.getString(R.string.hr_device_connected_to, deviceName)
        scanProgressBar?.visibility = View.GONE
        deviceListContainer?.removeAllViews()

        // Show disconnect button
        dialogView?.findViewWithTag<Button>("disconnectButton")?.visibility = View.VISIBLE
    }

    /**
     * Update dialog when disconnected.
     */
    private fun updateDialogForDisconnected() {
        statusText?.text = service.getString(R.string.hr_device_disconnected)
        dialogView?.findViewWithTag<Button>("disconnectButton")?.visibility = View.GONE

        // Restart scan
        handler.postDelayed({ startScan() }, 500)
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
                    val deviceName = gatt.device.name ?: "HR Sensor"
                    val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
                    SavedBluetoothDevices.save(prefs, SavedBluetoothDevice(
                        mac = gatt.device.address,
                        name = deviceName,
                        type = SensorDeviceType.HR_SENSOR
                    ))

                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    handler.post {
                        state.hrSensorConnected = false
                        state.currentHeartRateBpm = 0.0
                        batteryLevelPercent = -1
                        listener?.onHrSensorDisconnected()

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

            // Find Heart Rate Measurement characteristic
            val hrService = gatt.getService(HEART_RATE_SERVICE)
            if (hrService == null) {
                Log.e(TAG, "Heart Rate Service not found")
                return
            }

            val hrMeasurement = hrService.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (hrMeasurement == null) {
                Log.e(TAG, "Heart Rate Measurement characteristic not found")
                return
            }

            // Subscribe to HR notifications
            gatt.setCharacteristicNotification(hrMeasurement, true)

            val descriptor = hrMeasurement.getDescriptor(CCC_DESCRIPTOR)
            if (descriptor != null) {
                // Check if we need to use INDICATE instead of NOTIFY
                val props = hrMeasurement.properties
                val useIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                                  (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0

                @Suppress("DEPRECATION")
                descriptor.value = if (useIndicate) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
                Log.d(TAG, "Subscribing to Heart Rate Measurement")
            } else {
                Log.e(TAG, "CCC descriptor not found")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "HR subscription successful")

                // Try to read battery level
                val batteryService = gatt.getService(BATTERY_SERVICE)
                val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL)
                if (batteryChar != null) {
                    Log.d(TAG, "Reading battery level")
                    pendingInitialBatteryRead = true
                    gatt.readCharacteristic(batteryChar)
                } else {
                    Log.d(TAG, "Battery service not available")
                    // Still mark as connected even without battery
                    notifyConnected()
                }
            } else {
                Log.e(TAG, "HR subscription failed: $status")
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

        private fun notifyConnected() {
            handler.post {
                state.hrSensorConnected = true
                val deviceName = connectedDevice?.name ?: "HR Sensor"
                listener?.onHrSensorConnected(deviceName)

                if (dialogView != null) {
                    showConnectedState()
                }
            }
        }

        // Legacy callback for Android 12 and below
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value
            if (data == null || data.isEmpty()) return

            if (characteristic.uuid == HEART_RATE_MEASUREMENT) {
                parseHeartRateMeasurement(data)
            }
        }

        // New callback for Android 13+ (API 33)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) {
                parseHeartRateMeasurement(value)
            }
        }
    }

    /**
     * Parse Heart Rate Measurement characteristic (0x2A37).
     *
     * Format:
     * - Byte 0: Flags
     *   - Bit 0: HR Value Format (0 = UINT8, 1 = UINT16)
     *   - Bit 1-2: Sensor Contact Status
     *   - Bit 3: Energy Expended Present
     *   - Bit 4: RR-Interval Present
     * - Byte 1 (or 1-2): Heart Rate Value
     */
    private fun parseHeartRateMeasurement(data: ByteArray) {
        if (data.isEmpty()) return

        val flags = data[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0

        val heartRate = if (is16Bit && data.size >= 3) {
            // 16-bit HR value (little-endian)
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        } else if (data.size >= 2) {
            // 8-bit HR value
            data[1].toInt() and 0xFF
        } else {
            return
        }

        // Some optical sensors report 0 HR when no contact - filter these
        if (heartRate == 0) return

        // Update timestamp for connection diagnostics
        lastDataTimestampMs = System.currentTimeMillis()

        state.currentHeartRateBpm = heartRate.toDouble()
        listener?.onHeartRateUpdate(heartRate)
    }

    /**
     * Clean up resources.
     */
    @SuppressLint("MissingPermission")
    fun cleanup() {
        stopScan()
        removeDialog()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null
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
        Log.d(TAG, "HR device forgotten: $mac")
    }

    /**
     * Forget all saved HR devices and disconnect if connected.
     */
    fun forgetAllDevices() {
        disconnect()
        lastDataTimestampMs = 0L
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        SavedBluetoothDevices.removeByType(prefs, SensorDeviceType.HR_SENSOR)
        Log.d(TAG, "All HR devices forgotten")
    }

    /**
     * Reconnect to saved devices.
     */
    fun reconnect() {
        disconnect()
        autoConnect()
    }

    /**
     * Get all saved HR sensor devices.
     */
    fun getSavedDevices(): List<SavedBluetoothDevice> {
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        return SavedBluetoothDevices.getByType(prefs, SensorDeviceType.HR_SENSOR)
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
        return if (state.hrSensorConnected) connectedDevice?.name else null
    }

    /**
     * Get the MAC address of the currently connected device.
     */
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceMac(): String? {
        return if (state.hrSensorConnected) connectedDevice?.address else null
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
     * Get the current heart rate reading.
     */
    fun getCurrentHeartRate(): Int {
        return state.currentHeartRateBpm.toInt()
    }

    /**
     * Request a fresh battery level reading from the connected device.
     * Does nothing if not connected.
     */
    @SuppressLint("MissingPermission")
    fun refreshBatteryLevel() {
        val gatt = bluetoothGatt ?: return
        if (!state.hrSensorConnected) return

        val batteryService = gatt.getService(BATTERY_SERVICE)
        val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL)
        if (batteryChar != null) {
            gatt.readCharacteristic(batteryChar)
            Log.d(TAG, "Requested battery level refresh")
        }
    }
}
