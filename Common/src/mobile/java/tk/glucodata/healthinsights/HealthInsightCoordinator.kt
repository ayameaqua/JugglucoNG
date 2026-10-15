package tk.glucodata.healthinsights

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

class HealthInsightCoordinator internal constructor(private val context: Context, private val adapterOverride: HealthReadAdapter? = null,
    private val adapterOverrides: Map<HealthDataSource, HealthReadAdapter> = emptyMap()) {
    val settings = context.getSharedPreferences("health_insights", Context.MODE_PRIVATE)
    init {
        // Migrate the old Samsung-only preference once. Enabling a fresh HC reader must not
        // later look like an old Samsung installation merely because "enabled" now exists.
        synchronized(HealthInsightCoordinator::class.java) {
            if (!settings.contains("health_source")) {
                val initial = if (adapterOverride != null || settings.contains("enabled") || settings.contains("selected")) HealthDataSource.SAMSUNG else HealthDataSource.HEALTH_CONNECT
                check(settings.edit().putString("health_source", initial.name).commit())
            }
        }
    }
    private val stores = mutableMapOf<HealthDataSource, HealthInsightStore>()
    private val adapters = mutableMapOf<HealthDataSource, HealthReadAdapter>()
    private val lock = Mutex()
    private var lastRefresh = 0L
    private var lastRefreshSource: HealthDataSource? = null
    fun source(): HealthDataSource = settings.getString("health_source", null)?.let { value -> HealthDataSource.entries.firstOrNull { it.name == value } }
        ?: HealthDataSource.HEALTH_CONNECT
    @Synchronized fun storeFor(source: HealthDataSource): HealthInsightStore = stores.getOrPut(source) { HealthInsightStore(context, source.provider) }
    val store: HealthInsightStore get() = storeFor(source())
    @Synchronized fun adapterFor(source: HealthDataSource): HealthReadAdapter = adapters.getOrPut(source) {
        adapterOverrides[source] ?: adapterOverride ?: when(source) {
            HealthDataSource.SAMSUNG -> SamsungAdapterFactory.create(context)
            HealthDataSource.HEALTH_CONNECT -> HealthConnectReader(context)
        }
    }
    val adapter: HealthReadAdapter get() = adapterFor(source())
    fun cachedStores(): List<Pair<HealthDataSource, HealthInsightStore>> = HealthDataSource.entries.filter {
        it == source() || context.getDatabasePath(if(it == HealthDataSource.SAMSUNG) "health-insights.db" else "health-insights-hc.db").exists()
    }.map { it to storeFor(it) }
    fun selected(): Set<HealthKind> {
        val source = source()
        val saved = settings.getStringSet("selected_${source.name}", null)
            ?: if(source == HealthDataSource.SAMSUNG) settings.getStringSet("selected", null) else null
        return (saved?.mapNotNull { runCatching { HealthKind.valueOf(it) }.getOrNull() }?.toSet() ?: HealthKind.defaults)
            .intersect(adapter.supportedKinds)
    }
    fun saveSelected(kinds: Set<HealthKind>) { settings.edit().putStringSet("selected_${source().name}", kinds.map { it.name }.toSet()).apply() }
    suspend fun selectSource(source: HealthDataSource) = lock.withLock {
        check(settings.edit().putString("health_source", source.name).commit())
        lastRefresh = 0L
    }
    suspend fun refresh(force: Boolean, progress: (String) -> Unit = {}) {
        val requestedAt = System.currentTimeMillis()
        val alreadyRunning = lock.isLocked
        lock.withLock {
            val current = source()
            if (lastRefreshSource == current && ((alreadyRunning && lastRefresh >= requestedAt) || (!force && requestedAt - lastRefresh < 300_000))) return
            withContext(Dispatchers.IO) {
                val kinds = selected(); val reader = adapterFor(current); val cache = storeFor(current)
                when {
                    !settings.getBoolean("enabled", false) -> kinds.forEach { cache.fail(it, "读取开关已关闭；保留本地缓存", "disabled") }
                    !reader.available -> kinds.forEach { cache.fail(it, reader.availabilityMessage, "unavailable") }
                    else -> {
                        val until = Instant.now()
                        val days = settings.getInt("days", 30).coerceIn(1, 3650)
                        reader.read(kinds, until.minusSeconds(days * 86400L), until, cache, progress)
                    }
                }
                lastRefresh = System.currentTimeMillis(); lastRefreshSource = current
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
