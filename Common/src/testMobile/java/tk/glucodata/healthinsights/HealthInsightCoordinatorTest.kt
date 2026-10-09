package tk.glucodata.healthinsights

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HealthInsightCoordinatorTest {
    private lateinit var context: Context
    private class Adapter(override val available: Boolean = true, var permission: Boolean = true) : SamsungReadAdapter {
        override val availabilityMessage = "not installed"
        var reads = 0; var fail = false
        override suspend fun granted(kinds: Set<HealthKind>) = if (permission) kinds else emptySet()
        override suspend fun request(activity: Activity, kinds: Set<HealthKind>) = granted(kinds)
        override suspend fun read(kinds: Set<HealthKind>, from: Instant, until: Instant, store: HealthInsightStore, progress: (String) -> Unit) {
            reads++
            for (kind in kinds) {
                if (!permission || fail) { store.fail(kind, "fake failure", if (permission) "error" else "permission_denied"); continue }
                val run = store.begin()
                store.stage(run, kind, HealthCanonical.base("stable-id", kind.kind, HealthCanonical.time(from, until, "+08:00"), "samsung_health", until))
                store.complete(run, kind, from, until)
            }
        }
    }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("health-insights.db")
        context.getSharedPreferences("health_insights", 0).edit().clear().putStringSet("selected", setOf("HEART_RATE")).commit()
    }
    @Test fun disabledAndMissingAppSafelySkipSdkRead() = runBlocking {
        val adapter = Adapter(); val coordinator = HealthInsightCoordinator(context, adapter)
        coordinator.refresh(true); assertEquals(0, adapter.reads); assertEquals("disabled", coordinator.store.states().first().getString("status"))
        coordinator.store.close()
        val unavailable = Adapter(false); val next = HealthInsightCoordinator(context, unavailable)
        next.settings.edit().putBoolean("enabled", true).commit(); next.refresh(true)
        assertEquals(0, unavailable.reads); assertEquals("unavailable", next.store.states().first().getString("status")); next.store.close()
    }
    @Test fun permissionFailureThenManualRetryAndDuplicateImportRemainIdempotent() = runBlocking {
        val adapter = Adapter(permission = false); val coordinator = HealthInsightCoordinator(context, adapter)
        coordinator.settings.edit().putBoolean("enabled", true).commit()
        coordinator.refresh(true); assertEquals("permission_denied", coordinator.store.states().first().getString("status"))
        adapter.permission = true
        coordinator.refresh(true); adapter.fail = true; coordinator.refresh(true)
        assertEquals(1, coordinator.store.snapshot(StringWriter(), StringWriter(), StringWriter()))
        adapter.fail = false; coordinator.refresh(true)
        assertEquals(1, coordinator.store.snapshot(StringWriter(), StringWriter(), StringWriter()))
        coordinator.store.close()
    }
    @Test fun pageEntryThrottlesButExportRefreshesThenIncludesDisabledTypeCache() = runBlocking {
        val adapter = Adapter(); val coordinator = HealthInsightCoordinator(context, adapter)
        coordinator.settings.edit().putBoolean("enabled", true).commit()
        coordinator.refresh(false); coordinator.refresh(false); assertEquals(1, adapter.reads)
        coordinator.settings.edit().putStringSet("selected", emptySet()).commit()
        val count = coordinator.afterRefreshSnapshot { coordinator.store.snapshot(StringWriter(), StringWriter(), StringWriter()) }
        assertEquals(1, count)
        coordinator.store.close()
    }
}
