package io.github.avikulin.thud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.avikulin.thud.domain.model.RemoteAction
import io.github.avikulin.thud.service.RemoteControlBridge
import io.github.avikulin.thud.service.SettingsManager
import org.json.JSONObject

/**
 * AccessibilityService that intercepts KeyEvents from configured BLE remotes.
 *
 * CRITICAL: Device filtering is the FIRST check. Only explicitly configured
 * remote devices are ever intercepted. All other input devices (hardware keyboards,
 * treadmill buttons, phone volume keys) always pass through untouched.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        loadBindingsIntoBridge()
        Log.d(TAG, "Service connected, bindings loaded")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 1. Get device name — if null or not configured, pass through immediately
        val deviceName = event.device?.name ?: return false
        val deviceBindings = RemoteControlBridge.bindings[deviceName] ?: return false

        // 2. Look up this keyCode in the device's binding map
        val binding = deviceBindings[event.keyCode]

        // 3. If learn mode is active, forward key info to config activity
        val learnCallback = RemoteControlBridge.learnModeCallback
        if (learnCallback != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val keyLabel = KeyEvent.keyCodeToString(event.keyCode)
                    .removePrefix("KEYCODE_")
                learnCallback(event.keyCode, keyLabel, deviceName)
            }
            return true // consume all events from configured remotes during learn mode
        }

        // No binding for this key on this remote — pass through
        if (binding == null) return false

        // 4. TOGGLE_MODE always works regardless of isActive.
        //    All other actions only work in take-over mode.
        if (binding.action != RemoteAction.TOGGLE_MODE && !RemoteControlBridge.isActive) {
            return false
        }

        // 5. On ACTION_DOWN (including repeats): dispatch action
        if (event.action == KeyEvent.ACTION_DOWN) {
            RemoteControlBridge.keyPressIndicator?.invoke()
            RemoteControlBridge.actionHandler?.invoke(binding)
        }

        return true // consume the event
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only need key event filtering
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    /**
     * Load saved remote bindings from SharedPreferences into the bridge.
     * Called on service connect and can be called when config changes.
     */
    private fun loadBindingsIntoBridge() {
        val prefs = getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(SettingsManager.PREF_REMOTE_BINDINGS, null) ?: return

        try {
            val config = JSONObject(json)
            val remotesArray = config.optJSONArray("remotes") ?: return
            val result = mutableMapOf<String, Map<Int, RemoteControlBridge.ResolvedBinding>>()

            for (i in 0 until remotesArray.length()) {
                val remote = remotesArray.getJSONObject(i)
                if (!remote.optBoolean("enabled", true)) continue

                val deviceName = remote.getString("deviceName")
                val bindingsArray = remote.optJSONArray("bindings") ?: continue
                val keyMap = mutableMapOf<Int, RemoteControlBridge.ResolvedBinding>()

                for (j in 0 until bindingsArray.length()) {
                    val b = bindingsArray.getJSONObject(j)
                    val action = try {
                        RemoteAction.valueOf(b.getString("action"))
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    val keyCode = b.getInt("keyCode")
                    val value = if (b.has("value")) b.getDouble("value") else null
                    keyMap[keyCode] = RemoteControlBridge.ResolvedBinding(action, value)
                }

                if (keyMap.isNotEmpty()) {
                    result[deviceName] = keyMap
                }
            }

            RemoteControlBridge.bindings = result
            Log.d(TAG, "Loaded bindings for ${result.size} remote(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse remote bindings", e)
        }
    }
}
