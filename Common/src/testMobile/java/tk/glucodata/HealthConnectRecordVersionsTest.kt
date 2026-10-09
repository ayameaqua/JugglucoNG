package tk.glucodata

import android.app.Application
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HealthConnectRecordVersionsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.deleteDatabase("health-connect-versions.db") }

    @Test fun retryAndRestartReuseVersionButCorrectionsAdvanceEvenWhenClockMovesBack() {
        var now = 1000L
        var store = HealthConnectRecordVersions(context) { now }
        val original = store.batch { it("record", 100) }
        assertEquals(1000L, original)
        assertEquals(original, store.batch { it("record", 100) })
        store.close()
        now = 900L
        store = HealthConnectRecordVersions(context) { now }
        val changed = store.batch { it("record", 110) }
        assertEquals(1001L, changed)
        assertEquals(changed, store.batch { it("record", 110) })
        assertEquals(1002L, store.batch { it("record", 100) })
        store.close()
    }

    @Test fun eachRecordHasItsOwnRevisionAndFailedSnapshotRollsBackAsAUnit() {
        var now = 1000L
        val store = HealthConnectRecordVersions(context) { now }
        store.batch { it("a", 100); it("b", 200) }
        now = 2000
        runCatching { store.batch { it("a", 120); error("snapshot failed before upload") } }
        assertEquals(1000L, store.batch { it("a", 100) })
        assertEquals(1000L, store.batch { it("b", 200) })
        assertEquals(2000L, store.batch { it("a", 120) })
        assertEquals(1000L, store.batch { it("b", 200) })
        store.close()
    }

    @Test fun correctedAndRetriedSnapshotKeepsIdsAndEpochAcrossTimezoneChanges() {
        val store = HealthConnectRecordVersions(context) { 1000 }
        val time = 1_800_000_000L
        val originalZone = java.util.TimeZone.getDefault()
        fun snapshot(mgdl: Int) = store.batch { versions ->
            GlucoseList(Metadata.unknownRecordingMethod(), 10, 1, "same-transmitter",
                { time or (mgdl.toLong() shl 32) or (11L shl 48) },
                { id, value -> versions(id, value) })
        }.single()
        try {
            val first = snapshot(100)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
            val corrected = snapshot(120)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val retry = snapshot(120)
            assertEquals(first.metadata.clientRecordId, corrected.metadata.clientRecordId)
            assertEquals(first.time, corrected.time)
            assertEquals(time, retry.time.epochSecond)
            assertTrue(corrected.metadata.clientRecordVersion > first.metadata.clientRecordVersion)
            assertEquals(corrected.metadata.clientRecordVersion, retry.metadata.clientRecordVersion)
            assertEquals(120.0, retry.level.inMilligramsPerDeciliter, 0.0)
        } finally { store.close(); java.util.TimeZone.setDefault(originalZone) }
    }
}
