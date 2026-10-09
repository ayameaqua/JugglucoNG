package tk.glucodata

import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.*
import org.junit.Test

class HealthConnectGlucoseListTests {
    @Test fun simultaneousSensorsHaveIndependentIdsAndTruthfulDeviceMetadata() {
        val time = 1_800_000_000L
        fun records(serial: String, manufacturer: String, model: String) = GlucoseList(
            Metadata.unknownRecordingMethod(device = androidx.health.connect.client.records.metadata.Device(0, manufacturer, model)), 0, 1, serial) { packed(time, 120, 1) }
        val a = records("tx-a", "Yuwell", "CT4 · cgm-a")
        val b = records("tx-b", "Sibionics", "GS1-P2 · cgm-b")
        assertNotEquals(a.single().metadata.clientRecordId, b.single().metadata.clientRecordId)
        assertEquals(a.single().time, b.single().time)
        assertEquals("Yuwell", a.single().metadata.device!!.manufacturer)
        assertEquals("GS1-P2 · cgm-b", b.single().metadata.device!!.model)
        assertNull(a.single().zoneOffset)
    }
    private fun packed(time: Long, glucose: Int, next: Int) =
        time or (glucose.toLong() shl 32) or (next.toLong() shl 48)

    @Test fun sparseSnapshotHasTheActualSizeAndStableIds() {
        var calls = 0
        val records = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") { pos ->
            calls++
            when (pos) {
                10 -> packed(1_700_000_000, 100, 13)
                13 -> packed(1_700_000_180, 105, 15)
                else -> error("reader escaped its chunk: $pos")
            }
        }
        assertEquals(2, calls)
        assertEquals(2, records.size)
        assertFalse(records.isEmpty())
        assertEquals("juggluco-ng:glucose:sensor:1700000000", records[0].metadata.clientRecordId)
        assertEquals(1_700_000_180L, records[1].time.epochSecond)
        assertEquals(2, records.toList().size)
        val replay = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") { pos ->
            if (pos == 10) packed(1_700_000_000, 100, 13) else packed(1_700_000_180, 105, 15)
        }
        assertEquals(records.map { it.metadata.clientRecordId }, replay.map { it.metadata.clientRecordId })
    }

    @Test fun emptyChunkContainsNoZeroGlucoseRecord() {
        val records = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") {
            packed(0, 0, 15)
        }
        assertTrue(records.isEmpty())
        assertEquals(0, records.size)
    }

    @Test fun malformedReaderCannotLoopForever() {
        var calls = 0
        val records = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") {
            calls++
            packed(0, 0, 10)
        }
        assertEquals(1, calls)
        assertTrue(records.isEmpty())
    }
}
