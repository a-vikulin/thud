package io.github.avikulin.thud.domain.engine

import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.domain.model.StepType

/**
 * Flattens workout steps by expanding repeat blocks into individual execution steps.
 *
 * For example, a workout with:
 *   - Warmup
 *   - Repeat 4x (Run, Recover)
 *   - Cooldown
 *
 * Becomes:
 *   - Warmup
 *   - Run 1/4
 *   - Recover 1/4
 *   - Run 2/4
 *   - Recover 2/4
 *   - Run 3/4
 *   - Recover 3/4
 *   - Run 4/4
 *   - Recover 4/4
 *   - Cooldown
 */
object WorkoutStepFlattener {

    /**
     * Flatten workout steps into execution steps.
     *
     * Uses position-based traversal: child steps immediately follow their parent
     * REPEAT step and have non-null parentRepeatStepId. This approach is robust
     * against ID changes that occur when steps are saved/reloaded from the database.
     *
     * @param steps The workout steps from database, ordered by orderIndex
     * @return List of flattened ExecutionStep ready for execution
     */
    fun flatten(steps: List<WorkoutStep>): List<ExecutionStep> {
        val result = mutableListOf<ExecutionStep>()
        var flatIndex = 0
        var stepIndex = 0
        var topLevelIndex = 0  // Tracks position among top-level steps for identity keys

        while (stepIndex < steps.size) {
            val step = steps[stepIndex]

            if (step.type == StepType.REPEAT) {
                // Collect child steps using position-based traversal
                // Children immediately follow the repeat and have non-null parentRepeatStepId
                val childSteps = mutableListOf<WorkoutStep>()
                var childIndex = stepIndex + 1
                while (childIndex < steps.size && steps[childIndex].parentRepeatStepId != null) {
                    childSteps.add(steps[childIndex])
                    childIndex++
                }

                val repeatCount = step.repeatCount ?: 1

                // Expand the repeat
                for (iteration in 1..repeatCount) {
                    for ((childPos, childStep) in childSteps.withIndex()) {
                        result.add(
                            createExecutionStep(
                                step = childStep,
                                flatIndex = flatIndex++,
                                repeatIteration = iteration,
                                repeatTotal = repeatCount,
                                stepIdentityKey = "r${topLevelIndex}_c${childPos}"
                            )
                        )
                    }
                }

                topLevelIndex++
                // Skip past the repeat and all its children
                stepIndex = childIndex
            } else if (step.parentRepeatStepId == null) {
                // Top-level non-repeat step
                result.add(
                    createExecutionStep(
                        step = step,
                        flatIndex = flatIndex++,
                        repeatIteration = null,
                        repeatTotal = null,
                        stepIdentityKey = "s${topLevelIndex}"
                    )
                )
                topLevelIndex++
                stepIndex++
            } else {
                // Child step encountered outside of repeat context (shouldn't happen)
                // Skip it to avoid duplicating steps
                stepIndex++
            }
        }

        return result
    }

    private fun createExecutionStep(
        step: WorkoutStep,
        flatIndex: Int,
        repeatIteration: Int?,
        repeatTotal: Int?,
        stepIdentityKey: String = ""
    ): ExecutionStep {
        return ExecutionStep(
            stepId = step.id,
            flatIndex = flatIndex,
            type = step.type,
            durationType = step.durationType,
            durationSeconds = step.durationSeconds,
            durationMeters = step.durationMeters,
            paceTargetKph = step.paceTargetKph,
            inclineTargetPercent = step.inclineTargetPercent,
            autoAdjustMode = step.autoAdjustMode,
            adjustmentType = step.adjustmentType,
            hrTargetMinPercent = step.hrTargetMinPercent,
            hrTargetMaxPercent = step.hrTargetMaxPercent,
            powerTargetMinPercent = step.powerTargetMinPercent,
            powerTargetMaxPercent = step.powerTargetMaxPercent,
            earlyEndCondition = step.earlyEndCondition,
            hrEndTargetMinPercent = step.hrEndTargetMinPercent,
            hrEndTargetMaxPercent = step.hrEndTargetMaxPercent,
            repeatIteration = repeatIteration,
            repeatTotal = repeatTotal,
            displayName = formatStepName(step, repeatIteration, repeatTotal),
            stepIdentityKey = stepIdentityKey
        )
    }

    private fun formatStepName(
        step: WorkoutStep,
        iteration: Int?,
        total: Int?
    ): String {
        val baseName = when (step.type) {
            StepType.WARMUP -> "Warmup"
            StepType.RUN -> "Run"
            StepType.RECOVER -> "Recover"
            StepType.REST -> "Rest"
            StepType.COOLDOWN -> "Cooldown"
            StepType.REPEAT -> "Repeat"
        }
        return if (iteration != null && total != null && total > 1) {
            "$baseName $iteration/$total"
        } else {
            baseName
        }
    }

    /**
     * Calculate total estimated duration in seconds for flattened steps.
     * Only counts TIME-based steps.
     */
    fun calculateTotalDurationSeconds(steps: List<ExecutionStep>): Int? {
        val timeBased = steps.filter { it.durationSeconds != null }
        if (timeBased.isEmpty()) return null
        return timeBased.sumOf { it.durationSeconds ?: 0 }
    }

    /**
     * Calculate total estimated distance in meters for flattened steps.
     * Only counts DISTANCE-based steps.
     */
    fun calculateTotalDistanceMeters(steps: List<ExecutionStep>): Int? {
        val distanceBased = steps.filter { it.durationMeters != null }
        if (distanceBased.isEmpty()) return null
        return distanceBased.sumOf { it.durationMeters ?: 0 }
    }
}
