package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFillCoordinatesTest {
    @Test fun fillLandscapeCropsHeightButKeepsRemoteTapAccurate() {
        val g = ScreenCoordinates.geometry(1920, 1080, 2400, 1080, 1f, 0f, 0f,
            ScreenCoordinates.DisplayMode.COVER)!!
        assertEquals(2400f, g.imageWidth, 0.1f)
        assertEquals(-135f, g.top, 0.1f)
        assertEquals(Pair(960, 540), ScreenCoordinates.toDesktop(
            1920, 1080, 2400, 1080, 1f, 0f, 0f, 1200f, 540f,
            mode = ScreenCoordinates.DisplayMode.COVER))
        assertEquals(Pair(960, 108), ScreenCoordinates.toDesktop(
            1920, 1080, 2400, 1080, 1f, 0f, 0f, 1200f, 0f,
            mode = ScreenCoordinates.DisplayMode.COVER))
    }

    @Test fun fitShowsEntireDesktopAndRejectsLetterboxTouches() {
        val g = ScreenCoordinates.geometry(1920, 1080, 2400, 1080, 1f, 0f, 0f,
            ScreenCoordinates.DisplayMode.CONTAIN)!!
        assertEquals(240f, g.left, 0.1f)
        assertNull(ScreenCoordinates.toDesktop(
            1920, 1080, 2400, 1080, 1f, 0f, 0f, 40f, 540f,
            mode = ScreenCoordinates.DisplayMode.CONTAIN))
        assertEquals(Pair(960, 540), ScreenCoordinates.toDesktop(
            1920, 1080, 2400, 1080, 1f, 0f, 0f, 1200f, 540f,
            mode = ScreenCoordinates.DisplayMode.CONTAIN))
    }

    @Test fun panZoomAndInverseTransformStayConsistent() {
        val g = ScreenCoordinates.geometry(1920, 1080, 960, 540, 2f, -100f, 0f,
            ScreenCoordinates.DisplayMode.COVER)!!
        val desktopX = 1060f
        assertEquals(480f, g.viewX(desktopX), 0.1f)
        assertEquals(desktopX, g.desktopX(g.viewX(desktopX)), 0.1f)
        assertEquals(Pair(1060, 540), ScreenCoordinates.toDesktop(
            1920, 1080, 960, 540, 2f, -100f, 0f, 480f, 270f,
            mode = ScreenCoordinates.DisplayMode.COVER))
    }

    @Test fun portraitCoverHasNoLetterboxAndInvalidInputsStaySafe() {
        val g = ScreenCoordinates.geometry(1920, 1080, 400, 800, 1f, 0f, 0f,
            ScreenCoordinates.DisplayMode.COVER)!!
        assertTrue(g.imageWidth > 400f)
        assertEquals(800f, g.imageHeight, 0.1f)
        assertNotNull(ScreenCoordinates.toDesktop(
            1920, 1080, 400, 800, 1f, 0f, 0f, 2f, 400f,
            mode = ScreenCoordinates.DisplayMode.COVER))
        assertNull(ScreenCoordinates.geometry(1920, 0, 400, 800, 1f, 0f, 0f))
        assertNull(ScreenCoordinates.geometry(1920, 1080, 400, 800, Float.NaN, 0f, 0f))
    }
}
