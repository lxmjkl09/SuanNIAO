package dev.suanniao

/**
 * Tracks exactly one workbench layout save. A TLS send returning true means queued,
 * NOT that the Windows Core persisted it. Only matching config echo confirms
 * success; a request-ID-matched NACK or elapsed timeout releases the edit lock.
 *
 * In particular, a late ACK from request A must never unblock request B.
 */
internal class WorkbenchSaveGate(private val timeoutMs: Long = 10_000L) {
    private data class Pending(
        val requestId: String,
        val signature: String,
        val startedAtMs: Long,
    )

    private var pending: Pending? = null
    val busy get() = pending != null
    val requestId get() = pending?.requestId

    fun begin(id: String, signature: String, nowMs: Long): Boolean {
        if (pending != null || id.isEmpty() || signature.isEmpty()) return false
        pending = Pending(id, signature, nowMs)
        return true
    }

    /** Only a validated config echo of this exact candidate completes a save. */
    fun onConfigEcho(signature: String): Boolean {
        val current = pending ?: return false
        if (current.signature != signature) return false
        pending = null
        return true
    }

    /** Return true iff the ACK belongs to the current save. A successful ACK
     * does not yet prove the new layout has reached this phone. */
    fun onAck(id: String, ok: Boolean): Boolean {
        val current = pending ?: return false
        if (current.requestId != id) return false
        if (!ok) pending = null
        return true
    }

    fun expire(id: String, nowMs: Long): Boolean {
        val current = pending ?: return false
        if (current.requestId != id ||
            nowMs - current.startedAtMs < timeoutMs) return false
        pending = null
        return true
    }

    fun abort(id: String) {
        if (pending?.requestId == id) pending = null
    }

    /**
     * 点了「完成」、还在等电脑确认时又不保存了：放开闸门（不然请求丢了以后一直说「还在等电脑确认」），
     * 返回那份布局的签名（没在等返回 null）：电脑其实存上了的话，推回来时还认得出来。
     */
    fun abandon(): String? {
        val current = pending ?: return null
        pending = null
        return current.signature
    }

    fun reset() { pending = null }
}
