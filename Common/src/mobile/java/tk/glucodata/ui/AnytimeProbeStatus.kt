package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.ui.setup.InlineQrScannerCard
import tk.glucodata.ui.viewmodel.SensorViewModel

@Composable
internal fun AnytimeProbeStatus(serial: String, model: SensorViewModel) {
    var status by remember(serial) { mutableStateOf(model.anytimeProbeStatus(serial)) }
    var editing by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(serial) { while (true) { status = model.anytimeProbeStatus(serial); delay(2_000) } }
    ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("探头二维码与算法状态", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { editing = true; message = "" }) { Text("扫描或更新当前探头二维码") }
            if (message.isNotEmpty()) Text(message)
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("当前探头二维码") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请确认这是当前正在佩戴的探头。更新会重建本地计算器；已有血糖历史会保留。UDI 包装码只提供元数据。")
            InlineQrScannerCard(Modifier.height(200.dp), onScanResult = {
                if (tk.glucodata.drivers.anytime.AnytimeAlgorithm.decodeQr(it) == null) false
                else { raw = it; true }
            })
            OutlinedTextField(raw, { raw = it }, label = { Text("二维码／手动校准码") })
            if (message.isNotEmpty()) Text(message)
        }
    }, confirmButton = { TextButton(onClick = {
        if (model.updateAnytimeProbeQr(serial, raw)) {
            editing = false
            raw = ""
            message = "已接收有效二维码，计算器将在下一条实时采样使用更新后的配置。请核对下方运行状态。"
        } else message = "二维码无效或传感器未找到，原有参数与历史已保留。"
    }) { Text("确认用于当前探头") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
}
