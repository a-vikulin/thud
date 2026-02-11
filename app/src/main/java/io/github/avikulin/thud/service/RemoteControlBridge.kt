package io.github.avikulin.thud.service

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

    /** deviceName → (keyCode → binding). Updated by RemoteControlManager when config changes. */
    @Volatile var bindings: Map<String, Map<Int, ResolvedBinding>> = emptyMap()
}
