package io.github.avikulin.thud.ui.remote

import android.app.AlertDialog
import android.content.Context
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.InputDevice
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.R
import io.github.avikulin.thud.domain.model.RemoteAction
import io.github.avikulin.thud.service.RemoteControlBridge
import io.github.avikulin.thud.service.RemoteControlManager
import io.github.avikulin.thud.service.RemoteControlManager.ActionBinding
import io.github.avikulin.thud.service.RemoteControlManager.RemoteConfig
import io.github.avikulin.thud.service.SettingsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Split-panel config activity for BLE remote controls.
 * Left pane: list of configured remotes.
 * Right pane: all actions with key assignment and optional increment value.
 */
class RemoteControlActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteControlActivity"
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    private lateinit var remoteListAdapter: RemoteListAdapter
    private lateinit var actionBindingAdapter: ActionBindingAdapter

    private lateinit var rvRemoteList: RecyclerView
    private lateinit var rvActionBindings: RecyclerView
    private lateinit var etAlias: EditText
    private lateinit var btnDeleteRemote: Button
    private lateinit var bindingsHeader: View
    private lateinit var tvEmptyState: TextView
    private lateinit var tvNoSelection: TextView
    private lateinit var rightPane: View

    private val handler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null

    /** In-memory config loaded from SharedPreferences. */
    private var remoteConfigs: MutableList<RemoteConfig> = mutableListOf()
    private var selectedIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)

        rvRemoteList = findViewById(R.id.rvRemoteList)
        rvActionBindings = findViewById(R.id.rvActionBindings)
        etAlias = findViewById(R.id.etAlias)
        btnDeleteRemote = findViewById(R.id.btnDeleteRemote)
        bindingsHeader = findViewById(R.id.bindingsHeader)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvNoSelection = findViewById(R.id.tvNoSelection)
        rightPane = findViewById(R.id.rightPane)

        setupRemoteList()
        setupActionBindings()
        setupHeader()

        loadConfig()
        updateEmptyState()
        updateRightPane()
    }

    override fun onDestroy() {
        // Remove learn mode callback when activity closes
        RemoteControlBridge.learnModeCallback = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setupHeader() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        // Also make the "Back" text clickable
        val backLabel = try { findViewById<View>(R.id.btnBack).parent as? View } catch (_: Exception) { null }

        findViewById<Button>(R.id.btnAddRemote).setOnClickListener { showAddRemoteDialog() }

        btnDeleteRemote.setOnClickListener {
            if (selectedIndex in remoteConfigs.indices) {
                val name = remoteConfigs[selectedIndex].alias.ifBlank { remoteConfigs[selectedIndex].deviceName }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.remote_delete))
                    .setMessage("Delete \"$name\"?")
                    .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                        remoteConfigs.removeAt(selectedIndex)
                        selectedIndex = -1
                        remoteListAdapter.selectedIndex = -1
                        remoteListAdapter.items = remoteConfigs
                        updateEmptyState()
                        updateRightPane()
                        scheduleSave()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }

        etAlias.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (selectedIndex in remoteConfigs.indices) {
                    remoteConfigs[selectedIndex].alias = s?.toString() ?: ""
                    remoteListAdapter.notifyItemChanged(selectedIndex)
                    scheduleSave()
                }
            }
        })
    }

    private fun setupRemoteList() {
        remoteListAdapter = RemoteListAdapter(
            onItemSelected = { index ->
                selectedIndex = index
                remoteListAdapter.selectedIndex = index
                updateRightPane()
            },
            onEnabledChanged = { index, enabled ->
                if (index in remoteConfigs.indices) {
                    remoteConfigs[index].enabled = enabled
                    scheduleSave()
                }
            }
        )
        rvRemoteList.layoutManager = LinearLayoutManager(this)
        rvRemoteList.adapter = remoteListAdapter
    }

    private fun setupActionBindings() {
        actionBindingAdapter = ActionBindingAdapter(
            onAssignKey = { action -> showLearnDialog(action) },
            onValueChanged = { action, value -> onBindingValueChanged(action, value) }
        )
        rvActionBindings.layoutManager = LinearLayoutManager(this)
        rvActionBindings.adapter = actionBindingAdapter
    }

    private fun updateEmptyState() {
        if (remoteConfigs.isEmpty()) {
            rvRemoteList.visibility = View.GONE
            tvEmptyState.visibility = View.VISIBLE
        } else {
            rvRemoteList.visibility = View.VISIBLE
            tvEmptyState.visibility = View.GONE
        }
    }

    private fun updateRightPane() {
        if (selectedIndex !in remoteConfigs.indices) {
            bindingsHeader.visibility = View.GONE
            rvActionBindings.visibility = View.GONE
            tvNoSelection.visibility = View.VISIBLE
            return
        }

        bindingsHeader.visibility = View.VISIBLE
        rvActionBindings.visibility = View.VISIBLE
        tvNoSelection.visibility = View.GONE

        val config = remoteConfigs[selectedIndex]
        etAlias.setText(config.alias)

        // Build binding map from the config's bindings
        val map = mutableMapOf<RemoteAction, ActionBinding>()
        for (binding in config.bindings) {
            map[binding.action] = binding
        }
        actionBindingAdapter.bindingMap = map
    }

    // ==================== Learn Mode Dialog ====================

    private fun showLearnDialog(action: RemoteAction) {
        if (selectedIndex !in remoteConfigs.indices) return
        val config = remoteConfigs[selectedIndex]
        val existingBinding = config.bindings.find { it.action == action }

        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        // Build a custom dialog with a text view for detected key
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_learn_title, getString(action.labelResId)))
            .setMessage(buildLearnMessage(existingBinding?.keyLabel, null))
            .setPositiveButton(getString(R.string.btn_ok), null) // set later
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setNeutralButton(getString(R.string.btn_clear), null) // set later

        val dialog = builder.create()
        var detectedKeyCode: Int? = null
        var detectedKeyLabel: String? = null
        var detectedDeviceName: String? = null

        dialog.show()

        // Set up learn mode callback
        RemoteControlBridge.learnModeCallback = { keyCode, keyLabel, deviceName ->
            handler.post {
                detectedKeyCode = keyCode
                detectedKeyLabel = keyLabel
                detectedDeviceName = deviceName
                dialog.setMessage(buildLearnMessage(existingBinding?.keyLabel, keyLabel))
            }
        }

        dialog.setOnDismissListener {
            RemoteControlBridge.learnModeCallback = null
        }

        // OK button — assign the detected key
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val keyCode = detectedKeyCode
            val keyLabel = detectedKeyLabel
            if (keyCode != null && keyLabel != null) {
                // Check for duplicate on this remote
                val existingAction = config.bindings.find { it.keyCode == keyCode && it.action != action }
                if (existingAction != null) {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.remote_learn_duplicate, getString(existingAction.action.labelResId)))
                        .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                            // Remove old binding
                            existingAction.keyCode = null
                            existingAction.keyLabel = null
                            // Assign new
                            assignKey(config, action, keyCode, keyLabel)
                            dialog.dismiss()
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                } else {
                    assignKey(config, action, keyCode, keyLabel)
                    dialog.dismiss()
                }
            } else {
                dialog.dismiss()
            }
        }

        // Clear button — remove binding
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val binding = config.bindings.find { it.action == action }
            if (binding != null) {
                binding.keyCode = null
                binding.keyLabel = null
            }
            updateRightPane()
            remoteListAdapter.notifyItemChanged(selectedIndex)
            scheduleSave()
            dialog.dismiss()
        }
    }

    private fun buildLearnMessage(currentLabel: String?, detectedLabel: String?): String {
        val sb = StringBuilder()
        sb.appendLine(getString(R.string.remote_learn_prompt))
        sb.appendLine()
        if (currentLabel != null) {
            sb.appendLine(getString(R.string.remote_learn_current, currentLabel))
        }
        if (detectedLabel != null) {
            sb.appendLine(getString(R.string.remote_learn_detected, detectedLabel))
        } else {
            sb.appendLine(getString(R.string.remote_learn_waiting))
        }
        sb.appendLine()
        sb.appendLine(getString(R.string.remote_learn_hold_hint))
        return sb.toString()
    }

    private fun assignKey(config: RemoteConfig, action: RemoteAction, keyCode: Int, keyLabel: String) {
        var binding = config.bindings.find { it.action == action }
        if (binding != null) {
            binding.keyCode = keyCode
            binding.keyLabel = keyLabel
        } else {
            val defaultValue = when (action) {
                RemoteAction.SPEED_UP, RemoteAction.SPEED_DOWN -> 0.5
                RemoteAction.INCLINE_UP, RemoteAction.INCLINE_DOWN -> 0.5
                else -> null
            }
            binding = ActionBinding(action, keyCode, keyLabel, defaultValue)
            config.bindings.add(binding)
        }
        updateRightPane()
        remoteListAdapter.notifyItemChanged(selectedIndex)
        scheduleSave()
    }

    private fun onBindingValueChanged(action: RemoteAction, value: Double) {
        if (selectedIndex !in remoteConfigs.indices) return
        val config = remoteConfigs[selectedIndex]
        val binding = config.bindings.find { it.action == action }
        if (binding != null) {
            binding.value = value
        } else {
            // Create binding with just the value (no key assigned yet)
            config.bindings.add(ActionBinding(action, null, null, value))
        }
        scheduleSave()
    }

    // ==================== Add Remote Dialog ====================

    private fun showAddRemoteDialog() {
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        val externalDevices = inputManager.inputDeviceIds.toList()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device ->
                val sources = device.sources
                val isKeyboard = (sources and InputDevice.SOURCE_KEYBOARD) != 0
                val isExternal = !device.isVirtual && device.name.isNotBlank()
                val alreadyConfigured = remoteConfigs.any { cfg -> cfg.deviceName == device.name }
                isKeyboard && isExternal && !alreadyConfigured
            }

        if (externalDevices.isEmpty()) {
            showAutoDetectDialog()
            return
        }

        val deviceNames = externalDevices.map { device -> device.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_add_title))
            .setItems(deviceNames) { _, which ->
                val device = externalDevices[which]
                addRemoteAndSelect(device.name, device.name)
            }
            .setNeutralButton(getString(R.string.remote_add_auto_detect)) { _, _ ->
                showAutoDetectDialog()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showAutoDetectDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_auto_detect_title))
            .setMessage(getString(R.string.remote_auto_detect_prompt))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        dialog.show()

        RemoteControlBridge.learnModeCallback = { _, _, deviceName ->
            handler.post {
                RemoteControlBridge.learnModeCallback = null
                dialog.dismiss()
                if (remoteConfigs.none { it.deviceName == deviceName }) {
                    addRemoteAndSelect(deviceName, deviceName)
                } else {
                    Toast.makeText(this, "\"$deviceName\" is already configured", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.setOnDismissListener {
            RemoteControlBridge.learnModeCallback = null
        }
    }

    private fun addRemoteAndSelect(deviceName: String, alias: String) {
        val config = RemoteConfig(deviceName, alias)
        remoteConfigs.add(config)
        selectedIndex = remoteConfigs.size - 1
        remoteListAdapter.items = remoteConfigs
        remoteListAdapter.selectedIndex = selectedIndex
        updateEmptyState()
        updateRightPane()
        scheduleSave()
    }

    // ==================== Config Persistence ====================

    private fun loadConfig() {
        val prefs = getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(SettingsManager.PREF_REMOTE_BINDINGS, null)
        if (json == null) {
            remoteConfigs = mutableListOf()
            remoteListAdapter.items = remoteConfigs
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

                result.add(RemoteConfig(deviceName, alias, enabled, bindings))
            }

            remoteConfigs = result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            remoteConfigs = mutableListOf()
        }

        remoteListAdapter.items = remoteConfigs

        // Auto-select first remote if available
        if (remoteConfigs.isNotEmpty()) {
            selectedIndex = 0
            remoteListAdapter.selectedIndex = 0
        }
    }

    private fun saveConfig() {
        val remotesArray = JSONArray()
        for (config in remoteConfigs) {
            val remoteObj = JSONObject().apply {
                put("deviceName", config.deviceName)
                put("alias", config.alias)
                put("enabled", config.enabled)
            }
            val bindingsArray = JSONArray()
            for (binding in config.bindings) {
                if (binding.keyCode == null) continue
                val bObj = JSONObject().apply {
                    put("action", binding.action.name)
                    put("keyCode", binding.keyCode)
                    put("keyLabel", binding.keyLabel)
                    if (binding.value != null) put("value", binding.value)
                }
                bindingsArray.put(bObj)
            }
            remoteObj.put("bindings", bindingsArray)
            remotesArray.put(remoteObj)
        }

        val root = JSONObject().apply { put("remotes", remotesArray) }
        val prefs = getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SettingsManager.PREF_REMOTE_BINDINGS, root.toString()).apply()

        // Push to bridge so AccessibilityService picks up changes immediately
        pushBindingsToBridge()
    }

    private fun pushBindingsToBridge() {
        val result = mutableMapOf<String, Map<Int, RemoteControlBridge.ResolvedBinding>>()
        for (config in remoteConfigs) {
            if (!config.enabled) continue
            val keyMap = mutableMapOf<Int, RemoteControlBridge.ResolvedBinding>()
            for (binding in config.bindings) {
                val keyCode = binding.keyCode ?: continue
                keyMap[keyCode] = RemoteControlBridge.ResolvedBinding(binding.action, binding.value)
            }
            if (keyMap.isNotEmpty()) {
                result[config.deviceName] = keyMap
            }
        }
        RemoteControlBridge.bindings = result
    }

    private fun scheduleSave() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { saveConfig() }
        saveRunnable = runnable
        handler.postDelayed(runnable, SAVE_DEBOUNCE_MS)
    }
}
