package io.github.avikulin.thud.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R
import io.github.avikulin.thud.data.entity.SpeedCalibrationPoint
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Scatter plot of treadmill speed (X) vs Stryd speed (Y) with regression and identity lines.
 * Semi-transparent dots create a natural density heatmap via alpha accumulation.
 * All Paint objects pre-allocated in init — no allocation in onDraw.
 */
@SuppressLint("ViewConstructor")
class SpeedCalibrationChart(context: Context) : View(context) {

    companion object {
        private const val DOT_RADIUS_DP = 3f
        private const val DOT_ALPHA = 25   // semi-transparent for density effect
        private const val AXIS_TEXT_SP = 10f
        private const val LABEL_TEXT_SP = 9f
        private const val PADDING_DP = 32f
        private const val AXIS_LABEL_OFFSET_DP = 14f
    }

    // Data
    private var points: List<SpeedCalibrationPoint> = emptyList()
    private var regressionA = 1.0
    private var regressionB = 0.0
    private var regressionR2 = 0.0
    private var pointCount = 0

    // Treadmill speed range (for axis scaling when no data)
    private var treadmillMinKph = 1.6
    private var treadmillMaxKph = 20.0

    // Axis range (auto-scaled to data or treadmill range)
    private var xMin = 0.0
    private var xMax = 20.0
    private var yMin = 0.0
    private var yMax = 20.0

    // Layout
    private val paddingPx = PADDING_DP * resources.displayMetrics.density
    private val axisLabelOffset = AXIS_LABEL_OFFSET_DP * resources.displayMetrics.density
    private val dotRadius = DOT_RADIUS_DP * resources.displayMetrics.density

    // Paints (all pre-allocated)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        alpha = DOT_ALPHA
        style = Paint.Style.FILL
    }

    private val regressionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_speed)
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val identityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_label)
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(
            floatArrayOf(8f * resources.displayMetrics.density, 4f * resources.displayMetrics.density),
            0f
        )
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_grid)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_axis_label)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AXIS_TEXT_SP, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_label)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SP, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_background)
        style = Paint.Style.FILL
    }

    // Pre-allocated path for lines
    private val linePath = Path()

    /**
     * Set display data. Call this when data changes.
     * @param treadmillMinKph treadmill minimum speed (for axis scaling when no data)
     * @param treadmillMaxKph treadmill maximum speed (for axis scaling when no data)
     */
    fun setData(
        pairs: List<SpeedCalibrationPoint>,
        a: Double,
        b: Double,
        r2: Double,
        n: Int,
        treadmillMinKph: Double = 1.6,
        treadmillMaxKph: Double = 20.0
    ) {
        points = pairs
        regressionA = a
        regressionB = b
        regressionR2 = r2
        pointCount = n
        this.treadmillMinKph = treadmillMinKph
        this.treadmillMaxKph = treadmillMaxKph

        computeAxisRange(pairs, a, b)
        invalidate()
    }

    /**
     * Compute axis ranges from data points, or from treadmill speed range when no data.
     * X-axis is ALWAYS the treadmill speed range — never changes with regression params.
     * Y-axis adjusts to fit the identity line (y=x), regression line (y=ax+b), and data dots.
     */
    private fun computeAxisRange(pairs: List<SpeedCalibrationPoint>, a: Double, b: Double) {
        // X-axis: treadmill speed range only (with data, use data range; without, use device range)
        val dataXMin = if (pairs.isNotEmpty()) pairs.minOf { it.treadmillKph } else treadmillMinKph
        val dataXMax = if (pairs.isNotEmpty()) pairs.maxOf { it.treadmillKph } else treadmillMaxKph
        val xMargin = (dataXMax - dataXMin) * 0.05 + 0.5
        xMin = floor((dataXMin - xMargin) * 2) / 2  // snap to 0.5
        xMax = ceil((dataXMax + xMargin) * 2) / 2

        // Y-axis: must encompass identity line (y=x), regression line, and data dots
        val regYAtXMin = a * dataXMin + b
        val regYAtXMax = a * dataXMax + b
        // Identity line spans y = dataXMin..dataXMax within the X range
        var allYMin = min(min(dataXMin, regYAtXMin), regYAtXMax)
        var allYMax = max(max(dataXMax, regYAtXMax), regYAtXMin)

        if (pairs.isNotEmpty()) {
            allYMin = min(allYMin, pairs.minOf { it.strydKph })
            allYMax = max(allYMax, pairs.maxOf { it.strydKph })
        }

        val yMargin = (allYMax - allYMin) * 0.05 + 0.5
        yMin = floor((allYMin - yMargin) * 2) / 2
        yMax = ceil((allYMax + yMargin) * 2) / 2

        // Ensure minimum range
        if (xMax - xMin < 2.0) { xMin -= 1.0; xMax += 1.0 }
        if (yMax - yMin < 2.0) { yMin -= 1.0; yMax += 1.0 }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = resources.getDimensionPixelSize(R.dimen.calibration_chart_height)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val plotLeft = paddingPx + axisLabelOffset
        val plotRight = width - paddingPx / 2
        val plotTop = paddingPx / 2
        val plotBottom = height - paddingPx - axisLabelOffset

        // Grid lines at integer kph values
        val xStep = niceStep(xMax - xMin)
        var gx = ceil(xMin / xStep) * xStep
        while (gx <= xMax) {
            val px = mapX(gx, plotLeft, plotRight)
            canvas.drawLine(px, plotTop, px, plotBottom, gridPaint)
            canvas.drawText(
                String.format(Locale.US, "%.0f", gx),
                px, plotBottom + axisLabelOffset * 0.8f, axisTextPaint
            )
            gx += xStep
        }
        var gy = ceil(yMin / xStep) * xStep
        while (gy <= yMax) {
            val py = mapY(gy, plotTop, plotBottom)
            canvas.drawLine(plotLeft, py, plotRight, py, gridPaint)
            axisTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                String.format(Locale.US, "%.0f", gy),
                plotLeft - 4f, py + axisTextPaint.textSize / 3, axisTextPaint
            )
            axisTextPaint.textAlign = Paint.Align.CENTER
            gy += xStep
        }

        // Identity line y=x (dashed)
        val idxStart = max(xMin, yMin)
        val idxEnd = min(xMax, yMax)
        if (idxEnd > idxStart) {
            linePath.reset()
            linePath.moveTo(mapX(idxStart, plotLeft, plotRight), mapY(idxStart, plotTop, plotBottom))
            linePath.lineTo(mapX(idxEnd, plotLeft, plotRight), mapY(idxEnd, plotTop, plotBottom))
            canvas.drawPath(linePath, identityPaint)
        }

        // Regression line (solid)
        val regXStart = xMin
        val regXEnd = xMax
        val regYStart = regressionA * regXStart + regressionB
        val regYEnd = regressionA * regXEnd + regressionB
        linePath.reset()
        linePath.moveTo(
            mapX(regXStart, plotLeft, plotRight),
            mapY(regYStart.coerceIn(yMin, yMax), plotTop, plotBottom)
        )
        linePath.lineTo(
            mapX(regXEnd, plotLeft, plotRight),
            mapY(regYEnd.coerceIn(yMin, yMax), plotTop, plotBottom)
        )
        canvas.drawPath(linePath, regressionPaint)

        // Data points (semi-transparent dots)
        for (p in points) {
            val px = mapX(p.treadmillKph, plotLeft, plotRight)
            val py = mapY(p.strydKph, plotTop, plotBottom)
            // Clip to plot area
            if (px in plotLeft..plotRight && py in plotTop..plotBottom) {
                canvas.drawCircle(px, py, dotRadius, dotPaint)
            }
        }

        // Axis labels
        canvas.drawText("Treadmill (kph)", (plotLeft + plotRight) / 2, height.toFloat() - 2f, labelTextPaint)
        canvas.save()
        canvas.rotate(-90f, labelTextPaint.textSize, (plotTop + plotBottom) / 2)
        canvas.drawText("Stryd (kph)", labelTextPaint.textSize, (plotTop + plotBottom) / 2, labelTextPaint)
        canvas.restore()
    }

    private fun mapX(value: Double, left: Float, right: Float): Float {
        val ratio = (value - xMin) / (xMax - xMin)
        return left + ratio.toFloat() * (right - left)
    }

    private fun mapY(value: Double, top: Float, bottom: Float): Float {
        val ratio = (value - yMin) / (yMax - yMin)
        return bottom - ratio.toFloat() * (bottom - top)  // Y inverted
    }

    /** Choose a nice step size for grid lines based on data range. */
    private fun niceStep(range: Double): Double {
        return when {
            range <= 4 -> 0.5
            range <= 8 -> 1.0
            range <= 16 -> 2.0
            else -> 5.0
        }
    }
}
