package io.github.avikulin.thud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.avikulin.thud.domain.model.AndroidAction
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
 *
 * Dispatch uses fallback-both-ways: mode determines priority, but keys bound
 * in only one column work in both modes.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibility"
    }

    private lateinit var audioManager: AudioManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        loadBindingsIntoBridge()
        Log.d(TAG, "Service connected, bindings loaded")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 1. Get device name
        val deviceName = event.device?.name ?: return false

        // 2. If learn mode is active, forward key info to config activity
        //    This MUST be before device filtering — auto-detect needs unconfigured devices
        val learnCallback = RemoteControlBridge.learnModeCallback
        if (learnCallback != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val keyLabel = KeyEvent.keyCodeToString(event.keyCode)
                    .removePrefix("KEYCODE_")
                Log.d(TAG, "Learn mode: keyCode=${event.keyCode}, label=$keyLabel, device=$deviceName")
                learnCallback(event.keyCode, keyLabel, deviceName)
            }
            return true // consume all events during learn mode
        }

        // 3. Device filtering — only configured remotes are intercepted
        val isConfigured = deviceName in RemoteControlBridge.configuredDeviceNames
        if (!isConfigured) return false

        val thudBindings = RemoteControlBridge.bindings[deviceName]
        val androidBindingMap = RemoteControlBridge.androidBindings[deviceName]

        // 3. Look up keyCode in both binding maps
        val thudBinding = thudBindings?.get(event.keyCode)
        val androidAction = androidBindingMap?.get(event.keyCode)

        // Nothing bound in either column? Log it so we can see unbound keys from configured devices
        if (thudBinding == null && androidAction == null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val keyLabel = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
                Log.d(TAG, "Unbound key from configured device: keyCode=${event.keyCode}, label=$keyLabel, device=$deviceName")
            }
            return false
        }

        // 4. TOGGLE_MODE always executes regardless of mode
        if (thudBinding?.action == RemoteAction.TOGGLE_MODE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                RemoteControlBridge.keyPressIndicator?.invoke()
                RemoteControlBridge.actionHandler?.invoke(thudBinding)
            }
            return true
        }

        // 5. Fallback both ways: mode determines priority, other column is fallback
        if (event.action == KeyEvent.ACTION_DOWN) {
            RemoteControlBridge.keyPressIndicator?.invoke()
            if (RemoteControlBridge.isActive) {
                // Mode 1 (take-over): tHUD first, android fallback
                if (thudBinding != null) {
                    RemoteControlBridge.actionHandler?.invoke(thudBinding)
                } else if (androidAction != null) {
                    executeAndroidAction(androidAction)
                }
            } else {
                // Mode 2 (pass-through): android first, tHUD fallback
                if (androidAction != null) {
                    executeAndroidAction(androidAction)
                } else if (thudBinding != null) {
                    RemoteControlBridge.actionHandler?.invoke(thudBinding)
                }
            }
        }

        return true // consume — key is bound in at least one column
    }

    private fun executeAndroidAction(action: AndroidAction) {
        when (action) {
            AndroidAction.MEDIA_PLAY_PAUSE -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            AndroidAction.MEDIA_NEXT -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            AndroidAction.MEDIA_PREVIOUS -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            AndroidAction.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            AndroidAction.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            AndroidAction.MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            AndroidAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            AndroidAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            AndroidAction.RECENT_APPS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
        RemoteControlBridge.androidActionHandler?.invoke(action)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
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
            val thudResult = mutableMapOf<String, Map<Int, RemoteControlBridge.ResolvedBinding>>()
            val androidResult = mutableMapOf<String, Map<Int, AndroidAction>>()
            val deviceNames = mutableSetOf<String>()

            for (i in 0 until remotesArray.length()) {
                val remote = remotesArray.getJSONObject(i)
                if (!remote.optBoolean("enabled", true)) continue

                val deviceName = remote.getString("deviceName")
                deviceNames.add(deviceName)

                // tHUD bindings
                val bindingsArray = remote.optJSONArray("bindings")
                if (bindingsArray != null) {
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
                        thudResult[deviceName] = keyMap
                    }
                }

                // Android bindings
                val androidArray = remote.optJSONArray("androidBindings")
                if (androidArray != null) {
                    val androidKeyMap = mutableMapOf<Int, AndroidAction>()
                    for (j in 0 until androidArray.length()) {
                        val b = androidArray.getJSONObject(j)
                        val action = try {
                            AndroidAction.valueOf(b.getString("action"))
                        } catch (_: IllegalArgumentException) {
                            continue
                        }
                        val keyCode = b.getInt("keyCode")
                        androidKeyMap[keyCode] = action
                    }
                    if (androidKeyMap.isNotEmpty()) {
                        androidResult[deviceName] = androidKeyMap
                    }
                }
            }

            RemoteControlBridge.configuredDeviceNames = deviceNames
            RemoteControlBridge.bindings = thudResult
            RemoteControlBridge.androidBindings = androidResult
            Log.d(TAG, "Loaded ${deviceNames.size} device(s): $deviceNames, " +
                    "thud bindings for ${thudResult.size}, android bindings for ${androidResult.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse remote bindings", e)
        }
    }
}
