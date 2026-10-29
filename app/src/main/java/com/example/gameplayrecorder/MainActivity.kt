package com.example.gameplayrecorder

import android.Manifest
import android.content.Intent
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
            updateUi(running = true)
        }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.CAMERA] == true && granted[Manifest.permission.RECORD_AUDIO] == true) {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projectionConsent.launch(manager.createScreenCaptureIntent())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button = Button(this).apply { setOnClickListener { toggleRecording() } }
        setContentView(FrameLayout(this).apply {
            addView(button, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        })
    }

    override fun onResume() {
        super.onResume()
        updateUi(RecorderService.isRunning)
    }

    private fun updateUi(running: Boolean) {
        // Recording starts from the camera overlay once it's positioned.
        button.text = if (running) "Stop recording" else "Set up recording"
    }

    private fun toggleRecording() {
        if (RecorderService.isRunning) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            updateUi(running = false)
        } else if (!Settings.canDrawOverlays(this)) {
            // Needed for the camera preview overlay; can only be granted from Settings.
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
