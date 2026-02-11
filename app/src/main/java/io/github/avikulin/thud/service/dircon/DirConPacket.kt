package io.github.avikulin.thud.service.dircon

/**
 * DirCon packet structure for GATT-over-TCP protocol.
 *
 * Header format (6 bytes):
 * - Byte 0: Message Version (always 0x01)
 * - Byte 1: Message Identifier (command type)
 * - Byte 2: Sequence Number
 * - Byte 3: Response Code (0x00 = success)
 * - Bytes 4-5: Payload Length (big-endian)
 */
data class DirConPacket(
    val messageVersion: Int = MESSAGE_VERSION,
    val identifier: Int,
    val sequenceNumber: Int = 0,
    val responseCode: Int = RESPONSE_SUCCESS,
    val payload: ByteArray = ByteArray(0)
) {
    companion object {
        const val HEADER_SIZE = 6
        const val MESSAGE_VERSION = 0x01

        // Message types
        const val MSG_DISCOVER_SERVICES = 0x01
        const val MSG_DISCOVER_CHARACTERISTICS = 0x02
        const val MSG_READ_CHARACTERISTIC = 0x03
        const val MSG_WRITE_CHARACTERISTIC = 0x04
        const val MSG_ENABLE_NOTIFICATIONS = 0x05
        const val MSG_UNSOLICITED_NOTIFICATION = 0x06
        const val MSG_UNKNOWN_07 = 0x07
        const val MSG_ERROR = 0xFF

        // Response codes
        const val RESPONSE_SUCCESS = 0x00
        const val RESPONSE_UNKNOWN_MESSAGE = 0x01
        const val RESPONSE_UNEXPECTED_ERROR = 0x02
        const val RESPONSE_SERVICE_NOT_FOUND = 0x03
        const val RESPONSE_CHARACTERISTIC_NOT_FOUND = 0x04
        const val RESPONSE_NOT_SUPPORTED = 0x05
        const val RESPONSE_WRITE_FAILED = 0x06
        const val RESPONSE_UNKNOWN_PROTOCOL = 0x07

        // Characteristic property flags
        const val PROP_READ = 0x01
        const val PROP_WRITE = 0x02
        const val PROP_NOTIFY = 0x04
        const val PROP_INDICATE = 0x08

        // Standard BLE base UUID: 0000xxxx-0000-1000-8000-00805F9B34FB
        private val BLE_BASE_UUID_SUFFIX = byteArrayOf(
            0x00, 0x00, 0x10, 0x00.toByte(),
            0x80.toByte(), 0x00, 0x00, 0x80.toByte(),
            0x5F, 0x9B.toByte(), 0x34, 0xFB.toByte()
        )

        /**
         * Parse a packet from raw bytes.
         * Returns null if data is incomplete.
         */
        fun parse(data: ByteArray, offset: Int = 0): DirConPacket? {
            if (data.size - offset < HEADER_SIZE) return null

            val version = data[offset].toInt() and 0xFF
            val identifier = data[offset + 1].toInt() and 0xFF
            val seqNum = data[offset + 2].toInt() and 0xFF
            val respCode = data[offset + 3].toInt() and 0xFF
            val length = ((data[offset + 4].toInt() and 0xFF) shl 8) or
                         (data[offset + 5].toInt() and 0xFF)

            if (data.size - offset < HEADER_SIZE + length) return null

            val payload = if (length > 0) {
                data.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + length)
            } else {
                ByteArray(0)
            }

            return DirConPacket(version, identifier, seqNum, respCode, payload)
        }

        /**
         * Encode a 16-bit BLE UUID to full 128-bit format (16 bytes).
         */
        fun encodeUuid16(uuid16: Int): ByteArray {
            val result = ByteArray(16)
            result[0] = 0x00
            result[1] = 0x00
            result[2] = ((uuid16 shr 8) and 0xFF).toByte()
            result[3] = (uuid16 and 0xFF).toByte()
            System.arraycopy(BLE_BASE_UUID_SUFFIX, 0, result, 4, 12)
            return result
        }

        /**
         * Extract 16-bit UUID from a 16-byte encoded UUID.
         */
        fun decodeUuid16(encoded: ByteArray, offset: Int = 0): Int {
            return ((encoded[offset + 2].toInt() and 0xFF) shl 8) or
                   (encoded[offset + 3].toInt() and 0xFF)
        }

        /**
         * Format UUID for mDNS TXT record (full 128-bit string format).
         */
        fun formatUuidForMdns(uuid16: Int): String {
            return String.format(
                "%08X-0000-1000-8000-00805F9B34FB",
                uuid16
            )
        }
    }

    /**
     * Total packet size including header.
     */
    val totalSize: Int get() = HEADER_SIZE + payload.size

    /**
     * Encode packet to bytes for transmission.
     */
    fun encode(): ByteArray {
        val data = ByteArray(HEADER_SIZE + payload.size)
        data[0] = messageVersion.toByte()
        data[1] = identifier.toByte()
        data[2] = sequenceNumber.toByte()
        data[3] = responseCode.toByte()
        data[4] = (payload.size shr 8 and 0xFF).toByte()
        data[5] = (payload.size and 0xFF).toByte()
        payload.copyInto(data, HEADER_SIZE)
        return data
    }

    /**
     * Create a response packet for this request.
     */
    fun createResponse(responseCode: Int = RESPONSE_SUCCESS, payload: ByteArray = ByteArray(0)): DirConPacket {
        return DirConPacket(
            messageVersion = MESSAGE_VERSION,
            identifier = this.identifier,
            sequenceNumber = this.sequenceNumber,
            responseCode = responseCode,
            payload = payload
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DirConPacket
        return messageVersion == other.messageVersion &&
               identifier == other.identifier &&
               sequenceNumber == other.sequenceNumber &&
               responseCode == other.responseCode &&
               payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageVersion
        result = 31 * result + identifier
        result = 31 * result + sequenceNumber
        result = 31 * result + responseCode
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "DirConPacket(id=0x${identifier.toString(16)}, seq=$sequenceNumber, " +
               "resp=0x${responseCode.toString(16)}, payload=${payload.size} bytes)"
    }
}
