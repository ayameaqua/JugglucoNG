@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package tk.glucodata.healthinsights

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.HistoryReading
import tk.glucodata.HealthConnectSources
import tk.glucodata.HealthConnection
import tk.glucodata.SensorVisuals
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.util.GlucoseFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HealthInsightsActivity : ComponentActivity() {
    private var healthPermissionResult: CompletableDeferred<Set<String>>? = null
    private val healthPermissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { result -> healthPermissionResult?.complete(result) }
    suspend fun requestHealthConnectPermissions(permissions: Set<String>): Set<String> = withContext(Dispatchers.Main.immediate) {
        check(healthPermissionResult == null) { "已有健康数据授权请求" }
        val result = CompletableDeferred<Set<String>>()
        healthPermissionResult = result
        try { healthPermissionLauncher.launch(permissions); result.await() }
        finally { if(healthPermissionResult === result) healthPermissionResult = null }
    }
    override fun onDestroy() { healthPermissionResult?.cancel(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { JugglucoTheme { Insights(this) } } }
}

@Composable
private fun Insights(activity: HealthInsightsActivity) {
    val coordinator = remember { HealthInsightCoordinator.get(activity) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(coordinator.selected()) }
    var healthSource by remember { mutableStateOf(coordinator.source()) }
    var readHistory by remember { mutableStateOf(coordinator.settings.getBoolean("hc_read_history", false)) }
    var enabled by remember { mutableStateOf(coordinator.settings.getBoolean("enabled", false)) }
    var days by remember { mutableIntStateOf(coordinator.settings.getInt("days", 30)) }
    var customDays by remember { mutableStateOf(days.toString()) }
    var tab by remember { mutableIntStateOf(if (activity.intent.hasExtra("source_serial")) 3 else 0) }
    val sourceStore = remember { HealthConnectSources(activity) }
    DisposableEffect(Unit) { onDispose { sourceStore.close() } }
    var sources by remember { mutableStateOf(emptyList<HealthConnectSources.Source>()) }
    var sourceQuery by remember { mutableStateOf(activity.intent.getStringExtra("source_serial") ?: "") }
    var pickedSource by remember { mutableStateOf<HealthConnectSources.Source?>(null) }
    var sourceReadings by remember { mutableStateOf(emptyList<HistoryReading>()) }
    var sourceMessage by remember { mutableStateOf("") }
    suspend fun lookupSource(query: String) {
        val result = withContext(Dispatchers.IO) {
            val source = sourceStore.find(query.trim())
            val exact = sourceStore.lookup(query.trim())
            val readings = source?.let {
                val dao = HistoryDatabase.getInstance(activity).historyDao()
                // Native HC IDs/times use whole seconds; Room can retain the
                // original millisecond component. Match that UTC second only.
                if (exact != null) dao.getSensorReadingsInTimeRange(it.serial, exact.epochMs, exact.epochMs + 1000L)
                else dao.healthSourcePage(it.serial, Long.MAX_VALUE, 300)
            }.orEmpty()
            Triple(source, readings, exact)
        }
        pickedSource = result.first; sourceReadings = result.second
        sourceMessage = when {
            result.first == null -> "未找到来源。可输入来源 ID、完整 UID 或本应用保存的 Health Connect 记录 ID。"
            result.third != null -> "佩戴周期：${result.third!!.wearId ?: "历史归属未确定"}；${if (result.third!!.uploaded) "该记录已完成提交" else "该记录等待提交或重试"}"
            else -> "最近 ${result.second.size} 条本地 Auto／Raw 数据；导出／上传页面可选择全部来源或指定传感器。"
        }
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var health by remember { mutableStateOf(emptyList<JSONObject>()) }
    var glucose by remember { mutableStateOf(emptyList<HistoryReading>()) }
    var states by remember { mutableStateOf(emptyList<JSONObject>()) }
    var permissionGranted by remember { mutableStateOf(emptySet<HealthKind>()) }
    suspend fun load() {
        withContext(Dispatchers.IO) {
            states = coordinator.store.states()
            health = coordinator.store.recent(System.currentTimeMillis() - 7 * 86400_000L)
            glucose = HistoryDatabase.getInstance(activity).historyDao().getReadingsSince(System.currentTimeMillis() - 7 * 86400_000L)
            HistoryDatabase.getInstance(activity).historyDao().getAllSensorSerials().forEach { HealthConnectSources.resolve(activity, sourceStore, it) }
            sources = sourceStore.all()
            permissionGranted = runCatching { coordinator.adapter.granted(selected) }.getOrDefault(emptySet())
        }
    }
    fun refresh(force: Boolean) { scope.launch { busy = true; try { coordinator.refresh(force) { message = it }; load() } catch (ex: Exception) { message = "读取失败：${ex.message}" } finally { busy = false } } }
    LaunchedEffect(Unit) { busy = true; try { coordinator.refresh(false) { message = it }; load(); if (sourceQuery.isNotBlank()) lookupSource(sourceQuery) } finally { busy = false } }
    Scaffold(topBar = { TopAppBar(title = { Text("健康数据与血糖") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab) { listOf("联动时间线", "分析", "来源与授权", "血糖来源").forEachIndexed { n, text -> Tab(selected = tab == n, onClick = { tab = n }, text = { Text(text) }) } }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()); if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall) }
                if (tab == 0) {
                    item { Text("最近 24 小时血糖 · 最近 7 天睡眠与运动", style = MaterialTheme.typography.titleMedium); Text("时间按当前手机时区显示，比较使用相同 UTC 时间轴。图表和列表是浏览窗口，导出包含所有本地数据。", style = MaterialTheme.typography.bodySmall) }
                    item { CombinedTimeline(glucose, health) }
                    items(health, key = { it.getString("record_id") }) { row ->
                        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            val kind = HealthKind.entries.firstOrNull { it.kind == row.optString("kind") }
                            Text(kind?.label ?: row.optString("kind"), style = MaterialTheme.typography.titleSmall)
                            val t = row.optJSONObject("time")
                            Text(if (t?.optString("basis") == "local_date") t.optString("date") else t?.optString("start_utc")?.let { localTime(it) } ?: "日期未提供")
                            Text(row.optJSONObject("metrics")?.let { metrics ->
                                metrics.keys().asSequence().mapNotNull { key -> metrics.optJSONObject(key)?.let { metric ->
                                    if (metric.isNull("value")) null else "${metricLabel(key)}：${metric.opt("value")} ${metric.optString("unit")}" } }.joinToString(" · ").ifEmpty { "完整记录已缓存；暂无数值摘要" }
                            } ?: "完整记录已缓存", style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                    if (health.isEmpty()) item { Text("此来源尚无本地健康数据。到来源页选择、启用、授权并同步；睡眠、运动会与血糖按时间关联。") }
                } else if (tab == 1) {
                    item {
                        Text("时间共现", style = MaterialTheme.typography.titleLarge)
                        Text("按睡眠／运动的实际区间关联血糖，不推断因果。多传感器分别计算，避免不同来源混成一个结果。")
                    }
                    items(health.filter { it.optString("kind") in setOf("sleep", "exercise") }) { row ->
                        val time = row.getJSONObject("time"); val start = time.optLong("start_epoch_ms"); val end = time.optLong("end_epoch_ms")
                        val groups = glucose.filter { it.timestamp in start..end && it.value.isFinite() && it.value > 0 }.groupBy { it.sensorSerial }
                        ElevatedCard { Column(Modifier.padding(12.dp)) {
                            Text("${if (row.optString("kind") == "sleep") "睡眠" else "运动"} · ${localTime(time.optString("start_utc"))}")
                            if (groups.isEmpty()) Text("当前浏览窗口内没有区间重叠的有效血糖点")
                            groups.forEach { (serial, readings) -> Text("${sources.firstOrNull { it.serial == serial }?.label ?: serial}：${readings.size} 点，区间均值 ${displayGlucose(readings.map { it.value }.average().toFloat())}；范围 ${displayGlucose(readings.minOf { it.value })}–${displayGlucose(readings.maxOf { it.value })}") }
                        } }
                    }
                } else if (tab == 3) {
                    item {
                        Text("各传感器独立记录与上传", style = MaterialTheme.typography.titleMedium)
                        Text("来源 ID 不随连接顺序或主传感器选择改变。Health Connect 的设备信息包含该 ID；其他应用是否显示它由对方决定。未确定的旧佩戴周期保留未知。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(sourceQuery, { sourceQuery = it }, label = { Text("来源 ID／完整 UID／Health Connect 记录 ID") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = { scope.launch { lookupSource(sourceQuery) } }) { Text("查询") }
                            OutlinedButton(enabled = !busy, onClick = { HealthConnection.syncStoredSensors(replayAll = true); message = "已安排所有本地传感器有效采样重新上传；需启用 Health Connect 并获得权限。记录 ID 保持一致，失败时保留未上传游标。" }) { Text("补传全部来源") }
                        }
                        if (sourceMessage.isNotBlank()) Text(sourceMessage, style = MaterialTheme.typography.bodySmall)
                    }
                    items(sources, key = { it.uid }) { source ->
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { sourceQuery = source.shortId; scope.launch { lookupSource(sourceQuery) } }) { Text(source.label) }
                    }
                    pickedSource?.let { source -> item { Text("${source.label}\nUID：${source.uid}", style = MaterialTheme.typography.bodySmall) } }
                    items(sourceReadings, key = { "${it.sensorSerial}:${it.timestamp}" }) { row ->
                        Text("${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(row.timestamp))}\nAuto ${displayGlucose(row.value)} · Raw ${displayGlucose(row.rawValue)}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    item {
                        Text("健康数据来源（二选一）", style = MaterialTheme.typography.titleMedium)
                        HealthDataSource.entries.forEach { source ->
                            FilterChip(selected = healthSource == source, enabled = !busy, label = { Text(source.label) }, onClick = {
                                if(healthSource != source) scope.launch {
                                    busy = true
                                    try { coordinator.selectSource(source); healthSource = source; selected = coordinator.selected(); permissionGranted = emptySet(); load(); message = "已切换来源，旧缓存保留。只同步当前来源，请按需授权并手动同步。" }
                                    finally { busy = false }
                                }
                            })
                        }
                        Row { Text("读取健康数据", Modifier.weight(1f)); Switch(enabled, { enabled = it; coordinator.settings.edit().putBoolean("enabled", it).apply() }, enabled = !busy) }
                        Text(coordinator.adapter.availabilityMessage, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(3, 7, 30, 90).forEach { d -> FilterChip(days == d, { days = d; customDays = d.toString(); coordinator.settings.edit().putInt("days", d).apply() }, enabled = !busy, label = { Text("${d}天") }) } }
                        OutlinedTextField(customDays, { text ->
                            customDays = text.filter(Char::isDigit).take(4)
                            customDays.toIntOrNull()?.takeIf { it in 1..3650 }?.let { days = it; coordinator.settings.edit().putInt("days", it).apply() }
                        }, enabled = !busy, singleLine = true, label = { Text("自定义读取天数（1–3650）") }, isError = customDays.toIntOrNull() !in 1..3650)
                        Text("只刷新选中的来源。导出保留两个来源已有缓存，并标明当前来源；分析页只使用当前来源，避免混合计算。", style = MaterialTheme.typography.bodySmall)
                        if(healthSource == HealthDataSource.HEALTH_CONNECT) {
                            Row { Checkbox(readHistory, { readHistory = it; coordinator.settings.edit().putBoolean("hc_read_history",it).apply() }, enabled = !busy && coordinator.adapter.supportsHistoryPermission); Column {
                                Text("读取更早历史（单独授权）")
                                Text(if(coordinator.adapter.supportsHistoryPermission) "超过 30 天时请勾选并重新授权；实际可读范围由系统决定。" else "当前系统暂未提供额外历史读取能力，默认请求近 30 天。", style = MaterialTheme.typography.bodySmall)
                            } }
                            Text("Health Connect 中必须已有其他应用写入的记录；授权不会补出三星健康未同步到系统的数据。Jugg 自己写入的血糖不会回读。",style = MaterialTheme.typography.bodySmall)
                        } else Text("位置和个人资料需单独选中及授权。",style = MaterialTheme.typography.bodySmall)
                    }
                    items(HealthKind.entries) { kind -> Row(Modifier.fillMaxWidth()) {
                        Checkbox(kind in selected, { checked -> selected = if (checked) selected + kind else selected - kind; coordinator.saveSelected(selected) }, enabled = !busy && kind in coordinator.adapter.supportedKinds)
                        Column { Text(kind.label + if (kind.separateOptIn) "（单独授权）" else ""); Text(if(kind !in coordinator.adapter.supportedKinds) "此来源不支持" else if (kind in permissionGranted) "已获得读取权限" else "未获得读取权限", style = MaterialTheme.typography.bodySmall) }
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && enabled && coordinator.adapter.available && selected.isNotEmpty(), onClick = { scope.launch { busy = true; try { permissionGranted = coordinator.adapter.request(activity, selected); message = "授权完成；可手动同步" } catch (ex: Exception) { message = "授权失败：${ex.message}" } finally { busy = false } } }) { Text("请求读取授权") }
                        OutlinedButton(enabled = !busy, onClick = { refresh(true) }) { Text("手动同步") }
                    } }
                    items(states) { state -> Text("${HealthKind.entries.firstOrNull { it.kind == state.optString("kind") }?.label ?: state.optString("kind")}：${statusLabel(state.optString("status"))}\n最近成功：${state.optLong("last_success_epoch_ms").takeIf { it > 0 }?.let { localTime(Instant.ofEpochMilli(it).toString()) } ?: "暂无"}\n${state.optString("error").takeUnless { it == "null" } ?: ""}\n${state.optString("deletion_history_gap").takeUnless { it == "null" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Button(modifier = Modifier.fillMaxWidth().padding(16.dp), enabled = !busy, onClick = {
                activity.startActivity(android.content.Intent(activity, HealthDataTransferActivity::class.java))
            }) { Text("导出／上传数据给 Agent") }
        }
    }
}

private fun metricLabel(key: String) = mapOf("heart_rate" to "心率", "heart_rate_min" to "最低心率", "heart_rate_max" to "最高心率", "oxygen_saturation" to "血氧", "skin_temperature" to "皮肤温度", "duration" to "时长", "steps" to "步数")[key] ?: key
private fun statusLabel(key: String) = mapOf("success" to "已更新", "history_limited" to "更早历史受限", "permission_partial" to "部分类型已授权", "unsupported" to "当前系统不支持", "error" to "读取失败", "partial" to "覆盖不完整", "disabled" to "读取已关闭", "unavailable" to "当前不可用", "permission_denied" to "未获授权", "association_permission" to "随运动记录读取", "exercise_selection_missing" to "请同时选择运动记录")[key] ?: key

private fun localTime(value: String): String = runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm XXX").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault(value)
private fun displayGlucose(mgdl: Float): String = GlucoseFormatter.formatFromMgDl(mgdl, GlucoseFormatter.isMmolApp()) + if (GlucoseFormatter.isMmolApp()) " mmol/L" else " mg/dL"

@Composable
private fun CombinedTimeline(glucose: List<HistoryReading>, health: List<JSONObject>) {
    val now = System.currentTimeMillis(); val from = now - 86400_000L
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        fun x(ms: Long) = ((ms - from).toFloat() / 86400_000 * size.width).coerceIn(0f, size.width)
        health.filter { it.optString("kind") in setOf("sleep", "exercise") }.forEach { row ->
            val t = row.getJSONObject("time"); val a = t.optLong("start_epoch_ms"); val b = t.optLong("end_epoch_ms")
            if (b >= from && a <= now) drawRect(if (row.optString("kind") == "sleep") Color(0xff8c79bc).copy(alpha = .22f) else Color(0xffe4ab42).copy(alpha = .3f), Offset(x(a), 0f), Size((x(b)-x(a)).coerceAtLeast(1f), size.height))
        }
        glucose.groupBy { it.sensorSerial }.forEach { (serial, readings) ->
            val sorted = readings.filter { it.timestamp in from..now && it.value.isFinite() && it.value > 0 }.sortedBy { it.timestamp }
            sorted.zipWithNext().forEach { (a,b) -> if (b.timestamp - a.timestamp <= 10 * 60_000) drawLine(Color(SensorVisuals.colorArgb(serial)), Offset(x(a.timestamp), size.height * (1 - (a.value / 400f).coerceIn(0f, 1f))), Offset(x(b.timestamp), size.height * (1 - (b.value / 400f).coerceIn(0f, 1f))), strokeWidth = 2.dp.toPx()) }
        }
    }
    Text("${localTime(Instant.ofEpochMilli(from).toString())} → ${localTime(Instant.ofEpochMilli(now).toString())}", style = MaterialTheme.typography.bodySmall)
    Text("紫色：睡眠 · 橙色：运动 · 曲线：各血糖来源的存储 Auto 通道（0–${displayGlucose(400f)}）", style = MaterialTheme.typography.bodySmall)
    glucose.map { it.sensorSerial }.distinct().forEach { serial -> Text(serial, color = Color(SensorVisuals.colorArgb(serial)), style = MaterialTheme.typography.bodySmall) }
}
