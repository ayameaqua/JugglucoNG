package tk.glucodata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.HistorySensorMode
import tk.glucodata.data.HistorySensorSelection
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.viewmodel.HistorySensorSource

@Composable
internal fun HistorySensorControls(
    selection: HistorySensorSelection,
    sources: List<HistorySensorSource>,
    homeSensor: String,
    onMode: (HistorySensorMode) -> Unit,
    onSingle: (String?) -> Unit,
    onToggle: (String) -> Unit,
) {
    var showSources by remember { mutableStateOf(false) }
    val labels = listOf(R.string.history_sensor_single, R.string.history_sensor_selected, R.string.history_sensor_all)
    val chosen = selection.querySensors(homeSensor)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ConnectedButtonGroup(
            options = HistorySensorMode.entries,
            selectedOption = selection.mode,
            onOptionSelected = onMode,
            label = { Text(stringResource(labels[it.ordinal])) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { showSources = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when (selection.mode) {
                    HistorySensorMode.SINGLE -> {
                        val label = sources.firstOrNull { it.id == chosen?.firstOrNull() }?.label.orEmpty()
                        if (selection.singleSensor == null) stringResource(R.string.history_sensor_follow_label, label)
                        else label
                    }
                    HistorySensorMode.SELECTED -> stringResource(R.string.history_sensor_count, chosen?.size ?: 0)
                    HistorySensorMode.ALL -> stringResource(R.string.history_sensor_all_note)
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showSources) AlertDialog(
        onDismissRequest = { showSources = false },
        title = { Text(stringResource(R.string.history_sensor_sources)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (selection.mode == HistorySensorMode.SINGLE) item {
                    Row(Modifier.fillMaxWidth().clickable { onSingle(null); showSources = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selection.singleSensor == null, onClick = { onSingle(null); showSources = false })
                        Text(stringResource(R.string.history_sensor_follow))
                    }
                }
                items(sources, key = { it.id }) { source ->
                    val choose = {
                        if (selection.mode == HistorySensorMode.SINGLE) { onSingle(source.id); showSources = false }
                        else if (selection.mode == HistorySensorMode.SELECTED) onToggle(source.id)
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = selection.mode != HistorySensorMode.ALL, onClick = choose).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (selection.mode) {
                            HistorySensorMode.SINGLE -> RadioButton(selected = selection.singleSensor == source.id, onClick = choose)
                            HistorySensorMode.SELECTED -> Checkbox(checked = source.id in selection.selectedSensors, onCheckedChange = { choose() })
                            HistorySensorMode.ALL -> Spacer(Modifier.width(8.dp))
                        }
                        Box(Modifier.size(9.dp).background(Color(source.colorArgb), CircleShape))
                        Text(source.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSources = false }) { Text(stringResource(android.R.string.ok)) } },
    )
}
