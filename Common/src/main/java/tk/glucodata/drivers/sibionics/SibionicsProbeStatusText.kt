package tk.glucodata.drivers.sibionics

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Read-only presentation of persisted metadata and an independently captured running algorithm. */
internal object SibionicsProbeStatusText {
    data class Saved(
        val sensorId: String,
        val startTimeMs: Long,
        val code: String?,
        val confirmedAtMs: Long,
        val probeChangePending: Boolean,
    )

    data class Runtime(
        val code: String?,
        val configuredSensitivity: Float,
        val manualOverride: Boolean,
        val legacyShortCode: Boolean,
        val algorithm: String,
        val continuous: Boolean,
        val sampleIndex: Int?,
        val activeSensitivity: Float?,
        val recovering: Boolean,
        val missingSourceIndex: Int,
        val historyTransferActive: Boolean,
        val rebuildPending: Boolean,
        val historyConfigurationMatches: Boolean,
    )

    fun format(saved: Saved, runtime: Runtime? = null, showCode: Boolean = false,
        timeZone: TimeZone = TimeZone.getDefault()): String {
        val factory = SibionicsProbeCalibration.sensitivity(saved.code)
        fun time(at: Long) = if (at > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US)
            .apply { this.timeZone = timeZone }.format(Date(at)) else "尚未确定"
        val lines = mutableListOf<String>()
        // This is a display reference, not a newly inferred or persisted physical session.
        lines += "周期参考标识：" + if (saved.startTimeMs > 0L) {
            hash("${saved.sensorId}:${saved.startTimeMs}").take(8)
        } else "尚未确定"
        lines += "开始：${time(saved.startTimeMs)}（按本周期采样时间轴）"
        lines += "二维码：" + when {
            saved.code.isNullOrBlank() -> "未保存有效探头码"
            factory == null -> "存在，但未通过工厂参数校验"
            saved.code.length == 14 -> "存在 · 工厂校准（14 位探头识别码）"
            else -> "存在 · 工厂校准（GS1 包装序列）"
        }
        lines += "归属：" + when {
            saved.probeChangePending -> "待处理的新探头切换；旧码归属需重新核对"
            factory == null -> "尚未确认当前探头工厂参数"
            saved.confirmedAtMs > 0L -> "用户明确指定当前探头（请核对包装）"
            else -> "来自添加流程或旧记录，当前探头归属待核对"
        }
        lines += "录入／确认：" + if (saved.confirmedAtMs > 0L) time(saved.confirmedAtMs) else "未保存"
        saved.code?.takeIf { it.isNotBlank() }?.let {
            lines += "二维码标识：${hash(it)}"
            if (showCode) lines += "已保存探头码：$it"
        }
        lines += "工厂初始灵敏度：" + (factory?.let { number(it) } ?: "未知")
        if (runtime == null) {
            lines += "运行参数：驱动尚未运行，已保存参数不代表当前计算已采用"
            lines += "历史重算：等待运行驱动核对"
            return lines.joinToString("\n")
        }
        val source = when {
            runtime.manualOverride -> "手动覆盖（优先于工厂码）"
            SibionicsProbeCalibration.sensitivity(runtime.code) != null -> "已载入的探头工厂码"
            runtime.legacyShortCode -> "旧式短码参数"
            else -> "备用默认参数"
        }
        val calculatorPending = !runtime.manualOverride && factory != null &&
            kotlin.math.abs(runtime.configuredSensitivity - factory) > 0.00001f
        lines += "运行初始灵敏度：${number(runtime.configuredSensitivity)} · $source"
        lines += "算法：${runtime.algorithm}"
        lines += "连续状态：" + when {
            runtime.recovering -> "正在恢复本周期连续算法，原有血糖暂时保留"
            runtime.continuous && runtime.sampleIndex != null -> "本周期算法已连续初始化 · ID=${runtime.sampleIndex}"
            else -> "暂无可核对的本周期有效采样"
        }
        runtime.activeSensitivity?.takeIf { it.isFinite() }?.let {
            lines += "最近算法内部有效灵敏度：${number(it, 3)}"
        }
        lines += "参数采用：" + when {
            saved.probeChangePending -> "新探头归属待处理，不能确认旧码适用于当前探头"
            saved.code != runtime.code -> "参数已保存，等待运行驱动接收"
            calculatorPending -> "探头码已载入，运行计算器仍待更新"
            runtime.manualOverride -> "实际计算采用手动覆盖值，工厂码继续保留"
            factory == null -> "当前计算缺少本探头工厂码，请补录"
            runtime.recovering || !runtime.continuous || runtime.sampleIndex == null ->
                "驱动已载入探头码，等待连续采样验证"
            else -> "当前连续算法已采用已载入的探头参数"
        }
        lines += "历史重算：" + when {
            saved.probeChangePending -> "新探头归属待处理，不能确认旧周期重算适用于本周期"
            saved.code != runtime.code -> "等待驱动接收新参数，原有历史保留"
            calculatorPending -> "等待计算器加载新参数，原有历史保留"
            runtime.recovering -> "先恢复连续算法，原有历史暂时保留"
            runtime.missingSourceIndex > 0 -> "原始采样待补齐，从 ID=${runtime.missingSourceIndex} 继续；原值保留"
            runtime.historyTransferActive -> "正在接收历史采样，重算等待回填稳定"
            runtime.rebuildPending -> "按当前参数的连续重算尚未完成，原值保留"
            runtime.historyConfigurationMatches -> "本机已有按当前参数完成的历史重算记录"
            else -> "本机尚无按当前参数完成的历史重算记录"
        }
        return lines.joinToString("\n")
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(6)
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }

    private fun number(value: Float, digits: Int = 2) = String.format(Locale.US, "%.${digits}f", value)
}
