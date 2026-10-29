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
import android.graphics.Typeface
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
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Foreground service that holds one screen capture for as long as it runs, so consent is only asked
 * for once, and records from it any number of times, driven from its notification. Each recording
 * starts in setup mode, where the camera preview can be dragged up or down to pick the recorded band
 * of the screen, and starts writing the file once the user locks the position.
 */
class RecorderService : Service() {

    companion object {
        /** Shows the camera overlay to position it for a new recording. */
        const val ACTION_SETUP = "com.example.gameplayrecorder.SETUP"
        /** Locks the camera position and starts writing the recording. */
        const val ACTION_START = "com.example.gameplayrecorder.START"
        /** Ends the current setup or recording, keeping the screen capture for the next one. */
        const val ACTION_STOP = "com.example.gameplayrecorder.STOP"
        /** Ends any recording and releases the screen capture. */
        const val ACTION_QUIT = "com.example.gameplayrecorder.QUIT"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        var state = State.STOPPED
            private set
    }

    enum class State {
        /** No screen capture; consent is needed before recording. */
        STOPPED,
        /** Holding the screen capture, ready to set up a recording. */
        IDLE,
        /** Positioning the camera. */
        SETUP,
        RECORDING,
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth = VideoCompositor.WIDTH
    private var screenHeight = VideoCompositor.HEIGHT
    /** Recordings whose files are still being written after they stopped. */
    private var finishingRecordings = 0
    private var camera: CameraDevice? = null
    private var compositor: VideoCompositor? = null
    private var audio: AudioCapture? = null
    private var overlayWindowManager: WindowManager? = null
    private var preview: View? = null
    private var previewParams: WindowManager.LayoutParams? = null
    private var lockButton: View? = null
    private var timer: Chronometer? = null
    private var shade: ShadeView? = null
    /** Distance in pixels from the top of the screen to the top of the recorded band. */
    private var recordedTop = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SETUP -> startSetup()
            ACTION_START -> lock()
            ACTION_STOP -> endRecording()
            ACTION_QUIT -> quit()
            else -> if (intent != null && state == State.STOPPED) {
                state = State.IDLE
                // Started while the app is in the foreground, so the camera and microphone stay usable
                // for later recordings started from the notification while a game is in front.
                startForeground(
                    NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                startCapture(intent)
            }
        }
        // E.g. an action from a notification left over from a previous run.
        if (state == State.STOPPED && finishingRecordings == 0) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        quit()
        super.onDestroy()
    }

    /** Starts the screen capture, which is kept until [quit] so later recordings need no new consent. */
    private fun startCapture(intent: Intent) {
        val dpi = intent.getIntExtra(EXTRA_DPI, 0)
        screenWidth = intent.getIntExtra(EXTRA_WIDTH, VideoCompositor.WIDTH)
        screenHeight = intent.getIntExtra(EXTRA_HEIGHT, VideoCompositor.HEIGHT)
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(
            intent.getIntExtra(EXTRA_RESULT_CODE, 0),
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)!!,
        )!!
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = quit()

            // Fires when capture begins and whenever the captured content changes size (e.g. rotation).
            override fun onCapturedContentResize(width: Int, height: Int) {
                screenWidth = width
                screenHeight = height
                compositor?.setScreenSize(width, height)
                virtualDisplay?.resize(width, height, dpi)
            }
        }, Handler(mainLooper))

        // A projection can only create one virtual display, so it's kept and pointed at each
        // recording's compositor in turn. Without a surface between recordings, it renders nothing.
        virtualDisplay = projection.createVirtualDisplay(
            "GameplayRecorder", screenWidth, screenHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null,
        )
    }

    /** Starts capturing the camera and shows the overlays for positioning it. */
    private fun startSetup() {
        val virtualDisplay = virtualDisplay
        if (state != State.IDLE || virtualDisplay == null) return
        state = State.SETUP
        updateNotification()

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val compositor = VideoCompositor(display)
        this.compositor = compositor
        compositor.setScreenSize(screenWidth, screenHeight)
        virtualDisplay.surface = compositor.screenSurface

        openFrontCamera(compositor)
        if (Settings.canDrawOverlays(this)) {
            showSetupOverlays(compositor, display, screenWidth, screenHeight)
        } else {
            // Nothing to position without overlays, so record the bottom of the screen.
            recordedTop = screenHeight -
                (VideoCompositor.SCREEN_HEIGHT * screenWidth.toFloat() / VideoCompositor.WIDTH).roundToInt()
            lock()
        }
    }

    /** Fixes the camera position and starts writing the recording. */
    private fun lock() {
        val projection = projection
        val compositor = compositor
        if (state != State.SETUP || projection == null || compositor == null) return
        state = State.RECORDING
        finishingRecordings++

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
        // only pass through another app's overlays if they're at most 80% opaque combined.)
        lockButton?.visibility = View.GONE
        timer?.apply {
            base = SystemClock.elapsedRealtime()
            visibility = View.VISIBLE
            start()
        }
        val params = previewParams
        if (preview != null && params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = 0.3f
            overlayWindowManager?.updateViewLayout(preview, params)
        }
        updateNotification()
    }

    /**
     * Ends the current setup or recording and goes back to idle. A recording's file is finished in the
     * background once its encoders drain.
     */
    private fun endRecording() {
        if (state != State.SETUP && state != State.RECORDING) return
        state = State.IDLE
        // Remove the preview before the compositor shuts down so it can release the preview surface.
        timer?.stop()
        timer = null
        lockButton = null
        preview?.let { overlayWindowManager?.removeViewImmediate(it) }
        preview = null
        previewParams = null
        shade?.let { overlayWindowManager?.removeViewImmediate(it) }
        shade = null
        camera?.close()
        camera = null
        // Detach the screen before the compositor releases the surface it's drawing to.
        virtualDisplay?.surface = null
        compositor?.stop()
        compositor = null
        audio?.stop()
        audio = null
        updateNotification()
    }

    /** Ends any recording, releases the screen capture, and stops once all files are finished. */
    private fun quit() {
        if (state == State.STOPPED) return
        endRecording()
        state = State.STOPPED
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        if (finishingRecordings == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    /**
     * Shows the camera in a draggable overlay, with the band of the screen to be recorded directly
     * above or below it (whichever side has more room), and shades the rest of the screen. Neither
     * overlay draws inside the recorded band, so they never appear in the video.
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

        val shade = ShadeView(context)
        windowManager.addView(shade, overlayParams(
            screenWidth, screenHeight,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // The most that, combined with the recording-time preview's 30%, stays within the 80%
            // that still lets touches through to the game.
            alpha = 0.7f
        })
        this.shade = shade

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
            shade.setUnshaded(minOf(recordedTop, params.y), maxOf(recordedTop + recordedHeight, params.y + cameraHeight))
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
        // Elapsed recording time, shown once recording starts.
        val timer = Chronometer(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val timerMargin = (8 * resources.displayMetrics.density).roundToInt()
        val view = FrameLayout(context).apply {
            addView(textureView)
            addView(lockButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            addView(timer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(timerMargin, timerMargin, timerMargin, timerMargin) })
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
        this.timer = timer
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
        finishingRecordings--
        if (state == State.STOPPED && finishingRecordings == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
                // The recording this camera was opened for already ended.
                if (this@RecorderService.compositor !== compositor) {
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

    private fun updateNotification() =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())

    /** A notification describing the current [state], with actions to move on from it. */
    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ))
            .setOngoing(true)

        fun action(icon: Int, title: String, action: String) = builder.addAction(Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build())

        when (state) {
            State.IDLE -> {
                builder.setContentTitle("Ready to record")
                action(android.R.drawable.presence_video_online, "Set up recording", ACTION_SETUP)
                action(android.R.drawable.ic_menu_close_clear_cancel, "Quit", ACTION_QUIT)
            }
            State.SETUP -> {
                builder.setContentTitle("Drag the camera, then tap Start recording")
                action(android.R.drawable.ic_media_play, "Start recording", ACTION_START)
                action(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", ACTION_STOP)
            }
            State.RECORDING -> {
                builder.setContentTitle("Recording gameplay")
                action(android.R.drawable.ic_media_pause, "Stop", ACTION_STOP)
            }
            State.STOPPED -> builder.setContentTitle("Saving recording")
        }
        return builder.build()
    }
}

/** Lightly shades the screen above and below the band covered by the recorded area and the camera. */
private class ShadeView(context: Context) : View(context) {

    private val paint = Paint().apply { color = Color.argb(128, 0, 0, 0) }
    private var unshadedTop = 0
    private var unshadedBottom = 0

    fun setUnshaded(top: Int, bottom: Int) {
        unshadedTop = top
        unshadedBottom = bottom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val right = width.toFloat()
        canvas.drawRect(0f, 0f, right, unshadedTop.toFloat(), paint)
        canvas.drawRect(0f, unshadedBottom.toFloat(), right, height.toFloat(), paint)
    }
}
