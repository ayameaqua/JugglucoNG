package tk.glucodata

import android.content.Context

/** Shared identity boundary only; manufacturers still decide what proves a new probe. */
object WearCalibrationBoundary {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wear_calibration_boundaries", Context.MODE_PRIVATE)
    fun begin(ctx: Context, id: String, startMs: Long) {
        if (startMs <= 0L) return
        val p = prefs(ctx)
        val starts = p.getString(id, "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
        check(p.edit().putString(id, (starts + startMs).distinct().sorted().joinToString(",")).commit())
        CalibrationAccess.notifyExternalCalibrationPipelineChanged()
    }

    /** Historical calibrations remain usable for their historical wear, never for a later one. */
    fun window(ctx: Context, id: String, sampleMs: Long): LongRange? {
        val starts = prefs(ctx).getString(id, "").orEmpty().split(',').mapNotNull(String::toLongOrNull).sorted()
        if (starts.isEmpty()) return null
        val start = starts.lastOrNull { it <= sampleMs } ?: 0L
        val end = starts.firstOrNull { it > sampleMs } ?: Long.MAX_VALUE
        return start until end
    }
}
