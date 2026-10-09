package tk.glucodata.healthinsights

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Writer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.util.UUID

/** Separate database: Samsung reads cannot hold a CGM/Health Connect transaction. */
class HealthInsightStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "health-insights.db", null, 1) {
    private val prefs = context.getSharedPreferences("health_insights", Context.MODE_PRIVATE)
    private val secret: ByteArray = synchronized(HealthInsightStore::class.java) {
        prefs.getString("alias_secret", null)?.let { android.util.Base64.decode(it, 0) }
            ?: ByteArray(32).also { SecureRandom().nextBytes(it); check(prefs.edit().putString("alias_secret", android.util.Base64.encodeToString(it, 2)).commit()) }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,json TEXT NOT NULL,at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX records_time ON records(at)")
        db.execSQL("CREATE TABLE series(parent TEXT NOT NULL,idx INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(parent,idx))")
        db.execSQL("CREATE TABLE relations(parent TEXT NOT NULL,child TEXT NOT NULL,relation TEXT NOT NULL,PRIMARY KEY(parent,child,relation))")
        db.execSQL("CREATE TABLE staged(run TEXT,id TEXT,kind TEXT,json TEXT,at INTEGER,deleted INTEGER,PRIMARY KEY(run,id))")
        db.execSQL("CREATE TABLE staged_series(run TEXT,parent TEXT,idx INTEGER,json TEXT,PRIMARY KEY(run,parent,idx))")
        db.execSQL("CREATE TABLE staged_relations(run TEXT,parent TEXT,child TEXT,relation TEXT,PRIMARY KEY(run,parent,child,relation))")
        db.execSQL("CREATE TABLE sync(kind TEXT PRIMARY KEY,success INTEGER NOT NULL DEFAULT 0,status TEXT,error TEXT,from_ms INTEGER,to_ms INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unexpected health cache migration") }
    fun alias(provider: String, kind: String, uid: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("$provider\u0000$kind\u0000$uid".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun begin(): String = UUID.randomUUID().toString()
    @Synchronized fun stage(run: String, kind: HealthKind, row: JSONObject, series: List<JSONObject> = emptyList()) {
        val id = row.getString("record_id")
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("staged", null, ContentValues().apply {
                put("run", run); put("id", id); put("kind", kind.kind); put("json", row.toString()); put("at", row.getJSONObject("time").let { time ->
                    if (time.optString("basis") == "local_date") java.time.LocalDate.parse(time.getString("date")).atStartOfDay(java.time.ZoneId.of(time.optString("query_timezone", java.time.ZoneId.systemDefault().id))).toInstant().toEpochMilli()
                    else time.optLong("start_epoch_ms", 0)
                }); put("deleted", 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("staged_series", "run=? AND parent=?", arrayOf(run, id))
            series.forEachIndexed { n, item -> db.insertOrThrow("staged_series", null, ContentValues().apply { put("run", run); put("parent", id); put("idx", n); put("json", item.toString()) }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun stageDelete(run: String, kind: HealthKind, uid: String) {
        val id = alias("samsung_health", kind.kind, uid)
        writableDatabase.insertWithOnConflict("staged", null, ContentValues().apply { put("run", run); put("id", id); put("kind", kind.kind); put("json", "{}"); put("at", 0); put("deleted", 1) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun associate(run: String, parent: String, child: String) {
        writableDatabase.insertWithOnConflict("staged_relations", null, ContentValues().apply { put("run", run); put("parent", parent); put("child", child); put("relation", "during_sleep") }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun complete(run: String, kind: HealthKind, from: Instant, until: Instant) {
        val db = writableDatabase; db.beginTransaction()
        try {
            // Replace series atomically with each parent revision. Never infer deletion from an empty read.
            db.execSQL("DELETE FROM series WHERE parent IN (SELECT id FROM staged WHERE run=?)", arrayOf(run))
            db.execSQL("DELETE FROM records WHERE id IN (SELECT id FROM staged WHERE run=? AND deleted=1)", arrayOf(run))
            db.execSQL("DELETE FROM relations WHERE parent IN (SELECT id FROM staged WHERE run=? AND deleted=1) OR child IN (SELECT id FROM staged WHERE run=? AND deleted=1)", arrayOf(run, run))
            db.execSQL("INSERT OR REPLACE INTO records SELECT id,kind,json,at FROM staged WHERE run=? AND deleted=0", arrayOf(run))
            db.execSQL("INSERT OR REPLACE INTO series SELECT parent,idx,json FROM staged_series WHERE run=? AND parent IN (SELECT id FROM staged WHERE run=? AND deleted=0)", arrayOf(run, run))
            db.execSQL("INSERT OR IGNORE INTO relations SELECT parent,child,relation FROM staged_relations WHERE run=? AND child IN (SELECT id FROM records) AND parent NOT IN (SELECT id FROM staged WHERE run=? AND deleted=1)", arrayOf(run, run))
            db.insertWithOnConflict("sync", null, ContentValues().apply { put("kind", kind.kind); put("success", until.toEpochMilli()); put("status", "success"); putNull("error"); put("from_ms", from.toEpochMilli()); put("to_ms", until.toEpochMilli()) }, SQLiteDatabase.CONFLICT_REPLACE)
            cleanup(db, run); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun cleanup(db: SQLiteDatabase, run: String) { listOf("staged", "staged_series", "staged_relations").forEach { db.delete(it, "run=?", arrayOf(run)) } }
    @Synchronized fun abandon(run: String) { cleanup(writableDatabase, run) }
    @Synchronized fun fail(kind: HealthKind, message: String, status: String = "error") {
        val db = writableDatabase
        db.insertWithOnConflict("sync", null, ContentValues().apply { put("kind", kind.kind) }, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("sync", ContentValues().apply { put("status", status); put("error", message) }, "kind=?", arrayOf(kind.kind))
    }
    @Synchronized fun contains(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM records WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    @Synchronized fun cursor(kind: HealthKind): Long = readableDatabase.rawQuery("SELECT success FROM sync WHERE kind=?", arrayOf(kind.kind)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    @Synchronized fun states(): List<JSONObject> = readableDatabase.rawQuery("SELECT kind,success,status,error,from_ms,to_ms FROM sync ORDER BY kind", null).use { c -> buildList { while (c.moveToNext()) add(JSONObject().put("kind", c.getString(0)).put("last_success_epoch_ms", c.getLong(1)).put("status", c.getString(2)).put("error", c.getString(3) ?: JSONObject.NULL).put("from_epoch_ms", c.getLong(4)).put("until_epoch_ms", c.getLong(5))) } }
    @Synchronized fun recent(from: Long): List<JSONObject> = readableDatabase.rawQuery("SELECT json FROM records WHERE at>=? ORDER BY at DESC LIMIT 150", arrayOf(from.toString())).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    /** Synchronous streaming snapshot under this cache's lock; no live CGM database lock. */
    @Synchronized fun snapshot(records: Writer, series: Writer, relations: Writer): Int {
        var count = 0
        readableDatabase.rawQuery("SELECT json FROM records ORDER BY kind,at,id", null).use { c -> while (c.moveToNext()) { records.write(c.getString(0)); records.write("\n"); count++ } }
        readableDatabase.rawQuery("SELECT json FROM series ORDER BY parent,idx", null).use { c -> while (c.moveToNext()) { series.write(c.getString(0)); series.write("\n") } }
        readableDatabase.rawQuery("SELECT parent,child,relation FROM relations ORDER BY parent,child", null).use { c -> while (c.moveToNext()) { relations.write(JSONObject().put("parent_record_id", c.getString(0)).put("record_id", c.getString(1)).put("relation", c.getString(2)).toString()); relations.write("\n") } }
        return count
    }
}
