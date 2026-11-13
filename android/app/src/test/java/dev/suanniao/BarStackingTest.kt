package dev.suanniao

import dev.suanniao.BarStacking.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两条都展开又叠在一起时怎么让开（v3.3 U2、U8）：横屏 873×393，两条隔 6，离屏幕边至少 4 */
class BarStackingTest {
    private val w = 873f
    private val h = 393f

    private fun stack(fixed: Box, moving: Box, sideBySide: Boolean = false) =
        BarStacking.resolve(fixed, moving, w, h, 6f, 4f, sideBySide)

    private fun onScreen(b: Box) = b.x >= 4f && b.y >= 4f && b.right <= w - 4f && b.bottom <= h - 4f

    @Test fun barsThatDoNotOverlapStayWhereTheyAre() {
        val moving = Box(400f, 300f, 300f, 80f)
        assertSame(moving, stack(Box(10f, 300f, 300f, 80f), moving))
    }

    @Test fun theUpperBarGoesRightAboveTheOther() {
        val fixed = Box(10f, 250f, 320f, 100f)
        val moved = stack(fixed, Box(10f, 200f, 320f, 100f))
        assertEquals(Box(10f, 144f, 320f, 100f), moved)
        assertFalse(moved.overlaps(fixed))
        assertEquals(6f, fixed.y - moved.bottom, 0.001f)
    }

    @Test fun noRoomAboveGoesRightBelow() {
        val fixed = Box(10f, 50f, 320f, 100f)
        val moved = stack(fixed, Box(10f, 20f, 320f, 100f))
        assertEquals(Box(10f, 156f, 320f, 100f), moved)
        assertFalse(moved.overlaps(fixed))
    }

    @Test fun noRoomEitherWayOverlapsAsLittleAsPossibleOnScreen() {
        val fixed = Box(10f, 100f, 320f, 200f)
        val moved = stack(fixed, Box(10f, 90f, 320f, 150f))
        // 贴屏幕上边重叠 54 高，贴下边重叠 61 高：贴上边
        assertEquals(Box(10f, 4f, 320f, 150f), moved)
        assertTrue(onScreen(moved))
    }

    @Test fun verticalBarsSitSideBySide() {
        // 两条竖条都靠左边：挪的那条贴到右边，高低不变
        val fixed = Box(4f, 100f, 70f, 200f)
        val moved = stack(fixed, Box(4f, 60f, 70f, 200f), sideBySide = true)
        assertEquals(Box(80f, 60f, 70f, 200f), moved)
        assertFalse(moved.overlaps(fixed))
    }

    @Test fun verticalBarsGoToTheNearSideFirstAndTheOtherSideWhenNoRoom() {
        val nearLeft = stack(Box(400f, 100f, 70f, 200f), Box(380f, 100f, 70f, 200f), sideBySide = true)
        assertEquals(324f, nearLeft.x, 0.001f)
        // 靠着屏幕右边：右边放不下，贴到左边
        val atRightEdge = stack(Box(799f, 100f, 70f, 200f), Box(799f, 50f, 70f, 200f), sideBySide = true)
        assertEquals(723f, atRightEdge.x, 0.001f)
        assertTrue(onScreen(atRightEdge))
    }
}
