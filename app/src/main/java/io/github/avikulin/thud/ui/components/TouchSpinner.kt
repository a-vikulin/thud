package io.github.avikulin.thud.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R
import io.github.avikulin.thud.util.PaceConverter
import kotlin.math.abs
import kotlin.math.pow

/**
 * TouchSpinner - A touch-friendly numeric input control.
 *
 * USAGE:
 *   Drag UP to increase value, drag DOWN to decrease.
 *   The further you drag, the faster and larger the changes.
 *
 * CONFIGURABLE PER INSTANCE:
 *   - deadZonePx: pixels before changes start
 *   - maxDragPx: pixels for maximum speed/magnitude
 *   - slowestIntervalMs / fastestIntervalMs: tick rate range
 *   - maxStepMultiplier: magnitude multiplier at max drag
 *   - dragExponent: curve shape (1=linear, 2=quadratic, 3=cubic)
 */
@SuppressLint("ClickableViewAccessibility")
class TouchSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ============================================================
    // SENSITIVITY SETTINGS (loaded from resources, can override per instance)
    // ============================================================

    /** Pixels to drag before any value change occurs */
    var deadZonePx: Float

    /** Pixels of drag for maximum speed/magnitude */
    var maxDragPx: Float

    /** Tick interval at minimum drag (slowest) */
    var slowestIntervalMs: Long

    /** Tick interval at maximum drag (fastest) */
    var fastestIntervalMs: Long

    /** Step multiplier at max drag (1.0 = no scaling, 15.0 = 15x step at max) */
    var maxStepMultiplier: Double

    /** Curve exponent: 1.0=linear, 2.0=quadratic, 3.0=cubic */
    var dragExponent: Double

    // ============================================================
    // VALUE CONFIGURATION
    // ============================================================

    var minValue: Double = 0.0
    var maxValue: Double = 100.0
    var step: Double = 1.0
    var format: Format = Format.INTEGER
    var suffix: String = ""

    private var _value: Double = 0.0
    var value: Double
        get() = _value
        set(newValue) {
            val clamped = newValue.coerceIn(minValue, maxValue)
            if (clamped != _value) {
                _value = clamped
                invalidate()
                onValueChanged?.invoke(_value)
            }
        }

    var onValueChanged: ((Double) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    enum class Format {
        INTEGER,     // "42"
        DECIMAL,     // "42.5"
        TIME_MMSS,   // "5:30" (value = total seconds)
        PACE_MMSS,   // "5:30" (value = seconds per km)
        DISTANCE     // "1.5 km" or "500 m" (value = meters)
    }

    // ============================================================
    // INTERNAL STATE
    // ============================================================

    private var touchStartY = 0f
    private var touchCurrentY = 0f
    private var isDragging = false
    private var hasExitedDeadZone = false  // Once true, keep responding even in dead zone

    private val handler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isDragging) return

            try {
                val dragDistance = touchStartY - touchCurrentY  // positive = dragged up
                val dragPx = abs(dragDistance)

                // First time: require exiting dead zone to start
                // After that: keep responding even when passing through center
                if (!hasExitedDeadZone && dragPx < deadZonePx) {
                    handler.postDelayed(this, 50L)
                    return
                }

                // Mark that we've started adjusting
                hasExitedDeadZone = true

                // Direction: +1 for up (increase), -1 for down (decrease)
                val direction = if (dragDistance > 0) 1 else -1

                // Use a small threshold to avoid jitter at exact center
                val jitterThreshold = 5f
                if (dragPx < jitterThreshold) {
                    // Too close to center - wait but don't reset
                    handler.postDelayed(this, 50L)
                    return
                }

                // Normalized drag position (0 to 1)
                // Use full drag distance for magnitude (no dead zone subtraction after started)
                val normalizedDrag = (dragPx / maxDragPx).coerceIn(0f, 1f)

                // Apply curve
                val curveValue = normalizedDrag.toDouble().pow(dragExponent)

                // Calculate step multiplier (1x at min, maxStepMultiplier at max)
                val stepMultiplier = 1.0 + (maxStepMultiplier - 1.0) * curveValue

                // Calculate interval (slowest at min, fastest at max)
                val intervalRange = slowestIntervalMs - fastestIntervalMs
                val interval = slowestIntervalMs - (intervalRange * curveValue).toLong()

                // Apply change
                adjustValue(step * stepMultiplier * direction)

                handler.postDelayed(this, interval.coerceAtLeast(fastestIntervalMs))
            } catch (e: Exception) {
                // If anything goes wrong, keep the loop alive
                if (isDragging) {
                    handler.postDelayed(this, 100L)
                }
            }
        }
    }

    // ============================================================
    // DRAWING
    // ============================================================

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_step_border)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.spinner_value_text)
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.spinner_text_size)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_label_dim)
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.spinner_text_size) * 0.5f
    }

    private val bgRect = RectF()
    private val cornerRadius = resources.getDimension(R.dimen.spinner_corner_radius)
    private val normalBgColor = ContextCompat.getColor(context, R.color.spinner_value_background)
    private val pressedBgColor = ContextCompat.getColor(context, R.color.spinner_button_pressed)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minWidth = resources.getDimensionPixelSize(R.dimen.spinner_value_min_width)
        val height = resources.getDimensionPixelSize(R.dimen.spinner_height)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(minWidth, widthSize)
            else -> minWidth
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        bgPaint.color = if (isDragging) pressedBgColor else normalBgColor
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint)

        // Up arrow (if not at max)
        if (_value < maxValue) {
            val arrowY = height * 0.12f + arrowPaint.textSize * 0.4f
            canvas.drawText("▲", width / 2f, arrowY, arrowPaint)
        }

        // Down arrow (if not at min)
        if (_value > minValue) {
            val arrowY = height - height * 0.12f
            canvas.drawText("▼", width / 2f, arrowY, arrowPaint)
        }

        // Value text (centered)
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(formatValue(_value), width / 2f, textY, textPaint)
    }

    // ============================================================
    // TOUCH HANDLING
    // ============================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.rawY
                touchCurrentY = event.rawY
                isDragging = true
                hasExitedDeadZone = false  // Reset on new touch
                disallowParentIntercept(true)
                handler.post(updateRunnable)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchCurrentY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                handler.removeCallbacks(updateRunnable)
                disallowParentIntercept(false)
                invalidate()
                onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun disallowParentIntercept(disallow: Boolean) {
        var p: ViewParent? = parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow)
            p = p.parent
        }
    }

    // ============================================================
    // VALUE ADJUSTMENT
    // ============================================================

    private fun adjustValue(delta: Double) {
        // Round delta to nearest step multiple for clean values
        // This ensures incline is always 0.5% increments, time is whole seconds, etc.
        val rawSteps = delta / step
        val stepsToMove = if (rawSteps >= 0) {
            maxOf(1L, rawSteps.toLong())
        } else {
            minOf(-1L, rawSteps.toLong())
        }
        val roundedDelta = stepsToMove * step
        val newValue = (_value + roundedDelta).coerceIn(minValue, maxValue)
        if (newValue != _value) {
            _value = newValue
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
            onValueChanged?.invoke(_value)
        }
    }

    private fun formatValue(v: Double): String {
        return when (format) {
            Format.INTEGER -> "${v.toInt()}$suffix"
            Format.DECIMAL -> String.format("%.1f%s", v, suffix)
            Format.TIME_MMSS -> {
                PaceConverter.formatPace(v.toInt()) + suffix
            }
            Format.PACE_MMSS -> {
                if (v <= 0) "--:--$suffix"
                else PaceConverter.formatPace(v.toInt()) + suffix
            }
            Format.DISTANCE -> {
                val meters = v.toInt()
                if (meters >= 1000) String.format("%.1f km%s", meters / 1000.0, suffix)
                else String.format("%d m%s", meters, suffix)
            }
        }
    }

    override fun onDetachedFromWindow() {
        isDragging = false
        handler.removeCallbacks(updateRunnable)
        super.onDetachedFromWindow()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1.0f else 0.5f
    }

    init {
        isFocusable = true
        isClickable = true

        // Load behavior defaults from resources
        deadZonePx = resources.getInteger(R.integer.spinner_dead_zone_px).toFloat()
        maxDragPx = resources.getInteger(R.integer.spinner_max_drag_px).toFloat()
        slowestIntervalMs = resources.getInteger(R.integer.spinner_slowest_interval_ms).toLong()
        fastestIntervalMs = resources.getInteger(R.integer.spinner_fastest_interval_ms).toLong()
        maxStepMultiplier = resources.getFloat(R.dimen.spinner_max_step_multiplier).toDouble()
        dragExponent = resources.getFloat(R.dimen.spinner_drag_exponent).toDouble()
    }
}
