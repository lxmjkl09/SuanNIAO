package dev.suanniao

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 悬浮球那一圈按钮的位置。spots 给的角度是相对「这一圈朝哪开」的方向（place 算出来的 base）。
 * 5 个以内排成一道弧并均匀分开；6 到 8 个时外圈 5 个、内圈放剩下的。按钮少的人不会看到一圈空洞。
 * 球松手吸到最近的一条边（snap）；打开时这一圈朝屏幕里面开，整圈都在屏幕里（place，v3.3 U3）。
 */
internal object BallRingLayout {
    data class Spot(val angleDeg: Float, val radiusDp: Int)

    /**
     * 一圈怎么摆：baseDeg 是朝哪个方向开（度，0 = 右，90 = 下，跟屏幕坐标一样 y 往下）；
     * sign 让第一个按钮落在弧的左上那头（「语音」还是在最上面或最左边，跟以前一样）；
     * (cx, cy) 是圈的中心，打开时球待在这里（dp）
     */
    data class Placement(val baseDeg: Float, val sign: Int, val cx: Float, val cy: Float) {
        fun angleOf(spot: Spot) = baseDeg + sign * spot.angleDeg
    }

    private const val OUTER_DP = 135
    private const val INNER_DP = 73
    private val OUTER = floatArrayOf(-74f, -37f, 0f, 37f, 74f)
    /** 圈里一个按钮的一半大小（按钮 58dp） */
    const val HALF_DP = 29f

    fun spots(count: Int): List<Spot> {
        val n = count.coerceIn(0, 8)
        if (n == 0) return emptyList()
        if (n == 1) return listOf(Spot(0f, OUTER_DP))
        if (n <= 5) {
            val spread = when (n) { 2, 3 -> 37f; 4 -> 55f; else -> 74f }
            return (0 until n).map { i -> Spot(-spread + 2 * spread * i / (n - 1), OUTER_DP) }
        }
        val inner = when (n - 5) {
            1 -> floatArrayOf(0f)
            2 -> floatArrayOf(-47f, 47f)
            else -> floatArrayOf(-67f, 0f, 67f)
        }
        return OUTER.map { Spot(it, OUTER_DP) } + inner.map { Spot(it, INNER_DP) }
    }

    /**
     * 松手后吸到最近的一条边（左、右、上、下都行），离边 margin。x、y 是球的左上角，size 是球多大，w、h 是屏幕；
     * 返回吸好以后的左上角。贴着那条边，另一个方向不动（跑出屏幕的话拉回来）。一样近时按左、右、上、下的顺序
     */
    fun snap(x: Float, y: Float, w: Float, h: Float, size: Float, margin: Float): Pair<Float, Float> {
        val maxX = (w - size - margin).coerceAtLeast(margin)
        val maxY = (h - size - margin).coerceAtLeast(margin)
        val left = x
        val right = w - size - x
        val top = y
        val bottom = h - size - y
        val nearest = minOf(minOf(left, right), minOf(top, bottom))
        val keepX = x.coerceIn(margin, maxX)
        val keepY = y.coerceIn(margin, maxY)
        return when (nearest) {
            left -> margin to keepY
            right -> maxX to keepY
            top -> keepX to margin
            else -> keepX to maxY
        }
    }

    /**
     * 打开那一圈摆在哪（v3.3 U3）：在 8 个方向（上下左右和四个斜角）里挑一个，整圈都放得进屏幕、圈的中心离球挪得最少；
     * 挪得一样少就挑最朝屏幕中间的。球在一条边的中间时正对着屏幕里开、一点不用挪；在角上时斜着朝屏幕中间开，挪得最少。
     * 打开时球待在圈中心，按钮离中心至少 73dp，球（半径 25dp）和按钮（半径 29dp）碰不到，所以一圈盖不住球。
     * bx、by：球心；w、h：屏幕；margin：按钮离屏幕边至少多远；都是 dp
     */
    fun place(count: Int, bx: Float, by: Float, w: Float, h: Float, margin: Float = 4f): Placement {
        val spots = spots(count)
        val tx = w / 2 - bx
        val ty = h / 2 - by
        val toward = if (abs(tx) < 1f && abs(ty) < 1f) 0.0 else atan2(ty.toDouble(), tx.toDouble())
        var best = Placement(0f, 1, bx, by)
        var bestCost = Float.MAX_VALUE
        var bestAlign = -2.0
        for (k in 0 until 8) {
            val base = k * 45f
            val sign = startSign(base)
            // 一圈按钮中心相对圈中心最远到哪（球自己在中心，也算进去）
            var minX = 0f
            var maxX = 0f
            var minY = 0f
            var maxY = 0f
            for (s in spots) {
                val a = Math.toRadians((base + sign * s.angleDeg).toDouble())
                val dx = (s.radiusDp * cos(a)).toFloat()
                val dy = (s.radiusDp * sin(a)).toFloat()
                minX = min(minX, dx)
                maxX = max(maxX, dx)
                minY = min(minY, dy)
                maxY = max(maxY, dy)
            }
            val loX = margin + HALF_DP - minX
            val hiX = w - margin - HALF_DP - maxX
            val loY = margin + HALF_DP - minY
            val hiY = h - margin - HALF_DP - maxY
            val fits = loX <= hiX && loY <= hiY
            // 屏幕太小（分屏）哪个方向都放不下：放在能放的范围中间，尽量少出屏幕
            val cx = if (loX <= hiX) bx.coerceIn(loX, hiX) else (loX + hiX) / 2
            val cy = if (loY <= hiY) by.coerceIn(loY, hiY) else (loY + hiY) / 2
            val cost = hypot(cx - bx, cy - by) + if (fits) 0f else 100_000f
            val align = cos(Math.toRadians(base.toDouble()) - toward)
            if (cost < bestCost - 0.5f || (cost <= bestCost + 0.5f && align > bestAlign)) {
                best = Placement(base, sign, cx, cy)
                bestCost = cost
                bestAlign = align
            }
        }
        return best
    }

    /**
     * 第一个按钮放在弧的哪一头：放在左上那头（x + y 小的那头；一样就放上面那头）。
     * 球在左边、右边时「语音」在最上面，在上边、下边时在最左边，跟以前一样好找
     */
    private fun startSign(base: Float): Int {
        val a = Math.toRadians((base - 74f).toDouble())
        val b = Math.toRadians((base + 74f).toDouble())
        val sa = cos(a) + sin(a)
        val sb = cos(b) + sin(b)
        return when {
            sa < sb - 1e-6 -> 1
            sb < sa - 1e-6 -> -1
            sin(a) <= sin(b) -> 1
            else -> -1
        }
    }
}
