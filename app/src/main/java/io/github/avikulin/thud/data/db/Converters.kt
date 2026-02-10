package io.github.avikulin.thud.data.db

import android.util.Log
import androidx.room.TypeConverter
import io.github.avikulin.thud.domain.model.AdjustmentScope
import io.github.avikulin.thud.domain.model.AdjustmentType
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.EarlyEndCondition
import io.github.avikulin.thud.domain.model.StepType

/**
 * Type converters for Room database to handle enum types.
 * All converters use safe parsing with fallback defaults to prevent crashes
 * from corrupted or migrated database values.
 */
class Converters {

    @TypeConverter
    fun fromStepType(value: StepType): String = value.name

    @TypeConverter
    fun toStepType(value: String): StepType = try {
        StepType.valueOf(value)
    } catch (_: IllegalArgumentException) {
        Log.e(TAG, "Unknown StepType '$value', defaulting to RUN")
        StepType.RUN
    }

    @TypeConverter
    fun fromDurationType(value: DurationType): String = value.name

    @TypeConverter
    fun toDurationType(value: String): DurationType = try {
        DurationType.valueOf(value)
    } catch (_: IllegalArgumentException) {
        Log.e(TAG, "Unknown DurationType '$value', defaulting to TIME")
        DurationType.TIME
    }

    @TypeConverter
    fun fromAdjustmentType(value: AdjustmentType?): String? = value?.name

    @TypeConverter
    fun toAdjustmentType(value: String?): AdjustmentType? =
        value?.let {
            try {
                AdjustmentType.valueOf(it)
            } catch (_: IllegalArgumentException) {
                Log.e(TAG, "Unknown AdjustmentType '$it', defaulting to null")
                null
            }
        }

    @TypeConverter
    fun fromAutoAdjustMode(value: AutoAdjustMode): String = value.name

    @TypeConverter
    fun toAutoAdjustMode(value: String): AutoAdjustMode = try {
        AutoAdjustMode.valueOf(value)
    } catch (_: IllegalArgumentException) {
        Log.e(TAG, "Unknown AutoAdjustMode '$value', defaulting to NONE")
        AutoAdjustMode.NONE
    }

    @TypeConverter
    fun fromEarlyEndCondition(value: EarlyEndCondition): String = value.name

    @TypeConverter
    fun toEarlyEndCondition(value: String): EarlyEndCondition = try {
        EarlyEndCondition.valueOf(value)
    } catch (_: IllegalArgumentException) {
        Log.e(TAG, "Unknown EarlyEndCondition '$value', defaulting to NONE")
        EarlyEndCondition.NONE
    }

    @TypeConverter
    fun fromAdjustmentScope(value: AdjustmentScope): String = value.name

    @TypeConverter
    fun toAdjustmentScope(value: String): AdjustmentScope = try {
        AdjustmentScope.valueOf(value)
    } catch (_: IllegalArgumentException) {
        Log.e(TAG, "Unknown AdjustmentScope '$value', defaulting to ALL_STEPS")
        AdjustmentScope.ALL_STEPS
    }

    companion object {
        private const val TAG = "Converters"
    }
}
