package com.example.gameplayrecorder

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var button: Button
    /** Sent to Settings for the overlay permission; continue asking for the rest on return. */
    private var awaitingOverlayPermission = false

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val bounds = windowManager.maximumWindowMetrics.bounds
            val intent = Intent(this, RecorderService::class.java)
                .putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(RecorderService.EXTRA_DATA, result.data)
                .putExtra(RecorderService.EXTRA_WIDTH, bounds.width())
                .putExtra(RecorderService.EXTRA_HEIGHT, bounds.height())
                .putExtra(RecorderService.EXTRA_DPI, resources.displayMetrics.densityDpi)
            startForegroundService(intent)
            updateUi(RecorderService.State.IDLE)
        }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.CAMERA] == true && granted[Manifest.permission.RECORD_AUDIO] == true) {
                val manager = getSystemService(MediaProjectionManager::class.java)
                // Only offer the entire screen: the recorded band is positioned in full-screen coordinates.
                val config = MediaProjectionConfig.createConfigForDefaultDisplay()
                projectionConsent.launch(manager.createScreenCaptureIntent(config))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button = Button(this).apply { setOnClickListener { onButtonClick() } }
        setContentView(FrameLayout(this).apply {
            addView(button, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        })
        // Get every permission up front so recordings can then be started from the notification.
        if (savedInstanceState == null && RecorderService.state == RecorderService.State.STOPPED) {
            requestCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            if (Settings.canDrawOverlays(this)) requestCapture()
        }
        updateUi(RecorderService.state)
    }

    private fun updateUi(state: RecorderService.State) {
        // Recording starts from the camera overlay once it's positioned.
        button.text = when (state) {
            RecorderService.State.STOPPED -> "Allow screen capture"
            RecorderService.State.IDLE -> "Set up recording"
            RecorderService.State.SETUP, RecorderService.State.RECORDING -> "Stop recording"
        }
    }

    private fun onButtonClick() {
        when (RecorderService.state) {
            RecorderService.State.STOPPED -> requestCapture()
            RecorderService.State.IDLE -> {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_SETUP))
                updateUi(RecorderService.State.SETUP)
            }
            RecorderService.State.SETUP, RecorderService.State.RECORDING -> {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                updateUi(RecorderService.State.IDLE)
            }
        }
    }

    /** Asks for the overlay, camera, microphone, and notification permissions, then screen capture. */
    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            // Needed for the camera preview overlay; can only be granted from Settings.
            awaitingOverlayPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        } else {
            permissions.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ))
        }
    }
}
