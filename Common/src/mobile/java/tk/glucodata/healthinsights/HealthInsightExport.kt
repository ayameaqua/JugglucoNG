package tk.glucodata.healthinsights

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.HealthConnectSources
import tk.glucodata.data.HistoryDatabase
import java.io.File
import java.io.BufferedWriter
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

internal object HealthInsightExport {
    suspend fun create(context: Context, coordinator: HealthInsightCoordinator, database: HistoryDatabase? = null): File = coordinator.afterRefreshSnapshot {
        withContext(Dispatchers.IO) {
            val cutoff = Instant.now()
            val folder = File(context.cacheDir, "health-export-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val output = File(context.cacheDir, "juggluco-health-export-${cutoff.toEpochMilli()}.zip")
            val sourceStore = HealthConnectSources(context)
            val sourceCatalog = mutableMapOf<String, HealthConnectSources.Source>()
            try {
                var samsungCount = 0
                File(folder, "samsung-records.jsonl").bufferedWriter().use { records ->
                    File(folder, "series.jsonl").bufferedWriter().use { series ->
                        File(folder, "relations.jsonl").bufferedWriter().use { relations -> samsungCount = coordinator.store.snapshot(records, series, relations) }
                    }
                }
                val states = coordinator.store.states()
                val db = database ?: HistoryDatabase.getInstance(context)
                var glucoseCount = 0; var journalCount = 0
                var min = Long.MAX_VALUE; var max = 0L
                // Separate source snapshot validated against concurrent Room commits; no writer transaction.
                StableHistoryRead.run(db) {
                    glucoseCount = 0; journalCount = 0; min = Long.MAX_VALUE; max = 0L
                    File(folder, "recorded-display.jsonl").bufferedWriter().use { displayWriter ->
                        File(folder, "uncertainty.jsonl").bufferedWriter().use { uncertaintyWriter ->
                            File(folder, "glucose.jsonl").bufferedWriter().use { writer ->
                                File(folder, "journal.jsonl").bufferedWriter().use { journal ->
                                    var after = 0L
                                    while (true) {
                                        val page = db.historyDao().healthExportPage(after, cutoff.toEpochMilli(), 1000)
                                        if (page.isEmpty()) break
                                        for (reading in page) {
                                            val sensor = sourceCatalog.getOrPut(reading.sensorSerial) { HealthConnectSources.resolve(context, sourceStore, reading.sensorSerial) }
                                            after = reading.id
                                            val stamp = Instant.ofEpochMilli(reading.timestamp)
                                            val id = coordinator.store.alias("juggluco", "blood_glucose", "${reading.sensorSerial}:${reading.timestamp}")
                                            val row = HealthCanonical.base(id, "blood_glucose", HealthCanonical.time(stamp, stamp, null), "juggluco", cutoff)
                                            row.put("granularity", "point")
                                            row.getJSONObject("source").put("app_id", BuildConfig.APPLICATION_ID).put("sensor_alias", coordinator.store.alias("juggluco", "sensor", reading.sensorSerial)).put("method", reading.source)
                                            row.getJSONObject("source").put("source_uid_alias", coordinator.store.alias("juggluco", "source_uid", sensor.uid))
                                                .put("local_lookup_id", sensor.shortId)
                                                .put("wear_session_alias", sourceStore.wearAt(sensor.serial, reading.timestamp)?.let { coordinator.store.alias("juggluco", "wear_session", it) } ?: JSONObject.NULL)
                                            row.getJSONObject("metrics").put("glucose", HealthCanonical.metric(reading.value, "mg/dL")).put("device_glucose", HealthCanonical.metric(reading.rawValue, "mg/dL"))
                                            row.getJSONObject("attributes").put("glucose_policy", "stored_auto_and_raw_mgdl_v1").put("display_calibration_applied", false).put("channel_semantics", "stored Auto value and separate raw lane; not an extra conversion or manual time correction")
                                            reading.rate?.let { row.getJSONObject("metrics").put("rate", HealthCanonical.metric(it, "mg/dL/min")) }
                                            row.getJSONObject("quality").put("normalization", "complete")
                                            writer.line(row); glucoseCount++; min = minOf(min, reading.timestamp); max = maxOf(max, reading.timestamp)
                                        }
                                    }
                                    var displayAfter = -1L
                                    while (true) {
                                        val page = db.readingDisplayDao().getRecoveryPage(displayAfter, 1000)
                                        if (page.isEmpty()) break
                                        for (display in page) {
                                            displayAfter = display.timestamp
                                            displayWriter.line(JSONObject().put("timestamp_epoch_ms", display.timestamp)
                                                .put("sensor_alias", coordinator.store.alias("juggluco", "sensor", display.sensorSerial))
                                                .put("display_mgdl", display.displayMgdl).put("view_mode", display.viewMode)
                                                .put("calibration_fingerprint", display.calibrationFingerprint).put("recorded_at_epoch_ms", display.recordedAt))
                                        }
                                    }
                                    var uncertaintyAfter = -1L; var uncertaintySensor = ""
                                    while (true) {
                                        val page = db.readingUncertaintyDao().getRecoveryPage(uncertaintyAfter, uncertaintySensor, 1000)
                                        if (page.isEmpty()) break
                                        for (value in page) {
                                            uncertaintyAfter = value.timestamp; uncertaintySensor = value.sensorSerial
                                            uncertaintyWriter.line(JSONObject().put("timestamp_epoch_ms", value.timestamp)
                                                .put("sensor_alias", coordinator.store.alias("juggluco", "sensor", value.sensorSerial))
                                                .put("lower_mgdl", value.lowerMgdl).put("upper_mgdl", value.upperMgdl).put("interval_mass", value.intervalMass)
                                                .put("confidence", value.confidence ?: JSONObject.NULL).put("artifact_probability", value.artifactProbability ?: JSONObject.NULL))
                                        }
                                    }
                                    var journalAfter = 0L
                                    while (true) {
                                        val page = db.journalDao().getRecoveryEntriesPage(journalAfter, 1000)
                                        if (page.isEmpty()) break
                                        for (entry in page) {
                                            journalAfter = entry.id
                                            if (entry.createdAt > cutoff.toEpochMilli()) continue
                                            val id = coordinator.store.alias("juggluco", "journal", entry.recoveryId ?: entry.id.toString())
                                            val end = Instant.ofEpochMilli(entry.timestamp + (entry.durationMinutes ?: 0) * 60_000L)
                                            val row = HealthCanonical.base(id, "journal_${entry.entryType}", HealthCanonical.time(Instant.ofEpochMilli(entry.timestamp), end, null), "juggluco", cutoff)
                                            row.getJSONObject("source").put("app_id", BuildConfig.APPLICATION_ID).put("method", entry.originSource ?: entry.source)
                                            row.getJSONObject("attributes").put("title", entry.title).put("note", entry.note ?: JSONObject.NULL).put("amount", entry.amount ?: JSONObject.NULL).put("entry_type", entry.entryType)
                                                .put("duration_minutes", entry.durationMinutes ?: JSONObject.NULL).put("intensity", entry.intensity ?: JSONObject.NULL)
                                                .put("protein_grams", entry.proteinGrams ?: JSONObject.NULL).put("fat_grams", entry.fatGrams ?: JSONObject.NULL)
                                                .put("insulin_curve_snapshot", entry.insulinCurveJsonSnapshot ?: JSONObject.NULL)
                                            entry.glucoseValueMgDl?.let { row.getJSONObject("metrics").put("glucose", HealthCanonical.metric(it, "mg/dL")) }
                                            val journalFields = journalFields(entry)
                                            for (key in listOf("sensorSerial", "sourceRecordId", "recoveryId", "nsRemoteId")) {
                                                val raw = journalFields.optString(key)
                                                if (raw.isNotEmpty() && raw != "null") journalFields.put(key, coordinator.store.alias("juggluco", key, raw))
                                            }
                                            row.getJSONObject("attributes").put("journal_fields", journalFields)
                                            journal.line(row); journalCount++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                File(folder, "glucose-sources.jsonl").bufferedWriter().use { writer -> sourceCatalog.values.forEach { source ->
                    writer.line(JSONObject().put("sensor_alias", coordinator.store.alias("juggluco", "sensor", source.serial))
                        .put("source_uid_alias", coordinator.store.alias("juggluco", "source_uid", source.uid))
                        .put("local_lookup_id", source.shortId).put("manufacturer", source.manufacturer ?: JSONObject.NULL)
                        .put("model", source.model ?: JSONObject.NULL).put("health_connect_device_model", source.device.model))
                } }
                val partial = states.any { it.optString("status") !in setOf("success", "association_permission") }
                val manifest = JSONObject().put("schema_version", "0.1.0").put("exported_at_utc", cutoff.toString()).put("cutoff_epoch_ms", cutoff.toEpochMilli())
                    .put("scope", "all_valid_local_records_all_sensors_all_channels_all_cached_samsung_types_and_journal")
                    .put("source_snapshots", "Samsung cache lock, then Room pages validated by a dedicated read-only data_version connection; concurrent changes retry; no cross-database atomicity claimed")
                    .put("counts", JSONObject().put("blood_glucose", glucoseCount).put("samsung_health", samsungCount).put("journal", journalCount))
                    .put("partial_refresh", partial).put("samsung_sync", JSONArray(states)).put("samsung_read_enabled", coordinator.settings.getBoolean("enabled", false))
                    .put("samsung_selected_types", JSONArray(coordinator.selected().map { it.name })).put("timestamps", "absolute UTC instants; original Samsung offsets retained; CGM offsets unknown are null")
                    .put("unmapped_fields", "Retained under attributes.sdk_fields and child series; normalization is partial, units are not guessed")
                    .put("coverage", JSONObject().put("glucose_start_epoch_ms", if (min == Long.MAX_VALUE) JSONObject.NULL else min).put("glucose_end_epoch_ms", if (max == 0L) JSONObject.NULL else max)
                        .put("samsung", "Only previously synchronized scope, not entire Samsung account history"))
                File(folder, "manifest.json").writeText(manifest.toString(2))
                val summary = JSONObject().put("glucose_records", glucoseCount).put("samsung_records", samsungCount).put("journal_records", journalCount).put("partial_refresh", partial)
                File(folder, "summary.json").writeText(summary.toString(2))
                File(folder, "summary.md").writeText("血糖 $glucoseCount 条；三星健康 $samsungCount 条；日志 $journalCount 条。\n\n本包包含全部已缓存数据和完整子序列。刷新${if (partial) "存在失败或跳过，请查 manifest.json" else "完成"}。时序共现不能说明因果。\n")
                File(folder, "README.txt").writeText("JugglucoNG 健康数据 0.1.0\n先读 manifest.json 和 summary.json。逐行读取 glucose.jsonl、samsung-records.jsonl、journal.jsonl；再按 parent_record_id/series_id 加载 series.jsonl，按 relations.jsonl 关联睡眠。UTC epoch 是比较依据，勿按手机时区平移记录。每日汇总保留三星的本地日期语义，不伪造采样时刻。所有数值/完整日志已保留，未映射字段不能猜单位。glucose=Room 存储 Auto 通道，device_glucose=Raw 通道；recorded-display.jsonl 另外保留实际记录的显示值及通道，uncertainty.jsonl 保留可信区间。应用的后处理显示校准不重复套用。保留失败状态和已有缓存不代表全账户覆盖。文件可能含个人健康和用户输入日志，分享由用户操作。\n")
                File(folder, "README.txt").appendText("\nglucose-sources.jsonl 给出各血糖来源的目录，按 sensor_alias/source_uid_alias 关联；local_lookup_id 可在本机血糖来源页查询。wear_session_alias 仅表示已知的佩戴周期，null 保持未知，不能按当前探头参数猜旧历史归属。\n")
                for (name in listOf("record.schema.json", "series.schema.json", "field-catalog.json", "capabilities.json")) {
                    context.assets.open("health-insights/$name").use { input -> File(folder, name).outputStream().use { input.copyTo(it) } }
                }
                val inventory = JSONArray()
                ZipOutputStream(output.outputStream().buffered()).use { zip ->
                    for (file in folder.listFiles().orEmpty().filter { it.name != "manifest.json" }.sortedBy { it.name }) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var lines = 0L
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                digest.update(buffer, 0, n)
                                if (file.extension == "jsonl") for (i in 0 until n) if (buffer[i] == 10.toByte()) lines++
                                zip.write(buffer, 0, n)
                            }
                        }
                        zip.closeEntry()
                        inventory.put(JSONObject().put("name", file.name).put("bytes", file.length())
                            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                            .apply { if (file.extension == "jsonl") put("records", lines) })
                    }
                    manifest.put("files", inventory)
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                output
            } catch (ex: Exception) { output.delete(); throw ex }
            finally { sourceStore.close(); folder.deleteRecursively() }
        }
    }
    private fun journalFields(entry: tk.glucodata.data.journal.JournalEntryEntity): JSONObject = JSONObject().apply {
        put("id", JSONObject.wrap(entry.id))
        put("timestamp", JSONObject.wrap(entry.timestamp))
        put("sensorSerial", JSONObject.wrap(entry.sensorSerial))
        put("entryType", JSONObject.wrap(entry.entryType))
        put("title", JSONObject.wrap(entry.title))
        put("note", JSONObject.wrap(entry.note))
        put("amount", JSONObject.wrap(entry.amount))
        put("glucoseValueMgDl", JSONObject.wrap(entry.glucoseValueMgDl))
        put("durationMinutes", JSONObject.wrap(entry.durationMinutes))
        put("intensity", JSONObject.wrap(entry.intensity))
        put("insulinPresetId", JSONObject.wrap(entry.insulinPresetId))
        put("foodId", JSONObject.wrap(entry.foodId))
        put("proteinGrams", JSONObject.wrap(entry.proteinGrams))
        put("fatGrams", JSONObject.wrap(entry.fatGrams))
        put("source", JSONObject.wrap(entry.source))
        put("originSource", JSONObject.wrap(entry.originSource))
        put("sourceRecordId", JSONObject.wrap(entry.sourceRecordId))
        put("recoveryId", JSONObject.wrap(entry.recoveryId))
        put("createdAt", JSONObject.wrap(entry.createdAt))
        put("updatedAt", JSONObject.wrap(entry.updatedAt))
        put("nsUploadedAt", JSONObject.wrap(entry.nsUploadedAt))
        put("nsRemoteId", JSONObject.wrap(entry.nsRemoteId))
        put("insulinCurveJsonSnapshot", JSONObject.wrap(entry.insulinCurveJsonSnapshot))
        put("insulinCurveProfileId", JSONObject.wrap(entry.insulinCurveProfileId))
        put("insulinCurveModelVersion", JSONObject.wrap(entry.insulinCurveModelVersion))
        put("insulinCurveEvidence", JSONObject.wrap(entry.insulinCurveEvidence))
        put("insulinBodyWeightKg", JSONObject.wrap(entry.insulinBodyWeightKg))
        put("insulinCurveWasApproximated", JSONObject.wrap(entry.insulinCurveWasApproximated))
        put("lvUploadedAt", JSONObject.wrap(entry.lvUploadedAt))
    }
    private fun BufferedWriter.line(row: JSONObject) { write(row.toString()); newLine() }
}
