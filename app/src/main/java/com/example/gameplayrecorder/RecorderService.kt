package com.example.gameplayrecorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Foreground service that owns the screen capture, camera, audio, and output file for one recording.
 * It starts in setup mode, where the camera preview can be dragged up or down to pick the recorded
 * band of the screen, and starts writing the file once the user locks the position.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gameplayrecorder.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** True from the start of setup until the recording stops. */
        var isRunning = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var camera: CameraDevice? = null
    private var compositor: VideoCompositor? = null
    private var audio: AudioCapture? = null
    private var overlayWindowManager: WindowManager? = null
    private var preview: View? = null
    private var previewParams: WindowManager.LayoutParams? = null
    private var lockButton: View? = null
    private var recordedArea: RecordedAreaView? = null
    /** Distance in pixels from the top of the screen to the top of the recorded band. */
    private var recordedTop = 0
    private var locked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
        } else if (intent != null && !isRunning) {
            startForeground(
                NOTIFICATION_ID, buildNotification("Drag the camera, then tap Start recording"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            startSetup(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    /** Starts capturing the screen and camera and shows the overlays for positioning the camera. */
    private fun startSetup(intent: Intent) {
        isRunning = true
        locked = false
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

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val compositor = VideoCompositor(display)
        this.compositor = compositor

        val width = intent.getIntExtra(EXTRA_WIDTH, VideoCompositor.WIDTH)
        val height = intent.getIntExtra(EXTRA_HEIGHT, VideoCompositor.HEIGHT)
        compositor.setScreenSize(width, height)
        virtualDisplay = projection.createVirtualDisplay(
            "GameplayRecorder", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, compositor.screenSurface, null, null,
        )

        openFrontCamera(compositor)
        if (Settings.canDrawOverlays(this)) {
            showSetupOverlays(compositor, display, width, height)
        } else {
            // Nothing to position without overlays, so record the bottom of the screen.
            recordedTop = height - (VideoCompositor.SCREEN_HEIGHT * width.toFloat() / VideoCompositor.WIDTH).roundToInt()
            lock()
        }
    }

    /** Fixes the camera position and starts writing the recording. */
    private fun lock() {
        val projection = projection
        val compositor = compositor
        if (locked || projection == null || compositor == null) return
        locked = true

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
        compositor.startEncoding(muxer, recordedTop)
        audio = AudioCapture(projection, muxer).apply { start() }

        // Hand touches back to the game and fade the preview so the game shows through it. (Touches
        // only pass through another app's overlay if it's at most 80% opaque.)
        recordedArea?.let { overlayWindowManager?.removeViewImmediate(it) }
        recordedArea = null
        lockButton?.visibility = View.GONE
        val params = previewParams
        if (preview != null && params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = 0.3f
            overlayWindowManager?.updateViewLayout(preview, params)
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("Recording gameplay"))
    }

    private fun stopRecording() {
        if (!isRunning) return
        isRunning = false
        // Remove the preview before the compositor shuts down so it can release the preview surface.
        preview?.let { overlayWindowManager?.removeViewImmediate(it) }
        preview = null
        recordedArea?.let { overlayWindowManager?.removeViewImmediate(it) }
        recordedArea = null
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
        // Stopped during setup: no file was started, so there's nothing to finish.
        if (!locked) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Shows the camera in a draggable overlay, with the band of the screen to be recorded directly
     * above or below it (whichever side has more room) outlined. The camera itself is never inside
     * the recorded band, so it never appears in the video.
     */
    @SuppressLint("ClickableViewAccessibility") // Dragging has no click equivalent.
    private fun showSetupOverlays(compositor: VideoCompositor, display: Display, screenWidth: Int, screenHeight: Int) {
        val scale = screenWidth.toFloat() / VideoCompositor.WIDTH
        val cameraHeight = (VideoCompositor.CAMERA_HEIGHT * scale).roundToInt()
        val recordedHeight = (VideoCompositor.SCREEN_HEIGHT * scale).roundToInt()
        val maxCameraTop = (screenHeight - cameraHeight).coerceAtLeast(0)
        // How far the camera can be from either edge while leaving room for a full band on the other side.
        val slack = (maxCameraTop - recordedHeight).coerceAtLeast(0)

        val context = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val windowManager = context.getSystemService(WindowManager::class.java)
        overlayWindowManager = windowManager

        val recordedArea = RecordedAreaView(context)
        windowManager.addView(recordedArea, overlayParams(
            screenWidth, screenHeight,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // The most that still lets touches through to the game.
            alpha = 0.8f
        })
        this.recordedArea = recordedArea

        val params = overlayParams(screenWidth, cameraHeight, 0, PixelFormat.OPAQUE).apply {
            // See-through enough to show the game underneath while positioning.
            alpha = 0.6f
        }
        previewParams = params

        fun placeCamera(top: Int) {
            val cameraTop = top.coerceIn(0, maxCameraTop)
            // Record whichever side of the camera is bigger, keeping the camera far enough from that
            // side's edge to fit a full band there.
            val recordAbove = cameraTop > maxCameraTop / 2
            params.y = if (recordAbove) cameraTop.coerceAtLeast(maxCameraTop - slack) else cameraTop.coerceAtMost(slack)
            recordedTop = if (recordAbove) params.y - recordedHeight else params.y + cameraHeight
            recordedArea.setBand(recordedTop, recordedHeight)
        }
        // Start with the camera above a band reaching the bottom of the screen.
        placeCamera(slack)

        val textureView = TextureView(context).apply {
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
        val lockButton = Button(context).apply {
            text = "Start recording"
            setOnClickListener { lock() }
        }
        val view = FrameLayout(context).apply {
            addView(textureView)
            addView(lockButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }

        var grabOffset = 0f
        textureView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> grabOffset = event.rawY - params.y
                MotionEvent.ACTION_MOVE -> {
                    placeCamera((event.rawY - grabOffset).roundToInt())
                    windowManager.updateViewLayout(view, params)
                }
            }
            true
        }

        windowManager.addView(view, params)
        preview = view
        this.lockButton = lockButton
    }

    /** Params for an overlay placed by raw screen coordinates, ignoring the status bar and display cutout. */
    private fun overlayParams(width: Int, height: Int, flags: Int, format: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        format,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
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
                if (!isRunning) {
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

    private fun buildNotification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(title)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stopIntent,
            ).build())
            .setOngoing(true)
            .build()
    }
}

/** Dims everything outside the band of the screen that will be recorded, and outlines the band. */
private class RecordedAreaView(context: Context) : View(context) {

    private val dimPaint = Paint().apply { color = Color.argb(128, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }
    private var bandTop = 0
    private var bandHeight = 0

    fun setBand(top: Int, height: Int) {
        bandTop = top
        bandHeight = height
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val top = bandTop.toFloat()
        val bottom = (bandTop + bandHeight).toFloat()
        val right = width.toFloat()
        canvas.drawRect(0f, 0f, right, top, dimPaint)
        canvas.drawRect(0f, bottom, right, height.toFloat(), dimPaint)
        val inset = borderPaint.strokeWidth / 2
        canvas.drawRect(inset, top + inset, right - inset, bottom - inset, borderPaint)
    }
}
