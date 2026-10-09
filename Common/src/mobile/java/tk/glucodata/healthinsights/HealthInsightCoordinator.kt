package tk.glucodata.healthinsights

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

class HealthInsightCoordinator internal constructor(private val context: Context, private val adapterOverride: SamsungReadAdapter? = null) {
    val store = HealthInsightStore(context)
    val adapter: SamsungReadAdapter by lazy { adapterOverride ?: SamsungAdapterFactory.create(context) }
    val settings = context.getSharedPreferences("health_insights", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var lastRefresh = 0L
    fun selected(): Set<HealthKind> = settings.getStringSet("selected", null)?.mapNotNull { runCatching { HealthKind.valueOf(it) }.getOrNull() }?.toSet() ?: HealthKind.defaults
    suspend fun refresh(force: Boolean, progress: (String) -> Unit = {}) {
        val requestedAt = System.currentTimeMillis()
        val alreadyRunning = lock.isLocked
        lock.withLock {
            if ((alreadyRunning && lastRefresh >= requestedAt) || (!force && requestedAt - lastRefresh < 300_000)) return
            withContext(Dispatchers.IO) {
                val kinds = selected()
                when {
                    !settings.getBoolean("enabled", false) -> kinds.forEach { store.fail(it, "读取开关已关闭；保留本地缓存", "disabled") }
                    !adapter.available -> kinds.forEach { store.fail(it, adapter.availabilityMessage, "unavailable") }
                    else -> {
                        val until = Instant.now()
                        val days = settings.getInt("days", 30).coerceIn(1, 3650)
                        adapter.read(kinds, until.minusSeconds(days * 86400L), until, store, progress)
                    }
                }
                lastRefresh = System.currentTimeMillis()
            }
        }
    }
    suspend fun <T> afterRefreshSnapshot(block: suspend () -> T): T {
        refresh(true)
        return lock.withLock { block() }
    }
    companion object {
        @Volatile private var instance: HealthInsightCoordinator? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: HealthInsightCoordinator(context.applicationContext).also { instance = it } }
    }
}
