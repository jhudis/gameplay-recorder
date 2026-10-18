package com.example.gameplayrecorder

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_PREVIEW_AT_BOTTOM = "previewAtBottom"
    }

    private lateinit var button: Button
    private lateinit var previewAtBottomSwitch: SwitchCompat
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

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
                .putExtra(RecorderService.EXTRA_PREVIEW_AT_BOTTOM, previewAtBottomSwitch.isChecked)
            startForegroundService(intent)
            updateUi(recording = true)
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
        previewAtBottomSwitch = SwitchCompat(this).apply {
            text = "Camera preview at bottom"
            isChecked = prefs.getBoolean(KEY_PREVIEW_AT_BOTTOM, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit { putBoolean(KEY_PREVIEW_AT_BOTTOM, checked) }
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(previewAtBottomSwitch)
                addView(button)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        })
    }

    override fun onResume() {
        super.onResume()
        updateUi(RecorderService.isRecording)
    }

    private fun updateUi(recording: Boolean) {
        button.text = if (recording) "Stop recording" else "Start recording"
        // The preview position is fixed for the length of a recording.
        previewAtBottomSwitch.isEnabled = !recording
    }

    private fun toggleRecording() {
        if (RecorderService.isRecording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            updateUi(recording = false)
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
