package tk.glucodata.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.WearSync2
import tk.glucodata.WearToggleSync
import tk.glucodata.ui.WearGlucoseStore
import tk.glucodata.ui.WearSectionTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PhoneConnectionScreen() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val snapshot by WearGlucoseStore.snapshot.collectAsState()
    var nodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var checked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val gms=remember { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)==ConnectionResult.SUCCESS }
    suspend fun inspect() {
        busy=true
        try {
            notifications=NotificationManagerCompat.from(context).areNotificationsEnabled()
            nodes=if(gms) Wearable.getNodeClient(context).connectedNodes.await().map { it.displayName } else emptyList()
            checked=true
        } catch(ex:Exception) { kotlin.coroutines.coroutineContext.ensureActive();message=context.getString(R.string.wear_phone_query_failed) }
        finally { busy=false }
    }
    LaunchedEffect(Unit) { inspect() }
    ScreenScaffold(timeText={TimeText()}) {
        ScalingLazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=22.dp,vertical=28.dp)) {
            item { WearSectionTitle(stringResource(R.string.wear_phone_connection)) }
            item { Text(stringResource(R.string.wear_phone_setup),style=MaterialTheme.typography.bodySmall) }
            item { Text(if(!gms) stringResource(R.string.wear_phone_gms_missing) else if(!checked) stringResource(R.string.wear_phone_checking) else if(nodes.isEmpty()) stringResource(R.string.wear_phone_no_peer) else stringResource(R.string.wear_phone_peers,nodes.joinToString(" · ")),style=MaterialTheme.typography.bodySmall) }
            item {
                val latest=snapshot.points.lastOrNull()?.timestamp
                Text(if(latest==null) stringResource(R.string.wear_phone_no_sample) else stringResource(R.string.wear_phone_latest_sample,DateTimeFormatter.ofPattern("MM-dd HH:mm XXX").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(latest))),style=MaterialTheme.typography.bodySmall)
            }
            item { Button(onClick={scope.launch { inspect() }},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.wear_phone_refresh)) } }
            item { Button(onClick={WearSync2.requestSync();WearToggleSync.requestState();message=context.getString(R.string.wear_phone_sync_requested)},enabled=gms && !busy,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.wear_phone_sync)) } }
            item { if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(if(notifications) R.string.wear_phone_notifications_on else R.string.wear_phone_notifications_off),style=MaterialTheme.typography.bodySmall) }
            item { Button(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.wear_phone_notification_settings)) } }
            item { Text(stringResource(R.string.wear_phone_face_help),style=MaterialTheme.typography.bodySmall) }
            item { Text(BuildConfig.APPLICATION_ID,style=MaterialTheme.typography.labelSmall) }
        }
    }
}
