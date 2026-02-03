package io.github.avikulin.thud.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent activity to request MediaProjection permission.
 * MediaProjection requires an Activity to show the system permission dialog.
 * This activity is transparent and finishes immediately after getting the result.
 */
class ScreenshotPermissionActivity : Activity() {

    companion object {
        private const val TAG = "ScreenshotPermission"
        private const val REQUEST_CODE_MEDIA_PROJECTION = 1001

        // Static callback for permission result
        private var permissionCallback: ((resultCode: Int, data: Intent?) -> Unit)? = null

        /**
         * Request screenshot permission.
         * @param context Context to start the activity
         * @param callback Called with result code and data intent
         */
        fun requestPermission(context: Context, callback: (resultCode: Int, data: Intent?) -> Unit) {
            permissionCallback = callback
            val intent = Intent(context, ScreenshotPermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Requesting MediaProjection permission")

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_MEDIA_PROJECTION
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            Log.d(TAG, "MediaProjection permission result: $resultCode")
            permissionCallback?.invoke(resultCode, data)
            permissionCallback = null
        }

        finish()
    }
}
