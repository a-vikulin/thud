package io.github.avikulin.thud.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.avikulin.thud.domain.model.AndroidAction
import io.github.avikulin.thud.domain.model.RemoteAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages remote control bindings and dispatches actions to treadmill controls.
 *
 * Registered as RemoteControlBridge.actionHandler — receives resolved bindings
 * from the AccessibilityService and calls TelemetryManager/WorkoutEngineManager.
 */
class RemoteControlManager(
    private val context: Context,
    private val state: ServiceStateHolder,
    private val telemetryManager: TelemetryManager,
    private val workoutEngineManager: WorkoutEngineManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "RemoteControlManager"
        private const val DEFAULT_SPEED_INCREMENT = 0.5
        private const val DEFAULT_INCLINE_INCREMENT = 0.5
    }

    interface Listener {
        fun onRemoteAction(action: RemoteAction)
        fun onModeChanged(isActive: Boolean)
        fun onKeyPressed()
    }

    var listener: Listener? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)

    /** In-memory config for the activity to read/write. */
    var remoteConfigs: MutableList<RemoteConfig> = mutableListOf()
        private set

    data class RemoteConfig(
        var deviceName: String,
        var alias: String,
        var enabled: Boolean = true,
        var bindings: MutableList<ActionBinding> = mutableListOf(),
        var androidBindings: MutableList<AndroidActionBinding> = mutableListOf()
    )

    data class ActionBinding(
        val action: RemoteAction,
        var keyCode: Int? = null,
        var keyLabel: String? = null,
        var value: Double? = null
    )

    data class AndroidActionBinding(
        val action: AndroidAction,
        var keyCode: Int? = null,
        var keyLabel: String? = null
    )

    fun initialize() {
        loadConfig()
        pushBindingsToBridge()

        RemoteControlBridge.actionHandler = { binding ->
            handleAction(binding)
        }
        RemoteControlBridge.keyPressIndicator = {
            listener?.onKeyPressed()
        }

        Log.d(TAG, "Initialized with ${remoteConfigs.size} remote(s)")
    }

    fun cleanup() {
        RemoteControlBridge.actionHandler = null
        RemoteControlBridge.androidActionHandler = null
        RemoteControlBridge.keyPressIndicator = null
        RemoteControlBridge.learnModeCallback = null
    }

    private fun handleAction(binding: RemoteControlBridge.ResolvedBinding) {
        Log.d(TAG, "Action: ${binding.action}, value: ${binding.value}")

        when (binding.action) {
            RemoteAction.SPEED_UP -> adjustSpeed(binding.value ?: DEFAULT_SPEED_INCREMENT)
            RemoteAction.SPEED_DOWN -> adjustSpeed(-(binding.value ?: DEFAULT_SPEED_INCREMENT))
            RemoteAction.INCLINE_UP -> adjustIncline(binding.value ?: DEFAULT_INCLINE_INCREMENT)
            RemoteAction.INCLINE_DOWN -> adjustIncline(-(binding.value ?: DEFAULT_INCLINE_INCREMENT))
            RemoteAction.BELT_START_PAUSE -> toggleBelt()
            RemoteAction.BELT_STOP -> stopBelt()
            RemoteAction.NEXT_STEP -> workoutEngineManager.skipToNextStep()
            RemoteAction.PREV_STEP -> workoutEngineManager.skipToPreviousStep()
            RemoteAction.TOGGLE_MODE -> toggleMode()
        }

        listener?.onRemoteAction(binding.action)
    }

    private fun adjustSpeed(deltaKph: Double) {
        val currentAdjusted = state.currentSpeedKph * state.paceCoefficient
        val newAdjusted = (currentAdjusted + deltaKph)
            .coerceIn(state.minSpeedKph * state.paceCoefficient, state.maxSpeedKph * state.paceCoefficient)
        scope.launch(Dispatchers.IO) {
            telemetryManager.ensureTreadmillRunning()
            telemetryManager.setTreadmillSpeed(newAdjusted)
        }
    }

    private fun adjustIncline(deltaPct: Double) {
        val currentEffective = state.currentInclinePercent
        val minEffective = state.minInclinePercent - state.inclineAdjustment
        val maxEffective = state.maxInclinePercent - state.inclineAdjustment
        val newEffective = (currentEffective + deltaPct).coerceIn(minEffective, maxEffective)
        scope.launch(Dispatchers.IO) {
            telemetryManager.ensureTreadmillRunning()
            telemetryManager.setTreadmillIncline(newEffective)
        }
    }

    private fun toggleBelt() {
        scope.launch(Dispatchers.IO) {
            // If belt is moving, pause. Otherwise resume.
            if (state.currentSpeedKph > 0) {
                telemetryManager.pauseTreadmill()
            } else {
                telemetryManager.ensureTreadmillRunning()
            }
        }
    }

    private fun stopBelt() {
        telemetryManager.stopTreadmill()
    }

    private fun toggleMode() {
        val newActive = !RemoteControlBridge.isActive
        RemoteControlBridge.isActive = newActive
        listener?.onModeChanged(newActive)
        Log.d(TAG, "Mode toggled: ${if (newActive) "take-over" else "pass-through"}")
    }

    // ──────────────────── Config persistence ────────────────────

    fun loadConfig() {
        val json = prefs.getString(SettingsManager.PREF_REMOTE_BINDINGS, null)
        if (json == null) {
            remoteConfigs = mutableListOf()
            return
        }
        try {
            val config = JSONObject(json)
            val remotesArray = config.optJSONArray("remotes") ?: JSONArray()
            val result = mutableListOf<RemoteConfig>()

            for (i in 0 until remotesArray.length()) {
                val remote = remotesArray.getJSONObject(i)
                val deviceName = remote.getString("deviceName")
                val alias = remote.optString("alias", deviceName)
                val enabled = remote.optBoolean("enabled", true)
                val bindingsArray = remote.optJSONArray("bindings") ?: JSONArray()
                val bindings = mutableListOf<ActionBinding>()

                for (j in 0 until bindingsArray.length()) {
                    val b = bindingsArray.getJSONObject(j)
                    val action = try {
                        RemoteAction.valueOf(b.getString("action"))
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    bindings.add(ActionBinding(
                        action = action,
                        keyCode = b.getInt("keyCode"),
                        keyLabel = if (b.has("keyLabel")) b.getString("keyLabel") else null,
                        value = if (b.has("value")) b.getDouble("value") else null
                    ))
                }

                // Android bindings (backward-compatible — may not exist in old configs)
                val androidArray = remote.optJSONArray("androidBindings") ?: JSONArray()
                val androidBindingsList = mutableListOf<AndroidActionBinding>()

                for (j in 0 until androidArray.length()) {
                    val b = androidArray.getJSONObject(j)
                    val androidAction = try {
                        AndroidAction.valueOf(b.getString("action"))
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    androidBindingsList.add(AndroidActionBinding(
                        action = androidAction,
                        keyCode = b.getInt("keyCode"),
                        keyLabel = if (b.has("keyLabel")) b.getString("keyLabel") else null
                    ))
                }

                result.add(RemoteConfig(deviceName, alias, enabled, bindings, androidBindingsList))
            }

            remoteConfigs = result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load remote config", e)
            remoteConfigs = mutableListOf()
        }
    }

    fun saveConfig() {
        val remotesArray = JSONArray()
        for (config in remoteConfigs) {
            val remoteObj = JSONObject().apply {
                put("deviceName", config.deviceName)
                put("alias", config.alias)
                put("enabled", config.enabled)
            }
            val bindingsArray = JSONArray()
            for (binding in config.bindings) {
                if (binding.keyCode == null) continue // don't persist unbound actions
                val bObj = JSONObject().apply {
                    put("action", binding.action.name)
                    put("keyCode", binding.keyCode)
                    put("keyLabel", binding.keyLabel)
                    if (binding.value != null) put("value", binding.value)
                }
                bindingsArray.put(bObj)
            }
            remoteObj.put("bindings", bindingsArray)

            val androidArray = JSONArray()
            for (binding in config.androidBindings) {
                if (binding.keyCode == null) continue
                val bObj = JSONObject().apply {
                    put("action", binding.action.name)
                    put("keyCode", binding.keyCode)
                    put("keyLabel", binding.keyLabel)
                }
                androidArray.put(bObj)
            }
            remoteObj.put("androidBindings", androidArray)

            remotesArray.put(remoteObj)
        }

        val root = JSONObject().apply {
            put("remotes", remotesArray)
        }

        prefs.edit().putString(SettingsManager.PREF_REMOTE_BINDINGS, root.toString()).apply()
        pushBindingsToBridge()
        Log.d(TAG, "Config saved: ${remoteConfigs.size} remote(s)")
    }

    /** Push current in-memory config to the bridge so AccessibilityService picks it up. */
    fun pushBindingsToBridge() {
        val thudResult = mutableMapOf<String, Map<Int, RemoteControlBridge.ResolvedBinding>>()
        val androidResult = mutableMapOf<String, Map<Int, AndroidAction>>()
        val deviceNames = mutableSetOf<String>()

        for (config in remoteConfigs) {
            if (!config.enabled) continue
            deviceNames.add(config.deviceName)

            val keyMap = mutableMapOf<Int, RemoteControlBridge.ResolvedBinding>()
            for (binding in config.bindings) {
                val keyCode = binding.keyCode ?: continue
                keyMap[keyCode] = RemoteControlBridge.ResolvedBinding(binding.action, binding.value)
            }
            if (keyMap.isNotEmpty()) {
                thudResult[config.deviceName] = keyMap
            }

            val androidKeyMap = mutableMapOf<Int, AndroidAction>()
            for (binding in config.androidBindings) {
                val keyCode = binding.keyCode ?: continue
                androidKeyMap[keyCode] = binding.action
            }
            if (androidKeyMap.isNotEmpty()) {
                androidResult[config.deviceName] = androidKeyMap
            }
        }

        RemoteControlBridge.configuredDeviceNames = deviceNames
        RemoteControlBridge.bindings = thudResult
        RemoteControlBridge.androidBindings = androidResult
    }

    fun addRemote(deviceName: String, alias: String = deviceName): RemoteConfig {
        val config = RemoteConfig(deviceName, alias)
        remoteConfigs.add(config)
        saveConfig()
        return config
    }

    fun removeRemote(index: Int) {
        if (index in remoteConfigs.indices) {
            remoteConfigs.removeAt(index)
            saveConfig()
        }
    }
}
