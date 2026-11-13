package dev.suanniao

import kotlin.math.max
import kotlin.math.min

/**
 * 唯一的屏幕矩阵：渲染裁剪、准星与远程触控必须共用这组参数。
 * COVER 无黑边但裁剪超出视口部分；CONTAIN 显示完整桌面但可能留边。
 */
object ScreenCoordinates {
    enum class DisplayMode { COVER, CONTAIN }

    data class Geometry(
        val scale: Float, val left: Float, val top: Float,
        val imageWidth: Float, val imageHeight: Float,
    ) {
        fun desktopX(viewX: Float): Float = (viewX - left) / scale
        fun desktopY(viewY: Float): Float = (viewY - top) / scale
        fun viewX(desktopX: Float): Float = left + desktopX * scale
        fun viewY(desktopY: Float): Float = top + desktopY * scale
    }

    fun geometry(
        sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int,
        zoom: Float, panX: Float, panY: Float,
        mode: DisplayMode = DisplayMode.CONTAIN,
    ): Geometry? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0 ||
            !zoom.isFinite() || zoom <= 0f || !panX.isFinite() || !panY.isFinite()
        ) return null
        val widthRatio = viewWidth.toFloat() / sourceWidth
        val heightRatio = viewHeight.toFloat() / sourceHeight
        val scale = (if (mode == DisplayMode.COVER) max(widthRatio, heightRatio)
            else min(widthRatio, heightRatio)) * zoom
        if (!scale.isFinite() || scale <= 0f) return null
        val w = sourceWidth * scale
        val h = sourceHeight * scale
        if (!w.isFinite() || !h.isFinite()) return null
        return Geometry(scale, (viewWidth - w) / 2f + panX, (viewHeight - h) / 2f + panY, w, h)
    }

    fun toDesktop(
        sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int,
        zoom: Float, panX: Float, panY: Float, x: Float, y: Float,
        clamp: Boolean = false, mode: DisplayMode = DisplayMode.CONTAIN,
    ): Pair<Int, Int>? {
        if (!x.isFinite() || !y.isFinite()) return null
        val g = geometry(sourceWidth, sourceHeight, viewWidth, viewHeight, zoom, panX, panY, mode)
            ?: return null
        val sx = g.desktopX(x)
        val sy = g.desktopY(y)
        if (!sx.isFinite() || !sy.isFinite()) return null
        if (!clamp && (sx < 0f || sx >= sourceWidth || sy < 0f || sy >= sourceHeight)) return null
        return Pair(sx.toInt().coerceIn(0, sourceWidth - 1), sy.toInt().coerceIn(0, sourceHeight - 1))
    }
}
