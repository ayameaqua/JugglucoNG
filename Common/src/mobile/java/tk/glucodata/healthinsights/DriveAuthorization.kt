package tk.glucodata.healthinsights

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Numeric/public diagnostics only: never retain an Intent, exception message, account or token. */
internal data class DriveAuthDiagnostic(
    val phase: String,
    val activityResultCode: Int? = null,
    val intentPresent: Boolean? = null,
    val sdkStatus: Int? = null,
    val outcome: String,
) {
    fun json(): JSONObject = JSONObject().put("phase", phase).put("activity_result_code", activityResultCode ?: JSONObject.NULL)
        .put("intent_present", intentPresent ?: JSONObject.NULL).put("sdk_status_code", sdkStatus ?: JSONObject.NULL)
        .put("sdk_status_name", sdkStatus?.let(CommonStatusCodes::getStatusCodeString) ?: JSONObject.NULL).put("outcome", outcome)

    fun message(): String {
        val code = sdkStatus?.let { "（SDK 代码 $it，${CommonStatusCodes.getStatusCodeString(it)}）" }.orEmpty()
        val result = activityResultCode?.let { "；Android 返回码 $it" }.orEmpty()
        val hint = when (sdkStatus) {
            CommonStatusCodes.DEVELOPER_ERROR -> "请核对 Android OAuth 客户端的实际包名与 SHA-1，以及所属 Cloud 项目。"
            CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT -> "请检查手机能否连接 Google 授权服务，再重试。"
            CommonStatusCodes.CANCELED, 12501 -> "Google 返回取消状态；若你没有主动退出，仍不能据此判断原因。"
            else -> "可复制登录诊断进一步定位；此状态不能证明是你主动取消。"
        }
        val description = when (outcome) {
            "account_not_selected" -> "Google 账号选择未完成"
            "invalid_account_result" -> "未收到有效的 Google 账号选择结果"
            "intent_sender_failed" -> "Google 授权窗口启动失败"
            "missing_grant" -> "未获得 Google Drive 上传权限"
            "resolution_returned_again" -> "Google 授权尚未完成，SDK 再次要求交互"
            else -> "Google 授权未完成"
        }
        return "$description$code$result。$hint"
    }
}

internal sealed interface DriveAuthOutcome {
    data class Granted(val result: AuthorizationResult, val diagnostic: DriveAuthDiagnostic) : DriveAuthOutcome
    data class Failed(val diagnostic: DriveAuthDiagnostic) : DriveAuthOutcome
}

/** Use the SDK result even on RESULT_CANCELED: errors can carry a useful ApiException. */
internal object DriveAuthorization {
    fun request(account: Account): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveClient.SCOPE))).setAccount(account).build()

    fun accountPicker(): Intent = AccountPicker.newChooseAccountIntent(AccountPicker.AccountChooserOptions.Builder()
        .setAllowableAccountsTypes(listOf("com.google")).setAlwaysShowAccountPicker(true).build())

    fun chosenAccount(resultCode: Int, data: Intent?): Account? {
        if (resultCode != Activity.RESULT_OK) return null
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() } ?: return null
        if (data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) != "com.google") return null
        return Account(name, "com.google")
    }

    fun returned(resultCode: Int, data: Intent?, parse: (Intent?) -> AuthorizationResult): DriveAuthOutcome {
        // The documented contract extra means the PendingIntent could not launch.
        if (data?.action == ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST &&
            data.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION)) {
            return DriveAuthOutcome.Failed(DriveAuthDiagnostic("resolution", resultCode, true, outcome = "intent_sender_failed"))
        }
        return try {
            val result = parse(data)
            when {
                result.hasResolution() -> DriveAuthOutcome.Failed(DriveAuthDiagnostic("resolution", resultCode, data != null, outcome = "resolution_returned_again"))
                result.accessToken.isNullOrBlank() || DriveClient.SCOPE !in result.grantedScopes ->
                    DriveAuthOutcome.Failed(DriveAuthDiagnostic("resolution", resultCode, data != null, outcome = "missing_grant"))
                else -> DriveAuthOutcome.Granted(result, DriveAuthDiagnostic("resolution", resultCode, data != null, CommonStatusCodes.SUCCESS, "granted"))
            }
        } catch (ex: CancellationException) { throw ex }
        catch (ex: Exception) { DriveAuthOutcome.Failed(failure("resolution", ex, resultCode, data != null)) }
    }

    fun failure(phase: String, error: Exception, resultCode: Int? = null, intentPresent: Boolean? = null) =
        DriveAuthDiagnostic(phase, resultCode, intentPresent, (error as? ApiException)?.statusCode, if (intentPresent == false) "no_result_intent" else "sdk_error")
}
