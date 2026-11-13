package dev.suanniao

import kotlin.math.hypot

/** One drag contract for floating tools; capture starts before a scroll parent can intercept. */
internal class FloatingToolDrag(
    private val threshold: Float,
    private val capture: (Boolean) -> Unit,
) {
    enum class End { NONE, TAP, DRAG }
    private var active = false
    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    var dx = 0f
        private set
    var dy = 0f
        private set

    fun down(x: Float, y: Float) {
        active = true
        dragging = false
        startX = x
        startY = y
        dx = 0f
        dy = 0f
        capture(true)
    }

    fun move(x: Float, y: Float): Boolean {
        if (!active) return false
        dx = x - startX
        dy = y - startY
        if (hypot(dx, dy) > threshold) dragging = true
        return dragging
    }

    fun up(): End {
        if (!active) return End.NONE
        val result = if (dragging) End.DRAG else End.TAP
        cancel()
        return result
    }

    fun cancel() {
        if (active) capture(false)
        active = false
        dragging = false
    }

    companion object {
        fun clampAxis(value: Float, origin: Int, size: Int, available: Int, margin: Int): Float {
            val min = (margin - origin).toFloat()
            val max = (available - margin - origin - size).toFloat().coerceAtLeast(min)
            return value.coerceIn(min, max)
        }
    }
}
