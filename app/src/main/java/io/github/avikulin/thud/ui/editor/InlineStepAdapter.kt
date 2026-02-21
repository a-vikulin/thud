package io.github.avikulin.thud.ui.editor

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.R
import io.github.avikulin.thud.data.entity.WorkoutStep
import io.github.avikulin.thud.domain.model.AdjustmentType
import io.github.avikulin.thud.domain.model.AutoAdjustMode
import io.github.avikulin.thud.domain.model.DurationType
import io.github.avikulin.thud.domain.model.EarlyEndCondition
import io.github.avikulin.thud.domain.model.StepType
import io.github.avikulin.thud.ui.components.DualKnobZoneSlider
import io.github.avikulin.thud.ui.components.TouchSpinner
import io.github.avikulin.thud.util.PaceConverter

/**
 * Adapter for inline step editing in the workout editor.
 * Each step is shown as a 2-row layout with inline controls.
 * Includes a footer with Add Step and Add Repeat buttons.
 */
class InlineStepAdapter(
    private val onStepChanged: (Int, WorkoutStep) -> Unit,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onAddBelow: (Int, StepType) -> Unit,
    private val onDuplicate: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onAddSubstep: (Int, StepType) -> Unit,
    private val onAddStep: (StepType) -> Unit,
    private val onAddRepeat: () -> Unit,
    private val onWarmupToggled: (Boolean) -> Unit = {},
    private val onCooldownToggled: (Boolean) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_STEP = 0
        private const val VIEW_TYPE_FOOTER = 1
        private const val VIEW_TYPE_WARMUP_HEADER = 2
        private const val VIEW_TYPE_COOLDOWN_FOOTER = 3
    }

    // Whether to show sentinel rows (hidden for system workouts)
    var showSentinels: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    // Sentinel state
    var warmupEnabled: Boolean = false
        set(value) {
            field = value
            if (showSentinels) notifyItemChanged(0)
        }
    var cooldownEnabled: Boolean = false
        set(value) {
            field = value
            if (showSentinels) notifyItemChanged(steps.size + 1)
        }
    var warmupSummary: String = ""
        set(value) {
            field = value
            if (showSentinels) notifyItemChanged(0)
        }
    var cooldownSummary: String = ""
        set(value) {
            field = value
            if (showSentinels) notifyItemChanged(steps.size + 1)
        }

    private var steps: List<WorkoutStep> = emptyList()

    // Step types for spinner
    private val stepTypes = listOf(
        StepType.WARMUP,
        StepType.RUN,
        StepType.RECOVER,
        StepType.REST,
        StepType.COOLDOWN
    )

    // Duration types (for planning/chart)
    private val durationTypes = listOf(
        DurationType.TIME,
        DurationType.DISTANCE
    )

    // Early end conditions (for execution)
    private val earlyEndConditions = listOf(
        EarlyEndCondition.NONE,
        EarlyEndCondition.OPEN,
        EarlyEndCondition.HR_RANGE
    )

    // Auto-adjust modes (None/HR/Power)
    private val autoAdjustModes = listOf(
        AutoAdjustMode.NONE,
        AutoAdjustMode.HR,
        AutoAdjustMode.POWER
    )

    // Adjustment types (Speed/Incline)
    private val adjustmentTypes = listOf(
        AdjustmentType.SPEED,
        AdjustmentType.INCLINE
    )

    // HR zones for sliders as % of LTHR (zone start values for zones 2-5)
    // Stored with 1 decimal precision for integer BPM snapping
    var hrZone2StartPercent = 80.0
    var hrZone3StartPercent = 88.0
    var hrZone4StartPercent = 95.0
    var hrZone5StartPercent = 102.0

    // Power zones as % of FTP (zone start values for zones 2-5)
    // Stored with 1 decimal precision for integer watt snapping
    var powerZone2StartPercent = 55.0
    var powerZone3StartPercent = 75.0
    var powerZone4StartPercent = 90.0
    var powerZone5StartPercent = 105.0

    // Threshold values for converting % to absolute (should be updated from settings)
    var userLthrBpm = 170     // Lactate Threshold HR
    var userFtpWatts = 250    // Functional Threshold Power

    // Computed absolute HR zone boundaries (zone start values for zones 2-5)
    val hrZone2Start: Int get() = kotlin.math.round(hrZone2StartPercent * userLthrBpm / 100.0).toInt()
    val hrZone3Start: Int get() = kotlin.math.round(hrZone3StartPercent * userLthrBpm / 100.0).toInt()
    val hrZone4Start: Int get() = kotlin.math.round(hrZone4StartPercent * userLthrBpm / 100.0).toInt()
    val hrZone5Start: Int get() = kotlin.math.round(hrZone5StartPercent * userLthrBpm / 100.0).toInt()

    // Treadmill capabilities (should be set from settings before use)
    var treadmillMinPaceSeconds = 180    // 3:00/km (20 kph)
    var treadmillMaxPaceSeconds = 3600   // 60:00/km (1 kph)
    var treadmillMinIncline = -3.0
    var treadmillMaxIncline = 15.0
    var treadmillInclineStep = 0.5

    /** Number of extra rows before steps (warmup sentinel when showSentinels). */
    private val headerCount: Int get() = if (showSentinels) 1 else 0

    /** Convert adapter position to step list index. Returns -1 if not a step row. */
    private fun toStepIndex(adapterPosition: Int): Int = adapterPosition - headerCount

    /** Convert step list index to adapter position. */
    private fun toAdapterPosition(stepIndex: Int): Int = stepIndex + headerCount

    fun submitList(newSteps: List<WorkoutStep>) {
        val oldSteps = steps
        val oldHeader = headerCount
        val oldExtra = if (showSentinels) 3 else 1  // warmup + cooldown + footer OR just footer
        val newExtra = oldExtra  // sentinel visibility doesn't change mid-submitList

        steps = newSteps

        // Use DiffUtil to compute minimal changes with move detection for animations
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldSteps.size + oldExtra
            override fun getNewListSize(): Int = newSteps.size + newExtra

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldStepIdx = oldItemPosition - oldHeader
                val newStepIdx = newItemPosition - oldHeader
                // Header sentinels
                if (oldItemPosition < oldHeader && newItemPosition < oldHeader) return true
                if (oldItemPosition < oldHeader || newItemPosition < oldHeader) return false
                // Footer items (cooldown sentinel + add buttons)
                if (oldStepIdx >= oldSteps.size && newStepIdx >= newSteps.size) {
                    return (oldItemPosition - oldSteps.size) == (newItemPosition - newSteps.size)
                }
                if (oldStepIdx >= oldSteps.size || newStepIdx >= newSteps.size) return false
                // Compare by id
                return oldSteps[oldStepIdx].id == newSteps[newStepIdx].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldStepIdx = oldItemPosition - oldHeader
                val newStepIdx = newItemPosition - oldHeader
                if (oldItemPosition < oldHeader && newItemPosition < oldHeader) return true
                if (oldItemPosition < oldHeader || newItemPosition < oldHeader) return false
                if (oldStepIdx >= oldSteps.size && newStepIdx >= newSteps.size) return true
                if (oldStepIdx >= oldSteps.size || newStepIdx >= newSteps.size) return false
                return oldSteps[oldStepIdx] == newSteps[newStepIdx]
            }
        }, true)

        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int {
        val sentinelCount = if (showSentinels) 2 else 0  // warmup + cooldown
        return sentinelCount + steps.size + 1  // +1 for footer
    }

    override fun getItemViewType(position: Int): Int {
        if (showSentinels) {
            if (position == 0) return VIEW_TYPE_WARMUP_HEADER
            if (position == steps.size + 1) return VIEW_TYPE_COOLDOWN_FOOTER
            if (position == steps.size + 2) return VIEW_TYPE_FOOTER
            return VIEW_TYPE_STEP
        }
        // No sentinels (system workout editor)
        return if (position < steps.size) VIEW_TYPE_STEP else VIEW_TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_WARMUP_HEADER, VIEW_TYPE_COOLDOWN_FOOTER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_step_sentinel, parent, false)
                SentinelViewHolder(view)
            }
            VIEW_TYPE_FOOTER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_step_footer, parent, false)
                FooterViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_workout_step_inline, parent, false)
                StepViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SentinelViewHolder -> {
                val isWarmup = position == 0
                holder.bind(isWarmup)
            }
            is StepViewHolder -> {
                val stepIndex = toStepIndex(position)
                holder.bind(steps[stepIndex], stepIndex)
            }
            is FooterViewHolder -> holder.bind()
        }
    }

    // ==================== Sentinel ViewHolder ====================

    inner class SentinelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val borderStrip: View = itemView.findViewById(R.id.borderStrip)
        private val checkboxEnabled: CheckBox = itemView.findViewById(R.id.checkboxEnabled)
        private val tvSummary: TextView = itemView.findViewById(R.id.tvSummary)
        fun bind(isWarmup: Boolean) {
            val context = itemView.context

            // Colored border
            val borderColor = if (isWarmup) R.color.sentinel_warmup_border else R.color.sentinel_cooldown_border
            borderStrip.setBackgroundColor(ContextCompat.getColor(context, borderColor))

            // Checkbox text and state
            checkboxEnabled.text = context.getString(
                if (isWarmup) R.string.sentinel_warmup_label else R.string.sentinel_cooldown_label
            )
            checkboxEnabled.setOnCheckedChangeListener(null)  // Clear before setting
            checkboxEnabled.isChecked = if (isWarmup) warmupEnabled else cooldownEnabled
            checkboxEnabled.setOnCheckedChangeListener { _, checked ->
                if (isWarmup) onWarmupToggled(checked) else onCooldownToggled(checked)
            }

            // Summary (only visible when enabled)
            val enabled = if (isWarmup) warmupEnabled else cooldownEnabled
            val summary = if (isWarmup) warmupSummary else cooldownSummary
            tvSummary.visibility = if (enabled) View.VISIBLE else View.GONE
            tvSummary.text = summary

        }
    }

    // ==================== Footer ViewHolder ====================

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnAddStep: Button = itemView.findViewById(R.id.btnAddStep)
        private val btnAddRepeat: Button = itemView.findViewById(R.id.btnAddRepeat)

        fun bind() {
            btnAddStep.setOnClickListener {
                // Default to RUN step, activity will show type selector
                onAddStep(StepType.RUN)
            }
            btnAddRepeat.setOnClickListener {
                onAddRepeat()
            }
        }
    }

    // ==================== Step ViewHolder ====================

    inner class StepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context: Context = itemView.context

        // Action buttons (left side)
        private val btnMoveUp: ImageButton = itemView.findViewById(R.id.btnMoveUp)
        private val btnMoveDown: ImageButton = itemView.findViewById(R.id.btnMoveDown)
        private val btnAddBelow: ImageButton = itemView.findViewById(R.id.btnAddBelow)

        // Type spinner
        private val spinnerType: Spinner = itemView.findViewById(R.id.spinnerType)

        // Pace/Incline containers
        private val containerPace: FrameLayout = itemView.findViewById(R.id.containerPace)
        private val cbPaceProgression: CheckBox = itemView.findViewById(R.id.cbPaceProgression)
        private val containerPaceEnd: FrameLayout = itemView.findViewById(R.id.containerPaceEnd)
        private val containerIncline: FrameLayout = itemView.findViewById(R.id.containerIncline)

        // Duration type
        private val spinnerDurationType: Spinner = itemView.findViewById(R.id.spinnerEndCondition)
        private val containerDuration: FrameLayout = itemView.findViewById(R.id.containerDuration)

        // Early end condition
        private val spinnerEarlyEnd: Spinner = itemView.findViewById(R.id.spinnerEarlyEnd)
        private val containerHrEnd: FrameLayout = itemView.findViewById(R.id.containerHrEnd)
        private val spacerRow1: View = itemView.findViewById(R.id.spacerRow1)

        // Action buttons
        private val btnDuplicate: ImageButton = itemView.findViewById(R.id.btnDuplicate)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        // Auto-adjust row
        private val layoutHrRow: LinearLayout = itemView.findViewById(R.id.layoutHrRow)
        private val spinnerAutoMode: Spinner = itemView.findViewById(R.id.spinnerAutoMode)
        private val spinnerAdjustType: Spinner = itemView.findViewById(R.id.spinnerAdjustType)
        private val containerZoneSlider: FrameLayout = itemView.findViewById(R.id.containerZoneSlider)

        // Repeat controls (inline in row 1)
        private val tvRepeatLabel: TextView = itemView.findViewById(R.id.tvRepeatLabel)
        private val containerRepeatCount: FrameLayout = itemView.findViewById(R.id.containerRepeatCount)
        private val tvRepeatTimes: TextView = itemView.findViewById(R.id.tvRepeatTimes)

        // Track current step and position
        private var currentStep: WorkoutStep? = null
        private var currentPosition: Int = -1
        private var isBinding = false
        private var isUserDragging = false  // True when user is actively dragging a spinner

        // TouchSpinners
        private var paceSpinner: TouchSpinner? = null
        private var paceEndSpinner: TouchSpinner? = null
        private var inclineSpinner: TouchSpinner? = null
        private var durationSpinner: TouchSpinner? = null
        private var repeatCountSpinner: TouchSpinner? = null
        private var zoneSlider: DualKnobZoneSlider? = null
        private var hrEndSlider: DualKnobZoneSlider? = null

        init {
            setupSpinnerAdapters()
            setupButtons()
            createTouchSpinners()
        }

        private fun setupSpinnerAdapters() {
            // Step type spinner adapter
            val typeAdapter = ArrayAdapter(
                context,
                R.layout.spinner_item,
                stepTypes.map { getStepTypeName(it) }
            )
            typeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinnerType.adapter = typeAdapter

            spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isBinding || currentPosition < 0) return
                    val step = currentStep ?: return
                    val newType = stepTypes.getOrNull(position) ?: return
                    if (newType != step.type) {
                        onStepChanged(currentPosition, step.copy(type = newType))
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // Duration type spinner adapter (Time/Distance)
            val durationTypeAdapter = ArrayAdapter(
                context,
                R.layout.spinner_item,
                durationTypes.map { getDurationTypeName(it) }
            )
            durationTypeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinnerDurationType.adapter = durationTypeAdapter

            spinnerDurationType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isBinding || currentPosition < 0) return
                    val step = currentStep ?: return
                    val newType = durationTypes.getOrNull(position) ?: return
                    if (newType != step.durationType) {
                        // Set default values when switching duration type
                        val updatedStep = when (newType) {
                            DurationType.TIME -> step.copy(
                                durationType = newType,
                                durationSeconds = step.durationSeconds ?: 60
                            )
                            DurationType.DISTANCE -> step.copy(
                                durationType = newType,
                                durationMeters = step.durationMeters ?: 1000
                            )
                        }
                        onStepChanged(currentPosition, updatedStep)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // Early end condition spinner adapter (None/Open/HR Range)
            val earlyEndAdapter = ArrayAdapter(
                context,
                R.layout.spinner_item,
                earlyEndConditions.map { getEarlyEndConditionName(it) }
            )
            earlyEndAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinnerEarlyEnd.adapter = earlyEndAdapter

            spinnerEarlyEnd.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isBinding || currentPosition < 0) return
                    val step = currentStep ?: return
                    val newCondition = earlyEndConditions.getOrNull(position) ?: return
                    if (newCondition != step.earlyEndCondition) {
                        val updatedStep = when (newCondition) {
                            EarlyEndCondition.HR_RANGE -> step.copy(
                                earlyEndCondition = newCondition,
                                hrEndTargetMinPercent = step.hrEndTargetMinPercent ?: 70.0,
                                hrEndTargetMaxPercent = step.hrEndTargetMaxPercent ?: 85.0
                            )
                            else -> step.copy(earlyEndCondition = newCondition)
                        }
                        // Update HR End slider visibility (hide spacer when slider is visible)
                        val hrEndVisible = newCondition == EarlyEndCondition.HR_RANGE
                        containerHrEnd.visibility = if (hrEndVisible) View.VISIBLE else View.GONE
                        spacerRow1.visibility = if (hrEndVisible) View.GONE else View.VISIBLE
                        onStepChanged(currentPosition, updatedStep)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // Auto-adjust mode spinner adapter (No/HR/Power)
            val autoModeAdapter = ArrayAdapter(
                context,
                R.layout.spinner_item,
                autoAdjustModes.map { getAutoAdjustModeName(it) }
            )
            autoModeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinnerAutoMode.adapter = autoModeAdapter

            spinnerAutoMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isBinding || currentPosition < 0) return
                    val step = currentStep ?: return
                    val newMode = autoAdjustModes.getOrNull(position) ?: return
                    if (newMode != step.autoAdjustMode) {
                        // Update visibility of adjust type spinner and zone slider
                        val enabled = newMode != AutoAdjustMode.NONE
                        spinnerAdjustType.isEnabled = enabled
                        spinnerAdjustType.alpha = if (enabled) 1f else 0.4f
                        zoneSlider?.isEnabled = enabled
                        zoneSlider?.alpha = if (enabled) 1f else 0.4f

                        // Build updated step with appropriate target values
                        val updatedStep = when (newMode) {
                            AutoAdjustMode.NONE -> step.copy(
                                autoAdjustMode = newMode,
                                adjustmentType = null,
                                hrTargetMinPercent = null,
                                hrTargetMaxPercent = null,
                                powerTargetMinPercent = null,
                                powerTargetMaxPercent = null
                            )
                            AutoAdjustMode.HR -> step.copy(
                                autoAdjustMode = newMode,
                                adjustmentType = step.adjustmentType ?: AdjustmentType.SPEED,
                                hrTargetMinPercent = step.hrTargetMinPercent ?: 75.0,
                                hrTargetMaxPercent = step.hrTargetMaxPercent ?: 85.0,
                                powerTargetMinPercent = null,
                                powerTargetMaxPercent = null
                            )
                            AutoAdjustMode.POWER -> step.copy(
                                autoAdjustMode = newMode,
                                adjustmentType = step.adjustmentType ?: AdjustmentType.SPEED,
                                hrTargetMinPercent = null,
                                hrTargetMaxPercent = null,
                                powerTargetMinPercent = step.powerTargetMinPercent ?: 85.0,
                                powerTargetMaxPercent = step.powerTargetMaxPercent ?: 95.0
                            )
                        }
                        onStepChanged(currentPosition, updatedStep)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // Adjustment type spinner adapter (Pace/Incline)
            val adjustTypeAdapter = ArrayAdapter(
                context,
                R.layout.spinner_item,
                adjustmentTypes.map { getAdjustmentTypeName(it) }
            )
            adjustTypeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinnerAdjustType.adapter = adjustTypeAdapter

            spinnerAdjustType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isBinding || currentPosition < 0) return
                    val step = currentStep ?: return
                    val newAdjustType = adjustmentTypes.getOrNull(position) ?: return
                    if (step.autoAdjustMode != AutoAdjustMode.NONE && newAdjustType != step.adjustmentType) {
                        onStepChanged(currentPosition, step.copy(adjustmentType = newAdjustType))
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        private fun setupButtons() {
            btnMoveUp.setOnClickListener {
                if (currentPosition >= 0) onMoveUp(currentPosition)
            }
            btnMoveDown.setOnClickListener {
                if (currentPosition >= 0) onMoveDown(currentPosition)
            }
            btnAddBelow.setOnClickListener {
                if (currentPosition >= 0) {
                    val step = currentStep
                    if (step?.type == StepType.REPEAT) {
                        // Add substep to this repeat
                        onAddSubstep(currentPosition, StepType.RUN)
                    } else {
                        onAddBelow(currentPosition, StepType.RUN)
                    }
                }
            }
            btnDuplicate.setOnClickListener {
                if (currentPosition >= 0) onDuplicate(currentPosition)
            }
            btnDelete.setOnClickListener {
                if (currentPosition >= 0) onDelete(currentPosition)
            }
        }

        private fun createTouchSpinners() {
            // Pace spinner (mm:ss/km format, stored as seconds per km)
            // Bounds from treadmill capabilities
            paceSpinner = TouchSpinner(context, null).apply {
                format = TouchSpinner.Format.PACE_MMSS
                minValue = treadmillMinPaceSeconds.toDouble()
                maxValue = treadmillMaxPaceSeconds.toDouble()
                step = 1.0         // 1 second base increment
                suffix = "/km"
            }
            paceSpinner?.onValueChanged = onValueChanged@{ paceSeconds ->
                if (!isBinding && currentPosition >= 0) {
                    isUserDragging = true
                    val step = currentStep ?: return@onValueChanged
                    // Convert seconds per km to kph for storage
                    val kph = PaceConverter.paceSecondsToSpeed(paceSeconds.toInt())
                    if (kph != step.paceTargetKph) {
                        currentStep = step.copy(paceTargetKph = kph)
                        // Don't update adapter during drag - wait for drag end
                    }
                }
            }
            paceSpinner?.onDragEnd = {
                isUserDragging = false
                // Now update the adapter with the final value
                if (currentPosition >= 0) {
                    currentStep?.let { onStepChanged(currentPosition, it) }
                }
            }
            containerPace.addView(paceSpinner)

            // End pace spinner (for progression steps)
            paceEndSpinner = TouchSpinner(context, null).apply {
                format = TouchSpinner.Format.PACE_MMSS
                minValue = treadmillMinPaceSeconds.toDouble()
                maxValue = treadmillMaxPaceSeconds.toDouble()
                step = 1.0
                suffix = "/km"
            }
            paceEndSpinner?.onValueChanged = onValueChanged@{ paceSeconds ->
                if (!isBinding && currentPosition >= 0) {
                    isUserDragging = true
                    val step = currentStep ?: return@onValueChanged
                    val kph = PaceConverter.paceSecondsToSpeed(paceSeconds.toInt())
                    if (kph != step.paceEndTargetKph) {
                        currentStep = step.copy(paceEndTargetKph = kph)
                    }
                }
            }
            paceEndSpinner?.onDragEnd = {
                isUserDragging = false
                if (currentPosition >= 0) {
                    currentStep?.let { onStepChanged(currentPosition, it) }
                }
            }
            containerPaceEnd.addView(paceEndSpinner)

            // Pace progression checkbox
            cbPaceProgression.setOnCheckedChangeListener { _, isChecked ->
                if (!isBinding && currentPosition >= 0) {
                    val step = currentStep ?: return@setOnCheckedChangeListener
                    paceEndSpinner?.isEnabled = isChecked
                    paceEndSpinner?.alpha = if (isChecked) 1.0f else 0.4f
                    val newEndTarget = if (isChecked) step.paceTargetKph else null
                    currentStep = step.copy(paceEndTargetKph = newEndTarget)
                    onStepChanged(currentPosition, currentStep!!)
                }
            }

            // Incline spinner (%)
            // Bounds and step from treadmill capabilities
            inclineSpinner = TouchSpinner(context, null).apply {
                format = TouchSpinner.Format.DECIMAL
                minValue = treadmillMinIncline
                maxValue = treadmillMaxIncline
                step = treadmillInclineStep
                suffix = "%"
            }
            inclineSpinner?.onValueChanged = onValueChanged@{ value ->
                if (!isBinding && currentPosition >= 0) {
                    isUserDragging = true
                    val step = currentStep ?: return@onValueChanged
                    if (value != step.inclineTargetPercent) {
                        currentStep = step.copy(inclineTargetPercent = value)
                        // Don't update adapter during drag - wait for drag end
                    }
                }
            }
            inclineSpinner?.onDragEnd = {
                isUserDragging = false
                if (currentPosition >= 0) {
                    currentStep?.let { onStepChanged(currentPosition, it) }
                }
            }
            containerIncline.addView(inclineSpinner)

            // Duration spinner (time)
            durationSpinner = TouchSpinner(context, null).apply {
                format = TouchSpinner.Format.TIME_MMSS
                minValue = 10.0    // 10 seconds minimum
                maxValue = 3600.0  // 60 minutes max
                step = 5.0         // 5 second base increment
                // Sensitivity: medium range
                maxStepMultiplier = 12.0  // max 60 sec per tick
            }
            durationSpinner?.onValueChanged = onValueChanged@{ value ->
                if (!isBinding && currentPosition >= 0) {
                    isUserDragging = true
                    val step = currentStep ?: return@onValueChanged
                    when (step.durationType) {
                        DurationType.TIME -> {
                            if (value.toInt() != step.durationSeconds) {
                                currentStep = step.copy(durationSeconds = value.toInt())
                                // Don't update adapter during drag - wait for drag end
                            }
                        }
                        DurationType.DISTANCE -> {
                            if (value.toInt() != step.durationMeters) {
                                currentStep = step.copy(durationMeters = value.toInt())
                                // Don't update adapter during drag - wait for drag end
                            }
                        }
                    }
                }
            }
            durationSpinner?.onDragEnd = {
                isUserDragging = false
                if (currentPosition >= 0) {
                    currentStep?.let { onStepChanged(currentPosition, it) }
                }
            }
            containerDuration.addView(durationSpinner)

            // Zone slider - works with percentages of threshold (LTHR or FTP)
            // Shows "85%" on top line and calculated absolute value on bottom line
            zoneSlider = DualKnobZoneSlider(context, null).apply {
                mode = DualKnobZoneSlider.Mode.HR  // Default to HR mode
                thresholdValue = userLthrBpm
                setZonesPercent(hrZone2StartPercent, hrZone3StartPercent, hrZone4StartPercent, hrZone5StartPercent)
            }
            zoneSlider?.onRangeChanged = onRangeChanged@{ minPercent, maxPercent ->
                if (!isBinding && currentPosition >= 0) {
                    val step = currentStep ?: return@onRangeChanged
                    // Store with 1 decimal precision (slider snaps to integer BPM/watts)
                    when (step.autoAdjustMode) {
                        AutoAdjustMode.HR -> {
                            if (minPercent != step.hrTargetMinPercent || maxPercent != step.hrTargetMaxPercent) {
                                currentStep = step.copy(hrTargetMinPercent = minPercent, hrTargetMaxPercent = maxPercent)
                                onStepChanged(currentPosition, currentStep!!)
                            }
                        }
                        AutoAdjustMode.POWER -> {
                            if (minPercent != step.powerTargetMinPercent || maxPercent != step.powerTargetMaxPercent) {
                                currentStep = step.copy(powerTargetMinPercent = minPercent, powerTargetMaxPercent = maxPercent)
                                onStepChanged(currentPosition, currentStep!!)
                            }
                        }
                        AutoAdjustMode.NONE -> { /* No-op */ }
                    }
                }
            }
            containerZoneSlider.addView(zoneSlider)

            // Repeat count spinner (integer, 2-100)
            repeatCountSpinner = TouchSpinner(context, null).apply {
                format = TouchSpinner.Format.INTEGER
                minValue = 2.0
                maxValue = 100.0
                step = 1.0
            }
            repeatCountSpinner?.onValueChanged = onValueChanged@{ value ->
                if (!isBinding && currentPosition >= 0) {
                    isUserDragging = true
                    val step = currentStep ?: return@onValueChanged
                    if (value.toInt() != step.repeatCount) {
                        currentStep = step.copy(repeatCount = value.toInt())
                        // Don't update adapter during drag - wait for drag end
                    }
                }
            }
            repeatCountSpinner?.onDragEnd = {
                isUserDragging = false
                if (currentPosition >= 0) {
                    currentStep?.let { onStepChanged(currentPosition, it) }
                }
            }
            containerRepeatCount.addView(repeatCountSpinner)

            // HR End slider (for HR_RANGE early end condition) - HR mode only
            hrEndSlider = DualKnobZoneSlider(context, null).apply {
                mode = DualKnobZoneSlider.Mode.HR
                thresholdValue = userLthrBpm
                setZonesPercent(hrZone2StartPercent, hrZone3StartPercent, hrZone4StartPercent, hrZone5StartPercent)
            }
            hrEndSlider?.onRangeChanged = onRangeChanged@{ minPercent, maxPercent ->
                if (!isBinding && currentPosition >= 0) {
                    val step = currentStep ?: return@onRangeChanged
                    // Store with 1 decimal precision (slider snaps to integer BPM)
                    if (minPercent != step.hrEndTargetMinPercent || maxPercent != step.hrEndTargetMaxPercent) {
                        currentStep = step.copy(hrEndTargetMinPercent = minPercent, hrEndTargetMaxPercent = maxPercent)
                        onStepChanged(currentPosition, currentStep!!)
                    }
                }
            }
            containerHrEnd.addView(hrEndSlider)
        }

        fun bind(step: WorkoutStep, position: Int) {
            isBinding = true

            // If user is actively dragging AND we're rebinding to the same position,
            // skip updating spinner values to avoid interrupting the drag
            val skipSpinnerUpdates = isUserDragging && position == currentPosition

            currentStep = step
            currentPosition = position

            // Handle REPEAT type differently
            if (step.type == StepType.REPEAT) {
                bindRepeatStep(step, skipSpinnerUpdates)
                isBinding = false
                return
            }

            // Indentation for substeps - indent the entire box using margin
            val indent = if (step.parentRepeatStepId != null) {
                context.resources.getDimensionPixelSize(R.dimen.inline_step_indent)
            } else 0
            val baseMargin = context.resources.getDimensionPixelSize(R.dimen.inline_step_padding)
            val layoutParams = itemView.layoutParams as? RecyclerView.LayoutParams
            layoutParams?.let {
                it.marginStart = baseMargin + indent
                it.marginEnd = baseMargin
                itemView.layoutParams = it
            }

            // Show all controls for regular steps
            spinnerType.visibility = View.VISIBLE
            containerPace.visibility = View.VISIBLE
            containerIncline.visibility = View.VISIBLE
            spinnerDurationType.visibility = View.VISIBLE
            spinnerEarlyEnd.visibility = View.VISIBLE
            layoutHrRow.visibility = View.VISIBLE

            // Hide repeat controls (only shown for REPEAT steps)
            tvRepeatLabel.visibility = View.GONE
            containerRepeatCount.visibility = View.GONE
            tvRepeatTimes.visibility = View.GONE

            // Type spinner (always update - changing type is a different operation)
            val typeIndex = stepTypes.indexOf(step.type)
            if (typeIndex >= 0) {
                spinnerType.setSelection(typeIndex)
            }

            // Skip spinner value updates if user is actively dragging
            if (!skipSpinnerUpdates) {
                // Pace spinner (convert kph to seconds per km)
                val paceSeconds = if (step.paceTargetKph > 0) 3600.0 / step.paceTargetKph else 600.0
                paceSpinner?.value = paceSeconds

                // Incline spinner
                inclineSpinner?.value = step.inclineTargetPercent
            }

            // Pace progression
            cbPaceProgression.visibility = View.VISIBLE
            containerPaceEnd.visibility = View.VISIBLE
            val hasProgression = step.paceEndTargetKph != null
            cbPaceProgression.isChecked = hasProgression
            paceEndSpinner?.isEnabled = hasProgression
            paceEndSpinner?.alpha = if (hasProgression) 1.0f else 0.4f
            if (!skipSpinnerUpdates) {
                val endPaceSeconds = if (hasProgression && step.paceEndTargetKph!! > 0) {
                    3600.0 / step.paceEndTargetKph
                } else {
                    // When disabled, show same pace as start
                    if (step.paceTargetKph > 0) 3600.0 / step.paceTargetKph else 600.0
                }
                paceEndSpinner?.value = endPaceSeconds
            }

            // Duration type spinner
            val durationTypeIndex = durationTypes.indexOf(step.durationType)
            if (durationTypeIndex >= 0) {
                spinnerDurationType.setSelection(durationTypeIndex)
            }

            // Duration spinner configuration based on duration type
            // Note: step size is set in createTouchSpinners(), not overridden here
            when (step.durationType) {
                DurationType.TIME -> {
                    containerDuration.visibility = View.VISIBLE
                    durationSpinner?.format = TouchSpinner.Format.TIME_MMSS
                    durationSpinner?.minValue = 10.0    // 10 seconds minimum
                    durationSpinner?.maxValue = 7200.0  // 2 hours max
                    if (!skipSpinnerUpdates) {
                        durationSpinner?.value = (step.durationSeconds ?: 60).toDouble()
                    }
                }
                DurationType.DISTANCE -> {
                    containerDuration.visibility = View.VISIBLE
                    durationSpinner?.format = TouchSpinner.Format.DISTANCE
                    durationSpinner?.minValue = 30.0    // 30m minimum
                    durationSpinner?.maxValue = 50000.0 // 50km max
                    if (!skipSpinnerUpdates) {
                        durationSpinner?.value = (step.durationMeters ?: 1000).toDouble()
                    }
                }
            }

            // Early end condition spinner
            val earlyEndIndex = earlyEndConditions.indexOf(step.earlyEndCondition)
            if (earlyEndIndex >= 0) {
                spinnerEarlyEnd.setSelection(earlyEndIndex)
            }

            // HR End slider visibility (for HR_RANGE early end) - hide spacer when slider is visible
            val hrEndVisible = step.earlyEndCondition == EarlyEndCondition.HR_RANGE
            containerHrEnd.visibility = if (hrEndVisible) View.VISIBLE else View.GONE
            spacerRow1.visibility = if (hrEndVisible) View.GONE else View.VISIBLE
            if (!skipSpinnerUpdates && step.hrEndTargetMinPercent != null && step.hrEndTargetMaxPercent != null) {
                // Set range as percentages - slider displays both % and absolute values
                hrEndSlider?.setRangePercent(step.hrEndTargetMinPercent.toDouble(), step.hrEndTargetMaxPercent.toDouble())
            }

            // Auto-adjust row
            val autoEnabled = step.autoAdjustMode != AutoAdjustMode.NONE
            spinnerAdjustType.isEnabled = autoEnabled
            spinnerAdjustType.alpha = if (autoEnabled) 1f else 0.4f
            zoneSlider?.isEnabled = autoEnabled
            zoneSlider?.alpha = if (autoEnabled) 1f else 0.4f

            // Auto-adjust mode spinner
            val autoModeIndex = autoAdjustModes.indexOf(step.autoAdjustMode)
            if (autoModeIndex >= 0) {
                spinnerAutoMode.setSelection(autoModeIndex)
            }

            // Adjustment type spinner
            val adjustTypeIndex = if (autoEnabled && step.adjustmentType != null) {
                adjustmentTypes.indexOf(step.adjustmentType)
            } else 0
            if (adjustTypeIndex >= 0) {
                spinnerAdjustType.setSelection(adjustTypeIndex)
            }

            // Configure zone slider based on auto-adjust mode
            if (!skipSpinnerUpdates) {
                when (step.autoAdjustMode) {
                    AutoAdjustMode.HR -> {
                        zoneSlider?.mode = DualKnobZoneSlider.Mode.HR
                        zoneSlider?.thresholdValue = userLthrBpm
                        zoneSlider?.setZonesPercent(hrZone2StartPercent, hrZone3StartPercent, hrZone4StartPercent, hrZone5StartPercent)
                        val minPercent = (step.hrTargetMinPercent ?: 75).toDouble()
                        val maxPercent = (step.hrTargetMaxPercent ?: 85).toDouble()
                        zoneSlider?.setRangePercent(minPercent, maxPercent)
                    }
                    AutoAdjustMode.POWER -> {
                        zoneSlider?.mode = DualKnobZoneSlider.Mode.POWER
                        zoneSlider?.thresholdValue = userFtpWatts
                        zoneSlider?.setZonesPercent(powerZone2StartPercent, powerZone3StartPercent, powerZone4StartPercent, powerZone5StartPercent)
                        val minPercent = (step.powerTargetMinPercent ?: 85).toDouble()
                        val maxPercent = (step.powerTargetMaxPercent ?: 95).toDouble()
                        zoneSlider?.setRangePercent(minPercent, maxPercent)
                    }
                    AutoAdjustMode.NONE -> {
                        // Use default HR zones for display even when disabled
                        zoneSlider?.mode = DualKnobZoneSlider.Mode.HR
                        zoneSlider?.thresholdValue = userLthrBpm
                        zoneSlider?.setZonesPercent(hrZone2StartPercent, hrZone3StartPercent, hrZone4StartPercent, hrZone5StartPercent)
                        zoneSlider?.setRangePercent(75.0, 85.0)
                    }
                }
            }

            isBinding = false
        }

        private fun bindRepeatStep(step: WorkoutStep, skipSpinnerUpdates: Boolean) {
            // For REPEAT steps, show only repeat count controls (single row)
            // Apply same margin as other level 1 steps
            val baseMargin = context.resources.getDimensionPixelSize(R.dimen.inline_step_padding)
            val layoutParams = itemView.layoutParams as? RecyclerView.LayoutParams
            layoutParams?.let {
                it.marginStart = baseMargin
                it.marginEnd = baseMargin
                itemView.layoutParams = it
            }

            // Hide regular step controls
            spinnerType.visibility = View.GONE
            containerPace.visibility = View.GONE
            cbPaceProgression.visibility = View.GONE
            containerPaceEnd.visibility = View.GONE
            containerIncline.visibility = View.GONE
            spinnerDurationType.visibility = View.GONE
            containerDuration.visibility = View.GONE
            spinnerEarlyEnd.visibility = View.GONE
            containerHrEnd.visibility = View.GONE
            layoutHrRow.visibility = View.GONE

            // Show inline repeat controls in row 1
            tvRepeatLabel.visibility = View.VISIBLE
            containerRepeatCount.visibility = View.VISIBLE
            tvRepeatTimes.visibility = View.VISIBLE
            spacerRow1.visibility = View.VISIBLE

            if (!skipSpinnerUpdates) {
                repeatCountSpinner?.value = (step.repeatCount ?: 2).toDouble()
            }
        }

        private fun getStepTypeName(type: StepType): String {
            return when (type) {
                StepType.WARMUP -> context.getString(R.string.step_type_warmup)
                StepType.RUN -> context.getString(R.string.step_type_run)
                StepType.RECOVER -> context.getString(R.string.step_type_recover)
                StepType.REST -> context.getString(R.string.step_type_rest)
                StepType.COOLDOWN -> context.getString(R.string.step_type_cooldown)
                StepType.REPEAT -> context.getString(R.string.step_type_repeat)
            }
        }

        private fun getDurationTypeName(type: DurationType): String {
            return when (type) {
                DurationType.TIME -> context.getString(R.string.duration_type_time)
                DurationType.DISTANCE -> context.getString(R.string.duration_type_distance)
            }
        }

        private fun getEarlyEndConditionName(condition: EarlyEndCondition): String {
            return when (condition) {
                EarlyEndCondition.NONE -> context.getString(R.string.early_end_none)
                EarlyEndCondition.OPEN -> context.getString(R.string.early_end_open)
                EarlyEndCondition.HR_RANGE -> context.getString(R.string.early_end_hr_range)
            }
        }

        private fun getAutoAdjustModeName(mode: AutoAdjustMode): String {
            return when (mode) {
                AutoAdjustMode.NONE -> context.getString(R.string.auto_adjust_none)
                AutoAdjustMode.HR -> context.getString(R.string.auto_adjust_hr)
                AutoAdjustMode.POWER -> context.getString(R.string.auto_adjust_power)
            }
        }

        private fun getAdjustmentTypeName(type: AdjustmentType): String {
            return when (type) {
                AdjustmentType.SPEED -> context.getString(R.string.hr_adjust_pace)
                AdjustmentType.INCLINE -> context.getString(R.string.hr_adjust_incline)
            }
        }

    }
}
