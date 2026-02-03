package io.github.avikulin.thud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Launcher activity that shows the HUD.
 * Click app icon → show HUD (does nothing if already visible)
 * Close button in HUD → hide HUD
 * Service keeps running in background for workout tracking.
 */
class MainActivity : AppCompatActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            checkNotificationPermissionAndShow()
        } else {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Start HUD regardless of notification permission result
        // The notification is nice-to-have but not required
        showHudAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkOverlayPermissionAndShow()
    }

    private fun checkOverlayPermissionAndShow() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        checkNotificationPermissionAndShow()
    }

    private fun checkNotificationPermissionAndShow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        showHudAndFinish()
    }

    private fun showHudAndFinish() {
        val intent = Intent(this, HUDService::class.java).apply {
            action = HUDService.ACTION_SHOW_HUD
        }
        startForegroundService(intent)
        finish()
    }
}
