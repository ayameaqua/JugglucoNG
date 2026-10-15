package tk.glucodata.healthinsights

import android.app.Activity
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.reflect.KClass

/** The gateway has no insert API: imported records cannot enter the CGM -> HC write queue. */
internal interface HealthConnectReadGateway {
    val available: Boolean
    val availabilityMessage: String
    fun feature(feature: Int): Boolean
    suspend fun permissions(): Set<String>
    suspend fun request(activity: Activity, permissions: Set<String>): Set<String>
    suspend fun page(type: KClass<out Record>, from: Instant, until: Instant, token: String?): HcReadPage
    suspend fun changesToken(types: Set<KClass<out Record>>): String
    suspend fun changes(token: String): HcChanges
}
internal data class HcReadPage(val records: List<Record>, val next: String?)
internal data class HcChanges(val upserts: List<Record>, val deletes: List<String>, val next: String, val more: Boolean, val expired: Boolean)
private class AndroidHealthConnectGateway(private val context: Context): HealthConnectReadGateway {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val status get() = runCatching { HealthConnectClient.getSdkStatus(context) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
    override val available get() = status == HealthConnectClient.SDK_AVAILABLE
    override val availabilityMessage get() = when(status) {
        HealthConnectClient.SDK_AVAILABLE -> "读取系统 Health Connect 中其他应用提供的数据；需要逐项授权。不会把读取结果写回血糖或日志。"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "请先安装或更新 Health Connect；已有缓存保留"
        else -> "此设备的 Health Connect 不可用；已有缓存保留"
    }
    override fun feature(feature: Int) = available && client.features.getFeatureStatus(feature)==HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    override suspend fun permissions() = if(available) client.permissionController.getGrantedPermissions() else emptySet()
    override suspend fun request(activity: Activity, permissions: Set<String>): Set<String> =
        (activity as? HealthInsightsActivity)?.requestHealthConnectPermissions(permissions) ?: error("请从健康数据与血糖页面授权")
    override suspend fun page(type: KClass<out Record>, from: Instant, until: Instant, token: String?): HcReadPage {
        val response=client.readRecords(ReadRecordsRequest(type,TimeRangeFilter.between(from,until),ascendingOrder=true,pageSize=500,pageToken=token))
        return HcReadPage(response.records,response.pageToken?.takeIf(String::isNotBlank))
    }
    override suspend fun changesToken(types: Set<KClass<out Record>>) = client.getChangesToken(ChangesTokenRequest(types))
    override suspend fun changes(token: String): HcChanges {
        val response=client.getChanges(token)
        return HcChanges(response.changes.filterIsInstance<UpsertionChange>().map { it.record },response.changes.filterIsInstance<DeletionChange>().map { it.recordId },response.nextChangesToken,response.hasMore,response.changesTokenExpired)
    }
}

internal class HealthConnectReader(private val context: Context, private val gateway: HealthConnectReadGateway = AndroidHealthConnectGateway(context)): HealthReadAdapter {
    override val available get() = gateway.available
    override val availabilityMessage get() = gateway.availabilityMessage
    override val supportedKinds get() = types.keys.filter { it != HealthKind.SKIN_TEMPERATURE || gateway.feature(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) }.toSet()
    override val supportsHistoryPermission get() = gateway.feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)
    private fun classes(kind: HealthKind): Set<KClass<out Record>> = types[kind].orEmpty().filter {
        it != SkinTemperatureRecord::class || gateway.feature(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)
    }.toSet()
    internal fun required(kinds: Set<HealthKind>): Set<String> = kinds.flatMap { kind -> classes(kind).map { HealthPermission.getReadPermission(it) } }.toSet()
    override suspend fun granted(kinds: Set<HealthKind>): Set<HealthKind> {
        val permissions=gateway.permissions()
        return kinds.filter { kind -> val types=classes(kind);types.isNotEmpty() && types.any { HealthPermission.getReadPermission(it) in permissions } }.toSet()
    }
    override suspend fun request(activity: Activity, kinds: Set<HealthKind>): Set<HealthKind> {
        val history=context.getSharedPreferences("health_insights",0).getBoolean("hc_read_history",false)
        val permissions=required(kinds).toMutableSet()
        if(history && supportsHistoryPermission) permissions.add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        if(permissions.isNotEmpty()) gateway.request(activity,permissions)
        return granted(kinds)
    }
    override suspend fun read(kinds: Set<HealthKind>, from: Instant, until: Instant, store: HealthInsightStore, progress: (String)->Unit) {
        require(store.provider == "health_connect")
        val permissions=gateway.permissions()
        val history=context.getSharedPreferences("health_insights",0).getBoolean("hc_read_history",false) && HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in permissions
        val floor=until.minusSeconds(30*86400L)
        val actualFrom=if(history || from>=floor) from else floor
        for(kind in kinds) {
            currentCoroutineContext().ensureActive()
            val requestedTypes=classes(kind)
            if(requestedTypes.isEmpty()) { store.fail(kind,"当前系统不支持此数据类型","unsupported");continue }
            val recordTypes=requestedTypes.filter { HealthPermission.getReadPermission(it) in permissions }.toSet()
            if(recordTypes.isEmpty()) { store.fail(kind,"未获得读取权限；保留缓存","permission_denied");continue }
            val partialPermissions=recordTypes.size != requestedTypes.size
            // Changes tokens depend on their record-type set. Keep separate tokens when grants change.
            val tokenKind=kind.kind + ":" + recordTypes.map { it.simpleName }.sortedBy { it }.joinToString(",")
            var run=store.begin()
            try {
                var attempts=0
                var finished=false
                while(!finished) {
                    val previousToken=store.changeToken(kind, tokenKind)
                    val permissionSetChanged=previousToken==null && store.cursor(kind)>0
                    var token=previousToken ?: withTimeout(30_000) { gateway.changesToken(recordTypes) }
                    if(attempts>0) token=withTimeout(30_000) { gateway.changesToken(recordTypes) }
                    fun allowed(record: Record): Boolean = !isJuggOrigin(record.metadata.dataOrigin.packageName,context.packageName)
                    fun stage(record: Record) {
                        if(!allowed(record)) return
                        val capture=HealthConnectCanonical.capture(record,kind,store,until)
                        store.stage(run,kind,capture.row,capture.children)
                    }
                    progress("正在读取${kind.label}…")
                    for(type in recordTypes) {
                        var pageToken: String?=null;val seen=mutableSetOf<String>()
                        do {
                            currentCoroutineContext().ensureActive()
                            val response=withTimeout(60_000) { gateway.page(type,actualFrom,until,pageToken) }
                            response.records.forEach(::stage)
                            pageToken=response.next?.takeIf(String::isNotBlank)
                            check(pageToken==null || seen.add(pageToken)) { "Health Connect 返回重复分页游标" }
                        } while(pageToken!=null)
                    }
                    val seen=mutableSetOf<String>();var expired=false
                    do {
                        currentCoroutineContext().ensureActive()
                        val response=withTimeout(60_000) { gateway.changes(token) }
                        if(response.expired) { expired=true;break }
                        response.upserts.forEach { record ->
                            if(record::class in recordTypes && allowed(record)) {
                                val id=store.alias("health_connect",kind.kind,record.metadata.id)
                                val time=HealthConnectCanonical.time(record)
                                if(store.contains(id) || (time.getLong("start_epoch_ms")<until.toEpochMilli() && time.getLong("end_epoch_ms")>=actualFrom.toEpochMilli())) stage(record)
                            }
                        }
                        response.deletes.forEach { store.stageDelete(run,kind,it) }
                        check(response.next.isNotBlank()) { "Health Connect 未返回变更游标" }
                        token=response.next
                        check(!response.more || seen.add(token)) { "Health Connect 返回重复变更游标" }
                    } while(response.more)
                    if(expired) {
                        check(attempts++ == 0) { "Health Connect 变更游标反复过期" }
                        store.abandon(run);run=store.begin();continue
                    }
                    store.complete(run,kind,actualFrom,until,token,if(attempts>0 || permissionSetChanged) "变更游标重建（过期或权限集合变化）；已重新读取授权范围。之前删除事件可能缺失，范围外旧缓存保留，不宣称删除历史完整。" else null, tokenKind)
                    if(partialPermissions) store.fail(kind,"部分类型未获得读取权限；已读取获准类型，其他旧缓存保留","permission_partial")
                    if(actualFrom>from) store.fail(kind,"未启用或未获更早历史权限；本次仅请求近 30 天，旧缓存保留","history_limited")
                    progress("${kind.label}读取完成")
                    finished=true
                }
            } catch(ex: Exception) {
                store.abandon(run);currentCoroutineContext().ensureActive()
                store.fail(kind,"Health Connect 读取失败（${ex.javaClass.simpleName}）；请检查系统授权并重试");progress("${kind.label}读取失败；原有缓存保留")
            }
        }
    }
    companion object {
        internal fun isJuggOrigin(origin: String, ownPackage: String) = origin==ownPackage || origin in setOf("tk.glucodata","tk.glucodata.ng","tk.glucodata.ng.dub","tk.glucodata.ng.dub2")
        val types: Map<HealthKind,Set<KClass<out Record>>> = mapOf(
            HealthKind.SLEEP to setOf(SleepSessionRecord::class), HealthKind.STEPS to setOf(StepsRecord::class),
            HealthKind.HEART_RATE to setOf(HeartRateRecord::class), HealthKind.EXERCISE to setOf(ExerciseSessionRecord::class),
            HealthKind.SKIN_TEMPERATURE to setOf(SkinTemperatureRecord::class), HealthKind.BLOOD_OXYGEN to setOf(OxygenSaturationRecord::class),
            HealthKind.ACTIVITY_SUMMARY to setOf(ActiveCaloriesBurnedRecord::class,TotalCaloriesBurnedRecord::class,DistanceRecord::class),
            HealthKind.FLOORS_CLIMBED to setOf(FloorsClimbedRecord::class),HealthKind.BLOOD_GLUCOSE to setOf(BloodGlucoseRecord::class),
            HealthKind.BLOOD_PRESSURE to setOf(BloodPressureRecord::class),
            HealthKind.BODY_COMPOSITION to setOf(WeightRecord::class,HeightRecord::class,BodyFatRecord::class,BoneMassRecord::class,LeanBodyMassRecord::class,BodyWaterMassRecord::class,BasalMetabolicRateRecord::class),
            HealthKind.BODY_TEMPERATURE to setOf(BodyTemperatureRecord::class,BasalBodyTemperatureRecord::class),
            HealthKind.WATER_INTAKE to setOf(HydrationRecord::class),HealthKind.NUTRITION to setOf(NutritionRecord::class),
            HealthKind.RESTING_HEART_RATE to setOf(RestingHeartRateRecord::class),HealthKind.RESPIRATORY_RATE to setOf(RespiratoryRateRecord::class),
            HealthKind.HEART_RATE_VARIABILITY to setOf(HeartRateVariabilityRmssdRecord::class),HealthKind.VO2_MAX to setOf(Vo2MaxRecord::class)
        )
    }
}
