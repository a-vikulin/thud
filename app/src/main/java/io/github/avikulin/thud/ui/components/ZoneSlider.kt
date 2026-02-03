package io.github.avikulin.thud.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R
import java.util.Locale
import kotlin.math.abs

/**
 * Custom slider for configuring 5 zones (HR or Power).
 * Works with percentages of threshold (LTHR or FTP).
 * Shows a horizontal bar divided into 5 colored zones with 4 draggable handles.
 */
@SuppressLint("ClickableViewAccessibility")
class ZoneSlider(context: Context) : View(context) {

    companion object {
        private const val MIN_PERCENT = 50
        private const val MAX_PERCENT = 120
        private const val MIN_GAP = 3  // Minimum gap between zones in %
        private const val HANDLE_WIDTH_DP = 48f
        private const val HANDLE_HEIGHT_DP = 54f
        private const val BAR_HEIGHT_DP = 30f
        private const val HANDLE_RADIUS_DP = 4f
        private const val TEXT_SIZE_SP = 11f
    }

    /**
     * Mode determines the unit suffix for absolute value display.
     */
    enum class Mode {
        HR,     // Shows "bpm" suffix
        POWER   // Shows "W" suffix
    }

    var mode: Mode = Mode.HR
        set(value) {
            field = value
            invalidate()
        }

    // Threshold value for converting % to absolute (LTHR in bpm or FTP in watts)
    var thresholdValue: Int = 170
        set(value) {
            field = value
            invalidate()
        }

    // Zone boundaries as % of threshold (starting values for zones 2-5)
    // Stored with 1 decimal precision for integer BPM/watt snapping
    private var zone2StartPercent = 80.0
    private var zone3StartPercent = 88.0
    private var zone4StartPercent = 95.0
    private var zone5StartPercent = 102.0

    // Colors for each zone (same colors for HR and Power)
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, resources.displayMetrics)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Dimensions in pixels
    private val handleWidth = HANDLE_WIDTH_DP * resources.displayMetrics.density
    private val handleHeight = HANDLE_HEIGHT_DP * resources.displayMetrics.density
    private val barHeight = BAR_HEIGHT_DP * resources.displayMetrics.density
    private val handleRadius = HANDLE_RADIUS_DP * resources.displayMetrics.density

    // Layout values
    private var barLeft = 0f
    private var barRight = 0f
    private var barTop = 0f
    private var barBottom = 0f

    // Preallocated rect for handle drawing
    private val handleRect = RectF()

    // Dragging state
    private var draggingHandle = -1  // -1 = none, 0-3 = handle index

    // Listener for value changes (percentages with 1 decimal precision)
    var onZoneChangedListener: ((Double, Double, Double, Double) -> Unit)? = null

    init {
        // Set minimum height
        minimumHeight = (handleHeight + 20 * resources.displayMetrics.density).toInt()
    }

    /**
     * Set zone boundaries as percentages of threshold (zone start values for zones 2-5).
     */
    fun setZonesPercent(z2Start: Double, z3Start: Double, z4Start: Double, z5Start: Double) {
        zone2StartPercent = z2Start.coerceIn(MIN_PERCENT + MIN_GAP.toDouble(), MAX_PERCENT - 4.0 * MIN_GAP)
        zone3StartPercent = z3Start.coerceIn(zone2StartPercent + MIN_GAP, MAX_PERCENT - 3.0 * MIN_GAP)
        zone4StartPercent = z4Start.coerceIn(zone3StartPercent + MIN_GAP, MAX_PERCENT - 2.0 * MIN_GAP)
        zone5StartPercent = z5Start.coerceIn(zone4StartPercent + MIN_GAP, MAX_PERCENT - MIN_GAP.toDouble())
        invalidate()
    }

    /**
     * Get zone boundaries as percentages (with 1 decimal precision).
     */
    fun getZonesPercent(): DoubleArray = doubleArrayOf(zone2StartPercent, zone3StartPercent, zone4StartPercent, zone5StartPercent)

    /**
     * Get zone boundaries as absolute values (using current threshold).
     */
    fun getZonesAbsolute(): IntArray = intArrayOf(
        percentToAbsolute(zone2StartPercent),
        percentToAbsolute(zone3StartPercent),
        percentToAbsolute(zone4StartPercent),
        percentToAbsolute(zone5StartPercent)
    )

    private fun percentToAbsolute(percent: Double): Int = kotlin.math.round(percent * thresholdValue / 100.0).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (handleHeight + 20 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Calculate bar bounds (leave room for handles on edges)
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

        val zoneValues = doubleArrayOf(MIN_PERCENT.toDouble(), zone2StartPercent, zone3StartPercent, zone4StartPercent, zone5StartPercent, MAX_PERCENT.toDouble())

        // Draw zone bars
        for (i in 0 until 5) {
            val left = percentToX(zoneValues[i])
            val right = percentToX(zoneValues[i + 1])
            barPaint.color = zoneColors[i]
            canvas.drawRect(left, barTop, right, barBottom, barPaint)
        }

        // Draw bar border
        canvas.drawRect(barLeft, barTop, barRight, barBottom, borderPaint)

        // Draw handles (each handle is colored by the zone it starts)
        val handleValues = doubleArrayOf(zone2StartPercent, zone3StartPercent, zone4StartPercent, zone5StartPercent)
        for (i in 0 until 4) {
            drawHandle(canvas, handleValues[i], zoneColors[i + 1])
        }
    }

    private fun drawHandle(canvas: Canvas, percent: Double, color: Int) {
        val x = percentToX(percent)
        val handleLeft = x - handleWidth / 2
        val handleRight = x + handleWidth / 2
        val handleTop = barTop - 5 * resources.displayMetrics.density
        val handleBottom = barBottom + 5 * resources.displayMetrics.density

        // Draw handle background
        handlePaint.color = color
        handleRect.set(handleLeft, handleTop, handleRight, handleBottom)
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, handlePaint)

        // Draw handle border
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, borderPaint)

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = findNearestHandle(event.x, event.y)
                if (draggingHandle >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
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
                    onZoneChangedListener?.invoke(zone2StartPercent, zone3StartPercent, zone4StartPercent, zone5StartPercent)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestHandle(x: Float, y: Float): Int {
        // Check if touch is within vertical range of handles
        val handleTop = barTop - 10 * resources.displayMetrics.density
        val handleBottom = barBottom + 10 * resources.displayMetrics.density
        if (y < handleTop || y > handleBottom) return -1

        val handleValues = doubleArrayOf(zone2StartPercent, zone3StartPercent, zone4StartPercent, zone5StartPercent)
        var nearestIndex = -1
        var nearestDist = Float.MAX_VALUE

        for (i in 0 until 4) {
            val handleX = percentToX(handleValues[i])
            val dist = abs(x - handleX)
            if (dist < handleWidth && dist < nearestDist) {
                nearestDist = dist
                nearestIndex = i
            }
        }

        return nearestIndex
    }

    private fun updateHandleValue(index: Int, newPercent: Double) {
        when (index) {
            0 -> {
                val minVal = MIN_PERCENT + MIN_GAP.toDouble()
                val maxVal = zone3StartPercent - MIN_GAP
                zone2StartPercent = newPercent.coerceIn(minVal, maxVal)
            }
            1 -> {
                val minVal = zone2StartPercent + MIN_GAP
                val maxVal = zone4StartPercent - MIN_GAP
                zone3StartPercent = newPercent.coerceIn(minVal, maxVal)
            }
            2 -> {
                val minVal = zone3StartPercent + MIN_GAP
                val maxVal = zone5StartPercent - MIN_GAP
                zone4StartPercent = newPercent.coerceIn(minVal, maxVal)
            }
            3 -> {
                val minVal = zone4StartPercent + MIN_GAP
                val maxVal = MAX_PERCENT - MIN_GAP.toDouble()
                zone5StartPercent = newPercent.coerceIn(minVal, maxVal)
            }
        }
    }
}
