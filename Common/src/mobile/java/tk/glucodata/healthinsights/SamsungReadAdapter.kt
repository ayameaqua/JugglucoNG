package tk.glucodata.healthinsights

import android.app.Activity
import android.content.Context
import java.time.Instant

interface SamsungReadAdapter {
    val available: Boolean
    val availabilityMessage: String
    suspend fun granted(kinds: Set<HealthKind>): Set<HealthKind>
    suspend fun request(activity: Activity, kinds: Set<HealthKind>): Set<HealthKind>
    suspend fun read(kinds: Set<HealthKind>, from: Instant, until: Instant, store: HealthInsightStore, progress: (String) -> Unit)
}

internal fun samsungInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo("com.sec.android.app.shealth", 0)
}.isSuccess

internal fun samsungVersionSupported(version: String?): Boolean {
    val actual = Regex("\\d+").findAll(version.orEmpty()).map { it.value.toIntOrNull() ?: 0 }.take(3).toList()
    if (actual.size < 3) return false
    val minimum = listOf(6, 30, 2)
    for (n in actual.indices) if (actual[n] != minimum[n]) return actual[n] > minimum[n]
    return true
}
