package io.github.avikulin.thud.util

import io.github.avikulin.thud.util.HeartRateZones

/**
 * Utility object for power zone calculations.
 * All power targets and zones are stored as % of FTP (Functional Threshold Power).
 *
 * Zone boundaries use "zone start" semantics:
 * - zone2Start = 138W means zone 2 begins at 138W (values >= 138 are zone 2)
 * - zone3Start = 188W means zone 3 begins at 188W (values >= 188 are zone 3)
 * - etc.
 */
object PowerZones {

    /**
     * Determine which Power zone a given wattage falls into.
     * Zone boundaries are absolute watt values where each zone starts.
     *
     * @param watts Current power in watts
     * @param zone2Start Watts where zone 2 begins (values >= this are zone 2+)
     * @param zone3Start Watts where zone 3 begins (values >= this are zone 3+)
     * @param zone4Start Watts where zone 4 begins (values >= this are zone 4+)
     * @param zone5Start Watts where zone 5 begins (values >= this are zone 5)
     * @return Zone number 0-5 (0 = no data, 1-5 = zones)
     */
    fun getZone(
        watts: Double,
        zone2Start: Int,
        zone3Start: Int,
        zone4Start: Int,
        zone5Start: Int
    ): Int {
        return when {
            watts <= 0 -> 0  // No power data
            watts < zone2Start -> 1
            watts < zone3Start -> 2
            watts < zone4Start -> 3
            watts < zone5Start -> 4
            else -> 5
        }
    }

    /**
     * Get the color resource ID for a given power zone.
     * Uses the same colors as HR zones for consistency.
     *
     * @param zone Zone number 1-5
     * @return Color resource ID
     */
    fun getZoneColorResId(zone: Int): Int = HeartRateZones.getZoneColorResId(zone)

    // ==================== Conversion Helpers ====================

    /**
     * Convert % of FTP to Watts.
     *
     * @param percent Percentage of FTP (e.g., 90.5 for 90.5%)
     * @param ftpWatts User's FTP in watts
     * @return Power in watts (rounded to nearest integer)
     */
    fun percentToWatts(percent: Double, ftpWatts: Int): Int = kotlin.math.round(percent * ftpWatts / 100.0).toInt()

    /**
     * Convert Watts to % of FTP.
     *
     * @param watts Power in watts
     * @param ftpWatts User's FTP in watts
     * @return Percentage of FTP
     */
    fun wattsToPercent(watts: Int, ftpWatts: Int): Int =
        if (ftpWatts > 0) watts * 100 / ftpWatts else 0

    /**
     * Convert Watts to % of FTP (Double version).
     *
     * @param watts Power in watts
     * @param ftpWatts User's FTP in watts
     * @return Percentage of FTP as Double for precision
     */
    fun wattsToPercentDouble(watts: Double, ftpWatts: Int): Double =
        if (ftpWatts > 0) watts * 100.0 / ftpWatts else 0.0

    /**
     * Get zone boundary in Watts.
     *
     * @param ftpWatts User's FTP in watts
     * @param zoneStartPercent Zone start boundary as % of FTP
     * @return Zone boundary in watts
     */
    fun getZoneBoundaryWatts(ftpWatts: Int, zoneStartPercent: Double): Int =
        percentToWatts(zoneStartPercent, ftpWatts)
}
