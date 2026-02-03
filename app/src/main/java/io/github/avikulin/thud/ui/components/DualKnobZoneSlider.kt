package io.github.avikulin.thud.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.util.HeartRateZones
import io.github.avikulin.thud.R
import io.github.avikulin.thud.util.PowerZones
import java.util.Locale
import kotlin.math.abs

/**
 * Dual-knob slider for selecting target range as percentage of threshold.
 * Works with both HR (% of LTHR) and Power (% of FTP) targets.
 *
 * Shows a horizontal bar colored by zones with two draggable handles for min/max.
 * Each handle displays both percentage and calculated absolute value:
 * "85%" on top line, "145" on bottom line.
 */
@SuppressLint("ClickableViewAccessibility")
class DualKnobZoneSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_PERCENT = 50
        private const val MAX_PERCENT = 120
        private const val MIN_GAP = 3  // Minimum gap between min and max handles in %
    }

    /**
     * Mode determines the unit suffix and zone calculation.
     */
    enum class Mode {
        HR,     // Heart rate - uses LTHR, shows bpm
        POWER   // Power - uses FTP, shows W
    }

    // Current mode
    var mode: Mode = Mode.HR
        set(value) {
            field = value
            invalidate()
        }

    // Threshold value for converting % to absolute (LTHR in bpm or FTP in watts)
    var thresholdValue: Int = 170
        set(value) {
            field = value
            recalculateZoneBoundaries()
            invalidate()
        }

    // Zone boundaries as % of threshold (starting values for zones 2-5)
    private var zone2StartPercent = 80.0
    private var zone3StartPercent = 88.0
    private var zone4StartPercent = 95.0
    private var zone5StartPercent = 102.0

    // Calculated integer boundaries (BPM or watts) - derived from percentages and threshold
    private var zone2StartAbsolute = 136
    private var zone3StartAbsolute = 150
    private var zone4StartAbsolute = 162
    private var zone5StartAbsolute = 173

    // Selected range as percentages (with 1 decimal precision for integer BPM/watt snapping)
    private var _minPercent = 80.0
    private var _maxPercent = 95.0

    var minPercent: Double
        get() = _minPercent
        set(value) {
            _minPercent = value.coerceIn(MIN_PERCENT.toDouble(), _maxPercent - MIN_GAP)
            invalidate()
        }

    var maxPercent: Double
        get() = _maxPercent
        set(value) {
            _maxPercent = value.coerceIn(_minPercent + MIN_GAP, MAX_PERCENT.toDouble())
            invalidate()
        }

    // Zone colors (same for HR and Power)
    private val zoneColors = intArrayOf(
        ContextCompat.getColor(context, R.color.hr_zone_1),
        ContextCompat.getColor(context, R.color.hr_zone_2),
        ContextCompat.getColor(context, R.color.hr_zone_3),
        ContextCompat.getColor(context, R.color.hr_zone_4),
        ContextCompat.getColor(context, R.color.hr_zone_5)
    )

    // Paints
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.hr_slider_text_size)
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.slider_unselected_overlay)
    }

    // Dimensions
    private val handleWidth = resources.getDimension(R.dimen.hr_slider_handle_width)
    private val handleHeight = resources.getDimension(R.dimen.hr_slider_handle_height)
    private val barHeight = resources.getDimension(R.dimen.hr_slider_bar_height)
    private val handleRadius = resources.getDimension(R.dimen.hr_slider_handle_radius)

    // Layout values
    private var barLeft = 0f
    private var barRight = 0f
    private var barTop = 0f
    private var barBottom = 0f

    // Preallocated rect for handle drawing
    private val handleRect = RectF()

    // Dragging state
    private var draggingHandle = -1  // -1 = none, 0 = min handle, 1 = max handle

    // Callback - returns percentages (with 1 decimal precision)
    var onRangeChanged: ((minPercent: Double, maxPercent: Double) -> Unit)? = null

    init {
        minimumHeight = (handleHeight + 20 * resources.displayMetrics.density).toInt()
    }

    /**
     * Set the zone boundaries as percentages of threshold (zone start values for zones 2-5).
     * Works for both HR and Power modes.
     */
    fun setZonesPercent(z2StartPct: Double, z3StartPct: Double, z4StartPct: Double, z5StartPct: Double) {
        zone2StartPercent = z2StartPct
        zone3StartPercent = z3StartPct
        zone4StartPercent = z4StartPct
        zone5StartPercent = z5StartPct
        recalculateZoneBoundaries()
        invalidate()
    }

    /**
     * Recalculate integer BPM/watts boundaries from stored percentages and threshold.
     */
    private fun recalculateZoneBoundaries() {
        zone2StartAbsolute = kotlin.math.round(zone2StartPercent * thresholdValue / 100.0).toInt()
        zone3StartAbsolute = kotlin.math.round(zone3StartPercent * thresholdValue / 100.0).toInt()
        zone4StartAbsolute = kotlin.math.round(zone4StartPercent * thresholdValue / 100.0).toInt()
        zone5StartAbsolute = kotlin.math.round(zone5StartPercent * thresholdValue / 100.0).toInt()
    }

    /**
     * Set the selected range as percentages (with decimal precision).
     */
    fun setRangePercent(minPct: Double, maxPct: Double) {
        _minPercent = minPct.coerceIn(MIN_PERCENT.toDouble(), MAX_PERCENT.toDouble() - MIN_GAP)
        _maxPercent = maxPct.coerceIn(_minPercent + MIN_GAP, MAX_PERCENT.toDouble())
        invalidate()
    }

    /**
     * Get the current range as percentages.
     */
    fun getRangePercent(): Pair<Double, Double> = Pair(_minPercent, _maxPercent)

    /**
     * Get the current range as absolute values (using threshold).
     */
    fun getRangeAbsolute(): Pair<Int, Int> = Pair(
        percentToAbsolute(_minPercent),
        percentToAbsolute(_maxPercent)
    )

    /**
     * Convert percentage to absolute value using current threshold.
     * Returns rounded integer for display (BPM or watts).
     */
    private fun percentToAbsolute(percent: Double): Int = kotlin.math.round(percent * thresholdValue / 100.0).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (handleHeight + 20 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = handleWidth / 2
        barLeft = padding
        barRight = w - padding
        barTop = (h - barHeight) / 2
        barBottom = barTop + barHeight
    }

    private fun percentToX(percent: Double): Float {
        val ratio = (percent - MIN_PERCENT) / (MAX_PERCENT - MIN_PERCENT)
        return barLeft + ratio.toFloat() * (barRight - barLeft)
    }

    /**
     * Convert x position to percentage, snapping to nearest integer absolute values (BPM/watts).
     * Returns percentage with 1 decimal precision.
     */
    private fun xToPercent(x: Float): Double {
        val ratio = (x - barLeft) / (barRight - barLeft)
        val rawPercent = MIN_PERCENT + ratio * (MAX_PERCENT - MIN_PERCENT)

        // Convert to absolute, round to nearest integer, convert back to percent with 1 decimal
        val rawAbsolute = rawPercent * thresholdValue / 100.0
        val roundedAbsolute = kotlin.math.round(rawAbsolute).toInt()
        val precisePercent = roundedAbsolute * 100.0 / thresholdValue

        // Round to 1 decimal place
        return (precisePercent * 10).toInt() / 10.0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw zone bars using integer BPM/watts boundaries converted back to percentages
        // This ensures zone borders align with actual integer BPM/watts values
        val zonePercents = doubleArrayOf(
            MIN_PERCENT.toDouble(),
            zone2StartAbsolute * 100.0 / thresholdValue,
            zone3StartAbsolute * 100.0 / thresholdValue,
            zone4StartAbsolute * 100.0 / thresholdValue,
            zone5StartAbsolute * 100.0 / thresholdValue,
            MAX_PERCENT.toDouble()
        )
        for (i in 0 until 5) {
            val left = percentToX(zonePercents[i])
            val right = percentToX(zonePercents[i + 1])
            barPaint.color = zoneColors[i]
            canvas.drawRect(left, barTop, right, barBottom, barPaint)
        }

        // Draw dimmed overlay for unselected areas
        val minX = percentToX(_minPercent)
        val maxX = percentToX(_maxPercent)
        canvas.drawRect(barLeft, barTop, minX, barBottom, dimPaint)
        canvas.drawRect(maxX, barTop, barRight, barBottom, dimPaint)

        // Draw handles
        drawHandle(canvas, _minPercent)
        drawHandle(canvas, _maxPercent)
    }

    private fun drawHandle(canvas: Canvas, percent: Double) {
        val x = percentToX(percent)
        val handleLeft = x - handleWidth / 2
        val handleRight = x + handleWidth / 2
        val handleTop = barTop - 5 * resources.displayMetrics.density
        val handleBottom = barBottom + 5 * resources.displayMetrics.density

        // Get handle color from zone
        val zoneColor = getZoneColorForPercent(percent)
        handlePaint.color = zoneColor

        // Draw handle background
        handleRect.set(handleLeft, handleTop, handleRight, handleBottom)
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, handlePaint)

        // Draw handle border
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, handleBorderPaint)

        // Draw value text: "85.3%" on top line, "145" on bottom line
        val absoluteValue = percentToAbsolute(percent)
        val lineHeight = textPaint.textSize * 1.2f
        val centerY = handleTop + (handleBottom - handleTop) / 2

        // Top line: percentage with 1 decimal
        val percentText = if (percent == percent.toInt().toDouble()) "${percent.toInt()}%" else String.format(Locale.US, "%.1f%%", percent)
        canvas.drawText(percentText, x, centerY - lineHeight / 4, textPaint)
        // Bottom line: absolute value (integer BPM/watts)
        canvas.drawText(absoluteValue.toString(), x, centerY + lineHeight * 0.7f, textPaint)
    }

    private fun getZoneColorForPercent(percent: Double): Int {
        // Compare using integer BPM/watts values for consistent zone determination
        val absoluteValue = percentToAbsolute(percent)
        val zone = when {
            absoluteValue < zone2StartAbsolute -> 1
            absoluteValue < zone3StartAbsolute -> 2
            absoluteValue < zone4StartAbsolute -> 3
            absoluteValue < zone5StartAbsolute -> 4
            else -> 5
        }
        return zoneColors[(zone - 1).coerceIn(0, 4)]
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = findNearestHandle(event.x, event.y)
                if (draggingHandle >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle >= 0) {
                    val newPercent = xToPercent(event.x)
                    updateHandleValue(draggingHandle, newPercent)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle >= 0) {
                    draggingHandle = -1
                    onRangeChanged?.invoke(_minPercent, _maxPercent)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestHandle(x: Float, y: Float): Int {
        val handleTop = barTop - 10 * resources.displayMetrics.density
        val handleBottom = barBottom + 10 * resources.displayMetrics.density
        if (y < handleTop || y > handleBottom) return -1

        val minX = percentToX(_minPercent)
        val maxX = percentToX(_maxPercent)

        val distToMin = abs(x - minX)
        val distToMax = abs(x - maxX)

        return when {
            distToMin < handleWidth && distToMin < distToMax -> 0
            distToMax < handleWidth -> 1
            distToMin < handleWidth -> 0
            else -> -1
        }
    }

    private fun updateHandleValue(index: Int, newPercent: Double) {
        when (index) {
            0 -> { // Min handle
                _minPercent = newPercent.coerceIn(MIN_PERCENT.toDouble(), _maxPercent - MIN_GAP)
            }
            1 -> { // Max handle
                _maxPercent = newPercent.coerceIn(_minPercent + MIN_GAP, MAX_PERCENT.toDouble())
            }
        }
    }
}
