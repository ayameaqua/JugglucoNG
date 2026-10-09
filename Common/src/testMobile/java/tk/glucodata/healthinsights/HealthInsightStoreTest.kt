package tk.glucodata.healthinsights

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.io.StringWriter
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HealthInsightStoreTest {
    private lateinit var store: HealthInsightStore
    private val from = Instant.parse("2026-10-06T00:00:00Z")
    private val until = Instant.parse("2026-10-09T00:00:00Z")
    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("health-insights.db")
        store = HealthInsightStore(ctx)
    }
    @After fun close() { store.close() }
    private fun row(id: String) = HealthCanonical.base(id, "heart_rate", HealthCanonical.time(from, until, "+07:00"), "samsung_health", until)
    @Test fun replacementKeepsExactlyOneParentAndReplacesWholeSeriesRevision() {
        var run = store.begin()
        store.stage(run, HealthKind.HEART_RATE, row("one"), listOf(JSONObject().put("revision", 1), JSONObject().put("revision", 1)))
        store.complete(run, HealthKind.HEART_RATE, from, until)
        run = store.begin()
        store.stage(run, HealthKind.HEART_RATE, row("one"), listOf(JSONObject().put("revision", 2)))
        store.complete(run, HealthKind.HEART_RATE, from, until)
        val r = StringWriter(); val s = StringWriter()
        assertEquals(1, store.snapshot(r, s, StringWriter()))
        assertEquals(1, s.toString().trim().lines().size)
        assertEquals(2, JSONObject(s.toString().trim()).getInt("revision"))
    }
    @Test fun failedPageDoesNotPublishPartialParentsSeriesOrAdvanceCursor() {
        val initial = store.begin(); store.stage(initial, HealthKind.HEART_RATE, row("old")); store.complete(initial, HealthKind.HEART_RATE, from, until)
        val run = store.begin(); store.stage(run, HealthKind.HEART_RATE, row("new")); store.abandon(run); store.fail(HealthKind.HEART_RATE, "page 2 timeout")
        assertEquals(until.toEpochMilli(), store.cursor(HealthKind.HEART_RATE))
        val r = StringWriter(); assertEquals(1, store.snapshot(r, StringWriter(), StringWriter())); assertTrue(r.toString().contains("old")); assertFalse(r.toString().contains("new"))
    }
    @Test fun directAndAssociatedRecordsDeduplicateAndExplicitDeletionRemovesRelations() {
        val id = store.alias("samsung_health", "blood_oxygen", "provider-uid")
        val run = store.begin(); store.stage(run, HealthKind.BLOOD_OXYGEN, row(id)); store.stage(run, HealthKind.BLOOD_OXYGEN, row(id)); store.associate(run, "sleep", id)
        store.complete(run, HealthKind.BLOOD_OXYGEN, from, until)
        val relations = StringWriter(); assertEquals(1, store.snapshot(StringWriter(), StringWriter(), relations)); assertTrue(relations.toString().contains("sleep"))
        val next = store.begin(); store.stage(next, HealthKind.BLOOD_OXYGEN, row(id)); store.associate(next, "sleep", id)
        store.stageDelete(next, HealthKind.BLOOD_OXYGEN, "provider-uid"); store.complete(next, HealthKind.BLOOD_OXYGEN, from, until)
        val after = StringWriter(); assertEquals(0, store.snapshot(StringWriter(), StringWriter(), after)); assertEquals("", after.toString())
    }
    @Test fun emptySuccessDoesNotDeleteCachedDataAndDeniedOrDisabledDoesNotEraseSuccess() {
        val run = store.begin(); store.stage(run, HealthKind.HEART_RATE, row("cached")); store.complete(run, HealthKind.HEART_RATE, from, until)
        store.complete(store.begin(), HealthKind.HEART_RATE, from, until)
        for (status in listOf("disabled", "permission_denied", "unavailable")) {
            store.fail(HealthKind.HEART_RATE, status, status)
            assertEquals(1, store.snapshot(StringWriter(), StringWriter(), StringWriter()))
            assertEquals(until.toEpochMilli(), store.cursor(HealthKind.HEART_RATE))
        }
    }
    @Test fun epochAndOriginalOffsetsRemainDistinctAndAllSeriesIncludingNestedLogsSurvive() {
        val point = row("test")
        assertEquals(from.toEpochMilli(), point.getJSONObject("time").getLong("start_epoch_ms"))
        assertEquals("+07:00", point.getJSONObject("time").getString("original_offset"))
        val log = JSONArray().put(JSONObject().put("getTimestamp", JSONObject().put("utc", from.toString())).put("getSpeed", 1.2))
        val fields = JSONObject().put("SESSIONS", JSONArray().put(JSONObject().put("getStartTime", JSONObject().put("utc", from.toString())).put("getEndTime", JSONObject().put("utc", until.toString())).put("getLog", log)))
        val series = HealthCanonical.splitSeries(point, fields)
        assertEquals(2, series.size)
        assertEquals(2, point.getJSONArray("series_refs").length())
        assertTrue(series.any { it.getJSONObject("attributes").getJSONObject("sdk_value").optDouble("getSpeed") == 1.2 })
        assertTrue(series.all { it.getJSONObject("time").getString("basis") == "instant_interval" })
    }
    @Test fun aliasesRemainStableAndProviderTypeNamespacesCannotCollide() {
        val a = store.alias("samsung_health", "sleep", "private-uid")
        assertEquals(a, store.alias("samsung_health", "sleep", "private-uid"))
        assertNotEquals(a, store.alias("samsung_health", "heart_rate", "private-uid"))
        assertFalse(a.contains("private-uid"))
    }
}
