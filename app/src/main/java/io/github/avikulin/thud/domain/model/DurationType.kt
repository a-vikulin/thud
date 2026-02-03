package io.github.avikulin.thud.domain.model

/**
 * How step duration is measured (for planning/chart).
 * Every step must have a TIME or DISTANCE for planning purposes.
 */
enum class DurationType {
    TIME,        // Duration in seconds
    DISTANCE     // Duration in meters
}
