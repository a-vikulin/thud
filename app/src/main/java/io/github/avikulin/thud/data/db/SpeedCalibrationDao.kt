package io.github.avikulin.thud.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.avikulin.thud.data.entity.SpeedCalibrationPoint

@Dao
interface SpeedCalibrationDao {

    @Insert
    suspend fun insertAll(points: List<SpeedCalibrationPoint>)

    /** Get all points for the last N runs (by most recent runId). */
    @Query("""
        SELECT * FROM speed_calibration_points
        WHERE runId IN (
            SELECT DISTINCT runId FROM speed_calibration_points
            ORDER BY runId DESC LIMIT :maxRuns
        )
    """)
    suspend fun getPointsForLastRuns(maxRuns: Int): List<SpeedCalibrationPoint>

    /** Delete runs older than the newest N. */
    @Query("""
        DELETE FROM speed_calibration_points
        WHERE runId NOT IN (
            SELECT DISTINCT runId FROM speed_calibration_points
            ORDER BY runId DESC LIMIT :keepRuns
        )
    """)
    suspend fun trimOldRuns(keepRuns: Int)

    /** Count of distinct runs stored. */
    @Query("SELECT COUNT(DISTINCT runId) FROM speed_calibration_points")
    suspend fun getRunCount(): Int

    /** Total point count for last N runs (for UI display). */
    @Query("""
        SELECT COUNT(*) FROM speed_calibration_points
        WHERE runId IN (
            SELECT DISTINCT runId FROM speed_calibration_points
            ORDER BY runId DESC LIMIT :maxRuns
        )
    """)
    suspend fun getPointCountForLastRuns(maxRuns: Int): Int
}
