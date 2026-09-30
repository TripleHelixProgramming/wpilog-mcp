# Robustness Plan: Remediating the Robustness Review

| | |
|---|---|
| **Date** | 2026-09-29 |
| **Addresses** | [ROBUSTNESS_REVIEW.md](ROBUSTNESS_REVIEW.md) (issue IDs A1–H and rules R1–R8 refer to it) |
| **Baseline** | HEAD `4b39641` plus the working-tree changes the review calls **WT** |
| **Status** | In progress — see [Progress](#progress) |

## Decisions

Recorded 2026-09-29; the maintainer concurred with each recommendation.

1. **Struct output keys (C4): one clean break.** Decoded structs use the schema's own nested field names. No legacy
   aliases: agents read fresh output on every call, and duplicate keys would bloat every response.
2. **`success` is `false` for `not_applicable` and `no_match`**, as the review proposes (`true` only for `ok` and
   `partial`).
3. **`predict_battery_health` keeps its health score** (a settled decision in `CODE_REVIEW_REJECTION.md`). G2 is met by
   fixing the evidence the score and recommendations rest on, and their wording.
4. **`can_health` keeps its name** but shares one implementation with `analyze_can_bus` (reads the same counters).

## What verification changed

The review was checked against the baseline source (log layer, statistics and query tools, response plumbing by
hand; domain, analysis, export, and core tools by two independent verification passes). Every issue reproduces as
described. These findings change how the fixes are built:

- **WPILib already ships a schema-driven struct decoder.** `edu.wpi.first.util.struct.StructDescriptorDatabase` and
  `DynamicStruct` are in wpiutil-java 2026.2.2 (already a dependency). They are pure Java and handle nested structs
  (even when defined out of order), fixed-size arrays, enums, and bit-fields. §5.1 is wiring, not a new parser. For
  logs without schemas, canonical layouts come from wpimath (`Pose2d.struct.getSchema()` and so on).
- **25 of the 45 tools build their JSON by hand** instead of using `ResponseBuilder` (all of CoreTools, six FRC-domain
  tools, six robot-analysis tools, export, discovery, and TBA). The result contract is therefore enforced once, in
  `ToolBase.execute`, where it covers every tool; hand-built tools move to `ResponseBuilder` when next touched.
- **`NaN` leaves the server as invalid JSON.** Gson writes `JsonElement` trees leniently, so results contain a bare
  `NaN` token. Emitters are more widespread than A6: `time_correlate`, `moi_regression`, loop timing, CAN averages,
  `analyze_auto`, `profile_mechanism`, swerve speeds. A central guard beats per-tool fixes.
- **More wrong answers of the same kinds** (not in the review):
  - `analyze_replay_drift` reports every array entry as divergent (`Objects.equals` on Java arrays).
  - `export_csv` shifts `Pose2d[]`/`Pose3d[]` columns by one, mislabels other struct arrays, and writes `double[]`
    fields as `[D@…`.
  - `get_match_phases` can report a negative `auto_duration`, can label disabled time as teleop, and fills missing
    durations from game data without saying so.
  - The swerve drift and vision jump helpers return distance 0 for layouts they don't recognize, so "no drift" and
    "no jumps" are silent.
  - `predict_battery_health` picks its voltage entry its own way, which can disagree with `get_ds_timeline`.
  - `get_ds_timeline` duplicates events when a log has both `DS:` and `/DriverStation/` entries.
  - `analyze_can_bus` counts samples above zero, not counter increments.
  - `ToolUtils.isEnabledAt` treats "no enable data" as "enabled".
- **Descriptions promise output no code produces**, beyond G4: temperature profiling (`profile_mechanism`), current
  draw (`predict_battery_health`), peak currents (`generate_report`), and the messages "no auto period detected",
  "no swerve modules detected", and "no code metadata found".
- **Guidance is coupled to today's limits.** `AnalysisGuidance` traps say numeric tools skip arrays, that three tools
  always run over the whole log, and they spell out the current quality formula; CLAUDE.md says `confidence_level`
  reads "low" on good data. Each phase updates these in the same change. The `initialize` instructions are at 2,006
  of 2,048 bytes (JSON-escaped), so adding G3's sentence means trimming something else.
- **The §6 determinism test conflicts with the tie rule.** Shuffling declaration order would flip genuine ties, which
  are broken by declaration order. The test permutes map iteration order instead (keeping entry ids), and genuine
  top-rank ties are reported as `ambiguous`.

**Corrections to the review.** `profile_mechanism` matches `mechanism_name` anywhere in an entry name, not only as a
prefix. `power_analysis` already has a `brownout_threshold` parameter (default 6.8 V; the logged value is never
read). The vision latency claim is in the class Javadoc and the `suggest_tools` catalog, not the MCP description.
Spelled-out `ReceiveErrorCount` does match the CAN error rule; `TEC` and `REC` don't.

## Phases

| Phase | Theme | Closes | Size |
|---|---|---|---|
| 0 | Baseline and measurement | (harness) | S |
| 1 | Honest results | A1–A7, B9, B10, E1, E2, G3; D3 paths, G4 wording, G5 totals, part of B1 | M |
| 2 | Schema-driven structs and field paths | C1–C5, D1, D2, G7 | L |
| 3 | Roles, one resolver, scope | B1, B3–B8, E3, E4, F3, G2 | L |
| 4 | Strings, intervals, alignment, quality | F1, F2, G1, H; rest of G5 and D3 | M–L |
| 5 | Consistency and docs | G4 test, G6, §5.8 | S–M |

B2 is already fixed in the baseline. Each phase leaves the server consistent and can ship as its own minor version.
Standing rule for every change: update the tool description, `doc/TOOLS.md`, `CHANGELOG.md`, and any
`AnalysisGuidance` text it affects.

### Phase 0 — Baseline and measurement (no behavior change)

- Commit the baseline working tree on a branch, so each phase is a reviewable change.
- Fixture corpus: a test helper writes small real `.wpilog` files with `DataLogWriter` and `StructLogEntry` (real
  schemas), one per row of the review's §6 table, plus a log with no DriverStation data and one with both `DS:` and
  `/DriverStation/` entries.
- `ToolConformanceTest`: every tool against every fixture, checking the §6 rules (status, no non-finite numbers,
  totals, determinism under permuted map iteration order). It starts with a checked-in known-failures list; no change
  may add to it, and each phase removes its rows.
- Golden test of Appendix A against the real log, opt-in by system property like the stress tests.
- `MockLogBuilder` made deterministic (`LinkedHashMap`, seeded random numbers instead of `Math.random`).

### Phase 1 — Honest results

1. **Result contract**, enforced in `ToolBase.execute` so all tools get it: `status` ∈ {`ok`, `partial`,
   `not_applicable`, `no_match`}; `success` true only for `ok`/`partial`; `inputs`; `skipped` (each with a reason);
   `total`/`returned` on every limited list. `ResponseBuilder` gets the helpers.
2. **Non-finite guard** at the same boundary: tests fail on any `NaN`/`Infinity` in a result; production replaces the
   value with `null` and names the field in a warning. Then fix each emitter.
3. **Determinism (B9):** `LinkedHashMap` in `LazyParsedLog` and `LogParser`; each "first matching entry" becomes an
   explicit ranking with an entry-id tie-break (interim, until the Phase 3 resolver).
4. **Stop silent success:** A1–A7 as the review proposes, plus the extras above. Replay reports `pairs_compared`,
   compares arrays element-wise, and uses a numeric tolerance; the distance helpers fail instead of returning 0;
   tool-specific notice keys move into `warnings`.
5. **Descriptions match behavior (R8):** implement or remove every promise listed above; implement or drop
   `spike_threshold`.
6. **`get_match_phases` (E1, E2):** enabled segments with `end_reason` and `mode`; sample-and-hold for change-only
   DriverStation values; say when a duration came from game data; match phases only when FMS is attached or the
   pattern fits game timing; keep `phases` for compatibility. Built as a per-log `MatchTimeline` that Phase 3's
   `scope` builds on. `isEnabledAt` returns "unknown" instead of "enabled"; duplicate DriverStation events fixed.
7. **`analyze_loop_timing`:** an `entry` parameter, AdvantageKit's `FullCycleMS`/`UserCodeMS` as candidates, and the
   unit decision stated in the result.
8. **A working escape hatch (G3 + D3):** `export_csv` resolves bare and relative names inside the export directory,
   returns the absolute path written, names the real directory in errors, and fixes the column bugs; the instructions
   gain "export and compute externally, citing the export". Until Phase 2 this is the only route for struct data.

### Phase 2 — Schema-driven structs and field paths

1. Per-log schema database built from `/.schema/struct:*` entries at the end of the `LazyParsedLog` scan (and a first
   pass in `LogParser`). Immutable once built, so concurrent reads are safe; `DynamicStruct` is not thread-safe, so
   one is created per decode.
2. A `StructValue` (schema reference + record bytes) replaces the per-sample `LinkedHashMap`. Field accessors are
   compiled once per struct type. JSON uses the schema's nested field names and enum labels. Record length is checked
   exactly (a multiple of the struct size for arrays); a mismatch is a decode error that `get_entry_info`,
   `list_entries`, and every tool using the entry report. Update the Caffeine weigher; one Gson with a `StructValue`
   adapter.
3. No schema entry: fall back to wpimath's canonical layouts; only `PoseObservation`, `TargetObservation`, and
   `SwerveSample` get hardcoded layouts, marked `layout_source: "assumed"`.
4. Today's decoders become enrichers, applied only on an exact schema match. Derived values (degrees, yaw) go under
   `_derived` and are addressable by path; on a mismatch, enrichment is skipped with a warning.
5. Field paths: `name.translation.x`, `[2]`, `[*]`, plus a `field` parameter; an exact entry name always wins (names
   can contain dots). Booleans read as 0/1; angle fields are marked for unwrapping and circular statistics. Every
   numeric tool uses it; type errors list the numeric fields (D2).
6. `list_entries`/`get_entry_info` show the schema and field paths and prefer non-empty samples (G7);
   `list_struct_types` takes an optional `path` (C1).
7. Migrate code reading the old keys (export, vision, swerve) and the tests (~45 usages in 9 files); `MockLogBuilder`
   packs real structs.

Breaking output change: minor version bump and a CHANGELOG entry. The parsed-log disk cache is not on the load path,
so no persisted data goes stale.

### Phase 3 — Roles, one resolver, scope

1. `SignalResolver` with a `Role` enum, resolving in the §5.3 order (explicit parameter → type/schema → metadata →
   ranked names). Ties go to the lowest entry id and are flagged `ambiguous` with candidates. Cached per log
   (thread-safe; dropped with the log); explicit overrides are never cached. Replaces `isDsEntry`, `findDsEntry`,
   `selectVoltageEntry`, and the inline discovery loops; adds `FMSInfo/FMSControlData` for NetworkTables-only logs.
2. Results record `resolved`. A `resolve_signals` tool lets an agent check the mapping once and override it.
3. `scope`/`windows` on the statistical tools (legacy `start_time`/`end_time` = one window); data quality computed
   within scope.
4. Brownouts (E3): the logged threshold if present, else a stated default; all four power tools take brownout events
   from the logged flag.
5. Tool rewrites, one change each, checked against fixtures and Appendix A: loop timing (full B1 chain); power,
   battery, and report (B8, E3, G2 — duration-qualified brownouts, enabled-only averages); CAN (B3, with `can_health`
   reading the same counters for F3); swerve (B5); vision streams by schema (B4; residuals wait for Phase 4);
   `profile_mechanism` (B6, plus its overshoot and window bugs); `analyze_auto` (B7); `compare_matches` (E4).

### Phase 4 — Strings, intervals, alignment, quality

1. One text-event source (F1, F2) for `search_strings`, `get_ds_timeline`, `can_health`, and `generate_report`:
   `string` lines, `string[]` alerts tracked as state (appear, clear, duration), `json` string values.
2. `find_condition` returns intervals (total time, fraction of scope, both edges) and accepts booleans, time ranges,
   and compound `all`/`any` conditions across entries; its output can be passed as `windows`.
3. `detect_anomalies`: scope, sort by time or severity, true totals (G5).
4. `align_entries` (§5.6); `compare_entries` and `time_correlate` accept fields and a lag search; vision residuals built
   on alignment.
5. `export_csv` flattens struct fields and arrays into columns; capped inline mode.
6. `DataQuality` recalibration (§5.7), then rewrite the calibration text in `AnalysisGuidance` and CLAUDE.md.
7. The §5.6 pose helpers are optional; deferred until an analysis needs them.

### Phase 5 — Consistency and docs

- Description test (G4): each tool declares its output keys; the test checks each appears in at least one fixture
  result.
- `list_available_logs` paging and filters (G6); guidance audit within the 2 KB limit.
- Regenerate `TOOL_RESPONSES.md` from a checked-in sweep harness (Appendix B as code); add new tools to both stress
  tests.
- Done when the conformance known-failures list is empty and the golden test passes.

## Findings during implementation

- **Two golden values in the review are wrong** (recomputed with `wpiutil.log.DataLogReader` and numpy):
  `/RealOutputs/CANBus/CANHD/TEC` peaks at **215 at 650.86 s** (five seconds before the first brownout), not 85 at
  205.76 s. 85 was the first of 13 excursions from zero, and the counter rose past error-passive (128) nine times.
  The camera 3 alert is in `/RealOutputs/Alerts/warnings` (not `errors`) from 737.678 to 783.804 s;
  `/Vision/Camera3/Connected` is false until 783.858 s, which explains the review's two end times.
  `ReviewLogGoldenTest` uses the corrected values.
- **WPILib 2026.2.2's native `DataLogWriter` blocks forever** inside `appendRaw` once a log grows past roughly a
  megabyte (200 k plain doubles is enough). Fixtures are therefore written by a small pure-Java WPILOG writer
  (`fixtures/WpilogWriter`), validated against WPILib's own Java reader and the Python (C++) reader.
- **wpimath cannot be used at runtime or in tests without extra libraries**: its geometry classes load protobuf and
  units classes during initialization, and main code never uses wpimath. Canonical WPILib schemas are therefore
  written out as strings (they match the ones in real logs byte for byte).
- **`LogCache` closes a log even when it is replaced** (not only evicted). Harmless in production, where the same path
  is never loaded twice concurrently, but test code must not swap a cached log for a wrapper around it.
- **`ToolUtils.estimateSeasonYear` reads the first `20xx` anywhere in the file path**, so a directory name can change
  the season. The log itself records the date (`/SystemStats/EpochTimeMicros`, `systemTime`,
  `/RealMetadata/BuildDate`); the season should come from there first.
- **WPILib's `DataLogIterator.hasNext()` requires 16 bytes after a record's start** (`m_pos + 16 <= size`), so the
  for-each form silently skips a final record shorter than that — a boolean, a small number, a short struct. This is
  what the old tests' "sentinel" records worked around ("DataLogWriter drops the last record"). `forEachRemaining`
  bounds records correctly; both parsers now walk records by their own lengths (`DataLogAccess.recordEnd`).

## Exit checks (Appendix A)

- **Phase 1:** four enabled segments, the last ending at `log_end`.
- **Phase 2:** the byte-for-byte decode check; pose wander over 362–394 s (x 29.0 cm, y 59.9 cm, heading 7.33°) via
  `get_statistics` with field paths.
- **Phase 3:** loop p95 53.1 ms and 18.5 % of loops over 25 ms while enabled; CANHD TEC maximum 215 at 650.86 s; mean
  |speed| 0.97 m/s while enabled (per module 0.950–0.987); per-camera observation counts.
- **Phase 4:** the camera 3 alert (`/RealOutputs/Alerts/warnings`, 737.678–783.804 s); "disabled and stationary" as one
  compound condition.

## Appendix: verification detail

Line numbers are for the baseline source; class and method names are given so they survive edits.

**`get_match_phases`** (`RobotAnalysisTools.GetMatchPhasesTool`). First name with `isDsEntry && contains("enabled")`;
first with `contains("autonomous"|"auto") && !contains("command")`. `lastDisableTime` is overwritten by every `false`
after the first enable, so a final segment running to end of log is lost (the `maxTimestamp` fallback only runs if no
`false` followed). Later auto rising edges overwrite `autoStart`, so `auto_duration` can go negative. The
`teleopStart = autoEnd` fallback fires whenever teleop is still unset, labeling disabled time as teleop. Missing auto
end, endgame, and season year are filled from the game knowledge base (or the clock year) with no warning. A single
`Autonomous=false` sample triggers neither edge; a single `true` sample counts as a rising edge.

**`analyze_swerve`** (`RobotAnalysisTools.AnalyzeSwerveTool`). Non-swerve entries → `other`. `extractSpeeds` averages
signed `speed_mps` over all elements. `extractSingleSpeed` returns NaN for arrays, so slip compares nothing and
`wheel_slip` is omitted. Sync needs ≥2 measured entries. Drift uses `findFirst` for "odometry"/"estimatedpose" and
"vision"+"pose"; an explicit `odometry_entry`/`vision_entry` that doesn't exist gives a silent null. `poseDistance`
returns 0 for unrecognized layouts and counts it as a comparison. No module labels anywhere; data quality from
`states.get(0)` (hash order). The description's "no swerve modules detected" string is never produced.

**`power_analysis`.** Uses `selectVoltageEntry`; `brownout_threshold` parameter default 6.8; never reads
`BrownoutVoltage`/`BrownedOut` (`brownoutvoltage` is only a "not the battery" hint in `voltageEntryRank`).
`brownout_risk` is HIGH if any sample is below threshold, MODERATE below threshold+1 V, over the whole log.

**`can_health`.** Scans only `string` entries, whole sample; rule `contains("can") && (timeout|error|fault)` ("can"
matches cannot/scan/cancel; "fault" matches "default"). No counters. Without DS data every error counts as enabled.
GOOD / CONCERNING (<50) / POOR, not duration-normalized. Data quality computed on unsorted concatenated samples.

**`compare_matches`.** Parameters `path`, `compare_path`, `name`; whole-log min/max/mean; quality from log 1 only.

**`generate_report`** (`ExportTools`). Battery: first name containing `batteryvoltage`/`battery_voltage` (hash order);
`brownout_risk` from hardcoded 6.8/9.0; one NaN sample poisons `Math.min` and the battery section is silently
dropped. Errors: every `string` entry, per batch, `error|exception|fault` ("default" matches); preview = first 100
chars; first 5 only. Description promises "peak currents" (never computed). `code_info` keeps the last GitSHA match,
`get_code_metadata` the first.

**`export_csv`.** Allowed: only `{java.io.tmpdir}/wpilog-export` (or `-exportdir`, `WPILOG_EXPORT_DIR`, config
`exportdir`), checked after `toRealPath`; a missing parent directory is an IOException treated as a rejection.
Relative names resolve against the server's working directory. The error names directories that are not allowed.
Pose2d[]/Pose3d[] headers lack the index column the rows have; other struct arrays get header
`timestamp_sec,index,value` while rows carry all fields; SwerveSample `moduleForcesX/Y` written as `[D@…`.

**`list_available_logs`** has no parameters. **`get_entry_info`** samples indices {0, n/2, n−1} and shows the
decoder's keys; suggestions are case-sensitive. **`list_struct_types`** is a hardcoded list of 16 names (SwerveSample
filed under "vision"). **`suggest_tools`** is keyword scoring with hash-order ties. **`read_entry`** has no maximum
`limit`; not-found gives no suggestions.

**`analyze_vision`** (`FrcDomainTools.AnalyzeVisionTool`). `vision_prefix` (case-sensitive `startsWith`) gates both
target and pose discovery. `pose_jumps` added only when non-empty; no warning, no pose override. Targets: `hastarget`,
`/tv`, `.tv`, `targetvalid`. Poses: contains `pose`, not `target`, type contains `Pose2d`/`Pose3d` (arrays then skipped
as non-Map). `calculatePoseDistance` returns 0 when it finds no x/y. `acquisition_rate` is a per-sample ratio.

**`analyze_auto`.** With no auto period the result is `success` + `data_quality` only; the promised "no auto period
detected" is never produced. Chooser: first name containing `chooser`, first value ever logged (not at auto start),
no type check. `path_following_error` omitted silently; discovery keeps the last match; only the first auto period.

**`profile_mechanism`.** `mechanism_name` is a case-insensitive substring match. Roles by first match: setpoint
(`setpoint`|`goal`), measurement (`position`|`actual`, not `setpoint` — `GoalPosition` can be both), velocity, current.
Following error silently absent unless both found; RMSE ignores the time window; `overshoot_percent` measures the
step itself. Description promises "motor temperature profiling" (not implemented).

**`analyze_replay_drift`.** `/RealOutputs/` → `/ReplayOutputs/`; unpaired entries dropped uncounted; `Objects.equals`
(reference equality for Java arrays, so every array entry is divergent); first divergence per entry; samples without
a replay sample within 1 ms skipped uncounted; list capped at 10.

**`analyze_loop_timing`.** `contains("looptime") || (contains("loop") && contains("time"))`, first match, no type
check, no override. Units: `s` ×1000; `auto` by median ([0.001, 1) → seconds, > 500 → µs); any other `unit` silently
ms; only the µs decision is reported. Non-finite values flow into statistics.

**`analyze_can_bus`.** `bus_name` unused. `can` + `util|bandwidth|busoff` → utilization (raw values labeled
`avg_percent`/`max_percent`); `can` + `error|fault|timeout` → errors. `error_count` = samples with value > 0; entries
with zero errors omitted. Confidence from the first utilization entry's whole-series quality. Notices in ad hoc
`ds_enabled_warning`/`warning` keys.

**`predict_battery_health`.** Threshold default 6.8. "URGENT: Replace battery immediately" whenever any crossing below
threshold occurs (0.2 V hysteresis, no minimum duration); "Average voltage low" when the average (idle included) is
below a hardcoded 11.5 V; 9.0 V ignores `warning_threshold`. Score: −20 per brownout, −5 per sag, and more; CRITICAL
if any sample is below threshold. Own voltage discovery (`findEntryByPattern`, first substring match, no type check);
"current draw" never read.

**`get_ds_timeline`.** Only `string` entries scanned for text (`string[]`, `json` skipped). Events for every DS entry
containing `enabled` or `auto` (duplicates with both naming conventions); no warning when no DS entries exist.

**Other.** `moi_regression`: NPE on missing `kt`/`gear_ratio`; no positivity checks; NaN `r_squared`.
`get_code_metadata`: `{metadata:{}}` with no message; first match on duplicates. `get_server_guide` with an unknown
category returns empty categories silently. `analyze_cycles`: incomplete idle period not bounded by `end_time`;
`sample_count` counts the whole entry. `ToolBase.extractNumericData` (which filters to finite values) is unused.

## Progress

- [x] **Phase 0 — Baseline and measurement.** Fixture corpus (20 logs written by a pure-Java WPILOG writer),
  `ToolConformanceTest` with a known-failures ratchet (110 violations at baseline; 164 once the status, limits, and
  truncation checks were added), opt-in `ReviewLogGoldenTest`, deterministic `MockLogBuilder`.
- [x] **Phase 1 — Honest results.** Result contract enforced in `ToolBase.execute`; NaN/Infinity replaced and named;
  declaration-order entries; `MatchTimeline` (segments, sample-and-hold, FMS matches, season from the log's clock)
  behind `get_match_phases`, `get_ds_timeline`, and `analyze_auto`; `no_match`/`not_applicable` wherever a tool found
  nothing; true totals and `limits`; descriptions that match behavior; `analyze_loop_timing` `entry`; `export_csv`
  paths, flattening, and inline mode; the G3 escape hatch in the instructions. **The conformance known-failures list
  is empty.**
- [x] **Pulled forward from Phase 3/4** while rewriting the same tools: CAN counters by field name (B3) and
  `can_health` on them (F3); swerve arrays (B5); logged brownout threshold and flag brownouts in all four power tools
  (E3), evidence-based battery health (G2), `generate_report` on the shared helpers (B8, F2 for the report);
  `profile_mechanism` roles by stem (B6); chooser ranking (B7); the loop timing discovery chain (B1); vision pose
  observation streams with latency and residuals (B4, except target streams and `Pose3d[]` sets); a shared
  `TimeScope` (`scope` on `analyze_swerve`, `analyze_loop_timing`, `predict_battery_health`); `find_condition`
  intervals, windows, and booleans (most of Phase 4 item 2); `export_csv` flattening (Phase 4 item 5).
- [x] **Phase 2 — Schema-driven structs and field paths.** Done: `log/struct/StructSchemas` decodes every struct
  by the log's own schemas (WPILib's `StructDescriptorDatabase` for parsing; compiled per-struct plans for nested
  structs, fixed arrays, enums, and bit-fields; exact size checks; WPILib and template fallbacks with `source`);
  `_derived` rotations; the 16 hand-written decoders and their registry removed; per-entry decode problems reported
  by every log-reading tool; `list_struct_types` per log; `get_entry_info` schema, source, leaf paths, and
  representative non-empty samples; enum columns in `export_csv`; the dropped final record (WPILib's iterator)
  fixed in both parsers. Field paths (`NumericSignal`: entry + `FieldPath`, `field` parameters, exact names win)
  in all seven numeric tools, with type errors that list numeric fields (D1, D2), angle unwrapping, circular
  statistics, and shortest-difference angle comparison; `list_entries` notes; guidance traps updated.
- [ ] **Phase 3 — Roles, one resolver, scope.** Done: `scope` and `windows` on the seven numeric tools (per-window
  differences, peaks, unwrapping, and condition search; `DataQuality.fromSegments`). Remaining:
  `SignalResolver`/`resolve_signals`, `resolved` in results, `compare_matches` (E4), vision target streams.
- [ ] **Phase 4 — Strings, intervals, alignment, quality.** Done: one text source (`TextEvents`: string lines,
  `string[]` alerts as appear/clear episodes, json string values) behind `search_strings`, `get_ds_timeline` (with
  `ALERT_RAISED` events), `can_health`, and `generate_report` (F1); compound `all`/`any` conditions in
  `find_condition` with held values and `abs_`/`ne` operators, whose intervals feed `windows`. Remaining:
  `align_entries`; lag search; `DataQuality` recalibration (G1).
- [ ] **Phase 5 — Consistency and docs.**

Golden checks on the review log: all 15 pass (timeline, flag brownouts, logged threshold, loop timing
percentiles, first-segment statistics, CAN TEC peak, per-module swerve speeds, ObservationScore, PoseObservation
decode by schema path, per-camera observation counts, pose wander with heading range and circular mean, 189
heading wraps, loop time over scope `enabled` and `segment:0`, the camera 3 alert as two appear/clear episodes, and
"disabled and stationary" as one compound condition).
