package io.github.avikulin.thud.util

import io.github.avikulin.thud.R
import io.github.avikulin.thud.domain.model.StepType

/**
 * Utility object for heart rate zone and step type color calculations.
 * Centralizes color logic to avoid duplication across UI components.
 *
 * HR targets and zones can be stored as either:
 * - Absolute BPM values (legacy)
 * - % of LTHR (Lactate Threshold HR) - preferred for portability
 *
 * Zone boundaries use "zone start" semantics:
 * - zone2Start = 136 means zone 2 begins at 136 bpm (values >= 136 are zone 2)
 * - zone3Start = 150 means zone 3 begins at 150 bpm (values >= 150 are zone 3)
 * - etc.
 */
object HeartRateZones {

    /**
     * Determine which HR zone a given BPM falls into.
     * Zone boundaries are absolute BPM values where each zone starts.
     *
     * @param bpm Heart rate in beats per minute
     * @param zone2Start BPM where zone 2 begins (values >= this are zone 2+)
     * @param zone3Start BPM where zone 3 begins (values >= this are zone 3+)
     * @param zone4Start BPM where zone 4 begins (values >= this are zone 4+)
     * @param zone5Start BPM where zone 5 begins (values >= this are zone 5)
     * @return Zone number 0-5 (0 = no data, 1-5 = zones)
     */
    fun getZone(
        bpm: Double,
        zone2Start: Int,
        zone3Start: Int,
        zone4Start: Int,
        zone5Start: Int
    ): Int {
        return when {
            bpm <= 0 -> 0  // No HR data
            bpm < zone2Start -> 1
            bpm < zone3Start -> 2
            bpm < zone4Start -> 3
            bpm < zone5Start -> 4
            else -> 5
        }
    }

    /**
     * Determine which HR zone a given BPM falls into.
     * Zone boundaries are percentages of LTHR where each zone starts.
     *
     * @param bpm Heart rate in beats per minute
     * @param lthrBpm User's Lactate Threshold HR in BPM
     * @param z2StartPercent % of LTHR where zone 2 begins (e.g., 80)
     * @param z3StartPercent % of LTHR where zone 3 begins (e.g., 88)
     * @param z4StartPercent % of LTHR where zone 4 begins (e.g., 95)
     * @param z5StartPercent % of LTHR where zone 5 begins (e.g., 102)
     * @return Zone number 0-5 (0 = no data, 1-5 = zones)
     */
    fun getZoneFromPercent(
        bpm: Double,
        lthrBpm: Int,
        z2StartPercent: Int,
        z3StartPercent: Int,
        z4StartPercent: Int,
        z5StartPercent: Int
    ): Int {
        if (bpm <= 0 || lthrBpm <= 0) return 0

        val percentOfLthr = (bpm * 100 / lthrBpm).toInt()
        return when {
            percentOfLthr < z2StartPercent -> 1
            percentOfLthr < z3StartPercent -> 2
            percentOfLthr < z4StartPercent -> 3
            percentOfLthr < z5StartPercent -> 4
            else -> 5
        }
    }

    /**
     * Get the color resource ID for a given HR zone.
     * @param zone Zone number 1-5
     * @return Color resource ID
     */
    fun getZoneColorResId(zone: Int): Int {
        return when (zone) {
            1 -> R.color.hr_zone_1
            2 -> R.color.hr_zone_2
            3 -> R.color.hr_zone_3
            4 -> R.color.hr_zone_4
            5 -> R.color.hr_zone_5
            else -> R.color.hr_zone_1  // Default to zone 1 color
        }
    }

    /**
     * Get the color resource ID for a given step type.
     * @param type Step type
     * @return Color resource ID
     */
    fun getStepTypeColorResId(type: StepType): Int {
        return when (type) {
            StepType.WARMUP -> R.color.step_warmup
            StepType.RUN -> R.color.step_run
            StepType.RECOVER -> R.color.step_recover
            StepType.REST -> R.color.step_rest
            StepType.COOLDOWN -> R.color.step_cooldown
            StepType.REPEAT -> R.color.step_pending
        }
    }

    // ==================== Conversion Helpers ====================

    /**
     * Convert % of LTHR to BPM.
     *
     * @param percent Percentage of LTHR (e.g., 85.3 for 85.3%)
     * @param lthrBpm User's LTHR in BPM
     * @return Heart rate in BPM (rounded to nearest integer)
     */
    fun percentToBpm(percent: Double, lthrBpm: Int): Int = kotlin.math.round(percent * lthrBpm / 100.0).toInt()

    /**
     * Convert BPM to % of LTHR.
     *
     * @param bpm Heart rate in BPM
     * @param lthrBpm User's LTHR in BPM
     * @return Percentage of LTHR
     */
    fun bpmToPercent(bpm: Int, lthrBpm: Int): Int =
        if (lthrBpm > 0) bpm * 100 / lthrBpm else 0

    /**
     * Convert BPM to % of LTHR (Double version).
     *
     * @param bpm Heart rate in BPM
     * @param lthrBpm User's LTHR in BPM
     * @return Percentage of LTHR as Double for precision
     */
    fun bpmToPercentDouble(bpm: Double, lthrBpm: Int): Double =
        if (lthrBpm > 0) bpm * 100.0 / lthrBpm else 0.0

    /**
     * Get zone boundary in BPM.
     *
     * @param lthrBpm User's LTHR in BPM
     * @param zoneStartPercent Zone start boundary as % of LTHR
     * @return Zone boundary in BPM
     */
    fun getZoneBoundaryBpm(lthrBpm: Int, zoneStartPercent: Double): Int =
        percentToBpm(zoneStartPercent, lthrBpm)
}
