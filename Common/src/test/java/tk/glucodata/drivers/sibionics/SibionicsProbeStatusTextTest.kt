package tk.glucodata.drivers.sibionics

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class SibionicsProbeStatusTextTest {
    private val code = "EU2VCZUQPSHD5Q"
    private val start = 1_791_500_040_000L
    private val saved = SibionicsProbeStatusText.Saved("SIBI:TEST", start, code, start + 60_000L, false)
    private val runtime = SibionicsProbeStatusText.Runtime(code, 1.73f, false, false,
        "V116A · STOCK", true, 120, 1.69f, false, -1, false, false, true)

    @Test fun offlineSavedQrDoesNotClaimRunningAdoptionOrCompletedHistory() {
        val text = SibionicsProbeStatusText.format(saved)
        assertTrue(text.contains("工厂初始灵敏度：1.73"))
        assertTrue(text.contains("驱动尚未运行"))
        assertFalse(text.contains("当前连续算法已采用"))
        assertFalse(text.contains("完成的历史重算记录"))
    }

    @Test fun fullProbeCodeIsHiddenUnlessExplicitlyRequested() {
        val hidden = SibionicsProbeStatusText.format(saved, runtime)
        assertFalse(hidden.contains(code))
        assertTrue(hidden.contains("二维码标识："))
        val visible = SibionicsProbeStatusText.format(saved, runtime, showCode = true)
        assertTrue(visible.contains("已保存探头码：$code"))
        assertEquals(period(hidden), period(visible))
    }

    @Test fun liveFactoryAndDynamicInternalSensitivityAreShownSeparately() {
        val text = SibionicsProbeStatusText.format(saved, runtime)
        assertTrue(text.contains("工厂初始灵敏度：1.73"))
        assertTrue(text.contains("运行初始灵敏度：1.73 · 已载入的探头工厂码"))
        assertTrue(text.contains("内部有效灵敏度：1.690"))
        assertTrue(text.contains("ID=120"))
        assertTrue(text.contains("当前连续算法已采用"))
    }

    @Test fun codeSavedAheadOfDriverRetainsTheActualOldRuntimeValue() {
        val text = SibionicsProbeStatusText.format(saved,
            runtime.copy(code = null, configuredSensitivity = 1.44f, activeSensitivity = null))
        assertTrue(text.contains("运行初始灵敏度：1.44 · 备用默认参数"))
        assertTrue(text.contains("参数已保存，等待运行驱动接收"))
        assertFalse(text.contains("当前连续算法已采用"))
        assertFalse(text.contains("本机已有按当前参数完成"))
    }

    @Test fun loadedCodeWithStaleCalculatorCannotBeReportedAsAdopted() {
        val text = SibionicsProbeStatusText.format(saved, runtime.copy(configuredSensitivity = 1.44f))
        assertTrue(text.contains("运行计算器仍待更新"))
        assertTrue(text.contains("等待计算器加载新参数"))
        assertFalse(text.contains("当前连续算法已采用"))
    }

    @Test fun manualOverrideWinsAndTheFactoryValueRemainsVisible() {
        val text = SibionicsProbeStatusText.format(saved,
            runtime.copy(configuredSensitivity = 1.50f, manualOverride = true))
        assertTrue(text.contains("工厂初始灵敏度：1.73"))
        assertTrue(text.contains("运行初始灵敏度：1.50 · 手动覆盖"))
        assertTrue(text.contains("实际计算采用手动覆盖值"))
        assertFalse(text.contains("运行计算器仍待更新"))
    }

    @Test fun missingAndInvalidOldCodesAreNotPresentedAsFactoryParameters() {
        for (invalid in listOf(null, "P225043JMV", "EU2VCZUQPSHD50")) {
            val text = SibionicsProbeStatusText.format(saved.copy(code = invalid, confirmedAtMs = 0),
                runtime.copy(code = invalid, configuredSensitivity = 1.44f))
            assertTrue(text.contains("工厂初始灵敏度：未知"))
            assertTrue(text.contains("备用默认参数"))
            assertTrue(text.contains("当前计算缺少本探头工厂码"))
            assertFalse(text.contains("用户明确指定当前探头"))
            assertFalse(text.contains("当前连续算法已采用"))
        }
    }

    @Test fun setupOnlyAndPendingNewProbeDoNotClaimConfirmedOwnership() {
        val legacy = SibionicsProbeStatusText.format(saved.copy(confirmedAtMs = 0), runtime)
        assertTrue(legacy.contains("当前探头归属待核对"))
        assertFalse(legacy.contains("用户明确指定当前探头"))
        val pending = SibionicsProbeStatusText.format(saved.copy(probeChangePending = true), runtime)
        assertTrue(pending.contains("不能确认旧码适用于当前探头"))
        assertFalse(pending.contains("当前连续算法已采用"))
        assertFalse(pending.contains("本机已有按当前参数完成"))
    }

    @Test fun recoveryAndMissingInputOverridePreviouslyCompletedHistoryStatus() {
        val recovering = SibionicsProbeStatusText.format(saved, runtime.copy(recovering = true))
        assertTrue(recovering.contains("先恢复连续算法"))
        assertFalse(recovering.contains("当前连续算法已采用"))
        assertFalse(recovering.contains("本机已有按当前参数完成"))
        val missing = SibionicsProbeStatusText.format(saved, runtime.copy(missingSourceIndex = 65))
        assertTrue(missing.contains("原始采样待补齐，从 ID=65 继续"))
        assertFalse(missing.contains("本机已有按当前参数完成"))
    }

    @Test fun backfillAndPendingRebuildNeverAppearAsCompleted() {
        val backfill = SibionicsProbeStatusText.format(saved, runtime.copy(historyTransferActive = true))
        assertTrue(backfill.contains("重算等待回填稳定"))
        assertFalse(backfill.contains("本机已有按当前参数完成"))
        val pending = SibionicsProbeStatusText.format(saved, runtime.copy(rebuildPending = true))
        assertTrue(pending.contains("连续重算尚未完成"))
        assertFalse(pending.contains("本机已有按当前参数完成"))
        val unrecorded = SibionicsProbeStatusText.format(saved, runtime.copy(historyConfigurationMatches = false))
        assertTrue(unrecorded.contains("本机尚无按当前参数完成"))
    }

    @Test fun qrCorrectionDoesNotChangeTheWearReferenceButNewStartDoes() {
        val original = SibionicsProbeStatusText.format(saved)
        val corrected = SibionicsProbeStatusText.format(saved.copy(code = "145TUMXYK4S46V", confirmedAtMs = start + 120_000L))
        assertEquals(period(original), period(corrected))
        assertNotEquals(period(original), period(SibionicsProbeStatusText.format(saved.copy(startTimeMs = start + 60_000L))))
        assertTrue(SibionicsProbeStatusText.format(saved.copy(startTimeMs = 0)).contains("周期参考标识：尚未确定"))
    }

    @Test fun changingDisplayTimezoneDoesNotChangeWearIdentityOrPersistedEpoch() {
        val shanghai = SibionicsProbeStatusText.format(saved, timeZone = TimeZone.getTimeZone("GMT+08:00"))
        val bangkok = SibionicsProbeStatusText.format(saved, timeZone = TimeZone.getTimeZone("GMT+07:00"))
        assertEquals(period(shanghai), period(bangkok))
        assertTrue(shanghai.contains("+08:00")); assertTrue(bangkok.contains("+07:00"))
        assertEquals(start, saved.startTimeMs)
        assertEquals(start + 60_000L, saved.confirmedAtMs)
    }

    private fun period(text: String) = text.lineSequence().first()
}
