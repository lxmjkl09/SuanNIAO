package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanInputQueueTest {
    @Test fun adjacentMovesKeepOnlyLatestUnsentCursorPosition() {
        val queue = LanInputQueue()
        queue.offer("move-1", screenMove = true)
        queue.offer("move-2", screenMove = true)
        queue.offer("move-3", screenMove = true)
        assertEquals(1, queue.depth())
        assertEquals("move-3", queue.take())
    }

    @Test fun mouseButtonTransitionsAreNeverDiscardedOrReordered() {
        val queue = LanInputQueue()
        queue.offer("down")
        queue.offer("move-1", screenMove = true)
        queue.offer("move-2", screenMove = true)
        queue.offer("scroll")
        queue.offer("move-3", screenMove = true)
        queue.offer("up")
        queue.offer("move-4", screenMove = true)
        assertEquals(6, queue.depth())
        assertEquals("down", queue.take())
        assertEquals("move-2", queue.take())
        assertEquals("scroll", queue.take())
        assertEquals("move-3", queue.take())
        assertEquals("up", queue.take())
        assertEquals("move-4", queue.take())
    }

    @Test fun alreadyDequeuedMovesAreNotLost() {
        val queue = LanInputQueue()
        queue.offer("move-1", screenMove = true)
        assertEquals("move-1", queue.take())
        queue.offer("move-2", screenMove = true)
        assertEquals("move-2", queue.take())
    }

    @Test fun stopIsOrderedAfterPendingControlRelease() {
        val queue = LanInputQueue()
        queue.offer("down")
        queue.offer("move-1", screenMove = true)
        queue.offer("up")
        queue.stop()
        assertEquals("down", queue.take())
        assertEquals("move-1", queue.take())
        assertEquals("up", queue.take())
        assertNull(queue.take())
    }

    @Test fun nonMouseMoveMessagesAreNeverCoalesced() {
        val queue = LanInputQueue()
        queue.offer("scroll-1")
        queue.offer("scroll-2")
        queue.offer("key-a")
        assertEquals(3, queue.depth())
        assertEquals("scroll-1", queue.take())
        assertEquals("scroll-2", queue.take())
        assertEquals("key-a", queue.take())
    }
}
