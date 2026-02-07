package io.github.avikulin.thud.domain.model

/**
 * Scope of effort adjustment coefficients during workout execution.
 * Controls whether speed/incline adjustments (from auto-adjust or manual +/- buttons)
 * affect all steps globally or are isolated per step position.
 */
enum class AdjustmentScope {
    ALL_STEPS,  // Coefficients are global within a phase (default, current behavior)
    ONE_STEP    // Each step position has its own coefficients; repeat children share by position
}
