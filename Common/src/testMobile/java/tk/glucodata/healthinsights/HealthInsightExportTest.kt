package tk.glucodata.healthinsights

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.data.*
import tk.glucodata.data.journal.JournalEntryEntity
import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HealthInsightExportTest {
    private val unavailable = object : SamsungReadAdapter {
        override val available = false
        override val availabilityMessage = "not installed"
        override suspend fun granted(kinds: Set<HealthKind>) = emptySet<HealthKind>()
        override suspend fun request(activity: Activity, kinds: Set<HealthKind>) = emptySet<HealthKind>()
        override suspend fun read(kinds: Set<HealthKind>, from: Instant, until: Instant, store: HealthInsightStore, progress: (String) -> Unit) = error("disabled SDK must not read")
    }
    @Test fun selectedCgmExportsOneOrSeveralSensorsWithoutHealthJournalOrOtherLanes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("health-insights.db")
        context.getSharedPreferences("health_insights", 0).edit().clear().commit()
        var availabilityChecks = 0
        val adapter = object : SamsungReadAdapter by unavailable {
            override val available: Boolean get() { availabilityChecks++; error("CGM-only must not invoke Samsung") }
        }
        val coordinator = HealthInsightCoordinator(context, adapter)
        coordinator.settings.edit().putBoolean("enabled", true).commit()
        val name = "health-selected-${java.util.UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
        try {
            val stamp = Instant.parse("2026-10-06T03:00:00.123Z").toEpochMilli()
            // An entirely excluded first page must still advance the cursor.
            db.historyDao().insertAll((0..2004).map { n -> HistoryReading(timestamp = stamp + n * 60_000L,
                sensorSerial = if (n < 1000) "excluded" else if (n % 2 == 0) "a" else "b",
                value = 100f, rawValue = 90f, rate = null, firstStoredAt = 1) })
            db.readingDisplayDao().sealAll(listOf(
                ReadingDisplay(ReadingDisplay.minuteOf(stamp), "excluded", 117f, 1, 42L, stamp),
                ReadingDisplay(ReadingDisplay.minuteOf(stamp + 1000 * 60_000), "a", 118f, 1, 42L, stamp)))
            db.readingUncertaintyDao().insertAll(listOf(
                ReadingUncertainty("excluded", ReadingDisplay.minuteOf(stamp), 80f, 120f, .9f, null, null),
                ReadingUncertainty("a", ReadingDisplay.minuteOf(stamp + 1000 * 60_000), 81f, 121f, .9f, null, null)))
            db.journalDao().upsertEntry(JournalEntryEntity(timestamp = stamp, sensorSerial = "a", entryType = "note", title = "private note", note = "must not leak",
                amount = null, glucoseValueMgDl = null, durationMinutes = null, intensity = null, insulinPresetId = null, source = "manual", sourceRecordId = null, createdAt = 1, updatedAt = 1))
            val run = coordinator.store.begin()
            val health = HealthCanonical.base("cached", "blood_oxygen", HealthCanonical.time(Instant.ofEpochMilli(stamp), Instant.ofEpochMilli(stamp), "+07:00"), "samsung_health", Instant.ofEpochMilli(stamp))
            coordinator.store.stage(run, HealthKind.BLOOD_OXYGEN, health, emptyList())
            coordinator.store.complete(run, HealthKind.BLOOD_OXYGEN, Instant.ofEpochMilli(stamp), Instant.ofEpochMilli(stamp + 1))
            for (selected in listOf(setOf("a"), setOf("a", "b"))) {
                val file = HealthInsightExport.create(context, coordinator, db, HealthExportSelection(HealthExportSelection.Scope.CGM_ONLY, selected))
                try { ZipFile(file).use { zip ->
                    fun rows(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readLines().filter(String::isNotBlank).map(::JSONObject) }
                    val expected = selected.map { coordinator.store.alias("juggluco", "sensor", it) }.toSet()
                    val values = rows("glucose.jsonl")
                    assertEquals(if (selected.size == 1) 503 else 1005, values.size)
                    assertEquals(expected, values.map { it.getJSONObject("source").getString("sensor_alias") }.toSet())
                    assertEquals(stamp + 1000 * 60_000, values.first().getJSONObject("time").getLong("start_epoch_ms"))
                    assertEquals(90.0, values.first().getJSONObject("metrics").getJSONObject("device_glucose").getDouble("value"), 0.0)
                    assertEquals(expected, rows("glucose-sources.jsonl").map { it.getString("sensor_alias") }.toSet())
                    for (stream in listOf("recorded-display.jsonl", "uncertainty.jsonl")) assertEquals(listOf(coordinator.store.alias("juggluco", "sensor", "a")), rows(stream).map { it.getString("sensor_alias") })
                    for (stream in listOf("samsung-records.jsonl", "series.jsonl", "relations.jsonl", "journal.jsonl")) assertTrue(rows(stream).isEmpty())
                    val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                    assertFalse(manifest.getBoolean("partial_refresh"))
                    assertFalse(manifest.getJSONObject("selection").getBoolean("health_and_journal_included"))
                    assertEquals("CGM_ONLY", manifest.getJSONObject("selection").getString("mode"))
                    assertEquals(0, manifest.getJSONArray("samsung_sync").length())
                } } finally { file.delete() }
            }
            assertEquals(0, availabilityChecks)
            assertEquals(2005, db.historyDao().getCount())
            assertTrue(coordinator.store.states().all { it.optString("status") == "success" })
        } finally { db.close(); context.deleteDatabase(name); coordinator.store.close() }
    }
    @Test fun emptyOrAmbiguousSensorSelectionIsRejected() {
        assertTrue(runCatching { HealthExportSelection(HealthExportSelection.Scope.CGM_ONLY) }.isFailure)
        assertTrue(runCatching { HealthExportSelection(HealthExportSelection.Scope.CGM_ONLY, setOf(" ")) }.isFailure)
        assertTrue(runCatching { HealthExportSelection(sensors = setOf("a")) }.isFailure)
        assertTrue(HealthExportSelection().accepts("a"))
        assertFalse(HealthExportSelection(HealthExportSelection.Scope.CGM_ONLY, setOf("a")).accepts("b"))
    }
    @Test fun fullZipUsesAllSensorsAndPagesRetainsSeriesRawDisplayLogsAndAbsoluteTime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("health-insights.db")
        context.getSharedPreferences("health_insights", 0).edit().clear().commit()
        val coordinator = HealthInsightCoordinator(context, unavailable)
        val databaseName = "health-export-${java.util.UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, databaseName).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val previousZone = TimeZone.getDefault()
        try {
            val stamp = Instant.parse("2026-10-06T03:00:00.123Z").toEpochMilli()
            db.historyDao().insertAll((0..1004).map { n -> HistoryReading(timestamp = stamp + n * 180_000L,
                sensorSerial = if (n % 2 == 0) "transmitter-a" else "transmitter-b", value = 100f + n % 40,
                rawValue = 90f + n % 40, rate = null, source = if (n % 2 == 0) "sensor" else "nightscout", firstStoredAt = 1) })
            db.readingDisplayDao().sealAll(listOf(ReadingDisplay(ReadingDisplay.minuteOf(stamp), "transmitter-a", 117f, 1, 42L, stamp)))
            val hcSecond = stamp / 1000L * 1000L
            assertEquals(listOf(stamp), db.historyDao().getSensorReadingsInTimeRange("transmitter-a", hcSecond, hcSecond + 1000).map { it.timestamp })
            assertEquals(300, db.historyDao().healthSourcePage("transmitter-a", Long.MAX_VALUE, 300).size)
            db.readingUncertaintyDao().insertAll(listOf(ReadingUncertainty("transmitter-a", ReadingDisplay.minuteOf(stamp), 80f, 120f, .9f, null, null)))
            db.journalDao().upsertEntry(JournalEntryEntity(timestamp = stamp, sensorSerial = "transmitter-a", entryType = "note", title = "synthetic", note = "complete note",
                amount = null, glucoseValueMgDl = null, durationMinutes = 30, intensity = null, insulinPresetId = null, source = "manual", sourceRecordId = "private-source-id", createdAt = 1, updatedAt = 1))
            val run = coordinator.store.begin()
            val row = HealthCanonical.base("oxygen-alias", "blood_oxygen", HealthCanonical.time(Instant.ofEpochMilli(stamp), Instant.ofEpochMilli(stamp), "+07:00"), "samsung_health", Instant.ofEpochMilli(stamp))
            val fields = JSONObject("""{"SERIES":[{"getTimestamp":{"utc":"2026-10-06T03:00:00.123Z"},"getOxygenSaturation":98}]}""")
            coordinator.store.stage(run, HealthKind.BLOOD_OXYGEN, row, HealthCanonical.splitSeries(row, fields))
            coordinator.store.complete(run, HealthKind.BLOOD_OXYGEN, Instant.ofEpochMilli(stamp), Instant.ofEpochMilli(stamp + 1))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))
            val first = HealthInsightExport.create(context, coordinator, db)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val second = HealthInsightExport.create(context, coordinator, db)
            ZipFile(first).use { a -> ZipFile(second).use { b ->
                fun read(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
                val glucose = read(a, "glucose.jsonl").lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
                assertEquals(1005, glucose.size)
                assertEquals(2, glucose.map { it.getJSONObject("source").getString("sensor_alias") }.toSet().size)
                val catalog = read(a, "glucose-sources.jsonl").lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                assertEquals(2, catalog.size)
                assertEquals(catalog.map { it.getString("local_lookup_id") }.toSet(), glucose.map { it.getJSONObject("source").getString("local_lookup_id") }.toSet())
                assertTrue(glucose.all { it.getJSONObject("source").isNull("wear_session_alias") })
                assertEquals(stamp, glucose.first().getJSONObject("time").getLong("start_epoch_ms"))
                assertEquals(90.0, glucose.first().getJSONObject("metrics").getJSONObject("device_glucose").getDouble("value"), 0.0)
                for (name in listOf("glucose.jsonl", "samsung-records.jsonl", "series.jsonl", "journal.jsonl", "recorded-display.jsonl", "uncertainty.jsonl")) {
                    // imported_at is capture metadata and can differ between exports.
                    fun times(zip: ZipFile) = read(zip, name).lineSequence().filter { it.isNotBlank() }.map { JSONObject(it).optJSONObject("time")?.toString() }.toList()
                    assertEquals(times(a), times(b))
                }
                assertEquals("+07:00", JSONObject(read(a, "samsung-records.jsonl").trim()).getJSONObject("time").getString("original_offset"))
                assertEquals(1, read(a, "series.jsonl").lineSequence().count { it.isNotBlank() })
                assertEquals(117.0, JSONObject(read(a, "recorded-display.jsonl").trim()).getDouble("display_mgdl"), 0.0)
                assertFalse(read(a, "journal.jsonl").contains("private-source-id"))
                assertTrue(read(a, "journal.jsonl").contains("complete note"))
                val manifest = JSONObject(read(a, "manifest.json"))
                assertTrue(manifest.getBoolean("partial_refresh"))
                val inventory = manifest.getJSONArray("files")
                for (n in 0 until inventory.length()) {
                    val item = inventory.getJSONObject(n)
                    val bytes = a.getInputStream(a.getEntry(item.getString("name"))).readBytes()
                    assertEquals(bytes.size.toLong(), item.getLong("bytes"))
                    assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, item.getString("sha256"))
                }
                assertNotNull(a.getEntry("record.schema.json"))
            } }
            System.getProperty("health.export.fixture")?.let { first.copyTo(File(it), overwrite = true) }
            first.delete(); second.delete(); Unit
        } finally { TimeZone.setDefault(previousZone); db.close(); context.deleteDatabase(databaseName); coordinator.store.close() }
    }
    @Test fun concurrentHistoryWriteCompletesAndInvalidatesFirstAttempt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "health-concurrent-${java.util.UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            db.historyDao().insertAll(listOf(HistoryReading(timestamp = 1000, sensorSerial = "a", value = 100f, rawValue = 100f, rate = null, firstStoredAt = 1)))
            var attempts = 0
            val rows = StableHistoryRead.run(db) {
                attempts++
                val first = db.historyDao().healthExportPage(0, Long.MAX_VALUE, 1000)
                if (attempts == 1) {
                    // A live writer must finish during export, without waiting for it to release a transaction.
                    kotlinx.coroutines.withTimeout(5000) {
                        db.historyDao().insertAll(listOf(HistoryReading(timestamp = 2000, sensorSerial = "b", value = 120f, rawValue = 120f, rate = null, firstStoredAt = 1)))
                    }
                }
                first
            }
            assertEquals(2, attempts)
            assertEquals(listOf(1000L, 2000L), rows.map { it.timestamp })
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun continuouslyChangingHistoryFailsInsteadOfReturningMixedPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "health-busy-${java.util.UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
        try {
            var attempts = 0
            val result = runCatching {
                StableHistoryRead.run(db) {
                    attempts++
                    db.historyDao().insertAll(listOf(HistoryReading(timestamp = attempts.toLong(), sensorSerial = "a", value = 100f, rawValue = 100f, rate = null, firstStoredAt = 1)))
                    db.historyDao().healthExportPage(0, Long.MAX_VALUE, 1000)
                }
            }
            assertTrue(result.isFailure)
            assertEquals(3, attempts)
            assertEquals(3, db.historyDao().healthExportPage(0, Long.MAX_VALUE, 1000).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun supportedSamsungVersionsUseOfficialMinimumRatherThanLexicalComparison() {
        assertFalse(samsungVersionSupported(null)); assertFalse(samsungVersionSupported("6.30.1"))
        assertFalse(samsungVersionSupported("6.9.99")); assertFalse(samsungVersionSupported("invalid"))
        assertTrue(samsungVersionSupported("6.30.2")); assertTrue(samsungVersionSupported("7.00.6.012"))
        assertTrue(samsungVersionSupported("8.0.0"))
    }
}
