package io.github.avikulin.thud.domain.model

/**
 * Optional early end condition for a step.
 * This allows a step to end before its planned duration/distance.
 */
enum class EarlyEndCondition {
    NONE,      // Step ends exactly at planned duration/distance
    OPEN,      // Step can extend beyond planned duration until "Next Step" pressed
    HR_RANGE   // Step ends early when HR enters specified range (with countdown)
}
