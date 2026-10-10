package tk.glucodata.healthinsights

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import tk.glucodata.HealthConnectSources
import tk.glucodata.data.HistoryDatabase
import java.io.File

internal data class HealthTransferState(
    val sources: List<HealthConnectSources.Source> = emptyList(),
    val scope: HealthExportSelection.Scope = HealthExportSelection.Scope.ALL_DATA,
    val selected: Set<String> = emptySet(),
    val account: DriveAccount? = null,
    val busy: Boolean = false,
    val canCancel: Boolean = false,
    val message: String = "",
    val cloudLink: String? = null,
    val complete: Boolean = false,
    val pendingSave: File? = null,
)

/** Retains active work through configuration changes. No token or health data is put in saved state. */
internal class HealthTransferViewModel(application: Application) : AndroidViewModel(application) {
    enum class Action { CONNECT, UPLOAD }
    private val context = application.applicationContext
    private val coordinator = HealthInsightCoordinator.get(context)
    private val prefs = context.getSharedPreferences("health_drive_account", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(HealthTransferState(account = savedAccount()))
    val state = _state.asStateFlow()
    private var action: Action? = null
    private var selectionForUpload: HealthExportSelection? = null
    private var expectedAccountId: String? = null
    private var job: Job? = null
    private var cacheFile: File? = null

    init { viewModelScope.launch {
        try {
            val sources = withContext(Dispatchers.IO) {
                val store = HealthConnectSources(context)
                try { HistoryDatabase.getInstance(context).historyDao().getAllSensorSerials().sorted().map { HealthConnectSources.resolve(context, store, it) } }
                finally { store.close() }
            }
            _state.update { it.copy(sources = sources) }
        } catch (ex: Exception) { _state.update { it.copy(message = "无法读取传感器目录，请返回后重试") } }
    } }
    private fun savedAccount(): DriveAccount? = prefs.getString("id", null)?.let { DriveAccount(it, prefs.getString("email", "").orEmpty(), prefs.getString("name", "").orEmpty()) }
    fun setScope(value: HealthExportSelection.Scope) { if (!state.value.busy) _state.update { it.copy(scope = value) } }
    fun select(serial: String, checked: Boolean) { if (!state.value.busy) _state.update { it.copy(selected = if (checked) it.selected + serial else it.selected - serial) } }
    private fun selection() = HealthExportSelection(state.value.scope, if (state.value.scope == HealthExportSelection.Scope.CGM_ONLY) state.value.selected.toSet() else emptySet())
    fun beginAuthorization(value: Action): Boolean {
        if (state.value.busy) return false
        if (value == Action.UPLOAD) {
            val snapshot = runCatching { selection() }.getOrElse { _state.update { it.copy(message = "请先选择一支或多支 CGM") }; return false }
            selectionForUpload = snapshot
            expectedAccountId = state.value.account?.id
        }
        action = value
        _state.update { it.copy(busy = true, canCancel = false, message = "等待 Google 账号授权…", cloudLink = if (value == Action.UPLOAD) null else it.cloudLink, complete = if (value == Action.UPLOAD) false else it.complete) }
        return true
    }
    fun authorizationFailed(message: String) {
        action = null; selectionForUpload = null; expectedAccountId = null
        _state.update { it.copy(busy = false, canCancel = false, message = message) }
    }
    fun authorized(result: AuthorizationResult) {
        val target = action ?: return
        action = null
        val selection = selectionForUpload
        val expected = expectedAccountId
        selectionForUpload = null
        expectedAccountId = null
        val token = result.accessToken
        if (token.isNullOrBlank() || DriveClient.SCOPE !in result.grantedScopes) {
            authorizationFailed("未获得 Google Drive 上传权限；本地导出仍可用")
            return
        }
        _state.update { it.copy(canCancel = true) }
        job = viewModelScope.launch {
            try {
                val client = DriveClient(token)
                val account = withContext(Dispatchers.IO) { client.account() }
                if (target == Action.UPLOAD && expected != null && account.id != expected) {
                    _state.update { it.copy(message = "Google 返回的账号与上次连接不一致；请先点“切换账号”确认，未上传数据") }
                    return@launch
                }
                prefs.edit().putString("id", account.id).putString("email", account.email).putString("name", account.name).apply()
                _state.update { it.copy(account = account) }
                if (target == Action.CONNECT) { _state.update { it.copy(message = "Google 账号已连接；尚未上传任何数据") }; return@launch }
                val request = checkNotNull(selection)
                _state.update { it.copy(message = if (request.includeHealth) "正在刷新三星并生成全部整合数据…" else "正在生成指定 CGM 的全部本地历史…") }
                val file = HealthInsightExport.create(context, coordinator, selection = request)
                cacheFile = file
                withContext(Dispatchers.IO) {
                    DriveExportBundle.prepare(file, context.cacheDir).use { bundle ->
                        val link = DriveExportUploader(client).upload(bundle,
                            { text -> _state.update { it.copy(message = text) } },
                            { link -> _state.update { it.copy(cloudLink = link, complete = false) } })
                        _state.update { it.copy(cloudLink = link, complete = true, message = "上传完成。可在 ChatGPT 中通过已授权的 Google Drive 连接读取此目录的 START_HERE.txt；实际可读性还需在 ChatGPT 验证。") }
                    }
                }
            } catch (ex: CancellationException) { _state.update { it.copy(message = "已停止上传。云盘中的未完成目录可自行删除；本地历史未变更。") }; throw ex }
            catch (ex: Exception) {
                if (ex is DriveFailure && ex.status == 401) runCatching { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
                _state.update { it.copy(message = "${if (target == Action.UPLOAD) "上传" else "连接"}失败：${safeError(ex)}。${if (it.cloudLink != null) "已创建的目录仍标记为未完成。" else ""}") }
            } finally { cacheFile?.delete(); cacheFile = null; _state.update { it.copy(busy = false, canCancel = false) } }
        }
    }
    fun exportLocal() {
        if (state.value.busy) return
        val request = runCatching { selection() }.getOrElse { _state.update { it.copy(message = "请先选择一支或多支 CGM") }; return }
        _state.update { it.copy(busy = true, canCancel = true, message = "正在生成导出文件…") }
        job = viewModelScope.launch {
            try {
                val file = HealthInsightExport.create(context, coordinator, selection = request)
                cacheFile = file
                _state.update { it.copy(pendingSave = file, canCancel = false, message = "请选择保存位置") }
            } catch (ex: CancellationException) { cacheFile?.delete(); cacheFile = null; _state.update { it.copy(busy = false, canCancel = false, message = "已停止导出") }; throw ex }
            catch (ex: Exception) { _state.update { it.copy(busy = false, canCancel = false, message = "导出失败：${safeError(ex)}") } }
        }
    }
    fun save(uri: Uri?) {
        val file = state.value.pendingSave
        _state.update { it.copy(pendingSave = null) }
        if (file == null) { _state.update { it.copy(busy = false, message = "导出操作已中断，请重试") }; return }
        _state.update { it.copy(canCancel = true) }
        job = viewModelScope.launch {
            try {
                if (uri == null) _state.update { it.copy(message = "已取消保存") }
                else {
                    withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output -> file.inputStream().use { it.copyTo(output) } } }
                    _state.update { it.copy(message = "导出已保存；内容与相同范围的云盘快照一致") }
                }
            } catch (ex: Exception) { _state.update { it.copy(message = "保存失败：请检查空间和目录权限后重试") } }
            finally { file.delete(); cacheFile = null; _state.update { it.copy(busy = false, canCancel = false) } }
        }
    }
    fun cancel() { if (state.value.canCancel) job?.cancel() }
    fun disconnect() { if (!state.value.busy) { prefs.edit().clear().apply(); _state.update { it.copy(account = null, message = "本机已断开账号。已有云盘文件保留；可在 Google 账号的第三方授权页撤销访问。") } } }
    override fun onCleared() { super.onCleared(); cacheFile?.delete() }
    private fun safeError(ex: Exception): String = when (ex) {
        is DriveFailure -> ex.message.orEmpty()
        is java.io.IOException -> "文件或网络操作未完成，请检查空间及网络"
        else -> "操作未完成，请重试"
    }
}
