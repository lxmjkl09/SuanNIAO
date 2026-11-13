package dev.suanniao

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Authenticated SCN2 H264/MediaCodec with legacy SCN1 JPEG fallback. */
class ScreenStream(private val context: Context) {
    private val tag = "SuanNIAO-screen"
    private val ui = Handler(Looper.getMainLooper())
    private val pendingFrame = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var socket: SSLSocket? = null
    private var target: ScreenView? = null
    private var current: String? = null

    fun start(binding: LanBinding, view: ScreenView) {
        if (binding.revoked || (binding.screenPort !in 1..65535 && binding.screenH264Port !in 1..65535)) {
            stop()
            view.showDisconnected("电脑端尚未提供画面")
            return
        }
        val key = "${binding.pc}:${binding.phoneId}:${binding.screenPort}:${binding.screenH264Port}"
        if (target === view && current == key) return
        stop()
        target = view
        current = key
        val seq = ++generation
        thread(name = "suanniao-screen", isDaemon = true) {
            val token = LanSecure.open(context, binding.sealedToken)
            if (token == null) {
                ui.post { if (generation == seq) view.showDisconnected("请重新绑定局域网") }
                return@thread
            }
            var preferH264 = binding.screenH264Port in 1..65535
            // Five healthy seconds is not proof of transport stability. Count
            // recent failures across healthy samples to avoid a permanent
            // H264 start/EOF/retry storm on Redmi's video path.
            val reconnectPolicy = ScreenReconnectPolicy()
            Log.i(tag, "video negotiation: H264 advertised=$preferH264")
            while (generation == seq) {
                var conn: SSLSocket? = null
                var stage = "connect"
                try {
                    val port = if (preferH264) binding.screenH264Port else binding.screenPort
                    if (port !in 1..65535) throw IOException("Screen port missing")
                    conn = connect(binding, port, token, seq) ?: throw IOException("Screen connection unavailable")
                    if (generation != seq) { conn.close(); return@thread }
                    socket = conn
                    // Avoid a permanently frozen frame if an encoder/TLS peer
                    // stops producing data without closing its socket.
                    conn.soTimeout = 8_000
                    val input = DataInputStream(BufferedInputStream(conn.inputStream, 64 * 1024))
                    stage = if (preferH264) "H264 decode" else "JPEG decode"
                    Log.i(tag, "video transport authenticated: $stage")
                    if (preferH264) readH264(input, view, seq) {
                        // No reset here: repeated EOF after a successful
                        // five-second sample still counts as an unstable session.
                    } else readJpeg(input, view, seq)
                } catch (e: Exception) {
                    if (generation != seq) continue
                    // Log only the transport phase and exception class: never tokens,
                    // local IPs, certificate fingerprints or user screen contents.
                    Log.w(tag, "video failed at $stage: ${e.javaClass.simpleName}")
                    ui.post { if (generation == seq && target === view) view.showReconnecting() }
                    if (preferH264 && binding.screenPort in 1..65535) {
                        // Grid/fullscreen transitions can overlap with a closing encoder.
                        // Retry before permanently falling back to JPEG.
                        val useJpeg = reconnectPolicy.recordH264Failure(
                            SystemClock.elapsedRealtime())
                        if (useJpeg) {
                            preferH264 = false
                            Log.w(tag, "fallback to JPEG after repeated H264 EOFs within 90 seconds")
                        } else {
                            Log.w(tag, "retrying H264 (recent failures=${reconnectPolicy.recentFailures()})")
                        }
                    }
                } finally {
                    if (socket === conn) socket = null
                    runCatching { conn?.close() }
                }
                if (generation == seq) Thread.sleep(if (preferH264) 450 else 900)
            }
        }
    }

    private fun connect(binding: LanBinding, port: Int, token: String, seq: Int): SSLSocket? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.allNetworks?.firstOrNull { n ->
            manager.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        for (addr in binding.tryOrder()) {
            if (generation != seq) return null
            var candidate: SSLSocket? = null
            try {
                candidate = LanSecure.connect(addr, port, binding.fp, 5000, network)
                candidate.tcpNoDelay = true
                val pc = LanSecure.auth(candidate, binding.phoneId, token)
                if (pc != binding.pc) throw IOException("Wrong computer")
                val result = candidate
                candidate = null
                return result
            } catch (_: Exception) {
            } finally { runCatching { candidate?.close() } }
        }
        return null
    }

    private fun readJpeg(input: DataInputStream, view: ScreenView, seq: Int) {
        while (generation == seq) {
            if (input.readInt() != MAGIC_JPEG) throw IOException("JPEG frame mismatch")
            val w = input.readInt()
            val h = input.readInt()
            val size = input.readInt()
            if (w !in 1..3840 || h !in 1..3840 || size !in 1..MAX_FRAME) throw IOException("JPEG frame bounds")
            val payload = ByteArray(size)
            input.readFully(payload)
            val bmp = BitmapFactory.decodeByteArray(payload, 0, size) ?: throw IOException("JPEG decode failed")
            if (generation != seq) { bmp.recycle(); return }
            if (!pendingFrame.compareAndSet(false, true)) { bmp.recycle(); continue }
            ui.post {
                try {
                    if (generation == seq && target === view) view.renderFrame(bmp, w, h)
                    else bmp.recycle()
                } finally { pendingFrame.set(false) }
            }
        }
    }

    private fun readH264(input: DataInputStream, view: ScreenView, seq: Int, onHealthy: () -> Unit) {
        var decoder: H264ScreenDecoder? = null
        var dimensions: Pair<Int, Int>? = null
        var sampleAt = SystemClock.elapsedRealtime()
        var frameCount = 0
        var byteCount = 0L
        var lastFrameAt = 0L
        var maxInterframeMs = 0L
        var decodedBefore = 0L
        var presentedBefore = 0L
        try {
            while (generation == seq) {
                if (input.readInt() != MAGIC_H264) throw IOException("H264 frame mismatch")
                val w = input.readInt()
                val h = input.readInt()
                val encodedW = input.readInt()
                val encodedH = input.readInt()
                val size = input.readInt()
                if (w !in 1..3840 || h !in 1..3840 || encodedW !in 1..3840 ||
                    encodedH !in 1..3840 || size !in 1..MAX_FRAME
                ) throw IOException("H264 frame bounds")
                val au = ByteArray(size)
                input.readFully(au)
                if (dimensions != Pair(encodedW, encodedH)) {
                    decoder?.close()
                    decoder = null
                    val latch = CountDownLatch(1)
                    var surface: Surface? = null
                    ui.post {
                        try {
                            if (generation == seq && target === view) surface = view.createDecoderSurface(w, h, encodedW, encodedH)
                        } finally { latch.countDown() }
                    }
                    if (!latch.await(2, TimeUnit.SECONDS) || generation != seq) {
                        surface?.release()
                        throw IOException("Screen surface unavailable")
                    }
                    val output = surface ?: throw IOException("Screen surface missing")
                    try { decoder = H264ScreenDecoder(encodedW, encodedH, output, au) }
                    catch (error: Exception) { output.release(); throw error }
                    dimensions = Pair(encodedW, encodedH)
                    Log.i(tag, "H264 MediaCodec configured: ${encodedW}x${encodedH}")
                }
                decoder?.submit(au) ?: throw IOException("H264 decoder stopped")
                // Switch video layers only after the decoder really produced
                // a frame. Codec configuration alone does not guarantee a
                // visible image, especially on Xiaomi/MediaTek devices.
                if (decoder?.renderedFrames() ?: 0L > 0L) {
                    ui.post {
                        if (generation == seq && target === view) view.showH264Frames()
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (lastFrameAt > 0) maxInterframeMs = maxOf(maxInterframeMs, now - lastFrameAt)
                lastFrameAt = now
                frameCount++
                byteCount += size
                if (now - sampleAt >= 5000) {
                    val elapsed = now - sampleAt
                    val received = frameCount * 1000.0 / elapsed
                    val decoderFrames = decoder?.renderedFrames() ?: 0L
                    val shown = (decoderFrames - decodedBefore) * 1000.0 / elapsed
                    val presented = decoder?.presentedFrames() ?: 0L
                    val presentFps = (presented - presentedBefore) * 1000.0 / elapsed
                    val callbackLag = decoder?.lastRenderCallbackLagMs() ?: -1L
                    val mbps = byteCount * 8.0 / elapsed / 1000.0
                    Log.i(tag, "H264 sample: recvFps=%.1f outputFps=%.1f presentFps=%.1f bitrateMbps=%.2f maxGapMs=%d localRenderCallbackMs=%d".format(
                        java.util.Locale.US, received, shown, presentFps, mbps, maxInterframeMs, callbackLag))
                    onHealthy()
                    sampleAt = now
                    frameCount = 0
                    byteCount = 0L
                    decodedBefore = decoderFrames
                    presentedBefore = presented
                    maxInterframeMs = 0L
                }
            }
        } finally { decoder?.close() }
    }

    fun stop() {
        generation++
        val old = socket
        socket = null
        runCatching { old?.close() }
        target = null
        current = null
    }

    private companion object {
        const val MAGIC_JPEG = 0x53434e31
        const val MAGIC_H264 = 0x53434e32
        const val MAX_FRAME = 3 * 1024 * 1024
    }
}
