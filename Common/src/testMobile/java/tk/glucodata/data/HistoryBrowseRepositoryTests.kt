package tk.glucodata.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HistoryBrowseRepositoryTests {
    private lateinit var database: HistoryDatabase
    private lateinit var repository: HistoryRepository
    private val start = 1_791_500_040_000L // aligned to a minute
    private val minute = 60_000L
    private val dao get() = database.historyDao()
    private fun reading(serial: String, minuteOffset: Int, value: Float = 120f) =
        HistoryReading(timestamp = start + minuteOffset * minute, sensorSerial = serial, value = value, rawValue = value - 5, rate = 1f)

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).allowMainThreadQueries().build()
        repository = HistoryRepository(database)
    }
    @After fun close() = database.close()
    private suspend fun twoSensors() {
        dao.insertAll((0..30).map { reading("GS1-P2", it, 130f + it) } + (0..30 step 3).map { reading("CT4", it, 100f + it) })
    }

    @Test fun singleSourcesHaveTheirOwnCadenceWithoutDeletingOtherReadings() = runBlocking {
        twoSensors()
        val yuwell = repository.observeBrowseWindow(listOf("CT4"), 0, Long.MAX_VALUE).first()
        val sibionics = repository.observeBrowseWindow(listOf("GS1-P2"), 0, Long.MAX_VALUE).first()
        assertEquals(11, yuwell.size); assertEquals(31, sibionics.size)
        assertTrue(yuwell.all { it.sensorSerial == "CT4" }); assertTrue(sibionics.all { it.sensorSerial == "GS1-P2" })
        assertTrue(yuwell.zipWithNext().all { (a, b) -> b.timestamp - a.timestamp == 3 * minute })
        assertTrue(sibionics.zipWithNext().all { (a, b) -> b.timestamp - a.timestamp == minute })
        assertEquals(42, dao.getCount())
    }

    @Test fun combinedPreservesBothValuesAtCoincidentTimestamps() = runBlocking {
        twoSensors()
        val merged = repository.observeBrowseWindow(listOf("CT4", "GS1-P2"), 0, Long.MAX_VALUE).first()
        assertEquals(42, merged.size)
        assertEquals(setOf(100f, 130f), merged.filter { it.timestamp == start }.map { it.value }.toSet())
        assertEquals(11, merged.groupBy { it.timestamp }.count { it.value.size == 2 })
        assertEquals(42, dao.getCount())
    }

    @Test fun allIncludesEndedAndImportedSourcesButSingleNeverImplicitlyAddsThem() = runBlocking {
        twoSensors(); dao.insertAll(listOf(reading("ended", -1440), reading(HistoryRepository.IMPORTED_SENSOR_SERIAL, 2)))
        assertEquals(setOf("CT4", "GS1-P2", "ended", HistoryRepository.IMPORTED_SENSOR_SERIAL), repository.observeBrowseSensors().first().toSet())
        assertEquals(44, repository.observeBrowseWindow(null, 0, Long.MAX_VALUE).first().size)
        assertEquals(11, repository.observeBrowseWindow(listOf("CT4"), 0, Long.MAX_VALUE).first().size)
        assertTrue(repository.observeBrowseWindow(emptyList(), 0, Long.MAX_VALUE).first().isEmpty())
        assertEquals(1, repository.observeBrowseWindow(listOf(HistoryRepository.IMPORTED_SENSOR_SERIAL), 0, Long.MAX_VALUE).first().size)
    }

    @Test fun summariesCountEachSourceAndRespectDateAndSensorFilters() = runBlocking {
        twoSensors(); dao.insert(reading("ended", -1440))
        val single = repository.observeBrowseSummary(listOf("CT4"), start + minute, start + 9 * minute).first()!!
        assertEquals(3, single.readingCount); assertEquals(start + 3 * minute, single.earliestMs); assertEquals(start + 9 * minute, single.latestMs)
        val combined = repository.observeBrowseSummary(listOf("CT4", "GS1-P2"), start, start + 30 * minute).first()!!
        assertEquals(42, combined.readingCount)
        val all = repository.observeBrowseSummary(null, 0, Long.MAX_VALUE).first()!!
        assertEquals(43, all.readingCount); assertEquals(start - 1440 * minute, all.earliestMs)
        assertNull(repository.observeBrowseSummary(emptyList(), 0, Long.MAX_VALUE).first())
    }

    @Test fun aliasesOfOnePhysicalSensorAreCoalescedButUnrelatedSensorsRemain() = runBlocking {
        dao.insertAll(listOf(reading("ABBOTT3#1YL08230BFY", 0), reading("1YL08230BFY", 0), reading("CT4", 0, 130f)))
        val all = repository.observeBrowseWindow(null, 0, Long.MAX_VALUE).first()
        assertEquals(2, all.size)
        assertEquals(2, repository.observeBrowseSummary(null, 0, Long.MAX_VALUE).first()!!.readingCount)
        assertEquals(1, repository.observeBrowseWindow(listOf("1YL08230BFY"), 0, Long.MAX_VALUE).first().size)
        assertEquals(3, dao.getCount())
    }

    @Test fun browsingLeavesEpochUnchangedWhenTimezoneChanges() = runBlocking {
        dao.insert(reading("CT4", 0))
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
            val a = repository.observeBrowseWindow(listOf("CT4"), start, start).first()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val b = repository.observeBrowseWindow(listOf("CT4"), start, start).first()
            assertEquals(start, a.single().timestamp); assertEquals(a.single().timestamp, b.single().timestamp)
        } finally { java.util.TimeZone.setDefault(previous) }
    }
}
