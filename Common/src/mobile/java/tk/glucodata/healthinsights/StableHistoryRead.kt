package tk.glucodata.healthinsights

import android.database.sqlite.SQLiteDatabase
import tk.glucodata.data.HistoryDatabase

/**
 * An optimistic snapshot, without reserving Room's writer for the duration of a file export.
 * OPEN_READONLY has a single platform connection (no WAL pool), so data_version is compared
 * on the same connection. Room commits are external to that connection, including rewrites
 * and deletes. If any commit occurs between the two version reads, discard all generated
 * pages and retry. The block must replace, not append to, its previous output.
 *
 * No SQL transaction, journal-mode change, second SQLite implementation, or migration here.
 * A busy database fails the export rather than returning inconsistent pages or pausing CGM.
 */
internal object StableHistoryRead {
    suspend fun <T> run(database: HistoryDatabase, block: suspend () -> T): T {
        val path = database.openHelper.readableDatabase.path.orEmpty()
        require(path.isNotBlank() && path != ":memory:") { "Health export requires a persistent history database" }
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { observer ->
            repeat(3) {
                val before = observer.versionToken()
                val result = block()
                if (observer.versionToken() == before) return result
            }
        }
        error("血糖数据库正在更新，未生成不一致的导出；请稍后再试")
    }

    private fun SQLiteDatabase.versionToken(): Long = rawQuery("PRAGMA data_version", null).use {
        check(it.moveToFirst())
        it.getLong(0)
    }
}
