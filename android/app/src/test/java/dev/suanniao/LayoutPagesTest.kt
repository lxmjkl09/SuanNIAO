package dev.suanniao

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPagesTest {
    /** workbench 里的 id 是工作台专用页（电脑发来的带 "workbench": true） */
    private fun pages(vararg ids: String, workbench: Set<String> = setOf("remote-shortcuts")) = JSONArray().apply {
        ids.forEach { id -> put(JSONObject().put("id", id).put("name", id).apply { if (id in workbench) put("workbench", true) }) }
    }

    @Test fun workbenchPagesAreHiddenWhereverTheySit() {
        assertEquals(listOf(0, 2), LayoutPages.visible(pages("main", "remote-shortcuts", "p2")))
        assertEquals(listOf(0, 1), LayoutPages.visible(pages("main", "p2", "remote-shortcuts")))
        assertEquals(listOf(1), LayoutPages.visible(pages("remote-shortcuts", "main")))
        assertEquals(emptyList<Int>(), LayoutPages.visible(pages("remote-shortcuts")))
        assertEquals(emptyList<Int>(), LayoutPages.visible(null))
        assertTrue(LayoutPages.isWorkbench(JSONObject().put("workbench", true)))
        assertFalse(LayoutPages.isWorkbench(JSONObject().put("workbench", false)))
        assertFalse(LayoutPages.isWorkbench(JSONObject()))
    }

    @Test fun savedPageIsFoundByIdEvenWhenAHiddenPageMovesIn() {
        val before = pages("main", "p2")
        assertEquals(1, LayoutPages.resolve(before, LayoutPages.visible(before), "p2", 1))
        // 专用页插到中间：还是 p2，而且是看得见的第 2 页
        val after = pages("main", "remote-shortcuts", "p2")
        assertEquals(1, LayoutPages.resolve(after, LayoutPages.visible(after), "p2", 1))
        val first = pages("remote-shortcuts", "main", "p2")
        assertEquals(0, LayoutPages.resolve(first, LayoutPages.visible(first), "main", 0))
    }

    @Test fun deletedPageFallsBackToTheSavedPositionWithinRange() {
        val p = pages("main", "remote-shortcuts", "p2")
        val shown = LayoutPages.visible(p)
        assertEquals(1, LayoutPages.resolve(p, shown, "gone", 1))
        assertEquals(1, LayoutPages.resolve(p, shown, "gone", 7))
        assertEquals(0, LayoutPages.resolve(p, shown, "gone", -3))
        assertEquals(0, LayoutPages.resolve(pages("remote-shortcuts"), emptyList(), "main", 2))
    }

    @Test fun legacyIndexCountedTheWorkbenchPage() {
        // 旧版只记了下标（算上专用页）：p2 原来是第 3 页，现在是看得见的第 2 页
        val p = pages("main", "remote-shortcuts", "p2")
        val shown = LayoutPages.visible(p)
        assertEquals(1, LayoutPages.resolve(p, shown, null, 2))
        // 原来看的就是专用页：退到它前面那页
        assertEquals(0, LayoutPages.resolve(p, shown, null, 1))
        assertEquals(0, LayoutPages.resolve(p, shown, null, 0))
        assertEquals(1, LayoutPages.resolve(p, shown, null, 9))
        val hiddenFirst = pages("remote-shortcuts", "main")
        assertEquals(0, LayoutPages.resolve(hiddenFirst, LayoutPages.visible(hiddenFirst), null, 0))
    }
}
