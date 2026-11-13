package dev.suanniao

/**
 * 工作台里正式版按键的单击 / 双击 / 长按判定（跟 KeyButton 一样的规则，不含连发和直通）：
 * - 没配双击：松手立刻算单击；配了双击：松手后等双击间隔，没有第二下才算单击；
 * - 配了长按：按住到时间就算长按，松手不再算单击。
 * 时间由调用方传进来（方便测试）；tick 要在按下后 longMs、松手后 doubleMs 各调一次。
 */
internal class TapTriggers(
    private val hasDouble: Boolean,
    private val hasLong: Boolean,
    private val doubleMs: Long,
    private val longMs: Long,
) {
    private var downAt = -1L
    private var longFired = false
    private var pendingClickAt = -1L

    fun down(now: Long) {
        downAt = now
        longFired = false
    }

    fun tick(now: Long): String? {
        if (hasLong && downAt >= 0 && !longFired && now - downAt >= longMs) {
            longFired = true
            pendingClickAt = -1
            return "longPress"
        }
        if (pendingClickAt >= 0 && downAt < 0 && now - pendingClickAt > doubleMs) {
            pendingClickAt = -1
            return "click"
        }
        return null
    }

    fun up(now: Long): String? {
        downAt = -1
        if (longFired) return null
        if (!hasDouble) return "click"
        if (pendingClickAt >= 0 && now - pendingClickAt <= doubleMs) {
            pendingClickAt = -1
            return "doubleClick"
        }
        pendingClickAt = now
        return null
    }

    fun cancel() {
        downAt = -1
        pendingClickAt = -1
        longFired = false
    }
}
