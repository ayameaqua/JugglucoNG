package tk.glucodata.healthinsights

import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.HealthConnectSources
import tk.glucodata.data.HistoryReading

/** Export annotations only. Never changes acquisition, a stored value or an upload. */
internal object HealthGlucoseAudit {
    const val POLICY = "cgm_export_review_v1"
    const val JUMP_MGDL = 90.0
    const val JUMP_RATE_MGDL_PER_MIN = 10.0
    const val MAX_JUMP_GAP_MS = 5 * 60_000L
    const val EARLY_SESSION_MS = 30 * 60_000L

    fun flags(reading: HistoryReading, previous: HistoryReading?, wearStartMs: Long?): List<String> = buildList {
        if (!reading.value.isFinite() || reading.value <= 0) add("auto_missing_or_invalid")
        if (!reading.rawValue.isFinite() || reading.rawValue <= 0) add("raw_missing_or_invalid")
        if (previous == null) add("first_available_record") // Not proof of physical startup.
        if (wearStartMs != null && reading.timestamp - wearStartMs in 0 until EARLY_SESSION_MS) add("early_known_wear")
        val elapsed = previous?.let { reading.timestamp - it.timestamp } ?: 0L
        if (previous != null && previous.sensorSerial == reading.sensorSerial && elapsed in 1..MAX_JUMP_GAP_MS &&
            (wearStartMs == null || previous.timestamp >= wearStartMs) &&
            reading.value.isFinite() && previous.value.isFinite() && reading.value > 0 && previous.value > 0) {
            val delta = kotlin.math.abs(reading.value.toDouble() - previous.value)
            if (delta >= JUMP_MGDL && delta / (elapsed / 60_000.0) >= JUMP_RATE_MGDL_PER_MIN) add("abrupt_auto_change_review")
        }
    }

    fun sourceEntry(store: HealthInsightStore, serial: String, source: HealthConnectSources.Source,
                    wears: List<Pair<String, Long>>): JSONObject = JSONObject()
        // The catalog key is the actual Room alias, not the resolved transmitter name.
        .put("sensor_alias", store.alias("juggluco", "sensor", serial))
        .put("canonical_sensor_alias", store.alias("juggluco", "sensor", source.serial))
        .put("source_uid_alias", store.alias("juggluco", "source_uid", source.uid))
        .put("local_lookup_id", source.shortId).put("manufacturer", source.manufacturer ?: JSONObject.NULL)
        .put("model", source.model ?: JSONObject.NULL).put("health_connect_device_model", source.device.model)
        .put("identity_basis", if (serial == source.serial) "registered_device_name" else "driver_resolved_storage_alias")
        .put("identity_semantics", "sensor_alias identifies the historical storage name; source_uid_alias/local_lookup_id identify the registered device and may be shared by aliases; neither proves a disposable probe identity")
        .put("known_wear_sessions", JSONArray(wears.map { (id, start) -> JSONObject()
            .put("wear_session_alias", store.alias("juggluco", "wear_session", id)).put("start_epoch_ms", start) }))
        .put("unknown_historical_wear", "null is unknown; current QR/configuration is not evidence for older samples")

    fun channelContract(source: HealthConnectSources.Source): JSONObject = JSONObject()
        .put("stored_unit", "mg/dL").put("export_conversion", "identity; no second display calibration")
        .put("historical_algorithm_version", JSONObject.NULL).put("historical_calibration_parameters", JSONObject.NULL)
        .put("configuration_evidence", "not captured per stored Room sample; current settings cannot identify old configuration")
        .put("current_driver_contract", when (source.manufacturer) {
            "Yuwell" -> if (source.model == "CT4") "Auto: MK4 MODEL; Raw: linear current estimate, not original vendor glucose. raw_mgdl=(K*Iw_normalized_nA+R/100)*18; defaults K=0.30,R=50; voltage_mode=1 halves Iw. MODEL K0 defaults to 1.13. QR updates can change Raw independently of Auto. Historical formula/version is unknown."
                else "Anytime Auto depends on family/algorithm; Raw is a separate current-derived estimate (CT5 may use learned scale), not necessarily vendor-reported glucose. Historical formula/version is unknown."
            "Sibionics" -> "Raw is protocol input glucose in mmol/L converted by the driver to mg/dL; Auto passes through the selected Sibionics algorithm, probe sensitivity and possible integrated user calibration. Historical selection/sensitivity is not captured in Room."
            else -> "Auto and raw are stored lanes. Historical origin/formula cannot be inferred from this export."
        })

    fun policy(): JSONObject = JSONObject().put("version", POLICY).put("action", "review flags only; values retained; no automatic exclusion or medical classification")
        .put("abrupt_change_absolute_mgdl", JUMP_MGDL).put("abrupt_change_rate_mgdl_per_min", JUMP_RATE_MGDL_PER_MIN)
        .put("maximum_comparison_gap_ms", MAX_JUMP_GAP_MS).put("early_known_wear_ms", EARLY_SESSION_MS)
        .put("boundary", "same stored sensor alias and known wear; first available record does not prove startup")
}
