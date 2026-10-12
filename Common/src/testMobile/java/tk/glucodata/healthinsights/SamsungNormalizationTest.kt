package tk.glucodata.healthinsights

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class SamsungNormalizationTest {
    private val start = Instant.parse("2026-10-06T03:00:00Z")
    private fun row(kind: String, end: Instant = start) = HealthCanonical.base("id",kind,HealthCanonical.time(start,end,"+07:00"),"samsung_health",start)
    private fun normalize(row: JSONObject, fields: JSONObject): Pair<JSONObject,List<JSONObject>> {
        val original = fields.toString()
        val children = HealthCanonical.splitSeries(row,fields)
        val p = SamsungNormalization.Projection(row); children.forEach(p::child)
        assertEquals(original, fields.toString())
        return p.finish() to children
    }
    @Test fun pointOxygenIsInstantIntervalOxygenIsMeanAndEpochOffsetAreUnchanged() {
        val fields = JSONObject().put("OXYGEN_SATURATION",98).put("MIN_OXYGEN_SATURATION",97).put("MAX_OXYGEN_SATURATION",99)
        val point=normalize(row("blood_oxygen"),fields).first
        val interval=normalize(row("blood_oxygen",start.plusSeconds(60)),fields).first
        assertEquals("point",point.getString("granularity"))
        assertEquals("instant",point.getJSONObject("metrics").getJSONObject("oxygen_saturation").getString("statistic"))
        assertEquals("mean",interval.getJSONObject("metrics").getJSONObject("oxygen_saturation").getString("statistic"))
        assertEquals("+07:00",point.getJSONObject("time").getString("original_offset"))
        assertEquals(start.toEpochMilli(),point.getJSONObject("time").getLong("start_epoch_ms"))
    }
    @Test fun bodyUnitsFollowDocumentedPercentMassAndVolumeAndDoNotPromotePartial() {
        val r=normalize(row("body_composition"),JSONObject().put("WEIGHT",99.5).put("MUSCLE_MASS",20).put("SKELETAL_MUSCLE_MASS",36.8).put("TOTAL_BODY_WATER",50.1).put("HEIGHT",175).put("UNRECOGNIZED",9)).first
        val m=r.getJSONObject("metrics")
        assertEquals("%",m.getJSONObject("muscle_mass").getString("unit")); assertEquals("kg",m.getJSONObject("skeletal_muscle_mass").getString("unit"))
        assertEquals("L",m.getJSONObject("total_body_water").getString("unit")); assertEquals("cm",m.getJSONObject("height").getString("unit"))
        assertFalse(m.has("unrecognized")); assertEquals(9,r.getJSONObject("attributes").getJSONObject("sdk_fields").getInt("UNRECOGNIZED"))
        assertEquals("partial",r.getJSONObject("quality").getString("normalization"))
    }
    @Test fun exerciseSessionAndLogsMapDurationFractionCaloriesDistanceAndKnownUnits() {
        val fields=JSONObject("""{"SESSIONS":[{"getStartTime":{"utc":"2026-10-06T03:00:00Z"},"getEndTime":{"utc":"2026-10-06T03:01:00Z"},"getDuration":{"seconds":59,"nano":930000000},"getCalories":5,"getDistance":50,"getExerciseType":"WALKING","getLog":[{"getTimestamp":{"utc":"2026-10-06T03:00:01Z"},"getSpeed":1.2,"getPower":30,"getHeartRate":80}]}]}""")
        val (r,children)=normalize(row("exercise",start.plusSeconds(60)),fields)
        val m=r.getJSONObject("metrics")
        assertEquals(59.93,m.getJSONObject("duration").getDouble("value"),1e-6)
        assertEquals("kcal",m.getJSONObject("energy_burned").getString("unit"))
        val log=children.single { it.getJSONObject("attributes").getString("field_path").endsWith("getLog") }
        assertEquals("m/s",log.getJSONObject("metrics").getJSONObject("speed").getString("unit"))
        assertEquals("W",log.getJSONObject("metrics").getJSONObject("power").getString("unit"))
    }
    private fun sleepFields(overlap: Boolean = false) = JSONObject("""{"DURATION":{"seconds":600,"nano":0},"SESSIONS":[{"getStartTime":{"utc":"2026-10-06T03:00:00Z"},"getEndTime":{"utc":"2026-10-06T03:10:00Z"},"getStages":[{"getStartTime":{"utc":"2026-10-06T03:00:00Z"},"getEndTime":{"utc":"2026-10-06T03:05:00Z"},"getStage":"LIGHT"},{"getStartTime":{"utc":"2026-10-06T03:${if(overlap) "04" else "05"}:00Z"},"getEndTime":{"utc":"2026-10-06T03:07:00Z"},"getStage":"AWAKE"}]}]}""")
    @Test fun sleepIntervalAwakeAsleepAndUnclassifiedAreDistinctDeviceEstimates() {
        val r=normalize(row("sleep",start.plusSeconds(600)),sleepFields()).first
        val m=r.getJSONObject("metrics")
        assertFalse(m.has("duration"))
        assertEquals(600.0,m.getJSONObject("record_interval_duration").getDouble("value"),0.0)
        assertEquals(600.0,m.getJSONObject("sdk_session_duration").getDouble("value"),0.0)
        assertEquals(300.0,m.getJSONObject("estimated_asleep_duration").getDouble("value"),0.0)
        assertEquals(120.0,m.getJSONObject("estimated_awake_duration").getDouble("value"),0.0)
        assertEquals(180.0,m.getJSONObject("unclassified_duration").getDouble("value"),0.0)
    }
    @Test fun overlappingSleepStagesDoNotDoubleCountOrPretendDurationsAreComplete() {
        val r=normalize(row("sleep",start.plusSeconds(600)),sleepFields(true)).first
        assertFalse(r.getJSONObject("metrics").has("estimated_asleep_duration"))
        assertTrue(r.getJSONObject("quality").getJSONArray("flags").toString().contains("sleep_stage_bounds_or_overlap_review"))
    }
    @Test fun unknownStageAndMissingStagesAreNotInventedAsSleep() {
        val fields=sleepFields(); fields.getJSONArray("SESSIONS").getJSONObject(0).getJSONArray("getStages").getJSONObject(0).put("getStage","UNKNOWN")
        val r=normalize(row("sleep",start.plusSeconds(600)),fields).first
        assertEquals(0.0,r.getJSONObject("metrics").getJSONObject("estimated_asleep_duration").getDouble("value"),0.0)
        val missing=normalize(row("sleep",start.plusSeconds(600)),JSONObject()).first
        assertFalse(missing.getJSONObject("metrics").has("estimated_asleep_duration"))
    }
    @Test fun aggregateIsDailyDurationUsesNanosAndOverlappingSourcesAreNotAdded() {
        val r=row("activity_summary"); r.put("time",JSONObject().put("basis","local_date").put("date","2026-10-06").put("calendar_basis","SDK"));r.put("granularity","daily")
        val fields=JSONObject("""{"TOTAL_ACTIVE_TIME":[{"getValue":{"seconds":4151,"nano":111000000}}],"TOTAL_DISTANCE":[{"getValue":500},{"getValue":600}]}""")
        val result=normalize(r,fields).first
        assertEquals(4151.111,result.getJSONObject("metrics").getJSONObject("active_duration").getDouble("value"),1e-6)
        assertFalse(result.getJSONObject("metrics").has("distance"));assertEquals("daily",result.getString("granularity"))
    }
}
