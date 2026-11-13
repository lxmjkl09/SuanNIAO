package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Test

/** 局域网断了隔多久再试（v3.3 U4）：前台最多 5 秒，后台照旧最多 10 秒，电脑正连着别的手机隔 30 秒 */
class LanRetryPolicyTest {
    private fun waits(foreground: Boolean, busy: Boolean = false): List<Long> {
        var backoff = LanRetryPolicy.FIRST_MS
        return (0 until 7).map {
            LanRetryPolicy.wait(backoff, foreground, busy).also { backoff = LanRetryPolicy.next(backoff) }
        }
    }

    @Test fun foregroundRetriesAtLeastEveryFiveSeconds() {
        assertEquals(listOf(1000L, 2000L, 4000L, 5000L, 5000L, 5000L, 5000L), waits(foreground = true))
    }

    @Test fun backgroundKeepsBackingOffToTenSecondsToSaveBattery() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L, 10_000L), waits(foreground = false))
    }

    @Test fun busyComputerIsLeftAloneForThirtySecondsEvenInTheForeground() {
        assertEquals(List(7) { 30_000L }, waits(foreground = true, busy = true))
    }
}
