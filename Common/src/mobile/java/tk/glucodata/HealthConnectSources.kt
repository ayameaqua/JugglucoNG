package tk.glucodata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.Device
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.anytime.AnytimeWearStore
import tk.glucodata.drivers.anytime.AnytimeRegistry
import tk.glucodata.drivers.anytime.AnytimeConstants
import tk.glucodata.drivers.sibionics.SibionicsRegistry
import java.util.UUID

/** Passive identity index. It never starts, ends or resets a physical sensor. */
internal class HealthConnectSources(context: Context) :
    SQLiteOpenHelper(context, "health-connect-sources.db", null, 1) {
    data class Source(val serial: String, val uid: String, val manufacturer: String?, val model: String?) {
        val shortId get() = "cgm-${uid.take(8)}"
        val label get() = listOfNotNull(manufacturer, model, shortId).joinToString(" · ")
        val device get() = Device(Device.TYPE_UNKNOWN, manufacturer, listOfNotNull(model, shortId).joinToString(" · "))
        val fingerprint get() = "$manufacturer\u0000$model\u0000$uid"
    }
    data class RecordSource(val recordId: String, val source: Source, val wearId: String?, val epochMs: Long, val uploaded: Boolean)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources(serial TEXT PRIMARY KEY, uid TEXT NOT NULL UNIQUE, manufacturer TEXT, model TEXT, published TEXT)")
        db.execSQL("CREATE TABLE aliases(alias TEXT PRIMARY KEY, serial TEXT NOT NULL)")
        db.execSQL("CREATE TABLE wears(id TEXT PRIMARY KEY, serial TEXT NOT NULL, start_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX wears_source_time ON wears(serial, start_ms)")
        db.execSQL("CREATE TABLE records(record_id TEXT PRIMARY KEY, serial TEXT NOT NULL, wear_id TEXT, epoch_ms INTEGER NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0, health_id TEXT)")
        db.execSQL("CREATE UNIQUE INDEX records_health_id ON records(health_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun register(serial: String, alias: String = serial, manufacturer: String? = null, model: String? = null): Source {
        require(serial.isNotBlank() && alias.isNotBlank())
        val aliasSerial = readableDatabase.query("aliases", arrayOf("serial"), "alias=?", arrayOf(alias), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }
        val previous = bySerial(serial) ?: aliasSerial?.let(::bySerial)
        val source = Source(serial, previous?.uid ?: UUID.randomUUID().toString(), manufacturer ?: previous?.manufacturer, model ?: previous?.model)
        writableDatabase.beginTransaction()
        try {
            if (previous != null && previous.serial != serial) {
                // Learning the canonical name must retain the original identity.
                for (table in listOf("sources", "aliases", "wears", "records")) writableDatabase.update(table,
                    ContentValues().apply { put("serial", serial) }, "serial=?", arrayOf(previous.serial))
            }
            val values = ContentValues().apply { put("serial", serial); put("uid", source.uid); put("manufacturer", source.manufacturer); put("model", source.model) }
            if (previous == null) check(writableDatabase.insertOrThrow("sources", null, values) != -1L)
            else writableDatabase.update("sources", values, "serial=?", arrayOf(serial))
            check(writableDatabase.insertWithOnConflict("aliases", null, ContentValues().apply { put("alias", alias); put("serial", serial) }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return source
    }

    @Synchronized fun observeWear(source: Source, startMs: Long, explicitId: String? = null) {
        if (startMs <= 0) return // Unknown historical ownership stays unknown.
        val known = readableDatabase.query("wears", arrayOf("id"), "serial=? AND ABS(start_ms-?)<60000", arrayOf(source.serial, startMs.toString()), null, null, null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        // A driver's explicit UUID takes precedence; a small clock refinement is not a new wear.
        val id = explicitId ?: known ?: UUID.randomUUID().toString()
        writableDatabase.insertWithOnConflict("wears", null, ContentValues().apply { put("id", id); put("serial", source.serial); put("start_ms", startMs) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun wearAt(serial: String, epochMs: Long): String? = readableDatabase.query("wears", arrayOf("id"),
        "serial=? AND start_ms<=?", arrayOf(serial, epochMs.toString()), null, null, "start_ms DESC", "1").use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized fun needsReplay(source: Source): Boolean = readableDatabase.query("sources", arrayOf("published"), "serial=?", arrayOf(source.serial), null, null, null).use {
        !it.moveToFirst() || it.getString(0) != source.fingerprint
    }
    @Synchronized fun markPublished(source: Source) { writableDatabase.update("sources", ContentValues().apply { put("published", source.fingerprint) }, "serial=?", arrayOf(source.serial)) }

    /** Keep exact legacy clientRecordIds; identity is additional local metadata. */
    @Synchronized fun index(source: Source, records: List<BloodGlucoseRecord>, uploaded: Boolean, healthIds: List<String>? = null) {
        writableDatabase.beginTransaction()
        try { records.forEachIndexed { n, record ->
            val id = requireNotNull(record.metadata.clientRecordId)
            val epochMs = record.time.toEpochMilli()
            val existed = lookup(id)
            val values = ContentValues().apply {
                put("record_id", id); put("serial", source.serial); put("epoch_ms", epochMs)
                put("wear_id", existed?.wearId ?: wearAt(source.serial, epochMs))
                put("uploaded", if (uploaded) 1 else 0)
            }
            // Providers return IDs in input order. Never guess a mapping if a
            // provider returns an unexpected number or empty IDs.
            if (healthIds?.size == records.size && healthIds[n].isNotBlank()) values.put("health_id", healthIds[n])
            if (existed == null) check(writableDatabase.insertOrThrow("records", null, values) != -1L)
            else writableDatabase.update("records", values, "record_id=?", arrayOf(id))
        }; writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun bySerial(serial: String): Source? = readableDatabase.query("sources", arrayOf("serial", "uid", "manufacturer", "model"), "serial=?", arrayOf(serial), null, null, null).use {
        if (it.moveToFirst()) Source(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) else null
    }
    @Synchronized fun all(): List<Source> = readableDatabase.rawQuery("SELECT serial,uid,manufacturer,model FROM sources ORDER BY serial", null).use { c ->
        buildList { while (c.moveToNext()) add(Source(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
    }
    @Synchronized fun find(query: String): Source? = all().filter { it.uid.equals(query, true) || it.shortId.equals(query, true) || it.serial == query }.singleOrNull()
        ?: lookup(query)?.source
    @Synchronized fun lookup(id: String): RecordSource? = readableDatabase.query("records", arrayOf("record_id", "serial", "wear_id", "epoch_ms", "uploaded"), "record_id=? OR health_id=?", arrayOf(id, id), null, null, null).use {
        if (it.moveToFirst()) bySerial(it.getString(1))?.let { source -> RecordSource(it.getString(0), source, it.getString(2), it.getLong(3), it.getInt(4) != 0) } else null
    }

    companion object {
        fun resolve(context: Context, store: HealthConnectSources, alias: String, sensorptr: Long = 0L): Source {
            val serial = SensorIdentity.resolveAppSensorId(alias) ?: alias
            val managed = runCatching { ManagedSensorRuntime.resolveUiSnapshot(serial) }.getOrNull()
            val backingPtr = sensorptr.takeIf { it != 0L } ?: runCatching { Natives.str2sensorptr(alias) }.getOrDefault(0L)
            val persistedFamily = runCatching { ManagedSensorUiFamily.fromNativeCode(Natives.getSensorManagedFamilyFromSensorptr(backingPtr)) }.getOrDefault(ManagedSensorUiFamily.GENERIC)
            val anytime = AnytimeRegistry.persistedRecords(context).firstOrNull { it.matchesId(serial) }
            val sibionics = SibionicsRegistry.persistedRecords(context).firstOrNull { it.matchesId(serial) }
            val vendor = managed?.let { SensorVendor.fromManagedFamily(it.uiFamily) }
                ?: when { anytime != null -> SensorVendor.YUWELL; sibionics != null -> SensorVendor.SIBIONICS
                    persistedFamily != ManagedSensorUiFamily.GENERIC -> SensorVendor.fromManagedFamily(persistedFamily)
                    else -> null }
                ?: SensorVendor.fromNativeKind(runCatching { SensorSourceResolver.resolveSensorKind(alias, -1) }.getOrDefault(-1))
            val manufacturer = when (vendor) {
                SensorVendor.ABBOTT -> "Abbott"; SensorVendor.YUWELL -> "Yuwell"; SensorVendor.SIBIONICS -> "Sibionics"
                SensorVendor.DEXCOM -> "Dexcom"; SensorVendor.ROCHE -> "Roche"; SensorVendor.MICROTECH -> "MicroTech"
                SensorVendor.SINOCARE -> "Sinocare"; SensorVendor.GLUTEC -> "Glutec"; SensorVendor.OTTAI -> "Ottai"
                else -> null
            }
            val model = managed?.vendorModel?.takeIf(String::isNotBlank)
                ?: anytime?.let { AnytimeConstants.resolveFamily(AnytimeRegistry.loadDeviceName(context, it.sensorId)).family.takeIf { family -> family != AnytimeConstants.Family.UNKNOWN }?.name }
                ?: sibionics?.variant?.displayLabel
                ?: when (runCatching { SensorSourceResolver.resolveSensorKind(alias, -1) }.getOrDefault(-1)) {
                SensorSourceResolver.SENSOR_KIND_LIBRE2 -> "FreeStyle Libre 2"; SensorSourceResolver.SENSOR_KIND_LIBRE3 -> "FreeStyle Libre 3"
                else -> null
            }
            val source = store.register(serial, alias, manufacturer, model)
            if (vendor == SensorVendor.YUWELL || source.manufacturer == "Yuwell") {
                AnytimeWearStore.knownSessions(context, serial).forEach { (id, start) -> store.observeWear(source, start, id) }
            }
            val start = managed?.startTimeMs?.takeIf { it > 0 }
                ?: anytime?.let { AnytimeRegistry.loadTimelineStartAt(context, it.sensorId).takeIf { ms -> ms > 0 } }
                ?: sibionics?.let { SibionicsRegistry.loadStartTimeMs(context, it.sensorId).takeIf { ms -> ms > 0 } }
                ?: if (backingPtr != 0L) runCatching { Natives.getSensorStartmsecFromSensorptr(backingPtr) }.getOrDefault(0L) else 0L
            store.observeWear(source, start, if (vendor == SensorVendor.YUWELL) AnytimeWearStore.session(context, serial) else null)
            return source
        }
    }
}
