package tk.glucodata.drivers.anytime

import org.junit.Assert.*
import org.junit.Test

class AnytimeManualHistoryRewriteTest {
    private fun task(qr: String? = "current-qr") = AnytimeManualHistoryRewrite("wear-a", qr, 1_800_000_000_000L, 501)
    private val family = AnytimeConstants.resolveFamily("SN8760000835")
    private fun raw(id: Int) = AnytimeRawRecord(0, id, .3f, 6f + id / 500f, 32f, byteArrayOf())

    @Test fun replacementRequiresFreshInputAndFullPrefixAndCapturedHorizon() {
        val task = task()
        assertFalse(task.canReplace(100, true))
        task.receive(100)
        assertTrue(task.canReplace(100, true))
        assertFalse(task.canReplace(100, false))
        task.receive(501); task.receive(-1)
        assertFalse(task.canReplace(501, true))
        assertFalse(task.canReplace(-1, true))
        assertTrue(task.matches("wear-a", "current-qr", task.timelineStartMs))
        assertFalse(task.matches("wear-b", "current-qr", task.timelineStartMs))
        assertFalse(task.matches("wear-a", "new-qr", task.timelineStartMs))
        assertFalse(task.matches("wear-a", "current-qr", task.timelineStartMs + 75_000))
    }

    @Test fun restartRetainsConsentInputSetAndResultWhileFinishDisablesOverwrite() {
        val original = task(null).apply { receive(300); receive(400); didWrite(listOf(300)) }
        val restored = AnytimeManualHistoryRewrite.restore(original.json())!!
        assertTrue(restored.matches("wear-a", null, original.timelineStartMs))
        assertTrue(restored.canReplace(400, true))
        assertFalse(restored.canReplace(399, true))
        restored.finish()
        assertFalse(restored.canReplace(400, true))
        assertTrue(restored.status.contains("重新读取 2 条"))
        assertTrue(restored.status.contains("写入 1 条"))
        assertFalse(AnytimeManualHistoryRewrite.restore(restored.json())!!.active)
    }

    @Test fun cancellationAndCorruptPersistenceNeverGrantOverwrite() {
        val task = task().apply { receive(300); cancel("QR changed") }
        assertFalse(task.canReplace(300, true))
        assertNull(AnytimeManualHistoryRewrite.restore("broken"))
        assertNull(AnytimeManualHistoryRewrite.restore(task.json().replace("\"stop\":501", "\"stop\":100001")))
        assertTrue(AnytimeManualHistoryRewrite.requiresFreshRead("${AnytimeManualHistoryRewrite.REASON}(resumed)"))
        assertFalse(AnytimeManualHistoryRewrite.requiresFreshRead("post-reset(reconnect)"))
    }

    @Test fun newParametersReplaceSamePriorityWithoutWeakeningAutomaticProtection() {
        val record = raw(500)
        val old = AnytimeAlgorithm.replayCt4History((0..500).map(::raw), null, family).last()
        val qr = AnytimeQr.parse("a61061B")!!
        val corrected = AnytimeAlgorithm.replayCt4History((0..500).map(::raw), qr, family).last()
        assertNotEquals(old.mgdlTimes10, corrected.mgdlTimes10)
        val buffer = AnytimeHistoryRoomImportBuffer()
        val sample = anytimeTimelineSampleMs(task().timelineStartMs, record.glucoseId, 180_000L, 0)
        assertTrue(buffer.queue(sample, old))
        buffer.markImported(buffer.drain())
        assertFalse(buffer.queue(sample, corrected))
        assertTrue(buffer.queue(sample, corrected, replaceExisting = true))
        val replacement = buffer.drain().single()
        assertEquals(sample, replacement.reading.timestampMs)
        assertEquals(corrected.mgdl, replacement.reading.glucoseMgdl, 0f)
    }

    @Test fun reverseAndRepeatedReadsUseAnIsolatedModelAndRetainGaps() {
        val id = "manual-rewrite-live-model"
        AnytimeAlgorithm.clearCalibratorState(id)
        AnytimeAlgorithm.compute(raw(500), null, family, "SN8760000835", 1_800_000_000_000,
            persistentSensorId = id, advanceModelFallback = true)
        val before = AnytimeAlgorithm.snapshotCalibratorState(id)
        val task = task()
        val records = (0..100).map(::raw) + (200..500).map(::raw)
        records.reversed().forEach { task.receive(it.glucoseId) }
        repeat(2) {
            val outputs = AnytimeAlgorithm.replayCt4AvailableHistory(records.reversed(), AnytimeQr.parse("a61061B"), family)
            assertTrue(task.canReplace(100, outputs.getValue(100).historyCompletePrefix))
            assertFalse(task.canReplace(500, outputs.getValue(500).historyCompletePrefix))
            assertEquals(before, AnytimeAlgorithm.snapshotCalibratorState(id))
        }
        val complete = AnytimeAlgorithm.replayCt4AvailableHistory((0..500).map(::raw), AnytimeQr.parse("a61061B"), family)
        assertTrue(task.canReplace(500, complete.getValue(500).historyCompletePrefix))
        assertEquals(before, AnytimeAlgorithm.snapshotCalibratorState(id))
        AnytimeAlgorithm.clearCalibratorState(id)
    }

    @Test fun pendingProbeRollbackIsDistinctFromAnOrdinaryReconnect() {
        val policy = AnytimeWearPolicy()
        assertFalse(policy.hasPendingRollover())
        policy.observe(1, 7000, 1_780_000_000_000, 1_782_000_000_000, 180_000)
        assertTrue(policy.hasPendingRollover())
        policy.observe(7001, 7000, 1_780_000_000_000, 1_782_000_180_000, 180_000)
        assertFalse(policy.hasPendingRollover())
    }
}
