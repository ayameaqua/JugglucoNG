package tk.glucodata.healthinsights

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.HealthConnectSources
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.HistoryReading
import tk.glucodata.data.ReadingDisplay
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile

/** Opt-in regression against a private capture. No real capture is a repository resource. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HealthExportCapturedSnapshotTest {
    @Test fun replayPrivateCapturePreservesAllValuesTimesSourceAttributesAndSeriesWhileRepairingExport(): Unit = runBlocking {
        val input = System.getProperty("health.audit.capture")
        assumeTrue("Private capture is optional and never bundled", !input.isNullOrBlank())
        val ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("health-insights.db");ctx.deleteDatabase("health-connect-sources.db")
        val adapter = object : SamsungReadAdapter {
            override val available=false
            override val availabilityMessage="offline capture replay"
            override suspend fun granted(kinds:Set<HealthKind>)=emptySet<HealthKind>()
            override suspend fun request(activity:Activity,kinds:Set<HealthKind>)=emptySet<HealthKind>()
            override suspend fun read(kinds:Set<HealthKind>,from:Instant,until:Instant,store:HealthInsightStore,progress:(String)->Unit)=error("never read live Samsung in replay")
        }
        ctx.getSharedPreferences("health_insights",Context.MODE_PRIVATE).edit().clear().commit()
        val coordinator=HealthInsightCoordinator(ctx,adapter)
        val db=Room.databaseBuilder(ctx,HistoryDatabase::class.java,"capture-replay.db").build()
        try { ZipFile(input!!).use { original ->
            fun rows(zip:ZipFile,name:String)=zip.getInputStream(zip.getEntry(name)).bufferedReader().use { reader -> reader.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }
            val glucose=rows(original,"glucose.jsonl")
            val sources=rows(original,"glucose-sources.jsonl").associateBy { it.getString("source_uid_alias") }
            val canonical=glucose.groupBy { it.getJSONObject("source").getString("source_uid_alias") }.mapValues { (_,list)->list.first().getJSONObject("source").getString("sensor_alias") }
            val resolve=glucose.associate { val s=it.getJSONObject("source");s.getString("sensor_alias") to canonical.getValue(s.getString("source_uid_alias")) }
            HealthConnectSources(ctx).use { catalog -> canonical.forEach { (uid,serial)->
                val source=sources[uid];catalog.register(serial,manufacturer=source?.optString("manufacturer"),model=source?.optString("model"))
            } }
            db.historyDao().insertAll(glucose.map { r ->
                val m=r.getJSONObject("metrics");HistoryReading(timestamp=r.getJSONObject("time").getLong("start_epoch_ms"),sensorSerial=r.getJSONObject("source").getString("sensor_alias"),
                    value=m.getJSONObject("glucose").getDouble("value").toFloat(),rawValue=m.getJSONObject("device_glucose").getDouble("value").toFloat(),rate=m.optJSONObject("rate")?.optDouble("value")?.toFloat(),firstStoredAt=1)
            })
            val displays=rows(original,"recorded-display.jsonl")
            db.readingDisplayDao().sealAll(displays.map { r -> ReadingDisplay(r.getLong("timestamp_epoch_ms"),r.getString("sensor_alias"),r.getDouble("display_mgdl").toFloat(),r.getInt("view_mode"),r.getLong("calibration_fingerprint"),r.getLong("recorded_at_epoch_ms")) })
            // Stage complete captured parent/series revisions, then run the actual production snapshot writer.
            val parents=rows(original,"samsung-records.jsonl").associateBy { it.getString("record_id") }
            val kindRuns=parents.values.map { it.getString("kind") }.distinct().associateWith { coordinator.store.begin() }
            parents.values.forEach { r -> coordinator.store.stage(kindRuns.getValue(r.getString("kind")),HealthKind.entries.single { it.kind==r.getString("kind") },r) }
            val childIndexes=mutableMapOf<String,Int>()
            val sql=coordinator.store.writableDatabase
            sql.beginTransaction()
            try {
            original.getInputStream(original.getEntry("series.jsonl")).bufferedReader().use { reader -> reader.forEachLine { line ->
                val child=JSONObject(line);val parent=parents.getValue(child.getString("parent_record_id"));val run=kindRuns.getValue(parent.getString("kind"))
                // Cache table format is internal; stream captured children without retaining a 70MB list.
                val next=childIndexes[parent.getString("record_id")]?:0
                childIndexes[parent.getString("record_id")]=next+1
                sql.execSQL("INSERT INTO staged_series VALUES (?,?,?,?)",arrayOf<Any>(run,parent.getString("record_id"),next,child.toString()))
            } }
            sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            kindRuns.forEach { (kind,run)->coordinator.store.complete(run,HealthKind.entries.single { it.kind==kind },Instant.EPOCH,Instant.now()) }
            rows(original,"relations.jsonl").forEach { link -> coordinator.store.writableDatabase.execSQL("INSERT OR IGNORE INTO relations VALUES (?,?,?)",
                arrayOf(link.getString("parent_record_id"),link.getString("record_id"),link.getString("relation"))) }
            val file=HealthInsightExport.create(ctx,coordinator,db, resolveSource = { _, store, alias -> store.register(resolve[alias] ?: alias, alias) })
            try { ZipFile(file).use { fixed ->
                val actual=rows(fixed,"glucose.jsonl")
                assertEquals(glucose.size,actual.size)
                val originalByKey=glucose.associateBy { it.getJSONObject("source").getString("sensor_alias")+":"+it.getJSONObject("time").getLong("start_epoch_ms") }
                actual.forEach { r ->
                    val originalAlias=resolve.keys.single { coordinator.store.alias("juggluco","sensor",it)==r.getJSONObject("source").getString("sensor_alias") }
                    val held=originalByKey.getValue(originalAlias+":"+r.getJSONObject("time").getLong("start_epoch_ms"))
                    assertEquals(held.getJSONObject("time").toString(),r.getJSONObject("time").toString())
                    for(lane in listOf("glucose","device_glucose")) assertEquals(held.getJSONObject("metrics").getJSONObject(lane).getDouble("value"),r.getJSONObject("metrics").getJSONObject(lane).getDouble("value"),1e-4)
                }
                val aliases=actual.map { it.getJSONObject("source").getString("sensor_alias") }.toSet()
                val catalog=rows(fixed,"glucose-sources.jsonl")
                assertEquals(aliases,catalog.map { it.getString("sensor_alias") }.toSet());assertEquals(aliases.size,catalog.size)
                val shown=rows(fixed,"recorded-display.jsonl")
                assertEquals(displays.size,shown.size);assertTrue(shown.all { it.getInt("association_candidate_count")==1 && !it.isNull("source_record_id") })
                val actualIds=actual.map { it.getString("record_id") }.toSet();assertTrue(shown.all { it.getString("source_record_id") in actualIds })
                val health=rows(fixed,"samsung-records.jsonl")
                assertEquals(parents.size,health.size)
                health.forEach { r -> assertEquals(parents.getValue(r.getString("record_id")).getJSONObject("attributes").get("sdk_fields").toString(),r.getJSONObject("attributes").get("sdk_fields").toString()) }
                val count=fixed.getInputStream(fixed.getEntry("series.jsonl")).bufferedReader().use { it.lineSequence().count(String::isNotBlank) }
                val expected=original.getInputStream(original.getEntry("series.jsonl")).bufferedReader().use { it.lineSequence().count(String::isNotBlank) }
                assertEquals(expected,count)
                assertEquals(rows(original,"relations.jsonl").size,rows(fixed,"relations.jsonl").size)
                System.getProperty("health.audit.output")?.let { file.copyTo(File(it),true) }
            } } finally { file.delete() }
        } } finally { db.close();ctx.deleteDatabase("capture-replay.db");coordinator.store.close() }
    }
}
