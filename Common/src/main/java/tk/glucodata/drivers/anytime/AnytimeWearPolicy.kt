package tk.glucodata.drivers.anytime

/** A pull, duplicate push or isolated corrupt id never starts a new physical wear. */
internal class AnytimeWearPolicy {
    enum class Decision { ACCEPT, QUARANTINE, NEW_WEAR }
    private var firstId = -1
    private var lastId = -1
    private var firstAt = 0L
    private var confirmations = 0

    fun observe(liveId: Int, previousMaxId: Int, oldStartMs: Long, nowMs: Long, intervalMs: Long): Decision {
        if (!liveIdLooksRolledBack(liveId, previousMaxId, 48)) {
            reset()
            return Decision.ACCEPT
        }
        // A delayed packet on the old clock cannot establish a later wear.
        val impliedStart = nowMs - liveId.toLong() * intervalMs
        if (oldStartMs > 0 && impliedStart <= oldStartMs + 2 * intervalMs) return Decision.QUARANTINE
        if (firstId < 0 || liveId < lastId || nowMs - firstAt > 30 * 60_000L) {
            firstId = liveId
            lastId = liveId
            firstAt = nowMs
            confirmations = 1
            return Decision.QUARANTINE
        }
        if (liveId == lastId) return Decision.QUARANTINE
        // Demand both distinct ascending ids and elapsed sampling time. Multiple
        // callbacks for one notification cannot supply confirmation.
        if (liveId > lastId && liveId - lastId <= 5) {
            lastId = liveId
            confirmations++
        } else {
            reset()
            return Decision.QUARANTINE
        }
        if (confirmations >= 3 && nowMs - firstAt >= intervalMs) {
            reset()
            return Decision.NEW_WEAR
        }
        return Decision.QUARANTINE
    }

    private fun reset() { firstId = -1; lastId = -1; firstAt = 0; confirmations = 0 }
}
