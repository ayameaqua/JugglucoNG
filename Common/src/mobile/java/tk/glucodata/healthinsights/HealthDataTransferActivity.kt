@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package tk.glucodata.healthinsights

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import tk.glucodata.BuildConfig
import tk.glucodata.ui.JugglucoTheme
import java.security.MessageDigest
import java.time.Instant

class HealthDataTransferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JugglucoTheme { HealthDataTransferScreen(this) } }
    }
}

@Composable
private fun HealthDataTransferScreen(activity: ComponentActivity, model: HealthTransferViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val client = remember { Identity.getAuthorizationClient(activity) }
    var setup by remember { mutableStateOf(false) }
    var launchedSave by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> launchedSave = null; model.save(uri) }
    LaunchedEffect(state.pendingSave) { state.pendingSave?.let { if (launchedSave != it.path) { launchedSave = it.path; save.launch(it.name) } } }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { response ->
        model.authorizationReturned(DriveAuthorization.returned(response.resultCode, response.data, client::getAuthorizationResultFromIntent))
    }
    fun authorizeAccount(account: Account) {
        if (!model.awaitingAuthorization()) return
        scope.launch {
            try {
                model.authorizationProgress("正在请求 Google Drive 授权…")
                val result = client.authorize(DriveAuthorization.request(account)).await()
                if (!model.awaitingAuthorization()) return@launch
                if (result.hasResolution()) {
                    model.authorizationProgress("等待 Google Drive 权限确认…")
                    authorization.launch(IntentSenderRequest.Builder(checkNotNull(result.pendingIntent).intentSender).build())
                }
                else model.authorized(result)
            } catch (ex: CancellationException) { model.authorizationFailed("授权流程因页面切换而中断，请重试"); throw ex }
            catch (ex: Exception) { val diagnostic = DriveAuthorization.failure("request", ex); model.authorizationFailed(diagnostic.message(), diagnostic) }
        }
    }
    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val account = DriveAuthorization.chosenAccount(response.resultCode, response.data)
        if (account != null) authorizeAccount(account)
        else {
            val diagnostic = DriveAuthDiagnostic("account_picker", response.resultCode, response.data != null, outcome = if (response.resultCode == android.app.Activity.RESULT_OK) "invalid_account_result" else "account_not_selected")
            model.authorizationFailed(diagnostic.message(), diagnostic)
        }
    }
    fun connect(action: HealthTransferViewModel.Action, changeAccount: Boolean = false) {
        if (!model.beginAuthorization(action)) return
        val services = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)
        if (services != ConnectionResult.SUCCESS) { model.authorizationFailed("Google Play 服务不可用或需更新（代码 $services）；本地导出仍可用", DriveAuthDiagnostic("play_services", sdkStatus = services, outcome = "unavailable")); return }
        val previous = state.account
        if (changeAccount || previous == null) {
            try { model.authorizationProgress("请选择 Google 账号…"); accountPicker.launch(DriveAuthorization.accountPicker()) }
            catch (ex: Exception) { val diagnostic = DriveAuthorization.failure("account_picker", ex); model.authorizationFailed(diagnostic.message(), diagnostic) }
        } else authorizeAccount(Account(previous.email, "com.google"))
    }
    Scaffold(topBar = { TopAppBar(title = { Text("导出与 Google Drive") }, navigationIcon = { TextButton(onClick = activity::finish) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("数据范围", style = MaterialTheme.typography.titleMedium) }
            item {
                Row { RadioButton(state.scope == HealthExportSelection.Scope.ALL_DATA, { model.setScope(HealthExportSelection.Scope.ALL_DATA) }, enabled = !state.busy); Column { Text("全部整合数据"); Text("所有 CGM、本地日志及两种健康来源的已有缓存（分开标注）", style = MaterialTheme.typography.bodySmall) } }
                Row { RadioButton(state.scope == HealthExportSelection.Scope.CGM_ONLY, { model.setScope(HealthExportSelection.Scope.CGM_ONLY) }, enabled = !state.busy); Column { Text("指定 CGM"); Text("可选一支或多支；包含全部本地历史、Auto／Raw 及来源信息", style = MaterialTheme.typography.bodySmall) } }
            }
            if (state.scope == HealthExportSelection.Scope.CGM_ONLY) {
                if (state.sources.isEmpty()) item { Text("暂无有本地历史的传感器") }
                items(state.sources, key = { it.serial }) { source -> Row {
                    Checkbox(source.serial in state.selected, { model.select(source.serial, it) }, enabled = !state.busy)
                    Column { Text(source.label); Text(source.serial, style = MaterialTheme.typography.bodySmall) }
                } }
            }
            item { Button(onClick = model::exportLocal, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出文件到手机") } }
            item { HorizontalDivider(); Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                Text(state.account?.let { "上次连接：${it.email}" } ?: "尚未连接账号")
                Text("上传到“我的云盘 / JugglucoNG Health”。每次创建独立快照，包含完整 ZIP 和便于 Agent 读取的分段文本。", style = MaterialTheme.typography.bodySmall)
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !state.busy, onClick = { connect(HealthTransferViewModel.Action.CONNECT, true) }) { Text(if (state.account == null) "连接 Google 账号" else "切换账号") }
                if (state.account != null) TextButton(enabled = !state.busy, onClick = model::disconnect) { Text("断开") }
            } }
            item { Button(enabled = !state.busy, onClick = { connect(HealthTransferViewModel.Action.UPLOAD) }, modifier = Modifier.fillMaxWidth()) { Text("上传所选范围到 Drive") } }
            item { Text("ChatGPT 需授权连接同一个 Google 账号，并读取本次快照的 START_HERE.txt。连接器的格式支持和索引更新需实际验证。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { setup = true }) { Text("首次使用：Google Cloud 配置") }
            }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); if (state.canCancel) TextButton(onClick = model::cancel) { Text("停止") } }
            if (state.message.isNotBlank()) item { Text(state.message) }
            state.authDiagnostic?.let { diagnostic -> item {
                TextButton(onClick = {
                    val text = diagnostic.json().put("timestamp_utc", Instant.now().toString()).put("app_package", BuildConfig.APPLICATION_ID)
                        .put("app_version", BuildConfig.VERSION_NAME).put("android_api", Build.VERSION.SDK_INT).put("signing_sha1", signingSha1(activity))
                        .put("drive_scope", DriveClient.SCOPE).put("account_flow", "AccountPicker_then_explicit_account_no_prompt")
                        .put("play_services_version", runCatching { activity.packageManager.getPackageInfo("com.google.android.gms", 0).versionName }.getOrNull() ?: org.json.JSONObject.NULL).toString(2)
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Google 登录诊断", text))
                    android.widget.Toast.makeText(activity, "登录诊断已复制，不含账号、令牌或健康数据", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("复制 Google 登录诊断") }
            } }
            state.cloudLink?.let { link -> item { TextButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) { Text(if (state.complete) "打开本次云盘快照" else "查看未完成的云盘目录") } } }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    if (setup) AlertDialog(onDismissRequest = { setup = false }, title = { Text("个人测试配置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. 在 Google Cloud 新建项目并启用 Google Drive API。\n2. 在 Google Auth Platform 设置应用信息、外部受众，添加 drive.file 数据权限。发布状态为测试时添加自己的 Gmail；正式发布无需测试名单。\n3. 创建 Android OAuth 客户端，填写下方包名和 SHA-1。\n4. 保存配置后回到这里连接 Google 账号。无需填入密钥或 Web 客户端 ID。", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer { Text("包名：${BuildConfig.APPLICATION_ID}\nSHA-1：${signingSha1(activity)}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/apis/library/drive.googleapis.com"))) }) { Text("打开 Google Cloud") }
        }
    }, confirmButton = { TextButton(onClick = { setup = false }) { Text("关闭") } })
}

@Suppress("DEPRECATION")
internal fun signingSha1(context: Context): String = runCatching {
    val signatures = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
        else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!
    signatures.joinToString("\n") { signature -> MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString(":") { "%02X".format(it) } }
}.getOrDefault("无法读取，请查看 APK 签名报告")
