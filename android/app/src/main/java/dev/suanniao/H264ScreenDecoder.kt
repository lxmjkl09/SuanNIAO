package dev.suanniao

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import android.view.Surface
import java.io.IOException

/**
 * Decodes complete H.264 Annex-B access units into a hardware Surface.
 * Keeps frame order intact: never drop dependent P frames without restarting
 * decoder / reconnecting at a fresh keyframe.
 *
 * Called only on the screen streaming worker, never on Android's UI thread.
 */
internal class H264ScreenDecoder(
    width: Int,
    height: Int,
    private val surface: Surface,
    firstAccessUnit: ByteArray,
) : AutoCloseable {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var initialized = false
    private var outputFrames = 0L
    private val actualPresented = AtomicLong(0L)
    private val queueTimes = ConcurrentHashMap<Long, Long>()
    private val renderCallbackLagMs = AtomicLong(-1L)

    /** Frames acknowledged by MediaCodec's Surface presentation callback. */
    fun presentedFrames(): Long = actualPresented.get()
    /** Local queue-to-callback time only (not capture-to-display latency). */
    fun lastRenderCallbackLagMs(): Long = renderCallbackLagMs.get()

    /** Counts frames actually dequeued for Surface rendering, not encoded packets. */
    fun renderedFrames(): Long = outputFrames

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val parameters = H264ParameterSets.extract(firstAccessUnit)
                ?: throw IOException("Missing H264 SPS/PPS in initial keyframe")
            format.setByteBuffer("csd-0", parameters.sps)
            format.setByteBuffer("csd-1", parameters.pps)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            // OEM callback support varies; rendering must work even if absent.
            runCatching {
                codec.setOnFrameRenderedListener({ _, pts, _ ->
                    actualPresented.incrementAndGet()
                    queueTimes.remove(pts)?.let { queued ->
                        val ms = (System.nanoTime() - queued) / 1_000_000L
                        if (ms >= 0L) renderCallbackLagMs.set(ms)
                    }
                }, Handler(Looper.getMainLooper()))
            }
            codec.start()
            initialized = true
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    fun submit(frame: ByteArray) {
        if (frame.isEmpty() || frame.size > 3 * 1024 * 1024) throw IOException("H264 access unit size invalid")
        // Consume all available output before enqueuing more video. The input
        // order cannot be skipped: H264 predictive frames would be corrupted.
        drain(0)
        val index = codec.dequeueInputBuffer(250_000)
        if (index < 0) throw IOException("Video decoder overloaded; resync required")
        val target = codec.getInputBuffer(index) ?: throw IOException("Video decoder input unavailable")
        target.clear()
        if (target.remaining() < frame.size) throw IOException("H264 access unit too large for decoder")
        target.put(frame)
        // H264 FPS varies by PC/capability. A fixed 50ms (20FPS) PTS is wrong
        // for a 60FPS stream and can cause irregular presentation scheduling.
        // Use a monotonic local arrival clock instead of assuming a frame rate.
        ptsUs = maxOf(ptsUs + 1L, SystemClock.elapsedRealtimeNanos() / 1_000L)
        if (queueTimes.size > 256) queueTimes.clear()
        queueTimes[ptsUs] = System.nanoTime()
        codec.queueInputBuffer(index, 0, frame.size, ptsUs, 0)
        drain(0)
    }

    private fun drain(timeoutUs: Long) {
        var timeout = timeoutUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            timeout = 0
            when {
                index >= 0 -> {
                    if (info.size > 0) outputFrames++
                    codec.releaseOutputBuffer(index, true)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return
            }
        }
    }

    override fun close() {
        if (initialized) {
            runCatching { codec.stop() }
            initialized = false
        }
        runCatching { codec.release() }
        queueTimes.clear()
        runCatching { surface.release() }
    }
}
