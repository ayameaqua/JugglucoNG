package tk.glucodata.healthinsights

/** One selection contract for local exports and Drive uploads. IDs are local sensor serials. */
internal data class HealthExportSelection(
    val scope: Scope = Scope.ALL_DATA,
    val sensors: Set<String> = emptySet(),
) {
    enum class Scope { ALL_DATA, CGM_ONLY }
    init {
        require(if (scope == Scope.CGM_ONLY) sensors.isNotEmpty() && sensors.none(String::isBlank) else sensors.isEmpty()) {
            "请选择至少一支 CGM；全部数据模式不使用单独的传感器筛选"
        }
    }
    val includeHealth get() = scope == Scope.ALL_DATA
    fun accepts(serial: String) = includeHealth || serial in sensors
}
