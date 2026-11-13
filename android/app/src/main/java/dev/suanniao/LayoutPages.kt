package dev.suanniao

import org.json.JSONArray
import org.json.JSONObject

/**
 * 普通按键页里看得见哪些页（工作台 v3.2 K1）。
 * 工作台的热键、快捷发送存在一页专用页上，电脑发来时那一页带 "workbench": true。
 * 以前它像普通页一样显示，用户当成多出来的一页删掉，悬浮球、键鼠条上的热键就跟着没了。
 * 所以手机的页面标签、换页、「调整布局」都跳过它；工作台照样从所有页收集按键（ScreenFocusControls.updateConfig）。
 * 手机记的「看到第几页」是看得见的页里的第几个，另外记上那页的 id：专用页夹在中间或排在后面，页码都对得上。
 */
internal object LayoutPages {
    fun isWorkbench(page: JSONObject?): Boolean = page?.optBoolean("workbench") == true

    /** 看得见的页在 pages 里的下标，按原来的顺序。只剩专用页时是空的（电脑「布局」里也不显示它，就当没有页） */
    fun visible(pages: JSONArray?): List<Int> =
        (0 until (pages?.length() ?: 0)).filter { !isWorkbench(pages!!.optJSONObject(it)) }

    /**
     * 上次看的那页现在是看得见的页里的第几个。先按 id 找；找不到（电脑上删了这页）就按记着的位置，超出就是最后一页。
     * savedId == null：刚从旧版升级上来，savedIndex 是算上专用页的下标，换算一下（正好是专用页就退到它前面那页）
     */
    fun resolve(pages: JSONArray, visible: List<Int>, savedId: String?, savedIndex: Int): Int {
        if (visible.isEmpty()) return 0
        if (savedId != null) {
            val byId = visible.indexOfFirst { pages.optJSONObject(it)?.optString("id") == savedId }
            return if (byId >= 0) byId else savedIndex.coerceIn(0, visible.size - 1)
        }
        val legacy = savedIndex.coerceIn(0, pages.length() - 1)
        return visible.indexOfLast { it <= legacy }.coerceAtLeast(0)
    }
}
