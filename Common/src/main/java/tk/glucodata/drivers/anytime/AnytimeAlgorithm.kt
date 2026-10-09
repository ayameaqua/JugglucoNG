// AnytimeAlgorithm.kt — Glucose computation, pure Kotlin. No vendor `.so` for readings.
//
// Paths:
//
//  1. NATIVE_PORT: `AnytimeNativeAlgorithm`, the pure-Kotlin port of the SHIPPED
//     vendor `.so` CT3 kernel (`yqidui_PX3`). Used for the CT3 families.
//     Validated against a Unicorn oracle (AnytimeNativeCt3Tests); stateful and
//     per-sensor, state persisted via AnytimeRegistry.
//
//  2. MODEL: `AnytimeCalibrator`, the pure-Kotlin port of the Reference App's MK4
//     chain (see docs/MK4_FINAL_SUMMARY.md), used for CT4. Empirical CT-14 model
//     for CT2. Stateful and per-sensor: see `calibratorFor`/`restoreCalibratorState`.
//     Only advanced from the live path (`advanceModelFallback = true`);
//     history/backfill records are not guaranteed ascending (recent-tail-first
//     backfill can revisit older ids after newer ones), so CT4 backfill replays an independent, ordered MODEL.
//
//  3. LINEAR: Pure-Kotlin raw display lane. It is not a replacement for the
//     algorithm and stays separate from the Auto lane; Auto+Raw modes must never
//     copy the auto value into the raw lane.
//
// CT5 is transmitter-computed (`fromComputedRecord`) with a learned Iw→mg/dL scale
// (`AnytimeCt5RawScale`), and never calls `compute()`.
//
// No JNI: the vendor `libalgorithm-jni.so` is not packaged and is not consulted for
// readings or QR decoding (`AnytimeQr` is the pure-Kotlin parser).
//
// This module hides the choice from callers — `compute()` always returns a `Result`.

package tk.glucodata.drivers.anytime

import tk.glucodata.Log

object AnytimeAlgorithm {

    private const val TAG = AnytimeConstants.TAG

    enum class Source { NATIVE, NATIVE_PORT, MODEL, LINEAR }

    /** One [AnytimeCalibrator] per sensor session, keyed by the persistent sensor id (SerialNumber). */
    private val calibrators = java.util.concurrent.ConcurrentHashMap<String, AnytimeCalibrator>()

    private fun calibratorFor(persistentSensorId: String, k0: Float): AnytimeCalibrator {
        val existing = calibrators[persistentSensorId]
        if (existing != null && existing.k0 == k0) return existing
        val created = AnytimeCalibrator(k0)
        calibrators[persistentSensorId] = created
        return created
    }

    /** Seed the pool from persisted state, e.g. in `restoreFromPersistence`, before any live call. */
    @JvmStatic
    fun restoreCalibratorState(persistentSensorId: String, k0: Float, state: AnytimeCalibrator.State) {
        if (persistentSensorId.isBlank() || k0 <= 0f) return
        val calibrator = AnytimeCalibrator(k0)
        calibrator.restoreState(state)
        calibrators[persistentSensorId] = calibrator
    }

    /** Read current continuity state for persistence, e.g. in `persistAlgorithmState`. */
    @JvmStatic
    fun snapshotCalibratorState(persistentSensorId: String): AnytimeCalibrator.State? =
        calibrators[persistentSensorId]?.snapshot()

    /** Drop pooled state for a sensor being removed/replaced. */
    @JvmStatic
    fun clearCalibratorState(persistentSensorId: String) {
        calibrators.remove(persistentSensorId)
        historyModels.remove(persistentSensorId)
    }

    // ---- CT3 native-port state (the vendored CT3 chain, pure Kotlin) ----

    private class NativePort(var state: AnytimeNativeState = AnytimeNativeState()) {
        /** The vendor `dynamic[0]`: the previous sample's glucose id. */
        var previousGlucoseId: Int = -1
    }

    private val nativePorts = java.util.concurrent.ConcurrentHashMap<String, NativePort>()

    private fun nativePortFor(persistentSensorId: String): NativePort =
        nativePorts.getOrPut(persistentSensorId) { NativePort() }

    /** Seed the pool from persisted state before the first live call. */
    @JvmStatic
    fun restoreNativePortState(persistentSensorId: String, encoded: String) {
        if (persistentSensorId.isBlank()) return
        AnytimeNativeState.decode(encoded)?.let { nativePorts[persistentSensorId] = NativePort(it) }
    }

    /** Read current continuity state for persistence; null when there is none. */
    @JvmStatic
    fun snapshotNativePortState(persistentSensorId: String): String? =
        nativePorts[persistentSensorId]?.state?.encode()

    @JvmStatic
    fun clearNativePortState(persistentSensorId: String) {
        nativePorts.remove(persistentSensorId)
    }

    /** CT3 families: the vendor kernel is the only source we port ourselves. */
    private fun isCt3Family(family: AnytimeConstants.FamilyEntry): Boolean =
        when (family.family) {
            AnytimeConstants.Family.CT3,
            AnytimeConstants.Family.CT3_PLUS,
            AnytimeConstants.Family.CT3_YUWELL,
            AnytimeConstants.Family.CT3_ULTRASONIC,
            -> true
            else -> false
        }

    /**
     * Pure-Kotlin CT3 chain (`AnytimeNativeAlgorithm`), used when the vendor `.so`
     * is absent. Stateful and per sensor; only advance from the live path.
     */
    private fun computeCt3NativePort(
        record: AnytimeRawRecord,
        calibration: AnytimeQrCalibration,
        persistentSensorId: String,
        lastReferenceBgMgdlTimes10: Int,
        lastReferenceBgGlucoseId: Int,
        rawMgdl: Float,
    ): Result {
        val port = nativePortFor(persistentSensorId)
        val attachReference =
            shouldAttachReferenceBg(record.glucoseId, lastReferenceBgGlucoseId, lastReferenceBgMgdlTimes10)
        val input = AnytimeNativeInput().apply {
            glucoseId = record.glucoseId
            iw = record.iwNa
            ib = record.ibNa
            temperatureC = record.temperatureC
            k0 = calibration.k
            r = calibration.r
            // Matches the JNI path: only the fingerstick fields are attached.
            flags = if (attachReference) 0x80 else 0
            newBgValue = if (attachReference) lastReferenceBgMgdlTimes10 / 10f else 0f
        }
        val out = AnytimeNativeAlgorithm.process(input, port.state, port.previousGlucoseId)
        port.previousGlucoseId = record.glucoseId

        val mmol = out.glucose.coerceAtLeast(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat())
        val mgdlTimes10 = (out.mgdl * 10)
            .coerceIn(AnytimeConstants.ALGO_MGDL_MIN_TIMES10, AnytimeConstants.ALGO_MGDL_MAX_TIMES10)
        return Result(
            glucoseId = record.glucoseId,
            mmol = mmol,
            mgdlTimes10 = mgdlTimes10,
            ibNa = record.ibNa,
            iwNa = record.iwNa,
            temperatureC = record.temperatureC,
            trend = out.trend,
            errorCode = out.errorCode,
            warnCode = out.warn,
            source = Source.NATIVE_PORT,
            rawMgdl = rawMgdl,
        )
    }

    /** Algorithm output. Mirrors the native `DataOutput` where the bundled JNI provides it. */
    data class Result(
        val glucoseId: Int,
        val mmol: Float,
        val mgdlTimes10: Int,
        val ibNa: Float,
        val iwNa: Float,
        val temperatureC: Float,
        val trend: Int,
        val errorCode: Int,
        val warnCode: Int,
        val source: Source,
        /** Uncalibrated/simple K/R glucose estimate for raw-history view. */
        val rawMgdl: Float = Float.NaN,
        // Native-only diagnostics (NaN/-1 for linear path):
        val sensitivityCoefficient: Float = Float.NaN,
        val kBase: Float = Float.NaN,
        val kAuto: Float = Float.NaN,
        val iw30Iir: Float = Float.NaN,
        val iw48Iir: Float = Float.NaN,
        val beVoltageMv: Int = Int.MIN_VALUE,
        val weVoltageMv: Int = Int.MIN_VALUE,
        val reVoltageMv: Int = Int.MIN_VALUE,
        val ceVoltageMv: Int = Int.MIN_VALUE,
        val bVoltageMv: Int = Int.MIN_VALUE,
        /** Official native calibration status; -1 when the native path did not report it. */
        val historyCompletePrefix: Boolean = false,
        val calibrationStatus: Int = AnytimeCalibrationPolicy.CALIBRATION_STATUS_UNKNOWN,
        val modelTrace: AnytimeCalibrator.Trace? = null,
        val modelInputPath: String? = null,
    ) {
        val mgdl: Float get() = mgdlTimes10 / 10f
    }

    internal fun shouldAttachReferenceBg(
        recordGlucoseId: Int,
        referenceGlucoseId: Int,
        referenceMgdlTimes10: Int,
    ): Boolean =
        referenceGlucoseId in 1..recordGlucoseId && referenceMgdlTimes10 > 0

    /**
     * Run the algorithm on a single raw record.
     *
     * @param record   parsed `RX_PUSH_GLUCOSE` / `RX_PULL_GLUCOSE` record
     * @param qr       calibration from the QR (K/R + chemistry IDs)
     * @param family   sensor family + algorithm dispatch id
     * @param sensorIdName advertised name (the JNI uses it to re-detect family)
     * @param sampleTimeMs wall-clock ms for this sample
     * @param lastReferenceBgMgdlTimes10 last fingerstick (mg/dL × 10), 0 if none
     * @param lastReferenceBgGlucoseId   id of the record at which it was set
     * @param sessionPacketsSinceInit    packet count since session start (warmup gate)
     * @param sensorStartTimeMs          estimated time for glucose id 0
     */
    @JvmStatic
    fun compute(
        record: AnytimeRawRecord,
        qr: AnytimeQrCalibration?,
        family: AnytimeConstants.FamilyEntry,
        sensorIdName: String,
        sampleTimeMs: Long,
        lastReferenceBgMgdlTimes10: Int = 0,
        lastReferenceBgGlucoseId: Int = 0,
        sessionPacketsSinceInit: Int = 0,
        recentRecords: List<AnytimeRawRecord> = listOf(record),
        recentRecordsProvider: (() -> List<AnytimeRawRecord>)? = null,
        sensorStartTimeMs: Long = 0L,
        logNativeFallbackWarnings: Boolean = true,
        /** Stable per-sensor key for the MODEL calibrator pool; see `calibratorFor`. */
        persistentSensorId: String = sensorIdName,
        /**
         * Advance the stateful MODEL fallback for this call. Only the live push
         * path may set this true — backfill ids are not guaranteed ascending
         * (recent-tail-first), which would corrupt the calibrator's continuity.
         */
        advanceModelFallback: Boolean = false,
    ): Result {
        val k = qr?.takeIf { it.hasAlgorithmCalibration }?.k ?: 0f
        val r = qr?.takeIf { it.hasAlgorithmCalibration }?.r ?: 0f
        val voltageFlag = qr?.voltageFlag ?: 0
        val linear = computeLinear(record, k, r, family, voltageFlag)
        val calibration = qr?.takeIf { it.hasAlgorithmCalibration }
        if (family.family == AnytimeConstants.Family.CT2) {
            return computeCt14(record, sampleTimeMs, sensorStartTimeMs)
        }
        if (family.family == AnytimeConstants.Family.CT4) {
            val k0 = if (k > 0f) k else AnytimeConstants.CT4_DEFAULT_K0
            val lastLive = snapshotCalibratorState(persistentSensorId)?.lastGlucoseId ?: -1
            return if (advanceModelFallback && record.glucoseId > lastLive) {
                computeModel(record, k0, persistentSensorId, linear.rawMgdl)
            } else {
                computeHistoryModel(record, k0, persistentSensorId,
                    recentRecordsProvider?.invoke() ?: recentRecords, family, qr)
            }
        }
        // No vendor `libalgorithm-jni.so` path: CT2 (empirical), CT4 (MK4) and CT5
        // (transmitter-computed + learned scale) are pure Kotlin, and CT3 runs the
        // pure-Kotlin port below. The vendor blob is never consulted for glucose.
        // CT3 has no reference model of its own: the pure-Kotlin native port is the
        // only calibrated path, and LINEAR the last resort. MODEL (MK4) is CT4-only,
        // so it is never used for CT3.
        if (isCt3Family(family)) {
            if (calibration != null && advanceModelFallback) {
                runCatching {
                    return computeCt3NativePort(
                        record = record,
                        calibration = calibration,
                        persistentSensorId = persistentSensorId,
                        lastReferenceBgMgdlTimes10 = lastReferenceBgMgdlTimes10,
                        lastReferenceBgGlucoseId = lastReferenceBgGlucoseId,
                        rawMgdl = linear.rawMgdl,
                    )
                }.onFailure { t ->
                    if (logNativeFallbackWarnings) {
                        Log.w(TAG, "CT3 native port failed: ${t.message}; using linear fallback")
                    }
                }
            }
            return linear
        }
        if (advanceModelFallback && k > 0f) {
            return computeModel(record, k, persistentSensorId, linear.rawMgdl)
        }
        return linear
    }

    /**
     * Reference App MK4 chain (see docs/MK4_FINAL_SUMMARY.md), stateful per
     * [persistentSensorId]. Only call with ascending [AnytimeRawRecord.glucoseId]
     * per sensor — see `advanceModelFallback` on [compute].
     */
    private fun computeModel(
        record: AnytimeRawRecord,
        k0: Float,
        persistentSensorId: String,
        rawMgdl: Float,
    ): Result {
        // Valid factory/manual coefficients override the reference default.
        val effectiveK0 = if (k0 > 0f) k0 else AnytimeConstants.CT4_DEFAULT_K0
        val calibrator = calibratorFor(persistentSensorId, effectiveK0)
        return modelResult(record, calibrator.computeNext(record), rawMgdl, calibrator.trace()).copy(modelInputPath = "live")
    }

    private fun modelResult(record: AnytimeRawRecord, filteredMmol: Float, rawMgdl: Float, trace: AnytimeCalibrator.Trace? = null): Result {
        val mmol = filteredMmol.coerceAtLeast(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat())
        return Result(glucoseId = record.glucoseId, mmol = mmol,
            mgdlTimes10 = (mmol * 180f + .5f).toInt().coerceIn(
                AnytimeConstants.ALGO_MGDL_MIN_TIMES10, AnytimeConstants.ALGO_MGDL_MAX_TIMES10),
            ibNa = record.ibNa, iwNa = record.iwNa, temperatureC = record.temperatureC,
            trend = 6, errorCode = 0, warnCode = 0, source = Source.MODEL, rawMgdl = rawMgdl, modelTrace = trace)
    }

    private class HistoryModel(val k0: Float) {
        var inputs: List<AnytimeRawRecord> = emptyList()
        val outputs = HashMap<Int, Result>()
        var model = AnytimeCalibrator(k0)
    }
    private val historyModels = java.util.concurrent.ConcurrentHashMap<String, HistoryModel>()

    /** Ordered replay with an independent filter. Never reads/writes the live filter. */
    private fun computeHistoryModel(record: AnytimeRawRecord, k0: Float, id: String,
        records: List<AnytimeRawRecord>, family: AnytimeConstants.FamilyEntry, qr: AnytimeQrCalibration?): Result {
        val pool = historyModels.compute(id) { _, old -> old?.takeIf { it.k0 == k0 } ?: HistoryModel(k0) }!!
        synchronized(pool) {
            val prefix = (records + record).associateBy { it.glucoseId }.values
                .filter { it.glucoseId <= record.glucoseId }.sortedBy { it.glucoseId }
            val reusable = pool.inputs.take(prefix.size) == prefix || prefix.take(pool.inputs.size) == pool.inputs
            if (!reusable) { pool.inputs = emptyList(); pool.outputs.clear(); pool.model = AnytimeCalibrator(k0) }
            if (prefix.size > pool.inputs.size) {
                for (item in prefix.drop(pool.inputs.size)) {
                    val raw = computeLinear(item, qr?.takeIf { it.hasAlgorithmCalibration }?.k ?: 0f,
                        qr?.takeIf { it.hasAlgorithmCalibration }?.r ?: 0f, family, qr?.voltageFlag ?: 0).rawMgdl
                    pool.outputs[item.glucoseId] = modelResult(item, pool.model.computeNext(item), raw, pool.model.trace())
                }
                pool.inputs = prefix
            }
            val complete = prefix.firstOrNull()?.glucoseId == 0 && prefix.zipWithNext().all { (a, b) -> b.glucoseId == a.glucoseId + 1 }
            return pool.outputs.getValue(record.glucoseId).copy(historyCompletePrefix = complete, modelInputPath = if (complete) "history_complete" else "history_partial")
        }
    }

    internal fun replayCt4History(records: List<AnytimeRawRecord>, qr: AnytimeQrCalibration?, family: AnytimeConstants.FamilyEntry): List<Result> {
        val ordered = records.sortedBy { it.glucoseId }
        if (ordered.firstOrNull()?.glucoseId != 0 || ordered.zipWithNext().any { (a,b) -> b.glucoseId != a.glucoseId + 1 }) return emptyList()
        val model = AnytimeCalibrator(effectiveModelK0(qr))
        return ordered.map { record ->
            val raw = computeLinear(record, qr?.takeIf { it.hasAlgorithmCalibration }?.k ?: 0f,
                qr?.takeIf { it.hasAlgorithmCalibration }?.r ?: 0f, family, qr?.voltageFlag ?: 0).rawMgdl
            modelResult(record, model.computeNext(record), raw, model.trace()).copy(historyCompletePrefix = true, modelInputPath = "history_complete")
        }
    }

    /** One linear pass per BLE page, including sparse tails, with no live state. */
    internal fun replayCt4AvailableHistory(records: List<AnytimeRawRecord>, qr: AnytimeQrCalibration?, family: AnytimeConstants.FamilyEntry): Map<Int, Result> {
        val ordered = records.associateBy { it.glucoseId }.values.sortedBy { it.glucoseId }
        val k0 = effectiveModelK0(qr)
        var model = AnytimeCalibrator(k0)
        var previous = -1
        var complete = ordered.firstOrNull()?.glucoseId == 0
        return buildMap {
            for (record in ordered) {
                if (previous >= 0 && record.glucoseId != previous + 1) { model = AnytimeCalibrator(k0); complete = false }
                val raw = computeLinear(record, qr?.takeIf { it.hasAlgorithmCalibration }?.k ?: 0f,
                    qr?.takeIf { it.hasAlgorithmCalibration }?.r ?: 0f, family, qr?.voltageFlag ?: 0).rawMgdl
                put(record.glucoseId, modelResult(record, model.computeNext(record), raw).copy(historyCompletePrefix = complete))
                previous = record.glucoseId
            }
        }
    }

    internal fun effectiveModelK0(qr: AnytimeQrCalibration?): Float =
        qr?.takeIf { it.hasAlgorithmCalibration }?.k ?: AnytimeConstants.CT4_DEFAULT_K0

    internal fun canRestoreCt4State(qr: AnytimeQrCalibration?, savedK0: Float?): Boolean =
        if (savedK0 != null) savedK0 == effectiveModelK0(qr)
        else qr == null || qr.hasAlgorithmCalibration

    /** Linear K/R fallback. */
    @JvmStatic
    fun computeLinear(
        record: AnytimeRawRecord,
        k: Float,
        r: Float,
        family: AnytimeConstants.FamilyEntry? = null,
        voltageFlag: Int = 0,
    ): Result {
        // Effective K/R defaults if the QR wasn't scanned: empirical CT3 averages.
        val kEff = if (k > 0f) k else 0.30f
        val rEff = if (r > 0f) r else 50f
        val rawIw = normalizedRawIw(record.iwNa, family, voltageFlag)
        val rawMmol = kEff * rawIw + rEff / 100f
        val mmol = rawMmol.coerceAtLeast(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat())
        val mgdlTimes10 = (mmol * 18.0f * 10f + 0.5f).toInt()
            .coerceIn(AnytimeConstants.ALGO_MGDL_MIN_TIMES10, AnytimeConstants.ALGO_MGDL_MAX_TIMES10)
        return Result(
            glucoseId = record.glucoseId,
            mmol = mmol,
            mgdlTimes10 = mgdlTimes10,
            ibNa = record.ibNa,
            iwNa = record.iwNa,
            temperatureC = record.temperatureC,
            trend = 6, // TREND_NONE — linear path doesn't compute trend
            errorCode = 0,
            warnCode = 0,
            source = Source.LINEAR,
            rawMgdl = rawMmol * 18.0f,
        )
    }

    /** CT2 aging-compensated raw current, nA. Split out so the term is unit-testable. */
    @JvmStatic
    fun ct14RawNa(iwNa: Float, sampleTimeMs: Long, sensorStartTimeMs: Long): Float {
        val elapsedDays = if (sensorStartTimeMs > 0L && sampleTimeMs > sensorStartTimeMs) {
            (sampleTimeMs - sensorStartTimeMs).toFloat() / 86_400_000f
        } else {
            0f
        }
        return iwNa + AnytimeConstants.CT14_AGING_NA_PER_DAY * elapsedDays
    }

    /**
     * CT2/CT-14 empirical model (see [AnytimeConstants.CT14_AGING_NA_PER_DAY]):
     *
     *   raw        = Iw + 0.4 · elapsedDays      (aging drift of the sensor)
     *   stock_mmol = (raw − intercept) / slope   (default CT2 calibration)
     *
     * The user's fingerstick calibration is applied later by the caller
     * (`AnytimeBleManager.applyUserCalibration`), on top of this stock value.
     */
    @JvmStatic
    fun computeCt14(
        record: AnytimeRawRecord,
        sampleTimeMs: Long,
        sensorStartTimeMs: Long,
    ): Result {
        val rawNa = ct14RawNa(record.iwNa, sampleTimeMs, sensorStartTimeMs)
        val stockMmol = (rawNa - AnytimeConstants.CT14_DEFAULT_INTERCEPT) / AnytimeConstants.CT14_DEFAULT_SLOPE
        val mmol = stockMmol.coerceAtLeast(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat())
        val mgdlTimes10 = (mmol * 18.0f * 10f + 0.5f).toInt()
            .coerceIn(AnytimeConstants.ALGO_MGDL_MIN_TIMES10, AnytimeConstants.ALGO_MGDL_MAX_TIMES10)
        return Result(
            glucoseId = record.glucoseId,
            mmol = mmol,
            mgdlTimes10 = mgdlTimes10,
            ibNa = record.ibNa,
            iwNa = record.iwNa,
            temperatureC = record.temperatureC,
            trend = 6, // TREND_NONE — this path does not compute a trend
            errorCode = 0,
            warnCode = 0,
            source = Source.LINEAR,
            rawMgdl = stockMmol * 18.0f,
        )
    }

    /** Use vendor-computed `0x0C` record directly (bypasses algorithm). */
    @JvmStatic
    fun fromComputedRecord(
        rec: AnytimeComputedRecord,
        qr: AnytimeQrCalibration? = null,
        family: AnytimeConstants.FamilyEntry? = null,
    ): Result {
        val rawLinear = computeLinear(
            AnytimeRawRecord(
                indexInPacket = 0,
                glucoseId = rec.glucoseId,
                ibNa = rec.ibNa,
                iwNa = rec.iwNa,
                temperatureC = rec.temperatureC,
                recordBytes = ByteArray(0),
            ),
            qr?.k ?: 0f,
            qr?.r ?: 0f,
            family,
            qr?.voltageFlag ?: 0,
        )
        val mgdlTimes10 = (rec.gluMgdl * 10).coerceIn(
            AnytimeConstants.ALGO_MGDL_MIN_TIMES10,
            AnytimeConstants.ALGO_MGDL_MAX_TIMES10,
        )
        return Result(
            glucoseId = rec.glucoseId,
            mmol = rec.gluMmol,
            mgdlTimes10 = mgdlTimes10,
            ibNa = rec.ibNa,
            iwNa = rec.iwNa,
            temperatureC = rec.temperatureC,
            trend = rec.trend,
            errorCode = rec.errorCode,
            warnCode = rec.warnCode,
            source = Source.NATIVE, // it's transmitter-native, even more authoritative
            rawMgdl = rawLinear.rawMgdl,
            beVoltageMv = rec.beVoltageMv,
            weVoltageMv = rec.weVoltageMv,
            reVoltageMv = rec.reVoltageMv,
            ceVoltageMv = rec.ceVoltageMv,
            bVoltageMv = rec.batteryRaw,
        )
    }

    /**
     * Decode the QR string with the pure-Kotlin parser (see [AnytimeQr], which ports
     * the vendor's own regex shapes). No JNI fallback: the vendor `decodeCT` is not
     * packaged and, on real labels, mis-reads some codes (a 15-day CT4 sticker comes
     * back with `lifeTime = 6`).
     */
    @JvmStatic
    fun decodeQr(qr: String): AnytimeQrCalibration? = AnytimeQr.parse(qr)

    private fun normalizedRawIw(
        iwNa: Float,
        family: AnytimeConstants.FamilyEntry?,
        voltageFlag: Int,
    ): Float {
        if (iwNa <= 0f || !iwNa.isFinite()) return iwNa
        return if (family?.family == AnytimeConstants.Family.CT4 && voltageFlag == 1) {
            iwNa / 2f
        } else {
            iwNa
        }
    }

}
