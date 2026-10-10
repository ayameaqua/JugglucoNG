package tk.glucodata.data

import tk.glucodata.SensorIdentity
import tk.glucodata.ui.GlucosePoint

/** Browsing preserves independent CGMs; only replicas of the same CGM are coalesced. */
internal object HistoryBrowseData {
    fun sensorId(serial: String): String {
        if (HistoryRepository.isImportedHistorySerial(serial)) return serial
        val storage = SensorIdentity.resolveRoomStorageSensorId(serial) ?: serial
        return SensorIdentity.resolveAppSensorId(storage) ?: storage
    }

    fun merge(readings: List<HistoryReading>): List<HistoryReading> {
        val identities = readings.map { it.sensorSerial }.distinct().associateWith(::sensorId)
        return readings.groupBy { identities.getValue(it.sensorSerial) }
            .flatMap { (sensor, rows) ->
                HistoryDisplayMerge.mergeReadings(rows.sortedBy { it.timestamp }, sensor)
            }.sortedWith(compareBy<HistoryReading> { it.timestamp }.thenBy { identities.getValue(it.sensorSerial) })
    }

    /** Overlapping loaded windows must not discard two CGMs at the same timestamp. */
    fun joinWindows(a: List<GlucosePoint>, b: List<GlucosePoint>): List<GlucosePoint> =
        (a + b).associateBy { it.sensorSerial to it.timestamp }.values
            .sortedWith(compareBy<GlucosePoint> { it.timestamp }.thenBy { it.sensorSerial.orEmpty() })
}

enum class HistorySensorMode { SINGLE, SELECTED, ALL }

/** null singleSensor follows the home primary; null query means all stored sources. */
data class HistorySensorSelection(
    val mode: HistorySensorMode = HistorySensorMode.SINGLE,
    val singleSensor: String? = null,
    val selectedSensors: List<String> = emptyList(),
) {
    fun querySensors(homeSensor: String?): List<String>? = when (mode) {
        HistorySensorMode.SINGLE -> listOfNotNull(singleSensor ?: homeSensor?.takeIf(String::isNotBlank))
        HistorySensorMode.SELECTED -> selectedSensors.distinct()
        HistorySensorMode.ALL -> null
    }
}
