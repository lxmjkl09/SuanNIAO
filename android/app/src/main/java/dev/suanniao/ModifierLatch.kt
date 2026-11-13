package dev.suanniao

/**
 * 键鼠条上的 Ctrl / Shift / Alt / Win。点一下：关 → 一次（下一次点击或按键带上，用完自动松开）→ 锁住 → 关。
 * 这里只管状态；按下 / 松开的消息由调用方发：cycle 返回 true = 发按下，false = 发松开，null = 不用发。
 */
internal class ModifierLatch {
    enum class State { OFF, ONCE, LOCKED }

    private val states = linkedMapOf("ctrl" to State.OFF, "shift" to State.OFF, "alt" to State.OFF, "win" to State.OFF)

    fun state(key: String): State = states[key] ?: State.OFF

    fun cycle(key: String): Boolean? {
        val current = states[key] ?: return null
        val next = when (current) {
            State.OFF -> State.ONCE
            State.ONCE -> State.LOCKED
            State.LOCKED -> State.OFF
        }
        states[key] = next
        return when (next) {
            State.ONCE -> true
            State.OFF -> false
            State.LOCKED -> null
        }
    }

    /** 一次点击或按键用掉了：「一次」的松开，锁住的不动。返回要发松开的键。 */
    fun consumeOnce(): List<String> = take { it == State.ONCE }

    /** 断线、退出、超时：全部松开。返回要发松开的键。 */
    fun releaseAll(): List<String> = take { it != State.OFF }

    /** 按下没发出去（局域网断了）：退回「关」，电脑上本来就没按着，不用发松开 */
    fun drop(key: String) {
        if (key in states) states[key] = State.OFF
    }

    fun anyActive(): Boolean = states.values.any { it != State.OFF }

    /** 顶上提示条的字；都关着返回 null。 */
    fun chipText(): String? {
        val locked = states.filterValues { it == State.LOCKED }.keys.map(::label)
        val once = states.filterValues { it == State.ONCE }.keys.map(::label)
        val parts = buildList {
            if (locked.isNotEmpty()) add(locked.joinToString("+") + " 锁住中")
            if (once.isNotEmpty()) add(once.joinToString("+") + " 等下一次点击 / 按键")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun take(match: (State) -> Boolean): List<String> {
        val keys = states.filterValues(match).keys.toList()
        keys.forEach { states[it] = State.OFF }
        return keys
    }

    companion object {
        val KEYS = listOf("ctrl", "shift", "alt", "win")
        fun label(key: String): String = when (key) {
            "ctrl" -> "Ctrl"
            "shift" -> "Shift"
            "alt" -> "Alt"
            "win" -> "Win"
            else -> key
        }
    }
}
