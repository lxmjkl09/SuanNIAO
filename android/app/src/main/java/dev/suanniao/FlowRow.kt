package dev.suanniao

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 按固定列数自动换行的一排按钮：放不下就换到下一行，按钮全都看得见（取代原来的左右滑）。
 * 每格一样宽、一样高。父容器必须给确定的宽度。indexAt 给调整布局时「拖到哪一格」用。
 */
internal class FlowRow(context: Context, var columns: Int, private val cellHeight: Int, private val gap: Int) : ViewGroup(context) {
    private fun cellWidth(total: Int) = ((total - gap * (columns - 1)) / columns).coerceAtLeast(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cellW = cellWidth(width)
        var count = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY))
            count++
        }
        val rows = (count + columns - 1) / columns
        setMeasuredDimension(width, if (rows == 0) 0 else rows * cellHeight + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cellW = cellWidth(r - l)
        var n = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val x = (n % columns) * (cellW + gap)
            val y = (n / columns) * (cellHeight + gap)
            child.layout(x, y, x + cellW, y + cellHeight)
            n++
        }
    }

    /** 本控件里的坐标落在第几格（按显示顺序）；落在最后一格后面返回格子总数。 */
    fun indexAt(x: Float, y: Float): Int {
        val cellW = cellWidth(width)
        if (cellW <= 0) return 0
        val col = (x / (cellW + gap)).toInt().coerceIn(0, columns - 1)
        val row = (y / (cellHeight + gap)).toInt().coerceAtLeast(0)
        val count = (0 until childCount).count { getChildAt(it).visibility != View.GONE }
        return (row * columns + col).coerceIn(0, count)
    }
}
