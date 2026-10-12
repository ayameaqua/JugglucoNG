package tk.glucodata.healthinsights

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** SDK 1.1 DTO projections. Original sdk_fields/sdk_value are retained unchanged. */
internal object SamsungNormalization {
    const val VERSION = "samsung_documented_metrics_v1"
    private const val DOC_ROOT = "https://developer.samsung.com/health/data/api-reference/-shd/"
    private const val TYPE_DOC = DOC_ROOT + "com.samsung.android.sdk.health.data.request/-data-type/"
    private const val ENTRY_DOC = DOC_ROOT + "com.samsung.android.sdk.health.data.data.entries/"
    private data class Spec(val name: String, val unit: String, val statistic: String = "instant")
    private fun duration(v: Any?): Double? = (v as? JSONObject)?.let {
        val seconds = it.opt("seconds") as? Number ?: return null
        seconds.toDouble() + (it.opt("nano") as? Number)?.toDouble().orZero() / 1e9
    }
    private fun Double?.orZero() = this ?: 0.0
    private fun bounds(time: JSONObject): Pair<Instant, Instant>? = runCatching {
        Instant.parse(time.getString("start_utc")) to Instant.parse(time.getString("end_utc"))
    }.getOrNull()
    private fun evidence(attributes: JSONObject, name: String, path: String, doc: String, conversion: String = "identity") {
        val map = attributes.optJSONObject("metric_provenance") ?: JSONObject().also { attributes.put("metric_provenance", it) }
        map.put(name, JSONObject().put("sdk_path", path).put("official_reference", doc).put("conversion", conversion).put("mapping_version", VERSION))
    }
    private fun map(metrics: JSONObject, attributes: JSONObject, fields: JSONObject, specs: Map<String, Spec>, doc: String) {
        for ((key, spec) in specs) (fields.opt(key) as? Number)?.takeIf { it.toDouble().isFinite() }?.let {
            metrics.put(spec.name, HealthCanonical.metric(it, spec.unit, spec.statistic)); evidence(attributes, spec.name, key, doc)
        }
    }
    private fun addDuration(metrics: JSONObject, attributes: JSONObject, name: String, value: Any?, path: String, doc: String) {
        duration(value)?.let { metrics.put(name, HealthCanonical.metric(it, "s", "duration")); evidence(attributes, name, path, doc, "java.time.Duration seconds + nano/1e9") }
    }
    private val body = mapOf(
        "WEIGHT" to Spec("weight", "kg"), "HEIGHT" to Spec("height", "cm"),
        "BASAL_METABOLIC_RATE" to Spec("basal_metabolic_rate", "kcal/d"), "BODY_FAT" to Spec("body_fat", "%"),
        "BODY_FAT_MASS" to Spec("body_fat_mass", "kg"), "FAT_FREE" to Spec("fat_free", "%"), "FAT_FREE_MASS" to Spec("fat_free_mass", "kg"),
        "MUSCLE_MASS" to Spec("muscle_mass", "%"), "SKELETAL_MUSCLE" to Spec("skeletal_muscle", "%"),
        "SKELETAL_MUSCLE_MASS" to Spec("skeletal_muscle_mass", "kg"), "TOTAL_BODY_WATER" to Spec("total_body_water", "L"), "BODY_MASS_INDEX" to Spec("body_mass_index", "kg/m2"))
    private val exercise = mapOf(
        "getCalories" to Spec("energy_burned", "kcal", "total"), "getDistance" to Spec("distance", "m", "total"),
        "getAltitudeGain" to Spec("altitude_gain", "m", "total"), "getAltitudeLoss" to Spec("altitude_loss", "m", "total"),
        "getMinHeartRate" to Spec("heart_rate_min", "bpm", "min"), "getMaxHeartRate" to Spec("heart_rate_max", "bpm", "max"),
        "getMeanHeartRate" to Spec("heart_rate", "bpm", "mean"), "getMaxSpeed" to Spec("speed_max", "m/s", "max"), "getMeanSpeed" to Spec("speed", "m/s", "mean"),
        "getMaxPower" to Spec("power_max", "W", "max"), "getMeanPower" to Spec("power", "W", "mean"))

    /** One parent plus a bounded stage summary; child logs can stream without a large list. */
    class Projection(private val row: JSONObject) {
        private val kind = row.getString("kind")
        private val time = row.getJSONObject("time")
        private val metrics = row.getJSONObject("metrics")
        private val attributes = row.getJSONObject("attributes")
        private val sessions = mutableListOf<JSONObject>()
        private val stages = mutableListOf<Pair<String, Pair<Instant, Instant>>>()
        private var stageInvalid = false
        init {
            attributes.put("normalization_policy", VERSION)
            val fields = attributes.optJSONObject("sdk_fields") ?: JSONObject()
            val point = bounds(time)?.let { it.first == it.second } == true
            if (time.optString("basis") == "instant_interval") row.put("granularity", if (point) "point" else "interval")
            when (kind) {
                "body_composition" -> map(metrics, attributes, fields, body, TYPE_DOC + "-body-composition-type/index.html")
                "blood_oxygen", "heart_rate", "skin_temperature" -> {
                    val spec = when (kind) {
                        "blood_oxygen" -> mapOf("OXYGEN_SATURATION" to Spec("oxygen_saturation", "%", if (point) "instant" else "mean"),
                            "MIN_OXYGEN_SATURATION" to Spec("oxygen_saturation_min", "%", "min"), "MAX_OXYGEN_SATURATION" to Spec("oxygen_saturation_max", "%", "max"))
                        "heart_rate" -> mapOf("HEART_RATE" to Spec("heart_rate", "bpm", if (point) "instant" else "mean"),
                            "MIN_HEART_RATE" to Spec("heart_rate_min", "bpm", "min"), "MAX_HEART_RATE" to Spec("heart_rate_max", "bpm", "max"))
                        else -> mapOf("SKIN_TEMPERATURE" to Spec("skin_temperature", "Cel", if (point) "instant" else "mean"))
                    }
                    map(metrics, attributes, fields, spec, TYPE_DOC + "-${kind.replace('_', '-')}-type/index.html")
                }
                "sleep" -> {
                    // Legacy duration was the SDK bed-to-wake interval, not time asleep.
                    listOf("duration", "sdk_session_duration", "record_interval_duration", "estimated_asleep_duration", "estimated_awake_duration", "unclassified_duration").forEach(metrics::remove)
                    addDuration(metrics, attributes, "sdk_session_duration", fields.opt("DURATION"), "DURATION", TYPE_DOC + "-sleep-type/index.html")
                    bounds(time)?.let { (start, end) -> metrics.put("record_interval_duration", HealthCanonical.metric(Duration.between(start, end).toNanos() / 1e9, "s", "duration"))
                        evidence(attributes, "record_interval_duration", "time.start_utc/end_utc", ENTRY_DOC + "-sleep-session/index.html", "end-start; includes awake and unknown stages") }
                    attributes.put("measurement_basis", "device_estimated_sleep_stages; not clinical sleep measurement")
                }
                "activity_summary", "steps", "steps_goal", "active_time_goal", "active_calories_burned_goal" -> aggregate(fields)
                "floors_climbed" -> map(metrics, attributes, fields, mapOf("FLOOR" to Spec("floors_climbed", "count", "total")), TYPE_DOC + "-floors-climbed-type/index.html")
            }
            // Added metrics do not prove every SDK property has been normalized.
            row.getJSONObject("quality").put("normalization", "partial")
        }
        private fun aggregate(fields: JSONObject) {
            val mappings = when (kind) {
                "activity_summary" -> mapOf("TOTAL_ACTIVE_CALORIES_BURNED" to Spec("active_energy_burned", "kcal", "total"),
                    "TOTAL_CALORIES_BURNED" to Spec("energy_burned", "kcal", "total"), "TOTAL_DISTANCE" to Spec("distance", "m", "total"))
                "steps" -> mapOf("TOTAL" to Spec("steps", "count", "total"))
                "steps_goal" -> mapOf("LAST" to Spec("steps_goal", "count"))
                "active_calories_burned_goal" -> mapOf("LAST" to Spec("active_energy_goal", "kcal"))
                else -> emptyMap()
            }
            val doc = TYPE_DOC + "-${kind.replace('_', '-')}-type/index.html"
            for ((key, spec) in mappings) {
                val values = fields.optJSONArray(key)
                // Multiple aggregate sources may overlap. Never sum them speculatively.
                if (values?.length() == 1) (values.optJSONObject(0)?.opt("getValue") as? Number)?.let {
                    metrics.put(spec.name, HealthCanonical.metric(it, spec.unit, spec.statistic)); evidence(attributes, spec.name, "$key.0.getValue", doc)
                }
            }
            val durationKey = if (kind == "activity_summary") "TOTAL_ACTIVE_TIME" else if (kind == "active_time_goal") "LAST" else null
            durationKey?.let { key -> fields.optJSONArray(key)?.takeIf { it.length() == 1 }?.optJSONObject(0)?.opt("getValue")?.let {
                addDuration(metrics, attributes, if (kind == "activity_summary") "active_duration" else "active_duration_goal", it, "$key.0.getValue", doc)
            } }
            if (fields.keys().asSequence().any { fields.optJSONArray(it)?.length()?.let { n -> n > 1 } == true }) flag("multiple_aggregate_entries_unmapped")
        }
        fun child(child: JSONObject) {
            val attrs = child.optJSONObject("attributes") ?: return
            val dto = attrs.optJSONObject("sdk_value") ?: return
            val path = attrs.optString("field_path")
            val out = child.optJSONObject("metrics") ?: JSONObject().also { child.put("metrics", it) }
            if (kind == "exercise" && path == "SESSIONS") {
                sessions.add(child)
                map(out, attrs, dto, exercise, ENTRY_DOC + "-exercise-session/index.html")
                addDuration(out, attrs, "duration", dto.opt("getDuration"), "getDuration", ENTRY_DOC + "-exercise-session/index.html")
                attrs.put("exercise_type", dto.opt("getExerciseType") ?: JSONObject.NULL).put("count_type", dto.opt("getCountType") ?: JSONObject.NULL)
                (dto.opt("getCount") as? Number)?.let {
                    out.put("movement_count", HealthCanonical.metric(it, "count", "total"))
                    evidence(attrs, "movement_count", "getCount + getCountType", ENTRY_DOC + "-exercise-session/index.html")
                }
            } else if (kind == "exercise" && path.endsWith("getLog")) {
                map(out, attrs, dto, mapOf("getHeartRate" to Spec("heart_rate", "bpm"), "getSpeed" to Spec("speed", "m/s"),
                    "getPower" to Spec("power", "W"), "getCadence" to Spec("cadence", "count/min")), ENTRY_DOC + "-exercise-log/index.html")
            } else if (kind == "sleep" && path.endsWith("getStages")) {
                val name = dto.optString("getStage")
                attrs.put("sleep_stage", name).put("measurement_basis", "device_estimated")
                val interval = bounds(child.getJSONObject("time"))
                val parent = bounds(time)
                if (interval == null || parent == null || interval.second < interval.first || interval.first < parent.first || interval.second > parent.second) stageInvalid = true
                else {
                    stages.add(name to interval); out.put("duration", HealthCanonical.metric(Duration.between(interval.first, interval.second).toNanos() / 1e9, "s", "duration"))
                    evidence(attrs, "duration", "time.start_utc/end_utc", ENTRY_DOC + "-sleep-session/-sleep-stage/index.html", "end-start; device-estimated stage")
                }
            } else if (path == "SERIES_DATA") {
                val point = bounds(child.getJSONObject("time"))?.let { it.first == it.second } == true
                val statistic = if (point) "instant" else "mean"
                val spec = when (kind) {
                    "heart_rate" -> mapOf("getHeartRate" to Spec("heart_rate", "bpm", statistic), "getMin" to Spec("heart_rate_min", "bpm", "min"), "getMax" to Spec("heart_rate_max", "bpm", "max"))
                    "blood_oxygen" -> mapOf("getOxygenSaturation" to Spec("oxygen_saturation", "%", statistic), "getMin" to Spec("oxygen_saturation_min", "%", "min"), "getMax" to Spec("oxygen_saturation_max", "%", "max"))
                    "skin_temperature" -> mapOf("getSkinTemperature" to Spec("skin_temperature", "Cel", statistic), "getMin" to Spec("skin_temperature_min", "Cel", "min"), "getMax" to Spec("skin_temperature_max", "Cel", "max"))
                    else -> emptyMap()
                }
                map(out, attrs, dto, spec, ENTRY_DOC + when (kind) { "heart_rate" -> "-heart-rate/"; "blood_oxygen" -> "-oxygen-saturation/"; else -> "-skin-temperature/" } + "index.html")
            }
        }
        private fun flag(value: String) {
            val flags = row.getJSONObject("quality").getJSONArray("flags")
            if ((0 until flags.length()).none { flags.optString(it) == value }) flags.put(value)
        }
        fun finish(): JSONObject {
            if (kind == "exercise") {
                if (sessions.size == 1) {
                    val child = sessions.single()
                    val m = child.getJSONObject("metrics")
                    m.keys().forEach { name -> metrics.put(name, m.get(name)); evidence(attributes, name, "series:${child.getString("series_id")}.metrics.$name", ENTRY_DOC + "-exercise-session/index.html", "single SDK session; identity") }
                    attributes.put("exercise_type", child.getJSONObject("attributes").opt("exercise_type"))
                } else if (sessions.size > 1) flag("multiple_exercise_sessions_use_child_metrics")
            }
            if (kind == "sleep") {
                val sorted = stages.sortedBy { it.second.first }
                if (sorted.zipWithNext().any { (a, b) -> a.second.second > b.second.first }) stageInvalid = true
                if (stageInvalid) flag("sleep_stage_bounds_or_overlap_review")
                else if (sorted.isNotEmpty()) {
                    val totals = sorted.groupBy { it.first }.mapValues { (_, intervals) -> intervals.sumOf { Duration.between(it.second.first, it.second.second).toNanos() / 1e9 } }
                    val asleep = listOf("LIGHT", "DEEP", "REM").sumOf { totals[it].orZero() }
                    val awake = totals["AWAKE"].orZero()
                    val classified = asleep + awake
                    val span = bounds(time)?.let { Duration.between(it.first, it.second).toNanos() / 1e9 } ?: 0.0
                    for ((name, value) in mapOf("estimated_asleep_duration" to asleep, "estimated_awake_duration" to awake, "unclassified_duration" to (span - classified).coerceAtLeast(0.0))) {
                        metrics.put(name, HealthCanonical.metric(value, "s", "duration")); evidence(attributes, name, "series:SESSIONS.*.getStages", ENTRY_DOC + "-sleep-session/-sleep-stage/index.html", "non-overlapping stage intervals; LIGHT+DEEP+REM asleep, AWAKE awake; unknown stages and gaps remain unclassified")
                    }
                    if (classified < span) flag("sleep_stage_coverage_partial")
                } else flag("sleep_stages_unavailable")
            }
            attributes.put("unmapped_fields_policy", "SDK originals retained; non-metric or undocumented units remain attributes; partial is intentional")
            return row
        }
    }
}
