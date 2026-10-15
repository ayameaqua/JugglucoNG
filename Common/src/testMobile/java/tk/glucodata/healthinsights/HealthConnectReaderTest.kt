package tk.glucodata.healthinsights

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal fun hcMetadata(id:String,origin:String="example.health"): Metadata = Metadata.unknownRecordingMethodWithId(id).also {
    Metadata::class.java.getDeclaredField("dataOrigin").apply { isAccessible=true }.set(it,DataOrigin(origin))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,manifest=Config.NONE)
class HealthConnectReaderTest {
    private val context: Context get()=RuntimeEnvironment.getApplication()
    private lateinit var store:HealthInsightStore
    private val from=Instant.parse("2026-10-01T00:00:00Z")
    private val until=Instant.parse("2026-10-15T00:00:00Z")
    private class Gateway: HealthConnectReadGateway {
        override val available=true
        override val availabilityMessage="test"
        var grants=HealthConnectReader.types.values.flatten().map { HealthPermission.getReadPermission(it) }.toSet()
        var permissionFailure=false
        var rows:List<Record> = emptyList()
        var pageReads=0;var fail=false;var repeat=false;var emptyToken=false;var expired=false;var deletes:List<String> = emptyList()
        var upserts:List<Record> = emptyList();var seenFrom:Instant?=null
        override fun feature(feature:Int)=true
        override suspend fun permissions():Set<String> { if(permissionFailure) error("synthetic-secret-token");return grants }
        override suspend fun request(activity:Activity,permissions:Set<String>)=grants
        override suspend fun page(type:KClass<out Record>,from:Instant,until:Instant,token:String?):HcReadPage {
            pageReads++;seenFrom=from
            if(fail && token!=null) error("page 2 failed")
            return if(token==null) HcReadPage(rows.filter { it::class==type },if(emptyToken) "" else "page-2") else HcReadPage(emptyList(),if(repeat) "page-2" else null)
        }
        override suspend fun changesToken(types:Set<KClass<out Record>>)="initial"
        override suspend fun changes(token:String):HcChanges {
            if(expired) { expired=false;return HcChanges(emptyList(),emptyList(),"expired",false,true) }
            return HcChanges(upserts,deletes,"final-token",false,false)
        }
    }
    @Before fun setup() {
        context.deleteDatabase("health-insights-hc.db");context.deleteDatabase("health-insights.db")
        context.getSharedPreferences("health_insights",0).edit().clear().commit()
        store=HealthInsightStore(context,"health_connect")
    }
    @After fun close(){store.close()}
    private fun record(id:String="record",origin:String="example.health",value:Double=97.0)=OxygenSaturationRecord(from.plusSeconds(10),ZoneOffset.ofHours(7),Percentage(value),hcMetadata(id,origin))
    private fun records():List<JSONObject> { val writer=StringWriter();store.snapshot(writer,StringWriter(),StringWriter());return writer.toString().lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }
    private fun tokenKey(kind:HealthKind)=kind.kind+":"+HealthConnectReader.types.getValue(kind).map { it.simpleName }.sortedBy { it }.joinToString(",")
    @Test fun paginatedReadDeduplicatesRetriesAndPreservesOriginalOffset()=runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record())
        val reader=HealthConnectReader(context,gateway)
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        val row=records().single()
        assertEquals("health_connect",row.getJSONObject("source").getString("provider"))
        assertEquals("example.health",row.getJSONObject("source").getString("app_id"))
        assertEquals("+07:00",row.getJSONObject("time").getString("original_offset"))
        assertEquals(from.plusSeconds(10).toEpochMilli(),row.getJSONObject("time").getLong("start_epoch_ms"))
        assertEquals("point",row.getString("granularity"))
        assertEquals(97.0,row.getJSONObject("metrics").getJSONObject("oxygen_saturation").getDouble("value"),0.0)
        assertEquals("instant",row.getJSONObject("metrics").getJSONObject("oxygen_saturation").getString("statistic"))
        assertEquals("final-token",store.changeToken(HealthKind.BLOOD_OXYGEN,tokenKey(HealthKind.BLOOD_OXYGEN)))
    }
    @Test fun ownPackageAndOtherJuggVariantsAreExcludedButOriginalOtherAppsRemain()=runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record("self",context.packageName),record("dub","tk.glucodata.ng.dub"),record("normal","tk.glucodata.ng"),record("other","example.other"))
        HealthConnectReader(context,gateway).read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertEquals("example.other",records().single().getJSONObject("source").getString("app_id"))
    }
    @Test fun failedSecondPageDoesNotPublishPartialDataOrAdvanceToken()=runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record());val reader=HealthConnectReader(context,gateway)
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        gateway.rows=listOf(record("new"));gateway.fail=true
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertEquals(1,records().size);assertEquals("error",store.states().single().getString("status"))
        assertEquals("final-token",store.changeToken(HealthKind.BLOOD_OXYGEN,tokenKey(HealthKind.BLOOD_OXYGEN)))
    }
    @Test fun emptySuccessfulReadDoesNotDeleteButExplicitChangeDeletionDoes()=runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record());val reader=HealthConnectReader(context,gateway)
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        gateway.rows=emptyList();reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertEquals(1,records().size);assertEquals(0,store.states().single().getInt("returned_count"))
        gateway.deletes=listOf("record");reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertTrue(records().isEmpty())
    }
    @Test fun repeatedPageTokenFailsBoundedlyWhileBlankPageTokenEndsNormally()=runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record());gateway.repeat=true
        val reader=HealthConnectReader(context,gateway);reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertTrue(records().isEmpty());assertEquals(2,gateway.pageReads)
        gateway.repeat=false;gateway.emptyToken=true;reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertEquals(1,records().size)
    }
    @Test fun expiredChangeTokenRebuildsSnapshotAndRecordsPersistentDeletionGap()=runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record());gateway.expired=true
        val reader=HealthConnectReader(context,gateway);reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertEquals(1,records().size);assertFalse(store.states().single().isNull("deletion_history_gap"))
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertFalse(store.states().single().isNull("deletion_history_gap"))
    }
    @Test fun deniedPermissionSkipsReadAndOptionalHistoryDoesNotPretendCoverage()=runBlocking {
        val gateway=Gateway();gateway.grants=emptySet();val reader=HealthConnectReader(context,gateway)
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertEquals(0,gateway.pageReads);assertEquals("permission_denied",store.states().single().getString("status"))
        gateway.grants=reader.required(setOf(HealthKind.BLOOD_OXYGEN))
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),until.minusSeconds(90*86400L),until,store,{})
        assertEquals(until.minusSeconds(30*86400L),gateway.seenFrom);assertEquals("history_limited",store.states().single().getString("status"))
        context.getSharedPreferences("health_insights",0).edit().putBoolean("hc_read_history",true).commit()
        gateway.grants=gateway.grants+HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),until.minusSeconds(90*86400L),until,store,{})
        assertEquals(until.minusSeconds(90*86400L),gateway.seenFrom)
    }
    @Test fun changedRecordUpdatesOneStableIdAndMixedPermissionsReadOnlyGrantedTypes()=runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record());val reader=HealthConnectReader(context,gateway)
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        val id=records().single().getString("record_id")
        gateway.rows=emptyList();gateway.upserts=listOf(record(value=98.0));reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        assertEquals(id,records().single().getString("record_id"));assertEquals(98.0,records().single().getJSONObject("metrics").getJSONObject("oxygen_saturation").getDouble("value"),0.0)
        gateway.upserts=emptyList();gateway.grants=setOf(HealthPermission.getReadPermission(WeightRecord::class));gateway.rows=listOf(WeightRecord(from,null,Mass.kilograms(70.0),hcMetadata("weight")))
        reader.read(setOf(HealthKind.BODY_COMPOSITION),from,until,store,{})
        assertTrue(records().any { it.getJSONObject("metrics").has("weight") })
        assertEquals("permission_partial",store.states().first { it.getString("kind")=="body_composition" }.getString("status"))
    }
    @Test fun samplesSleepStagesAndTemperatureDeltasRetainDistinctSemantics() {
        val heart=HeartRateRecord(from,ZoneOffset.UTC,from.plusSeconds(60),ZoneOffset.UTC,listOf(HeartRateRecord.Sample(from.plusSeconds(1),60),HeartRateRecord.Sample(from.plusSeconds(2),80)),hcMetadata("heart"))
        val h=HealthConnectCanonical.capture(heart,HealthKind.HEART_RATE,store,until)
        assertEquals("point_samples",h.row.getJSONArray("series_refs").getJSONObject(0).getString("semantic_type"));assertEquals(2,h.children.size);assertEquals(70.0,h.row.getJSONObject("metrics").getJSONObject("heart_rate").getDouble("value"),0.0)
        val sleep=SleepSessionRecord(from,ZoneOffset.UTC,from.plusSeconds(600),ZoneOffset.UTC,hcMetadata("sleep"),stages=listOf(
            SleepSessionRecord.Stage(from,from.plusSeconds(300),SleepSessionRecord.STAGE_TYPE_LIGHT),
            SleepSessionRecord.Stage(from.plusSeconds(300),from.plusSeconds(420),SleepSessionRecord.STAGE_TYPE_AWAKE)))
        val s=HealthConnectCanonical.capture(sleep,HealthKind.SLEEP,store,until)
        assertEquals(300.0,s.row.getJSONObject("metrics").getJSONObject("estimated_asleep_duration").getDouble("value"),0.0)
        assertEquals(180.0,s.row.getJSONObject("metrics").getJSONObject("unclassified_duration").getDouble("value"),0.0)
        val skin=SkinTemperatureRecord(from,null,from.plusSeconds(600),null,hcMetadata("skin"),listOf(SkinTemperatureRecord.Delta(from.plusSeconds(1),TemperatureDelta.celsius(0.4))),Temperature.celsius(33.0))
        val t=HealthConnectCanonical.capture(skin,HealthKind.SKIN_TEMPERATURE,store,until)
        assertTrue(t.row.getJSONObject("metrics").has("skin_temperature_baseline"))
        assertTrue(t.children.single().getJSONObject("metrics").has("skin_temperature_delta"))
        assertFalse(t.children.single().getJSONObject("metrics").has("skin_temperature"))
        System.getProperty("health.hc.fixture")?.let { path ->
            val captures=listOf(h,s,t,HealthConnectCanonical.capture(record(),HealthKind.BLOOD_OXYGEN,store,until))
            java.io.File(path).writeText(captures.joinToString("\n",postfix="\n") { it.row.toString() })
            java.io.File("$path.series").writeText(captures.flatMap { it.children }.joinToString("\n",postfix="\n") { it.toString() })
        }
    }
    @Test fun bodyWaterMassIsNotInventedAsVolumeAndHydrationUsesMilliliters() {
        val mass=BodyWaterMassRecord(from,null,Mass.kilograms(40.0),hcMetadata("water-mass"))
        val m=HealthConnectCanonical.capture(mass,HealthKind.BODY_COMPOSITION,store,until).row.getJSONObject("metrics")
        assertEquals("kg",m.getJSONObject("body_water_mass").getString("unit"));assertFalse(m.has("total_body_water"))
        val hydration=HydrationRecord(from,null,from.plusSeconds(1),null,Volume.milliliters(250.0),hcMetadata("drink"))
        assertEquals(250.0,HealthConnectCanonical.capture(hydration,HealthKind.WATER_INTAKE,store,until).row.getJSONObject("metrics").getJSONObject("water_intake").getDouble("value"),0.0)
    }
    @Test fun manifestDeclaresEverySdkReadPermissionAndAddsNoWriteRoute() {
        val manifest=listOf(java.io.File("src/mobile/AndroidManifest.xml"),java.io.File("Common/src/mobile/AndroidManifest.xml")).first { it.isFile }.readText()
        val permissions=Regex("android:name=\"(android.permission.health.[^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        val reader=HealthConnectReader(context,Gateway())
        assertTrue(permissions.containsAll(reader.required(reader.supportedKinds)))
        assertTrue(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in permissions)
        assertEquals(setOf(HealthPermission.getWritePermission(BloodGlucoseRecord::class)),permissions.filter { ".WRITE_" in it }.toSet())
    }

    @Test fun glucoseUsesSdkUnitsAndSameAbsoluteTimeAcrossPhoneZoneChanges() {
        val old=java.util.TimeZone.getDefault()
        try {
            val instant=from.plusMillis(123)
            val record=BloodGlucoseRecord(instant,ZoneOffset.ofHours(7),hcMetadata("glucose"),BloodGlucose.millimolesPerLiter(7.7))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
            val first=HealthConnectCanonical.capture(record,HealthKind.BLOOD_GLUCOSE,store,until).row
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val second=HealthConnectCanonical.capture(record,HealthKind.BLOOD_GLUCOSE,store,until).row
            assertEquals(first.getString("record_id"),second.getString("record_id"))
            assertEquals(first.getJSONObject("time").toString(),second.getJSONObject("time").toString())
            assertEquals(instant.toEpochMilli(),second.getJSONObject("time").getLong("start_epoch_ms"))
            assertEquals("+07:00",second.getJSONObject("time").getString("original_offset"))
            assertEquals("mg/dL",second.getJSONObject("metrics").getJSONObject("glucose").getString("unit"))
            assertEquals(138.6,second.getJSONObject("metrics").getJSONObject("glucose").getDouble("value"),0.5)
        } finally { java.util.TimeZone.setDefault(old) }
    }

    @Test fun permissionServiceFailureRetainsCacheAndStillExportsLocalDataWithoutLeakingErrorDetails(): Unit =runBlocking {
        val gateway=Gateway();gateway.rows=listOf(record());val reader=HealthConnectReader(context,gateway)
        reader.read(setOf(HealthKind.BLOOD_OXYGEN),from,until,store,{})
        gateway.permissionFailure=true
        val c=HealthInsightCoordinator(context,adapterOverrides=mapOf(HealthDataSource.HEALTH_CONNECT to reader))
        c.selectSource(HealthDataSource.HEALTH_CONNECT);c.saveSelected(setOf(HealthKind.BLOOD_OXYGEN));c.settings.edit().putBoolean("enabled",true).commit()
        val db=androidx.room.Room.databaseBuilder(context,tk.glucodata.data.HistoryDatabase::class.java,"hc-permission-error-export.db").build()
        try {
            val file=HealthInsightExport.create(context,c,db)
            try { java.util.zip.ZipFile(file).use { zip ->
                val text=zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                val m=JSONObject(text)
                assertEquals(1,m.getJSONObject("counts").getInt("health_connect"));assertTrue(m.getBoolean("partial_refresh"))
                assertEquals("error",m.getJSONArray("health_connect_sync").getJSONObject(0).getString("status"))
                assertFalse(text.contains("synthetic-secret-token"));assertEquals(1,records().size)
            } } finally { file.delete() }
        } finally { db.close();c.store.close() }
    }

}
