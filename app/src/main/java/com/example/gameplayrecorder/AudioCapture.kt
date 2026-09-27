package com.example.gameplayrecorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import java.nio.ByteOrder

/** Records the microphone and other apps' playback audio, mixes them, and encodes to AAC. */
@SuppressLint("MissingPermission") // RECORD_AUDIO is granted before recording starts.
class AudioCapture(projection: MediaProjection, private val muxer: Muxer) {

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FRAMES_PER_READ = 1024
        const val SHORTS_PER_READ = FRAMES_PER_READ * CHANNELS
    }

    private val pcmFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    private val micRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(pcmFormat)
        .setBufferSizeInBytes(SHORTS_PER_READ * 2 * 8)
        .build()

    private val mediaRecord = AudioRecord.Builder()
        .setAudioFormat(pcmFormat)
        .setBufferSizeInBytes(SHORTS_PER_READ * 2 * 8)
        .setAudioPlaybackCaptureConfig(
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        )
        .build()

    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SHORTS_PER_READ * 2)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    @Volatile private var running = false

    fun start() {
        running = true
        encoder.start()
        micRecord.startRecording()
        mediaRecord.startRecording()
        Thread(::captureLoop, "audio-capture").start()
        Thread({ drainEncoder(encoder, muxer) }, "audio-drain").start()
    }

    fun stop() {
        running = false
    }

    private fun captureLoop() {
        val mic = ShortArray(SHORTS_PER_READ)
        val media = ShortArray(SHORTS_PER_READ)
        // Timestamps share System.nanoTime() with the video track and advance by sample count.
        val startUs = System.nanoTime() / 1000
        var framesWritten = 0L
        fun ptsUs() = startUs + framesWritten * 1_000_000 / SAMPLE_RATE

        while (running) {
            // The mic paces the loop; playback capture is read non-blocking so silence can't stall it.
            val count = micRecord.read(mic, 0, SHORTS_PER_READ)
            if (count <= 0) continue
            val mediaCount = mediaRecord.read(media, 0, count, AudioRecord.READ_NON_BLOCKING).coerceAtLeast(0)
            media.fill(0, mediaCount, count)

            val index = encoder.dequeueInputBuffer(-1)
            val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                buffer.putShort((mic[i] + media[i]).coerceIn(-32768, 32767).toShort())
            }
            encoder.queueInputBuffer(index, 0, count * 2, ptsUs(), 0)
            framesWritten += count / CHANNELS
        }

        micRecord.stop()
        micRecord.release()
        mediaRecord.stop()
        mediaRecord.release()
        val index = encoder.dequeueInputBuffer(-1)
        encoder.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }
}
