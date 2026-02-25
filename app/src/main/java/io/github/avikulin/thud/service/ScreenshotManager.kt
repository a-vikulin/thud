package io.github.avikulin.thud.service

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import androidx.core.content.ContextCompat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import io.github.avikulin.thud.R
import io.github.avikulin.thud.ui.ScreenshotPermissionActivity
import io.github.avikulin.thud.util.FileExportHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Manages automatic screenshot capture during workouts using MediaProjection API.
 * Screenshots are triggered on step start and pause (first stop).
 *
 * DRM-protected content (e.g., Netflix) appears as black due to FLAG_SECURE.
 * When detected (>40% black pixels), smart blending replaces black with wallpaper:
 * - Pure black → full wallpaper
 * - Dark grays (HUD panels) → dimmed wallpaper (preserves panel look)
 * - Colored pixels (text, icons) → fully preserved
 *
 * Uses optimized single-pass bulk pixel processing for speed.
 *
 * Filename format matches FIT files: WorkoutName_StartTime_ScreenshotTime.png
 */
class ScreenshotManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ScreenshotManager"
        private const val MIME_TYPE = "image/png"
        private const val VIRTUAL_DISPLAY_NAME = "ScreenshotCapture"

        // DRM detection and blending thresholds
        private const val BLACK_PIXEL_THRESHOLD = 0.40  // 40% pure black pixels = DRM content detected
        private const val PURE_BLACK_THRESHOLD = 10     // Luminance < 10 = pure black → full wallpaper
        private const val GRAY_BLEND_THRESHOLD = 60     // Luminance < 60 = dark gray → dimmed wallpaper
        private const val COLOR_SATURATION_THRESHOLD = 25  // Saturation > 25 = colored → preserve
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(ZoneId.systemDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    /** Whether auto-screenshot mode is enabled */
    @Volatile
    var isEnabled = false
        private set

    /** Whether we have MediaProjection permission */
    private var hasPermission = false

    /** Current workout name for filename generation */
    private var currentWorkoutName: String = "Workout"

    /** Run start time for filename generation (matches FIT file naming) */
    private var runStartTimeMs: Long = 0L

    /** Pending toggle request while waiting for permission */
    private var pendingToggle = false

    /** Callback for state changes (e.g., after permission granted) */
    var onStateChanged: ((isEnabled: Boolean) -> Unit)? = null

    /** Cached wallpaper bitmap (lazily loaded) */
    private var cachedWallpaper: Bitmap? = null

    init {
        // Get screen dimensions
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    /**
     * Toggle screenshot mode on/off.
     * When toggled ON, requests permission if needed, then takes a screenshot.
     *
     * @return New state (true = enabled, false = disabled or pending permission)
     */
    fun toggle(workoutName: String, startTimeMs: Long): Boolean {
        currentWorkoutName = workoutName
        runStartTimeMs = startTimeMs

        if (isEnabled) {
            // Turning off (user toggle)
            isEnabled = false
            Log.d(TAG, "Screenshot mode toggled OFF by user")
            return false
        }

        // Turning on - check permission
        if (!hasPermission) {
            pendingToggle = true
            requestPermission()
            return false // Will be enabled after permission granted
        }

        // Permission already granted
        enableScreenshots()
        return true
    }

    /**
     * Request MediaProjection permission via transparent activity.
     */
    private fun requestPermission() {
        Log.d(TAG, "Requesting MediaProjection permission")
        showToast(context.getString(R.string.screenshot_permission_required))

        ScreenshotPermissionActivity.requestPermission(context) { resultCode, data ->
            mainHandler.post {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d(TAG, "MediaProjection permission granted")
                    setupMediaProjection(resultCode, data)
                    hasPermission = true

                    if (pendingToggle) {
                        pendingToggle = false
                        enableScreenshots()
                    }
                } else {
                    Log.w(TAG, "MediaProjection permission denied")
                    pendingToggle = false
                    showToast(context.getString(R.string.screenshot_permission_denied))
                }
            }
        }
    }

    /**
     * Set up MediaProjection with the permission result.
     */
    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    cleanupProjection()
                    hasPermission = false
                    isEnabled = false
                }
            }, mainHandler)

            Log.d(TAG, "MediaProjection set up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up MediaProjection", e)
            hasPermission = false
        }
    }

    /**
     * Enable screenshot mode after permission is granted.
     */
    private fun enableScreenshots() {
        isEnabled = true
        showToast(context.getString(R.string.screenshot_enabled))
        Log.d(TAG, "Screenshot mode enabled")

        // Notify listener of state change
        onStateChanged?.invoke(true)

        // Take immediate screenshot when enabled
        takeScreenshot("enabled")
    }

    /**
     * Update workout info (called when workout starts or changes).
     */
    fun setWorkoutInfo(workoutName: String, startTimeMs: Long) {
        currentWorkoutName = workoutName
        runStartTimeMs = startTimeMs
    }

    /**
     * Take a screenshot if enabled.
     * Called on step start and pause.
     *
     * @param trigger Description of what triggered the screenshot (for logging)
     */
    fun takeScreenshotIfEnabled(trigger: String) {
        if (!isEnabled) {
            Log.d(TAG, "Screenshot skipped (disabled): trigger=$trigger")
            return
        }
        Log.d(TAG, "Screenshot taking: trigger=$trigger")
        takeScreenshot(trigger)
    }

    /**
     * Take a screenshot using MediaProjection.
     */
    private fun takeScreenshot(trigger: String) {
        if (mediaProjection == null) {
            Log.e(TAG, "Cannot take screenshot: MediaProjection not available")
            return
        }

        scope.launch {
            try {
                val filename = generateFilename()
                val file = getScreenshotFile(filename)

                // Ensure target directory exists
                file.parentFile?.mkdirs()

                Log.d(TAG, "Taking screenshot to: ${file.absolutePath} (trigger: $trigger)")

                var bitmap = captureScreen()

                if (bitmap != null) {
                    // Smart blend: detect DRM and substitute wallpaper in single optimized pass
                    val blended = smartBlendWithWallpaper(bitmap)
                    if (blended !== bitmap) {
                        bitmap.recycle()
                        bitmap = blended
                    }

                    // Save bitmap to file
                    val saved = withContext(Dispatchers.IO) {
                        saveBitmapToPng(bitmap, file)
                    }
                    bitmap.recycle()

                    if (saved) {
                        // Notify MediaStore so file appears in file explorers
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf(MIME_TYPE),
                            null
                        )
                        val displayPath = FileExportHelper.getDisplayPath(filename, FileExportHelper.Subfolder.SCREENSHOTS)
                        Log.d(TAG, "Screenshot saved: $displayPath")
                        showToast(context.getString(R.string.screenshot_saved, filename))
                    } else {
                        Log.e(TAG, "Failed to save screenshot")
                        showToast(context.getString(R.string.screenshot_failed))
                    }
                } else {
                    Log.e(TAG, "Failed to capture screen")
                    showToast(context.getString(R.string.screenshot_failed))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot error", e)
                showToast(context.getString(R.string.screenshot_failed))
            }
        }
    }

    /**
     * Capture the screen using MediaProjection.
     */
    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Main) {
        try {
            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
            )

            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, mainHandler
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay")
                cleanupCapture()
                return@withContext null
            }

            // Wait a bit for the display to render
            kotlinx.coroutines.delay(100)

            // Acquire the latest image
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Log.e(TAG, "Failed to acquire image")
                cleanupCapture()
                return@withContext null
            }

            // Convert to bitmap
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop to actual screen size if there's padding
            val croppedBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }

            cleanupCapture()
            croppedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            cleanupCapture()
            null
        }
    }

    /**
     * Clean up capture resources (but keep MediaProjection for future captures).
     */
    private fun cleanupCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    /**
     * Clean up MediaProjection (called when projection stops).
     */
    private fun cleanupProjection() {
        cleanupCapture()
        mediaProjection = null
    }

    /**
     * Save bitmap to PNG file.
     */
    private fun saveBitmapToPng(bitmap: Bitmap, file: File): Boolean {
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.exists() && file.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap", e)
            false
        }
    }

    /**
     * Generate filename matching FIT file format with screenshot timestamp.
     * Format: WorkoutName_RunStartTime_ScreenshotTime.png
     */
    private fun generateFilename(): String {
        val sanitizedName = currentWorkoutName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val now = Instant.now()
        val runStartStr = if (runStartTimeMs > 0) {
            dateFormatter.format(Instant.ofEpochMilli(runStartTimeMs))
        } else {
            dateFormatter.format(now)
        }
        val screenshotStr = dateFormatter.format(now)
        return "${sanitizedName}_${runStartStr}_$screenshotStr.png"
    }

    /**
     * Get the screenshot file in the profile-aware Downloads/tHUD/<profile>/screenshots folder.
     */
    private fun getScreenshotFile(filename: String): File {
        val screenshotsDir = FileExportHelper.getAbsoluteDir(FileExportHelper.Subfolder.SCREENSHOTS)
        return File(screenshotsDir, filename)
    }

    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Get the device wallpaper or a fallback gradient, scaled to screen size.
     * Cached for performance.
     *
     * Fallback order:
     * 1. Actual wallpaper bitmap (requires READ_EXTERNAL_STORAGE on some devices)
     * 2. Gradient based on WallpaperColors API (no permission needed, Android 8.1+)
     * 3. Default dark gradient
     */
    private fun getWallpaperBitmap(): Bitmap? {
        cachedWallpaper?.let { return it }

        val wallpaperManager = WallpaperManager.getInstance(context)

        // Try 1: Get actual wallpaper (WallpaperManager requires READ_EXTERNAL_STORAGE specifically)
        val hasReadExternal = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasReadExternal) {
            try {
                val drawable = wallpaperManager.drawable
                if (drawable != null) {
                    val bitmap = if (drawable is BitmapDrawable) {
                        drawable.bitmap
                    } else {
                        val bmp = Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(1),
                            drawable.intrinsicHeight.coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                    val scaled = Bitmap.createScaledBitmap(bitmap, screenWidth, screenHeight, true)
                    cachedWallpaper = scaled
                    Log.d(TAG, "Wallpaper loaded from drawable: ${scaled.width}x${scaled.height}")
                    return scaled
                }
            } catch (e: SecurityException) {
                Log.d(TAG, "Cannot access wallpaper directly (permission denied), trying WallpaperColors")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get wallpaper drawable", e)
            }
        } else {
            Log.d(TAG, "Storage permission not granted, trying WallpaperColors fallback")
        }

        // Try 2: Use WallpaperColors API to create a matching gradient (Android 8.1+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val colors: WallpaperColors? = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                if (colors != null) {
                    val gradient = createGradientFromColors(colors)
                    cachedWallpaper = gradient
                    Log.d(TAG, "Created gradient from WallpaperColors")
                    return gradient
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get WallpaperColors", e)
            }
        }

        // Try 3: Default dark gradient
        val defaultGradient = createDefaultGradient()
        cachedWallpaper = defaultGradient
        Log.d(TAG, "Using default dark gradient")
        return defaultGradient
    }

    /**
     * Create a gradient bitmap from WallpaperColors.
     */
    private fun createGradientFromColors(colors: WallpaperColors): Bitmap {
        val primary = colors.primaryColor?.toArgb() ?: Color.DKGRAY
        val secondary = colors.secondaryColor?.toArgb() ?: darkenColor(primary)
        val tertiary = colors.tertiaryColor?.toArgb() ?: darkenColor(secondary)

        return createGradientBitmap(primary, secondary, tertiary)
    }

    /**
     * Create a default dark gradient for fallback.
     */
    private fun createDefaultGradient(): Bitmap {
        val topColor = Color.rgb(30, 30, 40)      // Dark blue-gray
        val middleColor = Color.rgb(20, 20, 30)   // Darker
        val bottomColor = Color.rgb(10, 10, 20)   // Near black
        return createGradientBitmap(topColor, middleColor, bottomColor)
    }

    /**
     * Create a vertical gradient bitmap with three colors.
     */
    private fun createGradientBitmap(topColor: Int, middleColor: Int, bottomColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        val gradient = LinearGradient(
            0f, 0f,
            0f, screenHeight.toFloat(),
            intArrayOf(topColor, middleColor, bottomColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), paint)

        return bitmap
    }

    /**
     * Darken a color by reducing RGB values.
     */
    private fun darkenColor(color: Int): Int {
        val factor = 0.7f
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r, g, b)
    }

    /**
     * Single-pass optimized DRM detection and wallpaper blending.
     *
     * Uses bulk pixel operations (getPixels/setPixels) which are ~100x faster
     * than individual getPixel/setPixel calls.
     *
     * Blending logic:
     * - Pure black (luminance < 10) → Full wallpaper replacement
     * - Dark grays (luminance 10-60) → Dimmed wallpaper (gray acts as darkening filter)
     * - Colored pixels (saturation > 25) → Fully preserved (text, icons, etc.)
     * - Light grays → Preserved as-is
     *
     * @return Blended bitmap if DRM detected, or original bitmap if no DRM content
     */
    private fun smartBlendWithWallpaper(screenshot: Bitmap): Bitmap {
        val wallpaper = getWallpaperBitmap()
        if (wallpaper == null) {
            Log.w(TAG, "No wallpaper available, skipping blend")
            return screenshot
        }

        val width = screenshot.width
        val height = screenshot.height
        val pixelCount = width * height

        // Bulk read all pixels (MUCH faster than individual getPixel calls)
        val pixels = IntArray(pixelCount)
        screenshot.getPixels(pixels, 0, width, 0, 0, width, height)

        // Scale wallpaper to match if needed, then bulk read
        val scaledWallpaper = if (wallpaper.width != width || wallpaper.height != height) {
            Bitmap.createScaledBitmap(wallpaper, width, height, true)
        } else {
            wallpaper
        }
        val wpPixels = IntArray(pixelCount)
        scaledWallpaper.getPixels(wpPixels, 0, width, 0, 0, width, height)
        if (scaledWallpaper !== wallpaper) {
            scaledWallpaper.recycle()
        }

        // Single pass: detect + blend
        var blackCount = 0
        var blendedCount = 0
        val startTime = System.currentTimeMillis()

        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            val luminance = (r + g + b) / 3
            val saturation = maxOf(r, g, b) - minOf(r, g, b)

            // Colored pixels (text, icons) → preserve
            if (saturation > COLOR_SATURATION_THRESHOLD) continue

            // Light pixels → preserve
            if (luminance >= GRAY_BLEND_THRESHOLD) continue

            // Track black pixels for DRM detection
            if (luminance < PURE_BLACK_THRESHOLD) blackCount++

            // Blend with wallpaper
            val wpPixel = wpPixels[i]
            val wr = Color.red(wpPixel)
            val wg = Color.green(wpPixel)
            val wb = Color.blue(wpPixel)

            if (luminance < PURE_BLACK_THRESHOLD) {
                // Pure black → full wallpaper replacement
                pixels[i] = wpPixel
            } else {
                // Dark gray → blend wallpaper with original pixel
                // This simulates semi-transparent panel over wallpaper:
                // - Higher luminance (lighter gray) = more original, less wallpaper
                // - Lower luminance (darker gray) = less original, more wallpaper
                val originalWeight = luminance.toFloat() / GRAY_BLEND_THRESHOLD  // 0.17 to 1.0
                val wallpaperWeight = 1.0f - originalWeight  // 0.83 to 0.0
                pixels[i] = Color.rgb(
                    (wr * wallpaperWeight + r * originalWeight).toInt().coerceIn(0, 255),
                    (wg * wallpaperWeight + g * originalWeight).toInt().coerceIn(0, 255),
                    (wb * wallpaperWeight + b * originalWeight).toInt().coerceIn(0, 255)
                )
            }
            blendedCount++
        }

        val elapsed = System.currentTimeMillis() - startTime
        val blackRatio = blackCount.toDouble() / pixelCount

        // If not enough black pixels, this isn't DRM content - return original
        if (blackRatio < BLACK_PIXEL_THRESHOLD) {
            Log.d(TAG, "No DRM detected (${(blackRatio * 100).toInt()}% black < ${(BLACK_PIXEL_THRESHOLD * 100).toInt()}% threshold), " +
                "keeping original [${elapsed}ms]")
            return screenshot
        }

        // Create result bitmap with blended pixels
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)

        Log.d(TAG, "DRM blend complete: ${(blackRatio * 100).toInt()}% black, $blendedCount pixels blended [${elapsed}ms]")
        return result
    }

    /**
     * Clean up all resources.
     */
    fun cleanup() {
        isEnabled = false
        cleanupCapture()
        mediaProjection?.stop()
        mediaProjection = null
        hasPermission = false
        cachedWallpaper?.recycle()
        cachedWallpaper = null
    }
}
