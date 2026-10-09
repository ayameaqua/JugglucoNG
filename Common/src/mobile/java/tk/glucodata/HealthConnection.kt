/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Sun Mar 10 11:37:11 CET 2024                                                 */


package tk.glucodata

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission.Companion.getReadPermission
import androidx.health.connect.client.permission.HealthPermission.Companion.getWritePermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.Log.doLog
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class HealthConnection(private val client: HealthConnectClient) {
    private val recordVersions by lazy { HealthConnectRecordVersions(checkNotNull(Applic.app)) }
    private val sources by lazy { HealthConnectSources(checkNotNull(Applic.app)) }
    private val exportLock = Any()
    private val pendingExports = LinkedHashMap<Long, String>()
    private var exportWorkerActive = false
    private val activityImportActive = AtomicBoolean(false)

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun writeAllIns(sensorptr: Long, sensorName: String) {
        if (sensorptr == 0L) {
            if (doLog) Log.i(LOG_ID, "writeAll $sensorName: no sensorptr")
            return
        }
        val startWorker = synchronized(exportLock) {
            // Coalesce repeated triggers for the same native sensor. If a write
            // lands while an export is running, one follow-up pass remains queued.
            pendingExports[sensorptr] = sensorName
            if (exportWorkerActive) {
                false
            } else {
                exportWorkerActive = true
                true
            }
        }
        if (!startWorker) return

        scope.launch {
            while (true) {
                val request = synchronized(exportLock) {
                    val next = pendingExports.entries.firstOrNull()
                    if (next == null) {
                        exportWorkerActive = false
                        null
                    } else {
                        pendingExports.remove(next.key)
                        next.key to next.value
                    }
                } ?: return@launch
                try {
                    exportOneSensor(request.first, request.second)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (th: Throwable) {
                    // One sensor failing must not strand a later trigger in the
                    // queue. The cursor only advances after successful inserts.
                    Log.stack(LOG_ID, "writeAll", th)
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun exportOneSensor(sensorptr: Long, sensorName: String) {
        Log.i(LOG_ID, "writeAll 0x${sensorptr.toHexString()} $sensorName")
        if (!hasPermission) {
            // Background writes do not repeatedly launch permission UI for
            // every queued sensor. The settings/init flow requests it once.
            checkPermissionsAndRun(null)
            if (!hasPermission) {
                if (doLog) Log.i(LOG_ID, "No permission")
                return
            }
        }

        val source = HealthConnectSources.resolve(checkNotNull(Applic.app), sources, sensorName, sensorptr)
        val meta = androidx.health.connect.client.records.metadata.Metadata.unknownRecordingMethod(device = source.device)
        // One metadata replay also recovers previously suppressed sensors. This
        // only rewinds the export cursor; it never rewrites local glucose data.
        if (sources.needsReplay(source)) Natives.healthConnectResetSensor(sensorptr)
        while (true) {
            if (!Natives.gethealthConnect()) return
            val snapshot = Natives.healthConnectfromSensorptr(sensorptr)
            val end = ((snapshot ushr 16) and 0xFFFF).toInt()
            val start = (snapshot and 0xFFFF).toInt()
            if (start >= end) { sources.markPublished(source); return }
            val take = min(end - start, 500)
            // Materialize a bounded list before the suspending insert. Native
            // indices include empty minute slots, not just valid records.
            val records = recordVersions.batch(source.fingerprint) { versionOf ->
                GlucoseList(meta, sensorptr, start, take, sensorName) { id, mgdl -> versionOf(id, mgdl) }
            }
            if (records.isNotEmpty()) {
                sources.index(source, records, uploaded = false)
                val inserted = client.insertRecords(records)
                sources.index(source, records, uploaded = true, healthIds = inserted.recordIdsList)
            }
            // A concurrent backfill (including one inside this chunk) invalidates
            // the snapshot. Re-read from the preserved cursor; stable record IDs
            // make replay safe. Failed inserts never reach this acknowledgement.
            if (!Natives.healthConnectWritten(sensorptr, snapshot, start + take)) {
                if (doLog) Log.i(LOG_ID, "Native history changed during Health Connect insert; replaying")
            }
        }
    }

    private fun enqueueStoredSensors(replayAll: Boolean = false) {
        scope.launch {
            if (!Natives.gethealthConnect()) return@launch
            if (replayAll) Natives.healthConnectReset()
            // Retain existing clientRecordId aliases even when native uses a
            // short name for lookup. Finished sensors with poll history count too.
            val aliases = recordVersions.sensorAliases()
            for (nativeName in Natives.healthConnectSensorNames().orEmpty()) {
                val alias = aliases.firstOrNull { SensorIdentity.matches(it, nativeName) }
                    ?: SensorIdentity.resolveAppSensorId(nativeName) ?: nativeName
                writeAllIns(Natives.str2sensorptr(nativeName), alias)
            }
        }
    }

private suspend fun checkPermissionsAndRun(act:MainActivity?) {
        Log.i(LOG_ID,"Before getGrantedPermissions()")
        val granted = client.permissionController.getGrantedPermissions()
        Log.i(LOG_ID,"checkPermissionsAndRun granted=$granted")
        if (granted.containsAll(PERMISSIONS)) {
            Log.i(LOG_ID,"granted")
            hasPermission = true
        } else {
            hasPermission = false
            if(act?.permHealth!=null) {
                val request=act.permHealth
                withContext(Dispatchers.Main) {
                    request.request(PERMISSIONS)
                }
                Log.i(LOG_ID,"requested")
                }
            else
                Log.i(LOG_ID,"no act?.permHealth, not requested")
        }
    }

private fun importActivityIns(daysBack: Int) {
    if (activityImportActive.getAndSet(true)) {
        if(doLog) {Log.i(LOG_ID, "activity import already active");}
        return
    }
    scope.launch {
        try {
            if (!hasPermission) {
                checkPermissionsAndRun(MainActivity.thisone)
                if (!hasPermission) return@launch
            }
            val now = Instant.now()
            val start = now.minusSeconds(daysBack.coerceIn(1, 30) * 24L * 60L * 60L)
            val repository = JournalRepository()
            var imported = 0

            val sessions = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, now)
                )
            ).records
            sessions.forEach { session ->
                val startMillis = session.startTime.toEpochMilli()
                val endMillis = session.endTime.toEpochMilli()
                val durationMinutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1)
                repository.upsertEntry(
                    JournalEntryInput(
                        timestamp = startMillis,
                        type = JournalEntryType.ACTIVITY,
                        title = session.title?.takeIf { it.isNotBlank() } ?: "Health activity",
                        note = session.notes,
                        durationMinutes = durationMinutes,
                        intensity = durationMinutes.inferredHealthIntensity(),
                        source = JournalEntrySource.HEALTH_CONNECT,
                        sourceRecordId = session.stableHealthRecordId("exercise", startMillis, endMillis)
                    )
                )
                imported++
            }

            val steps = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, now)
                )
            ).records
            steps
                .filter { it.count >= 250L }
                .forEach { record ->
                    val startMillis = record.startTime.toEpochMilli()
                    val endMillis = record.endTime.toEpochMilli()
                    val durationMinutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1)
                    repository.upsertEntry(
                        JournalEntryInput(
                            timestamp = startMillis,
                            type = JournalEntryType.ACTIVITY,
                            title = "Steps",
                            note = "${record.count} steps",
                            amount = record.count.toFloat(),
                            durationMinutes = durationMinutes,
                            intensity = record.count.inferredStepIntensity(durationMinutes),
                            source = JournalEntrySource.HEALTH_CONNECT,
                            sourceRecordId = record.stableHealthRecordId("steps", startMillis, endMillis)
                        )
                    )
                    imported++
                }
            Log.i(LOG_ID, "Imported $imported Health Connect activity records")
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "importActivity", th)
        } finally {
            activityImportActive.set(false)
        }
    }
}


companion object {
    val PERMISSIONS =
        if(Build.VERSION.SDK_INT < 28) setOf("") else
            setOf(
                getWritePermission(
                    BloodGlucoseRecord::class
                ),
                getReadPermission(ExerciseSessionRecord::class),
                getReadPermission(StepsRecord::class)
            )
    @Volatile var hasPermission = false
    private const val LOG_ID = "HealthConnection"
   @Volatile
        private var instance:HealthConnection? = null

    private fun googleplay(context: ComponentActivity) {
        val playstr =
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect://onboarding"
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage("com.android.vending")
        intent.setData(Uri.parse(playstr))
        intent.putExtra("overlay", true)
        intent.putExtra("callerId", context.packageName)
        context.startActivity(intent)
    }
   fun init(context:MainActivity)  {
     if(instance==null) {
	       GlobalScope.launch {
		  susinit(context)
		}
       } else instance?.enqueueStoredSensors()

   }
private suspend   fun susinit(context: MainActivity): Int {

           if (Build.VERSION.SDK_INT < 28) {
               return HealthConnectClient.SDK_UNAVAILABLE

           }
           return try {
               var ret = HealthConnectClient.getSdkStatus(context)
               when (ret) {
                   HealthConnectClient.SDK_AVAILABLE -> {
                       Log.i(LOG_ID, "SDK_AVAILABLE")
                       val client = HealthConnectClient.getOrCreate(context)
                       val health = HealthConnection(client)
                       instance = health
                       Log.i(LOG_ID, "after getOrCreate")
                       health.checkPermissionsAndRun(context)
                       health.enqueueStoredSensors()
                       Log.i(LOG_ID, "after checkPermissionsAndRun")
		       MainActivity.tryHealth=0;
                   }

                   HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                       Log.i(LOG_ID, "SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED")
                       googleplay(context)
                       Log.i(LOG_ID, "After googleplay")
                   }

                   HealthConnectClient.SDK_UNAVAILABLE -> {
                       Log.i(LOG_ID, "SDK_UNAVAILABLE")

                   }

                   else -> Log.e(LOG_ID, "unknown return value from getSdkStatus(context)")
               }
               ret
           } catch (th: Throwable) {
               Log.stack(LOG_ID, "exception ", th)
               HealthConnectClient.SDK_UNAVAILABLE
           }
   }

fun writeAll(sensorptr:Long,sensorname:String) {
	instance?.writeAllIns(sensorptr,sensorname);
    }
    fun syncStoredSensors(replayAll: Boolean = false) { instance?.enqueueStoredSensors(replayAll) }
    fun importActivity(daysBack: Int = 14) {
        instance?.importActivityIns(daysBack) ?: MainActivity.thisone?.let { context ->
            GlobalScope.launch {
                susinit(context)
                instance?.importActivityIns(daysBack)
            }
        }
    }
    public fun stop() {
        instance?.scope?.cancel()
        instance = null
        }
    }
}

private fun Int.inferredHealthIntensity(): JournalIntensity {
    return when {
        this >= 75 -> JournalIntensity.INTENSE
        this >= 25 -> JournalIntensity.MODERATE
        else -> JournalIntensity.LIGHT
    }
}

private fun Long.inferredStepIntensity(durationMinutes: Int): JournalIntensity {
    val stepsPerMinute = this.toFloat() / durationMinutes.coerceAtLeast(1)
    return when {
        stepsPerMinute >= 110f -> JournalIntensity.INTENSE
        stepsPerMinute >= 70f -> JournalIntensity.MODERATE
        else -> JournalIntensity.LIGHT
    }
}

private fun androidx.health.connect.client.records.Record.stableHealthRecordId(
    type: String,
    startMillis: Long,
    endMillis: Long
): String {
    val id = metadata.id.takeIf { it.isNotBlank() }
    return "health_connect:$type:${id ?: "$startMillis:$endMillis"}"
}
