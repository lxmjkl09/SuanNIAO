package dev.suanniao

import org.junit.Assert.*
import org.junit.Test

class ScreenReconnectPolicyTest {
    @Test fun transientFailureKeepsH264Preferred() {
        val policy = ScreenReconnectPolicy()
        assertFalse(policy.recordH264Failure(1_000))
        assertEquals(1, policy.recentFailures())
    }

    @Test fun repeatedFailureFallsBackEvenAfterSeveralHealthyFiveSecondSamples() {
        val policy = ScreenReconnectPolicy()
        assertFalse(policy.recordH264Failure(1000))
        assertFalse(policy.recordH264Failure(30_000))
        assertTrue(policy.recordH264Failure(61_000))
    }

    @Test fun oldFailuresExpireAfterWindow() {
        val policy = ScreenReconnectPolicy()
        assertFalse(policy.recordH264Failure(100))
        assertFalse(policy.recordH264Failure(91_000))
        assertFalse(policy.recordH264Failure(91_500))
        assertTrue(policy.recordH264Failure(92_000))
    }

    @Test fun exactBoundaryIsIncluded() {
        val policy = ScreenReconnectPolicy(windowMs = 90_000, maxFailures = 2)
        assertFalse(policy.recordH264Failure(1_000))
        assertTrue(policy.recordH264Failure(91_000))
    }
}
