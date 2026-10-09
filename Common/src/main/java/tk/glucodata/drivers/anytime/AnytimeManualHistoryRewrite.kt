package tk.glucodata.drivers.anytime

import org.json.JSONObject
import java.util.BitSet
import java.util.Base64

/** Explicit consent applies only to the captured wear, QR, clock and history horizon. */
internal class AnytimeManualHistoryRewrite(
    val wearId: String,
    val qrRaw: String?,
    val timelineStartMs: Long,
    val stopBeforeId: Int,
) {
    private val received = BitSet()
    private val written = BitSet()
    @Volatile var active = true
        private set
    @Volatile var status = "正在重新读取并计算本周期历史"
        private set

    fun matches(wear: String, qr: String?, timeline: Long) =
        wearId == wear && qrRaw == qr && timelineStartMs == timeline
    @Synchronized fun receive(id: Int) { if (active && id in 0 until stopBeforeId) received.set(id) }
    @Synchronized fun canReplace(id: Int, completePrefix: Boolean) =
        active && completePrefix && id in 0 until stopBeforeId && received[id]
    @Synchronized fun didWrite(ids: Collection<Int>) { ids.filter { it in 0 until stopBeforeId }.forEach(written::set) }
    @Synchronized fun finish() {
        active = false
        status = "本周期历史已处理：重新读取 ${received.cardinality()} 条，成功重算写入 ${written.cardinality()} 条；缺失、预热或无法完整重算的记录保留原值。上报在后台按权限继续。"
    }
    fun cancel(message: String) { active = false; status = message }
    @Synchronized fun json(): String = JSONObject().put("wear", wearId).put("qr", qrRaw ?: JSONObject.NULL)
        .put("timeline", timelineStartMs).put("stop", stopBeforeId).put("active", active).put("status", status)
        .put("received", Base64.getEncoder().encodeToString(received.toByteArray()))
        .put("written", Base64.getEncoder().encodeToString(written.toByteArray())).toString()

    companion object {
        const val REASON = "user-requested-recompute"
        fun requiresFreshRead(reason: String) = reason.startsWith(REASON)
        fun restore(encoded: String): AnytimeManualHistoryRewrite? = runCatching {
            val o = JSONObject(encoded)
            val stop = o.getInt("stop"); require(stop in 1..100_000)
            AnytimeManualHistoryRewrite(o.getString("wear"), if (o.isNull("qr")) null else o.getString("qr"), o.getLong("timeline"), stop).apply {
                received.or(BitSet.valueOf(Base64.getDecoder().decode(o.getString("received"))))
                written.or(BitSet.valueOf(Base64.getDecoder().decode(o.getString("written"))))
                active = o.getBoolean("active"); status = o.getString("status")
            }
        }.getOrNull()
    }
}
