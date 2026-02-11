package io.github.avikulin.thud.service

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Type of Bluetooth sensor device.
 */
enum class SensorDeviceType {
    HR_SENSOR,
    FOOT_POD
}

/**
 * Represents a saved Bluetooth device with MAC address, display name, and type.
 */
data class SavedBluetoothDevice(
    val mac: String,
    val name: String,
    val type: SensorDeviceType
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mac", mac)
        put("name", name)
        put("type", type.name)
    }

    companion object {
        fun fromJson(json: JSONObject): SavedBluetoothDevice {
            return SavedBluetoothDevice(
                mac = json.getString("mac"),
                name = json.getString("name"),
                type = SensorDeviceType.valueOf(json.getString("type"))
            )
        }
    }
}

/**
 * Utility for managing the unified list of saved Bluetooth devices in SharedPreferences.
 * All devices (HR sensors and foot pods) are stored together as a JSON array string.
 */
object SavedBluetoothDevices {
    const val PREF_KEY = "saved_bt_devices"

    /**
     * Get all saved devices.
     */
    fun getAll(prefs: SharedPreferences): List<SavedBluetoothDevice> {
        val jsonString = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    SavedBluetoothDevice.fromJson(jsonArray.getJSONObject(i))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get all saved devices of a specific type.
     */
    fun getByType(prefs: SharedPreferences, type: SensorDeviceType): List<SavedBluetoothDevice> {
        return getAll(prefs).filter { it.type == type }
    }

    /**
     * Save a device to the list. If the MAC already exists, updates the entry.
     * New devices are added at the front of the list (most recently used first).
     */
    fun save(prefs: SharedPreferences, device: SavedBluetoothDevice) {
        val devices = getAll(prefs).toMutableList()

        // Remove existing entry with same MAC (if any)
        devices.removeAll { it.mac == device.mac }

        // Add new device at the front (most recently used)
        devices.add(0, device)

        // Save back to prefs
        saveList(prefs, devices)
    }

    /**
     * Remove a device by MAC address.
     */
    fun remove(prefs: SharedPreferences, mac: String) {
        val devices = getAll(prefs).toMutableList()
        devices.removeAll { it.mac == mac }
        saveList(prefs, devices)
    }

    /**
     * Remove all saved devices of a specific type.
     */
    fun removeByType(prefs: SharedPreferences, type: SensorDeviceType) {
        val devices = getAll(prefs).toMutableList()
        devices.removeAll { it.type == type }
        saveList(prefs, devices)
    }

    /**
     * Check if a MAC address is already saved.
     */
    fun isSaved(prefs: SharedPreferences, mac: String): Boolean {
        return getAll(prefs).any { it.mac == mac }
    }

    private fun saveList(prefs: SharedPreferences, devices: List<SavedBluetoothDevice>) {
        val jsonArray = JSONArray()
        devices.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(PREF_KEY, jsonArray.toString()).apply()
    }
}
