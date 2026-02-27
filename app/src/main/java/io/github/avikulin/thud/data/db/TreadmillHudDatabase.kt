package io.github.avikulin.thud.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.avikulin.thud.data.entity.SpeedCalibrationPoint
import io.github.avikulin.thud.data.entity.Workout
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.service.ProfileManager
import java.io.File

/**
 * Room database for tHUD app.
 */
@Database(
    entities = [Workout::class, WorkoutStep::class, SpeedCalibrationPoint::class],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TreadmillHudDatabase : RoomDatabase() {

    abstract fun workoutDao(): WorkoutDao
    abstract fun speedCalibrationDao(): SpeedCalibrationDao

    companion object {
        private const val DATABASE_NAME = "treadmillhud.db"

        private val instances = mutableMapOf<String, TreadmillHudDatabase>()

        /**
         * Migration from version 1 to 2:
         * - Add hrEndTargetMin and hrEndTargetMax columns to workout_steps table
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_steps ADD COLUMN hrEndTargetMin INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE workout_steps ADD COLUMN hrEndTargetMax INTEGER DEFAULT NULL")
            }
        }

        /**
         * Migration from version 2 to 3:
         * - Add earlyEndCondition column to workout_steps table
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_steps ADD COLUMN earlyEndCondition TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /**
         * Migration from version 3 to 4:
         * - Add estimatedTss column to workouts table
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN estimatedTss INTEGER DEFAULT NULL")
            }
        }

        /**
         * Migration from version 4 to 5:
         * - Convert HR targets from BPM to % of LTHR
         * - Add Power targets as % of FTP
         * - Add autoAdjustMode column
         * - Rename hrAdjustmentType to adjustmentType
         *
         * Uses default LTHR of 170 BPM for conversion (reasonable average).
         * Users can update their actual LTHR in settings.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val DEFAULT_LTHR = 170  // Reasonable default for migration

                // Create new table with updated schema
                db.execSQL("""
                    CREATE TABLE workout_steps_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workoutId INTEGER NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        durationType TEXT NOT NULL,
                        durationSeconds INTEGER,
                        durationMeters INTEGER,
                        earlyEndCondition TEXT NOT NULL DEFAULT 'NONE',
                        hrEndTargetMinPercent INTEGER,
                        hrEndTargetMaxPercent INTEGER,
                        paceTargetKph REAL NOT NULL,
                        inclineTargetPercent REAL NOT NULL,
                        autoAdjustMode TEXT NOT NULL DEFAULT 'NONE',
                        adjustmentType TEXT,
                        hrTargetMinPercent INTEGER,
                        hrTargetMaxPercent INTEGER,
                        powerTargetMinPercent INTEGER,
                        powerTargetMaxPercent INTEGER,
                        repeatCount INTEGER,
                        parentRepeatStepId INTEGER,
                        FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE
                    )
                """)

                // Copy data from old table, converting BPM to % of LTHR
                // Formula: percent = bpm * 100 / LTHR
                db.execSQL("""
                    INSERT INTO workout_steps_new (
                        id, workoutId, orderIndex, type, durationType,
                        durationSeconds, durationMeters, earlyEndCondition,
                        hrEndTargetMinPercent, hrEndTargetMaxPercent,
                        paceTargetKph, inclineTargetPercent,
                        autoAdjustMode, adjustmentType,
                        hrTargetMinPercent, hrTargetMaxPercent,
                        powerTargetMinPercent, powerTargetMaxPercent,
                        repeatCount, parentRepeatStepId
                    )
                    SELECT
                        id, workoutId, orderIndex, type, durationType,
                        durationSeconds, durationMeters, earlyEndCondition,
                        CASE WHEN hrEndTargetMin IS NOT NULL THEN ROUND(hrEndTargetMin * 100.0 / $DEFAULT_LTHR) ELSE NULL END,
                        CASE WHEN hrEndTargetMax IS NOT NULL THEN ROUND(hrEndTargetMax * 100.0 / $DEFAULT_LTHR) ELSE NULL END,
                        paceTargetKph, inclineTargetPercent,
                        CASE WHEN hrTargetMin IS NOT NULL AND hrTargetMax IS NOT NULL AND hrAdjustmentType IS NOT NULL THEN 'HR' ELSE 'NONE' END,
                        hrAdjustmentType,
                        CASE WHEN hrTargetMin IS NOT NULL THEN ROUND(hrTargetMin * 100.0 / $DEFAULT_LTHR) ELSE NULL END,
                        CASE WHEN hrTargetMax IS NOT NULL THEN ROUND(hrTargetMax * 100.0 / $DEFAULT_LTHR) ELSE NULL END,
                        NULL, NULL,
                        repeatCount, parentRepeatStepId
                    FROM workout_steps
                """)

                // Drop old table
                db.execSQL("DROP TABLE workout_steps")

                // Rename new table to original name
                db.execSQL("ALTER TABLE workout_steps_new RENAME TO workout_steps")

                // Recreate index
                db.execSQL("CREATE INDEX index_workout_steps_workoutId ON workout_steps(workoutId)")
            }
        }

        /**
         * Migration from version 5 to 6:
         * - Change HR/Power target percentages from INTEGER to REAL for 1 decimal precision
         * - This supports integer BPM/watt snapping while preserving percentage precision
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create new table with REAL columns for percent fields
                db.execSQL("""
                    CREATE TABLE workout_steps_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workoutId INTEGER NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        durationType TEXT NOT NULL,
                        durationSeconds INTEGER,
                        durationMeters INTEGER,
                        earlyEndCondition TEXT NOT NULL DEFAULT 'NONE',
                        hrEndTargetMinPercent REAL,
                        hrEndTargetMaxPercent REAL,
                        paceTargetKph REAL NOT NULL,
                        inclineTargetPercent REAL NOT NULL,
                        autoAdjustMode TEXT NOT NULL DEFAULT 'NONE',
                        adjustmentType TEXT,
                        hrTargetMinPercent REAL,
                        hrTargetMaxPercent REAL,
                        powerTargetMinPercent REAL,
                        powerTargetMaxPercent REAL,
                        repeatCount INTEGER,
                        parentRepeatStepId INTEGER,
                        FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE
                    )
                """)

                // Copy data from old table (INTEGER → REAL is automatic in SQLite)
                db.execSQL("""
                    INSERT INTO workout_steps_new (
                        id, workoutId, orderIndex, type, durationType,
                        durationSeconds, durationMeters, earlyEndCondition,
                        hrEndTargetMinPercent, hrEndTargetMaxPercent,
                        paceTargetKph, inclineTargetPercent,
                        autoAdjustMode, adjustmentType,
                        hrTargetMinPercent, hrTargetMaxPercent,
                        powerTargetMinPercent, powerTargetMaxPercent,
                        repeatCount, parentRepeatStepId
                    )
                    SELECT
                        id, workoutId, orderIndex, type, durationType,
                        durationSeconds, durationMeters, earlyEndCondition,
                        hrEndTargetMinPercent, hrEndTargetMaxPercent,
                        paceTargetKph, inclineTargetPercent,
                        autoAdjustMode, adjustmentType,
                        hrTargetMinPercent, hrTargetMaxPercent,
                        powerTargetMinPercent, powerTargetMaxPercent,
                        repeatCount, parentRepeatStepId
                    FROM workout_steps
                """)

                // Drop old table
                db.execSQL("DROP TABLE workout_steps")

                // Rename new table to original name
                db.execSQL("ALTER TABLE workout_steps_new RENAME TO workout_steps")

                // Recreate index
                db.execSQL("CREATE INDEX index_workout_steps_workoutId ON workout_steps(workoutId)")
            }
        }

        /**
         * Migration from version 6 to 7:
         * - Add systemWorkoutType column for warmup/cooldown templates
         * - Add useDefaultWarmup/useDefaultCooldown flags to attach templates
         * - Add lastExecutedAt for workout list ordering
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN systemWorkoutType TEXT")
                db.execSQL("ALTER TABLE workouts ADD COLUMN useDefaultWarmup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workouts ADD COLUMN useDefaultCooldown INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workouts ADD COLUMN lastExecutedAt INTEGER")
            }
        }

        /**
         * Migration from version 7 to 8:
         * - Add adjustmentScope column for per-workout coefficient scoping
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN adjustmentScope TEXT NOT NULL DEFAULT 'ALL_STEPS'")
            }
        }

        /**
         * Migration from version 8 to 9:
         * - Add paceEndTargetKph column for pace progression (gradual speed change within a step)
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_steps ADD COLUMN paceEndTargetKph REAL DEFAULT NULL")
            }
        }

        /**
         * Migration from version 9 to 10:
         * - Add speed_calibration_points table for Stryd auto-calibration data
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE speed_calibration_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER NOT NULL,
                        treadmillKph REAL NOT NULL,
                        strydKph REAL NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX index_speed_calibration_points_runId ON speed_calibration_points(runId)")
            }
        }

        /**
         * Get a database instance for a specific absolute path.
         * Each profile has its own DB at files/profiles/<id>/treadmillhud.db.
         */
        fun getInstance(context: Context, dbPath: String): TreadmillHudDatabase {
            return synchronized(this) {
                instances[dbPath] ?: buildDatabase(context, dbPath).also { instances[dbPath] = it }
            }
        }

        /**
         * Convenience: get the database instance for the active profile.
         */
        fun getActiveInstance(context: Context): TreadmillHudDatabase {
            val dbPath = ProfileManager.dbPath(context, ProfileManager.getActiveProfileId(context))
            return getInstance(context, dbPath)
        }

        /**
         * Close all open database instances. Call during profile switch.
         */
        fun closeAll() {
            synchronized(this) {
                instances.values.forEach { it.close() }
                instances.clear()
            }
        }

        /**
         * Close a specific database instance by path.
         */
        fun closeDatabase(dbPath: String) {
            synchronized(this) {
                instances.remove(dbPath)?.close()
            }
        }

        private fun buildDatabase(context: Context, dbPath: String): TreadmillHudDatabase {
            File(dbPath).parentFile?.mkdirs()
            return Room.databaseBuilder(
                context.applicationContext,
                TreadmillHudDatabase::class.java,
                dbPath
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
        }
    }
}
