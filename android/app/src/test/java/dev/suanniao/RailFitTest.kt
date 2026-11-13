package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏侧栏塞进画面右边的空白（v3.3 U1）。20:9 手机横屏 873×393dp 看 16:9 桌面：画面 698dp 宽，右边空白约 87dp。
 * 设置三组：5 个开关（多了「闲时半透明」「竖条」）、5 个、「返回主页」
 */
class RailFitTest {
    private val settings = listOf(5, 5, 1)

    /** 一列排下来面板有多高（跟 WorkbenchRail.setContent 排的一样） */
    private fun columnHeight(plan: RailFit.Plan, sections: List<Int>, mine: Boolean): Float {
        val breaks = sections.size - 1
        val separators = if (mine) (breaks - 1) * RailFit.DIVIDER + RailFit.CAPTION else breaks * RailFit.DIVIDER
        val gaps = sections.sumOf { it - 1 } * RailFit.GAP
        return 2 * RailFit.PAD + sections.sum() * plan.cellH + gaps + separators
    }

    @Test fun narrowColumnFitsTheBlankRightOfThePicture() {
        val plan = RailFit.plan(87f, 393f, fill = false, sections = settings, mine = false)
        assertTrue(plan.fit)
        assertEquals(1, plan.columns)
        // 按钮宽 = 空白 − 屏幕边、小竖条、缝、内边距
        assertEquals(87f - RailFit.SIDE, plan.cellW, 0.01f)
        assertTrue(plan.cellH in RailFit.MIN_CELL_H..RailFit.MAX_CELL_H)
        // 整列放得进屏幕高（上下各留一点）
        assertTrue(columnHeight(plan, settings, false) <= 393f - 2 * RailFit.EDGE + 0.01f)
        // 面板加小竖条正好在空白里，不盖画面
        assertTrue(plan.cellW + RailFit.SIDE <= 87f + 0.01f)
    }

    @Test fun buttonsAreAtMost72dpWideAnd40dpTall() {
        val plan = RailFit.plan(122f, 900f, fill = false, sections = settings, mine = false)
        assertTrue(plan.fit)
        assertEquals(72f, plan.cellW, 0.01f)
        assertEquals(40f, plan.cellH, 0.01f)
        assertEquals(11, plan.textSp)
    }

    @Test fun aFewOwnButtonsStillFit() {
        val sections = settings + 3
        val plan = RailFit.plan(87f, 393f, fill = false, sections = sections, mine = true)
        assertTrue(plan.fit)
        assertTrue(columnHeight(plan, sections, true) <= 393f - 2 * RailFit.EDGE + 0.01f)
    }

    @Test fun narrowBlankFillModeOrTooManyButtonsCoverThePictureEdgeInstead() {
        assertSame(RailFit.OVERLAY, RailFit.plan(50f, 393f, fill = false, sections = settings, mine = false))
        // 空白过了 56dp，可算出来按钮太窄，字放不下
        assertSame(RailFit.OVERLAY, RailFit.plan(60f, 393f, fill = false, sections = settings, mine = false))
        assertSame(RailFit.OVERLAY, RailFit.plan(87f, 393f, fill = true, sections = settings, mine = false))
        assertSame(RailFit.OVERLAY, RailFit.plan(87f, 393f, fill = false, sections = settings + 24, mine = true))
        assertFalse(RailFit.OVERLAY.fit)
        assertEquals(2, RailFit.OVERLAY.columns)
    }
}
