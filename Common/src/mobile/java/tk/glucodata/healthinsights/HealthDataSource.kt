package tk.glucodata.healthinsights

enum class HealthDataSource(val provider: String, val label: String) {
    HEALTH_CONNECT("health_connect", "Android 健康数据（Health Connect）"),
    SAMSUNG("samsung_health", "三星健康（直接读取）");
}
