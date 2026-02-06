package io.github.avikulin.thud.service.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import io.github.avikulin.thud.service.dircon.FtmsCharacteristics
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE FTMS (Fitness Machine Service) server for fitness app connectivity.
 *
 * Exposes treadmill data over Bluetooth Low Energy, allowing apps like
 * Zwift, Kinomap, etc. to connect and receive real-time treadmill metrics.
 * Also accepts control commands for speed/incline.
 */
@SuppressLint("MissingPermission")
class BleFtmsServer(
    private val context: Context,
    private val listener: Listener,
    private val deviceName: String = "tHUD Treadmill BLE",
    private val controlAllowed: Boolean = true
) {
    interface Listener {
        /** Called when a client connects */
        fun onClientConnected(clientAddress: String)

        /** Called when a client disconnects */
        fun onClientDisconnected(clientAddress: String)

        /** Called when client requests control of the treadmill */
        fun onControlRequested(): Boolean

        /** Called when client sets target speed */
        fun onSetTargetSpeed(speedKph: Double)

        /** Called when client sets target incline */
        fun onSetTargetIncline(inclinePercent: Double)

        /** Called when client sends start/resume command */
        fun onStartResume()

        /** Called when client sends stop/pause command */
        fun onStopPause(stop: Boolean)

        /** Called when client sends simulation parameters (grade from virtual terrain) */
        fun onSimulationParameters(gradePercent: Double, windSpeedMps: Double, crr: Double, cw: Double)

        /** Get current treadmill speed in km/h */
        fun getCurrentSpeedKph(): Double

        /** Get current treadmill incline in % */
        fun getCurrentInclinePercent(): Double

        /** Get total distance in meters */
        fun getTotalDistanceMeters(): Double

        /** Get elapsed time in seconds */
        fun getElapsedTimeSeconds(): Int

        /** Get current heart rate in BPM (0 if not available) */
        fun getCurrentHeartRateBpm(): Int

        /** Check if treadmill is running */
        fun isTreadmillRunning(): Boolean

        /** Get min/max speed range */
        fun getSpeedRange(): Pair<Double, Double>

        /** Get min/max incline range */
        fun getInclineRange(): Pair<Double, Double>

        /** Get average speed in km/h (0 if not available) */
        fun getAverageSpeedKph(): Double = 0.0

        /** Get positive elevation gain in meters (0 if not available) */
        fun getPositiveElevationGainMeters(): Double = 0.0

        /** Get negative elevation gain in meters (0 if not available) */
        fun getNegativeElevationGainMeters(): Double = 0.0

        /** Get total calories burned in kcal (0 if not available) */
        fun getTotalCaloriesKcal(): Double = 0.0

        /** Get calories burned per hour in kcal/h (0 if not available) */
        fun getCaloriesPerHourKcal(): Double = 0.0

        /** Check if Stryd foot pod is connected */
        fun isStrydConnected(): Boolean = false

        /** Get Stryd power in watts (0 if not available) */
        fun getStrydPowerWatts(): Int = 0

        /** Get Stryd cadence in steps per minute (0 if not available) */
        fun getStrydCadenceSpm(): Int = 0

        /** Get Stryd speed in km/h (0 if not available) */
        fun getStrydSpeedKph(): Double = 0.0
    }

    companion object {
        private const val TAG = "BleFtmsServer"
        private const val NOTIFICATION_INTERVAL_MS = 250L  // 4 Hz update rate

        // Standard BLE UUIDs
        private val UUID_FTMS_SERVICE = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        private val UUID_HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val UUID_DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

        // FTMS Characteristics
        private val UUID_FITNESS_MACHINE_FEATURE = UUID.fromString("00002acc-0000-1000-8000-00805f9b34fb")
        private val UUID_TREADMILL_DATA = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
        private val UUID_TRAINING_STATUS = UUID.fromString("00002ad3-0000-1000-8000-00805f9b34fb")
        private val UUID_SUPPORTED_SPEED_RANGE = UUID.fromString("00002ad4-0000-1000-8000-00805f9b34fb")
        private val UUID_SUPPORTED_INCLINE_RANGE = UUID.fromString("00002ad5-0000-1000-8000-00805f9b34fb")
        private val UUID_CONTROL_POINT = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
        private val UUID_FITNESS_MACHINE_STATUS = UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb")

        // Heart Rate Characteristics
        private val UUID_HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        // Device Info Characteristics
        private val UUID_MANUFACTURER_NAME = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val UUID_MODEL_NUMBER = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor
        private val UUID_CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var isRunning = false
    private var notificationJob: Job? = null

    // Track connected devices and their notification subscriptions
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val notificationSubscriptions = ConcurrentHashMap<String, MutableSet<UUID>>()

    // Track which device has control (atomic for thread safety across GATT callbacks)
    private val controllingDevice = AtomicReference<String?>(null)

    // Service addition queue (addService is async, must wait for callback)
    private val pendingServices = mutableListOf<BluetoothGattService>()
    private var servicesReady = false

    /**
     * Start the BLE FTMS server.
     */
    fun start(): Boolean {
        if (isRunning) {
            Log.w(TAG, "Server already running")
            return true
        }

        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return false
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            return false
        }

        // Open GATT server
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }

        // Queue services for addition (addService is async)
        pendingServices.clear()
        pendingServices.add(createFtmsService())
        pendingServices.add(createHeartRateService())
        pendingServices.add(createDeviceInfoService())
        servicesReady = false

        // Start adding first service (rest will be added in onServiceAdded callback)
        if (pendingServices.isNotEmpty()) {
            val firstService = pendingServices.removeAt(0)
            Log.d(TAG, "Adding service: ${firstService.uuid}")
            if (!gattServer!!.addService(firstService)) {
                Log.e(TAG, "Failed to add service: ${firstService.uuid}")
                gattServer?.close()
                gattServer = null
                return false
            }
        }

        isRunning = true
        Log.i(TAG, "BLE FTMS server starting (waiting for services to be added)...")
        return true
    }

    /**
     * Stop the BLE FTMS server.
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        servicesReady = false
        notificationJob?.cancel()
        notificationJob = null

        stopAdvertising()

        // Disconnect all clients
        connectedDevices.values.forEach { device ->
            gattServer?.cancelConnection(device)
        }
        connectedDevices.clear()
        notificationSubscriptions.clear()
        pendingServices.clear()
        controllingDevice.set(null)

        gattServer?.close()
        gattServer = null

        scope.cancel()
        Log.i(TAG, "BLE FTMS server stopped")
    }

    fun isRunning(): Boolean = isRunning

    // ==================== Service Setup ====================

    private fun createFtmsService(): BluetoothGattService {
        val service = BluetoothGattService(
            UUID_FTMS_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Fitness Machine Feature - Read
        service.addCharacteristic(BluetoothGattCharacteristic(
            UUID_FITNESS_MACHINE_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ))

        // Treadmill Data - Notify
        val treadmillData = BluetoothGattCharacteristic(
            UUID_TREADMILL_DATA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        treadmillData.addDescriptor(createCccDescriptor())
        service.addCharacteristic(treadmillData)

        // Training Status - Read, Notify
        val trainingStatus = BluetoothGattCharacteristic(
            UUID_TRAINING_STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        trainingStatus.addDescriptor(createCccDescriptor())
        service.addCharacteristic(trainingStatus)

        // Supported Speed Range - Read
        service.addCharacteristic(BluetoothGattCharacteristic(
            UUID_SUPPORTED_SPEED_RANGE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ))

        // Supported Incline Range - Read
        service.addCharacteristic(BluetoothGattCharacteristic(
            UUID_SUPPORTED_INCLINE_RANGE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ))

        // Fitness Machine Control Point - Write, Indicate
        val controlPoint = BluetoothGattCharacteristic(
            UUID_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        controlPoint.addDescriptor(createCccDescriptor())
        service.addCharacteristic(controlPoint)

        // Fitness Machine Status - Notify
        val machineStatus = BluetoothGattCharacteristic(
            UUID_FITNESS_MACHINE_STATUS,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        machineStatus.addDescriptor(createCccDescriptor())
        service.addCharacteristic(machineStatus)

        return service
    }

    private fun createHeartRateService(): BluetoothGattService {
        val service = BluetoothGattService(
            UUID_HEART_RATE_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val hrMeasurement = BluetoothGattCharacteristic(
            UUID_HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        hrMeasurement.addDescriptor(createCccDescriptor())
        service.addCharacteristic(hrMeasurement)

        return service
    }

    private fun createDeviceInfoService(): BluetoothGattService {
        val service = BluetoothGattService(
            UUID_DEVICE_INFO_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        service.addCharacteristic(BluetoothGattCharacteristic(
            UUID_MANUFACTURER_NAME,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ))

        service.addCharacteristic(BluetoothGattCharacteristic(
            UUID_MODEL_NUMBER,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ))

        return service
    }

    private fun createCccDescriptor(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            UUID_CCC_DESCRIPTOR,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
    }

    // ==================== Advertising ====================

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)  // More stable than LOW_LATENCY
            .setConnectable(true)
            .setTimeout(0)  // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID_FTMS_SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID_HEART_RATE_SERVICE))
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
    }

    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "BLE advertising failed: $errorMsg")

            // Retry after a delay if it's a transient error
            if (isRunning && errorCode != ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                scope.launch {
                    delay(1000)
                    if (isRunning && !isAdvertising) {
                        Log.d(TAG, "Retrying BLE advertising...")
                        startAdvertising()
                    }
                }
            }
        }
    }

    // ==================== GATT Server Callbacks ====================

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Service added: ${service.uuid}")

                // Add next pending service or start advertising
                if (pendingServices.isNotEmpty()) {
                    val nextService = pendingServices.removeAt(0)
                    Log.d(TAG, "Adding next service: ${nextService.uuid}")
                    gattServer?.addService(nextService)
                } else {
                    // All services added, start advertising
                    servicesReady = true
                    Log.i(TAG, "All services added, starting advertising")
                    startAdvertising()
                    startNotificationLoop()
                }
            } else {
                Log.e(TAG, "Failed to add service ${service.uuid}: status=$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Connection state change with error status: $status for $address")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Client connected: $address")
                    connectedDevices[address] = device
                    notificationSubscriptions[address] = mutableSetOf()
                    listener.onClientConnected(address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client disconnected: $address (status=$status)")
                    connectedDevices.remove(address)
                    notificationSubscriptions.remove(address)
                    controllingDevice.compareAndSet(address, null)
                    listener.onClientDisconnected(address)

                    // Restart advertising if no clients connected (Android may have stopped it)
                    if (connectedDevices.isEmpty() && isRunning && !isAdvertising) {
                        Log.d(TAG, "Restarting advertising after disconnect")
                        startAdvertising()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "MTU changed to $mtu for ${device.address}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Read request for ${characteristic.uuid} from ${device.address}")
            val value = when (characteristic.uuid) {
                UUID_FITNESS_MACHINE_FEATURE -> FtmsCharacteristics.encodeFitnessMachineFeature()
                UUID_SUPPORTED_SPEED_RANGE -> {
                    val (min, max) = listener.getSpeedRange()
                    FtmsCharacteristics.encodeSupportedSpeedRange(min, max, 0.1)
                }
                UUID_SUPPORTED_INCLINE_RANGE -> {
                    val (min, max) = listener.getInclineRange()
                    FtmsCharacteristics.encodeSupportedInclineRange(min, max, 0.5)
                }
                UUID_TRAINING_STATUS -> {
                    FtmsCharacteristics.encodeTrainingStatus(listener.isTreadmillRunning())
                }
                UUID_MANUFACTURER_NAME -> "tHUD".toByteArray()
                UUID_MODEL_NUMBER -> deviceName.toByteArray()
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
                    return
                }
            }

            // Handle offset for long reads
            val responseValue = if (offset < value.size) {
                value.copyOfRange(offset, value.size)
            } else {
                ByteArray(0)
            }

            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseValue)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                UUID_CONTROL_POINT -> {
                    Log.d(TAG, "Control point write from ${device.address}: ${value.joinToString(" ") { "%02X".format(it) }}")
                    val result = handleControlPointWrite(device.address, value)

                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }

                    // Send indication with result
                    val opCode = value.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                    val indication = FtmsCharacteristics.encodeControlPointResponse(opCode, result)
                    sendIndication(device, UUID_CONTROL_POINT, indication)
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
                    }
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == UUID_CCC_DESCRIPTOR) {
                val subscriptions = notificationSubscriptions[device.address]
                val charUuid = descriptor.characteristic.uuid
                val value = if (subscriptions?.contains(charUuid) == true) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == UUID_CCC_DESCRIPTOR) {
                val charUuid = descriptor.characteristic.uuid
                val subscriptions = notificationSubscriptions.getOrPut(device.address) { mutableSetOf() }

                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    subscriptions.add(charUuid)
                    Log.d(TAG, "Notifications enabled for ${charUuid} from ${device.address}")
                } else {
                    subscriptions.remove(charUuid)
                    Log.d(TAG, "Notifications disabled for ${charUuid} from ${device.address}")
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
                }
            }
        }
    }

    // ==================== Control Point Handling ====================

    private fun handleControlPointWrite(deviceAddress: String, data: ByteArray): Int {
        val command = FtmsCharacteristics.parseControlCommand(data)
        Log.d(TAG, "Parsed command: $command")

        return when (command) {
            is FtmsCharacteristics.ControlCommand.RequestControl -> {
                if (!controlAllowed) {
                    Log.d(TAG, "Control request denied - control not allowed by settings")
                    FtmsCharacteristics.RESULT_CONTROL_NOT_PERMITTED
                } else {
                    val current = controllingDevice.get()
                    if (current == null || current == deviceAddress) {
                        if (listener.onControlRequested()) {
                            controllingDevice.compareAndSet(current, deviceAddress)
                            FtmsCharacteristics.RESULT_SUCCESS
                        } else {
                            FtmsCharacteristics.RESULT_CONTROL_NOT_PERMITTED
                        }
                    } else {
                        FtmsCharacteristics.RESULT_CONTROL_NOT_PERMITTED
                    }
                }
            }

            is FtmsCharacteristics.ControlCommand.Reset -> {
                controllingDevice.set(null)
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.SetTargetSpeed -> {
                Log.d(TAG, "Set target speed: ${command.speedKph} km/h")
                listener.onSetTargetSpeed(command.speedKph)
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.SetTargetInclination -> {
                Log.d(TAG, "Set target incline: ${command.inclinePercent}%")
                listener.onSetTargetIncline(command.inclinePercent)
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.StartResume -> {
                Log.d(TAG, "Start/resume requested")
                listener.onStartResume()
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.StopPause -> {
                Log.d(TAG, "Stop/pause requested: stop=${command.stop}")
                listener.onStopPause(command.stop)
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.SetIndoorBikeSimulation -> {
                Log.d(TAG, "Simulation params: grade=${command.gradePercent}%, wind=${command.windSpeedMps}m/s")
                listener.onSimulationParameters(
                    command.gradePercent,
                    command.windSpeedMps,
                    command.crr,
                    command.cw
                )
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.SetTargetResistance,
            is FtmsCharacteristics.ControlCommand.SetTargetPower -> {
                FtmsCharacteristics.RESULT_NOT_SUPPORTED
            }

            is FtmsCharacteristics.ControlCommand.Unknown -> {
                Log.w(TAG, "Unknown control command: 0x${command.opCode.toString(16)}")
                FtmsCharacteristics.RESULT_NOT_SUPPORTED
            }

            null -> FtmsCharacteristics.RESULT_INVALID_PARAMETER
        }
    }

    // ==================== Notifications ====================

    private fun startNotificationLoop() {
        notificationJob = scope.launch {
            while (isActive && isRunning) {
                sendNotificationsToAllClients()
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
    }

    private fun sendNotificationsToAllClients() {
        if (connectedDevices.isEmpty()) return

        val speedKph = listener.getCurrentSpeedKph()
        val avgSpeedKph = listener.getAverageSpeedKph()
        val inclinePercent = listener.getCurrentInclinePercent()
        val distanceMeters = listener.getTotalDistanceMeters()
        val positiveElevation = listener.getPositiveElevationGainMeters()
        val negativeElevation = listener.getNegativeElevationGainMeters()
        val totalCalories = listener.getTotalCaloriesKcal()
        val caloriesPerHour = listener.getCaloriesPerHourKcal()
        val heartRateBpm = listener.getCurrentHeartRateBpm()
        val elapsedSeconds = listener.getElapsedTimeSeconds()

        // Encode treadmill data
        val treadmillData = FtmsCharacteristics.encodeTreadmillData(
            speedKph = speedKph,
            avgSpeedKph = avgSpeedKph,
            inclinePercent = inclinePercent,
            distanceMeters = distanceMeters,
            positiveElevationGainM = positiveElevation,
            negativeElevationGainM = negativeElevation,
            totalEnergyKcal = totalCalories,
            energyPerHourKcal = caloriesPerHour,
            heartRateBpm = heartRateBpm,
            elapsedSeconds = elapsedSeconds
        )

        // Send to all subscribed clients
        connectedDevices.forEach { (address, device) ->
            val subscriptions = notificationSubscriptions[address] ?: return@forEach

            if (subscriptions.contains(UUID_TREADMILL_DATA)) {
                sendNotification(device, UUID_TREADMILL_DATA, treadmillData)
            }

            if (heartRateBpm > 0 && subscriptions.contains(UUID_HEART_RATE_MEASUREMENT)) {
                val hrData = FtmsCharacteristics.encodeHeartRateMeasurement(heartRateBpm)
                sendNotification(device, UUID_HEART_RATE_MEASUREMENT, hrData)
            }
        }
    }

    private fun sendNotification(device: BluetoothDevice, charUuid: UUID, value: ByteArray) {
        val service = when (charUuid) {
            UUID_HEART_RATE_MEASUREMENT -> gattServer?.getService(UUID_HEART_RATE_SERVICE)
            else -> gattServer?.getService(UUID_FTMS_SERVICE)
        }
        val characteristic = service?.getCharacteristic(charUuid) ?: return

        characteristic.value = value
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
    }

    private fun sendIndication(device: BluetoothDevice, charUuid: UUID, value: ByteArray) {
        val characteristic = gattServer?.getService(UUID_FTMS_SERVICE)?.getCharacteristic(charUuid) ?: return
        characteristic.value = value
        gattServer?.notifyCharacteristicChanged(device, characteristic, true)
    }
}
