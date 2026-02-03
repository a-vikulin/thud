package io.github.avikulin.thud.service.dircon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DirCon (Direct Connect) server for Zwift and other fitness apps.
 *
 * Implements the Wahoo Direct Connect protocol which encapsulates
 * Bluetooth GATT operations over TCP/IP with mDNS discovery.
 */
class DirConServer(
    private val context: Context,
    private val listener: Listener,
    private val serviceName: String = "tHUD",
    private val controlAllowed: Boolean = true
) {
    interface Listener {
        /** Called when a client connects */
        fun onClientConnected(clientAddress: String)

        /** Called when a client disconnects */
        fun onClientDisconnected(clientAddress: String)

        /** Called when Zwift requests control of the treadmill */
        fun onControlRequested(): Boolean

        /** Called when Zwift sets target speed */
        fun onSetTargetSpeed(speedKph: Double)

        /** Called when Zwift sets target incline */
        fun onSetTargetIncline(inclinePercent: Double)

        /** Called when Zwift sends start/resume command */
        fun onStartResume()

        /** Called when Zwift sends stop/pause command */
        fun onStopPause(stop: Boolean)

        /** Called when Zwift sends simulation parameters (contains grade/incline from virtual terrain) */
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
        fun getSpeedRange(): Pair<Double, Double>  // min, max in km/h

        /** Get min/max incline range */
        fun getInclineRange(): Pair<Double, Double>  // min, max in %

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
    }

    companion object {
        private const val TAG = "DirConServer"
        private const val SERVICE_TYPE = "_wahoo-fitness-tnp._tcp."
        private const val DEFAULT_PORT = 36866
        private const val NOTIFICATION_INTERVAL_MS = 250L  // 4 Hz update rate
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val isRunning = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<String, ClientHandler>()
    private var notificationJob: Job? = null

    // Device identification
    private val macAddress: String by lazy { findMacAddress() }
    private val serialNumber: String = "TREADMILLHUD001"

    /**
     * Start the DirCon server.
     */
    fun start(): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running")
            return true
        }

        try {
            // Start TCP server
            serverSocket = ServerSocket(DEFAULT_PORT)
            Log.d(TAG, "TCP server started on port $DEFAULT_PORT")

            isRunning.set(true)

            // Accept connections in background
            scope.launch {
                acceptConnections()
            }

            // Start mDNS advertisement
            startMdnsAdvertisement()

            // Start notification loop
            startNotificationLoop()

            Log.i(TAG, "DirCon server started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
            stop()
            return false
        }
    }

    /**
     * Stop the DirCon server.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        Log.d(TAG, "Stopping DirCon server")

        // Stop notification loop
        notificationJob?.cancel()
        notificationJob = null

        // Stop mDNS
        stopMdnsAdvertisement()

        // Close all client connections
        clients.values.forEach { it.close() }
        clients.clear()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null

        Log.i(TAG, "DirCon server stopped")
    }

    /**
     * Get the number of connected clients.
     */
    fun getClientCount(): Int = clients.size

    /**
     * Check if server is running.
     */
    fun isRunning(): Boolean = isRunning.get()

    // ==================== TCP Server ====================

    private suspend fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                val clientAddress = socket.inetAddress.hostAddress ?: "unknown"
                Log.i(TAG, "Client connected: $clientAddress")

                val handler = ClientHandler(socket, this)
                clients[clientAddress] = handler

                scope.launch {
                    try {
                        listener.onClientConnected(clientAddress)
                        handler.run()
                    } finally {
                        clients.remove(clientAddress)
                        listener.onClientDisconnected(clientAddress)
                        Log.i(TAG, "Client disconnected: $clientAddress")
                    }
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection: ${e.message}")
                }
            }
        }
    }

    // ==================== mDNS Advertisement ====================

    private fun startMdnsAdvertisement() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = this@DirConServer.serviceName
                serviceType = SERVICE_TYPE
                port = DEFAULT_PORT

                // Add TXT records
                setAttribute("mac-address", macAddress)
                setAttribute("serial-number", serialNumber)

                // BLE service UUIDs (Fitness Machine + Heart Rate)
                val uuids = listOf(
                    DirConPacket.formatUuidForMdns(FtmsCharacteristics.SERVICE_FITNESS_MACHINE),
                    DirConPacket.formatUuidForMdns(FtmsCharacteristics.SERVICE_HEART_RATE)
                ).joinToString(",")
                setAttribute("ble-service-uuids", uuids)
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service registered: ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS registration failed: errorCode=$errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service unregistered")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS unregistration failed: errorCode=$errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.d(TAG, "mDNS advertisement started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS: ${e.message}", e)
        }
    }

    private fun stopMdnsAdvertisement() {
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mDNS: ${e.message}")
        }
        registrationListener = null
        nsdManager = null
    }

    // ==================== Notifications ====================

    private fun startNotificationLoop() {
        notificationJob = scope.launch {
            while (isActive && isRunning.get()) {
                sendNotificationsToAllClients()
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
    }

    private fun sendNotificationsToAllClients() {
        if (clients.isEmpty()) return

        // Get current treadmill state
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

        // Send to all clients that have enabled notifications
        clients.values.forEach { client ->
            client.sendNotificationIfEnabled(
                FtmsCharacteristics.CHAR_TREADMILL_DATA,
                treadmillData
            )

            // Also send heart rate if available
            if (heartRateBpm > 0) {
                val hrData = FtmsCharacteristics.encodeHeartRateMeasurement(heartRateBpm)
                client.sendNotificationIfEnabled(
                    FtmsCharacteristics.CHAR_HEART_RATE_MEASUREMENT,
                    hrData
                )
            }
        }
    }

    // ==================== Protocol Handling ====================

    internal fun handlePacket(packet: DirConPacket, client: ClientHandler): DirConPacket? {
        return when (packet.identifier) {
            DirConPacket.MSG_DISCOVER_SERVICES -> handleDiscoverServices(packet)
            DirConPacket.MSG_DISCOVER_CHARACTERISTICS -> handleDiscoverCharacteristics(packet)
            DirConPacket.MSG_READ_CHARACTERISTIC -> handleReadCharacteristic(packet)
            DirConPacket.MSG_WRITE_CHARACTERISTIC -> handleWriteCharacteristic(packet, client)
            DirConPacket.MSG_ENABLE_NOTIFICATIONS -> handleEnableNotifications(packet, client)
            DirConPacket.MSG_UNKNOWN_07 -> packet.createResponse(payload = ByteArray(0))
            else -> {
                Log.w(TAG, "Unknown message type: 0x${packet.identifier.toString(16)}")
                packet.createResponse(DirConPacket.RESPONSE_UNKNOWN_MESSAGE)
            }
        }
    }

    private fun handleDiscoverServices(packet: DirConPacket): DirConPacket {
        // Return available services: Fitness Machine, Heart Rate, Device Info
        val services = listOf(
            FtmsCharacteristics.SERVICE_FITNESS_MACHINE,
            FtmsCharacteristics.SERVICE_HEART_RATE,
            FtmsCharacteristics.SERVICE_DEVICE_INFO
        )

        val payload = services.flatMap { DirConPacket.encodeUuid16(it).toList() }.toByteArray()
        return packet.createResponse(payload = payload)
    }

    private fun handleDiscoverCharacteristics(packet: DirConPacket): DirConPacket {
        if (packet.payload.size < 16) {
            return packet.createResponse(DirConPacket.RESPONSE_SERVICE_NOT_FOUND)
        }

        val serviceUuid = DirConPacket.decodeUuid16(packet.payload)
        val characteristics = when (serviceUuid) {
            FtmsCharacteristics.SERVICE_FITNESS_MACHINE -> FtmsCharacteristics.getFitnessMachineCharacteristics()
            FtmsCharacteristics.SERVICE_HEART_RATE -> FtmsCharacteristics.getHeartRateCharacteristics()
            FtmsCharacteristics.SERVICE_DEVICE_INFO -> FtmsCharacteristics.getDeviceInfoCharacteristics()
            else -> {
                Log.w(TAG, "Unknown service UUID: 0x${serviceUuid.toString(16)}")
                return packet.createResponse(DirConPacket.RESPONSE_SERVICE_NOT_FOUND)
            }
        }

        // Build response: service UUID + (characteristic UUID + properties) for each
        val responsePayload = mutableListOf<Byte>()

        // Echo service UUID
        responsePayload.addAll(packet.payload.take(16))

        // Add each characteristic
        characteristics.forEach { char ->
            responsePayload.addAll(DirConPacket.encodeUuid16(char.uuid).toList())
            responsePayload.add(char.properties.toByte())
        }

        return packet.createResponse(payload = responsePayload.toByteArray())
    }

    private fun handleReadCharacteristic(packet: DirConPacket): DirConPacket {
        if (packet.payload.size < 16) {
            return packet.createResponse(DirConPacket.RESPONSE_CHARACTERISTIC_NOT_FOUND)
        }

        val charUuid = DirConPacket.decodeUuid16(packet.payload)
        val data = when (charUuid) {
            FtmsCharacteristics.CHAR_FITNESS_MACHINE_FEATURE ->
                FtmsCharacteristics.encodeFitnessMachineFeature()

            FtmsCharacteristics.CHAR_SUPPORTED_SPEED_RANGE -> {
                val (min, max) = listener.getSpeedRange()
                FtmsCharacteristics.encodeSupportedSpeedRange(min, max, 0.1)
            }

            FtmsCharacteristics.CHAR_SUPPORTED_INCLINE_RANGE -> {
                val (min, max) = listener.getInclineRange()
                FtmsCharacteristics.encodeSupportedInclineRange(min, max, 0.5)
            }

            FtmsCharacteristics.CHAR_TRAINING_STATUS ->
                FtmsCharacteristics.encodeTrainingStatus(listener.isTreadmillRunning())

            FtmsCharacteristics.CHAR_MANUFACTURER_NAME -> "tHUD".toByteArray()
            FtmsCharacteristics.CHAR_MODEL_NUMBER -> "v1.0".toByteArray()
            FtmsCharacteristics.CHAR_SERIAL_NUMBER -> serialNumber.toByteArray()
            FtmsCharacteristics.CHAR_FIRMWARE_REVISION -> "1.0.0".toByteArray()

            else -> {
                Log.w(TAG, "Read unsupported characteristic: 0x${charUuid.toString(16)}")
                return packet.createResponse(DirConPacket.RESPONSE_CHARACTERISTIC_NOT_FOUND)
            }
        }

        // Response: characteristic UUID + data
        val responsePayload = DirConPacket.encodeUuid16(charUuid) + data
        return packet.createResponse(payload = responsePayload)
    }

    private fun handleWriteCharacteristic(packet: DirConPacket, client: ClientHandler): DirConPacket {
        if (packet.payload.size < 17) {  // 16 byte UUID + at least 1 byte data
            return packet.createResponse(DirConPacket.RESPONSE_CHARACTERISTIC_NOT_FOUND)
        }

        val charUuid = DirConPacket.decodeUuid16(packet.payload)
        val data = packet.payload.copyOfRange(16, packet.payload.size)

        when (charUuid) {
            FtmsCharacteristics.CHAR_FITNESS_MACHINE_CONTROL_POINT -> {
                Log.d(TAG, "Control point write: ${data.joinToString(" ") { "%02X".format(it) }}")
                val command = FtmsCharacteristics.parseControlCommand(data)
                Log.d(TAG, "Parsed command: $command")
                val result = handleControlCommand(command, client)

                // Send indication response
                val opCode = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                val indication = FtmsCharacteristics.encodeControlPointResponse(opCode, result)
                client.sendNotificationIfEnabled(charUuid, indication)
            }

            else -> {
                Log.w(TAG, "Write to unsupported characteristic: 0x${charUuid.toString(16)}")
                return packet.createResponse(DirConPacket.RESPONSE_CHARACTERISTIC_NOT_FOUND)
            }
        }

        // Response: characteristic UUID + success byte
        val responsePayload = DirConPacket.encodeUuid16(charUuid) + byteArrayOf(0x01)
        return packet.createResponse(payload = responsePayload)
    }

    private fun handleControlCommand(command: FtmsCharacteristics.ControlCommand?, client: ClientHandler): Int {
        if (command == null) {
            return FtmsCharacteristics.RESULT_INVALID_PARAMETER
        }

        return when (command) {
            is FtmsCharacteristics.ControlCommand.RequestControl -> {
                if (!controlAllowed) {
                    Log.d(TAG, "Control request denied - control not allowed by settings")
                    FtmsCharacteristics.RESULT_CONTROL_NOT_PERMITTED
                } else if (listener.onControlRequested()) {
                    client.hasControl = true
                    FtmsCharacteristics.RESULT_SUCCESS
                } else {
                    FtmsCharacteristics.RESULT_CONTROL_NOT_PERMITTED
                }
            }

            is FtmsCharacteristics.ControlCommand.Reset -> {
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.SetTargetSpeed -> {
                Log.d(TAG, "Zwift set target speed: ${command.speedKph} km/h")
                listener.onSetTargetSpeed(command.speedKph)
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.SetTargetInclination -> {
                Log.d(TAG, "Zwift set target incline: ${command.inclinePercent}%")
                listener.onSetTargetIncline(command.inclinePercent)
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.StartResume -> {
                Log.d(TAG, "Zwift sent start/resume")
                listener.onStartResume()
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.StopPause -> {
                Log.d(TAG, "Zwift sent stop/pause (stop=${command.stop})")
                listener.onStopPause(command.stop)
                FtmsCharacteristics.RESULT_SUCCESS
            }

            is FtmsCharacteristics.ControlCommand.SetIndoorBikeSimulation -> {
                // This contains the virtual terrain grade from Zwift!
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
                // Not applicable for treadmill
                FtmsCharacteristics.RESULT_NOT_SUPPORTED
            }

            is FtmsCharacteristics.ControlCommand.Unknown -> {
                Log.w(TAG, "Unknown control command: 0x${command.opCode.toString(16)}")
                FtmsCharacteristics.RESULT_NOT_SUPPORTED
            }
        }
    }

    private fun handleEnableNotifications(packet: DirConPacket, client: ClientHandler): DirConPacket {
        if (packet.payload.size < 17) {
            return packet.createResponse(DirConPacket.RESPONSE_CHARACTERISTIC_NOT_FOUND)
        }

        val charUuid = DirConPacket.decodeUuid16(packet.payload)
        val enable = packet.payload[16].toInt() and 0xFF == 0x01

        if (enable) {
            client.enabledNotifications.add(charUuid)
            Log.d(TAG, "Notifications enabled for 0x${charUuid.toString(16)}")
        } else {
            client.enabledNotifications.remove(charUuid)
            Log.d(TAG, "Notifications disabled for 0x${charUuid.toString(16)}")
        }

        // Response: characteristic UUID + status
        val responsePayload = DirConPacket.encodeUuid16(charUuid) + byteArrayOf(0x01)
        return packet.createResponse(payload = responsePayload)
    }

    // ==================== Utilities ====================

    private fun findMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val mac = networkInterface.hardwareAddress
                if (mac != null && mac.size == 6 && !networkInterface.isLoopback) {
                    return mac.joinToString(":") { "%02X".format(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get MAC address: ${e.message}")
        }
        // Fallback MAC address
        return "00:11:22:33:44:55"
    }

    /**
     * Get the device's WiFi IP address for display/logging.
     */
    fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi IP: ${e.message}")
        }
        return null
    }
}
