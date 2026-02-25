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
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Bluetooth LE connections to heart rate sensors.
 * Supports simultaneous connections to multiple HR sensors.
 * HUDService (via listener) decides which sensor's value drives the HUD and engine.
 */
class HrSensorManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val state: ServiceStateHolder
) {
    companion object {
        private const val TAG = "HrSensorManager"
        private const val SCAN_TIMEOUT_MS = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private val RECONNECT_DELAYS = longArrayOf(5000, 10000, 20000)

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
     * Callback interface for HR sensor events. All callbacks identify the sensor by MAC.
     * HUDService is responsible for routing the data to state and engine.
     */
    interface Listener {
        fun onHrSensorConnected(mac: String, deviceName: String)
        fun onHrSensorDisconnected(mac: String, deviceName: String)
        fun onHeartRateUpdate(mac: String, deviceName: String, bpm: Int)
        fun onRrIntervalsReceived(mac: String, deviceName: String, rrIntervalsMs: List<Double>)
    }

    var listener: Listener? = null

    // Per-MAC connection state
    private data class HrConnection(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt,
        var batteryLevel: Int = -1,
        var lastDataMs: Long = 0L
    )
    private val connections = ConcurrentHashMap<String, HrConnection>()
    // GATT objects awaiting STATE_CONNECTED callback — NOT yet "connected" for isConnected() purposes
    private val pendingGatts = ConcurrentHashMap<String, Pair<BluetoothDevice, BluetoothGatt>>()
    private val intentionalDisconnects = ConcurrentHashMap.newKeySet<String>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val reconnectRunnables = ConcurrentHashMap<String, Runnable?>()

    // Per-sensor RR interval capability (set on first notification with bit 4 set)
    private val rrCapableSensors: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Returns true if the sensor at [mac] has been observed broadcasting RR intervals. */
    fun hasRrCapability(mac: String): Boolean = mac in rrCapableSensors

    /** Returns the set of MACs currently known to broadcast RR intervals. */
    fun getRrCapableMacs(): Set<String> = rrCapableSensors.toSet()

    // BLE components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false

    // Handler for scan timeout and reconnect scheduling
    private val handler = Handler(Looper.getMainLooper())

    // Scan timeout tracking
    private var scanTimeoutRunnable: Runnable? = null

    // Discovered devices
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private val discoveredMacs = mutableSetOf<String>()

    // Dialog views (for the internal scan/connect dialog — used by HrSensorManager directly)
    private var dialogView: LinearLayout? = null
    private var deviceListContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var scanProgressBar: ProgressBar? = null

    // Public API

    val isAnyConnected: Boolean get() = connections.isNotEmpty()

    /** Returns true if the given MAC is currently connected. */
    fun isConnected(mac: String): Boolean = connections.containsKey(mac)

    /** Returns battery level [0-100] for the given MAC, or -1 if not available. */
    fun getBatteryLevel(mac: String): Int = connections[mac]?.batteryLevel ?: -1

    /** Returns timestamp (ms) of last HR data from the given MAC, or 0 if none. */
    fun getLastDataTimestamp(mac: String): Long = connections[mac]?.lastDataMs ?: 0L

    /** Returns current set of connected MACs. */
    fun getConnectedMacs(): Set<String> = connections.keys.toSet()

    val isDialogVisible: Boolean
        get() = dialogView != null

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
            true
        }
    }

    /**
     * Auto-connect to ALL saved HR sensors simultaneously.
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

        for (saved in savedDevices) {
            Log.d(TAG, "Attempting auto-connect to HR sensor ${saved.name} (${saved.mac})")
            try {
                @SuppressLint("MissingPermission")
                val device = bluetoothAdapter?.getRemoteDevice(saved.mac)
                if (device != null) {
                    connectToDevice(device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-connect to ${saved.mac} failed: ${e.message}")
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

        if (connections.isNotEmpty()) {
            showConnectedState()
        } else {
            startScan()
        }
    }

    fun toggleDialog() {
        if (dialogView != null) removeDialog() else showDialog()
    }

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

        val closeButton = OverlayHelper.createStyledButton(service, service.getString(R.string.btn_close)) {
            removeDialog()
        }
        titleRow.addView(closeButton)
        container.addView(titleRow)

        statusText = TextView(service).apply {
            text = service.getString(R.string.hr_dialog_scanning)
            setTextColor(ContextCompat.getColor(service, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
        }
        container.addView(statusText)

        scanProgressBar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing / 2 }
        }
        container.addView(scanProgressBar)

        deviceListContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
        }
        container.addView(deviceListContainer)

        val disconnectButton = OverlayHelper.createStyledButton(service, service.getString(R.string.btn_disconnect)) {
            disconnectAll()
        }.apply {
            visibility = View.GONE
            tag = "disconnectButton"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
        }
        container.addView(disconnectButton)

        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        val dialogParams = OverlayHelper.createOverlayParams(dialogWidth)
        windowManager.addView(container, dialogParams)
        dialogView = container
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning || bluetoothLeScanner == null) return

        discoveredDevices.clear()
        discoveredMacs.clear()
        deviceListContainer?.removeAllViews()

        statusText?.text = service.getString(R.string.hr_dialog_scanning)
        scanProgressBar?.visibility = View.VISIBLE

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        val timeout = Runnable {
            stopScan()
            if (discoveredDevices.isEmpty()) {
                statusText?.text = service.getString(R.string.hr_dialog_no_discovered)
            }
        }
        scanTimeoutRunnable = timeout
        handler.postDelayed(timeout, SCAN_TIMEOUT_MS)

        Log.d(TAG, "Started HR sensor scan")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }

        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        scanProgressBar?.visibility = View.GONE

        Log.d(TAG, "Stopped HR sensor scan")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown HR Sensor"

            if (!discoveredMacs.add(device.address)) return

            discoveredDevices.add(device)
            Log.d(TAG, "Discovered HR sensor: $deviceName (${device.address})")

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

        val connectButton = OverlayHelper.createStyledButton(service, service.getString(R.string.btn_connect), R.color.button_success) {
            stopScan()
            connectToDevice(device)
        }
        row.addView(connectButton)

        return row
    }

    /**
     * Connect to an HR sensor device. Closes any existing connection for the same MAC first.
     * Does NOT disconnect other MACs.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val mac = device.address
        Log.d(TAG, "Connecting to ${device.name} ($mac)")

        statusText?.text = service.getString(R.string.hr_device_connecting)

        // Close any existing connection or pending attempt for this MAC
        connections.remove(mac)?.gatt?.close()
        pendingGatts.remove(mac)?.second?.close()

        val callback = HrGattCallback(mac)
        val gatt = device.connectGatt(service, false, callback, BluetoothDevice.TRANSPORT_LE)
        // Store as pending — only moved to connections when STATE_CONNECTED callback fires
        pendingGatts[mac] = Pair(device, gatt)
    }

    /**
     * Disconnect a specific MAC only.
     */
    @SuppressLint("MissingPermission")
    fun disconnect(mac: String) {
        Log.d(TAG, "Disconnecting HR sensor $mac")
        intentionalDisconnects.add(mac)
        cancelPendingReconnect(mac)
        rrCapableSensors.remove(mac)

        val conn = connections.remove(mac)
        conn?.gatt?.disconnect()
        conn?.gatt?.close()

        val pending = pendingGatts.remove(mac)
        pending?.second?.disconnect()
        pending?.second?.close()
    }

    /**
     * Disconnect all connected HR sensors.
     */
    fun disconnectAll() {
        Log.d(TAG, "Disconnecting all HR sensors")
        val allMacs = (connections.keys + pendingGatts.keys).toSet()
        allMacs.forEach { disconnect(it) }
    }

    private fun showConnectedState() {
        val count = connections.size
        statusText?.text = service.getString(R.string.hr_device_connected_to, "$count device(s)")
        scanProgressBar?.visibility = View.GONE
        deviceListContainer?.removeAllViews()
        dialogView?.findViewWithTag<Button>("disconnectButton")?.visibility = View.VISIBLE
    }

    private fun updateDialogForDisconnected() {
        if (connections.isEmpty()) {
            statusText?.text = service.getString(R.string.hr_device_disconnected)
            dialogView?.findViewWithTag<Button>("disconnectButton")?.visibility = View.GONE
            handler.postDelayed({ startScan() }, 500)
        }
    }

    /**
     * Per-MAC GATT callback. MAC is captured at construction so all callbacks are self-identifying.
     */
    private inner class HrGattCallback(val mac: String) : BluetoothGattCallback() {
        private var pendingInitialBatteryRead = false

        /** Resolve best available name: BLE cache → saved prefs → fallback. */
        @SuppressLint("MissingPermission")
        private fun resolveDeviceName(gatt: BluetoothGatt): String {
            gatt.device.name?.let { return it }
            connections[mac]?.device?.name?.let { return it }
            val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
            SavedBluetoothDevices.getAll(prefs).firstOrNull { it.mac == mac }?.name?.let { return it }
            return "HR Sensor"
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "[$mac] onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "[$mac] Connected to GATT server")
                    reconnectAttempts[mac] = 0
                    intentionalDisconnects.remove(mac)

                    // Promote from pending to connected — isConnected() now returns true
                    val pending = pendingGatts.remove(mac)
                    val device = pending?.first ?: gatt.device
                    connections[mac] = HrConnection(device, gatt)

                    // Only update saved name if BLE provided a real name (not null)
                    val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
                    val bleName = gatt.device.name
                    if (bleName != null) {
                        SavedBluetoothDevices.save(prefs, SavedBluetoothDevice(
                            mac = mac,
                            name = bleName,
                            type = SensorDeviceType.HR_SENSOR
                        ))
                    } else if (!SavedBluetoothDevices.isSaved(prefs, mac)) {
                        // First-time connection with no name — save with fallback
                        SavedBluetoothDevices.save(prefs, SavedBluetoothDevice(
                            mac = mac,
                            name = "HR Sensor",
                            type = SensorDeviceType.HR_SENSOR
                        ))
                    }

                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "[$mac] Disconnected from GATT server")
                    val wasIntentional = intentionalDisconnects.remove(mac)
                    val deviceName = resolveDeviceName(gatt)
                    connections.remove(mac)
                    pendingGatts.remove(mac)?.second?.close()

                    handler.post {
                        listener?.onHrSensorDisconnected(mac, deviceName)

                        if (dialogView != null) {
                            updateDialogForDisconnected()
                        }

                        if (!wasIntentional && state.isRunning) {
                            scheduleReconnect(mac)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[$mac] Service discovery failed: $status")
                return
            }

            Log.d(TAG, "[$mac] Services discovered")

            val hrService = gatt.getService(HEART_RATE_SERVICE)
            if (hrService == null) {
                Log.e(TAG, "[$mac] Heart Rate Service not found")
                return
            }

            val hrMeasurement = hrService.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (hrMeasurement == null) {
                Log.e(TAG, "[$mac] Heart Rate Measurement characteristic not found")
                return
            }

            gatt.setCharacteristicNotification(hrMeasurement, true)

            val descriptor = hrMeasurement.getDescriptor(CCC_DESCRIPTOR)
            if (descriptor != null) {
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
                Log.d(TAG, "[$mac] Subscribing to Heart Rate Measurement")
            } else {
                Log.e(TAG, "[$mac] CCC descriptor not found")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "[$mac] HR subscription successful")

                val batteryService = gatt.getService(BATTERY_SERVICE)
                val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL)
                if (batteryChar != null) {
                    Log.d(TAG, "[$mac] Reading battery level")
                    pendingInitialBatteryRead = true
                    gatt.readCharacteristic(batteryChar)
                } else {
                    Log.d(TAG, "[$mac] Battery service not available")
                    notifyConnected(gatt)
                }
            } else {
                Log.e(TAG, "[$mac] HR subscription failed: $status")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL) {
                @Suppress("DEPRECATION")
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    val level = data[0].toInt() and 0xFF
                    connections[mac]?.batteryLevel = level
                    Log.d(TAG, "[$mac] Battery level: $level%")
                }
            }
            if (pendingInitialBatteryRead) {
                pendingInitialBatteryRead = false
                notifyConnected(gatt)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL) {
                if (value.isNotEmpty()) {
                    val level = value[0].toInt() and 0xFF
                    connections[mac]?.batteryLevel = level
                    Log.d(TAG, "[$mac] Battery level: $level%")
                }
            }
            if (pendingInitialBatteryRead) {
                pendingInitialBatteryRead = false
                notifyConnected(gatt)
            }
        }

        private fun notifyConnected(gatt: BluetoothGatt) {
            handler.post {
                val deviceName = resolveDeviceName(gatt)
                // Update saved name if BLE now provides the real name (fixes "HR Sensor" from earlier)
                val bleName = gatt.device.name
                if (bleName != null) {
                    val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
                    val saved = SavedBluetoothDevices.getAll(prefs).firstOrNull { it.mac == mac }
                    if (saved != null && saved.name != bleName) {
                        SavedBluetoothDevices.save(prefs, saved.copy(name = bleName))
                        Log.d(TAG, "[$mac] Updated saved name: '${saved.name}' → '$bleName'")
                    }
                }
                // Update the connection slot with the authoritative gatt reference
                val conn = connections[mac]
                if (conn != null) {
                    connections[mac] = conn.copy(gatt = gatt)
                }
                listener?.onHrSensorConnected(mac, deviceName)

                if (dialogView != null) {
                    showConnectedState()
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value
            if (data == null || data.isEmpty()) return
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) {
                parseHeartRateMeasurement(data, resolveDeviceName(gatt))
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) {
                parseHeartRateMeasurement(value, resolveDeviceName(gatt))
            }
        }

        private fun parseHeartRateMeasurement(data: ByteArray, deviceName: String) {
            if (data.isEmpty()) return

            val flags = data[0].toInt() and 0xFF
            val is16Bit = (flags and 0x01) != 0
            val hasEnergyExpended = (flags and 0x08) != 0
            val hasRrIntervals = (flags and 0x10) != 0

            // Parse heart rate value
            var offset: Int
            val heartRate: Int
            if (is16Bit && data.size >= 3) {
                heartRate = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                offset = 3
            } else if (data.size >= 2) {
                heartRate = data[1].toInt() and 0xFF
                offset = 2
            } else {
                return
            }

            if (heartRate == 0) return

            connections[mac]?.lastDataMs = System.currentTimeMillis()
            listener?.onHeartRateUpdate(mac, deviceName, heartRate)

            // Skip Energy Expended (UINT16 LE) if present
            if (hasEnergyExpended) offset += 2

            // Parse RR intervals (UINT16 LE, 1/1024 sec units)
            if (hasRrIntervals && offset < data.size) {
                rrCapableSensors.add(mac)
                val rrIntervalsMs = mutableListOf<Double>()
                while (offset + 1 < data.size) {
                    val rawRr = ((data[offset + 1].toInt() and 0xFF) shl 8) or
                                (data[offset].toInt() and 0xFF)
                    offset += 2
                    if (rawRr > 0) {
                        rrIntervalsMs.add(rawRr * 1000.0 / 1024.0)
                    }
                }
                if (rrIntervalsMs.isNotEmpty()) {
                    listener?.onRrIntervalsReceived(mac, deviceName, rrIntervalsMs)
                }
            }
        }
    }

    /**
     * Schedule a reconnect for a specific MAC with exponential backoff.
     */
    private fun scheduleReconnect(mac: String) {
        val attempt = reconnectAttempts.getOrDefault(mac, 0)
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "[$mac] Max reconnect attempts reached, giving up")
            reconnectAttempts.remove(mac)
            return
        }
        val delay = RECONNECT_DELAYS[attempt]
        Log.d(TAG, "[$mac] Scheduling reconnect attempt ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS in ${delay}ms")
        reconnectAttempts[mac] = attempt + 1
        val runnable = Runnable {
            if (!state.isRunning || intentionalDisconnects.contains(mac)) return@Runnable
            Log.d(TAG, "[$mac] Attempting auto-reconnect")
            connectToDeviceByMac(mac)
        }
        reconnectRunnables[mac] = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun cancelPendingReconnect(mac: String) {
        reconnectRunnables.remove(mac)?.let { handler.removeCallbacks(it) }
        reconnectAttempts.remove(mac)
    }

    /**
     * Clean up all connections and resources.
     */
    @SuppressLint("MissingPermission")
    fun cleanup() {
        reconnectRunnables.values.forEach { it?.let { r -> handler.removeCallbacks(r) } }
        reconnectRunnables.clear()
        reconnectAttempts.clear()
        stopScan()
        removeDialog()
        connections.values.forEach { it.gatt.close() }
        connections.clear()
        pendingGatts.values.forEach { it.second.close() }
        pendingGatts.clear()
        rrCapableSensors.clear()
    }

    /**
     * Forget a specific device by MAC and disconnect if currently connected.
     */
    fun forgetDevice(mac: String) {
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)

        if (connections.containsKey(mac) || pendingGatts.containsKey(mac)) {
            disconnect(mac)
        }

        SavedBluetoothDevices.remove(prefs, mac)
        Log.d(TAG, "HR device forgotten: $mac")
    }

    /**
     * Forget all saved HR devices and disconnect all.
     */
    fun forgetAllDevices() {
        disconnectAll()
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        SavedBluetoothDevices.removeByType(prefs, SensorDeviceType.HR_SENSOR)
        Log.d(TAG, "All HR devices forgotten")
    }

    /**
     * Disconnect all then reconnect all saved sensors.
     */
    fun reconnect() {
        disconnectAll()
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
     * Get the current heart rate reading (from state — managed by HUDService).
     */
    fun getCurrentHeartRate(): Int {
        return state.currentHeartRateBpm.toInt()
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
     * Request a fresh battery level reading from the given connected device.
     */
    @SuppressLint("MissingPermission")
    fun refreshBatteryLevel(mac: String) {
        val conn = connections[mac] ?: return
        val batteryService = conn.gatt.getService(BATTERY_SERVICE)
        val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL)
        if (batteryChar != null) {
            conn.gatt.readCharacteristic(batteryChar)
            Log.d(TAG, "[$mac] Requested battery level refresh")
        }
    }

    /**
     * Refresh battery for all connected sensors.
     */
    fun refreshBatteryLevel() {
        connections.keys.toList().forEach { refreshBatteryLevel(it) }
    }
}
