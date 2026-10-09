package tk.glucodata.healthinsights

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.*
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import java.lang.reflect.Modifier
import java.time.*

object SamsungAdapterFactory { fun create(context: Context): SamsungReadAdapter = OfficialSamsungReader(context.applicationContext) }

private class OfficialSamsungReader(private val context: Context) : SamsungReadAdapter {
    private val service by lazy { HealthDataService.getStore(context) }
    private fun installedVersion() = runCatching { context.packageManager.getPackageInfo("com.sec.android.app.shealth", 0).versionName }.getOrNull()
    override val available get() = android.os.Build.VERSION.SDK_INT >= 29 && samsungVersionSupported(installedVersion())
    override val availabilityMessage get() = when {
        !samsungInstalled(context) -> "未安装三星健康；保留本地缓存"
        !available -> "三星健康需 6.30.2 或更新版本，Android 10 或更新系统"
        else -> "三星健康 ${installedVersion()}：需要开发者模式／合作应用授权及读取权限"
    }
    private fun type(kind: HealthKind): DataType = DataTypes::class.java.getField(kind.name).get(null) as DataType
    private fun permission(kind: HealthKind) = Permission.of(type(kind), AccessType.READ)
    override suspend fun granted(kinds: Set<HealthKind>): Set<HealthKind> {
        if (!available) return emptySet()
        val actual = withTimeout(30_000) { service.getGrantedPermissions(kinds.map(::permission).toSet()) }
        return kinds.filter { permission(it) in actual }.toSet()
    }
    override suspend fun request(activity: Activity, kinds: Set<HealthKind>): Set<HealthKind> {
        if (!available) return emptySet()
        val actual = service.requestPermissions(kinds.map(::permission).toSet(), activity)
        return kinds.filter { permission(it) in actual }.toSet()
    }
    private fun descriptors(dataType: DataType): Map<String, Field<*>> = dataType.javaClass.fields
        .filter { Modifier.isStatic(it.modifiers) && Field::class.java.isAssignableFrom(it.type) }
        .associate { it.name to (it.get(null) as Field<*>) }
    private fun values(kind: HealthKind, point: HealthDataPoint, allowRoute: Boolean): JSONObject {
        val out = JSONObject()
        for ((name, field) in descriptors(type(kind))) {
            @Suppress("UNCHECKED_CAST")
            out.put(name, org.json.JSONTokener(JsonValue.of(point.getValue(field as Field<Any>)).toString()).nextValue())
        }
        fun scrub(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().asSequence().toList().forEach { k ->
                    if (!allowRoute && (k.contains("route", true) || k.contains("location", true))) v.remove(k)
                    else scrub(v.opt(k))
                }
                is JSONArray -> for (n in 0 until v.length()) scrub(v.opt(n))
            }
        }
        scrub(out)
        check(!out.toString().contains("serializationIncomplete") && !out.toString().contains("serializationError")) { "SDK DTO could not be captured completely" }
        return out
    }
    private fun stage(run: String, kind: HealthKind, data: HealthDataPoint, store: HealthInsightStore, observed: Instant, allowRoute: Boolean) {
        val id = store.alias("samsung_health", kind.kind, data.uid)
        val fields = values(kind, data, allowRoute)
        val row = HealthCanonical.base(id, kind.kind, (if (kind == HealthKind.ENERGY_SCORE) JSONObject().put("basis", "local_date").put("date", data.getStartLocalDateTime().toLocalDate().toString())
            .put("calendar_basis", "SDK_reported_local_date").put("query_timezone", ZoneId.systemDefault().id)
         else HealthCanonical.time(data.startTime, data.endTime ?: data.startTime, data.zoneOffset?.toString())), "samsung_health", observed)
        row.put("metrics", HealthCanonical.metrics(fields, kind.kind)).put("source_updated_at_utc", data.updateTime?.toString() ?: JSONObject.NULL)
        row.getJSONObject("source").put("app_id", data.dataSource?.appId ?: "com.sec.android.app.shealth")
            .put("device_alias", data.dataSource?.let { store.alias("samsung_health", "device", it.deviceId) } ?: JSONObject.NULL)
        store.stage(run, kind, row, HealthCanonical.splitSeries(row, fields))
    }
    private fun requestFor(kind: HealthKind, from: Instant, until: Instant, token: String?): ReadDataRequest<HealthDataPoint> {
        @Suppress("UNCHECKED_CAST")
        val readable = type(kind) as DataType.Readable<HealthDataPoint, *>
        val builder = readable.readDataRequestBuilder
        @Suppress("UNCHECKED_CAST")
        return when (builder) {
            is ReadDataRequest.DualTimeBuilder<*> -> (builder as ReadDataRequest.DualTimeBuilder<HealthDataPoint>)
                .setInstantTimeFilter(InstantTimeFilter.of(from, until)).setOrdering(Ordering.ASC).setPageSize(500).setPageToken(token).build()
            is ReadDataRequest.LocalDateBuilder<*> -> (builder as ReadDataRequest.LocalDateBuilder<HealthDataPoint>)
                .setLocalDateFilter(LocalDateFilter.of(from.atZone(ZoneId.systemDefault()).toLocalDate(), until.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)))
                .setOrdering(Ordering.ASC).setPageSize(500).setPageToken(token).build()
            else -> error("Unsupported SDK time builder for ${kind.name}")
        }
    }
    private class Pages {
        val seen = HashSet<String>()
        fun accept(next: String?) { if (next != null) check(seen.add(next)) { "SDK repeated a pagination token" } }
    }
    override suspend fun read(kinds: Set<HealthKind>, from: Instant, until: Instant, store: HealthInsightStore, progress: (String) -> Unit) {
        val permissions = try { granted(kinds) } catch (ex: Exception) {
            currentCoroutineContext().ensureActive(); kinds.forEach { store.fail(it, "授权查询失败：${ex.javaClass.simpleName}: ${ex.message}") }; return
        }
        val allowRoute = HealthKind.EXERCISE_LOCATION in kinds && HealthKind.EXERCISE_LOCATION in permissions
        // Sleep first: associations are available even when direct oxygen reads return empty.
        val order = kinds.sortedBy { if (it == HealthKind.SLEEP) 0 else if (it == HealthKind.BLOOD_OXYGEN || it == HealthKind.SKIN_TEMPERATURE) 2 else 1 }
        val sleepUids = linkedSetOf<String>()
        var sleepSucceeded = false
        for (kind in order) {
            currentCoroutineContext().ensureActive()
            if (kind !in permissions) { store.fail(kind, "未获得读取权限", "permission_denied"); continue }
            if (kind == HealthKind.EXERCISE_LOCATION) { store.fail(kind, "位置通过运动记录读取；是否存在由三星决定", if (HealthKind.EXERCISE in kinds) "association_permission" else "exercise_selection_missing"); continue }
            progress("正在读取${kind.label}…")
            val run = store.begin()
            val candidateSleepUids = linkedSetOf<String>()
            try {
                if (kind == HealthKind.USER_PROFILE) {
                    val response = withTimeout(60_000) { service.readData(DataTypes.USER_PROFILE.readDataRequestBuilder.build()) }
                    for (point in response.dataList) {
                        val row = HealthCanonical.base(store.alias("samsung_health", kind.kind, "current"), kind.kind,
                            JSONObject().put("basis", "snapshot").put("observed_at_utc", until.toString()), "samsung_health", until)
                        row.getJSONObject("attributes").put("sdk_fields", JSONObject(JsonValue.of(point).toString()))
                        store.stage(run, kind, row)
                    }
                } else if (type(kind) !is DataType.Readable<*, *>) {
                    aggregates(run, kind, from, until, store)
                } else {
                    var token: String? = null; val pages = Pages()
                    do {
                        val response = withTimeout(60_000) { service.readData(requestFor(kind, from, until, token)) }
                        for (point in response.dataList) {
                            stage(run, kind, point, store, until, allowRoute)
                            if (kind == HealthKind.SLEEP) candidateSleepUids.add(point.uid)
                        }
                        token = response.pageToken; pages.accept(token)
                    } while (token != null)
                    if (kind in setOf(HealthKind.BLOOD_OXYGEN, HealthKind.SKIN_TEMPERATURE) && HealthKind.SLEEP in permissions && HealthKind.SLEEP in kinds) {
                        for (sleep in sleepUids) {
                            val associate = if (kind == HealthKind.BLOOD_OXYGEN) DataType.SleepType.Associates.BLOOD_OXYGEN else DataType.SleepType.Associates.SKIN_TEMPERATURE
                            var at: String? = null; val ap = Pages()
                            do {
                                val response = withTimeout(60_000) { service.readAssociatedData(DataTypes.SLEEP.associatedReadRequestBuilder
                                    .setIdFilter(IdFilter.fromDataUid(sleep)).addAssociatedDataType(associate).setPageSize(500).setPageToken(at).build()) }
                                for (associated in response.dataList) {
                                    val points = if (kind == HealthKind.BLOOD_OXYGEN) associated.getDataPointOf(DataTypes.BLOOD_OXYGEN) else associated.getDataPointOf(DataTypes.SKIN_TEMPERATURE)
                                    for (point in points.orEmpty()) {
                                        stage(run, kind, point, store, until, allowRoute)
                                        store.associate(run, store.alias("samsung_health", "sleep", sleep), store.alias("samsung_health", kind.kind, point.uid))
                                    }
                                }
                                at = response.pageToken; ap.accept(at)
                            } while (at != null)
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    val changeType = type(kind) as? DataType.ChangeReadable<HealthDataPoint>
                    if (changeType != null) {
                        val previous = store.cursor(kind)
                        val changeFrom = if (previous > 0) Instant.ofEpochMilli(previous) else until
                        var ct: String? = null; val cp = Pages()
                        val changedUntil = Instant.now()
                        do {
                            val response = withTimeout(60_000) { service.readChanges(changeType.changedDataRequestBuilder
                                .setChangeTimeFilter(InstantTimeFilter.of(changeFrom, changedUntil)).setPageSize(500).setPageToken(ct).build()) }
                            for (change in response.dataList) {
                                if (change.changeType == ChangeType.DELETE) change.deleteDataUid?.let { store.stageDelete(run, kind, it) }
                                else change.upsertDataPoint?.let { point ->
                                    val id = store.alias("samsung_health", kind.kind, point.uid)
                                    if (store.contains(id) || (point.startTime < until && (point.endTime ?: point.startTime) >= from))
                                        stage(run, kind, point, store, until, allowRoute)
                                }
                            }
                            ct = response.pageToken; cp.accept(ct)
                        } while (ct != null)
                    }
                }
                store.complete(run, kind, from, until)
                if (kind == HealthKind.SLEEP) { sleepSucceeded = true; sleepUids.addAll(candidateSleepUids) }
                if (kind in setOf(HealthKind.BLOOD_OXYGEN, HealthKind.SKIN_TEMPERATURE) && !sleepSucceeded)
                    store.fail(kind, "睡眠读取未启用、未授权或失败；未覆盖完整睡眠关联数据", "partial")
                progress("${kind.label}读取完成")
            } catch (ex: Exception) {
                store.abandon(run); currentCoroutineContext().ensureActive()
                store.fail(kind, "${ex.javaClass.simpleName}: ${ex.message}")
                progress("${kind.label}读取失败；原有缓存保留")
            }
        }
    }

    private suspend fun aggregates(run: String, kind: HealthKind, from: Instant, until: Instant, store: HealthInsightStore) {
        val zone = ZoneId.systemDefault()
        var day = from.atZone(zone).toLocalDate()
        val last = until.atZone(zone).toLocalDate()
        while (!day.isAfter(last)) {
            val fields = JSONObject()
            suspend fun <T : Any> local(name: String, operation: AggregateOperation<T, AggregateRequest.LocalTimeBuilder<T>>) {
                val response = withTimeout(60_000) { service.aggregateData(operation.requestBuilder.setLocalTimeFilter(LocalTimeFilter.of(day.atStartOfDay(), day.plusDays(1).atStartOfDay())).build()) }
                fields.put(name, JSONArray().apply { response.dataList.forEach { put(JSONObject(JsonValue.of(it).toString())) } })
            }
            suspend fun <T : Any> goal(name: String, operation: AggregateOperation<T, AggregateRequest.AllSourceLocalDateBuilder<T>>) {
                var token: String? = null; val pages = Pages(); val list = JSONArray()
                do {
                    val response = withTimeout(60_000) { service.aggregateData(operation.requestBuilder.setLocalDateFilter(LocalDateFilter.of(day, day.plusDays(1))).setPageSize(500).setPageToken(token).build()) }
                    response.dataList.forEach { list.put(JSONObject(JsonValue.of(it).toString())) }
                    token = response.pageToken; pages.accept(token)
                } while (token != null)
                fields.put(name, list)
            }
            when (kind) {
                HealthKind.STEPS -> local("TOTAL", DataType.StepsType.TOTAL)
                HealthKind.ACTIVITY_SUMMARY -> { local("TOTAL_ACTIVE_CALORIES_BURNED", DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED); local("TOTAL_CALORIES_BURNED", DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED); local("TOTAL_ACTIVE_TIME", DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME); local("TOTAL_DISTANCE", DataType.ActivitySummaryType.TOTAL_DISTANCE) }
                HealthKind.SLEEP_GOAL -> { goal("LAST_BED_TIME", DataType.SleepGoalType.LAST_BED_TIME); goal("LAST_WAKE_UP_TIME", DataType.SleepGoalType.LAST_WAKE_UP_TIME) }
                HealthKind.STEPS_GOAL -> goal("LAST", DataType.StepsGoalType.LAST)
                HealthKind.ACTIVE_CALORIES_BURNED_GOAL -> goal("LAST", DataType.ActiveCaloriesBurnedGoalType.LAST)
                HealthKind.ACTIVE_TIME_GOAL -> goal("LAST", DataType.ActiveTimeGoalType.LAST)
                HealthKind.WATER_INTAKE_GOAL -> goal("LAST", DataType.WaterIntakeGoalType.LAST)
                HealthKind.NUTRITION_GOAL -> goal("LAST_CALORIES", DataType.NutritionGoalType.LAST_CALORIES)
                else -> error("No official aggregate route for ${kind.name}")
            }
            val stamp = JSONObject().put("basis", "local_date").put("date", day.toString()).put("calendar_basis", "SDK_local_calendar_total").put("query_timezone", zone.id)
            val row = HealthCanonical.base(store.alias("samsung_health", kind.kind, day.toString()), kind.kind, stamp, "samsung_health", until)
            row.getJSONObject("quality").put("origin", "aggregate")
            if (kind == HealthKind.STEPS) {
                val total = fields.optJSONArray("TOTAL")
                val value = if (total?.length() == 1) total.optJSONObject(0)?.opt("getValue") as? Number else null
                row.getJSONObject("metrics").put("steps", HealthCanonical.metric(value, "count", "total"))
                if (day == until.atZone(zone).toLocalDate()) row.getJSONObject("quality").getJSONArray("flags").put("partial_day")
            }
            row.getJSONObject("attributes").put("sdk_fields", fields)
            store.stage(run, kind, row)
            day = day.plusDays(1)
        }
    }
}
