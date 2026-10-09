package tk.glucodata

import android.app.Application
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HealthConnectSourcesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.deleteDatabase("health-connect-sources.db") }
    private fun record(alias: String, time: Long) = BloodGlucoseRecord(Instant.ofEpochMilli(time), null,
        Metadata.unknownRecordingMethod("juggluco-ng:glucose:$alias:${time / 1000}", 1), BloodGlucose.milligramsPerDeciliter(200.0), 1, 0, 0)

    @Test fun orderReconnectAndMainSelectionCannotChangeSourceIds() {
        var store = HealthConnectSources(context)
        val a = store.register("tx-a", manufacturer = "Yuwell", model = "CT4")
        val b = store.register("tx-b", manufacturer = "Sibionics", model = "GS1-P2")
        assertNotEquals(a.uid, b.uid)
        store.close(); store = HealthConnectSources(context)
        assertEquals(b, store.register("tx-b"))
        assertEquals(a, store.register("tx-a"))
        assertEquals(a, store.find(a.shortId)); assertEquals(b, store.find(b.uid))
        assertEquals("Yuwell", a.device.manufacturer)
        assertEquals("CT4 · ${a.shortId}", a.device.model)
        store.close()
    }
    @Test fun reusableTransmitterKeepsIdentityButDifferentWearsAndOldRecordsRemainDistinct() {
        var store = HealthConnectSources(context)
        val source = store.register("tx")
        store.observeWear(source, 100_000, "wear-a")
        val old = record("tx", 110_000)
        store.index(source, listOf(old), true)
        store.observeWear(source, 300_000, "wear-b")
        val fresh = record("tx", 310_000)
        store.index(source, listOf(fresh, old), false)
        assertEquals("wear-a", store.lookup(old.metadata.clientRecordId!!)!!.wearId)
        assertEquals("wear-b", store.lookup(fresh.metadata.clientRecordId!!)!!.wearId)
        assertNull(store.wearAt("tx", 10_000))
        store.close(); store = HealthConnectSources(context)
        assertEquals(source.uid, store.register("tx").uid)
        assertEquals("wear-a", store.lookup(old.metadata.clientRecordId!!)!!.wearId)
        store.close()
    }
    @Test fun sameTimeReadingsMapToTheirOwnSensorAndFailedUploadIsNotMarkedDone() {
        val store = HealthConnectSources(context)
        val a = store.register("a"); val b = store.register("b")
        val ra = record("a", 110_000); val rb = record("b", 110_000)
        store.index(a, listOf(ra), false); store.index(b, listOf(rb), true)
        assertEquals(a.uid, store.find(ra.metadata.clientRecordId!!)!!.uid)
        assertEquals(b.uid, store.find(rb.metadata.clientRecordId!!)!!.uid)
        assertFalse(store.lookup(ra.metadata.clientRecordId!!)!!.uploaded)
        store.index(a, listOf(ra), true)
        assertTrue(store.lookup(ra.metadata.clientRecordId!!)!!.uploaded)
        assertEquals(110_000L, store.lookup(ra.metadata.clientRecordId!!)!!.epochMs)
        store.close()
    }
    @Test fun metadataReplayRequiresCompletionAndUnknownDataDoesNotEraseKnownDevice() {
        val store = HealthConnectSources(context)
        val unknown = store.register("tx")
        assertTrue(store.needsReplay(unknown))
        store.markPublished(unknown); assertFalse(store.needsReplay(unknown))
        val known = store.register("tx", manufacturer = "Yuwell", model = "CT4")
        assertEquals(unknown.uid, known.uid); assertTrue(store.needsReplay(known))
        store.markPublished(known); assertFalse(store.needsReplay(store.register("tx")))
        store.close()
    }
    @Test fun learningCanonicalAliasRetainsIdentityAndLegacyRecordId() {
        val store = HealthConnectSources(context)
        val short = store.register("short")
        val row = record("short", 110_000)
        store.index(short, listOf(row), true)
        val full = store.register("canonical", alias = "short", manufacturer = "Yuwell")
        assertEquals(short.uid, full.uid)
        assertEquals("canonical", store.lookup(row.metadata.clientRecordId!!)!!.source.serial)
        assertEquals(full, store.find(row.metadata.clientRecordId!!))
        assertEquals(1, store.all().size)
        store.close()
    }
    @Test fun clockRefinementAndParameterUpdatesDoNotCreateAnotherWear() {
        val store = HealthConnectSources(context)
        val source = store.register("tx")
        store.observeWear(source, 100_000)
        val first = store.wearAt("tx", 200_000)
        store.observeWear(source, 110_000)
        val parametersUpdated = store.register("tx", model = "GS1-P2")
        assertEquals(first, store.wearAt(parametersUpdated.serial, 200_000))
        assertEquals(source.uid, parametersUpdated.uid)
        store.close()
    }
    @Test fun providerRecordIdsResolveAndSurviveRetriesButMalformedResponsesAreNotGuessed() {
        val store = HealthConnectSources(context)
        val source = store.register("tx")
        val first = record("tx", 110_000); val second = record("tx", 120_000)
        store.index(source, listOf(first, second), true, listOf("health-uuid-1", "health-uuid-2"))
        assertEquals(first.metadata.clientRecordId, store.lookup("health-uuid-1")!!.recordId)
        assertEquals(120_000L, store.lookup("health-uuid-2")!!.epochMs)
        store.index(source, listOf(first, second), false)
        assertFalse(store.lookup("health-uuid-1")!!.uploaded)
        store.index(source, listOf(first, second), true, listOf("unexpected-single-id"))
        assertNull(store.lookup("unexpected-single-id"))
        assertEquals(source, store.find("health-uuid-1"))
        store.close()
    }
}
