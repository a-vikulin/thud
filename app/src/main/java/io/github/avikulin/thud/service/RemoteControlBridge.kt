package io.github.avikulin.thud.service

import io.github.avikulin.thud.domain.model.AndroidAction
import io.github.avikulin.thud.domain.model.RemoteAction

/**
 * Same-process singleton connecting AccessibilityService ↔ HUDService.
 * Both services live in the same app process, so no IPC is needed.
 */
object RemoteControlBridge {

    data class ResolvedBinding(val action: RemoteAction, val value: Double?)

    /** Whether take-over mode is active (keys are intercepted for tHUD actions). */
    @Volatile var isActive: Boolean = false

    /** Called by AccessibilityService when a bound key is pressed in take-over mode. */
    @Volatile var actionHandler: ((ResolvedBinding) -> Unit)? = null

    /** Called on any bound key press for HUD blink feedback. */
    @Volatile var keyPressIndicator: (() -> Unit)? = null

    /** Non-null during learn mode — AccessibilityService forwards key info here. */
    @Volatile var learnModeCallback: ((keyCode: Int, keyLabel: String, deviceName: String) -> Unit)? = null

    /** All enabled device names, including those with no bindings yet. */
    @Volatile var configuredDeviceNames: Set<String> = emptySet()

    /** deviceName → (keyCode → binding). Updated by RemoteControlManager when config changes. */
    @Volatile var bindings: Map<String, Map<Int, ResolvedBinding>> = emptyMap()

    /** deviceName → (keyCode → AndroidAction). Updated alongside tHUD bindings. */
    @Volatile var androidBindings: Map<String, Map<Int, AndroidAction>> = emptyMap()

    /** Called when an android action fires (for HUD blink feedback only — execution is in AccessibilityService). */
    @Volatile var androidActionHandler: ((AndroidAction) -> Unit)? = null

    /** Device names where ALL keys (even unbound) should be consumed, not passed to OS. */
    @Volatile var consumeAllDeviceNames: Set<String> = emptySet()

    /** Called when AccessibilityService connects (permission just granted). HUDService uses this to auto-return. */
    @Volatile var onAccessibilityServiceConnected: (() -> Unit)? = null

    /** Set by HUDService when user taps OK in the permission dialog. Cleared after grant is handled. */
    @Volatile var awaitingAccessibilityGrant: Boolean = false

    /**
     * Check if an InputDevice name matches a configured device name.
     * Android HID appends " Keyboard" / " Mouse" to the BT name,
     * so we check exact match first, then prefix match.
     */
    fun isDeviceMatch(inputDeviceName: String, configuredName: String): Boolean {
        return inputDeviceName == configuredName || inputDeviceName.startsWith(configuredName)
    }

    /**
     * Resolve an InputDevice name to a configured device name, or null if no match.
     */
    fun resolveConfiguredDeviceName(inputDeviceName: String): String? {
        if (inputDeviceName in configuredDeviceNames) return inputDeviceName
        return configuredDeviceNames.firstOrNull { inputDeviceName.startsWith(it) }
    }
}
