package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.drivers.sibionics.SibionicsRegistry
import tk.glucodata.ui.setup.InlineQrScannerCard
import tk.glucodata.ui.viewmodel.SensorViewModel

@Composable
internal fun SibionicsProbeStatus(serial: String, model: SensorViewModel) {
    var showProbeCode by remember(serial) { mutableStateOf(false) }
    var status by remember(serial, showProbeCode) { mutableStateOf(model.sibionicsProbeStatus(serial, showProbeCode)) }
    var editing by remember(serial) { mutableStateOf(false) }
    var raw by remember(serial) { mutableStateOf("") }
    var message by remember(serial) { mutableStateOf("") }
    var saving by remember(serial) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = remember(raw) { SibionicsRegistry.decodeProbeQr(raw) }
    LaunchedEffect(serial, showProbeCode) {
        while (true) { status = model.sibionicsProbeStatus(serial, showProbeCode); delay(2_000) }
    }
    ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("探头二维码与算法状态", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(status, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { showProbeCode = !showProbeCode }) {
                Text(if (showProbeCode) "隐藏探头识别码" else "查看／复制探头识别码")
            }
            TextButton(onClick = { editing = true; raw = ""; message = "" }) {
                Text("扫描或更新当前探头二维码")
            }
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editing) AlertDialog(onDismissRequest = { if (!saving) editing = false },
        title = { Text("当前硅基探头二维码") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请扫描一次性探头／助针器包装上的二维码，确认属于当前正在佩戴的探头。发射器连接码不提供工厂灵敏度。")
                Text("更新会按本周期原始采样重建本地算法与历史，完成前保留原值。手动灵敏度覆盖继续保留。此操作不会重置硬件。")
                InlineQrScannerCard(Modifier.height(240.dp), scannerEnabled = !saving, onScanResult = {
                    if (SibionicsRegistry.decodeProbeQr(it) == null) {
                        message = "未识别到有效探头校准码，请核对是否扫描了发射器连接码。"
                        false
                    } else { raw = it; message = ""; true }
                })
                OutlinedTextField(raw, { raw = it; message = "" }, enabled = !saving,
                    label = { Text("完整探头二维码／14 位工厂码") })
                parsed?.let { Text("解码工厂初始灵敏度：%.2f".format(it.sensitivity)) }
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = parsed != null && !saving, onClick = {
            saving = true
            val submittedRaw = raw
            scope.launch {
                try {
                    if (model.updateSibionicsProbeQr(serial, submittedRaw)) {
                        editing = false; raw = ""
                        message = "探头参数已保存，驱动将核对并按需连续重建。手动灵敏度覆盖继续保留；请核对运行初始灵敏度及状态，完成后比较同一采样时刻。"
                        status = model.sibionicsProbeStatus(serial, showProbeCode)
                    } else message = "未能更新。请核对传感器和探头码；待处理的新探头切换需先完成，血糖历史仍保留。"
                } finally { saving = false }
            }
        }) { Text(if (saving) "正在保存" else "确认用于当前探头") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { editing = false }) { Text("取消") } })
}
