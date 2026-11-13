package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenCoordinatesTest {
    @Test
    fun portraitLetterboxMapsCenterExactly() {
        assertEquals(Pair(960, 540), ScreenCoordinates.toDesktop(
            1920, 1080, 400, 800, 1f, 0f, 0f, 200f, 400f,
        ))
        assertNull(ScreenCoordinates.toDesktop(
            1920, 1080, 400, 800, 1f, 0f, 0f, 200f, 100f,
        ))
    }

    @Test
    fun pinchZoomKeepsCoordinatesAndPanChangesTarget() {
        assertEquals(Pair(400, 300), ScreenCoordinates.toDesktop(
            800, 600, 400, 300, 2f, 0f, 0f, 200f, 150f,
        ))
        assertEquals(Pair(500, 300), ScreenCoordinates.toDesktop(
            800, 600, 400, 300, 2f, -100f, 0f, 200f, 150f,
        ))
    }

    @Test
    fun outsideImageDoesNotSendClickButDragReleaseIsClamped() {
        assertNull(ScreenCoordinates.toDesktop(
            800, 600, 400, 300, 1f, 0f, 0f, 450f, 160f,
        ))
        assertEquals(Pair(799, 320), ScreenCoordinates.toDesktop(
            800, 600, 400, 300, 1f, 0f, 0f, 450f, 160f, clamp = true,
        ))
    }

    @Test
    fun invalidSizesAndNonFiniteZoomAreRejected() {
        assertNull(ScreenCoordinates.toDesktop(0, 600, 400, 300, 1f, 0f, 0f, 100f, 100f))
        assertNull(ScreenCoordinates.toDesktop(800, 600, 400, 300, Float.NaN, 0f, 0f, 100f, 100f))
    }
}
