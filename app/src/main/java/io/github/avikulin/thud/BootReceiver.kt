package io.github.avikulin.thud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast and starts the HUD service.
 * The service will connect to the treadmill and be ready to show the HUD when requested.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, starting HUDService")

            val serviceIntent = Intent(context, HUDService::class.java).apply {
                action = HUDService.ACTION_START_SERVICE
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
