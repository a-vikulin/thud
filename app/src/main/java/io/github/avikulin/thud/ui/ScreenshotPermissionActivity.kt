package io.github.avikulin.thud.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Transparent activity to request MediaProjection and storage permissions.
 * MediaProjection requires an Activity to show the system permission dialog.
 * Storage permission is needed to access wallpaper for DRM content substitution.
 * This activity is transparent and finishes immediately after getting the results.
 */
class ScreenshotPermissionActivity : Activity() {

    companion object {
        private const val TAG = "ScreenshotPermission"
        private const val REQUEST_CODE_MEDIA_PROJECTION = 1001
        private const val REQUEST_CODE_STORAGE_PERMISSION = 1002

        // Static callback for permission result
        private var permissionCallback: ((resultCode: Int, data: Intent?) -> Unit)? = null

        /**
         * Request screenshot permission (and storage permission for wallpaper access).
         * @param context Context to start the activity
         * @param callback Called with result code and data intent
         */
        fun requestPermission(context: Context, callback: (resultCode: Int, data: Intent?) -> Unit) {
            permissionCallback = callback
            val intent = Intent(context, ScreenshotPermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /**
         * Check if storage permission is granted for wallpaper access.
         * WallpaperManager requires READ_EXTERNAL_STORAGE even on Android 13+ on some devices.
         */
        fun hasStoragePermission(context: Context): Boolean {
            // Check READ_EXTERNAL_STORAGE (required by WallpaperManager on all versions)
            val hasReadExternal = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            // On Android 13+, also need READ_MEDIA_IMAGES
            val hasMediaImages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not needed on older versions
            }

            return hasReadExternal && hasMediaImages
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Starting permission requests")

        // First, request storage permissions for wallpaper access (if not already granted)
        if (!hasStoragePermission(this)) {
            requestStoragePermissions()
        } else {
            // Storage permissions already granted, proceed to MediaProjection
            requestMediaProjection()
        }
    }

    private fun requestStoragePermissions() {
        Log.d(TAG, "Requesting storage permissions for wallpaper access")
        // Request both permissions - WallpaperManager needs READ_EXTERNAL_STORAGE even on Android 13+
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_STORAGE_PERMISSION)
    }

    private fun requestMediaProjection() {
        Log.d(TAG, "Requesting MediaProjection permission")
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_MEDIA_PROJECTION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Storage permission ${if (granted) "granted" else "denied"}")
            // Proceed to MediaProjection regardless of storage permission result
            // (wallpaper is optional, MediaProjection is required)
            requestMediaProjection()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            Log.d(TAG, "MediaProjection permission result: $resultCode")
            permissionCallback?.invoke(resultCode, data)
            permissionCallback = null
            finish()
        }
    }
}
