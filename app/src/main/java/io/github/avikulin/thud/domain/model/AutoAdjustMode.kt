package io.github.avikulin.thud.domain.model

/**
 * Determines the auto-adjustment mode for a workout step.
 * Each step can have at most one auto-adjustment mode (either HR or Power, never both).
 */
enum class AutoAdjustMode {
    NONE,   // No auto-adjustment - fixed pace/incline
    HR,     // Heart rate based - adjusts pace/incline to keep HR in target range
    POWER   // Power based - adjusts pace/incline to keep power in target range (from foot pod)
}
