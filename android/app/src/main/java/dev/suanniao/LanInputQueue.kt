package dev.suanniao

import java.util.concurrent.LinkedBlockingDeque

/**
 * LAN input send FIFO. Only coalesces adjacent pending screenPointer MOVE
 * messages. DOWN/UP, scroll, keys, selection and STOP remain strictly ordered.
 *
 * No history is ever removed after the writer has already dequeued an item.
 * This prevents stale cursor positions from growing behind a slow TLS writer
 * without dropping drag state transitions.
 */
internal class LanInputQueue {
    private class PendingScreenMove(val line: String)
    private val pending = LinkedBlockingDeque<Any>()
    private val stopped = Any()

    fun offer(line: String, screenMove: Boolean = false) {
        synchronized(pending) {
            if (screenMove) {
                val tail = pending.peekLast()
                if (tail is PendingScreenMove) {
                    // Identity-based removal: do not remove an unrelated item if
                    // the writer took the last MOVE between peek and removal.
                    pending.removeLastOccurrence(tail)
                }
                pending.offerLast(PendingScreenMove(line))
            } else {
                pending.offerLast(line)
            }
        }
    }

    fun stop() {
        synchronized(pending) { pending.offerLast(stopped) }
    }

    /** Null indicates the shutdown token. */
    fun take(): String? = when (val item = pending.takeFirst()) {
        stopped -> null
        is PendingScreenMove -> item.line
        else -> item as String
    }

    /** Read-only for diagnostics and unit tests; not a frame latency metric. */
    fun depth(): Int = pending.size
}
