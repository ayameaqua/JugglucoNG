package tk.glucodata.drivers.anytime

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** Additive migration: old keys stay readable; each rollover retains an immutable copy. */
internal object AnytimeWearStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    private fun key(id: String, field: String) = "anytime_wear_${field}_$id"

    fun session(ctx: Context, id: String): String {
        val p = prefs(ctx)
        return p.getString(key(id, "id"), null) ?: UUID.randomUUID().toString().also {
            check(p.edit().putString(key(id, "id"), it).commit()) { "Cannot persist wear identity" }
        }
    }

    fun bindQr(ctx: Context, id: String, raw: String, startMs: Long) {
        val parsed = requireNotNull(AnytimeAlgorithm.decodeQr(raw)) { "Invalid probe QR" }
        val p = prefs(ctx)
        val current = session(ctx, id)
        val e = p.edit()
        val previous = p.getString(AnytimeConstants.PREF_QR_CONTENT_PREFIX + id, null)
        if (!previous.isNullOrBlank() && previous != raw) {
            // A new QR can arrive before the live index confirms a new wear.
            // Retain the original metadata even if the rollover comes later.
            val revision = org.json.JSONObject().put("raw", previous).put("wear_session", current)
                .put("hash", hash(previous)).put("entered_at_epoch_ms", qrAt(ctx, id))
                .put("clock_start_epoch_ms", qrClock(ctx, id))
                .put("k", AnytimeRegistry.loadKValue(ctx, id)).put("r", AnytimeRegistry.loadRValue(ctx, id))
                .put("voltage_mode", AnytimeRegistry.loadVoltageFlag(ctx, id))
            e.putString("anytime_qr_revision_${current}_${UUID.randomUUID()}_$id", revision.toString())
        }
        if (previous != raw) {
            e.remove(AnytimeConstants.PREF_CALIBRATOR_TEMP_SMOOTH_PREV_PREFIX + id)
                .remove(AnytimeConstants.PREF_CALIBRATOR_FILTERED_PREV_PREFIX + id)
                .remove(AnytimeConstants.PREF_CALIBRATOR_LAST_ID_PREFIX + id)
                .remove(AnytimeConstants.PREF_CT3_NATIVE_STATE_PREFIX + id)
                .remove("anytime_wear_model_k0_$id")
        }
        // Ownership, coefficients and invalidation are one durable update.
        // A crash cannot stamp the old QR as a newly assigned current probe.
        e.putString(AnytimeConstants.PREF_QR_CONTENT_PREFIX + id, parsed.rawQr)
            .putFloat(AnytimeConstants.PREF_K_PREFIX + id, parsed.k)
            .putFloat(AnytimeConstants.PREF_R_PREFIX + id, parsed.r)
            .putInt(AnytimeConstants.PREF_VOLTAGE_PREFIX + id, parsed.voltageFlag)
        check(e.putString(key(id, "qr_session"), current)
            .putLong(key(id, "qr_at"), System.currentTimeMillis())
            .putLong(key(id, "qr_clock"), startMs)
            .putString(key(id, "qr_hash"), hash(raw)).commit())
    }

    fun matched(ctx: Context, id: String) = prefs(ctx).getString(key(id, "qr_session"), null) == session(ctx, id)
    fun qrAt(ctx: Context, id: String) = prefs(ctx).getLong(key(id, "qr_at"), 0L)
    fun qrClock(ctx: Context, id: String) = prefs(ctx).getLong(key(id, "qr_clock"), -1L)
    fun hash(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }

    /** Read only confirmed clock anchors; never attach old data to a current QR. */
    fun knownSessions(ctx: Context, id: String): Map<String, Long> {
        val p = prefs(ctx)
        val suffix = "_${AnytimeConstants.PREF_TIMELINE_START_AT_PREFIX}$id"
        val result = mutableMapOf<String, Long>()
        p.all.forEach { (k, value) ->
            if (k.startsWith("anytime_archive_") && k.endsWith(suffix) && value is Long && value > 0) {
                result[k.removePrefix("anytime_archive_").removeSuffix(suffix)] = value
            }
        }
        val current = p.getString(key(id, "id"), null)
        val start = p.getLong(AnytimeConstants.PREF_TIMELINE_START_AT_PREFIX + id, 0L)
        if (current != null && start > 0) result[current] = start
        return result
    }

    /** Archive before clearing any live state; failed commits abort rollover. */
    fun rollover(ctx: Context, id: String, keepNewlyAssignedQr: Boolean): String {
        val p = prefs(ctx)
        val old = session(ctx, id)
        val next = UUID.randomUUID().toString()
        val e = p.edit()
        for ((k, v) in p.all) {
            if (!k.startsWith("anytime_") || !k.endsWith("_$id") || k.startsWith("anytime_archive_") || k.startsWith("anytime_qr_revision_")) continue
            val dest = "anytime_archive_${old}_$k"
            when (v) {
                is String -> e.putString(dest, v)
                is Int -> e.putInt(dest, v)
                is Long -> e.putLong(dest, v)
                is Float -> e.putFloat(dest, v)
                is Boolean -> e.putBoolean(dest, v)
                is Set<*> -> e.putStringSet(dest, v.filterIsInstance<String>().toSet())
            }
        }
        e.putString(key(id, "id"), next)
        e.remove("anytime_wear_live_ids_$id")
        e.remove("anytime_wear_model_k0_$id")
        e.remove("anytime_wear_backfill_$id")
        e.remove("anytime_manual_history_$id")
        if (keepNewlyAssignedQr) e.putString(key(id, "qr_session"), next)
        else {
            e.remove(key(id, "qr_session"))
            e.remove(AnytimeConstants.PREF_QR_CONTENT_PREFIX + id)
            e.remove(AnytimeConstants.PREF_K_PREFIX + id)
            e.remove(AnytimeConstants.PREF_R_PREFIX + id)
            e.remove(AnytimeConstants.PREF_VOLTAGE_PREFIX + id)
        }
        e.putInt(AnytimeConstants.PREF_LAST_GLUCOSE_ID_PREFIX + id, -1)
        e.putLong(AnytimeConstants.PREF_SENSOR_START_AT_PREFIX + id, 0L)
        e.putLong(AnytimeConstants.PREF_TIMELINE_START_AT_PREFIX + id, 0L)
        e.putLong(AnytimeConstants.PREF_WARMUP_STARTED_AT_PREFIX + id, 0L)
        e.remove(AnytimeConstants.PREF_RAW_HISTORY_PREFIX + id)
        e.remove(AnytimeConstants.PREF_REF_BG_MGDL_TIMES10_PREFIX + id)
        e.remove(AnytimeConstants.PREF_REF_BG_GLUCOSE_ID_PREFIX + id)
        e.remove(AnytimeConstants.PREF_REF_BG_APPLIED_GLUCOSE_ID_PREFIX + id)
        e.remove(AnytimeConstants.PREF_REF_BG_HISTORY_PREFIX + id)
        // Persisted continuity must not resurrect the old model after a crash.
        e.remove(AnytimeConstants.PREF_CALIBRATOR_TEMP_SMOOTH_PREV_PREFIX + id)
        e.remove(AnytimeConstants.PREF_CALIBRATOR_FILTERED_PREV_PREFIX + id)
        e.remove(AnytimeConstants.PREF_CALIBRATOR_LAST_ID_PREFIX + id)
        e.remove(AnytimeConstants.PREF_CT3_NATIVE_STATE_PREFIX + id)
        check(e.commit()) { "Cannot archive previous wear" }
        return next
    }
}
