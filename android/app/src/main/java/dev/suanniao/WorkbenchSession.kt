package dev.suanniao

import android.content.SharedPreferences

/**
 * 手机自己记的工作台状态，不发电脑：每个方向里每条工具是展开、收起还是隐藏，展开区开没开
 * （语音条还记着展开的是「编辑▾」还是「快捷发送▾」那一排），
 * 平时拖到哪了（盖过电脑上存的默认位置），小把手在哪，悬浮球在哪，侧栏开没开，「闲时半透明」「竖条」开没开。
 * 电脑上的布局（默认位置）一变，就清掉平时拖出来的位置；展开 / 收起状态、两个开关留着。
 */
internal class WorkbenchSession(private val store: Store) {
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    enum class State { OPEN, COLLAPSED, HIDDEN }

    /** 语音条第一行下面能展开的两排：「编辑▾」的改字按键、「快捷发送▾」存好的话 */
    enum class VoiceRow { EDIT, QUICK }

    /** x、y、pillX、pillY 是左上角占屏幕的比例；null = 没拖过，用默认 */
    data class Tool(val state: State, val extra: Boolean, val x: Float?, val y: Float?, val pillX: Float?, val pillY: Float?)

    fun tool(landscape: Boolean, name: String): Tool {
        val p = prefix(landscape, name)
        val state = store.get("$p.state")?.let { s -> State.values().firstOrNull { it.name == s } }
            ?: if (landscape) State.COLLAPSED else State.OPEN
        val extra = store.get("$p.extra")?.toBooleanStrictOrNull() ?: (!landscape && name == "keymouse")
        val pill = defaultPill(landscape, name)
        return Tool(state, extra, ratio("$p.x"), ratio("$p.y"), ratio("$p.px") ?: pill?.first, ratio("$p.py") ?: pill?.second)
    }

    fun setTool(landscape: Boolean, name: String, tool: Tool) {
        val p = prefix(landscape, name)
        store.put("$p.state", tool.state.name)
        store.put("$p.extra", tool.extra.toString())
        store.put("$p.x", tool.x?.toString())
        store.put("$p.y", tool.y?.toString())
        store.put("$p.px", tool.pillX?.toString())
        store.put("$p.py", tool.pillY?.toString())
    }

    /**
     * 语音条现在展开的是哪一排；都收着返回 null。两排同时只开一排，横屏、竖屏各记各的。
     * 「展开没展开」还是语音条的 extra（以前只有「编辑▾」一排，开着的人升级后看到的还是那一排）。
     */
    fun voiceRow(landscape: Boolean): VoiceRow? {
        if (!tool(landscape, "voice").extra) return null
        val saved = store.get("${prefix(landscape, "voice")}.row")
        return VoiceRow.values().firstOrNull { it.name == saved } ?: VoiceRow.EDIT
    }

    /** 点了「编辑▾」或「快捷发送▾」：开着的正是这一排就收起，不然换成这一排（另一排跟着收起）。返回点完开着的那排 */
    fun toggleVoiceRow(landscape: Boolean, row: VoiceRow): VoiceRow? {
        val next = if (voiceRow(landscape) == row) null else row
        val p = prefix(landscape, "voice")
        store.put("$p.extra", (next != null).toString())
        if (next != null) store.put("$p.row", next.name)
        return next
    }

    fun ball(landscape: Boolean): Pair<Float, Float>? {
        val x = ratio("wb.${o(landscape)}.ball.x") ?: return null
        val y = ratio("wb.${o(landscape)}.ball.y") ?: return null
        return x to y
    }

    fun setBall(landscape: Boolean, x: Float, y: Float) {
        store.put("wb.${o(landscape)}.ball.x", x.coerceIn(0f, 1f).toString())
        store.put("wb.${o(landscape)}.ball.y", y.coerceIn(0f, 1f).toString())
    }

    fun railOpen(landscape: Boolean): Boolean = store.get("wb.${o(landscape)}.rail") == "true"

    fun setRailOpen(landscape: Boolean, open: Boolean) = store.put("wb.${o(landscape)}.rail", open.toString())

    /** 侧栏「闲时半透明」（v3.3 U7）：条和小把手 3 秒没碰就变淡。横竖屏通用，默认关 */
    var idleFade: Boolean
        get() = store.get("wb.idleFade") == "true"
        set(value) = store.put("wb.idleFade", value.toString())

    /** 侧栏「竖条」（v3.3 U8）：语音条、键鼠条一起改成竖着的窄条。横竖屏通用，默认关 */
    var verticalBars: Boolean
        get() = store.get("wb.vertical") == "true"
        set(value) = store.put("wb.vertical", value.toString())

    fun clearPositions() {
        for (landscape in listOf(false, true)) {
            for (name in listOf("voice", "keymouse")) {
                val p = prefix(landscape, name)
                for (k in listOf("x", "y", "px", "py")) store.put("$p.$k", null)
            }
            store.put("wb.${o(landscape)}.ball.x", null)
            store.put("wb.${o(landscape)}.ball.y", null)
        }
    }

    private fun ratio(key: String): Float? = store.get(key)?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }

    /** 横屏默认收在画面左右两边的空白里 */
    private fun defaultPill(landscape: Boolean, name: String): Pair<Float, Float>? = when {
        !landscape -> null
        name == "voice" -> 0.004f to 0.88f
        else -> 0.905f to 0.88f
    }

    private fun o(landscape: Boolean) = if (landscape) "l" else "p"
    private fun prefix(landscape: Boolean, name: String) = "wb.${o(landscape)}.$name"

    companion object {
        fun of(prefs: SharedPreferences) = WorkbenchSession(object : Store {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        })
    }
}
