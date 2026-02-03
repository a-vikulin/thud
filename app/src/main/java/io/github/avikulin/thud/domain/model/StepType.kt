package io.github.avikulin.thud.domain.model

/**
 * Type of workout step.
 */
enum class StepType {
    WARMUP,     // Easy pace, typically at start
    RUN,        // Work interval
    RECOVER,    // Easy pace between intervals
    REST,       // Full stop or very slow
    COOLDOWN,   // Easy pace at end
    REPEAT      // Container for repeated steps
}
