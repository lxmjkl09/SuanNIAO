package dev.suanniao

/**
 * UI policy only: the original VoiceButton recorder/ASR lifecycle is preserved.
 * Capturing, waiting for ASR and idle must be distinguishable: a busy recognizer
 * after the second tap is NOT still recording.
 */
internal object VoiceTapPolicy {
    enum class Phase { IDLE, RECORDING, RECOGNIZING }
    enum class TapAction { START, STOP, WAIT }

    fun phase(holding: Boolean, latched: Boolean, active: Boolean): Phase = when {
        holding || latched -> Phase.RECORDING
        active -> Phase.RECOGNIZING
        else -> Phase.IDLE
    }

    fun tapAction(phase: Phase): TapAction = when (phase) {
        Phase.IDLE -> TapAction.START
        Phase.RECORDING -> TapAction.STOP
        Phase.RECOGNIZING -> TapAction.WAIT
    }
}
