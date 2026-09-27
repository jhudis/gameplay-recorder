package com.example.gameplayrecorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Thread-safe MP4 muxer shared by the audio and video encoders. Samples that arrive before every
 * track has been added are buffered so the first video keyframe isn't lost.
 */
class Muxer(fd: FileDescriptor, private val trackCount: Int, private val onFinished: () -> Unit) {

    private class Sample(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val pending = ArrayList<Sample>()
    private var addedTracks = 0
    private var finishedTracks = 0
    private var started = false

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val track = muxer.addTrack(format)
        if (++addedTracks == trackCount) {
            muxer.start()
            started = true
            pending.forEach { muxer.writeSampleData(it.track, it.data, it.info) }
            pending.clear()
        }
        return track
    }

    @Synchronized
    fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (started) {
            muxer.writeSampleData(track, data, info)
            return
        }
        data.position(info.offset)
        data.limit(info.offset + info.size)
        val copy = ByteBuffer.allocate(info.size)
        copy.put(data)
        copy.flip()
        val copyInfo = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        pending += Sample(track, copy, copyInfo)
    }

    @Synchronized
    fun trackFinished() {
        if (++finishedTracks < trackCount) return
        if (started) runCatching { muxer.stop() }
        muxer.release()
        onFinished()
    }
}

/** Pulls encoded output from [codec] into [muxer] until end of stream. Blocks, so run it on its own thread. */
fun drainEncoder(codec: MediaCodec, muxer: Muxer) {
    val info = MediaCodec.BufferInfo()
    var track = -1
    while (true) {
        val index = codec.dequeueOutputBuffer(info, 10_000)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            track = muxer.addTrack(codec.outputFormat)
        } else if (index >= 0) {
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) muxer.write(track, codec.getOutputBuffer(index)!!, info)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }
    codec.stop()
    codec.release()
    muxer.trackFinished()
}
