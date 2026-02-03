package io.github.avikulin.thud.domain.model

/**
 * What to adjust when metric (HR or Power) is outside target range.
 * Used for both HR-based and Power-based auto-adjustment.
 */
enum class AdjustmentType {
    SPEED,    // Adjust speed to bring metric into target range
    INCLINE   // Adjust incline to bring metric into target range
}
