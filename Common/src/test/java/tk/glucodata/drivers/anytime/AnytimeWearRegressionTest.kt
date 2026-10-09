package tk.glucodata.drivers.anytime

import org.junit.Assert.*
import org.junit.Test

class AnytimeWearRegressionTest {
    private val interval = 180_000L
    private val oldStart = 1_780_000_000_000L
    private val now = oldStart + 16 * 86400_000L
    private val family = AnytimeConstants.resolveFamily("SN8760000835")
    private fun raw(id: Int, temp: Float = 32f) = AnytimeRawRecord(0, id, .3f, 5f + id / 500f, temp, byteArrayOf())
    private fun compute(id: String, record: AnytimeRawRecord, live: Boolean, prefix: List<AnytimeRawRecord> = listOf(record)) = AnytimeAlgorithm.compute(
        record, null, family, "SN8760000835", now, persistentSensorId = id,
        advanceModelFallback = live, recentRecords = prefix)

    @Test fun isolatedRollbackAndDuplicateCallbacksNeverConfirmNewWear() {
        val policy = AnytimeWearPolicy()
        repeat(10) { assertEquals(AnytimeWearPolicy.Decision.QUARANTINE, policy.observe(2, 7000, oldStart, now + it, interval)) }
        assertEquals(AnytimeWearPolicy.Decision.ACCEPT, policy.observe(7001, 7000, oldStart, now, interval))
        assertEquals(AnytimeWearPolicy.Decision.QUARANTINE, policy.observe(3, 7001, oldStart, now + interval, interval))
    }
    @Test fun threeAscendingLiveIdsWithElapsedTimeConfirmReuse() {
        val policy = AnytimeWearPolicy()
        assertEquals(AnytimeWearPolicy.Decision.QUARANTINE, policy.observe(1, 7000, oldStart, now, interval))
        assertEquals(AnytimeWearPolicy.Decision.QUARANTINE, policy.observe(2, 7000, oldStart, now + interval, interval))
        assertEquals(AnytimeWearPolicy.Decision.NEW_WEAR, policy.observe(3, 7000, oldStart, now + interval * 2, interval))
    }
    @Test fun fastBatchOutOfOrderAndSmallRollbackCannotCreateWear() {
        val policy = AnytimeWearPolicy()
        for (id in listOf(1,2,3)) assertEquals(AnytimeWearPolicy.Decision.QUARANTINE, policy.observe(id, 7000, oldStart, now, interval))
        assertEquals(AnytimeWearPolicy.Decision.ACCEPT, policy.observe(6998, 7000, oldStart, now, interval))
        assertEquals(AnytimeWearPolicy.Decision.QUARANTINE, policy.observe(3, 7000, oldStart, now, interval))
        assertEquals(AnytimeWearPolicy.Decision.QUARANTINE, policy.observe(2, 7000, oldStart, now + interval, interval))
    }
    @Test fun historyReplayAndDuplicatePushLeaveLiveMk4ExactlyUnchanged() {
        val id = "live-isolation"
        AnytimeAlgorithm.clearCalibratorState(id)
        compute(id, raw(500), true); compute(id, raw(501), true)
        val before = AnytimeAlgorithm.snapshotCalibratorState(id)
        val history = (0..100).map { raw(it, 30f + it / 200f) }
        for (index in listOf(100, 3, 2, 99, 100)) {
            assertEquals(AnytimeAlgorithm.Source.MODEL, compute(id, history[index], false, history).source)
            assertEquals(before, AnytimeAlgorithm.snapshotCalibratorState(id))
        }
        compute(id, raw(501, 10f), true, history)
        assertEquals(before, AnytimeAlgorithm.snapshotCalibratorState(id))
        val model = AnytimeCalibrator(AnytimeConstants.CT4_DEFAULT_K0).apply { restoreState(before!!) }
        val expected = model.computeNext(raw(502))
        assertEquals(expected.coerceAtLeast(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat()), compute(id, raw(502), true).mmol, .00001f)
    }
    @Test fun orderedHistoryIsDeterministicAcrossTailFirstAndRepeatedPulls() {
        val records = (0..200).map { raw(it) }
        val tail = compute("tail-first", records[200], false, records.takeLast(20))
        val repaired = compute("tail-first", records[200], false, records)
        val fresh = compute("full-replay", records[200], false, records)
        assertEquals(fresh.mgdlTimes10, repaired.mgdlTimes10)
        assertEquals(repaired.mgdlTimes10, compute("tail-first", records[200], false, records).mgdlTimes10)
        assertNull(AnytimeAlgorithm.snapshotCalibratorState("tail-first"))
        assertEquals(AnytimeAlgorithm.Source.MODEL, tail.source)
    }
    @Test fun onePassSparseHistoryMatchesContiguousReplayWithoutTouchingLiveFilter() {
        val id = "sparse-batch"
        compute(id, raw(500), true)
        val before = AnytimeAlgorithm.snapshotCalibratorState(id)
        val records = (0..50).map(::raw) + (100..150).map(::raw)
        val batch = AnytimeAlgorithm.replayCt4AvailableHistory(records.reversed(), null, family)
        assertEquals(compute("separate-tail", raw(150), false, (100..150).map(::raw)).mgdlTimes10, batch.getValue(150).mgdlTimes10)
        assertTrue(batch.getValue(50).historyCompletePrefix); assertFalse(batch.getValue(150).historyCompletePrefix)
        assertEquals(before, AnytimeAlgorithm.snapshotCalibratorState(id))
    }
    @Test fun manualUdiAndDefaultAreNotFactoryQrAndUdiDoesNotChangeMk4K0() {
        val manual = AnytimeQr.parse("a61061B")!!
        assertEquals(AnytimeQrCalibration.Format.MANUAL, manual.format)
        assertFalse(manual.isFactoryCalibration); assertTrue(manual.hasAlgorithmCalibration)
        val udi = AnytimeQr.parse("0116975124206236112602191728021910CQ6212")!!
        assertFalse(udi.isFactoryCalibration); assertFalse(udi.hasAlgorithmCalibration)
        assertEquals(AnytimeConstants.CT4_DEFAULT_K0, AnytimeAlgorithm.effectiveModelK0(udi), 0f)
        assertFalse(AnytimeAlgorithm.canRestoreCt4State(udi, null))
        assertFalse(AnytimeAlgorithm.canRestoreCt4State(udi, .30f))
        assertTrue(AnytimeAlgorithm.canRestoreCt4State(udi, AnytimeConstants.CT4_DEFAULT_K0))
        assertTrue(AnytimeAlgorithm.canRestoreCt4State(manual, null))
        assertTrue(AnytimeAlgorithm.canRestoreCt4State(null, null))
    }
    @Test fun appRestartAndTimezoneChangeKeepEpochAnchorAndLiveContinuity() {
        val key = "restart-model"
        compute(key, raw(600), true)
        val checkpoint = AnytimeAlgorithm.snapshotCalibratorState(key)!!
        val expected = compute(key, raw(601), true)
        AnytimeAlgorithm.clearCalibratorState(key)
        AnytimeAlgorithm.restoreCalibratorState(key, AnytimeConstants.CT4_DEFAULT_K0, checkpoint)
        assertEquals(expected.mgdlTimes10, compute(key, raw(601), true).mgdlTimes10)
        assertEquals(oldStart + 601 * interval, anytimeTimelineSampleMs(oldStart, 601, interval, now))
    }
}
