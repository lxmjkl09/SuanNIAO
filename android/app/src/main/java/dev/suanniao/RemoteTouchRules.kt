package dev.suanniao

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Unified direct-touch gesture contract. Pure math kept out of Android View
 * code so regressions in pinch/scroll recognition can be unit tested.
 */
internal object RemoteTouchRules {
    enum class TwoFingerIntent { UNDECIDED, SCROLL, ZOOM }

    const val DRAG_HOLD_MS = 420L

    /** A precise mouse must never guess wheel intent from ONE finger's
     * direction. The pointer always follows a single finger, even vertically.
     * Two fingers are reserved for wheel scrolling; the existing explicit
     * mouse wheel toolbar still works. */
    fun preciseWheelAllowed(pointerCount: Int, held: Boolean): Boolean =
        pointerCount == 2 && !held
    const val MULTI_TAP_MS = 360L

    fun classifyTwoFingers(
        initialSpan: Float,
        currentSpan: Float,
        centerDx: Float,
        centerDy: Float,
        density: Float,
    ): TwoFingerIntent {
        if (!initialSpan.isFinite() || initialSpan <= 0f ||
            !currentSpan.isFinite() || density <= 0f) return TwoFingerIntent.UNDECIDED
        val spread = abs(currentSpan - initialSpan)
        // Pinching must noticeably change the separation between fingers;
        // translating both fingers together is wheel scrolling, not zoom.
        if (spread > max(14f * density, initialSpan * .12f)) return TwoFingerIntent.ZOOM
        if (hypot(centerDx, centerDy) > 9f * density) return TwoFingerIntent.SCROLL
        return TwoFingerIntent.UNDECIDED
    }

    /** Three-finger horizontal swipe switches apps without a mode/menu. */
    fun isAppSwitchSwipe(dx: Float, dy: Float, density: Float): Boolean =
        dx.isFinite() && dy.isFinite() && density > 0f &&
            abs(dx) >= 68f * density && abs(dx) > abs(dy) * 1.6f

    /** MIUI reserves three-finger gestures for screenshots. Two-finger horizontal\n     * swipes are an accessible alternative, requiring a longer clear movement. */
    fun isTwoFingerAppSwitch(dx: Float, dy: Float, density: Float): Boolean =
        dx.isFinite() && dy.isFinite() && density > 0f &&
            abs(dx) >= 90f * density && abs(dx) > abs(dy) * 2f

    fun isTwoFingerShortcutHold(elapsedMs: Long, hadMovement: Boolean): Boolean =
        elapsedMs in 550L..1600L && !hadMovement

    fun isMultiTap(fingers: Int, elapsedMs: Long, hadMovement: Boolean): Boolean =
        fingers in 2..3 && elapsedMs in 0..MULTI_TAP_MS && !hadMovement
}
