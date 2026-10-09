package tk.glucodata.healthinsights

import android.app.Activity
import android.content.Context
import java.time.Instant

object SamsungAdapterFactory {
    fun create(context: Context): SamsungReadAdapter = object : SamsungReadAdapter {
        override val available = false
        override val availabilityMessage = "此构建未包含三星 SDK。个人测试需自行准备官方 SDK，并在三星健康开启开发者模式授权。"
        override suspend fun granted(kinds: Set<HealthKind>) = emptySet<HealthKind>()
        override suspend fun request(activity: Activity, kinds: Set<HealthKind>) = emptySet<HealthKind>()
        override suspend fun read(kinds: Set<HealthKind>, from: Instant, until: Instant, store: HealthInsightStore, progress: (String) -> Unit) {
            kinds.forEach { store.fail(it, availabilityMessage) }
        }
    }
}
