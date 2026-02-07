package io.github.avikulin.thud.data.db

import androidx.room.TypeConverter
import io.github.avikulin.thud.domain.model.AdjustmentScope
import io.github.avikulin.thud.domain.model.AdjustmentType
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.EarlyEndCondition
import io.github.avikulin.thud.domain.model.StepType

/**
 * Type converters for Room database to handle enum types.
 */
class Converters {

    @TypeConverter
    fun fromStepType(value: StepType): String = value.name

    @TypeConverter
    fun toStepType(value: String): StepType = StepType.valueOf(value)

    @TypeConverter
    fun fromDurationType(value: DurationType): String = value.name

    @TypeConverter
    fun toDurationType(value: String): DurationType = DurationType.valueOf(value)

    @TypeConverter
    fun fromAdjustmentType(value: AdjustmentType?): String? = value?.name

    @TypeConverter
    fun toAdjustmentType(value: String?): AdjustmentType? =
        value?.let { AdjustmentType.valueOf(it) }

    @TypeConverter
    fun fromAutoAdjustMode(value: AutoAdjustMode): String = value.name

    @TypeConverter
    fun toAutoAdjustMode(value: String): AutoAdjustMode = AutoAdjustMode.valueOf(value)

    @TypeConverter
    fun fromEarlyEndCondition(value: EarlyEndCondition): String = value.name

    @TypeConverter
    fun toEarlyEndCondition(value: String): EarlyEndCondition = EarlyEndCondition.valueOf(value)

    @TypeConverter
    fun fromAdjustmentScope(value: AdjustmentScope): String = value.name

    @TypeConverter
    fun toAdjustmentScope(value: String): AdjustmentScope = AdjustmentScope.valueOf(value)
}
