package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTapPolicyTest {
    @Test fun idleTapStarts() {
        val phase = VoiceTapPolicy.phase(holding = false, latched = false, active = false)
        assertEquals(VoiceTapPolicy.Phase.IDLE, phase)
        assertEquals(VoiceTapPolicy.TapAction.START, VoiceTapPolicy.tapAction(phase))
    }

    @Test fun recordingTapStops() {
        val latched = VoiceTapPolicy.phase(holding = false, latched = true, active = true)
        val holding = VoiceTapPolicy.phase(holding = true, latched = false, active = true)
        assertEquals(VoiceTapPolicy.TapAction.STOP, VoiceTapPolicy.tapAction(latched))
        assertEquals(VoiceTapPolicy.TapAction.STOP, VoiceTapPolicy.tapAction(holding))
    }

    @Test fun awaitingRecognitionCannotRestart() {
        val phase = VoiceTapPolicy.phase(holding = false, latched = false, active = true)
        assertEquals(VoiceTapPolicy.Phase.RECOGNIZING, phase)
        assertEquals(VoiceTapPolicy.TapAction.WAIT, VoiceTapPolicy.tapAction(phase))
    }

    @Test fun returnsToIdleAfterRecognitionSettles() {
        assertEquals(
            VoiceTapPolicy.TapAction.START,
            VoiceTapPolicy.tapAction(VoiceTapPolicy.phase(false, false, false))
        )
    }

    @Test fun recordingTakesPrecedenceOverActiveFlag() {
        assertEquals(VoiceTapPolicy.Phase.RECORDING, VoiceTapPolicy.phase(true, false, false))
        assertEquals(VoiceTapPolicy.Phase.RECORDING, VoiceTapPolicy.phase(false, true, false))
    }
}
