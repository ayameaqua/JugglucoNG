package tk.glucodata.drivers.anytime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.WearCalibrationBoundary

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class AnytimeWearPersistenceTest {
    private lateinit var context: Context
    private val id = "ANY:reused-transmitter"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("tk.glucodata_preferences", 0).edit().clear().commit()
        context.getSharedPreferences("wear_calibration_boundaries", 0).edit().clear().commit()
    }
    private fun previous() {
        AnytimeRegistry.saveQrContent(context, id, "a61061B")
        AnytimeRegistry.saveKValue(context, id, 1.06f)
        AnytimeRegistry.saveRValue(context, id, 1f)
        AnytimeRegistry.saveLastGlucoseId(context, id, 7000)
        AnytimeRegistry.saveTimelineStartAt(context, id, 1_780_000_000_000L)
        AnytimeRegistry.saveRawHistory(context, id, listOf(AnytimeRawRecord(0, 7000, .2f, 5f, 32f, byteArrayOf())))
        AnytimeRegistry.saveCalibratorState(context, id, AnytimeCalibrator.State(32f, 5f, 7000))
    }
    @Test fun unscannedNewProbeArchivesLegacyQrAndCannotRestoreOldParametersOrIds() {
        previous(); val old = AnytimeWearStore.session(context, id)
        val next = AnytimeWearStore.rollover(context, id, false)
        assertNotEquals(old, next)
        assertEquals(next, AnytimeWearStore.session(context, id))
        assertEquals("", AnytimeRegistry.loadQrContent(context, id))
        assertEquals(-1, AnytimeRegistry.loadLastGlucoseId(context, id))
        assertEquals(0L, AnytimeRegistry.loadTimelineStartAt(context, id))
        assertTrue(AnytimeRegistry.loadRawHistory(context, id).isEmpty())
        assertNull(AnytimeRegistry.loadCalibratorState(context, id))
        val p = context.getSharedPreferences("tk.glucodata_preferences", 0)
        assertEquals("a61061B", p.getString("anytime_archive_${old}_${AnytimeConstants.PREF_QR_CONTENT_PREFIX}$id", null))
        assertFalse(AnytimeWearStore.matched(context, id))
    }
    @Test fun explicitlyAssignedNewProbeQrSurvivesRolloverAndProcessRestore() {
        previous()
        AnytimeWearStore.bindQr(context, id, "AB34567", 0)
        assertEquals("AB34567", AnytimeRegistry.loadQrContent(context, id))
        assertNull(AnytimeRegistry.loadCalibratorState(context, id))
        AnytimeRegistry.saveQrContent(context, id, "AB34567")
        assertTrue(AnytimeWearStore.matched(context, id))
        val next = AnytimeWearStore.rollover(context, id, true)
        assertEquals(next, AnytimeWearStore.session(context, id))
        assertTrue(AnytimeWearStore.matched(context, id))
        assertEquals("AB34567", AnytimeRegistry.loadQrContent(context, id))
        assertNull(AnytimeRegistry.loadCalibratorState(context, id))
        assertTrue(context.getSharedPreferences("tk.glucodata_preferences", 0).all.any { (key, value) ->
            key.startsWith("anytime_qr_revision_") && (value as? String)?.contains("a61061B") == true
        })
    }
    @Test fun invalidQrCannotChangeCurrentProbeOrHistoricalMetadata() {
        previous()
        assertFalse(AnytimeRegistry.updateCurrentProbeQr(context, id, "invalid-qr"))
        assertEquals("a61061B", AnytimeRegistry.loadQrContent(context, id))
        assertEquals(7000, AnytimeRegistry.loadLastGlucoseId(context, id))
    }
    @Test fun sourceCatalogReadsCurrentAndArchivedWearAnchorsWithoutChangingProbeState() {
        assertTrue(AnytimeWearStore.knownSessions(context, id).isEmpty())
        previous(); val old = AnytimeWearStore.session(context, id)
        val next = AnytimeWearStore.rollover(context, id, false)
        AnytimeRegistry.saveTimelineStartAt(context, id, 1_790_000_000_000L)
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", 0)
        val before = prefs.all.toMap()
        assertEquals(mapOf(old to 1_780_000_000_000L, next to 1_790_000_000_000L), AnytimeWearStore.knownSessions(context, id))
        assertEquals(before, prefs.all.toMap())
    }
    @Test fun fingerprintCalibrationWindowsAreDisjointAndKeepPreviousWearAnchors() {
        WearCalibrationBoundary.begin(context, id, 2000L)
        WearCalibrationBoundary.begin(context, id, 4000L)
        val old = WearCalibrationBoundary.window(context, id, 3000L)!!
        val current = WearCalibrationBoundary.window(context, id, 5000L)!!
        assertTrue(2500L in old); assertFalse(4500L in old)
        assertFalse(2500L in current); assertTrue(4500L in current)
        assertEquals(current, WearCalibrationBoundary.window(context, id, 5000L))
    }
}
