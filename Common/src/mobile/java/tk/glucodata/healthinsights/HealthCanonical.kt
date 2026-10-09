package tk.glucodata.healthinsights

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object HealthCanonical {
    fun time(start: Instant, end: Instant, offset: String?): JSONObject = JSONObject()
        .put("basis", "instant_interval").put("start_utc", start.toString()).put("end_utc", end.toString())
        .put("start_epoch_ms", start.toEpochMilli()).put("end_epoch_ms", end.toEpochMilli()).put("original_offset", offset ?: JSONObject.NULL)
    fun base(id: String, kind: String, time: JSONObject, provider: String, observed: Instant): JSONObject = JSONObject()
        .put("schema_version", "0.1.0").put("record_id", id).put("kind", kind)
        .put("granularity", if (time.getString("basis") == "local_date") "daily" else if (time.getString("basis") == "snapshot") "profile" else "interval")
        .put("time", time).put("source", JSONObject().put("provider", provider).put("record_alias", id)
            .put("app_id", if (provider == "samsung_health") "com.sec.android.app.shealth" else "tk.glucodata")
            .put("device_alias", JSONObject.NULL).put("sensor_alias", JSONObject.NULL).put("method", JSONObject.NULL))
        .put("metrics", JSONObject()).put("attributes", JSONObject()).put("quality", JSONObject().put("normalization", "partial").put("origin", "measurement").put("flags", JSONArray()))
        .put("relations", JSONArray()).put("series_refs", JSONArray()).put("imported_at_utc", observed.toString())
    fun metric(value: Number?, unit: String, statistic: String = "instant"): JSONObject = JSONObject()
        .put("value", value?.takeIf { it.toDouble().isFinite() } ?: JSONObject.NULL).put("unit", unit).put("statistic", statistic)
        .apply { if (value == null || !value.toDouble().isFinite()) put("missing_reason", "not_provided") }

    /** Known units only; every remaining health field is retained with its SDK name. */
    fun metrics(fields: JSONObject, kind: String): JSONObject {
        val result = JSONObject()
        val mappings = mapOf("HEART_RATE" to ("heart_rate" to "bpm"), "MAX_HEART_RATE" to ("heart_rate_max" to "bpm"),
            "MIN_HEART_RATE" to ("heart_rate_min" to "bpm"), "OXYGEN_SATURATION" to ("oxygen_saturation" to "%"),
            "SKIN_TEMPERATURE" to ("skin_temperature" to "Cel"))
        mappings.forEach { (sdk, spec) -> if (fields.opt(sdk) is Number) result.put(spec.first, metric(fields.get(sdk) as Number, spec.second, if (sdk.startsWith("MAX_")) "max" else if (sdk.startsWith("MIN_")) "min" else "mean")) }
        if (kind == "sleep") (fields.optJSONObject("DURATION")?.opt("seconds") as? Number)?.let { result.put("duration", metric(it, "s", "duration")) }
        return result
    }

    fun splitSeries(record: JSONObject, fields: JSONObject): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        val refs = record.getJSONArray("series_refs")
        val parent = record.getString("record_id")
        fun walk(value: Any?, path: String): Any? {
            if (value is JSONArray) {
                fun timestamp(obj: JSONObject?) = (obj?.optJSONObject("getStartTime") ?: obj?.optJSONObject("getTimestamp"))?.optString("utc")?.takeIf { it.isNotEmpty() }
                if ((0 until value.length()).any { timestamp(value.optJSONObject(it)) == null }) {
                    return JSONArray().apply { for (n in 0 until value.length()) put(walk(value.opt(n), "$path.$n")) }
                }
                val sid = "$parent:$path"
                refs.put(JSONObject().put("series_id", sid).put("semantic_type", when { path.contains("Stages") -> "sleep_stages"; path.contains("Route") -> "exercise_route"; path.contains("Log") -> "exercise_log"; path.contains("Timestamp") -> "point_samples"; else -> "interval_summaries" }).put("count", value.length()))
                for (n in 0 until value.length()) {
                    val item = walk(value.opt(n), "$path.$n")
                    val obj = item as? JSONObject
                    val start = timestamp(obj)
                    val end = obj?.optJSONObject("getEndTime")?.optString("utc")?.takeIf { it.isNotEmpty() } ?: start
                    val stamp = if (start != null && end != null) time(Instant.parse(start), Instant.parse(end), record.getJSONObject("time").optString("original_offset").takeIf { it.isNotBlank() && it != "null" })
                        else JSONObject().put("basis", "relative_or_unknown").put("null_reason", "SDK entry has no absolute timestamp")
                    rows.add(JSONObject().put("schema_version", "0.1.0").put("parent_record_id", parent).put("series_id", sid).put("sample_index", n)
                        .put("time", stamp).put("metrics", JSONObject()).put("attributes", JSONObject().put("sdk_value", item ?: JSONObject.NULL).put("field_path", path))
                        )
                }
                return JSONObject().put("series_ref", sid).put("count", value.length())
            }
            if (value is JSONObject) {
                val out = JSONObject()
                value.keys().forEach { k -> out.put(k, walk(value.opt(k), if (path.isEmpty()) k else "$path.$k")) }
                return out
            }
            return value
        }
        record.getJSONObject("attributes").put("sdk_fields", walk(fields, ""))
        return rows
    }
}
