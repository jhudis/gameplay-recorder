package com.example.gameplayrecorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** Foreground service that owns the screen capture, camera, audio, and output file for one recording. */
class RecorderService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gameplayrecorder.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_PREVIEW_AT_BOTTOM = "previewAtBottom"
        private const val CHANNEL_ID = "recording"

        var isRecording = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var camera: CameraDevice? = null
    private var compositor: VideoCompositor? = null
    private var audio: AudioCapture? = null
    private var preview: View? = null
    private var previewWindowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
        } else if (intent != null && !isRecording) {
            startForeground(
                1, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            startRecording(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        isRecording = true
        val dpi = intent.getIntExtra(EXTRA_DPI, 0)
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(
            intent.getIntExtra(EXTRA_RESULT_CODE, 0),
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)!!,
        )!!
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopRecording()

            // Fires when capture begins and whenever the captured content changes size (e.g. rotation).
            override fun onCapturedContentResize(width: Int, height: Int) {
                compositor?.setScreenSize(width, height)
                virtualDisplay?.resize(width, height, dpi)
            }
        }, Handler(mainLooper))

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Gameplay_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/GameplayRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })!!
        val fileDescriptor = contentResolver.openFileDescriptor(uri, "rw")!!
        val muxer = Muxer(fileDescriptor.fileDescriptor, trackCount = 2) {
            mainExecutor.execute { finishRecording(uri, fileDescriptor) }
        }

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val previewAtBottom = intent.getBooleanExtra(EXTRA_PREVIEW_AT_BOTTOM, false)
        val compositor = VideoCompositor(muxer, display, cropScreenBottom = previewAtBottom)
        this.compositor = compositor

        val width = intent.getIntExtra(EXTRA_WIDTH, VideoCompositor.WIDTH)
        val height = intent.getIntExtra(EXTRA_HEIGHT, VideoCompositor.HEIGHT)
        compositor.setScreenSize(width, height)
        virtualDisplay = projection.createVirtualDisplay(
            "GameplayRecorder", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, compositor.screenSurface, null, null,
        )

        openFrontCamera(compositor)
        showCameraPreview(compositor, display, width, height, previewAtBottom)
        audio = AudioCapture(projection, muxer).apply { start() }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        // Remove the preview before the compositor shuts down so it can release the preview surface.
        preview?.let { previewWindowManager?.removeViewImmediate(it) }
        preview = null
        camera?.close()
        camera = null
        virtualDisplay?.release()
        virtualDisplay = null
        compositor?.stop()
        compositor = null
        audio?.stop()
        audio = null
        projection?.stop()
        projection = null
    }

    /**
     * Shows the camera in an overlay in the band just above the part of the screen that gets recorded,
     * or just below it if [atBottom]. That band is cropped out of the video, so the overlay never
     * appears in it.
     */
    private fun showCameraPreview(
        compositor: VideoCompositor,
        display: Display,
        screenWidth: Int,
        screenHeight: Int,
        atBottom: Boolean,
    ) {
        if (!Settings.canDrawOverlays(this)) return
        val scale = screenWidth.toFloat() / VideoCompositor.WIDTH
        val cameraHeight = (VideoCompositor.CAMERA_HEIGHT * scale).roundToInt()
        val recordedScreenHeight = (VideoCompositor.SCREEN_HEIGHT * scale).roundToInt()
        val params = WindowManager.LayoutParams(
            screenWidth, cameraHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = if (atBottom) {
                recordedScreenHeight.coerceAtMost(screenHeight - cameraHeight)
            } else {
                (screenHeight - recordedScreenHeight - cameraHeight).coerceAtLeast(0)
            }
            // Place by raw screen coordinates, ignoring the status bar and display cutout.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            // Mostly see-through so the game stays visible underneath. (Touches only pass through
            // another app's overlay if it's at most 80% opaque.)
            alpha = 0.3f
        }

        val context = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val view = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
                    compositor.setPreviewTexture(surface)

                // The compositor owns the texture and releases it, so return false.
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    compositor.setPreviewTexture(null)
                    return false
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
        val windowManager = context.getSystemService(WindowManager::class.java)
        windowManager.addView(view, params)
        preview = view
        previewWindowManager = windowManager
    }

    private fun finishRecording(uri: Uri, fileDescriptor: ParcelFileDescriptor) {
        fileDescriptor.close()
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission") // CAMERA is granted before recording starts.
    private fun openFrontCamera(compositor: VideoCompositor) {
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        val characteristics = manager.getCameraCharacteristics(id)
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 && it.height <= 1920 }
            .maxBy { it.width * it.height }
        val sensorSideways = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!! % 180 != 0
        compositor.setCameraSize(size.width, size.height, sensorSideways)

        manager.openCamera(id, mainExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!isRecording) {
                    device.close()
                    return
                }
                camera = device
                val surface = compositor.cameraSurface
                device.createCaptureSession(SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    mainExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (camera !== device) return
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                .apply { addTarget(surface) }
                                .build()
                            session.setRepeatingRequest(request, null, null)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                    },
                ))
            }

            override fun onDisconnected(device: CameraDevice) = device.close()

            override fun onError(device: CameraDevice, error: Int) = device.close()
        })
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Recording gameplay")
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stopIntent,
            ).build())
            .setOngoing(true)
            .build()
    }
}
