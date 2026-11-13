package dev.suanniao

import org.json.JSONArray
import org.json.JSONObject

/**
 * 远程工作台布局（schema 3）：悬浮球 8 个槽位 + 语音条改字行 + 语音条「快捷发送」 + 键鼠条按键区 + 侧栏「我的按钮」，
 * 加上横屏、竖屏各自的默认位置（左上角，占屏幕的比例）和大小（宽度比例 w、缩放 k）。
 * 电脑说了算（pc/config/src/workbench.rs，规则一样）：这里解析电脑下发的，调整布局时生成草稿交给电脑校验。
 * 规则：语音、键鼠、精密鼠标固定在悬浮球（能换位置，不能拿掉）；动作和正式版按键整个布局里只放一处；
 * 基础键同一排不重复，不同排可以各有一个；快捷发送只放正式版按键（常用文本、热键），最多 24 个。
 */
internal class WorkbenchLayoutV3 private constructor(
    val ball: List<WorkbenchRef?>,
    val voiceRow: List<WorkbenchRef>,
    val quickText: List<WorkbenchRef>,
    val keymouse: List<WorkbenchRef>,
    val rail: List<WorkbenchRef>,
    private val portrait: Geometry,
    private val landscape: Geometry,
) {
    enum class Container(val title: String) {
        BALL("悬浮球"), VOICE("语音条"), QUICK("快捷发送"), KEYMOUSE("键鼠条"), RAIL("侧栏")
    }
    data class Slot(val container: Container, val index: Int)
    data class Tool(val x: Double, val y: Double, val w: Double, val k: Double)
    data class Geometry(val voice: Tool, val keymouse: Tool, val ballX: Double, val ballY: Double)

    fun geometry(landscape: Boolean): Geometry = if (landscape) this.landscape else portrait

    fun items(container: Container): List<WorkbenchRef?> = when (container) {
        Container.BALL -> ball
        Container.VOICE -> voiceRow
        Container.QUICK -> quickText
        Container.KEYMOUSE -> keymouse
        Container.RAIL -> rail
    }

    fun isFixed(slot: Slot): Boolean = slot.container == Container.BALL &&
        (ball.getOrNull(slot.index) as? WorkbenchRef.Fn)?.id in WorkbenchCatalog.FIXED_BALL

    /** 动作和正式版按键在哪（只会有一处）；基础键可能好几处，返回 null。 */
    fun find(ref: WorkbenchRef): Slot? {
        if (ref is WorkbenchRef.Key) return null
        for (c in Container.values()) {
            val i = items(c).indexOf(ref)
            if (i >= 0) return Slot(c, i)
        }
        return null
    }

    fun remove(slot: Slot): WorkbenchLayoutV3 {
        if (isFixed(slot) || slot.index !in items(slot.container).indices) return this
        return edit { ball, rows ->
            if (slot.container == Container.BALL) ball[slot.index] = null
            else rows.getValue(slot.container).removeAt(slot.index)
        }
    }

    /** 悬浮球里两个槽位互换（固定的也能换位置）。 */
    fun swapBall(a: Int, b: Int): WorkbenchLayoutV3 {
        if (a !in 0 until BALL_SLOTS || b !in 0 until BALL_SLOTS || a == b) return this
        return edit { ball, _ ->
            val t = ball[a]
            ball[a] = ball[b]
            ball[b] = t
        }
    }

    /** 同一排里挪位置（常用的挪到前面）；悬浮球里就是互换。 */
    fun moveWithin(container: Container, from: Int, to: Int): WorkbenchLayoutV3 {
        if (container == Container.BALL) return swapBall(from, to)
        val size = items(container).size
        if (from !in 0 until size || to !in 0 until size || from == to) return this
        return edit { _, rows ->
            val list = rows.getValue(container)
            list.add(to, list.removeAt(from))
        }
    }

    /**
     * 把 ref 放到 target（从按钮库挑、或者「移到…」）。动作和正式版按键是「搬」：原来那处空出来。
     * source：基础键从哪一排搬过来（从库里新加的基础键传 null）。
     * 悬浮球的目标槽位有东西：source 也在悬浮球就互换，否则原来那个拿掉（回到按钮库）。固定的槽位不能被换掉。
     * 违反规则（同排重复的基础键、超过 24 个……）就原样返回。
     */
    fun place(ref: WorkbenchRef, target: Slot, source: Slot? = find(ref)): WorkbenchLayoutV3 {
        if (ref is WorkbenchRef.Fn && ref.id in WorkbenchCatalog.FIXED_BALL) {
            return if (source?.container == Container.BALL && target.container == Container.BALL)
                swapBall(source.index, target.index) else this
        }
        if (isFixed(target)) return this
        val sameContainer = source != null && source.container == target.container
        if (target.container == Container.BALL) {
            if (target.index !in 0 until BALL_SLOTS) return this
        } else if (!sameContainer && items(target.container).size >= MAX_ROW) return this
        val from = source?.takeIf { items(it.container).getOrNull(it.index) == ref }
        val result = edit { ball, rows ->
            var insertAt = target.index
            if (from != null) {
                if (from.container == Container.BALL) ball[from.index] = null
                else {
                    rows.getValue(from.container).removeAt(from.index)
                    if (from.container == target.container && from.index < insertAt) insertAt--
                }
            }
            if (target.container == Container.BALL) {
                val occupant = ball[target.index]
                ball[target.index] = ref
                if (occupant != null && occupant != ref && from?.container == Container.BALL) ball[from.index] = occupant
            } else {
                val list = rows.getValue(target.container)
                list.add(insertAt.coerceIn(0, list.size), ref)
            }
        }
        return if (result.valid()) result else this
    }

    /** 指向 ids 里没有的正式版按键（电脑上删掉了）的有几处 */
    fun missingButtons(ids: Set<String>): Int =
        Container.values().sumOf { c -> items(c).count { it is WorkbenchRef.Button && it.id !in ids } }

    /** 去掉指向电脑上已经删掉的按键的引用：悬浮球那格变空，各排里拿掉。都还在就原样返回 */
    fun withoutMissing(ids: Set<String>): WorkbenchLayoutV3 {
        if (missingButtons(ids) == 0) return this
        fun gone(ref: WorkbenchRef?) = ref is WorkbenchRef.Button && ref.id !in ids
        return edit { ball, rows ->
            for (i in ball.indices) if (gone(ball[i])) ball[i] = null
            rows.values.forEach { row -> row.removeAll { gone(it) } }
        }
    }

    /**
     * 调整布局期间，电脑推来的布局（theirs）比进来时（base）多出来的动作、正式版按键（比如刚建好的快捷发送），
     * 草稿（this）里还没有的，补到同一排的最后；悬浮球放回原来那格，那格有东西就放第一个空格。放不下就算了。
     * 不补的话，点「完成」会把它们从布局里拿掉，电脑接着把没人用的工作台按键删掉（v3.2 K2）。基础键能重复，不补。
     */
    fun withAdditions(base: WorkbenchLayoutV3, theirs: WorkbenchLayoutV3): WorkbenchLayoutV3 {
        var next = this
        for (c in Container.values()) {
            theirs.items(c).forEachIndexed { i, ref ->
                if (ref == null || ref is WorkbenchRef.Key || base.find(ref) != null || next.find(ref) != null) return@forEachIndexed
                val target = if (c == Container.BALL) {
                    val slot = if (next.ball[i] == null) i else next.ball.indexOfFirst { it == null }
                    if (slot < 0) return@forEachIndexed
                    Slot(c, slot)
                } else Slot(c, next.items(c).size)
                next = next.place(ref, target, null)
            }
        }
        return next
    }

    fun withTool(landscape: Boolean, name: String, tool: Tool): WorkbenchLayoutV3 {
        val g = geometry(landscape)
        return withGeometry(landscape, if (name == "voice") g.copy(voice = tool) else g.copy(keymouse = tool))
    }

    fun withBall(landscape: Boolean, x: Double, y: Double): WorkbenchLayoutV3 =
        withGeometry(landscape, geometry(landscape).copy(ballX = x.coerceIn(0.0, 1.0), ballY = y.coerceIn(0.0, 1.0)))

    /** 快捷发送总是写上（空的也写），顺序跟电脑约定的一样：ball, voiceRow, quickText, keymouse, rail */
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 3)
        .put("slots", JSONObject()
            .put("ball", JSONArray().apply { ball.forEach { put(it?.token ?: JSONObject.NULL) } })
            .put("voiceRow", JSONArray(voiceRow.map { it.token }))
            .put("quickText", JSONArray(quickText.map { it.token }))
            .put("keymouse", JSONArray(keymouse.map { it.token }))
            .put("rail", JSONArray(rail.map { it.token })))
        .put("geometry", JSONObject()
            .put("portrait", geometryJson(portrait))
            .put("landscape", geometryJson(landscape)))

    /** 跟电脑回推的配置比对（由模型重新生成，字段顺序固定，不受电脑端 JSON 顺序影响）。 */
    fun signature(): String = toJson().toString()

    private fun withGeometry(landscape: Boolean, g: Geometry) = if (landscape)
        WorkbenchLayoutV3(ball, voiceRow, quickText, keymouse, rail, portrait, g)
    else WorkbenchLayoutV3(ball, voiceRow, quickText, keymouse, rail, g, this.landscape)

    private fun edit(block: (MutableList<WorkbenchRef?>, Map<Container, MutableList<WorkbenchRef>>) -> Unit): WorkbenchLayoutV3 {
        val b = ball.toMutableList()
        val rows = mapOf(
            Container.VOICE to voiceRow.toMutableList(),
            Container.QUICK to quickText.toMutableList(),
            Container.KEYMOUSE to keymouse.toMutableList(),
            Container.RAIL to rail.toMutableList(),
        )
        block(b, rows)
        return WorkbenchLayoutV3(b, rows.getValue(Container.VOICE), rows.getValue(Container.QUICK),
            rows.getValue(Container.KEYMOUSE), rows.getValue(Container.RAIL), portrait, landscape)
    }

    private fun valid(): Boolean = problem(ball, voiceRow, quickText, keymouse, rail) == null

    companion object {
        const val BALL_SLOTS = 8
        /** 一排（语音条、快捷发送、键鼠条、侧栏）最多放几个 */
        const val MAX_ROW = 24

        val DEFAULT_PORTRAIT = Geometry(Tool(0.02, 0.86, 0.96, 1.0), Tool(0.02, 0.27, 0.96, 1.0), 0.85, 0.72)
        val DEFAULT_LANDSCAPE = Geometry(Tool(0.10, 0.72, 0.36, 1.0), Tool(0.50, 0.60, 0.42, 1.0), 0.015, 0.08)

        /** 跟电脑端 normalize 一样的规则；合格返回 null，否则返回原因。 */
        fun problem(
            ball: List<WorkbenchRef?>, voice: List<WorkbenchRef>, quick: List<WorkbenchRef>,
            keymouse: List<WorkbenchRef>, rail: List<WorkbenchRef>,
        ): String? {
            if (ball.size != BALL_SLOTS) return "悬浮球必须是 8 个槽位"
            if (listOf(voice, quick, keymouse, rail).any { it.size > MAX_ROW }) return "一排最多 24 个按钮"
            for (id in WorkbenchCatalog.FIXED_BALL) {
                if (ball.none { it == WorkbenchRef.Fn(id) }) return "语音、键鼠、精密鼠标必须留在悬浮球里"
            }
            for (row in listOf(voice, keymouse, rail)) {
                if (row.any { it is WorkbenchRef.Fn && it.id in WorkbenchCatalog.FIXED_BALL }) return "这个按钮只能放在悬浮球里"
            }
            // 快捷发送点一下就是正式版按键的单击：动作、基础键放不进来
            if (quick.any { it !is WorkbenchRef.Button }) return "快捷发送里只能放电脑上的按键"
            val unique = HashSet<WorkbenchRef>()
            for (row in listOf(ball.filterNotNull(), voice, quick, keymouse, rail)) {
                val keys = HashSet<WorkbenchRef>()
                for (ref in row) {
                    val fresh = if (ref is WorkbenchRef.Key) keys.add(ref) else unique.add(ref)
                    if (!fresh) return "同一个按钮只能放一处"
                }
            }
            return null
        }

        /** 解析电脑下发的布局：结构不对返回 null；引用了已删掉的正式版按键就跳过那一个，其余照常显示。 */
        fun parse(raw: JSONObject?, officialIds: Set<String>): WorkbenchLayoutV3? {
            if (raw == null || raw.optInt("schemaVersion") != 3) return null
            val slots = raw.optJSONObject("slots") ?: return null
            fun ref(value: Any?): WorkbenchRef? {
                val r = WorkbenchRef.parse(value as? String) ?: return null
                return if (r is WorkbenchRef.Button && r.id !in officialIds) null else r
            }
            val ballRaw = slots.optJSONArray("ball") ?: return null
            if (ballRaw.length() != BALL_SLOTS) return null
            val ball = (0 until BALL_SLOTS).map { i -> if (ballRaw.isNull(i)) null else ref(ballRaw.opt(i)) }
            fun row(name: String): List<WorkbenchRef>? {
                val array = slots.optJSONArray(name) ?: return null
                return (0 until array.length()).mapNotNull { ref(array.opt(it)) }
            }
            val voice = row("voiceRow") ?: return null
            // 快捷发送是后来加的：电脑上的蒜鸟旧一点就没有这一排，当成空的
            val quick = if (slots.isNull("quickText")) emptyList<WorkbenchRef>() else row("quickText") ?: return null
            val keymouse = row("keymouse") ?: return null
            val rail = row("rail") ?: return null
            if (problem(ball, voice, quick, keymouse, rail) != null) return null
            val geometry = raw.optJSONObject("geometry") ?: return null
            val portrait = parseGeometry(geometry.optJSONObject("portrait")) ?: return null
            val landscape = parseGeometry(geometry.optJSONObject("landscape")) ?: return null
            return WorkbenchLayoutV3(ball, voice, quick, keymouse, rail, portrait, landscape)
        }

        /**
         * 电脑还没有新格式时先这样显示（不能调整布局，也不保存）：只有动作和基础键。
         * 悬浮球第 8 格是「发图片」，跟电脑给新配置生成的默认布局一样（第 6、7 格电脑上是 Alt+F4、Alt+E）。
         */
        fun fallback(): WorkbenchLayoutV3 = WorkbenchLayoutV3(
            listOf("voice", "keymouse", "pointer", "taskSwitch", "captureWechat").map { WorkbenchRef.Fn(it) } +
                listOf(null, null, WorkbenchRef.Fn("sendFile")),
            listOf("backspace", "space", "left", "right", "up", "down", "capslock", "enter").map { WorkbenchRef.Key(it) },
            emptyList(),
            listOf("backspace", "delete", "left", "right", "enter", "esc").map { WorkbenchRef.Key(it) } + WorkbenchRef.Fn("captureSystem"),
            emptyList(),
            DEFAULT_PORTRAIT,
            DEFAULT_LANDSCAPE,
        )

        private fun parseGeometry(o: JSONObject?): Geometry? {
            o ?: return null
            fun tool(name: String): Tool? {
                val t = o.optJSONObject(name) ?: return null
                val x = unit(t, "x") ?: return null
                val y = unit(t, "y") ?: return null
                val w = num(t, "w")?.takeIf { it in 0.2..1.0 } ?: return null
                val k = num(t, "k")?.takeIf { it in 0.6..1.8 } ?: return null
                return Tool(x, y, w, k)
            }
            val b = o.optJSONObject("ball") ?: return null
            return Geometry(tool("voice") ?: return null, tool("keymouse") ?: return null,
                unit(b, "x") ?: return null, unit(b, "y") ?: return null)
        }

        private fun num(o: JSONObject, key: String): Double? = (o.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        private fun unit(o: JSONObject, key: String): Double? = num(o, key)?.takeIf { it in 0.0..1.0 }

        private fun geometryJson(g: Geometry) = JSONObject()
            .put("voice", toolJson(g.voice))
            .put("keymouse", toolJson(g.keymouse))
            .put("ball", JSONObject().put("x", g.ballX).put("y", g.ballY))

        private fun toolJson(t: Tool) = JSONObject().put("x", t.x).put("y", t.y).put("w", t.w).put("k", t.k)
    }
}
