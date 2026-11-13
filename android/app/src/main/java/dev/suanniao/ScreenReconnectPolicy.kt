package dev.suanniao

import java.util.ArrayDeque

/**
 * Keeps H264 as the preferred transport but avoids repeated decoder/encoder
 * reconnect storms when the video path is unstable. Measured with elapsed
 * (monotonic) time; never depends on wall clock or network credentials.
 */
internal class ScreenReconnectPolicy(
    private val windowMs: Long = 90_000L,
    private val maxFailures: Int = 3,
) {
    private val h264Failures = ArrayDeque<Long>()

    /** Returns true when JPEG should be used for the remainder of this session. */
    fun recordH264Failure(nowMs: Long): Boolean {
        while (h264Failures.isNotEmpty() &&
            nowMs - h264Failures.first > windowMs) {
            h264Failures.removeFirst()
        }
        h264Failures.addLast(nowMs)
        return h264Failures.size >= maxFailures
    }

    fun recentFailures() = h264Failures.size
}
