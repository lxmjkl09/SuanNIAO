package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class BallRingLayoutTest {
    private fun angles(n: Int) = BallRingLayout.spots(n).map { Math.round(it.angleDeg.toDouble()) }

    @Test fun fewItemsSpreadEvenlyOnOneArc() {
        assertEquals(listOf(0L), angles(1))
        assertEquals(listOf(-37L, 37L), angles(2))
        assertEquals(listOf(-37L, 0L, 37L), angles(3))
        assertEquals(listOf(-55L, -18L, 18L, 55L), angles(4))
        assertEquals(listOf(-74L, -37L, 0L, 37L, 74L), angles(5))
        assertEquals(setOf(135), BallRingLayout.spots(5).map { it.radiusDp }.toSet())
    }

    @Test fun sixToEightUseAnInnerRing() {
        assertEquals(listOf(-74L, -37L, 0L, 37L, 74L, -67L, 0L, 67L), angles(8))
        assertEquals(listOf(135, 135, 135, 135, 135, 73, 73), BallRingLayout.spots(7).map { it.radiusDp })
        assertEquals(0, BallRingLayout.spots(0).size)
        assertEquals(8, BallRingLayout.spots(12).size)
    }

    // ───────── 松手吸边（v3.3 U3）：横屏 873×393dp，球 50dp，离边 8dp ─────────

    private fun snap(x: Float, y: Float) = BallRingLayout.snap(x, y, 873f, 393f, 50f, 8f)

    @Test fun releasedBallSnapsToTheNearestOfFourEdges() {
        assertEquals(8f to 150f, snap(3f, 150f))
        assertEquals(815f to 150f, snap(800f, 150f))
        assertEquals(400f to 8f, snap(400f, 10f))
        assertEquals(400f to 335f, snap(400f, 340f))
        // 角上一样近：先贴左边，另一个方向拉回离边 8dp
        assertEquals(8f to 8f, snap(3f, 3f))
    }

    // ───────── 打开那一圈朝哪开（v3.3 U3） ─────────

    private val land = 873f to 393f
    private val port = 393f to 873f

    private fun place(n: Int, bx: Float, by: Float, screen: Pair<Float, Float>) =
        BallRingLayout.place(n, bx, by, screen.first, screen.second)

    @Test fun ballInTheMiddleOfAnEdgeOpensStraightInWithoutMoving() {
        val left = place(8, 33f, 196f, land)
        assertEquals(0f, left.baseDeg)
        assertEquals(33f to 196f, left.cx to left.cy)
        assertEquals(180f, place(8, 840f, 196f, land).baseDeg)
        val top = place(8, 196f, 33f, port)
        assertEquals(90f, top.baseDeg)
        assertEquals(196f to 33f, top.cx to top.cy)
        assertEquals(270f, place(8, 196f, 840f, port).baseDeg)
    }

    @Test fun firstButtonStaysAtTheTopOnLeftAndRightEdges() {
        // 跟以前一样「语音」（第一个）在最上面
        for (bx in listOf(33f, 840f)) {
            val p = place(8, bx, 196f, land)
            val first = Math.toRadians(p.angleOf(BallRingLayout.spots(8)[0]).toDouble())
            assertTrue(sin(first) < -0.9)
        }
        // 上边、下边：第一个在最左边
        for (by in listOf(33f, 840f)) {
            val p = place(8, 196f, by, port)
            val first = Math.toRadians(p.angleOf(BallRingLayout.spots(8)[0]).toDouble())
            assertTrue(cos(first) < -0.9)
        }
    }

    @Test fun ballInACornerOpensDiagonallyTowardTheMiddle() {
        val p = place(8, 33f, 33f, land)
        assertEquals(45f, p.baseDeg)
        // 圈中心往屏幕里挪（球打开时跟着挪），斜着挪比贴着一条边挪得少
        assertTrue(p.cx > 33f && p.cy > 33f)
        assertTrue(hypot(p.cx - 33f, p.cy - 33f) < 100f)
    }

    @Test fun ringNeverLeavesTheScreenOrCoversTheBall() {
        for ((w, h) in listOf(land, port)) {
            // 吸在四条边上的各个位置（离边 8dp，球心离边 33dp），包括四个角
            val xs = (0..20).map { 33f + (w - 66f) * it / 20 }
            val ys = (0..20).map { 33f + (h - 66f) * it / 20 }
            val balls = xs.map { it to 33f } + xs.map { it to h - 33f } + ys.map { 33f to it } + ys.map { w - 33f to it }
            for ((bx, by) in balls) for (n in 1..8) {
                val p = place(n, bx, by, w to h)
                // 球待在圈中心，也得在屏幕里
                assertTrue(p.cx - 25f >= 0f && p.cx + 25f <= w && p.cy - 25f >= 0f && p.cy + 25f <= h)
                for (spot in BallRingLayout.spots(n)) {
                    val a = Math.toRadians(p.angleOf(spot).toDouble())
                    val x = p.cx + spot.radiusDp * cos(a).toFloat()
                    val y = p.cy + spot.radiusDp * sin(a).toFloat()
                    val half = BallRingLayout.HALF_DP
                    assertTrue("n=$n ball=($bx,$by) item=($x,$y)", x - half >= 0f && x + half <= w && y - half >= 0f && y + half <= h)
                    // 按钮（半径 29）和球（半径 25）碰不到
                    assertTrue(hypot(x - p.cx, y - p.cy) >= 25f + half)
                }
            }
        }
    }
}
