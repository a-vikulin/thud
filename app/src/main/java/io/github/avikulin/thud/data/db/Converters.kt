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
    fun toStepType(value: String): StepType =
        stepTypeMap[value] ?: StepType.RUN.also { Log.e(TAG, "Unknown StepType '$value', defaulting to RUN") }

    @TypeConverter
    fun fromDurationType(value: DurationType): String = value.name

    @TypeConverter
    fun toDurationType(value: String): DurationType =
        durationTypeMap[value] ?: DurationType.TIME.also { Log.e(TAG, "Unknown DurationType '$value', defaulting to TIME") }

    @TypeConverter
    fun fromAdjustmentType(value: AdjustmentType?): String? = value?.name

    @TypeConverter
    fun toAdjustmentType(value: String?): AdjustmentType? =
        value?.let { adjustmentTypeMap[it] ?: null.also { _ -> Log.e(TAG, "Unknown AdjustmentType '$it', defaulting to null") } }

    @TypeConverter
    fun fromAutoAdjustMode(value: AutoAdjustMode): String = value.name

    @TypeConverter
    fun toAutoAdjustMode(value: String): AutoAdjustMode =
        autoAdjustModeMap[value] ?: AutoAdjustMode.NONE.also { Log.e(TAG, "Unknown AutoAdjustMode '$value', defaulting to NONE") }

    @TypeConverter
    fun fromEarlyEndCondition(value: EarlyEndCondition): String = value.name

    @TypeConverter
    fun toEarlyEndCondition(value: String): EarlyEndCondition =
        earlyEndConditionMap[value] ?: EarlyEndCondition.NONE.also { Log.e(TAG, "Unknown EarlyEndCondition '$value', defaulting to NONE") }

    @TypeConverter
    fun fromAdjustmentScope(value: AdjustmentScope): String = value.name

    @TypeConverter
    fun toAdjustmentScope(value: String): AdjustmentScope =
        adjustmentScopeMap[value] ?: AdjustmentScope.ALL_STEPS.also { Log.e(TAG, "Unknown AdjustmentScope '$value', defaulting to ALL_STEPS") }

    companion object {
        private const val TAG = "Converters"

        // Pre-built lookup maps — O(1) map lookup instead of valueOf() with try/catch
        private val stepTypeMap = StepType.entries.associateBy { it.name }
        private val durationTypeMap = DurationType.entries.associateBy { it.name }
        private val adjustmentTypeMap = AdjustmentType.entries.associateBy { it.name }
        private val autoAdjustModeMap = AutoAdjustMode.entries.associateBy { it.name }
        private val earlyEndConditionMap = EarlyEndCondition.entries.associateBy { it.name }
        private val adjustmentScopeMap = AdjustmentScope.entries.associateBy { it.name }
    }
}
