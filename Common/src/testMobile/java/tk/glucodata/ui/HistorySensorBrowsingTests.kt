package tk.glucodata.ui

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.data.HistoryBrowseData
import tk.glucodata.data.HistorySensorMode
import tk.glucodata.data.HistorySensorSelection
import tk.glucodata.logic.TrendEngine
import tk.glucodata.SensorVisuals
import tk.glucodata.ui.viewmodel.historySourceColors

class HistorySensorBrowsingTests {
    private fun point(sensor: String, i: Int, value: Float = 100f + i) =
        GlucosePoint(value, "", 1_791_500_040_000L + i * 60_000L, rawValue = value - 3f, sensorSerial = sensor)

    @Test fun defaultsFollowHomeWithoutMutatingHomeSelection() {
        val selection = HistorySensorSelection()
        assertEquals(listOf("CT4"), selection.querySensors("CT4"))
        assertEquals(listOf("GS1-P2"), selection.querySensors("GS1-P2"))
        assertEquals(selection, HistorySensorSelection())
    }
    @Test fun explicitSingleIsIndependentAndFollowCanBeRestored() {
        val selection = HistorySensorSelection(singleSensor = "ended")
        assertEquals(listOf("ended"), selection.querySensors("CT4"))
        assertEquals(listOf("GS1-P2"), selection.copy(singleSensor = null).querySensors("GS1-P2"))
    }
    @Test fun selectedAndAllHaveDifferentSemanticsIncludingEmptySelection() {
        val selection = HistorySensorSelection(HistorySensorMode.SELECTED, selectedSensors = listOf("CT4", "GS1-P2", "CT4"))
        assertEquals(listOf("CT4", "GS1-P2"), selection.querySensors("other"))
        assertTrue(selection.copy(selectedSensors = emptyList()).querySensors("CT4")!!.isEmpty())
        assertNull(selection.copy(mode = HistorySensorMode.ALL).querySensors("CT4"))
        assertTrue(HistorySensorSelection().querySensors("")!!.isEmpty())
    }
    @Test fun overlappingWindowsDeduplicateSameSourceOnly() {
        val a = point("CT4", 0); val b = point("GS1-P2", 0); val next = point("GS1-P2", 1)
        val merged = HistoryBrowseData.joinWindows(listOf(a, b), listOf(a, b, next))
        assertEquals(listOf(a, b, next), merged)
    }
    @Test fun inclusiveDateBoundsKeepAllSourcesAtBothEdges() {
        val points = (0..3).flatMap { listOf(point("CT4", it), point("GS1-P2", it)) }
        assertEquals(points.slice(2..5), points.sliceByTimestampRange(points[2].timestamp, points[5].timestamp))
        assertEquals(points.take(2), points.sliceByTimestampRange(points[0].timestamp, points[0].timestamp))
        assertTrue(points.sliceByTimestampRange(Long.MAX_VALUE, Long.MAX_VALUE).isEmpty())
    }
    @Test fun trendOfOneCgmIsUnaffectedByOppositeTrendOfAnotherCgm() {
        val own = (0..30).map { point("CT4", it, 100f + it) }
        val other = (0..30).map { point("GS1-P2", it, 300f - 5 * it) }
        val trends = HistorySourceTrends((own + other).sortedBy { it.timestamp })
        val actual = trends.forPoint(own.last())
        assertEquals(own.asReversed(), actual)
        assertTrue(actual.all { it.sensorSerial == "CT4" })
        assertEquals(TrendEngine.calculateTrend(own.asReversed(), false, false), TrendEngine.calculateTrend(actual, false, false))
    }
    @Test fun historyColoursMatchHomeEvenWhenAdditionalSourcesCollide() {
        val home = listOf("Yuwell", "Sibionics")
        val homePalette = SensorVisuals.distinctColorArgbMap(home)
        val extra = (0..100).map { "old-$it" }
        val colours = historySourceColors(extra + home, home, home.first())
        assertEquals(SensorVisuals.colorArgb(home.first()), colours[home.first()])
        assertEquals(homePalette.getValue(home.last()), colours[home.last()])
    }
    @Test fun changingBrowseScopeDoesNotChangeCurrentSensorColours() {
        val home = listOf("CT4", "GS1-P2")
        val all = historySourceColors(home + listOf("ended", "imported"), home, "CT4")
        for (sensor in home) {
            assertEquals(all[sensor], historySourceColors(listOf(sensor), home, "CT4")[sensor])
        }
    }
}
