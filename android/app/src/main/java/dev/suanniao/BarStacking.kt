package dev.suanniao

import kotlin.math.max
import kotlin.math.min

/**
 * 语音条、键鼠条都展开、又叠在一起时，把一条挪开（v3.3 U2）。这里只算位置，不碰视图，好测。
 * - 横条：挪到另一条正上方（隔 gap）；上面放不下就放正下方；再放不下就贴屏幕上边或下边，挑重叠少的。
 * - 竖条（v3.3 U8）：挪到另一条的左边或右边（先试离得近的那边）；都放不下同样挑重叠少的。
 * 只是显示时让开：ScreenFocusControls 每次都从存着的位置重新算，存着的位置不变。
 */
internal object BarStacking {
    /** 一条在屏幕上占的地方（像素，左上角 + 宽高，已经算上缩放） */
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right get() = x + w
        val bottom get() = y + h

        fun overlaps(o: Box) = x < o.right && o.x < right && y < o.bottom && o.y < bottom

        fun overlapArea(o: Box) = max(0f, min(right, o.right) - max(x, o.x)) * max(0f, min(bottom, o.bottom) - max(y, o.y))

        fun at(nx: Float, ny: Float) = copy(x = nx, y = ny)
    }

    /**
     * fixed 不动，moving 挪开，返回 moving 该显示在哪；两条不重叠就原样返回。
     * screenW、screenH：屏幕大小；gap：两条之间留多宽；margin：离屏幕边至少多远；sideBySide：竖条，左右错开
     */
    fun resolve(fixed: Box, moving: Box, screenW: Float, screenH: Float, gap: Float, margin: Float, sideBySide: Boolean): Box {
        if (!fixed.overlaps(moving)) return moving
        if (sideBySide) {
            val left = moving.at(fixed.x - gap - moving.w, moving.y)
            val right = moving.at(fixed.right + gap, moving.y)
            val fitsLeft = left.x >= margin
            val fitsRight = right.right <= screenW - margin
            // 先试挪过去近的那边：本来就在左半边的往左贴，在右半边的往右贴
            val preferLeft = moving.x + moving.w / 2 < fixed.x + fixed.w / 2
            return when {
                preferLeft && fitsLeft -> left
                fitsRight -> right
                fitsLeft -> left
                else -> leastOverlap(fixed, listOf(
                    moving.at(margin, moving.y),
                    moving.at(maxOf(margin, screenW - margin - moving.w), moving.y),
                ))
            }
        }
        val above = moving.at(moving.x, fixed.y - gap - moving.h)
        if (above.y >= margin) return above
        val below = moving.at(moving.x, fixed.bottom + gap)
        if (below.bottom <= screenH - margin) return below
        return leastOverlap(fixed, listOf(
            moving.at(moving.x, margin),
            moving.at(moving.x, maxOf(margin, screenH - margin - moving.h)),
        ))
    }

    /** 几个都在屏幕里的位置，挑跟 fixed 重叠最少的（一样就要前面那个） */
    private fun leastOverlap(fixed: Box, options: List<Box>): Box =
        options.minByOrNull { it.overlapArea(fixed) } ?: options.first()
}
