package dev.suanniao

import org.junit.Assert.*
import org.junit.Test

class FloatingToolDragTest {
    @Test fun reservesGestureBeforeParentCanScrollAndReleasesOnTap() {
        val capture = mutableListOf<Boolean>()
        val drag = FloatingToolDrag(6f, capture::add)
        drag.down(100f, 100f)
        assertEquals(listOf(true), capture)
        assertFalse(drag.move(103f, 102f))
        assertEquals(FloatingToolDrag.End.TAP, drag.up())
        assertEquals(listOf(true, false), capture)
    }

    @Test fun draggingBackToOriginNeverBecomesClick() {
        val drag = FloatingToolDrag(6f) {}
        drag.down(100f, 100f)
        assertTrue(drag.move(120f, 100f))
        assertTrue(drag.move(100f, 100f))
        assertEquals(FloatingToolDrag.End.DRAG, drag.up())
        assertEquals(FloatingToolDrag.End.NONE, drag.up())
    }

    @Test fun cancellationReleasesParentAndCannotClickOrPersist() {
        val capture = mutableListOf<Boolean>()
        val drag = FloatingToolDrag(6f, capture::add)
        drag.down(10f, 20f)
        drag.move(40f, 20f)
        drag.cancel()
        assertEquals(listOf(true, false), capture)
        assertFalse(drag.move(50f, 20f))
        assertEquals(FloatingToolDrag.End.NONE, drag.up())
    }

    @Test fun sameThresholdForHorizontalAndVerticalDrag() {
        val drag = FloatingToolDrag(6f) {}
        drag.down(0f, 0f)
        assertFalse(drag.move(6f, 0f))
        assertTrue(drag.move(0f, 7f))
        assertEquals(0f, drag.dx, 0f)
        assertEquals(7f, drag.dy, 0f)
    }

    @Test fun oversizedToolPinsToLeadingEdgeInsteadOfThrowing() {
        assertEquals(4f, FloatingToolDrag.clampAxis(500f, 0, 340, 320, 4), 0f)
        assertEquals(-96f, FloatingToolDrag.clampAxis(0f, 100, 340, 320, 4), 0f)
    }

    @Test fun normalToolStaysInsideBothEdges() {
        assertEquals(16f, FloatingToolDrag.clampAxis(500f, 100, 80, 200, 4), 0f)
        assertEquals(-96f, FloatingToolDrag.clampAxis(-500f, 100, 80, 200, 4), 0f)
        assertEquals(0f, FloatingToolDrag.clampAxis(0f, 100, 80, 200, 4), 0f)
    }
}
