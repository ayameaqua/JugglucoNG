package tk.glucodata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stable IDs require a higher revision to replace a value already in Health Connect. */
internal class HealthConnectRecordVersions(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(context, "health-connect-versions.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE revisions (record_id TEXT PRIMARY KEY, mgdl INTEGER NOT NULL, revision INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Commit the whole native snapshot before upload; a failed upload reuses these revisions. */
    @Synchronized
    fun <T> batch(block: ((String, Int) -> Long) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block { id, mgdl ->
                val previous = db.query("revisions", arrayOf("mgdl", "revision"),
                    "record_id = ?", arrayOf(id), null, null, null).use {
                    if (it.moveToFirst()) it.getInt(0) to it.getLong(1) else null
                }
                if (previous?.first == mgdl) previous.second else {
                    val revision = maxOf(now().coerceAtLeast(1), (previous?.second ?: 0) + 1)
                    db.insertWithOnConflict("revisions", null, ContentValues().apply {
                        put("record_id", id); put("mgdl", mgdl); put("revision", revision)
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                    revision
                }
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
}
