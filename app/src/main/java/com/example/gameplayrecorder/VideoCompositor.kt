package com.example.gameplayrecorder

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask

/**
 * Draws the camera (center-cropped, top third) and a band of the screen (bottom two thirds) into a
 * portrait H.264 encoder using OpenGL ES, and mirrors the camera into an on-screen preview. Until
 * [startEncoding] is called, only the preview is drawn.
 */
class VideoCompositor(private val display: Display) {

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val CAMERA_HEIGHT = HEIGHT / 3
        const val SCREEN_HEIGHT = HEIGHT - CAMERA_HEIGHT
        const val FPS = 30

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    private val thread = HandlerThread("compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    /** Distance in pixels from the top of the screen to the top of the recorded band. */
    private var recordedTop = 0

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    /** Keeps the context current when there's no encoder or preview surface to draw to. */
    private var offscreenEglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewEglSurface = EGL14.EGL_NO_SURFACE
    private val previewSize = IntArray(2)
    private var uTexMatrix = 0
    private var cameraTextureId = 0
    private var screenTextureId = 0

    private lateinit var cameraTexture: SurfaceTexture
    private lateinit var screenTexture: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set
    lateinit var screenSurface: Surface
        private set

    /** Camera width / height after the sensor's rotation to the device's natural orientation. */
    @Volatile private var cameraAspect = 9f / 16f
    @Volatile private var screenAspect = 9f / 16f
    @Volatile private var screenHeight = HEIGHT

    // Full-viewport quad; glViewport places it in the camera or screen region. GL reads these
    // client-side arrays on every draw, so they must stay referenced for the compositor's lifetime:
    // once garbage collected, their native memory is freed and draws use garbage vertices.
    private val positions = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private val stMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private val screenMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private var nextFrameTime = 0L

    private val tick = object : Runnable {
        override fun run() {
            drawFrame()
            nextFrameTime += 1000L / FPS
            handler.postAtTime(this, nextFrameTime)
        }
    }

    init {
        onGlThread(::setUpGl)
        handler.post {
            nextFrameTime = SystemClock.uptimeMillis()
            tick.run()
        }
    }

    fun setCameraSize(width: Int, height: Int, sensorSideways: Boolean) {
        cameraTexture.setDefaultBufferSize(width, height)
        cameraAspect = if (sensorSideways) height.toFloat() / width else width.toFloat() / height
    }

    fun setScreenSize(width: Int, height: Int) {
        screenTexture.setDefaultBufferSize(width, height)
        screenAspect = width.toFloat() / height
        screenHeight = height
    }

    /**
     * Mirrors the camera band onto [texture] every frame, or stops mirroring if null. Takes ownership
     * of the texture and releases it once it's replaced or the compositor stops.
     */
    fun setPreviewTexture(texture: SurfaceTexture?) {
        handler.post {
            releasePreview()
            if (texture == null) return@post
            previewTexture = texture
            previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, texture, intArrayOf(EGL14.EGL_NONE), 0)
            // Don't let the preview's vsync throttle the encoder.
            EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
            EGL14.eglSwapInterval(eglDisplay, 0)
        }
    }

    /**
     * Starts encoding into [muxer], recording the band of the screen that starts [recordedTop] pixels
     * from the top.
     */
    fun startEncoding(muxer: Muxer, recordedTop: Int) {
        handler.post {
            this.recordedTop = recordedTop
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = encoder.createInputSurface()
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            encoder.start()
            Thread({ drainEncoder(encoder, muxer) }, "video-drain").start()
            this.encoder = encoder
            encoderSurface = surface
        }
    }

    fun stop() {
        handler.post {
            handler.removeCallbacks(tick)
            releasePreview()
            encoder?.signalEndOfInputStream()
            cameraSurface.release()
            screenSurface.release()
            cameraTexture.release()
            screenTexture.release()
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroySurface(eglDisplay, offscreenEglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            encoderSurface?.release()
            thread.quitSafely()
        }
    }

    private fun setUpGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(1), 0, IntArray(1), 0)
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, IntArray(1), 0)
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        offscreenEglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, offscreenEglSurface, offscreenEglSurface, eglContext)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        GLES20.glUseProgram(program)
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        textures.forEach { id ->
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        cameraTextureId = textures[0]
        screenTextureId = textures[1]

        // Latch every new frame immediately so each draw uses the latest image from both sources.
        cameraTexture = SurfaceTexture(cameraTextureId).apply {
            setOnFrameAvailableListener({ it.updateTexImage() }, handler)
        }
        screenTexture = SurfaceTexture(screenTextureId).apply {
            setOnFrameAvailableListener({ it.updateTexImage() }, handler)
        }
        cameraSurface = Surface(cameraTexture)
        screenSurface = Surface(screenTexture)
    }

    private fun drawFrame() {
        // The camera, rotated to stay upright with the display and center-cropped to fill its band.
        val rotation = display.rotation * 90
        val aspect = if (rotation % 180 != 0) 1f / cameraAspect else cameraAspect
        val regionAspect = WIDTH.toFloat() / CAMERA_HEIGHT
        val scaleX = if (aspect < regionAspect) 1f else regionAspect / aspect
        val scaleY = if (aspect < regionAspect) aspect / regionAspect else 1f
        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(cropMatrix, 0, -rotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(cropMatrix, 0, scaleX, scaleY, 1f)
        Matrix.translateM(cropMatrix, 0, -0.5f, -0.5f, 0f)
        cameraTexture.getTransformMatrix(stMatrix)
        Matrix.multiplyMM(cameraMatrix, 0, stMatrix, 0, cropMatrix, 0)

        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Bottom: the recorded band of the screen, scaled to full width. Texture t=0 is the screen's
            // bottom edge, so the band's top is at t = 1 - topCrop.
            val topCrop = (recordedTop.toFloat() / screenHeight).coerceIn(0f, 1f)
            val visibleHeight = (screenAspect * SCREEN_HEIGHT / WIDTH).coerceAtMost(1f - topCrop)
            Matrix.setIdentityM(cropMatrix, 0)
            Matrix.translateM(cropMatrix, 0, 0f, 1f - topCrop - visibleHeight, 0f)
            Matrix.scaleM(cropMatrix, 0, 1f, visibleHeight, 1f)
            screenTexture.getTransformMatrix(stMatrix)
            Matrix.multiplyMM(screenMatrix, 0, stMatrix, 0, cropMatrix, 0)
            drawQuad(screenTextureId, screenMatrix, 0, SCREEN_HEIGHT)

            // Top: the camera.
            drawQuad(cameraTextureId, cameraMatrix, SCREEN_HEIGHT, CAMERA_HEIGHT)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        // Preview: the same camera crop, filling the overlay.
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
            EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_WIDTH, previewSize, 0)
            EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_HEIGHT, previewSize, 1)
            drawQuad(cameraTextureId, cameraMatrix, 0, previewSize[1], previewSize[0])
            EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
        }
    }

    private fun releasePreview() {
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            // Never leave the context current on a destroyed surface.
            EGL14.eglMakeCurrent(eglDisplay, offscreenEglSurface, offscreenEglSurface, eglContext)
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            previewEglSurface = EGL14.EGL_NO_SURFACE
        }
        previewTexture?.release()
        previewTexture = null
    }

    /** Draws [textureId] across a band of the current surface starting [y] pixels from the bottom. */
    private fun drawQuad(textureId: Int, matrix: FloatArray, y: Int, height: Int, width: Int = WIDTH) {
        GLES20.glViewport(0, y, width, height)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, matrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source.trimIndent())
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun floatBuffer(vararg values: Float) =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private fun <T> onGlThread(block: () -> T): T = FutureTask(block).also { handler.post(it) }.get()
}
