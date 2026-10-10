package tk.glucodata.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.HealthConnectSources
import tk.glucodata.SensorVisuals
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.*
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.ui.GlucosePoint

data class HistorySensorSource(val id: String, val label: String, val viewMode: Int, val colorArgb: Int)

/** Extra historical sources must not bump the colours currently shown on home. */
internal fun historySourceColors(ids: List<String>, homeSensors: List<String>, primary: String): Map<String, Int> {
    val homeColors = SensorVisuals.distinctColorArgbMap(homeSensors).entries
        .associate { HistoryBrowseData.sensorId(it.key) to it.value }
    val primaryColor = primary.takeIf(String::isNotBlank)?.let {
        // Home's primary ReadingRow uses the base identity tint; peers use the distinct palette.
        mapOf(HistoryBrowseData.sensorId(it) to SensorVisuals.colorArgb(tk.glucodata.SensorIdentity.resolveAppSensorId(it)))
    }.orEmpty()
    return SensorVisuals.distinctColorArgbMap(ids) + homeColors + primaryColor
}

/** Independent, read-only history browsing; never changes dashboard selection or the drivers. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryBrowserViewModel : ViewModel() {
    private val repository = HistoryRepository()
    private data class Home(val primary: String = "", val selected: List<String> = emptyList())
    private val home = MutableStateFlow(Home())
    private val _selection = MutableStateFlow(HistorySensorSelection())
    val selection = _selection.asStateFlow()
    val querySensors = combine(selection, home) { selection, home -> selection.querySensors(home.primary)?.map(HistoryBrowseData::sensorId)?.distinct() }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sources = combine(repository.observeBrowseSensors(), home, UiRefreshBus.events.onStart { emit(UiRefreshBus.Event.StatusOnly) }) {
        stored, home, _ ->
        val ids = (home.selected + listOf(home.primary).filter(String::isNotBlank) + stored).map(HistoryBrowseData::sensorId).distinct()
        val colors = historySourceColors(ids, home.selected, home.primary)
        val catalog = runCatching { HealthConnectSources(Applic.app).use { it.all() } }.getOrDefault(emptyList())
        ids.map { id ->
            val known = catalog.firstOrNull { HistoryBrowseData.sensorId(it.serial) == id }
            val model = runCatching { ManagedSensorRuntime.resolveUiSnapshot(id)?.vendorModel }.getOrNull()
            HistorySensorSource(
                id, known?.label ?: listOfNotNull(model?.takeIf(String::isNotBlank), id).joinToString(" · "),
                runCatching { CurrentDisplaySource.resolveViewModeForSensor(home.selected.firstOrNull { HistoryBrowseData.sensorId(it) == id } ?: id) }.getOrDefault(0),
                colors[id] ?: SensorVisuals.colorArgb(id),
            )
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val window = MutableStateFlow<TimelineWindow?>(null)
    private val tailStart = flow {
        while (true) {
            emit(TimelineWindowPolicy.liveTailStart(System.currentTimeMillis()))
            delay(60_000)
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineWindowPolicy.liveTailStart(System.currentTimeMillis()))

    val history = querySensors.flatMapLatest { sensors ->
        combine(tailStart, window) { start, viewport -> start to viewport }.flatMapLatest { (start, viewport) ->
            val tail = repository.observeBrowseWindow(sensors, start, Long.MAX_VALUE)
            val older = viewport?.let { repository.observeBrowseWindow(sensors, it.startMs, it.endMs) }
                ?: flowOf(emptyList())
            combine(tail, older, HistoryBrowseData::joinWindows)
        }.onStart { emit(emptyList()) }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<GlucosePoint>())

    val extents = querySensors.flatMapLatest { sensors ->
        repository.observeBrowseSummary(sensors, Long.MIN_VALUE, Long.MAX_VALUE)
            .map { it?.let { TimelineExtents(it.earliestMs, it.latestMs, it.readingCount) } }
            .onStart { emit(null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updateHome(primary: String, selected: List<String>) {
        home.value = Home(primary, selected.distinct())
    }

    fun chooseMode(mode: HistorySensorMode) {
        val previous = selection.value
        _selection.value = previous.copy(mode = mode, selectedSensors = if (mode == HistorySensorMode.SELECTED && previous.mode != mode && previous.selectedSensors.isEmpty()) home.value.selected.map(HistoryBrowseData::sensorId).distinct() else previous.selectedSensors)
    }

    fun chooseSingle(id: String?) { _selection.value = selection.value.copy(singleSensor = id) }
    fun toggleSensor(id: String) {
        val selected = selection.value.selectedSensors
        _selection.value = selection.value.copy(selectedSensors = if (id in selected) selected - id else selected + id)
    }

    fun onViewportChanged(start: Long, end: Long) {
        window.value = TimelineWindowPolicy.windowUpdate(window.value, tailStart.value, start, end)
    }

    fun rangeSummaryFlow(start: Long, end: Long): Flow<TimelineRangeSummary?> =
        querySensors.flatMapLatest { repository.observeBrowseSummary(it, start, end).onStart { emit(null) } }

    suspend fun exportHistory(start: Long, end: Long): List<GlucosePoint> =
        repository.observeBrowseWindow(querySensors.value, start, end).first()
}
