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
 * Single-handle slider for coefficient values.
 * Shows a gradient bar with a draggable handle displaying the current value.
 * Configurable min/max range, defaults to pace coefficient range (0.70 to 1.30).
 */
@SuppressLint("ClickableViewAccessibility")
class OneKnobSlider(context: Context) : View(context) {

    companion object {
        private const val HANDLE_WIDTH_DP = 50f
        private const val HANDLE_HEIGHT_DP = 50f
        private const val BAR_HEIGHT_DP = 30f
        private const val HANDLE_RADIUS_DP = 4f
        private const val TEXT_SIZE_SP = 14f
    }

    // Configurable range
    var minValue = 0.70
        set(value) { field = value; invalidate() }
    var maxValue = 1.30
        set(value) { field = value; invalidate() }

    // Current value
    private var value = 1.0

    // Colors
    private val barColorSlow = ContextCompat.getColor(context, R.color.hr_zone_2)  // Blue for slow
    private val barColorNormal = ContextCompat.getColor(context, R.color.hr_zone_3)  // Green for normal
    private val barColorFast = ContextCompat.getColor(context, R.color.hr_zone_4)  // Orange for fast
    private val handleColor = ContextCompat.getColor(context, R.color.popup_button_active)

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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_label)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
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
    private var isDragging = false

    // Listener for value changes
    var onValueChangedListener: ((Double) -> Unit)? = null

    init {
        minimumHeight = (handleHeight + 30 * resources.displayMetrics.density).toInt()
    }

    fun setValue(newValue: Double) {
        value = newValue.coerceIn(minValue, maxValue)
        invalidate()
    }

    fun getValue(): Double = value

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (handleHeight + 30 * resources.displayMetrics.density).toInt()
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

    private fun valueToX(v: Double): Float {
        val ratio = (v - minValue) / (maxValue - minValue)
        return barLeft + ratio.toFloat() * (barRight - barLeft)
    }

    private fun xToValue(x: Float): Double {
        val ratio = (x - barLeft) / (barRight - barLeft)
        return (minValue + ratio * (maxValue - minValue)).coerceIn(minValue, maxValue)
    }

    private fun getColorForValue(v: Double): Int {
        val range = maxValue - minValue
        val lowThreshold = minValue + range * 0.33
        val highThreshold = minValue + range * 0.66
        return when {
            v < lowThreshold -> barColorSlow
            v > highThreshold -> barColorFast
            else -> barColorNormal
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw three colored sections of the bar
        val range = maxValue - minValue
        val lowEnd = valueToX(minValue + range * 0.33)
        val highEnd = valueToX(minValue + range * 0.66)

        // Low section
        barPaint.color = barColorSlow
        canvas.drawRect(barLeft, barTop, lowEnd, barBottom, barPaint)

        // Mid section
        barPaint.color = barColorNormal
        canvas.drawRect(lowEnd, barTop, highEnd, barBottom, barPaint)

        // High section
        barPaint.color = barColorFast
        canvas.drawRect(highEnd, barTop, barRight, barBottom, barPaint)

        // Draw bar border
        canvas.drawRect(barLeft, barTop, barRight, barBottom, borderPaint)

        // Draw labels
        val labelY = barBottom + 15 * resources.displayMetrics.density
        val midValue = (minValue + maxValue) / 2
        canvas.drawText(String.format(Locale.US, "%.2f", minValue), barLeft, labelY, labelPaint)
        canvas.drawText(String.format(Locale.US, "%.2f", midValue), valueToX(midValue), labelY, labelPaint)
        canvas.drawText(String.format(Locale.US, "%.2f", maxValue), barRight, labelY, labelPaint)

        // Draw handle
        val x = valueToX(value)
        val handleLeft = x - handleWidth / 2
        val handleRight = x + handleWidth / 2
        val handleTop = barTop - 5 * resources.displayMetrics.density
        val handleBottom = barBottom + 5 * resources.displayMetrics.density

        handlePaint.color = getColorForValue(value)
        handleRect.set(handleLeft, handleTop, handleRight, handleBottom)
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, handlePaint)
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, borderPaint)

        // Draw value text on handle (3 significant digits)
        val textY = handleTop + (handleBottom - handleTop) / 2 + textPaint.textSize / 3
        canvas.drawText(String.format(Locale.US, "%.3f", value), x, textY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = valueToX(value)
                if (abs(event.x - x) < handleWidth && event.y >= barTop - 20 * resources.displayMetrics.density && event.y <= barBottom + 20 * resources.displayMetrics.density) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    value = xToValue(event.x)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onValueChangedListener?.invoke(value)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
