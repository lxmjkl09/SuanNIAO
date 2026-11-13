package dev.suanniao

import dev.suanniao.GridLayoutView.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

/** 手机「调整布局」里拖电脑屏幕模块的大小：高按画面比例跟着宽走（K6） */
class ScreenModuleRatioTest {
    private val r = LayoutEditor.RIGHT
    private val l = LayoutEditor.LEFT
    private val t = LayoutEditor.TOP
    private val b = LayoutEditor.BOTTOM

    private fun fit(start: Cell, dragged: Cell, edges: Int, k: Double = 0.5, step: Double? = 0.25,
                    others: List<Cell> = emptyList()) =
        LayoutEditor.fitRatio(start, dragged, edges, k, step, 0.5, others, 4.0, 8.0)

    private fun assertCell(expected: Cell, actual: Cell) =
        assertEquals(expected.toString(), actual.tidy().toString())

    @Test fun ratioFollowsTheGridAndDesktopShape() {
        // 4 列 8 行、格子宽:高 = 1:1、桌面 16:9 → 高 = 宽 × 0.5625，4 格宽时取到 2.25
        val k = 1.0 * 9 / 16
        assertCell(Cell(0.0, 0.0, 4.0, 2.25), fit(Cell(0.0, 0.0, 4.0, 4.0), Cell(0.0, 0.0, 4.0, 4.0), r or b, k))
    }

    @Test fun widthDragSetsTheHeight() {
        assertCell(Cell(0.0, 0.0, 3.0, 1.5), fit(Cell(0.0, 0.0, 4.0, 4.0), Cell(0.0, 0.0, 3.0, 4.0), r))
        // 拖左边：右边不动
        assertCell(Cell(0.0, 0.0, 4.0, 2.0), fit(Cell(1.0, 0.0, 3.0, 2.0), Cell(0.0, 0.0, 4.0, 2.0), l))
        // 拖右上角：下边不动
        assertCell(Cell(0.0, 6.0, 4.0, 2.0), fit(Cell(0.0, 7.0, 2.0, 1.0), Cell(0.0, 5.0, 4.0, 3.0), r or t))
    }

    @Test fun heightOnlyDragSetsTheWidth() {
        assertCell(Cell(0.0, 0.0, 2.0, 1.0), fit(Cell(0.0, 0.0, 4.0, 4.0), Cell(0.0, 0.0, 4.0, 1.0), b))
        // 拖上边：下边不动；宽最多到网格右边
        assertCell(Cell(0.0, 6.0, 4.0, 2.0), fit(Cell(0.0, 4.0, 4.0, 4.0), Cell(0.0, 6.0, 4.0, 2.0), t))
        assertCell(Cell(1.0, 0.0, 3.0, 1.5), fit(Cell(1.0, 0.0, 1.0, 0.5), Cell(1.0, 0.0, 1.0, 6.0), b))
        // 往小拖到底：宽不小于最小尺寸
        assertCell(Cell(0.0, 0.0, 1.0, 0.5), fit(Cell(0.0, 0.0, 2.0, 1.0), Cell(0.0, 0.0, 2.0, 0.5), b))
    }

    @Test fun heightSnapsToTheStepAndNeverBelowTheMinimum() {
        // 3 × 0.6 = 1.8 → 对齐到 1.75
        assertCell(Cell(0.0, 0.0, 3.0, 1.75), fit(Cell(0.0, 0.0, 4.0, 4.0), Cell(0.0, 0.0, 3.0, 4.0), r, k = 0.6))
        // 自由模式：取到 0.01 格
        assertCell(Cell(0.0, 0.0, 3.33, 1.87), fit(Cell(0.0, 0.0, 4.0, 4.0), Cell(0.0, 0.0, 3.33, 4.0), r, k = 0.5625, step = null))
        // 很扁的画面：高至少 0.5 格
        assertCell(Cell(0.0, 0.0, 2.0, 0.5), fit(Cell(0.0, 0.0, 4.0, 4.0), Cell(0.0, 0.0, 2.0, 4.0), r, k = 0.1))
    }

    @Test fun shrinksTheWidthUntilItFits() {
        // 下面紧挨着别的模块：宽 4 时高 2 会压上去，缩到 3 格宽、1.5 格高正好放下
        val below = listOf(Cell(0.0, 1.5, 4.0, 1.0))
        assertCell(Cell(0.0, 0.0, 3.0, 1.5), fit(Cell(0.0, 0.0, 2.0, 1.0), Cell(0.0, 0.0, 4.0, 1.0), r, others = below))
        // 贴着网格底边：放不下更大的，就保持原样
        assertCell(Cell(0.0, 7.0, 2.0, 1.0), fit(Cell(0.0, 7.0, 2.0, 1.0), Cell(0.0, 7.0, 4.0, 1.0), r))
        assertCell(Cell(0.0, 7.5, 1.0, 0.5), fit(Cell(0.0, 7.5, 1.0, 0.5), Cell(0.0, 7.5, 2.0, 0.5), r))
    }
}
