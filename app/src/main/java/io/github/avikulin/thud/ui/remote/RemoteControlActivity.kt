package io.github.avikulin.thud.ui.remote

import android.app.AlertDialog
import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.CheckBox

import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.HUDService
import io.github.avikulin.thud.R
import io.github.avikulin.thud.domain.model.AndroidAction
import io.github.avikulin.thud.domain.model.RemoteAction
import io.github.avikulin.thud.service.RemoteControlBridge
import io.github.avikulin.thud.service.RemoteControlManager.ActionBinding
import io.github.avikulin.thud.service.RemoteControlManager.AndroidActionBinding
import io.github.avikulin.thud.service.RemoteControlManager.RemoteConfig
import io.github.avikulin.thud.service.SettingsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Split-panel config activity for BLE remote controls.
 * Left pane: list of configured remotes.
 * Right pane: universal Toggle Mode row at top, then two columns:
 *   - Left column: tHUD actions (Mode 1)
 *   - Right column: Android actions (Mode 2)
 */
class RemoteControlActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteControlActivity"
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    private lateinit var remoteListAdapter: RemoteListAdapter
    private lateinit var thudBindingAdapter: ActionBindingAdapter
    private lateinit var androidBindingAdapter: AndroidActionBindingAdapter

    private lateinit var rvRemoteList: RecyclerView
    private lateinit var rvThudBindings: RecyclerView
    private lateinit var rvAndroidBindings: RecyclerView
    private lateinit var tvDeviceName: TextView
    private lateinit var btnDeleteRemote: Button
    private lateinit var btnToggleModeKey: Button
    private lateinit var cbConsumeAllKeys: CheckBox
    private lateinit var bindingsHeader: View
    private lateinit var toggleModeRow: View
    private lateinit var columnsArea: View
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
        rvThudBindings = findViewById(R.id.rvThudBindings)
        rvAndroidBindings = findViewById(R.id.rvAndroidBindings)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        btnDeleteRemote = findViewById(R.id.btnDeleteRemote)
        btnToggleModeKey = findViewById(R.id.btnToggleModeKey)
        cbConsumeAllKeys = findViewById(R.id.cbConsumeAllKeys)
        bindingsHeader = findViewById(R.id.bindingsHeader)
        toggleModeRow = findViewById(R.id.toggleModeRow)
        columnsArea = findViewById(R.id.columnsArea)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvNoSelection = findViewById(R.id.tvNoSelection)
        rightPane = findViewById(R.id.rightPane)

        setupRemoteList()
        setupBindingAdapters()
        setupHeader()

        loadConfig()
        updateEmptyState()
        updateRightPane()
    }

    override fun onResume() {
        super.onResume()
        HUDService.notifyActivityForeground(this)
    }

    override fun onPause() {
        super.onPause()
        HUDService.notifyActivityBackground(this)
    }

    override fun onDestroy() {
        RemoteControlBridge.learnModeCallback = null
        handler.removeCallbacksAndMessages(null)
        HUDService.notifyActivityClosed(this)
        super.onDestroy()
    }

    private fun setupHeader() {
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

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

        cbConsumeAllKeys.setOnCheckedChangeListener { _, isChecked ->
            if (selectedIndex in remoteConfigs.indices) {
                remoteConfigs[selectedIndex].consumeAllKeys = isChecked
                scheduleSave()
            }
        }

        btnToggleModeKey.setOnClickListener {
            showLearnDialogForToggleMode()
        }
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

    private fun setupBindingAdapters() {
        thudBindingAdapter = ActionBindingAdapter(
            onAssignKey = { action -> showLearnDialogForThudAction(action) },
            onValueChanged = { action, value -> onThudBindingValueChanged(action, value) }
        )
        rvThudBindings.layoutManager = LinearLayoutManager(this)
        rvThudBindings.adapter = thudBindingAdapter

        androidBindingAdapter = AndroidActionBindingAdapter(
            onAssignKey = { action -> showLearnDialogForAndroidAction(action) }
        )
        rvAndroidBindings.layoutManager = LinearLayoutManager(this)
        rvAndroidBindings.adapter = androidBindingAdapter
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
            toggleModeRow.visibility = View.GONE
            columnsArea.visibility = View.GONE
            tvNoSelection.visibility = View.VISIBLE
            return
        }

        bindingsHeader.visibility = View.VISIBLE
        toggleModeRow.visibility = View.VISIBLE
        columnsArea.visibility = View.VISIBLE
        tvNoSelection.visibility = View.GONE

        val config = remoteConfigs[selectedIndex]
        tvDeviceName.text = config.alias.ifBlank { config.deviceName }
        cbConsumeAllKeys.isChecked = config.consumeAllKeys

        // Toggle Mode key button
        val toggleBinding = config.bindings.find { it.action == RemoteAction.TOGGLE_MODE }
        btnToggleModeKey.text = toggleBinding?.keyLabel
            ?: getString(R.string.remote_key_not_assigned)

        // tHUD bindings (left column, excluding TOGGLE_MODE)
        val thudMap = mutableMapOf<RemoteAction, ActionBinding>()
        for (binding in config.bindings) {
            if (binding.action != RemoteAction.TOGGLE_MODE) {
                thudMap[binding.action] = binding
            }
        }
        thudBindingAdapter.bindingMap = thudMap

        // Android bindings (right column)
        val androidMap = mutableMapOf<AndroidAction, AndroidActionBinding>()
        for (binding in config.androidBindings) {
            androidMap[binding.action] = binding
        }
        androidBindingAdapter.bindingMap = androidMap
    }

    // ==================== Learn Mode Dialogs ====================

    private fun showLearnDialogForToggleMode() {
        if (selectedIndex !in remoteConfigs.indices) return
        val config = remoteConfigs[selectedIndex]
        val existingBinding = config.bindings.find { it.action == RemoteAction.TOGGLE_MODE }

        showGenericLearnDialog(
            title = getString(R.string.remote_learn_title, getString(R.string.remote_action_toggle_mode)),
            currentKeyLabel = existingBinding?.keyLabel,
            onAssign = { keyCode, keyLabel ->
                assignThudKey(config, RemoteAction.TOGGLE_MODE, keyCode, keyLabel)
            },
            onClear = {
                existingBinding?.let {
                    it.keyCode = null
                    it.keyLabel = null
                }
                updateRightPane()
                remoteListAdapter.notifyItemChanged(selectedIndex)
                scheduleSave()
            }
        )
    }

    private fun showLearnDialogForThudAction(action: RemoteAction) {
        if (selectedIndex !in remoteConfigs.indices) return
        val config = remoteConfigs[selectedIndex]
        val existingBinding = config.bindings.find { it.action == action }

        showGenericLearnDialog(
            title = getString(R.string.remote_learn_title, getString(action.labelResId)),
            currentKeyLabel = existingBinding?.keyLabel,
            onAssign = { keyCode, keyLabel ->
                // Duplicate check within tHUD bindings only (excluding TOGGLE_MODE)
                val duplicate = config.bindings.find {
                    it.keyCode == keyCode && it.action != action && it.action != RemoteAction.TOGGLE_MODE
                }
                if (duplicate != null) {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.remote_learn_duplicate, getString(duplicate.action.labelResId)))
                        .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                            duplicate.keyCode = null
                            duplicate.keyLabel = null
                            assignThudKey(config, action, keyCode, keyLabel)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                } else {
                    assignThudKey(config, action, keyCode, keyLabel)
                }
            },
            onClear = {
                existingBinding?.let {
                    it.keyCode = null
                    it.keyLabel = null
                }
                updateRightPane()
                remoteListAdapter.notifyItemChanged(selectedIndex)
                scheduleSave()
            }
        )
    }

    private fun showLearnDialogForAndroidAction(action: AndroidAction) {
        if (selectedIndex !in remoteConfigs.indices) return
        val config = remoteConfigs[selectedIndex]
        val existingBinding = config.androidBindings.find { it.action == action }

        showGenericLearnDialog(
            title = getString(R.string.remote_learn_title, getString(action.labelResId)),
            currentKeyLabel = existingBinding?.keyLabel,
            onAssign = { keyCode, keyLabel ->
                // Duplicate check within android bindings only
                val duplicate = config.androidBindings.find {
                    it.keyCode == keyCode && it.action != action
                }
                if (duplicate != null) {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.remote_learn_duplicate, getString(duplicate.action.labelResId)))
                        .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                            duplicate.keyCode = null
                            duplicate.keyLabel = null
                            assignAndroidKey(config, action, keyCode, keyLabel)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                } else {
                    assignAndroidKey(config, action, keyCode, keyLabel)
                }
            },
            onClear = {
                existingBinding?.let {
                    it.keyCode = null
                    it.keyLabel = null
                }
                updateRightPane()
                remoteListAdapter.notifyItemChanged(selectedIndex)
                scheduleSave()
            }
        )
    }

    /**
     * Generic learn dialog — shared by Toggle Mode, tHUD actions, and Android actions.
     * The caller provides the title, current key label, and callbacks for assign/clear.
     */
    private fun showGenericLearnDialog(
        title: String,
        currentKeyLabel: String?,
        onAssign: (keyCode: Int, keyLabel: String) -> Unit,
        onClear: () -> Unit
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(buildLearnMessage(currentKeyLabel, null))
            .setPositiveButton(getString(R.string.btn_ok), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setNeutralButton(getString(R.string.btn_clear), null)

        val dialog = builder.create()
        var detectedKeyCode: Int? = null
        var detectedKeyLabel: String? = null

        dialog.show()

        val expectedDevice = if (selectedIndex in remoteConfigs.indices)
            remoteConfigs[selectedIndex].deviceName else null

        RemoteControlBridge.learnModeCallback = { keyCode, keyLabel, deviceName ->
            // Only accept keys from the device being edited
            if (expectedDevice != null && RemoteControlBridge.isDeviceMatch(deviceName, expectedDevice)) {
                handler.post {
                    detectedKeyCode = keyCode
                    detectedKeyLabel = keyLabel
                    dialog.setMessage(buildLearnMessage(currentKeyLabel, keyLabel))
                }
            }
        }

        dialog.setOnDismissListener {
            RemoteControlBridge.learnModeCallback = null
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val keyCode = detectedKeyCode
            val keyLabel = detectedKeyLabel
            if (keyCode != null && keyLabel != null) {
                onAssign(keyCode, keyLabel)
            }
            dialog.dismiss()
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            onClear()
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

    // ==================== Key Assignment ====================

    private fun assignThudKey(config: RemoteConfig, action: RemoteAction, keyCode: Int, keyLabel: String) {
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

    private fun assignAndroidKey(config: RemoteConfig, action: AndroidAction, keyCode: Int, keyLabel: String) {
        var binding = config.androidBindings.find { it.action == action }
        if (binding != null) {
            binding.keyCode = keyCode
            binding.keyLabel = keyLabel
        } else {
            binding = AndroidActionBinding(action, keyCode, keyLabel)
            config.androidBindings.add(binding)
        }
        updateRightPane()
        remoteListAdapter.notifyItemChanged(selectedIndex)
        scheduleSave()
    }

    private fun onThudBindingValueChanged(action: RemoteAction, value: Double) {
        if (selectedIndex !in remoteConfigs.indices) return
        val config = remoteConfigs[selectedIndex]
        val binding = config.bindings.find { it.action == action }
        if (binding != null) {
            binding.value = value
        } else {
            config.bindings.add(ActionBinding(action, null, null, value))
        }
        scheduleSave()
    }

    // ==================== Add Remote Dialog ====================

    @android.annotation.SuppressLint("MissingPermission")
    private fun showAddRemoteDialog() {
        // Use bonded Bluetooth devices instead of InputDevice list.
        // InputDevice only shows currently-connected devices and includes internal
        // hardware keyboards (treadmill buttons), which clutters the list.
        // Bonded BT devices include paired remotes even when sleeping/disconnected.
        val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val hasBtPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val bondedDevices = if (bluetoothAdapter != null && hasBtPermission) {
            bluetoothAdapter.bondedDevices
                ?.filter { device ->
                    val name = device.name
                    if (name.isNullOrBlank()) return@filter false
                    if (remoteConfigs.any { cfg -> cfg.deviceName == name }) return@filter false
                    // Only show peripheral/input devices (keyboards, remotes, gamepads)
                    val major = device.bluetoothClass?.majorDeviceClass
                    major == BluetoothClass.Device.Major.PERIPHERAL
                }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }

        if (bondedDevices.isEmpty()) {
            showAutoDetectDialog()
            return
        }

        val deviceNames = bondedDevices.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_add_title))
            .setItems(deviceNames) { _, which ->
                val name = bondedDevices[which].name ?: return@setItems
                addRemoteAndSelect(name, name)
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

                // Android bindings (backward-compatible)
                val androidArray = remote.optJSONArray("androidBindings") ?: JSONArray()
                val androidBindingsList = mutableListOf<AndroidActionBinding>()

                for (k in 0 until androidArray.length()) {
                    val ab = androidArray.getJSONObject(k)
                    val androidAction = try {
                        AndroidAction.valueOf(ab.getString("action"))
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    androidBindingsList.add(AndroidActionBinding(
                        action = androidAction,
                        keyCode = ab.getInt("keyCode"),
                        keyLabel = if (ab.has("keyLabel")) ab.getString("keyLabel") else null
                    ))
                }

                val consumeAllKeys = remote.optBoolean("consumeAllKeys", false)
                result.add(RemoteConfig(deviceName, alias, enabled, bindings, androidBindingsList, consumeAllKeys))
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
                put("consumeAllKeys", config.consumeAllKeys)
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

            val androidArray = JSONArray()
            for (binding in config.androidBindings) {
                if (binding.keyCode == null) continue
                val abObj = JSONObject().apply {
                    put("action", binding.action.name)
                    put("keyCode", binding.keyCode)
                    put("keyLabel", binding.keyLabel)
                }
                androidArray.put(abObj)
            }
            remoteObj.put("androidBindings", androidArray)

            remotesArray.put(remoteObj)
        }

        val root = JSONObject().apply { put("remotes", remotesArray) }
        val prefs = getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SettingsManager.PREF_REMOTE_BINDINGS, root.toString()).apply()

        pushBindingsToBridge()
    }

    private fun pushBindingsToBridge() {
        val thudResult = mutableMapOf<String, Map<Int, RemoteControlBridge.ResolvedBinding>>()
        val androidResult = mutableMapOf<String, Map<Int, AndroidAction>>()
        val deviceNames = mutableSetOf<String>()
        val consumeAllNames = mutableSetOf<String>()
        for (config in remoteConfigs) {
            if (!config.enabled) continue
            deviceNames.add(config.deviceName)
            if (config.consumeAllKeys) consumeAllNames.add(config.deviceName)

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
        RemoteControlBridge.consumeAllDeviceNames = consumeAllNames
    }

    private fun scheduleSave() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { saveConfig() }
        saveRunnable = runnable
        handler.postDelayed(runnable, SAVE_DEBOUNCE_MS)
    }
}
