package com.mom.privatedrawing

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Records the app's own drawing canvas straight to an MP4 file by periodically
 * rendering it onto a MediaCodec encoder's input Surface. This never touches
 * MediaProjection, so there is no "cast/record the whole screen" system prompt,
 * and only the canvas itself ends up in the video — nothing else on screen.
 */
class DrawingRecorder(
    width: Int,
    height: Int,
    private val outputPath: String,
    private val fps: Int = 12
) {
    private val evenWidth = width - (width % 2)
    private val evenHeight = height - (height % 2)

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var recording = false
    @Volatile var isPaused = false
        private set

    private var drainThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureRunnable: Runnable? = null

    /** Starts encoding. [renderFrame] is called on the main thread for every captured frame. */
    fun start(renderFrame: (Canvas) -> Unit): Boolean {
        if (evenWidth <= 0 || evenHeight <= 0) return false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, evenWidth, evenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()

            encoder = enc
            inputSurface = surface
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            recording = true
            isPaused = false

            drainThread = Thread {
                while (recording) {
                    drainOutput(blockUntilEos = false)
                }
            }.also { it.start() }

            val intervalMs = (1000L / fps)
            captureRunnable = object : Runnable {
                override fun run() {
                    if (!recording) return
                    if (!isPaused) captureFrame(renderFrame)
                    mainHandler.postDelayed(this, intervalMs)
                }
            }
            mainHandler.post(captureRunnable!!)
            return true
        } catch (e: Exception) {
            release()
            return false
        }
    }

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    private fun captureFrame(renderFrame: (Canvas) -> Unit) {
        val surface = inputSurface ?: return
        try {
            val canvas = surface.lockCanvas(null) ?: return
            try {
                renderFrame(canvas)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Exception) {
            // A missed frame is harmless — the next tick will draw the current state.
        }
    }

    private fun drainOutput(blockUntilEos: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        var attempts = 0
        while (true) {
            val idx = enc.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!blockUntilEos) return
                    attempts++
                    if (attempts > 500) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && bufferInfo.size != 0 && muxerStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
            if (!recording && !blockUntilEos) return
        }
    }

    /** Blocking — call from a background thread. Finalizes and closes the MP4 file. */
    fun stop() {
        recording = false
        captureRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            encoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            drainThread?.join(2000)
        } catch (_: Exception) {
        }
        try {
            encoder?.let { drainOutput(blockUntilEos = true) }
        } catch (_: Exception) {
        }
        release()
    }

    private fun release() {
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        encoder = null
        muxer = null
        inputSurface = null
    }
}
