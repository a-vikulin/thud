package io.github.avikulin.thud.domain.engine

import io.github.avikulin.thud.util.HeartRateZones
import io.github.avikulin.thud.domain.model.AdjustmentType
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.EarlyEndCondition
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.util.PowerZones

/**
 * Flattened representation of a workout step for execution.
 * Repeat blocks are expanded into individual steps with iteration tracking.
 *
 * All HR targets are stored as % of LTHR (Lactate Threshold HR).
 * All Power targets are stored as % of FTP (Functional Threshold Power).
 * Use the conversion helper functions to get actual BPM/Watts values.
 */
data class ExecutionStep(
    /** Original step ID from database. */
    val stepId: Long,

    /** Index in the flattened execution list (0-based). */
    val flatIndex: Int,

    /** Type of step (WARMUP, RUN, RECOVER, etc.). */
    val type: StepType,

    /** How duration is measured (TIME, DISTANCE, or OPEN). */
    val durationType: DurationType,

    /** Duration in seconds (for TIME type). */
    val durationSeconds: Int?,

    /** Duration in meters (for DISTANCE type). */
    val durationMeters: Int?,

    /** Target pace in kph (required - stored as kph, displayed as min:sec/km). */
    val paceTargetKph: Double,

    /** Target incline in percent (required). */
    val inclineTargetPercent: Double,

    /** Auto-adjustment mode (NONE, HR, or POWER). */
    val autoAdjustMode: AutoAdjustMode = AutoAdjustMode.NONE,

    /** What to adjust when metric is out of range (SPEED or INCLINE). */
    val adjustmentType: AdjustmentType? = null,

    /** Minimum HR target as % of LTHR (null = no HR target). Stored with 1 decimal precision. */
    val hrTargetMinPercent: Double? = null,

    /** Maximum HR target as % of LTHR (null = no HR target). Stored with 1 decimal precision. */
    val hrTargetMaxPercent: Double? = null,

    /** Minimum Power target as % of FTP (null = no Power target). Stored with 1 decimal precision. */
    val powerTargetMinPercent: Double? = null,

    /** Maximum Power target as % of FTP (null = no Power target). Stored with 1 decimal precision. */
    val powerTargetMaxPercent: Double? = null,

    /** Early end condition (allows step to end before planned duration). */
    val earlyEndCondition: EarlyEndCondition = EarlyEndCondition.NONE,

    /** Minimum HR for early end HR_RANGE condition (% of LTHR). Stored with 1 decimal precision. */
    val hrEndTargetMinPercent: Double? = null,

    /** Maximum HR for early end HR_RANGE condition (% of LTHR). Stored with 1 decimal precision. */
    val hrEndTargetMaxPercent: Double? = null,

    /** Which iteration of repeat (1-based, null if not in repeat). */
    val repeatIteration: Int?,

    /** Total iterations in this repeat block (null if not in repeat). */
    val repeatTotal: Int?,

    /** Human-readable name (e.g., "Warmup", "Run 2/4"). */
    val displayName: String,

    /**
     * Identity key for coefficient scoping in ONE_STEP mode.
     * Steps with the same key share coefficients across repeat iterations.
     * E.g., all "Run" children in a 4x repeat share key "r0_c0".
     * Non-repeat steps get unique keys like "s0", "s1".
     */
    val stepIdentityKey: String = ""
) {
    // ==================== Conversion Helpers ====================

    /** Convert HR min target % to BPM using provided LTHR. */
    fun getHrTargetMinBpm(lthrBpm: Int): Int? =
        hrTargetMinPercent?.let { HeartRateZones.percentToBpm(it, lthrBpm) }

    /** Convert HR max target % to BPM using provided LTHR. */
    fun getHrTargetMaxBpm(lthrBpm: Int): Int? =
        hrTargetMaxPercent?.let { HeartRateZones.percentToBpm(it, lthrBpm) }

    /** Convert Power min target % to Watts using provided FTP. */
    fun getPowerTargetMinWatts(ftpWatts: Int): Int? =
        powerTargetMinPercent?.let { PowerZones.percentToWatts(it, ftpWatts) }

    /** Convert Power max target % to Watts using provided FTP. */
    fun getPowerTargetMaxWatts(ftpWatts: Int): Int? =
        powerTargetMaxPercent?.let { PowerZones.percentToWatts(it, ftpWatts) }

    /** Convert HR end target min % to BPM using provided LTHR. */
    fun getHrEndTargetMinBpm(lthrBpm: Int): Int? =
        hrEndTargetMinPercent?.let { HeartRateZones.percentToBpm(it, lthrBpm) }

    /** Convert HR end target max % to BPM using provided LTHR. */
    fun getHrEndTargetMaxBpm(lthrBpm: Int): Int? =
        hrEndTargetMaxPercent?.let { HeartRateZones.percentToBpm(it, lthrBpm) }

    // ==================== Convenience Properties ====================

    /**
     * Whether this step has HR target configured.
     */
    val hasHrTarget: Boolean
        get() = autoAdjustMode == AutoAdjustMode.HR &&
                hrTargetMinPercent != null &&
                hrTargetMaxPercent != null &&
                adjustmentType != null

    /**
     * Whether this step has Power target configured.
     */
    val hasPowerTarget: Boolean
        get() = autoAdjustMode == AutoAdjustMode.POWER &&
                powerTargetMinPercent != null &&
                powerTargetMaxPercent != null &&
                adjustmentType != null

    /**
     * Whether this step has any auto-adjust target (HR or Power).
     */
    val hasAutoAdjustTarget: Boolean
        get() = hasHrTarget || hasPowerTarget

    /**
     * Whether this step is part of a repeat block.
     */
    val isRepeated: Boolean
        get() = repeatIteration != null && repeatTotal != null

    /**
     * Whether this step has an early end HR range condition.
     */
    val hasHrEndTarget: Boolean
        get() = earlyEndCondition == EarlyEndCondition.HR_RANGE &&
                hrEndTargetMinPercent != null &&
                hrEndTargetMaxPercent != null
}
