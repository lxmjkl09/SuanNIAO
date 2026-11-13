package dev.suanniao

import org.junit.Assert.*
import org.junit.Test

class WorkbenchSaveGateTest {
    @Test fun aSuccessfulAckCannotUnlockUntilMatchingConfigReturns() {
        val gate = WorkbenchSaveGate(10_000)
        assertTrue(gate.begin("A", "layout-new", 100))
        assertTrue(gate.onAck("A", true))
        assertTrue(gate.busy)
        assertFalse(gate.onConfigEcho("layout-old"))
        assertTrue(gate.busy)
        assertTrue(gate.onConfigEcho("layout-new"))
        assertFalse(gate.busy)
    }

    @Test fun timeoutAllowsRetryAndLateAckCannotUnlockNextRequest() {
        val gate = WorkbenchSaveGate(10_000)
        assertTrue(gate.begin("A", "one", 100))
        assertFalse(gate.expire("A", 10_099))
        assertTrue(gate.expire("A", 10_100))
        assertTrue(gate.begin("B", "two", 10_101))
        assertFalse(gate.onAck("A", false))
        assertFalse(gate.expire("A", 30_000))
        assertTrue(gate.busy)
        assertFalse(gate.onConfigEcho("one"))
        assertTrue(gate.onAck("B", false))
        assertFalse(gate.busy)
    }

    @Test fun rejectedOrUnsentSaveReleasesLockWithoutAlteringConfig() {
        val gate = WorkbenchSaveGate()
        assertTrue(gate.begin("valid", "v2-geometry", 0))
        assertFalse(gate.begin("duplicate", "other", 1))
        gate.abort("unknown")
        assertTrue(gate.busy)
        gate.abort("valid")
        assertFalse(gate.busy)
        assertTrue(gate.begin("retry", "v2-geometry", 2))
        assertTrue(gate.onAck("retry", false))
        assertFalse(gate.busy)
    }

    @Test fun abandoningAPendingSaveReleasesTheGateAndKeepsItsSignature() {
        val gate = WorkbenchSaveGate()
        assertNull(gate.abandon())
        assertTrue(gate.begin("A", "draft", 0))
        assertEquals("draft", gate.abandon())
        assertFalse(gate.busy)
        // 电脑后来推回这份布局：已经不在等了，不算这一次保存
        assertFalse(gate.onConfigEcho("draft"))
        assertFalse(gate.onAck("A", true))
        assertTrue(gate.begin("B", "next", 1))
    }

    @Test fun invalidConfigDoesNotConfirmOrReleasePending() {
        val gate = WorkbenchSaveGate()
        assertTrue(gate.begin("first", "expected-v2", 0))
        assertFalse(gate.onConfigEcho("fallback-v1"))
        assertFalse(gate.onAck("", true))
        assertFalse(gate.onAck("unrelated", false))
        assertTrue(gate.busy)
        gate.reset()
        assertFalse(gate.busy)
    }
}
