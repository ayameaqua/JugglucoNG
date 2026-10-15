package tk.glucodata.healthinsights

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.units.*
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Public Health Connect DTOs only; route/record IDs are deliberately not reflected. */
internal object HealthConnectCanonical {
    const val POLICY = "health_connect_typed_units_v1"
    data class Captured(val row: JSONObject, val children: List<JSONObject>)
    private val excluded = setOf("getClass", "getMetadata", "getExerciseRouteResult", "getPlannedExerciseSessionId")
    private fun encode(value: Any?, depth: Int = 0): Any {
        check(depth <= 12) { "Health Connect value nesting exceeds supported depth" }
        return when(value) {
            null -> JSONObject.NULL
            is Number, is Boolean, is String -> value
            is Instant -> JSONObject().put("utc",value.toString()).put("epoch_ms",value.toEpochMilli())
            is ZoneOffset -> value.toString()
            is Duration -> JSONObject().put("seconds",value.seconds).put("nano",value.nano)
            is Mass -> quantity(value.inGrams,"g",value)
            is Length -> quantity(value.inMeters,"m",value)
            is Energy -> quantity(value.inKilocalories,"kcal",value)
            is Volume -> quantity(value.inMilliliters,"mL",value)
            is Pressure -> quantity(value.inMillimetersOfMercury,"mm[Hg]",value)
            is Temperature -> quantity(value.inCelsius,"Cel",value)
            is TemperatureDelta -> quantity(value.inCelsius,"Cel",value)
            is Percentage -> quantity(value.value,"%",value)
            is Power -> quantity(value.inWatts,"W",value)
            is BloodGlucose -> quantity(value.inMilligramsPerDeciliter,"mg/dL",value)
            is List<*> -> JSONArray().apply { value.forEach { put(encode(it,depth+1)) } }
            else -> {
                check(value.javaClass.name.startsWith("androidx.health.connect.client.records.")) { "Unsupported Health Connect value ${value.javaClass.simpleName}" }
                JSONObject().apply {
                    value.javaClass.methods.filter { it.parameterCount==0 && it.name.startsWith("get") && !Modifier.isStatic(it.modifiers) && it.name !in excluded && '$' !in it.name }
                        .sortedBy { it.name }.forEach { put(it.name,encode(it.invoke(value),depth+1)) }
                }
            }
        }
    }
    private fun quantity(value: Double, unit: String, original: Any) = JSONObject().put("value",value).put("unit",unit).put("source_representation",original.toString())
    fun time(record: Record): JSONObject {
        // InstantaneousRecord/IntervalRecord are internal Kotlin interfaces. Use public getters.
        val fields=encode(record) as JSONObject
        val start=fields.optJSONObject("getStartTime") ?: fields.getJSONObject("getTime")
        val end=fields.optJSONObject("getEndTime") ?: start
        val offset=fields.optString("getStartZoneOffset").takeUnless { it.isBlank() || it=="null" }
            ?: fields.optString("getZoneOffset").takeUnless { it.isBlank() || it=="null" }
        return HealthCanonical.time(Instant.parse(start.getString("utc")),Instant.parse(end.getString("utc")),offset)
    }
    private fun scalar(metrics: JSONObject, fields: JSONObject, getter: String, name: String, unit: String? = null, statistic: String = "instant") {
        val raw=fields.opt(getter)
        val number=if(raw is Number) raw else (raw as? JSONObject)?.opt("value") as? Number
        val actualUnit=unit ?: (raw as? JSONObject)?.optString("unit")?.takeIf { it.isNotBlank() }
        if(number!=null && actualUnit!=null) metrics.put(name,HealthCanonical.metric(number,actualUnit,statistic))
    }
    fun capture(record: Record, kind: HealthKind, store: HealthInsightStore, observed: Instant): Captured {
        check(record.metadata.id.isNotBlank()) { "Health Connect record is missing its platform ID" }
        val fields=encode(record) as JSONObject
        val time=timeFromFields(fields)
        val id=store.alias("health_connect",kind.kind,record.metadata.id)
        val row=HealthCanonical.base(id,kind.kind,time,"health_connect",observed)
        val attrs=row.getJSONObject("attributes")
        attrs.put("record_type",record.javaClass.simpleName).put("normalization_policy",POLICY)
            .put("recording_method_code",record.metadata.recordingMethod)
            .put("field_provenance","Public Health Connect 1.1.0 getters; quantity objects declare typed SDK conversion and source representation; no display calibration")
            .put("official_reference","https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/package-summary")
        record.metadata.clientRecordId?.let { attrs.put("client_record_id_alias",store.alias("health_connect","client_record",it)) }
        val source=row.getJSONObject("source")
        source.put("app_id",record.metadata.dataOrigin.packageName).put("method",when(record.metadata.recordingMethod) { 1->"active";2->"automatic";3->"manual";else->"unknown" })
        record.metadata.device?.let { attrs.put("device_info",JSONObject().put("manufacturer",it.manufacturer ?: JSONObject.NULL).put("model",it.model ?: JSONObject.NULL).put("type",it.type)) }
        row.put("source_updated_at_utc",record.metadata.lastModifiedTime.toString())
        val m=row.getJSONObject("metrics")
        when(record) {
            is StepsRecord -> scalar(m,fields,"getCount","steps","count","total")
            is FloorsClimbedRecord -> scalar(m,fields,"getFloors","floors_climbed","count","total")
            is OxygenSaturationRecord -> scalar(m,fields,"getPercentage","oxygen_saturation")
            is BloodGlucoseRecord -> { scalar(m,fields,"getLevel","glucose"); attrs.put("meal_type",record.mealType).put("relation_to_meal",record.relationToMeal).put("specimen_source",record.specimenSource) }
            is BloodPressureRecord -> { scalar(m,fields,"getSystolic","systolic_pressure");scalar(m,fields,"getDiastolic","diastolic_pressure") }
            is BodyTemperatureRecord -> scalar(m,fields,"getTemperature","body_temperature")
            is BasalBodyTemperatureRecord -> scalar(m,fields,"getTemperature","basal_body_temperature")
            is WeightRecord -> m.put("weight",HealthCanonical.metric(record.weight.inKilograms,"kg"))
            is HeightRecord -> m.put("height",HealthCanonical.metric(record.height.inMeters * 100.0,"cm"))
            is BodyFatRecord -> scalar(m,fields,"getPercentage","body_fat")
            is BoneMassRecord -> m.put("bone_mass",HealthCanonical.metric(record.mass.inKilograms,"kg"))
            is LeanBodyMassRecord -> m.put("lean_body_mass",HealthCanonical.metric(record.mass.inKilograms,"kg"))
            is BodyWaterMassRecord -> m.put("body_water_mass",HealthCanonical.metric(record.mass.inKilograms,"kg")) // Never equate kg to liters.
            is BasalMetabolicRateRecord -> m.put("basal_metabolic_rate",HealthCanonical.metric(record.basalMetabolicRate.inKilocaloriesPerDay,"kcal/d"))
            is ActiveCaloriesBurnedRecord -> scalar(m,fields,"getEnergy","active_energy_burned",statistic="total")
            is TotalCaloriesBurnedRecord -> scalar(m,fields,"getEnergy","energy_burned",statistic="total")
            is DistanceRecord -> scalar(m,fields,"getDistance","distance",statistic="total")
            is HydrationRecord -> scalar(m,fields,"getVolume","water_intake",statistic="total")
            is RestingHeartRateRecord -> scalar(m,fields,"getBeatsPerMinute","resting_heart_rate","bpm")
            is RespiratoryRateRecord -> scalar(m,fields,"getRate","respiratory_rate","count/min")
            is HeartRateVariabilityRmssdRecord -> scalar(m,fields,"getHeartRateVariabilityMillis","heart_rate_variability_rmssd","ms")
            is Vo2MaxRecord -> scalar(m,fields,"getVo2MillilitersPerMinuteKilogram","vo2_max","mL/(kg.min)")
            is NutritionRecord -> fields.keys().asSequence().filter { fields.optJSONObject(it)?.has("unit") == true }.forEach { getter ->
                val name=getter.removePrefix("get").replace(Regex("([a-z])([A-Z])"),"$1_$2").lowercase()
                scalar(m,fields,getter,name,statistic="total")
            }
            is SkinTemperatureRecord -> { scalar(m,fields,"getBaseline","skin_temperature_baseline");attrs.put("temperature_semantics","child values are deltas from baseline, not absolute skin temperature") }
            is ExerciseSessionRecord -> attrs.put("exercise_type_code",record.exerciseType).put("route_policy","not imported; requires separate route consent")
        }
        val children=HealthCanonical.splitSeries(row,fields)
        attrs.put("health_connect_fields",attrs.remove("sdk_fields"))
        for(child in children) {
            val dto=child.getJSONObject("attributes").getJSONObject("sdk_value");val cm=child.getJSONObject("metrics")
            when(record) {
                is HeartRateRecord -> scalar(cm,dto,"getBeatsPerMinute","heart_rate","bpm")
                is SkinTemperatureRecord -> scalar(cm,dto,"getDelta","skin_temperature_delta")
                is SleepSessionRecord -> {
                    val code=dto.optInt("getStage",-1)
                    child.getJSONObject("attributes").put("sleep_stage",stageName(code))
                }
            }
        }
        if(record is HeartRateRecord && record.samples.isNotEmpty()) {
            val values=record.samples.map { it.beatsPerMinute }
            m.put("heart_rate",HealthCanonical.metric(values.average(),"bpm","mean"))
                .put("heart_rate_min",HealthCanonical.metric(values.min(),"bpm","min")).put("heart_rate_max",HealthCanonical.metric(values.max(),"bpm","max"))
            attrs.put("heart_rate_summary_basis","arithmetic summary of captured samples, not time weighted")
        }
        if(record is SleepSessionRecord) sleep(row,record)
        row.getJSONObject("quality").put("normalization","partial") // Categorical/provider details remain explicit attributes.
        return Captured(row,children)
    }
    private fun timeFromFields(fields: JSONObject): JSONObject {
        val start=fields.optJSONObject("getStartTime") ?: fields.getJSONObject("getTime")
        val end=fields.optJSONObject("getEndTime") ?: start
        val offset=(fields.opt("getStartZoneOffset") as? String) ?: fields.opt("getZoneOffset") as? String
        return HealthCanonical.time(Instant.parse(start.getString("utc")),Instant.parse(end.getString("utc")),offset)
    }
    private fun stageName(stage: Int): String = when(stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE";SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AWAKE_IN_BED"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED";SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT";SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP";SleepSessionRecord.STAGE_TYPE_REM -> "REM";else -> "UNKNOWN"
    }
    private fun sleep(row: JSONObject, record: SleepSessionRecord) {
        fun seconds(start:Instant,end:Instant)=Duration.between(start,end).let { it.seconds+it.nano/1e9 }
        val m=row.getJSONObject("metrics"); val stages=record.stages.sortedBy { it.startTime }
        val span=seconds(record.startTime,record.endTime)
        m.put("record_interval_duration",HealthCanonical.metric(span,"s","duration"))
        row.getJSONObject("attributes").put("measurement_basis","device_estimated_sleep_stages")
        val flags=row.getJSONObject("quality").getJSONArray("flags")
        if(stages.isEmpty()) { flags.put("sleep_stages_unavailable");return }
        if(stages.any { it.startTime<record.startTime || it.endTime>record.endTime || it.endTime<it.startTime } || stages.zipWithNext().any { (a,b)->a.endTime>b.startTime }) { flags.put("sleep_stage_bounds_or_overlap_review");return }
        var asleep=0.0;var awake=0.0
        stages.forEach { val duration=seconds(it.startTime,it.endTime); when(stageName(it.stage)) {
            "SLEEPING","LIGHT","DEEP","REM" -> asleep+=duration;"AWAKE","AWAKE_IN_BED" -> awake+=duration
        } }
        m.put("estimated_asleep_duration",HealthCanonical.metric(asleep,"s","duration")).put("estimated_awake_duration",HealthCanonical.metric(awake,"s","duration"))
            .put("unclassified_duration",HealthCanonical.metric((span-asleep-awake).coerceAtLeast(0.0),"s","duration"))
        if(span-asleep-awake>0) flags.put("sleep_stage_coverage_partial")
    }
}
