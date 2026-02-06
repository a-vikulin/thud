package io.github.avikulin.thud.service.dircon

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles a single DirCon client connection.
 */
class ClientHandler(
    private val socket: Socket,
    private val server: DirConServer
) {
    companion object {
        private const val TAG = "DirConClient"
        private const val BUFFER_SIZE = 4096
    }

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val clientAddress: String = socket.inetAddress.hostAddress ?: "unknown"

    /** Characteristics with notifications enabled */
    val enabledNotifications = ConcurrentHashMap.newKeySet<Int>()

    /** Whether this client has control permission */
    @Volatile var hasControl = false

    private var isClosed = false

    /**
     * Run the client handler loop.
     * Reads packets and dispatches to server for handling.
     */
    suspend fun run() {
        val buffer = ByteArray(BUFFER_SIZE)
        val pendingData = mutableListOf<Byte>()

        try {
            while (!isClosed && !socket.isClosed) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break

                // Add to pending data
                pendingData.addAll(buffer.take(bytesRead))

                // Process complete packets
                while (pendingData.size >= DirConPacket.HEADER_SIZE) {
                    val data = pendingData.toByteArray()
                    val packet = DirConPacket.parse(data)

                    if (packet == null) {
                        // Incomplete packet, wait for more data
                        break
                    }

                    // Remove processed bytes
                    repeat(packet.totalSize) {
                        pendingData.removeAt(0)
                    }

                    // Handle packet - log at debug level to see what Zwift sends
                    Log.d(TAG, "[$clientAddress] Received: $packet")
                    val response = server.handlePacket(packet, this)

                    if (response != null) {
                        sendPacket(response)
                    }
                }
            }
        } catch (e: IOException) {
            if (!isClosed) {
                Log.d(TAG, "[$clientAddress] Connection error: ${e.message}")
            }
        } finally {
            close()
        }
    }

    /**
     * Send a packet to the client.
     */
    @Synchronized
    fun sendPacket(packet: DirConPacket) {
        if (isClosed || socket.isClosed) return

        try {
            val data = packet.encode()
            output.write(data)
            output.flush()
            // Only log non-notification packets to avoid flooding logcat
            if (packet.identifier != DirConPacket.MSG_UNSOLICITED_NOTIFICATION) {
                Log.d(TAG, "[$clientAddress] Sent: $packet")
            }
        } catch (e: IOException) {
            Log.w(TAG, "[$clientAddress] Failed to send packet: ${e.message}")
        }
    }

    /**
     * Send a notification if the client has enabled it for this characteristic.
     */
    fun sendNotificationIfEnabled(characteristicUuid: Int, data: ByteArray) {
        if (!enabledNotifications.contains(characteristicUuid)) return

        // Build notification packet
        val payload = DirConPacket.encodeUuid16(characteristicUuid) + data

        val packet = DirConPacket(
            identifier = DirConPacket.MSG_UNSOLICITED_NOTIFICATION,
            sequenceNumber = 0,  // Notifications use sequence 0
            responseCode = DirConPacket.RESPONSE_SUCCESS,
            payload = payload
        )

        sendPacket(packet)
    }

    /**
     * Close the client connection.
     */
    fun close() {
        if (isClosed) return
        isClosed = true

        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
