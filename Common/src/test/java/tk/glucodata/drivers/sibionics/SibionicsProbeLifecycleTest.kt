package tk.glucodata.drivers.sibionics

import org.junit.Assert.*
import org.junit.Test

class SibionicsProbeLifecycleTest {
    @Test fun firstBindingOfPreviouslyUnknownProbeDoesNotProveAnotherWear() {
        val code = "EU2VCZUQPSHD5Q"
        assertFalse(SibionicsSessionPolicy.probeIdentityChanged(null, code))
        assertFalse(SibionicsSessionPolicy.probeIdentityChanged("", code))
        assertFalse(SibionicsSessionPolicy.probeIdentityChanged(" ", code))
        assertTrue(SibionicsSessionPolicy.probeIdentityChanged(code, "145TUMXYK4S46V"))
    }
    @Test fun differentProbeCodesWithIdenticalFactorySensitivityStillChangeIdentity() {
        val fixture = javaClass.getResourceAsStream("/sibionics/probe-sensitivity-native.tsv")!!.bufferedReader().use { it.readLines() }
        val codes = fixture.filter { !it.startsWith("#") && it.isNotBlank() }.map { it.split('\t') }.distinctBy { it[0] }.groupBy { it[1] }.values.first { it.size > 1 }
        val a = codes[0][0]; val b = codes[1][0]
        assertNotEquals(a, b)
        assertEquals(SibionicsProbeSensitivity.tryDecode(a), SibionicsProbeSensitivity.tryDecode(b))
        assertTrue(SibionicsSessionPolicy.probeIdentityChanged(a, b))
        assertFalse(SibionicsSessionPolicy.probeIdentityChanged(a, a))
        assertFalse(SibionicsSessionPolicy.probeIdentityChanged(a, null))
    }
    @Test fun confirmedUnexplainedIndexRestartInvalidatesProbeButOrdinaryReconnectDoesNotProveRestart() {
        assertTrue(SibionicsSessionPolicy.shouldInvalidateProbe(false, 0, 100_000_000))
        assertNull(SibionicsSessionPolicy.restartedSessionStartMs(
            listOf(SibionicsSessionPolicy.SessionSample(1001, 100_000_000 + 1001 * 60_000L, true)),
            100_000_000, 1001, 100_000_000 + 1000 * 60_000L, false, 100_000_000 + 1001 * 60_000L))
    }
    @Test fun knownMaintenanceRestartKeepsSameProbeEvenIfAppWasOfflineAfterCommand() {
        val sentAt = 1_780_000_000_000L
        assertFalse(SibionicsSessionPolicy.shouldInvalidateProbe(false, sentAt, sentAt + 60_000))
        assertTrue(SibionicsSessionPolicy.shouldInvalidateProbe(false, sentAt, sentAt + 15 * 86400_000L))
        assertTrue(SibionicsSessionPolicy.shouldInvalidateProbe(true, sentAt, sentAt + 60_000))
    }
}
