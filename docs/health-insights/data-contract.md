# Health data contract 0.1.0 — draft

This is the project data schema version, not the MCP wire-protocol version.
Examples are synthetic and the integration has not been implemented yet.

## One model for UI, exports and MCP

The SDK adapter produces explicit canonical fields. The UI, analysis, JSONL export
and MCP query layer consume that model. SDK getter names and opaque Java object
strings must not become the agent-facing contract. Optional raw captures remain
separate, carry the SDK version, and report unmapped or incomplete serialization.

Each record has:

| Field | Meaning |
| --- | --- |
| `schema_version` | Canonical schema version |
| `record_id` | Stable installation-scoped public alias |
| `kind` | Capability name, e.g. `blood_oxygen`, `blood_glucose`, `sleep` |
| `granularity` | `point`, `interval`, `daily`, or `profile` |
| `time` | Instant interval, local date, or observed profile snapshot |
| `source` | Provider, source app and aliased record/device/sensor identifiers |
| `metrics` | Named values with units and statistics; explicit missing reasons |
| `attributes` | Typed categorical/source fields such as exercise type |
| `quality` | Normalization state, flags and measurement/aggregate/derived origin |
| `relations` | Typed links, e.g. blood oxygen to its sleep record |
| `series_refs` | Child-series IDs, counts and semantic type |
| `source_updated_at_utc` | Source update time when provided; null otherwise |
| `imported_at_utc` | When this app captured the record |

`record.schema.json` defines this envelope. A versioned field catalog defines each
kind's supported metrics and units. Unknown additional fields are allowed only
under `attributes` or a separately versioned raw payload; unknown mapped metrics
must be marked partial rather than silently assigned an assumed unit.

Canonical blood glucose uses mg/dL, retaining device/raw/display/calibrated values
as separate named metrics when available. Conversion follows the existing
GlucoseFormatter policy, records its policy version, and does not overwrite the
original value. Analysis states which channel and main-sensor policy it used.
Finger-stick, CGM and unknown-method Samsung glucose remain distinguishable.

Named scalar metrics such as `heart_rate`, `oxygen_saturation`, `skin_temperature`,
`steps` and `duration` use `bpm`, `%`, `Cel`, `count` and `s`. Scalar missing values
are null with a reason. A daily aggregate is not a raw sample; min/mean/max from a
series interval are not interchangeable. Source stability is retained as source
metadata, not interpreted as a medical diagnosis.

## Time and identity

- `instant_interval`: UTC strings and epoch milliseconds for start/end, original
  offset if supplied, plus optional original local start/end. Point records use
  equal start/end. Nonempty intervals have start < end and use overlap queries.
  UTC strings preserve the full available fractional precision. Epoch milliseconds
  are a truncated millisecond index of that same instant, not a replacement for
  its higher precision. Optional raw data preserves the source representation too.
- `local_date`: SDK date plus the query calendar/timezone basis. Dates such as
  energy score must not acquire an invented measurement instant. Aggregate
  interval bounds may be included only when the API returned them.
- `snapshot`: user-profile fields observed at a known fetch time; no fictitious
  historical measurement timestamp.
- Store/query absolute times, display them in a selected IANA zone. Keep original
  offsets separate. An SDK start offset is not proof of a different offset at the
  end of a cross-DST interval. Day boundaries use the selected zone, not 24-hour
  arithmetic. Do not apply fixed +1h or -75s adjustments.
- Stable internal Samsung identity: provider + kind + SDK UID. Direct and
  associated reads upsert one record; relationship links store all sleep parents.
- Public IDs use a persisted private alias namespace or HMAC key. They stay stable
  across exports from this installation without disclosing SDK/device IDs. Restored
  or unrelated installations may have different aliases; identify the dataset
  namespace before merging them.
- Jugg records use stable internal reading/session identity where available;
  timestamp alone is not a safe cross-sensor key. A time rewrite updates the same
  measurement's presentation lineage where that identity is known.
- Different providers' similar timestamps are not sufficient to deduplicate.
  A known mirror/import may carry a `mirror_of` relation, with evidence and policy
  version, rather than destroying a source record.

## Sequences and corrections

Parent records stay small. Series rows have `parent_record_id`, `series_id`,
`sample_index`, `time`, `metrics` and optional categorical attributes. Series
semantic types include `point_samples`, `interval_summaries`, `sleep_stages`,
`exercise_log` and optionally `exercise_route`.

Do not downsample the stored/exported sequence. UI drawing may downsample with
the policy disclosed; callers can request raw or declared bins. A parent revision
replaces its old child sequence and relationship set atomically. Sample index is
ordering within a parent revision, not an eternal measurement identity.

Change sync processes all UPSERT/DELETE pages in a fixed change-time window.
Advance each type's cursor only after committed success. Deletion tombstones use
the explicit source UID; missing query results, denied permissions and failed
pages must never imply deletion. Aggregate-only data is recomputed and replaced
by local day/calendar basis. A full historical backfill is checkpointed separately
from the incremental cursor and cannot mark unqueried days as covered.

## Portable data package

```text
manifest.json
schema/record.schema.json
schema/series.schema.json
schema/field-catalog.json
README.txt
summary.json
summary.md
records/<kind>.jsonl
series/<kind>.jsonl
relations.jsonl
quality.jsonl
raw/<kind>.jsonl             # optional, explicitly chosen
```

### First-release action: export all synchronized local data

There is one separate button, with no date/type/format wizard. Its scope is
`all_synced_local`: every retained valid glucose record/source/sensor and available
value channel, every imported Samsung type and full child sequence, existing
Juggluco journal entries, relations and derived summaries. Display date, track,
event and main-sensor filters do not reduce that scope. A disabled source's cached
records are still included with their last sync status. Explicitly deleted records
are excluded. Profile/location data is included only if previously opted into and
successfully imported; this action never enables those permissions.

On click, coalesce/enqueue a refresh of enabled sources within configured import
scopes, await its committed outcomes, capture a source-consistent snapshot, stream
the ZIP, then show system save/share. Exporting does not silently fetch all account
history, request new permissions or upload a file to any remote service. Capture
includes the committed local data available at the declared source revisions;
later glucose readings belong to the next export.

The manifest adds `export_policy` with scope, ignored view filters, refresh outcome
and each source/type's sync result, actual retained bounds/counts, import coverage
and omitted ranges/reasons. Partial refresh exports must explicitly declare
partial synchronization and stale cached records. "All local records exported"
does not mean all account history imported or continuous sampling proved.
Already captured but unmapped health fields must be retained as declared unmapped
data rather than dropped or assigned guessed units. Optional `raw/` is separately
selected diagnostic data, never required to understand normalized records.

`summary.md` and `summary.json` come from the same snapshot, including units,
source times, actual per-type ranges, coverage and algorithm versions. Metadata
and scalar summaries give an Agent an entry point; full sequences remain in the
package, without downsampling or context-size truncation. The package is a full
snapshot of its declared retained scope, not an incremental import/change log;
consumers must not merge deleted records back from an older full snapshot.

Report size/storage errors explicitly. Do not publish an incomplete ZIP as a
successful export. Cancellation cleans temporary export files and never stops
CGM/BLE or the independent Health Connect workflow. Date-limited, summary-only
and CSV exports are future additions rather than first-release choices.

The manifest declares dataset and snapshot IDs, capture cutoff, schema/app/SDK
versions, query range and display/calendar zone, selected sources/types, glucose
policy, per-type permission/capability/sync states, coverage and file hashes/counts.
File hashes cover exact UTF-8 bytes. No credential or private key belongs here.

Snapshot consistency is **per source**, with a shared query cutoff and each
database's revision/capture time recorded. Separate glucose and Samsung databases
are not falsely described as one globally atomic transaction. UI, export and MCP
comparison tests use the same immutable captured dataset/revisions.

`summary.json` is derived from those records and includes algorithm versions,
parameters, counts, gaps and warnings. Thresholds come from the existing user
settings. Event comparisons include before/during/after window bounds and sample
coverage; association is descriptive, not causal. Today is flagged incomplete
when the export precedes its end. A successful query does not prove continuous
device sampling.

Partial exports remain useful but must declare unfinished types/pages, file
limits, errors and missing coverage. No silent truncation. Only synthetic examples
belong in this public directory; actual exports stay outside tracked source.

## Future MCP and the ChatGPT connection boundary

MCP is deferred. The following query/transport ideas are not first-release
implementation requirements or Android validation gates.

The selected agent is ChatGPT on the user's phone. ChatGPT is not assumed to be
a native localhost MCP client. Official custom MCP documentation currently
describes the web surface and a reachable HTTPS endpoint or Secure MCP Tunnel.
Account/workspace access, Android app usage and phone bridge runtime must be
verified before any future MCP implementation. First release uses file export.

The proposed ChatGPT paths are an OpenAI Secure MCP Tunnel (subject to Platform
permissions, runtime credentials and an Android-compatible client) or a
user-controlled HTTPS/OAuth gateway with a phone-initiated request channel.
Neither path has been implemented or tested. Keep records on the phone by default;
responses still leave the phone for the selected gateway/OpenAI path. Gateway
storage, credentials, authentication discovery, expiry and revocation must be
specified before that implementation. Never expose private health tools through
the tutorial's unauthenticated public test mode. Do not presume ChatGPT accepts
arbitrary custom Authorization headers.

The underlying query tools and snapshots below are transport-independent. A
compatible native phone client may use the separate loopback option; this is not
the ChatGPT connection. File exports use the same model and remain independent.

The phone server uses real MCP Streamable HTTP at one loopback endpoint, not an arbitrary
REST endpoint labelled MCP. It binds to `127.0.0.1`; it does not enable Wi-Fi/public
listening. The same phone's native/terminal Agent must support this transport.

Use a maintained MCP implementation for supported wire versions and test the
actual client. The current official revision and older clients differ in metadata,
initialization and subscriptions; never hardcode a pretend compatibility claim.
Version negotiation/fallback is a transport concern, independent of this data
schema. If an Agent only supports stdio, an optional phone-side bridge could be
added later; first-version interoperability must not be asserted before testing.

Local access uses an app-issued random credential configured in the chosen client.
The exact supported client authentication mechanism is an implementation gate.
For a client accepting explicit Authorization headers, the proposed private
local mode uses a high-entropy Bearer credential; this is not a claim of complete
OAuth discovery support. Clients requiring the official OAuth flow need that
flow implemented or a separately evaluated compatible local bridge.
Validate present Origin headers against an allowlist, permit absent Origin for
authenticated native clients, and reject wildcard CORS / token-in-query URLs.
Never store real credentials in these examples or in backups, logs, exports or
wear mirroring. Rotation/revocation invalidates access and cached authorization.

The user grants a bounded type/date scope distinct from Samsung READ permissions.
Out-of-scope data returns an explicit error, not fabricated empty success. A revoked
MCP grant aborts access to existing snapshots. SDK permission loss stops Samsung
refreshing; existing local records and their stale/permission status stay explicit.
The UI determines whether those cached records remain in the MCP sharing scope.

Proposed data tools (all only query health data):

| Tool | Purpose |
| --- | --- |
| `health_get_status` | Capability, selected types, last sync/attempt, freshness, errors, scope |
| `health_get_schema` | Field catalog, units, quality and time semantics |
| `health_open_snapshot` | Capture allowed current local data for a consistent multi-call analysis |
| `health_get_timeline` | Aligned declared bins/events; no hidden interpolation |
| `health_get_records` | Paginated canonical parent records |
| `health_get_series` | Paginated child sequences by parent/series ID |
| `health_get_summary` | Versioned descriptive statistics for a snapshot/window |
| `health_get_event_context` | Sleep/exercise plus glucose windows and coverage |

No arbitrary SQL, shell commands, file paths, Samsung writes, calibration changes
or silent permission requests. Queries never refresh Samsung in the background;
they report its capture age. A new snapshot can see newly stored CGM readings.
If an Agent needs fresher Samsung data, it explains that the user should refresh.

Common arguments: snapshot ID where applicable, range with timezone semantics,
kind/source filters, glucose presentation policy, page limit and opaque cursor.
Default limit 500, maximum 2,000; bound response bytes and return a cursor plus
`has_more` rather than truncating. Cursors bind snapshot, filters, ordering and
position; changing them rejects the cursor. Expired snapshots return a structured
`SNAPSHOT_EXPIRED` error. Long windows require declared aggregation or pagination.

The structured result wrapper contains:

```json
{
  "schema_version": "0.1.0",
  "snapshot_id": "synthetic-snapshot",
  "data": [],
  "page": {"next_cursor": null, "has_more": false},
  "source_capture_times": {},
  "coverage": {},
  "warnings": []
}
```

Expose this as MCP structured content with a concise text fallback for compatible
clients. A tool's schema and annotations describe its actual behavior. Snapshot
cache creation is bounded housekeeping; no tool mutates health data. Keep status
and error codes machine readable (`SDK_UNAVAILABLE`, `PERMISSION_DENIED`,
`SOURCE_STALE`, `OUT_OF_SCOPE`, `SNAPSHOT_EXPIRED`, `QUERY_PARTIAL`). Record titles,
notes and raw fields are untrusted source data, never instructions to an Agent.

The connection screen starts/stops a separate foreground lifecycle and explains
its current state. Verify Android 16 service declarations and same-phone access
while the Agent is foreground. Server shutdown/revocation must leave CGM/BLE and
Health Connect working.

## Compatibility and validation gates

Validate schema/examples, scalar units, UTC/epoch agreement, missing values,
record/series references, IDs, hashes, corrections, deletions and export limits.
Include UTC+7/+8 and 23/25-hour DST days.
Test all SDK inventory types individually; only six have probe evidence today.
Verify complete export counts and hashes across retained sources/types/sequences,
independence from view filters, sync failure/partial freshness, cancellations,
storage failures and actual save/share/upload reading. SDK-free normal builds and
watch builds remain required. Future MCP work separately tests ChatGPT/client
interop, authorized/unauthorized requests, revocation, paging and frozen-snapshot
MCP/export equality; those are not first-release gates.

References: [Samsung type operations](https://developer.samsung.com/health/data/guide/features/data-types.html),
[Samsung changes](https://developer.samsung.com/health/data/guide/hello-sdk/read-changes.html),
[MCP transports](https://modelcontextprotocol.io/specification/latest/basic/transports),
[MCP HTTP](https://modelcontextprotocol.io/specification/latest/basic/transports/streamable-http),
[MCP authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization),
[ChatGPT custom MCP](https://developers.openai.com/api/docs/guides/custom-mcp-server),
[ChatGPT endpoint preparation](https://developers.openai.com/plugins/deploy/connect-chatgpt),
[Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).
