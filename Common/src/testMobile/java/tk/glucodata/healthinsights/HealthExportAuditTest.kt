package tk.glucodata.healthinsights

import android.app.Application
import androidx.room.Room
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import tk.glucodata.HealthConnectSources
import tk.glucodata.data.*
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HealthExportAuditTest {
    @Test fun catalogCoversBothStorageAliasesButSharesDeviceIdentityAndDoesNotInventWear() {
        val store = HealthInsightStore(RuntimeEnvironment.getApplication())
        try {
            val device = HealthConnectSources.Source("canonical", "same-device", "Yuwell", "CT4")
            val a = HealthGlucoseAudit.sourceEntry(store, "canonical", device, emptyList())
            val b = HealthGlucoseAudit.sourceEntry(store, "legacy", device, emptyList())
            assertNotEquals(a.getString("sensor_alias"), b.getString("sensor_alias"))
            assertEquals(a.getString("source_uid_alias"), b.getString("source_uid_alias"))
            assertEquals(a.getString("sensor_alias"), b.getString("canonical_sensor_alias"))
            assertEquals(0, b.getJSONArray("known_wear_sessions").length())
            assertEquals("driver_resolved_storage_alias", b.getString("identity_basis"))
        } finally { store.close() }
    }
    @Test fun displayJoinKeepsSubMinuteSampleAndDoesNotChooseBetweenDuplicatesOrOtherSensors() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).build()
        try {
            val minute = Instant.parse("2026-10-09T03:00:00Z").toEpochMilli()
            db.historyDao().insertAll(listOf(
                HistoryReading(timestamp = minute + 5330, sensorSerial = "a", value = 101f, rawValue = 90f, rate = null, firstStoredAt = 1),
                HistoryReading(timestamp = minute + 7000, sensorSerial = "b", value = 200f, rawValue = 190f, rate = null, firstStoredAt = 1),
                HistoryReading(timestamp = minute + 61_000, sensorSerial = "a", value = 102f, rawValue = 90f, rate = null, firstStoredAt = 1),
                HistoryReading(timestamp = minute + 65_000, sensorSerial = "a", value = 103f, rawValue = 90f, rate = null, firstStoredAt = 1),
                HistoryReading(timestamp = minute + 121_000, sensorSerial = "a", value = 104f, rawValue = 90f, rate = null, firstStoredAt = 100)))
            db.readingDisplayDao().sealAll((0..2).map { n -> ReadingDisplay(minute + n * 60_000, "a", 100f, 0, 0, minute) })
            val links = db.historyDao().healthDisplayLinks(-1, minute + 120_000, 50).sortedBy { it.minuteMs }
            assertEquals(listOf(1, 2, 0), links.map { it.candidateCount })
            assertEquals(minute + 5330, links.first().sampleMs)
            assertEquals(minute, links.first().minuteMs)
            assertEquals(5, db.historyDao().getCount())
        } finally { db.close() }
    }
    @Test fun reviewFlagsPreserveValuesCompareSameWearAndDoNotCallFirstObservationStartup() {
        fun reading(time: Long, value: Float, serial: String = "a") = HistoryReading(timestamp = time, sensorSerial = serial, value = value, rawValue = 200f, rate = null)
        val first = reading(180_000, 306f); val second = reading(240_000, 122.4f)
        assertEquals(listOf("first_available_record"), HealthGlucoseAudit.flags(first, null, null))
        assertTrue(HealthGlucoseAudit.flags(first, null, 150_000).contains("early_known_wear"))
        assertTrue(HealthGlucoseAudit.flags(second, first, null).contains("abrupt_auto_change_review"))
        assertFalse(HealthGlucoseAudit.flags(second, first, 220_000).contains("abrupt_auto_change_review"))
        assertFalse(HealthGlucoseAudit.flags(second.copy(sensorSerial = "b"), first, null).contains("abrupt_auto_change_review"))
        assertFalse(HealthGlucoseAudit.flags(second.copy(timestamp = 900_000), first, null).contains("abrupt_auto_change_review"))
        assertEquals(306f, first.value, 0f); assertEquals(122.4f, second.value, 0f)
    }
    @Test fun chronologicalAuditPagesIgnoreInsertionOrderKeepSameTimestampSensorsAndHonorCutoff() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).build()
        try {
            db.historyDao().insertAll(listOf(300L to "a", 100L to "b", 100L to "a", 200L to "a").map { (t,s) ->
                HistoryReading(timestamp=t,sensorSerial=s,value=100f,rawValue=90f,rate=null,firstStoredAt=1) })
            var time = -1L; var serial = ""; val seen = mutableListOf<Pair<Long,String>>()
            while (true) {
                val rows = db.historyDao().healthExportTimePage(time, serial, 2, 1)
                if (rows.isEmpty()) break
                val row = rows.single(); time = row.timestamp; serial = row.sensorSerial; seen.add(time to serial)
            }
            assertEquals(listOf(100L to "a",100L to "b",200L to "a",300L to "a"),seen)
        } finally { db.close() }
    }
    @Test fun rawContractDeclaresLinearCt4UnitsButCannotPretendCurrentQrIsHistoricalConfiguration() {
        val contract = HealthGlucoseAudit.channelContract(HealthConnectSources.Source("a","u","Yuwell","CT4"))
        assertTrue(contract.isNull("historical_calibration_parameters"))
        assertTrue(contract.isNull("historical_algorithm_version"))
        assertEquals("mg/dL", contract.getString("stored_unit"))
        assertTrue(contract.getString("current_driver_contract").contains("halves Iw"))
        assertTrue(contract.getString("export_conversion").contains("identity"))
    }
}
