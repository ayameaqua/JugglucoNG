package tk.glucodata.healthinsights

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class DriveAuthorizationTest {
    private fun result(token: String? = "synthetic-secret", scopes: List<String> = listOf(DriveClient.SCOPE), pending: PendingIntent? = null) =
        AuthorizationResult(null, token, null, scopes, null, pending)

    @Test fun canceledActivityStillParsesSdkDeveloperErrorRatherThanClaimingUserCancellation() {
        val intent = Intent().putExtra("ignored-private-extra", "synthetic-secret")
        var parsed = false
        val outcome = DriveAuthorization.returned(Activity.RESULT_CANCELED, intent) {
            assertSame(intent, it); parsed = true
            throw ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR, "synthetic-secret user@example.test"))
        } as DriveAuthOutcome.Failed
        assertTrue(parsed)
        assertEquals(CommonStatusCodes.DEVELOPER_ERROR, outcome.diagnostic.sdkStatus)
        assertEquals(Activity.RESULT_CANCELED, outcome.diagnostic.activityResultCode)
        assertTrue(outcome.diagnostic.message().contains("SHA-1"))
        assertFalse(outcome.diagnostic.message().contains("已取消"))
        assertFalse(outcome.diagnostic.json().toString().contains("synthetic-secret"))
        assertFalse(outcome.diagnostic.json().toString().contains("user@example.test"))
    }
    @Test fun sdkGrantIsAuthoritativeEvenWhenActivityCodeIsCanceled() {
        val granted = result()
        val outcome = DriveAuthorization.returned(Activity.RESULT_CANCELED, Intent()) { granted } as DriveAuthOutcome.Granted
        assertSame(granted, outcome.result)
        assertEquals("granted", outcome.diagnostic.outcome)
        assertEquals(CommonStatusCodes.SUCCESS, outcome.diagnostic.sdkStatus)
        assertFalse(outcome.diagnostic.json().toString().contains("synthetic-secret"))
    }
    @Test fun normalActivitySuccessRequiresValidSdkTokenAndDriveScope() {
        assertTrue(DriveAuthorization.returned(Activity.RESULT_OK, Intent()) { result() } is DriveAuthOutcome.Granted)
        for (invalid in listOf(result(token = null), result(token = " "), result(scopes = emptyList()), result(scopes = listOf("openid")))) {
            val outcome = DriveAuthorization.returned(Activity.RESULT_OK, Intent()) { invalid } as DriveAuthOutcome.Failed
            assertEquals("missing_grant", outcome.diagnostic.outcome)
        }
    }
    @Test fun sdkCancellationRemainsAnAmbiguousSdkStatusWithItsCode() {
        val outcome = DriveAuthorization.returned(Activity.RESULT_CANCELED, Intent()) {
            throw ApiException(Status(CommonStatusCodes.CANCELED))
        } as DriveAuthOutcome.Failed
        assertEquals(CommonStatusCodes.CANCELED, outcome.diagnostic.sdkStatus)
        assertTrue(outcome.diagnostic.message().contains("SDK 代码 16"))
        assertTrue(outcome.diagnostic.message().contains("不能据此判断原因"))
        assertFalse(outcome.diagnostic.message().contains("已取消 Google 授权"))
    }
    @Test fun missingIntentIsRecordedAndSdkParserIsStillCalled() {
        var called = false
        val outcome = DriveAuthorization.returned(Activity.RESULT_CANCELED, null) {
            called = true; assertNull(it); throw IllegalArgumentException("synthetic-secret")
        } as DriveAuthOutcome.Failed
        assertTrue(called)
        assertEquals(false, outcome.diagnostic.intentPresent)
        assertNull(outcome.diagnostic.sdkStatus)
        assertEquals("no_result_intent", outcome.diagnostic.outcome)
        assertFalse(outcome.diagnostic.message().contains("synthetic-secret"))
    }
    @Test fun pendingIntentLaunchFailureIsNotMistakenForConsentCancellation() {
        val data = Intent(ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST)
            .putExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION, IntentSender.SendIntentException("synthetic-secret"))
        val outcome = DriveAuthorization.returned(Activity.RESULT_CANCELED, data) { error("must not parse a launcher failure") } as DriveAuthOutcome.Failed
        assertEquals("intent_sender_failed", outcome.diagnostic.outcome)
        assertTrue(outcome.diagnostic.message().contains("启动失败"))
        assertFalse(outcome.diagnostic.json().toString().contains("synthetic-secret"))
    }
    @Test fun repeatedResolutionDoesNotStartAnInfiniteAuthorizationLoop() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pending = PendingIntent.getActivity(context, 17, Intent("synthetic-consent"), PendingIntent.FLAG_IMMUTABLE)
        val outcome = DriveAuthorization.returned(Activity.RESULT_OK, Intent()) { result(pending = pending) } as DriveAuthOutcome.Failed
        assertEquals("resolution_returned_again", outcome.diagnostic.outcome)
    }
    @Test fun networkRequestFailureShowsSafeSpecificSdkStatus() {
        val diagnostic = DriveAuthorization.failure("request", ApiException(Status(CommonStatusCodes.NETWORK_ERROR, "synthetic-secret")))
        assertEquals("request", diagnostic.phase)
        assertEquals(CommonStatusCodes.NETWORK_ERROR, diagnostic.sdkStatus)
        assertTrue(diagnostic.message().contains("网络") || diagnostic.message().contains("连接"))
        assertFalse(diagnostic.json().toString().contains("synthetic-secret"))
    }
    @Test fun coroutineCancellationIsNotConvertedToGoogleServiceError() {
        val canceled = CancellationException("lifecycle")
        assertSame(canceled, runCatching { DriveAuthorization.returned(Activity.RESULT_OK, Intent()) { throw canceled } }.exceptionOrNull())
    }
    @Test fun accountPickerAcceptsOnlySuccessfulExplicitGoogleSelection() {
        val data = Intent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "synthetic@example.test").putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.google")
        assertEquals(Account("synthetic@example.test", "com.google"), DriveAuthorization.chosenAccount(Activity.RESULT_OK, data))
        assertNull(DriveAuthorization.chosenAccount(Activity.RESULT_CANCELED, data))
        assertNull(DriveAuthorization.chosenAccount(Activity.RESULT_OK, null))
        assertNull(DriveAuthorization.chosenAccount(Activity.RESULT_OK, Intent()))
        assertNull(DriveAuthorization.chosenAccount(Activity.RESULT_OK, Intent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "synthetic@example.test").putExtra(AccountManager.KEY_ACCOUNT_TYPE, "other")))
    }
    @Test fun explicitAccountAuthorizationRequestsOnlyDriveFileWithoutForcedPromptOrServerSecret() {
        val account = Account("synthetic@example.test", "com.google")
        val request = DriveAuthorization.request(account)
        assertEquals(account, request.account)
        assertEquals(listOf(DriveClient.SCOPE), request.requestedScopes.map { it.scopeUri })
        assertEquals(AuthorizationRequest.Prompt.NOT_SET, request.prompt)
        assertFalse(request.isOfflineAccessRequested)
        assertNull(request.serverClientId)
        assertNotNull(DriveAuthorization.accountPicker())
    }
}
