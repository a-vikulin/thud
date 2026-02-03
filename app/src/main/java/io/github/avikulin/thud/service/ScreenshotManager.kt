package io.github.avikulin.thud.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages automatic screenshot capture during workouts using MediaProjection API.
 * Screenshots are triggered on step completion, pause, and workout end.
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
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
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
            // Turning off
            isEnabled = false
            Log.d(TAG, "Screenshot mode disabled")
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
     * Disable screenshot mode (called when run ends).
     */
    fun disable() {
        isEnabled = false
    }

    /**
     * Take a screenshot if enabled.
     * Called on step completion, pause, and workout end.
     *
     * @param trigger Description of what triggered the screenshot (for logging)
     */
    fun takeScreenshotIfEnabled(trigger: String) {
        if (!isEnabled) return
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

                val bitmap = captureScreen()

                if (bitmap != null) {
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
        val runStartStr = if (runStartTimeMs > 0) {
            dateFormat.format(Date(runStartTimeMs))
        } else {
            dateFormat.format(Date())
        }
        val screenshotStr = dateFormat.format(Date())
        return "${sanitizedName}_${runStartStr}_$screenshotStr.png"
    }

    /**
     * Get the screenshot file in the Downloads/tHUD/screenshots folder.
     */
    private fun getScreenshotFile(filename: String): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val screenshotsDir = File(downloadsDir, "tHUD/screenshots")
        return File(screenshotsDir, filename)
    }

    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
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
    }
}
