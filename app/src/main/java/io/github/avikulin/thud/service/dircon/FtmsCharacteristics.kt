package io.github.avikulin.thud.service.dircon

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FTMS (Fitness Machine Service) characteristic definitions and data encoding.
 */
object FtmsCharacteristics {
    private const val TAG = "FtmsCharacteristics"

    // Service UUIDs
    const val SERVICE_FITNESS_MACHINE = 0x1826
    const val SERVICE_HEART_RATE = 0x180D
    const val SERVICE_DEVICE_INFO = 0x180A

    // Fitness Machine Service Characteristics
    const val CHAR_TREADMILL_DATA = 0x2ACD
    const val CHAR_INDOOR_BIKE_DATA = 0x2AD2
    const val CHAR_FITNESS_MACHINE_FEATURE = 0x2ACC
    const val CHAR_FITNESS_MACHINE_CONTROL_POINT = 0x2AD9
    const val CHAR_FITNESS_MACHINE_STATUS = 0x2ADA
    const val CHAR_SUPPORTED_SPEED_RANGE = 0x2AD4
    const val CHAR_SUPPORTED_INCLINE_RANGE = 0x2AD5
    const val CHAR_SUPPORTED_RESISTANCE_RANGE = 0x2AD6
    const val CHAR_SUPPORTED_POWER_RANGE = 0x2AD8
    const val CHAR_TRAINING_STATUS = 0x2AD3

    // Heart Rate Service Characteristics
    const val CHAR_HEART_RATE_MEASUREMENT = 0x2A37

    // Device Info Characteristics
    const val CHAR_MANUFACTURER_NAME = 0x2A29
    const val CHAR_MODEL_NUMBER = 0x2A24
    const val CHAR_SERIAL_NUMBER = 0x2A25
    const val CHAR_FIRMWARE_REVISION = 0x2A26

    // FTMS Control Point Commands
    const val CMD_REQUEST_CONTROL = 0x00
    const val CMD_RESET = 0x01
    const val CMD_SET_TARGET_SPEED = 0x02
    const val CMD_SET_TARGET_INCLINATION = 0x03
    const val CMD_SET_TARGET_RESISTANCE = 0x04
    const val CMD_SET_TARGET_POWER = 0x05
    const val CMD_SET_TARGET_HR = 0x06
    const val CMD_START_RESUME = 0x07
    const val CMD_STOP_PAUSE = 0x08
    const val CMD_SET_INDOOR_BIKE_SIMULATION = 0x11
    const val CMD_RESPONSE_CODE = 0x80

    // Control Point Response Results
    const val RESULT_SUCCESS = 0x01
    const val RESULT_NOT_SUPPORTED = 0x02
    const val RESULT_INVALID_PARAMETER = 0x03
    const val RESULT_OPERATION_FAILED = 0x04
    const val RESULT_CONTROL_NOT_PERMITTED = 0x05

    /**
     * Characteristic info for discovery response.
     */
    data class CharacteristicInfo(
        val uuid: Int,
        val properties: Int
    )

    /**
     * Get characteristics for Fitness Machine Service.
     */
    fun getFitnessMachineCharacteristics(): List<CharacteristicInfo> = listOf(
        CharacteristicInfo(CHAR_FITNESS_MACHINE_FEATURE, DirConPacket.PROP_READ),
        CharacteristicInfo(CHAR_TREADMILL_DATA, DirConPacket.PROP_NOTIFY),
        CharacteristicInfo(CHAR_FITNESS_MACHINE_CONTROL_POINT, DirConPacket.PROP_WRITE or DirConPacket.PROP_INDICATE),
        CharacteristicInfo(CHAR_FITNESS_MACHINE_STATUS, DirConPacket.PROP_NOTIFY),
        CharacteristicInfo(CHAR_SUPPORTED_SPEED_RANGE, DirConPacket.PROP_READ),
        CharacteristicInfo(CHAR_SUPPORTED_INCLINE_RANGE, DirConPacket.PROP_READ),
        CharacteristicInfo(CHAR_TRAINING_STATUS, DirConPacket.PROP_READ or DirConPacket.PROP_NOTIFY)
    )

    /**
     * Get characteristics for Heart Rate Service.
     */
    fun getHeartRateCharacteristics(): List<CharacteristicInfo> = listOf(
        CharacteristicInfo(CHAR_HEART_RATE_MEASUREMENT, DirConPacket.PROP_NOTIFY)
    )

    /**
     * Get characteristics for Device Info Service.
     */
    fun getDeviceInfoCharacteristics(): List<CharacteristicInfo> = listOf(
        CharacteristicInfo(CHAR_MANUFACTURER_NAME, DirConPacket.PROP_READ),
        CharacteristicInfo(CHAR_MODEL_NUMBER, DirConPacket.PROP_READ),
        CharacteristicInfo(CHAR_SERIAL_NUMBER, DirConPacket.PROP_READ),
        CharacteristicInfo(CHAR_FIRMWARE_REVISION, DirConPacket.PROP_READ)
    )

    // ==================== Data Encoding ====================

    /**
     * Encode treadmill data notification.
     *
     * @param speedKph Current speed in km/h
     * @param avgSpeedKph Average speed in km/h (0 to omit)
     * @param inclinePercent Current incline in %
     * @param distanceMeters Total distance in meters
     * @param positiveElevationGainM Positive elevation gain in meters (0 to omit)
     * @param negativeElevationGainM Negative elevation gain in meters (0 to omit)
     * @param totalEnergyKcal Total energy expended in kcal (0 to omit)
     * @param energyPerHourKcal Energy per hour in kcal (0 to omit)
     * @param heartRateBpm Heart rate in BPM (0 to omit)
     * @param elapsedSeconds Elapsed time in seconds
     */
    fun encodeTreadmillData(
        speedKph: Double,
        avgSpeedKph: Double = 0.0,
        inclinePercent: Double,
        distanceMeters: Double,
        positiveElevationGainM: Double = 0.0,
        negativeElevationGainM: Double = 0.0,
        totalEnergyKcal: Double = 0.0,
        energyPerHourKcal: Double = 0.0,
        heartRateBpm: Int = 0,
        elapsedSeconds: Int
    ): ByteArray {
        // FTMS Treadmill Data characteristic (0x2ACD)
        //
        // Flags (per Bluetooth GATT Specification Supplement):
        // Bit 0 = More Data (0 = no more data, speed is present)
        // Bit 1 = Average Speed Present
        // Bit 2 = Total Distance Present
        // Bit 3 = Inclination and Ramp Angle Setting Present
        // Bit 8 = Heart Rate Present
        // Bit 10 = Elapsed Time Present
        //
        // Field order follows bit position (lowest first)
        //
        // NOTE: We match QZ's implementation exactly for Kinni compatibility:
        // Only bits 2, 3, 8, 10 (no elevation/energy which adds extra bytes)
        //
        var flags = 0x0000

        val hasHeartRate = heartRateBpm > 0

        flags = flags or 0x0004                      // Bit 2: Total Distance
        flags = flags or 0x0008                      // Bit 3: Inclination + Ramp Angle
        if (hasHeartRate) flags = flags or 0x0100   // Bit 8: Heart Rate
        flags = flags or 0x0400                      // Bit 10: Elapsed Time

        // Calculate buffer size (matching QZ exactly):
        // flags(2) + speed(2) + distance(3) + incline(2) + rampAngle(2) + [hr(1)] + elapsed(2)
        // Total: 13 bytes without HR, 14 bytes with HR
        val bufferSize = 2 + 2 + 3 + 4 + (if (hasHeartRate) 1 else 0) + 2
        val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

        // Flags (2 bytes)
        buffer.putShort(flags.toShort())

        // Instantaneous Speed (0.01 km/h units, uint16) - always present when bit 0 = 0
        val speedCentKph = (speedKph * 100).toInt().coerceIn(0, 65535)
        buffer.putShort(speedCentKph.toShort())

        // Total Distance (meters, uint24 - 3 bytes) - bit 2
        val distanceM = distanceMeters.toInt().coerceIn(0, 0xFFFFFF)
        buffer.put((distanceM and 0xFF).toByte())
        buffer.put(((distanceM shr 8) and 0xFF).toByte())
        buffer.put(((distanceM shr 16) and 0xFF).toByte())

        // Inclination (0.1% units, sint16) + Ramp Angle (0.1 degrees, sint16) - bit 3
        val inclineTenths = (inclinePercent * 10).toInt().coerceIn(-32768, 32767)
        buffer.putShort(inclineTenths.toShort())
        // Ramp angle - calculate from incline: angle = atan(incline/100) in degrees
        val rampAngleDegrees = Math.toDegrees(Math.atan(inclinePercent / 100.0))
        val rampAngleTenths = (rampAngleDegrees * 10).toInt().coerceIn(-32768, 32767)
        buffer.putShort(rampAngleTenths.toShort())

        // Heart Rate (uint8) - bit 8
        if (hasHeartRate) {
            buffer.put(heartRateBpm.coerceIn(0, 255).toByte())
        }

        // Elapsed Time (seconds, uint16) - bit 10
        val elapsed = elapsedSeconds.coerceIn(0, 65535)
        buffer.putShort(elapsed.toShort())

        // Return only used portion
        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)

        return result
    }

    /**
     * Encode heart rate measurement notification.
     */
    fun encodeHeartRateMeasurement(heartRateBpm: Int): ByteArray {
        // Flags: bit 0 = 0 means HR is uint8
        return byteArrayOf(
            0x00,  // Flags
            heartRateBpm.coerceIn(0, 255).toByte()
        )
    }

    /**
     * Encode Fitness Machine Feature characteristic (static).
     * Indicates what features the treadmill supports.
     * NOTE: Must match the fields we actually send in encodeTreadmillData()
     */
    fun encodeFitnessMachineFeature(): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        // Fitness Machine Features (4 bytes) - matching QZ for Kinni compatibility
        // Bit 2 = Total Distance Supported
        // Bit 3 = Inclination Supported
        // Bit 8 = Heart Rate Measurement Supported
        // Bit 10 = Elapsed Time Supported
        var features = 0
        features = features or 0x0004  // Bit 2: Total distance supported
        features = features or 0x0008  // Bit 3: Inclination supported
        features = features or 0x0100  // Bit 8: Heart rate measurement supported
        features = features or 0x0400  // Bit 10: Elapsed time supported
        buffer.putInt(features)

        // Target Setting Features (4 bytes)
        var targetFeatures = 0
        targetFeatures = targetFeatures or 0x0001  // Speed target supported
        targetFeatures = targetFeatures or 0x0002  // Incline target supported
        buffer.putInt(targetFeatures)

        return buffer.array()
    }

    /**
     * Encode supported speed range characteristic.
     */
    fun encodeSupportedSpeedRange(minKph: Double, maxKph: Double, incrementKph: Double): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort((minKph * 100).toInt().toShort())
        buffer.putShort((maxKph * 100).toInt().toShort())
        buffer.putShort((incrementKph * 100).toInt().toShort())
        return buffer.array()
    }

    /**
     * Encode supported incline range characteristic.
     */
    fun encodeSupportedInclineRange(minPercent: Double, maxPercent: Double, incrementPercent: Double): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort((minPercent * 10).toInt().toShort())
        buffer.putShort((maxPercent * 10).toInt().toShort())
        buffer.putShort((incrementPercent * 10).toInt().toShort())
        return buffer.array()
    }

    /**
     * Encode training status characteristic.
     */
    fun encodeTrainingStatus(isRunning: Boolean): ByteArray {
        // Flags: 0x00 = no string, Status: 0x01 = idle, 0x0D = running
        return byteArrayOf(
            0x00,  // Flags (no extended string)
            if (isRunning) 0x0D else 0x01  // Training status
        )
    }

    /**
     * Encode control point response.
     */
    fun encodeControlPointResponse(requestOpCode: Int, result: Int): ByteArray {
        return byteArrayOf(
            CMD_RESPONSE_CODE.toByte(),
            requestOpCode.toByte(),
            result.toByte()
        )
    }

    // ==================== Command Decoding ====================

    /**
     * Parsed control point command.
     */
    sealed class ControlCommand {
        object RequestControl : ControlCommand()
        object Reset : ControlCommand()
        data class SetTargetSpeed(val speedKph: Double) : ControlCommand()
        data class SetTargetInclination(val inclinePercent: Double) : ControlCommand()
        data class SetTargetResistance(val resistance: Double) : ControlCommand()
        data class SetTargetPower(val watts: Int) : ControlCommand()
        object StartResume : ControlCommand()
        data class StopPause(val stop: Boolean) : ControlCommand()
        /** Indoor Bike Simulation parameters - grade is the key value for treadmill incline */
        data class SetIndoorBikeSimulation(
            val windSpeedMps: Double,      // Wind speed in m/s
            val gradePercent: Double,       // Grade/incline in %
            val crr: Double,                // Rolling resistance coefficient
            val cw: Double                  // Wind resistance coefficient kg/m
        ) : ControlCommand()
        data class Unknown(val opCode: Int, val data: ByteArray) : ControlCommand()
    }

    /**
     * Parse a control point command.
     */
    fun parseControlCommand(data: ByteArray): ControlCommand? {
        if (data.isEmpty()) return null

        Log.d(TAG, "Parsing control command: ${data.joinToString(" ") { "%02X".format(it) }}")

        val opCode = data[0].toInt() and 0xFF
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get()  // Skip opcode

        val command: ControlCommand? = when (opCode) {
            CMD_REQUEST_CONTROL -> ControlCommand.RequestControl
            CMD_RESET -> ControlCommand.Reset
            CMD_SET_TARGET_SPEED -> {
                if (data.size < 3) null
                else {
                    val speedCentKph = buffer.short.toInt() and 0xFFFF
                    ControlCommand.SetTargetSpeed(speedCentKph / 100.0)
                }
            }
            CMD_SET_TARGET_INCLINATION -> {
                if (data.size < 3) null
                else {
                    val inclineTenths = buffer.short.toInt()
                    ControlCommand.SetTargetInclination(inclineTenths / 10.0)
                }
            }
            CMD_SET_TARGET_RESISTANCE -> {
                if (data.size < 2) null
                else {
                    val resistance = buffer.get().toInt() and 0xFF
                    ControlCommand.SetTargetResistance(resistance / 10.0)
                }
            }
            CMD_SET_TARGET_POWER -> {
                if (data.size < 3) null
                else {
                    val watts = buffer.short.toInt() and 0xFFFF
                    ControlCommand.SetTargetPower(watts)
                }
            }
            CMD_START_RESUME -> ControlCommand.StartResume
            CMD_STOP_PAUSE -> {
                val stop = if (data.size > 1) (data[1].toInt() and 0xFF) == 0x01 else true
                ControlCommand.StopPause(stop)
            }
            CMD_SET_INDOOR_BIKE_SIMULATION -> {
                // Format: windSpeed(sint16, 0.001 m/s), grade(sint16, 0.01%), crr(uint8, 0.0001), cw(uint8, 0.01 kg/m)
                if (data.size < 7) null
                else {
                    val windSpeed = buffer.short.toInt() * 0.001  // m/s
                    val grade = buffer.short.toInt() * 0.01       // percent
                    val crr = (buffer.get().toInt() and 0xFF) * 0.0001
                    val cw = (buffer.get().toInt() and 0xFF) * 0.01
                    ControlCommand.SetIndoorBikeSimulation(windSpeed, grade, crr, cw)
                }
            }
            else -> ControlCommand.Unknown(opCode, data.copyOfRange(1, data.size))
        }

        Log.d(TAG, "Parsed command: $command")
        return command
    }

    // ==================== RSC and Power Encoding (Stryd Re-broadcast) ====================

    /**
     * Encode RSC (Running Speed and Cadence) Measurement characteristic.
     * Used to re-broadcast Stryd cadence and speed data.
     *
     * @param speedKph Speed in km/h
     * @param cadenceSpm Cadence in strides per minute (one foot contact)
     * @param strideLength Stride length in meters (0 if not available)
     */
    fun encodeRscMeasurement(
        speedKph: Double,
        cadenceSpm: Int,
        strideLength: Double = 0.0
    ): ByteArray {
        // RSC Measurement format (per BLE spec):
        // Flags (1 byte):
        //   Bit 0: Instantaneous Stride Length Present
        //   Bit 1: Total Distance Present
        //   Bit 2: Walking or Running Status (0 = walking, 1 = running)
        // Speed (2 bytes): uint16, 1/256 m/s units
        // Cadence (1 byte): uint8, strides/min
        // Stride Length (2 bytes, optional): uint16, 1/100 m units
        // Total Distance (4 bytes, optional): uint32, 1/10 m units

        val hasStrideLength = strideLength > 0
        var flags = 0x04  // Bit 2 = running
        if (hasStrideLength) {
            flags = flags or 0x01  // Bit 0 = stride length present
        }

        val bufferSize = 1 + 2 + 1 + (if (hasStrideLength) 2 else 0)
        val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

        // Flags
        buffer.put(flags.toByte())

        // Instantaneous Speed (1/256 m/s units)
        val speedMs = speedKph / 3.6
        val speedRaw = (speedMs * 256).toInt().coerceIn(0, 65535)
        buffer.putShort(speedRaw.toShort())

        // Instantaneous Cadence (strides per minute)
        buffer.put(cadenceSpm.coerceIn(0, 255).toByte())

        // Stride Length (1/100 m units) - optional
        if (hasStrideLength) {
            val strideLengthCm = (strideLength * 100).toInt().coerceIn(0, 65535)
            buffer.putShort(strideLengthCm.toShort())
        }

        return buffer.array()
    }

    /**
     * Encode RSC Feature characteristic (static).
     */
    fun encodeRscFeature(): ByteArray {
        // RSC Feature (2 bytes):
        // Bit 0: Instantaneous Stride Length Measurement Supported
        // Bit 1: Total Distance Measurement Supported
        // Bit 2: Walking or Running Status Supported
        // Bit 3: Calibration Procedure Supported
        // Bit 4: Multiple Sensor Locations Supported
        val features = 0x0004  // Running status supported
        val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(features.toShort())
        return buffer.array()
    }

    /**
     * Encode Cycling Power Measurement characteristic.
     * Used to re-broadcast Stryd running power data.
     *
     * @param powerWatts Power in watts
     */
    fun encodeCyclingPowerMeasurement(powerWatts: Int): ByteArray {
        // Cycling Power Measurement format (per BLE spec):
        // Flags (2 bytes): indicate which optional fields are present
        //   Bit 0: Pedal Power Balance Present
        //   Bit 1: Pedal Power Balance Reference
        //   Bit 2: Accumulated Torque Present
        //   ... many more optional fields
        // Instantaneous Power (2 bytes): sint16, watts
        // Optional fields follow based on flags

        // We only need the basic power measurement, no optional fields
        val flags = 0x0000

        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        // Flags
        buffer.putShort(flags.toShort())

        // Instantaneous Power (watts)
        buffer.putShort(powerWatts.coerceIn(-32768, 32767).toShort())

        return buffer.array()
    }

    /**
     * Encode Cycling Power Feature characteristic (static).
     */
    fun encodeCyclingPowerFeature(): ByteArray {
        // Cycling Power Feature (4 bytes):
        // Minimal feature set - just instantaneous power
        val features = 0x00000000
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(features)
        return buffer.array()
    }
}
