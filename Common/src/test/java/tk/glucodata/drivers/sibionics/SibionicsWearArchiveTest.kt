package tk.glucodata.drivers.sibionics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class SibionicsWearArchiveTest {
    private lateinit var context: Context
    private val id = "GS1-P2:reused"
    private val codeKey get() = "sibionics_managed_probe_code_$id"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("tk.glucodata_preferences", 0).edit().clear().putString(codeKey, "previous-probe")
            .putLong("sibionics_probe_qr_at_$id", 1_780_000_000_000L)
            .putFloat("sibionics_managed_algorithm_sensitivity_$id", 1.3f)
            .putInt("sibionics_managed_last_index_$id", 20000).commit()
    }
    @Test fun unexplainedRestartInvalidatesOldCodeAndOverrideButArchivesMetadata() {
        SibionicsRegistry.archiveProbe(context, id, true)
        val p = context.getSharedPreferences("tk.glucodata_preferences", 0)
        assertNull(SibionicsRegistry.loadProbeCode(context, id))
        assertFalse(p.contains("sibionics_managed_algorithm_sensitivity_$id"))
        assertEquals(0, SibionicsRegistry.loadLastIndex(context, id))
        assertFalse(p.contains("sibionics_probe_qr_at_$id"))
        assertTrue(p.all.any { (k, v) -> k.startsWith("sibionics_archive_") &&
            k.endsWith("sibionics_probe_qr_at_$id") && v == 1_780_000_000_000L })
        assertTrue(p.all.any { (k, v) -> k.startsWith("sibionics_archive_") && k.endsWith(codeKey) && v == "previous-probe" })
    }
    @Test fun explicitlyNewCodeKeepsNewIdentityButDoesNotInheritManualSensitivity() {
        val p = context.getSharedPreferences("tk.glucodata_preferences", 0)
        p.edit().putString(codeKey, "new-probe").putString("sibionics_previous_probe_$id", "previous-probe").commit()
        SibionicsRegistry.archiveProbe(context, id, false)
        assertEquals("new-probe", SibionicsRegistry.loadProbeCode(context, id))
        assertFalse(p.contains("sibionics_probe_qr_at_$id"))
        assertFalse(p.contains("sibionics_managed_algorithm_sensitivity_$id"))
        assertTrue(p.all.any { (k, v) -> k.startsWith("sibionics_archive_") && k.endsWith(codeKey) && v == "previous-probe" })
    }
    @Test fun bleCallbackAliasCannotRestoreCanonicalOldProbeAfterConfirmedRestart() {
        val ble = "P225043JMV"
        val variant = SibionicsConstants.Variant.SIBIONICS2
        val active = SibionicsRegistry.ensureSensorRecord(context, ble, null, ble, variant)
        val qr = "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q"
        val scanned = SibionicsRegistry.ensureSensorRecord(context, qr, null, null, variant, bleNameOverride = ble)
        assertEquals("EU2VCZUQPSHD5Q", SibionicsRegistry.loadProbeCode(context, active.sensorId))
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", 0)
        prefs.edit().putFloat("sibionics_managed_algorithm_sensitivity_${scanned.sensorId}", 1.5f).commit()
        SibionicsRegistry.archiveProbe(context, active.sensorId, true)
        assertNull(SibionicsRegistry.loadProbeCode(context, active.sensorId))
        assertNull(SibionicsRegistry.loadProbeCode(context, scanned.sensorId))
        assertFalse(prefs.contains("sibionics_managed_algorithm_sensitivity_${scanned.sensorId}"))
        SibionicsRegistry.saveStartTimeMs(context, active.sensorId, 10_000L)
        assertFalse(5000L in tk.glucodata.WearCalibrationBoundary.window(context, scanned.sensorId, 20_000L)!!)
    }
}
