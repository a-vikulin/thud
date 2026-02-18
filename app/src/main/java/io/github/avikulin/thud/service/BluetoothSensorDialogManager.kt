package io.github.avikulin.thud.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R
import java.util.UUID

/**
 * Manages the unified Bluetooth sensors dialog overlay.
 * Shows status and controls for both HR sensor and Stryd foot pod.
 * Provides unified scanning for all compatible BLE devices.
 */
class BluetoothSensorDialogManager(
    private val service: Service,
    private val windowManager: WindowManager,
    private val state: ServiceStateHolder,
    private val hrSensorManager: HrSensorManager,
    private val strydManager: StrydManager
) {
    companion object {
        private const val TAG = "BluetoothSensorDialogMgr"
        private const val STATUS_REFRESH_INTERVAL_MS = 1000L
        private const val SCAN_TIMEOUT_MS = 15000L

        // BLE Service UUIDs for device identification
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val CYCLING_POWER_SERVICE: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
        val RSC_SERVICE: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
    }

    private var dialogView: LinearLayout? = null
    private var connectionStatusContainer: LinearLayout? = null
    private var savedDevicesContainer: LinearLayout? = null
    private var scanSection: LinearLayout? = null
    private var scanButton: Button? = null
    private var scanProgressBar: ProgressBar? = null
    private var scanResultsContainer: LinearLayout? = null

    private val handler = Handler(Looper.getMainLooper())
    private var statusRefreshRunnable: Runnable? = null

    // BLE scanning
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // Discovered devices: MAC -> (device, type)
    private val discoveredDevices = mutableMapOf<String, Pair<BluetoothDevice, SensorDeviceType>>()

    // Reconnecting state tracking - set of MAC addresses currently reconnecting
    private val reconnectingMacs = mutableSetOf<String>()

    val isDialogVisible: Boolean
        get() = dialogView != null

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
     * Show the unified Bluetooth sensors dialog.
     */
    fun showDialog() {
        if (dialogView != null) return

        initializeBluetooth()
        createDialog()

        // Request fresh battery levels from connected devices
        hrSensorManager.refreshBatteryLevel()
        strydManager.refreshBatteryLevel()

        updateStatus()
        startStatusRefresh()

        Log.d(TAG, "Bluetooth sensors dialog shown")
    }

    /**
     * Remove the dialog from screen.
     */
    fun removeDialog() {
        stopStatusRefresh()
        stopScan()

        dialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dialog: ${e.message}")
            }
        }
        dialogView = null
        connectionStatusContainer = null
        savedDevicesContainer = null
        scanSection = null
        scanButton = null
        scanProgressBar = null
        scanResultsContainer = null

        Log.d(TAG, "Bluetooth sensors dialog removed")
    }

    /**
     * Update status display for saved devices list.
     * Called when connection status changes.
     */
    fun updateStatus() {
        if (dialogView == null) return

        handler.post {
            updateSavedDevicesList()
        }
    }

    /**
     * Initialize Bluetooth adapter.
     */
    private fun initializeBluetooth() {
        val bluetoothManager = service.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    /**
     * Create the dialog UI.
     */
    private fun createDialog() {
        val resources = service.resources
        val dialogPaddingH = resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal)
        val dialogPaddingV = resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical)
        val dialogWidthFraction = resources.getFloat(R.dimen.dialog_width_fraction)
        val sectionSpacing = resources.getDimensionPixelSize(R.dimen.dialog_section_spacing)
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

        val titleText = TextView(service).apply {
            text = service.getString(R.string.bt_dialog_title)
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

        // Saved Devices Section (unified list - connected at top, disconnected below)
        savedDevicesContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
        }
        container.addView(savedDevicesContainer)

        // Divider
        container.addView(createDivider(sectionSpacing))

        // Scan section
        scanSection = createScanSection(sectionSpacing)
        container.addView(scanSection)

        // Add to window
        dialogView = container
        val dialogWidth = OverlayHelper.calculateWidth(state.screenWidth, dialogWidthFraction)
        val dialogParams = OverlayHelper.createOverlayParams(dialogWidth, focusable = true)
        windowManager.addView(container, dialogParams)
    }

    /**
     * Create a divider view.
     */
    private fun createDivider(sectionSpacing: Int): View {
        return View(service).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = sectionSpacing / 2
                bottomMargin = sectionSpacing / 2
            }
            setBackgroundColor(ContextCompat.getColor(service, R.color.text_label_dim))
        }
    }

    /**
     * Update the saved devices list - unified list with connected at top, disconnected below.
     */
    @SuppressLint("MissingPermission")
    private fun updateSavedDevicesList() {
        val container = savedDevicesContainer ?: return
        container.removeAllViews()

        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        val savedDevices = SavedBluetoothDevices.getAll(prefs)

        // Clear reconnecting state for connected devices
        hrSensorManager.getConnectedMacs().forEach { reconnectingMacs.remove(it) }
        if (state.strydConnected) {
            strydManager.getConnectedDeviceMac()?.let { reconnectingMacs.remove(it) }
        }

        if (savedDevices.isEmpty()) {
            val noSavedText = TextView(service).apply {
                text = service.getString(R.string.bt_no_saved_devices)
                setTextColor(ContextCompat.getColor(service, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
            }
            container.addView(noSavedText)
            return
        }

        // Sort: connected devices first, then disconnected
        val sortedDevices = savedDevices.sortedByDescending { device ->
            when (device.type) {
                SensorDeviceType.HR_SENSOR -> hrSensorManager.isConnected(device.mac)
                SensorDeviceType.FOOT_POD -> state.strydConnected &&
                    strydManager.getConnectedDeviceName() == device.name
            }
        }

        for (device in sortedDevices) {
            val row = createDeviceRow(device)
            container.addView(row)
        }

        // Show reconnect all button if no devices are connected but we have saved devices
        val anyConnected = hrSensorManager.isAnyConnected || state.strydConnected
        if (!anyConnected && savedDevices.isNotEmpty()) {
            val reconnectBtn = createButton(service.getString(R.string.btn_reconnect_all)) {
                // Mark all devices as reconnecting
                savedDevices.forEach { reconnectingMacs.add(it.mac) }
                hrSensorManager.reconnect()
                strydManager.reconnect()
                updateStatus()
            }
            reconnectBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing }
            container.addView(reconnectBtn)
        }
    }

    /**
     * Create a row for a device - shows connection status, live data if connected, and action buttons.
     */
    @SuppressLint("MissingPermission")
    private fun createDeviceRow(device: SavedBluetoothDevice): LinearLayout {
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        // Check if this device is currently connected
        val isConnected = when (device.type) {
            SensorDeviceType.HR_SENSOR -> hrSensorManager.isConnected(device.mac)
            SensorDeviceType.FOOT_POD -> state.strydConnected &&
                strydManager.getConnectedDeviceName() == device.name
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = rowSpacing / 2 }

            // Type icon (greyed out if not connected)
            val typeIcon = TextView(service).apply {
                text = if (device.type == SensorDeviceType.HR_SENSOR) "❤" else "👟"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_item_text_size))
                alpha = if (isConnected) 1.0f else 0.4f
            }
            addView(typeIcon)

            // Status dot (green if connected, gray otherwise)
            val statusDot = TextView(service).apply {
                text = " ● "
                setTextColor(ContextCompat.getColor(service,
                    if (isConnected) R.color.hr_zone_3 else R.color.text_label_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
            }
            addView(statusDot)

            // Device info column
            val infoColumn = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                val nameText = TextView(service).apply {
                    text = device.name
                    setTextColor(ContextCompat.getColor(service,
                        if (isConnected) R.color.text_primary else R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
                }
                addView(nameText)

                // Show live data if connected
                if (isConnected) {
                    val detailText = TextView(service).apply {
                        val lastDataTime = when (device.type) {
                            SensorDeviceType.HR_SENSOR -> formatLastDataTime(hrSensorManager.getLastDataTimestamp(device.mac))
                            SensorDeviceType.FOOT_POD -> formatLastDataTime(strydManager.lastDataTimestampMs)
                        }
                        val currentValue = when (device.type) {
                            SensorDeviceType.HR_SENSOR -> "${hrSensorManager.getCurrentHeartRate()} bpm"
                            SensorDeviceType.FOOT_POD -> "${strydManager.getCurrentPower()} W"
                        }
                        val batteryLevel = when (device.type) {
                            SensorDeviceType.HR_SENSOR -> formatBatteryLevel(hrSensorManager.getBatteryLevel(device.mac))
                            SensorDeviceType.FOOT_POD -> formatBatteryLevel(strydManager.batteryLevelPercent)
                        }
                        val batteryPart = if (batteryLevel != null) " | $batteryLevel" else ""
                        text = "$lastDataTime | $currentValue$batteryPart"
                        setTextColor(ContextCompat.getColor(service, R.color.text_secondary))
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_item_subtitle_size))
                    }
                    addView(detailText)
                }
            }
            addView(infoColumn)

            // Action buttons
            if (isConnected) {
                // Disconnect button
                val disconnectBtn = createButton(service.getString(R.string.btn_disconnect)) {
                    when (device.type) {
                        SensorDeviceType.HR_SENSOR -> hrSensorManager.disconnect(device.mac)
                        SensorDeviceType.FOOT_POD -> strydManager.disconnect()
                    }
                    updateStatus()
                }
                addView(disconnectBtn)
            } else {
                // Reconnect button with spinner - per device
                val isReconnecting = reconnectingMacs.contains(device.mac)
                val reconnectBtn = createReconnectButton(isReconnecting) {
                    // Clear any other reconnecting devices of the same type
                    val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
                    val sameTypeDevices = SavedBluetoothDevices.getByType(prefs, device.type)
                    sameTypeDevices.forEach { reconnectingMacs.remove(it.mac) }

                    // Mark this device as reconnecting
                    reconnectingMacs.add(device.mac)

                    // connectToDevice closes/reopens this MAC's connection internally
                    when (device.type) {
                        SensorDeviceType.HR_SENSOR -> {
                            hrSensorManager.connectToDeviceByMac(device.mac)
                        }
                        SensorDeviceType.FOOT_POD -> {
                            strydManager.disconnect()
                            strydManager.connectToDeviceByMac(device.mac)
                        }
                    }
                    updateStatus()
                }
                addView(reconnectBtn)
            }

            // Forget button (always shown)
            val forgetBtn = createButton(service.getString(R.string.btn_forget)) {
                reconnectingMacs.remove(device.mac)
                when (device.type) {
                    SensorDeviceType.HR_SENSOR -> hrSensorManager.forgetDevice(device.mac)
                    SensorDeviceType.FOOT_POD -> strydManager.forgetDevice(device.mac)
                }
                updateStatus()
            }
            addView(forgetBtn)
        }
    }

    /**
     * Create the scan section with scan button and results.
     */
    private fun createScanSection(sectionSpacing: Int): LinearLayout {
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL

            // Scan button
            scanButton = Button(service).apply {
                text = service.getString(R.string.btn_scan)
                setTextColor(ContextCompat.getColor(service, R.color.text_primary))
                backgroundTintList = ContextCompat.getColorStateList(service, R.color.button_success)
                setOnClickListener { toggleScan() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = rowSpacing
                }
            }
            addView(scanButton)

            // Scan progress bar
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
            addView(scanProgressBar)

            // Scan results in a scrollable container
            val scrollView = ScrollView(service).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    300  // Max height for results
                ).apply {
                    topMargin = rowSpacing / 2
                }
            }

            scanResultsContainer = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
            }
            scrollView.addView(scanResultsContainer)
            addView(scrollView)
        }
    }

    /**
     * Create a reconnect button that shows a spinner when reconnecting.
     * Mimics Button appearance exactly but with fixed width and internal spinner.
     */
    private fun createReconnectButton(isReconnecting: Boolean, onClick: () -> Unit): LinearLayout {
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val spinnerSize = service.resources.getDimensionPixelSize(R.dimen.reconnect_spinner_size)
        val buttonMinWidth = service.resources.getDimensionPixelSize(R.dimen.bt_action_button_min_width)

        // Create a reference button to copy its styling
        val refButton = Button(service)

        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = buttonMinWidth
            minimumHeight = refButton.minimumHeight

            // Copy Button's background and tint
            background = refButton.background.constantState?.newDrawable()?.mutate()
            backgroundTintList = ContextCompat.getColorStateList(service, R.color.button_secondary)

            // Copy Button's padding
            setPadding(refButton.paddingStart, refButton.paddingTop, refButton.paddingEnd, refButton.paddingBottom)

            // Make it behave like a button
            isClickable = !isReconnecting
            isFocusable = !isReconnecting
            if (!isReconnecting) {
                setOnClickListener { onClick() }
            }
            alpha = if (isReconnecting) 0.7f else 1.0f

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = rowSpacing
            }

            // Spinner (always present, visibility toggled)
            val spinner = ProgressBar(service, null, android.R.attr.progressBarStyleSmall).apply {
                layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                    marginEnd = rowSpacing / 2
                }
                indeterminateTintList = ContextCompat.getColorStateList(service, R.color.text_primary)
                visibility = if (isReconnecting) View.VISIBLE else View.GONE
            }
            addView(spinner)

            // Text (styled like Button text)
            val textView = TextView(service).apply {
                text = if (isReconnecting) {
                    service.getString(R.string.bt_status_reconnecting)
                } else {
                    service.getString(R.string.btn_reconnect)
                }
                setTextColor(ContextCompat.getColor(service, R.color.text_primary))
                typeface = refButton.typeface
                isAllCaps = refButton.isAllCaps
            }
            addView(textView)
        }
    }

    /**
     * Create a styled button.
     * @param minWidthRes Optional dimension resource ID for minimum width
     */
    private fun createButton(text: String, minWidthRes: Int = 0, onClick: () -> Unit): Button {
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        return Button(service).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            backgroundTintList = ContextCompat.getColorStateList(service, R.color.button_secondary)
            setOnClickListener { onClick() }
            if (minWidthRes != 0) {
                minimumWidth = service.resources.getDimensionPixelSize(minWidthRes)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = rowSpacing
            }
        }
    }

    /**
     * Format "last data" timestamp as relative time.
     */
    private fun formatLastDataTime(timestampMs: Long): String {
        if (timestampMs == 0L) return "--"

        val agoMs = System.currentTimeMillis() - timestampMs
        val agoSeconds = agoMs / 1000

        return when {
            agoSeconds < 5 -> "now"
            agoSeconds < 60 -> "${agoSeconds}s"
            agoSeconds < 120 -> ">1m"
            else -> ">2m"
        }
    }

    /**
     * Format battery level as percentage string.
     * Returns null if battery level is not available.
     */
    private fun formatBatteryLevel(percent: Int): String? {
        return if (percent >= 0) "$percent%" else null
    }

    // ==================== Unified BLE Scanning ====================

    /**
     * Toggle scanning state.
     */
    private fun toggleScan() {
        if (isScanning) {
            stopScan()
        } else {
            startScan()
        }
    }

    /**
     * Start unified BLE scan for both HR sensors and foot pods.
     */
    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning || bluetoothLeScanner == null) return

        isScanning = true
        discoveredDevices.clear()
        scanResultsContainer?.removeAllViews()

        // Update UI
        scanButton?.text = service.getString(R.string.btn_stop_scan)
        scanProgressBar?.visibility = View.VISIBLE

        // Build scan filters for all supported services
        val scanFilters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CYCLING_POWER_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(RSC_SERVICE)).build()
        )

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)

        // Auto-stop after timeout
        handler.postDelayed({
            stopScan()
        }, SCAN_TIMEOUT_MS)

        Log.d(TAG, "Started unified BLE scan")
    }

    /**
     * Stop BLE scan.
     */
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return

        isScanning = false

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }

        // Update UI
        scanButton?.text = service.getString(R.string.btn_scan)
        scanProgressBar?.visibility = View.GONE

        handler.removeCallbacksAndMessages(null)
        startStatusRefresh()

        Log.d(TAG, "Stopped BLE scan, found ${discoveredDevices.size} devices")
    }

    /**
     * BLE scan callback.
     */
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address

            // Skip if already discovered in this scan session
            if (discoveredDevices.containsKey(address)) return

            // Skip if device is already saved (paired)
            val prefs = service.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
            if (SavedBluetoothDevices.isSaved(prefs, address)) {
                Log.d(TAG, "Skipping already-saved device: ${device.name ?: "Unknown"} ($address)")
                return
            }

            // Determine device type from advertised services
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val deviceType = when {
                serviceUuids.contains(HEART_RATE_SERVICE) -> SensorDeviceType.HR_SENSOR
                serviceUuids.contains(CYCLING_POWER_SERVICE) -> SensorDeviceType.FOOT_POD
                serviceUuids.contains(RSC_SERVICE) -> SensorDeviceType.FOOT_POD
                else -> return  // Unknown device type, skip
            }

            discoveredDevices[address] = Pair(device, deviceType)
            Log.d(TAG, "Discovered ${deviceType.name}: ${device.name ?: "Unknown"} ($address)")

            // Add to UI on main thread
            handler.post {
                addDiscoveredDeviceToList(device, deviceType, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            handler.post {
                stopScan()
            }
        }
    }

    /**
     * Add a discovered device to the results list.
     */
    @SuppressLint("MissingPermission")
    private fun addDiscoveredDeviceToList(device: BluetoothDevice, deviceType: SensorDeviceType, rssi: Int) {
        val container = scanResultsContainer ?: return
        val rowSpacing = service.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = rowSpacing / 2
            }
        }

        // Type icon
        val typeIcon = TextView(service).apply {
            text = if (deviceType == SensorDeviceType.HR_SENSOR) "❤" else "👟"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_item_text_size))
        }
        row.addView(typeIcon)

        // Device name and signal
        val deviceName = device.name ?: "Unknown"
        val nameLabel = TextView(service).apply {
            text = " $deviceName (${rssi}dBm)"
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, service.resources.getDimension(R.dimen.dialog_section_title_size))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameLabel)

        // Connect button
        val connectBtn = Button(service).apply {
            text = service.getString(R.string.btn_connect)
            setTextColor(ContextCompat.getColor(service, R.color.text_primary))
            backgroundTintList = ContextCompat.getColorStateList(service, R.color.button_success)
            setOnClickListener {
                connectToDevice(device, deviceType)
            }
        }
        row.addView(connectBtn)

        container.addView(row)
    }

    /**
     * Connect to a discovered device using the appropriate manager.
     */
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, deviceType: SensorDeviceType) {
        stopScan()

        when (deviceType) {
            SensorDeviceType.HR_SENSOR -> {
                Log.d(TAG, "Connecting to HR sensor: ${device.name}")
                hrSensorManager.connectToDevice(device)
            }
            SensorDeviceType.FOOT_POD -> {
                Log.d(TAG, "Connecting to foot pod: ${device.name}")
                strydManager.connectToDevice(device)
            }
        }

        // Clear the results list after connecting
        scanResultsContainer?.removeAllViews()
        updateStatus()
    }

    /**
     * Start periodic status refresh.
     */
    private fun startStatusRefresh() {
        statusRefreshRunnable = object : Runnable {
            override fun run() {
                if (dialogView == null) return
                updateStatus()
                handler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS)
            }
        }
        handler.post(statusRefreshRunnable!!)
    }

    /**
     * Stop periodic status refresh.
     */
    private fun stopStatusRefresh() {
        statusRefreshRunnable?.let { handler.removeCallbacks(it) }
        statusRefreshRunnable = null
    }
}
