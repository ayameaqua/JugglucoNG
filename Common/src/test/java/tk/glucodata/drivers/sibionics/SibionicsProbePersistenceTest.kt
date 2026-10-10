package tk.glucodata.drivers.sibionics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class SibionicsProbePersistenceTest {
    @Test
    fun activeBleAliasResolvesCalibrationAfterQrReplacesItsRecord() {
        val context = PrefsContext(FakePreferences())
        val variant = SibionicsConstants.Variant.SIBIONICS2
        val ble = "P225043JMV"
        val active = SibionicsRegistry.ensureSensorRecord(context, ble, null, ble, variant)
        assertNull(SibionicsRegistry.loadProbeCode(context, active.sensorId))
        val qr = "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q"
        val scanned = SibionicsRegistry.ensureSensorRecord(context, qr, null, null, variant, bleNameOverride = ble)
        assertNotEquals(active.sensorId, scanned.sensorId)
        assertEquals(1, SibionicsRegistry.persistedRecords(context).size)
        val probe = SibionicsRegistry.loadProbeCode(context, active.sensorId)
        assertEquals("EU2VCZUQPSHD5Q", probe)
        assertEquals(1.73f, SibionicsSensitivity.sensitivityFor(active.shortCode, variant, probe), 0.00001f)
    }

    @Test
    fun scannedCalibrationSurvivesAddressWritesAndSessionRestart() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val variant = SibionicsConstants.Variant.SIBIONICS2
        val qr = "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q"
        val record = SibionicsRegistry.ensureSensorRecord(context, qr, null, null, variant)
        assertEquals("SIBI:VCZUQPSHD5Q", record.sensorId)
        assertEquals("VCZUQPSH", record.shortCode)
        SibionicsRegistry.ensureSensorRecord(context, record.displayName, "AA:BB:CC:DD:EE:FF",
            record.displayName, variant, shortCodeOverride = record.shortCode)
        SibionicsRegistry.saveSessionRestart(context, record.sensorId, byteArrayOf(9))
        val restored = SibionicsRegistry.findRecord(context, record.sensorId)!!
        val probe = SibionicsRegistry.loadProbeCode(context, restored.sensorId)
        assertEquals("EU2VCZUQPSHD5Q", probe)
        assertEquals(1.73f, SibionicsSensitivity.sensitivityFor(restored.shortCode, restored.variant, probe), 0.00001f)
        assertNull(SibionicsRegistry.loadProbeCode(context, "SIBI:OTHER"))
    }

    @Test
    fun bindingCurrentProbeKeepsTransmitterWearAndHistoryWhileInvalidatingOldCoefficientState() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val ble = "P225043JMV"
        val record = SibionicsRegistry.ensureSensorRecord(context, ble, "AA:BB:CC:DD:EE:FF", ble,
            SibionicsConstants.Variant.SIBIONICS2)
        val id = record.sensorId
        val start = 1_790_000_000_000L
        SibionicsRegistry.saveStartTimeMs(context, id, start)
        SibionicsRegistry.saveAlgorithmCheckpoint(context, id, 700, byteArrayOf(1, 2, 3))
        SibionicsRegistry.saveLocalRebuildFingerprint(context, id, "old-default")
        SibionicsRegistry.saveLastReading(context, id, start + 699 * 60_000L, 100f, 110f)
        prefs.values["user_setting"] = "preserved"
        prefs.values["journal_history_marker"] = "preserved"
        val reading = SibionicsRegistry.loadLastReading(context, id)

        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, id, "EU2VCZUQPSHD5Q", at = start + 700 * 60_000L))

        assertEquals(listOf(record), SibionicsRegistry.persistedRecords(context))
        assertEquals("EU2VCZUQPSHD5Q", SibionicsRegistry.loadProbeCode(context, id))
        assertEquals(start, SibionicsRegistry.loadStartTimeMs(context, id))
        assertEquals(700, SibionicsRegistry.loadLastIndex(context, id))
        assertEquals(reading, SibionicsRegistry.loadLastReading(context, id))
        assertNull(SibionicsRegistry.loadAlgorithmState(context, id))
        assertEquals("", SibionicsRegistry.loadLocalRebuildFingerprint(context, id))
        assertEquals("preserved", prefs.values["user_setting"])
        assertEquals("preserved", prefs.values["journal_history_marker"])
        assertFalse(SibionicsRegistry.probeChanged(context, id))
        assertFalse(prefs.values.keys.any { it.startsWith("sibionics_archive_") || it.startsWith("sibionics_new_probe_clock_") })
    }

    @Test
    fun currentProbeCorrectionArchivesOldQrWithoutStartingAnotherWearAndRescanIsIdempotent() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val record = SibionicsRegistry.ensureSensorRecord(context, "P225043JMV", null, "P225043JMV",
            SibionicsConstants.Variant.SIBIONICS2)
        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, "EU2VCZUQPSHD5Q", at = 100))
        SibionicsRegistry.saveStartTimeMs(context, record.sensorId, 1_790_000_000_000L)
        SibionicsRegistry.saveLastIndex(context, record.sensorId, 900)
        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, "145TUMXYK4S46V", at = 200))
        assertEquals("145TUMXYK4S46V", SibionicsRegistry.loadProbeCode(context, record.sensorId))
        assertEquals(900, SibionicsRegistry.loadLastIndex(context, record.sensorId))
        assertEquals(1_790_000_000_000L, SibionicsRegistry.loadStartTimeMs(context, record.sensorId))
        assertEquals(listOf("EU2VCZUQPSHD5Q"), prefs.values.filterKeys { it.startsWith("sibionics_probe_qr_revision_") }.values.toList())
        assertFalse(SibionicsRegistry.probeChanged(context, record.sensorId))
        val before = HashMap(prefs.values)
        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, "145TUMXYK4S46V", at = 300))
        assertEquals(before, prefs.values)
    }

    @Test
    fun explicitSensitivityOverrideIsPreservedAndKeepsItsContinuationState() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val record = SibionicsRegistry.ensureSensorRecord(context, "P225043JMV", null, "P225043JMV",
            SibionicsConstants.Variant.SIBIONICS2)
        val id = record.sensorId
        SibionicsRegistry.saveAlgorithmSensitivityOverride(context, id, 1.50f)
        val state = byteArrayOf(9, 8, 7)
        SibionicsRegistry.saveAlgorithmCheckpoint(context, id, 100, state)
        SibionicsRegistry.saveLocalRebuildFingerprint(context, id, "explicit-1.50")
        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, id, "EU2VCZUQPSHD5Q"))
        assertEquals(1.50f, SibionicsRegistry.loadAlgorithmSensitivityOverride(context, id)!!, 0f)
        assertArrayEquals(state, SibionicsRegistry.loadAlgorithmState(context, id))
        assertEquals("explicit-1.50", SibionicsRegistry.loadLocalRebuildFingerprint(context, id))
    }

    @Test
    fun confirmingSameLegacyCodeRecordsBindingWithoutRebuildingOrChangingWear() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val qr = "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q"
        val record = SibionicsRegistry.ensureSensorRecord(context, qr, null, null, SibionicsConstants.Variant.SIBIONICS2)
        val state = byteArrayOf(7, 8)
        SibionicsRegistry.saveAlgorithmCheckpoint(context, record.sensorId, 400, state)
        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, qr, at = 12345))
        assertEquals(12345L, prefs.values["sibionics_probe_qr_at_${record.sensorId}"])
        assertArrayEquals(state, SibionicsRegistry.loadAlgorithmState(context, record.sensorId))
        assertEquals(400, SibionicsRegistry.loadLastIndex(context, record.sensorId))
        val before = HashMap(prefs.values)
        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, qr, at = 23456))
        assertEquals(before, prefs.values)
    }

    @Test
    fun knownRuntimeAliasCoefficientCheckpointIsInvalidatedToo() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val record = SibionicsRegistry.ensureSensorRecord(context, "P225043JMV", null, "P225043JMV",
            SibionicsConstants.Variant.SIBIONICS2)
        val alias = record.displayName
        SibionicsRegistry.saveAlgorithmCheckpoint(context, alias, 600, byteArrayOf(1))
        SibionicsRegistry.saveLocalRebuildFingerprint(context, alias, "old-default")
        assertTrue(SibionicsRegistry.persistCurrentProbeQr(context, alias, "EU2VCZUQPSHD5Q", aliases = setOf(alias)))
        assertEquals("EU2VCZUQPSHD5Q", SibionicsRegistry.loadProbeCode(context, alias))
        assertEquals(listOf(record), SibionicsRegistry.persistedRecords(context))
        assertNull(SibionicsRegistry.loadAlgorithmState(context, alias))
        assertEquals(600, SibionicsRegistry.loadLastIndex(context, alias))
        assertEquals("", SibionicsRegistry.loadLocalRebuildFingerprint(context, alias))
    }

    @Test
    fun invalidConnectionQrPendingNewWearAndOtherVariantDoNotChangePreferences() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val record = SibionicsRegistry.ensureSensorRecord(context, "P225043JMV", null, "P225043JMV",
            SibionicsConstants.Variant.SIBIONICS2)
        for (invalid in listOf("P225043JMV", "EU2VCZUQPSHD50", "")) {
            val before = HashMap(prefs.values)
            assertFalse(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, invalid))
            assertEquals(before, prefs.values)
        }
        prefs.values["sibionics_probe_changed_${record.sensorId}"] = true
        val pending = HashMap(prefs.values)
        assertFalse(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, "EU2VCZUQPSHD5Q"))
        assertEquals(pending, prefs.values)
        val other = SibionicsRegistry.ensureSensorRecord(context, "GEPD802J", null, "GEPD802J", SibionicsConstants.Variant.CHINESE)
        val before = HashMap(prefs.values)
        assertFalse(SibionicsRegistry.persistCurrentProbeQr(context, other.sensorId, "EU2VCZUQPSHD5Q"))
        assertFalse(SibionicsRegistry.persistCurrentProbeQr(context, "unknown", "EU2VCZUQPSHD5Q"))
        assertEquals(before, prefs.values)
    }

    @Test
    fun failedAtomicCommitCannotPartiallyReplaceFactoryCodeOrClearCheckpoint() {
        val prefs = FakePreferences(commitSucceeds = false)
        val context = PrefsContext(prefs)
        val record = SibionicsRegistry.ensureSensorRecord(context, "P225043JMV", null, "P225043JMV",
            SibionicsConstants.Variant.SIBIONICS2)
        SibionicsRegistry.saveAlgorithmCheckpoint(context, record.sensorId, 400, byteArrayOf(1, 2))
        val before = HashMap(prefs.values)
        assertFalse(SibionicsRegistry.persistCurrentProbeQr(context, record.sensorId, "EU2VCZUQPSHD5Q"))
        assertEquals(before, prefs.values)
    }

    private class PrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    /** In-memory SharedPreferences; an editor's changes land together, on apply or commit. */
    private class FakePreferences(private val commitSucceeds: Boolean = true) : SharedPreferences {
        val values = HashMap<String, Any?>()
        var commits = 0

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            private var clear = false
            private fun put(key: String?, value: Any?) = apply { puts[key!!] = value; removes.remove(key) }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = apply { removes += key!!; puts.remove(key) }
            override fun clear() = apply { clear = true }
            private fun land() {
                if (clear) values.clear()
                removes.forEach(values::remove)
                values.putAll(puts)
            }
            override fun commit(): Boolean {
                commits++
                if (!commitSucceeds) return false
                land()
                return true
            }
            override fun apply() = land()
        }
    }
}
