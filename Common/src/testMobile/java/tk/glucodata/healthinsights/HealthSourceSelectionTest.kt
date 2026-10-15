package tk.glucodata.healthinsights

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.data.HistoryDatabase
import java.time.Instant
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,manifest=Config.NONE)
class HealthSourceSelectionTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val stamp=Instant.parse("2026-10-14T00:00:00Z")
    private class Reader(val provider:String):HealthReadAdapter {
        var reads=0;var canRead=true
        override val available get()=canRead
        override val availabilityMessage="unavailable"
        override suspend fun granted(kinds:Set<HealthKind>)=kinds
        override suspend fun request(activity:Activity,kinds:Set<HealthKind>)=kinds
        override suspend fun read(kinds:Set<HealthKind>,from:Instant,until:Instant,store:HealthInsightStore,progress:(String)->Unit) {
            reads++
            val kind=HealthKind.HEART_RATE;val run=store.begin()
            val row=HealthCanonical.base(store.alias(provider,kind.kind,"same-id"),kind.kind,HealthCanonical.time(from,from,null),provider,until)
            store.stage(run,kind,row);store.complete(run,kind,from,until)
        }
    }
    @Before fun reset(){context.deleteDatabase("health-insights.db");context.deleteDatabase("health-insights-hc.db");context.getSharedPreferences("health_insights",0).edit().clear().commit()}
    @Test fun freshInstallDefaultsToAndroidAndLegacySamsungSelectionIsPreserved() {
        val c=HealthInsightCoordinator(context)
        assertEquals(HealthDataSource.HEALTH_CONNECT,c.source())
        c.settings.edit().putBoolean("enabled",true).commit()
        assertEquals(HealthDataSource.HEALTH_CONNECT,c.source())
        assertEquals(HealthDataSource.HEALTH_CONNECT,HealthInsightCoordinator(context).source())
        c.settings.edit().remove("health_source").putStringSet("selected",setOf("HEART_RATE")).commit()
        assertEquals(HealthDataSource.SAMSUNG,HealthInsightCoordinator(context).source())
    }
    @Test fun onlyOneReaderRunsSwitchPersistsAndInactiveCacheCannotEnterActiveTimeline(): Unit =runBlocking {
        val samsung=Reader("samsung_health");val hc=Reader("health_connect")
        val overrides=mapOf(HealthDataSource.SAMSUNG to samsung,HealthDataSource.HEALTH_CONNECT to hc)
        val c=HealthInsightCoordinator(context,adapterOverrides=overrides)
        c.selectSource(HealthDataSource.SAMSUNG);c.settings.edit().putBoolean("enabled",true).commit();c.saveSelected(setOf(HealthKind.HEART_RATE))
        c.refresh(true);assertEquals(1,samsung.reads);assertEquals(0,hc.reads)
        val samsungId=c.store.recent(0).single().getString("record_id")
        c.selectSource(HealthDataSource.HEALTH_CONNECT);c.saveSelected(setOf(HealthKind.HEART_RATE));assertTrue(c.store.recent(0).isEmpty())
        c.refresh(false);assertEquals(1,samsung.reads);assertEquals(1,hc.reads)
        assertNotEquals(samsungId,c.store.recent(0).single().getString("record_id"))
        assertEquals(1,c.storeFor(HealthDataSource.SAMSUNG).recent(0).size)
        val restarted=HealthInsightCoordinator(context,adapterOverrides=overrides)
        assertEquals(HealthDataSource.HEALTH_CONNECT,restarted.source())
        assertEquals(1,restarted.store.recent(0).size)
        c.cachedStores().forEach { it.second.close() };restarted.store.close()
    }
    @Test fun disabledAndUnavailableSkipSelectedReaderAndKeepItsCache(): Unit =runBlocking {
        val reader=Reader("health_connect");val c=HealthInsightCoordinator(context,adapterOverrides=mapOf(HealthDataSource.HEALTH_CONNECT to reader))
        c.selectSource(HealthDataSource.HEALTH_CONNECT);c.saveSelected(setOf(HealthKind.HEART_RATE))
        c.refresh(true);assertEquals(0,reader.reads)
        c.settings.edit().putBoolean("enabled",true).commit();c.refresh(true);assertEquals(1,reader.reads)
        reader.canRead=false;c.refresh(true);assertEquals(1,reader.reads);assertEquals(1,c.store.recent(0).size);assertEquals("unavailable",c.store.states().single().getString("status"))
        c.store.close()
    }
    @Test fun fullExportKeepsProviderStreamsSeparateAndDeclaresActiveSource(): Unit =runBlocking {
        val samsung=Reader("samsung_health");val hc=Reader("health_connect")
        val c=HealthInsightCoordinator(context,adapterOverrides=mapOf(HealthDataSource.SAMSUNG to samsung,HealthDataSource.HEALTH_CONNECT to hc))
        c.selectSource(HealthDataSource.SAMSUNG);c.settings.edit().putBoolean("enabled",true).commit();c.saveSelected(setOf(HealthKind.HEART_RATE));c.refresh(true)
        c.selectSource(HealthDataSource.HEALTH_CONNECT);c.saveSelected(setOf(HealthKind.HEART_RATE));c.refresh(true)
        val db=Room.databaseBuilder(context,HistoryDatabase::class.java,"dual-source-export.db").build()
        try {
            val file=HealthInsightExport.create(context,c,db)
            try { ZipFile(file).use { zip ->
                fun text(name:String)=zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
                val manifest=JSONObject(text("manifest.json"))
                assertEquals("health_connect",manifest.getJSONObject("health_source").getString("selected_provider"))
                assertEquals(1,manifest.getJSONObject("counts").getInt("health_connect"));assertEquals(1,manifest.getJSONObject("counts").getInt("samsung_health"))
                assertEquals("health_connect",JSONObject(text("health-connect-records.jsonl").trim()).getJSONObject("source").getString("provider"))
                assertEquals("samsung_health",JSONObject(text("samsung-records.jsonl").trim()).getJSONObject("source").getString("provider"))
                assertFalse(text("health-connect-records.jsonl").contains("samsung_documented_metrics_v1"))
                assertEquals(1,samsung.reads);assertEquals(2,hc.reads)
                assertFalse(manifest.getBoolean("samsung_read_enabled"))
                System.getProperty("health.dual.fixture")?.let { file.copyTo(java.io.File(it),true) }
            } } finally { file.delete() }
        } finally {db.close();c.cachedStores().forEach { it.second.close() }}
    }
    @Test fun oldSamsungCacheUpgradeKeepsRecordsSettingsAliasesAndAddsTransactionalChangeState() {
        var store=HealthInsightStore(context)
        val run=store.begin();val row=HealthCanonical.base("held","heart_rate",HealthCanonical.time(stamp,stamp,"+08:00"),"samsung_health",stamp)
        store.stage(run,HealthKind.HEART_RATE,row);store.complete(run,HealthKind.HEART_RATE,stamp,stamp)
        val alias=store.alias("juggluco","sensor","existing");store.writableDatabase.execSQL("DROP TABLE change_state");store.writableDatabase.version=2;store.close()
        store=HealthInsightStore(context)
        assertTrue(store.contains("held"));assertEquals(alias,store.alias("juggluco","sensor","existing"))
        val next=store.begin();store.complete(next,HealthKind.HEART_RATE,stamp,stamp,"test-token")
        store.close();store=HealthInsightStore(context);assertEquals("test-token",store.changeToken(HealthKind.HEART_RATE));store.close()
    }
    @Test fun recentWindowKeepsSleepAndExerciseEvenWhenManyNewerHeartRowsExist() {
        val store=HealthInsightStore(context,"health_connect");val run=store.begin()
        for((id,kind,time) in listOf(Triple("sleep",HealthKind.SLEEP,stamp),Triple("exercise",HealthKind.EXERCISE,stamp.plusSeconds(1)))) {
            store.stage(run,kind,HealthCanonical.base(id,kind.kind,HealthCanonical.time(time,time,null),"health_connect",stamp))
        }
        repeat(200) { n -> val time=stamp.plusSeconds(10+n.toLong());store.stage(run,HealthKind.HEART_RATE,HealthCanonical.base("hr-$n","heart_rate",HealthCanonical.time(time,time,null),"health_connect",stamp)) }
        store.complete(run,HealthKind.HEART_RATE,stamp,stamp.plusSeconds(300))
        val rows=store.recent(stamp.toEpochMilli());assertTrue(rows.any { it.getString("record_id")=="sleep" });assertTrue(rows.any { it.getString("record_id")=="exercise" });assertEquals(152,rows.size)
        store.close()
    }
    @Test fun sourceSelectionsSurviveIndependentEditsAndRestart(): Unit =runBlocking {
        val reader=Reader("unused")
        val c=HealthInsightCoordinator(context,adapterOverrides=HealthDataSource.entries.associateWith { reader })
        c.selectSource(HealthDataSource.SAMSUNG);c.saveSelected(setOf(HealthKind.SLEEP))
        c.selectSource(HealthDataSource.HEALTH_CONNECT);c.saveSelected(setOf(HealthKind.HEART_RATE))
        val restarted=HealthInsightCoordinator(context,adapterOverrides=HealthDataSource.entries.associateWith { reader })
        assertEquals(setOf(HealthKind.HEART_RATE),restarted.selected())
        restarted.selectSource(HealthDataSource.SAMSUNG)
        assertEquals(setOf(HealthKind.SLEEP),restarted.selected())
    }

}
