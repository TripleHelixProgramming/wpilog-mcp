# Robustness Review: Making wpilog-mcp Codebase-Independent

| | |
|---|---|
| **Date** | 2026-09-29 |
| **Builds tested** | Installed 0.8.2 release jar, and the working tree (HEAD `4b39641` plus uncommitted changes; shadow jar built 2026-08-28 15:34, after the last source edit). Both builds behaved identically except where noted as **WT** (working tree). |
| **Test log** | `akit_26-09-30_00-10-26.wpilog` — Team 2363 (Biocore), practice session, 2026-09-29 |
| **Paths** | `tools/` = `src/main/java/org/triplehelix/wpilogmcp/tools/`, `log/` = `src/main/java/org/triplehelix/wpilogmcp/log/`. Line numbers refer to the working tree at the time of review; class and method names are given so they can be found after edits. |

## 1. Goal

> An agent should be able to analyze a log correctly without ever seeing the code that produced it.

A WPILOG file carries most of what is needed to make that possible. Every entry has a declared type. Struct
layouts are embedded as `/.schema/struct:*` entries. AdvantageKit tags its entries with `{"source":"AdvantageKit"}`
metadata. WPILib, AdvantageKit, PhotonVision, Limelight, CTRE, and REV each use a small number of recognizable
conventions. What the file does **not** guarantee is any particular entry name.

Today many tools find their inputs by searching entry names for keywords tuned to the logs they were developed
against, decode structs with hardcoded layouts keyed by struct name, and report `success: true` when they find
nothing. On a log from a different codebase — or from the same codebase in a different situation (a practice
session instead of an FMS match, PhotonVision instead of Limelight, AdvantageKit's array-valued module states
instead of one entry per module) — they return plausible-looking wrong answers, or empty results that an agent
cannot distinguish from "no problem found".

This document lists every problem found in a full sweep of the tools against one such log, traces each to its
cause, and proposes changes that make the server derive its behavior from the log itself.

### Design rules

Every fix below is an instance of one of these rules. They are intended as acceptance criteria for every tool.

| # | Rule |
|---|---|
| R1 | **Decode from the log's own schema.** Struct layouts come from `/.schema/struct:*`. Hardcoded decoders may add convenience fields; they never define layout. |
| R2 | **Resolve inputs by type and schema first, names last.** Every tool that discovers an entry automatically also accepts an explicit override, and resolution is deterministic. |
| R3 | **Every result says what it used.** Name the entries, fields, and time windows each number came from, and the other candidates that were considered. |
| R4 | **Never succeed silently.** If a tool (or a section of one) cannot apply, it says `not_applicable` or `no_match`, what it looked for, and how to point it at the right data. No `NaN` and no silently missing sections inside `success: true`. |
| R5 | **Every numeric leaf is addressable.** Struct fields and array elements can be used anywhere a numeric entry can. |
| R6 | **Don't assume a match.** A log contains any number of enabled segments; "autonomous" may never be logged; thresholds come from the log when the log records them. |
| R7 | **Counts are totals.** Limited or paged outputs report the true total alongside what was returned. |
| R8 | **Descriptions match behavior.** A tool description never claims an output the code does not produce, and server guidance never forbids the only workaround for a documented limitation. |

## 2. Method

1. **A real task.** The review started from an actual debugging question: find the source of pose jitter after
   re-enabling four PhotonVision cameras. The analysis began with the MCP tools and moved to Python
   (`wpiutil.log.DataLogReader` + numpy) wherever the tools could not express it. The Python results are the
   ground truth used below ([Appendix A](#appendix-a-ground-truth-for-the-test-log)).
2. **A sweep of the tools.** 34 of the 45 tools were exercised: 27 in a scripted stdio sweep (33 calls) against
   both builds ([Appendix B](#appendix-b-test-calls)), and 7 more (`list_entries`, `search_entries`,
   `get_code_metadata`, `health_check`, `get_game_info`, `get_server_guide`, `analyze_cycles`) interactively
   against 0.8.2. Each result was checked against ground truth. Not exercised: the TBA and revlog tools (not
   applicable to this log), `get_types`, `find_peaks`, `list_loaded_logs`, and `moi_regression`. Statements
   about those come from the source only and are marked as such.
3. **Source review.** Each failure was traced to its cause in the working tree.

### Why this log is a good test

It is an ordinary log from a common software stack, but it differs from the logs the tools were tuned on in
exactly the ways other teams' logs will.

| Aspect | This log |
|---|---|
| Logging framework | AdvantageKit. Inputs under `/Drive/...` and `/Vision/...`; outputs under `/RealOutputs/...`; metadata under `/RealMetadata/...`; console captured as multi-line batches in `/RealOutputs/Console`. |
| Session | Practice, no FMS. Four enabled segments; the last runs to the end of the log. `/DriverStation/Autonomous` is logged once (`false`). |
| Loop timing | `/RealOutputs/LoggedRobot/FullCycleMS`, `UserCodeMS`, `LogPeriodicMS`, `GCTimeMS` |
| Swerve | `struct:SwerveModuleState[]` (4 elements) at `/RealOutputs/SwerveStates/{Setpoints,SetpointsOptimized,Measured}`; per-module IO at `/Drive/Module{FrontLeft,FrontRight,BackLeft,BackRight}/...` |
| Pose | `struct:Pose2d` at `/RealOutputs/Drive/Pose` |
| Vision | PhotonVision through the AdvantageKit vision template. Per camera under `/Vision/CameraN/`: `struct:PoseObservation[]`, `int64[] TagIds`, `LatencyMs`, `Connected`. Summaries as `struct:Pose3d[]`. |
| Power | `/SystemStats/BatteryVoltage`, `/SystemStats/BrownedOut` (true twice), `/SystemStats/BrownoutVoltage` = 6.75 V; regulator rails at `/SystemStats/{3v3,5v,6v}Rail/Voltage` |
| CAN | `/SystemStats/CANBus/*` for the roboRIO bus; CANivore buses as `/RealOutputs/CANBus/{CAN2,CANHD}/{Utilization,TEC,REC,BusOffCount,TxFullCount}` |
| Alerts | `string[]` entries: `/RealOutputs/Alerts/{errors,warnings,infos}`, `/RealOutputs/PhotonAlerts/*` |
| File | 116 MB, 26 minutes, truncated final write |

## 3. Scorecard

Issue IDs refer to [section 4](#4-issues).

| Tool | Result on this log | Issues |
|---|---|---|
| `list_entries`, `search_entries`, `get_code_metadata`, `health_check`, `get_game_info` | Correct | — |
| `read_entry` | Correct, including the custom `struct:PoseObservation[]` (checked byte-for-byte) — but only because its layout happens to match the hardcoded decoder | C1 |
| `get_statistics` | Correct on numeric entries, including time windows (matches ground truth p50/p95). Fails on every struct entry. | D1 |
| `find_condition` | Crossings correct; `transition_count` is capped by `limit`; no time range | G5 |
| `compare_entries` | Numeric: works. Struct: `success: true`, `rmse: NaN`, `max_difference: 0.0` | A6, D1 |
| `get_ds_timeline` | Enable/disable and brownout timing correct. **WT** adds the roboRIO brownout flag and text-event counts. Still ignores `string[]` alerts and the logged brownout threshold. | E3, F1 |
| `list_available_logs` | Correct; 25 KB response for 88 logs, no paging or filtering | G6 |
| `get_entry_info` | "Sample values" for a `Pose3d[]` entry were three empty arrays | G7 |
| `suggest_tools` | Reasonable, but recommended `analyze_vision`, which cannot read this vision data | B4 |
| `analyze_loop_timing` | **Fails:** "No loop time entry found" | B1 |
| `get_match_phases` | **Wrong:** one "enabled" phase, 40.2–840.1 s. Actual: four segments, the last 995.2 s to end of log. | E1, E2 |
| `power_analysis` | 0.8.2: **wrong entry** (`/SystemStats/5vRail/Voltage` → "HIGH brownout risk, 46,242 samples below threshold"). **WT: fixed** — the battery entry is now chosen. Threshold still hardcoded at 6.8 V. | B2, E3 |
| `predict_battery_health` | Brownout times correct; "health 0 / CRITICAL / URGENT: Replace battery immediately" from two dips shorter than 100 ms | E3, G2 |
| `generate_report` | Counted the boot-banner console batch as an error; 6.8 V hardcoded | F2, E3, B8 |
| `analyze_swerve` | **Wrong** average module speed (0.054 m/s; true mean \|speed\| 0.93 m/s); ~400 unrelated entry names under `other`; slip, sync, and drift sections silently absent | B5, A4 |
| `analyze_vision` | Only generic pose-jump detection on `/RealOutputs/Drive/Pose`. With `vision_prefix: "/Vision"` it returns `{target_acquisition: []}` and nothing else. | B4, A1, G4 |
| `analyze_can_bus` | `errors: []` at confidence "high", although CANHD's transmit error counter reached 85; `bus_name` ignored; `BusOffCount` reported as utilization; 0–1 fractions labeled `avg_percent` | B3 |
| `can_health` | "CONCERNING" from one console line; ignores the structured CAN counters | F3 |
| `analyze_replay_drift` | `divergent_count: 0` on a log that has no replay outputs | A3, B10 |
| `analyze_auto` | `success: true` with no content (the log has no autonomous period) | A2 |
| `profile_mechanism` | Stall events only; following-error analysis silently skipped | A5, B6 |
| `compare_matches` | **WT** adds per-log detail. Still whole-log min/max/mean, so `max` is the ~9.6 s boot loop in both logs. | E4 |
| `time_correlate`, `rate_of_change` on struct entries | Fail with "No numeric data" or "Not enough data". `find_peaks` and `detect_anomalies` filter values the same way (source only; not called on struct entries). | D1, D2 |
| `detect_anomalies` | Returns boot transients first; count capped; no time range; `spike_threshold` unused | G5, G4 |
| `search_strings` | **WT** rewrite is much better, but still misses `string[]` alerts such as "Vision camera 3 is disconnected." | F1 |
| `export_csv` | Rejected an absolute path in the agent's scratch directory and a bare filename; the error names directories that are not actually allowed | D3 |

## 4. Issues

Each issue lists what was **observed** (against ground truth), the **cause**, and the **fix**. Working-tree status
is noted where it differs from 0.8.2.

### A. Silent success

Rule R4. These are the most damaging failures for an agent, because the result looks like an answer.

**A1. `analyze_vision` returns an empty object when nothing matches.**
- *Observed:* `vision_prefix: "/Vision"`, window 362–394 s, `jump_threshold: 0.1` →
  `{"success": true, "target_acquisition": []}`. In that window `/RealOutputs/Drive/Pose` moved 60 cm while the
  robot sat disabled (Appendix A).
- *Cause:* `tools/FrcDomainTools.java`, `AnalyzeVisionTool`. The prefix is a case-sensitive `startsWith` applied to
  both vision and pose discovery (≈ line 1120), so the pose entry outside `/Vision` is excluded. `pose_jumps` is
  only added when non-empty (≈ line 678). There is no warning when nothing matched.
- *Fix:* report `vision_entries_used` and `pose_entry_used`; return `status: "no_match"` with the patterns and
  types searched and the nearest candidates; accept `pose_entry` independently of `vision_prefix`.

**A2. `analyze_auto` returns only a data-quality block.** The log has no autonomous period. The tool should say
so: `not_applicable: no autonomous period — /DriverStation/Autonomous has 1 sample (false)`.

**A3. `analyze_replay_drift` reports `divergent_count: 0` on a log that is not a replay.**
- *Cause:* `AnalyzeReplayDriftTool` (≈ lines 1548–1590) drops `/RealOutputs/X` entries that have no
  `/ReplayOutputs/X` counterpart without counting them. It also compares with exact `Objects.equals` and keeps
  only the first divergence per entry.
- *Fix:* report `pairs_compared`; if it is 0, return `not_applicable` ("this looks like a real-robot log; run on
  the `_sim` replay output"). Use a numeric tolerance for doubles; report the divergence count and maximum per
  entry.

**A4. `analyze_swerve` sections vanish.** The slip, sync, and drift sections are `null` or absent with no
explanation. Details in B5.

**A5. `profile_mechanism` silently skips following-error analysis** when it cannot pair a setpoint with a
measurement. It should list which roles (setpoint, measurement, current, temperature) it resolved and which it
did not.

**A6. `compare_entries` returns `success: true` with `rmse: NaN` and `max_difference: 0.0`** for two
`struct:ChassisSpeeds` entries.
- *Cause:* `tools/StatisticsTools.java`, `CompareEntriesTool` (≈ line 172) passes raw values to
  `ToolUtils.calculateRmseLinear` (≈ line 509), which returns `NaN` when it cannot extract numbers. The `NaN` is
  emitted as a result.
- *Fix:* fail with a type error that names the numeric fields (see D1, D2). Never emit `NaN` inside a success.

**A7. Counts equal the returned rows, not the total.** `detect_anomalies.anomaly_count` and
`find_condition.transition_count` equal `limit` whenever the output is truncated (G5). Rule R7.

### B. Input discovery by entry name

Rules R2 and R3. Most FRC-specific tools find their inputs by searching entry names for keywords and taking the
first hit. `LazyParsedLog` keeps entries in a `HashMap` (`log/LazyParsedLog.java` ≈ lines 90–92), so "first" is
effectively arbitrary when several entries match. Few tools accept an override, and none report which entry
they used.

**B1. `analyze_loop_timing` cannot find AdvantageKit's loop time.**
- *Observed:* "No loop time entry found. Look for entries containing 'LoopTime'". The log has
  `/RealOutputs/LoggedRobot/FullCycleMS` (59,217 samples) and `UserCodeMS`. Ground truth: 18.5 % of enabled loops
  exceed 25 ms; p95 53 ms; p99 92 ms.
- *Cause:* `AnalyzeLoopTimingTool` (≈ line 1642) accepts only names containing `looptime`, or both `loop` and
  `time`. There is no parameter to name the entry.
- *Fix:* add an `entry` parameter. Built-in candidates: `LoggedRobot/FullCycleMS` and `LoggedRobot/UserCodeMS`
  (report both — the full cycle includes logging), then names containing `looptime`, `loop_time`, or `cycletime`.
  Fallbacks: loop periods derived from `/Timestamp` or any 50 Hz periodic signal, and WPILib's overrun console
  messages. The **WT** text classifier already groups 1,299 "Loop time of Xs overrun" and 1,296
  "CommandScheduler loop overrun" messages in this log. Report the unit decision.

**B2. `power_analysis` chose a regulator rail as the battery.** 0.8.2 took the first name containing "voltage",
`/SystemStats/5vRail/Voltage`, and reported 46,242 samples below brownout threshold. **Fixed in WT** by
`ToolUtils.voltageEntryRank` (battery > input/bus > other > rails). Remaining: `generate_report` still uses its
own check (B8).

**B3. `analyze_can_bus` misses CANivore error counters and ignores `bus_name`.**
- *Observed:* `errors: []` at confidence "high", although `/RealOutputs/CANBus/CANHD/TEC` changed 68 times and
  peaked at 85 at 205.76 s (error-passive is 128, bus-off 255). *[Corrected in ROBUSTNESS_PLAN.md: the first excursion reached 85; the counter peaks at 215 at 650.86 s.]* The output is identical for `bus_name: "rio"` and
  `"CANHD"`. `BusOffCount` entries appear under `utilization` with `avg_percent: 0.0`. Utilization values are 0–1
  fractions (max 1.0 means 100 %) but are labeled `avg_percent` / `max_percent`.
- *Cause:* `AnalyzeCanBusTool` (≈ lines 1778–1830). `busName` is read and never used. Entries are classified by
  substring: `can` + (`util` | `bandwidth` | `busoff`) → utilization; `can` + (`error` | `fault` | `timeout`) →
  errors. `TEC` and `REC` — the standard names for CAN transmit/receive error counters, used by CTRE's CAN bus
  status — match neither. The bare substring `can` would also match unrelated names such as `Canandgyro` or
  `scan`.
- *Fix:* group entries per bus (the path segment after `CANBus/`, or `rio` for `/SystemStats/CANBus`) and honor
  `bus_name`. Classify by known field names: `Utilization`, `BusOffCount`, `TxFullCount`,
  `ReceiveErrorCount`/`REC`, `TransmitErrorCount`/`TEC`. Report counter deltas and maxima per enabled segment.
  Detect fraction versus percent from the value range.

**B4. `analyze_vision` only recognizes Limelight-style NetworkTables names.**
- *Observed:* `target_acquisition: []` on a log containing about 17,000 PhotonVision pose observations from four
  cameras.
- *Cause:* `AnalyzeVisionTool` (≈ line 592) looks for names containing `hastarget`, ending in `/tv` or `.tv`, or
  containing `targetvalid`. Pose entries must contain "pose", must not contain "target", and must have a type
  containing `Pose2d` or `Pose3d`. `struct:PoseObservation[]` fails that test; `Pose3d[]` passes but array values
  are skipped. Latency and the vision-versus-odometry comparison that the description promises are not
  implemented (G4).
- *Fix:* discover vision data by type and schema.
  - Any struct array whose schema contains a `Pose3d` or `Pose2d` field and a `timestamp` field is a
    pose-observation stream. Structs with yaw/pitch/area-style fields are target streams. `Pose3d[]` entries are
    pose sets. Group streams by parent path (camera).
  - Per stream, report: observation rate; fraction of loops with at least one observation; tag-count
    distribution if a `tagCount` field exists; latency from a sibling `Latency*` entry or from log time minus the
    embedded timestamp.
  - Given a resolved robot pose entry, report residuals between each observation and the robot pose sampled at
    the observation's embedded timestamp.
  - Keep the Limelight name patterns as an additional source.

**B5. `analyze_swerve` assumes one entry per module.**
- *Observed:* average module speed 0.054 m/s, against a true mean |speed| of 0.93 m/s (0.97 m/s while enabled).
  About 400 unrelated entry names appear under `swerve_entries.other`. `slip_threshold`, `sync_threshold_rad`,
  `vision_entry`, and `odometry_entry` produced no output.
- *Cause:* `AnalyzeSwerveTool` in `tools/RobotAnalysisTools.java`:
  - every entry that is not a swerve type is classified `other` (≈ line 341);
  - `extractSpeeds` averages the **signed** `speed_mps` over all array elements, pooling all modules. Measured
    and optimized module speeds are negative 38–52 % of the time, so the average cancels toward zero;
  - `extractSingleSpeed` returns `NaN` for array values, so slip comparison skips every sample (≈ line 473);
  - sync analysis needs at least two measured entries, but AdvantageKit logs one array;
  - drift discovery uses `findFirst` (≈ lines 592, 602), lands on `Odometry/Trajectory` (1 sample) and
    `Vision/Summary/RobotPosesAccepted` (an array), and gives up silently.
- *Fix:* treat a `SwerveModuleState[N]` entry as N modules. Label indices `module[0..N-1]`, and add FL/FR/BL/BR
  only as a stated assumption. Use |speed| for magnitude statistics. Pair setpoint and measured arrays by index.
  Drop `other`. Report every skipped section with its reason. Resolve odometry and vision through roles
  ([5.3](#53-signal-roles-and-one-resolver)).

**B6. `profile_mechanism` takes the first entry containing `velocity` or `current`** under the prefix
(`tools/FrcDomainTools.java` ≈ lines 744–748). With AdvantageKit module IO — `DriveVelocityRadPerSec`,
`TurnVelocityRadPerSec`, `DriveCurrentAmps`, `TurnCurrentAmps` under one prefix — the pairing is arbitrary.
*Fix:* accept `setpoint_entry`, `measurement_entry`, and `current_entry`; prefer entries that share a stem
(`Drive*` with `Drive*`); report candidates.

**B7. `analyze_auto` looks for a name containing `chooser`** (≈ line 985) to find the selected routine. This log
uses `/RealOutputs/AutoSelector/SelectedAutoMode`. *Fix:* also accept string entries whose names contain `auto`
together with `selected`, `mode`, or `routine`; report the entry used.

**B8. `generate_report` has its own discovery** for battery voltage (`tools/ExportTools.java` ≈ lines 329, 415)
and a hardcoded brownout threshold (≈ line 346), separate from `power_analysis`. *Fix:* use the shared resolver
([5.3](#53-signal-roles-and-one-resolver)).

**B9. Discovery is nondeterministic.** Any first-match over `log.entries()` depends on `HashMap` iteration order.
*Fix:* preserve declaration order (`LinkedHashMap`) and rank candidates explicitly.

**B10. Replay detection hardcodes `/RealOutputs/` → `/ReplayOutputs/`** (≈ lines 1549–1554). That is correct for
AdvantageKit, but on any other log the tool must report `not_applicable` (A3).

### C. Struct decoding

Rule R1. This is the largest correctness risk because its failures are silent.

**C1. Layouts are hardcoded by struct name; the log's schema is ignored.**
`log/subsystems/StructDecoderRegistry.java` registers 16 decoders (≈ lines 55–82), and `decodeStruct`
(≈ lines 112–125) picks one by struct name. The `/.schema/struct:*` entries are never parsed. `list_struct_types`
returns this fixed list, which reads as "these are the only structs a log can contain".

**C2. A same-named struct with a different layout is decoded wrongly, with no error.** The only guard is a
minimum length (e.g. `PoseObservationDecoder` expects 88 bytes, ≈ line 27). `StructDecoder.decodeArray` walks the
data at the hardcoded record size and silently drops leftover bytes, so an array of 96-byte records decodes into
the wrong number of garbage records. `PoseObservation` and `TargetObservation` are team-defined names (from the
AdvantageKit vision template, which teams modify), and `SwerveSample` belongs to Choreo, whose sample format is
versioned. This log decoded correctly only because Biocore's `PoseObservation` still matches the template.

**C3. Any other custom struct is invisible.** A team's own struct (an arm state, a shot record) or a vendor
struct comes back as a hex string (≤ 100 bytes) or `"<Type: N bytes>"`, and no tool can use it.

**C4. Output keys are ad hoc per decoder** — `x`, `rotation_rad` for `Pose2d`; flattened `pose_x`, `pose_qw` for
`PoseObservation`; added `yaw_deg` for `TargetObservation` — rather than the schema's field names. An agent
cannot predict them from the schema.

**C5. Enum labels are hardcoded** (`MEGATAG_1`, `MEGATAG_2`, `PHOTONVISION`; `PoseObservationDecoder` ≈ line 28)
instead of read from the schema's `enum {...}` clause.

*Fix:* [5.1](#51-schema-driven-struct-decoding).

### D. Numeric tools cannot reach struct fields

Rules R5 and R8.

**D1. Every numeric tool drops values that are not a `Number`.** `get_statistics`, `compare_entries`,
`detect_anomalies`, `find_peaks`, `rate_of_change`, and `time_correlate` filter on `instanceof Number`
(`tools/StatisticsTools.java` ≈ lines 91, 233, 256, 318, 394, 514, 517; `ToolUtils` ≈ lines 310, 493), and so does
`find_condition` (`tools/QueryTools.java` ≈ line 197). As a result, the central questions of pose, vision, and
swerve analysis cannot be asked:
- How far did `Drive/Pose.x` wander while the robot was disabled?
- Does the pose heading track the gyro?
- What is module 2's speed error?

More than anything else, this forced the jitter analysis out of the server.

**D2. Error messages hide the cause.** `rate_of_change` on a `struct:Pose2d` says "Not enough data";
`get_statistics` says "No numeric data in range"; `time_correlate` says "No numeric data". Each should say:
"entry is `struct:Pose2d`; numeric fields are `translation.x`, `translation.y`, `rotation.value`; pass `field`."

**D3. `export_csv` rejects reasonable paths and misstates the rule.**
- *Observed:* an absolute path under the agent's scratch directory and a bare filename were both rejected with
  "CSV files can only be written to the configured log directory or system temp directory".
- *Cause:* `tools/ExportTools.java` (≈ lines 37–39, 104–108). Only `{java.io.tmpdir}/wpilog-export` (on macOS
  `/var/folders/.../T/wpilog-export`), `-exportdir`, or `WPILOG_EXPORT_DIR` is allowed. The message names none of
  them.
- *Fix:* resolve bare and relative names inside the export directory; return the absolute written path; put the
  real allowed directory in the error. Consider an inline mode that returns rows in the response (capped), for
  agents that cannot read the export directory. Flatten struct fields and arrays into columns
  ([5.2](#52-field-paths-everywhere)).

### E. Assuming the log is an FMS match

Rule R6.

**E1. `get_match_phases` models one contiguous match.**
- *Observed:* one `enabled` phase, 40.2–840.1 s. Ground truth: enabled 40.207–359.162, 395.210–744.929,
  791.534–840.133, and 995.211 s to end of log (1588.27 s).
- *Cause:* `GetMatchPhasesTool` (`tools/RobotAnalysisTools.java` ≈ lines 96–108) records the first enable and the
  last disable after it. The span therefore includes the disabled gaps, and the final segment is lost: a disable
  has already been seen, so the fallback to end of log never runs.
- *Impact:* the server's `initialize` instructions tell agents to "Call get_match_phases before any time
  reasoning", so this wrong answer is the first thing an agent learns about any practice log (G3).
- *Fix:* return
  `segments: [{start, end, end_reason: "disabled" | "log_end", mode: "auto" | "teleop" | "test" | "unknown"}]`, plus
  aggregated match phases when the pattern matches a match (FMS attached, or one auto segment followed by one
  teleop segment within match timing).

**E2. Values logged only on change look like missing data.** `/DriverStation/Autonomous` has one sample (`false`
at 8.36 s). AdvantageKit logs on change, so it was `false` for the whole log. Tools must carry the last value
forward (sample-and-hold) and say "autonomous was never true", not "mode transitions not found".

**E3. The brownout threshold defaults to 6.8 V even when the log records it.** This log has
`/SystemStats/BrownoutVoltage` = 6.75 and `/SystemStats/BrownedOut`. `power_analysis`, `predict_battery_health`,
`get_ds_timeline` (voltage events), and `generate_report` (hardcoded, `tools/ExportTools.java` ≈ line 346) all use
6.8. The **WT** `get_ds_timeline` now reports the roboRIO flag separately, which is the right direction.
*Fix:* take the threshold from `BrownoutVoltage` when it is logged; otherwise infer the roboRIO generation or state
which default was assumed; use the logged flag for brownout events everywhere.

**E4. `compare_matches` compares whole-log min/max/mean.** In both logs compared, `max` is the ~9.6 s first loop at
boot, which dominates the comparison. *Fix:* accept a `scope` ([5.5](#55-time-model)); flag or exclude boot
transients; return percentiles. (**WT** adds `entry_found` and `sample_count`; the statistics' scope is unchanged.)

### F. Strings and alerts

**F1. Only entries of type `string` are searched.** `search_strings` (`tools/QueryTools.java` ≈ line 400),
`get_ds_timeline` text events (`tools/FrcDomainTools.java` ≈ line 404), `can_health`
(`tools/RobotAnalysisTools.java` ≈ line 1039), and `generate_report` (`tools/ExportTools.java` ≈ line 359) all skip
`string[]` and `json`. WPILib `Alert`s are `string[]` (logged by AdvantageKit at
`/RealOutputs/Alerts/{errors,warnings,infos}`, `/RealOutputs/PhotonAlerts/*`). Missed here:
"Vision camera 3 is disconnected." (737.68–783.80 s) and "PhotonCamera 'OV2311_TH_7' is disconnected."
*Fix:* search `string[]` element by element. Treat alert arrays as state, and report when each message appears and
disappears; that gives start, end, and duration. Index `json` string values too.

**F2. `generate_report` counts console batches, not lines.** Any batch containing `error`, `exception`, or `fault`
anywhere counts as one error (`tools/ExportTools.java` ≈ lines 358–372), so the boot banner counts. The preview
shows the batch's first 100 characters instead of the matching line. *Fix:* reuse the **WT** `classifyText` line
classifier.

**F3. `can_health` is keyword-based.** It reports "CONCERNING" from one enabled-time console line and ignores the
structured counters B3 would read. Keyword matching also inherits other programs' labels: one of the four "CAN
errors" it counted here is a NAND flash (`ubi_open_volume`) error that the robot's own kernel-log monitor tagged
`CAN_ERROR`. Consider merging `can_health` into `analyze_can_bus`, or having it read the same counters.

### G. Data quality, guidance, and ergonomics

**G1. The data-quality score is almost always 0.50 ("low").**
- *Observed:* `FullCycleMS` (59,217 samples at 47.8 Hz) scores 0.50; so does nearly every other result.
- *Cause:* `tools/DataQuality.java` (≈ lines 88–128). A gap is any interval longer than 5× the median; the gap
  penalty saturates at 20 gaps regardless of log length; jitter is the non-robust standard deviation of intervals,
  so one multi-second gap maxes it out. Any real log loses 0.3 + 0.2. AdvantageKit writes values only when they
  change and many signals are event-driven, so "gaps" are normal. For `FullCycleMS`, the gaps are the loop overruns
  themselves.
- *Impact:* a warning on every result teaches agents to ignore warnings, and `confidence_level: "low"` on good
  data conflicts with the guidance to state directly observed events as facts.
- *Fix:* [5.7](#57-data-quality).

**G2. Recommendations overreach.** `predict_battery_health` returns "URGENT: Replace battery immediately -
brownouts detected" (`tools/FrcDomainTools.java` ≈ line 2297), and "Average voltage low - battery may be
undercharged" for a 26-minute average that includes idle time. The brownouts are real (the roboRIO flag
confirms them), but their cause is a hypothesis, as the server's own guidance says. *Fix:* report the facts
(events, sag against current draw) and list candidate causes. Remove replacement advice, or require evidence
across several logs before giving it.

**G3. Server guidance conflicts with tool coverage.** The **WT** `initialize` instructions (1,987 bytes) say: "Never
compute statistics, correlations, rates, or durations by hand; use the tools. Call get_match_phases before any
time reasoning." Because of D1 and E1, an agent that follows these rules on this log either stops or reasons from
a wrong phase table. *Fix:* the second sentence is fine once E1 is fixed. For the first, add: "If a tool reports a
data type it cannot handle, export the data and compute externally, citing the export."

**G4. Descriptions promise outputs that don't exist.** `analyze_vision` claims pose discrepancy between vision and
odometry, and latency analysis. `detect_anomalies` claims spike/drop detection, but `spike_threshold` is declared
(`tools/StatisticsTools.java` ≈ line 219) and never read. *Fix:* a test that runs each tool on the fixture corpus
([section 6](#6-testing-for-codebase-independence)) and asserts that every output its description names appears
for at least one fixture.

**G5. Limits and ranges.**
- `detect_anomalies` has no `start_time`/`end_time` and returns anomalies in time order up to `limit`, so boot
  transients fill the result (FullCycleMS: 9,603 ms, 1,814 ms, 1,135 ms, ...). `anomaly_count` is the returned
  count.
- `find_condition` has no time range, returns only rising crossings, and `transition_count` is the returned count.
- *Fix:* time ranges on both; true totals; `sort: "time" | "severity"`; `find_condition` returns intervals
  ([5.5](#55-time-model)).

**G6. `list_available_logs` returns everything** (25.5 KB for 88 logs). Add `limit`/`offset`, `since`, `event`,
and a name filter.

**G7. `get_entry_info` sample values are not representative.** For a `Pose3d[]` entry it showed the first,
middle, and last samples, all empty arrays. Prefer non-empty samples; for struct entries, show the schema and the
numeric leaf fields.

### H. Missing primitives

The server cannot carry the analysis that prompted this review on its own, and the gaps are general, not specific
to vision. Each step of that analysis, and the primitive it needed:

| Analysis step | Primitive needed | Available today? |
|---|---|---|
| Decode four cameras' `PoseObservation[]` into rows | Flatten arrays of structs ([5.2](#52-field-paths-everywhere)) | `read_entry` pages only |
| Pose wander while the robot sat disabled | Field access (`Drive/Pose.x`) + statistics over disabled segments where speed ≈ 0 | No (D1, E1) |
| Pose heading against the gyro | Field access + angle-aware comparison of two entries | No |
| Each camera against the fused pose at capture time | Sample entry B at timestamps embedded in entry A (`PoseObservation.timestamp`) | No |
| Timing of pose steps (≈ 100 ms cadence) | Step detection on a field, with intervals between events | Partly (`analyze_vision` pose jumps above a threshold; no intervals between them) |
| Size of vision corrections while driving | Change in one entry minus the change predicted by another (odometry) | No *[0.9.0: `pose_corrections`]* |
| Hand-off to Python | Export flattened, aligned data | Path restrictions (D3); no struct flattening |

Reproducing a team's own filter scoring is rightly out of scope. Everything in the middle column is general
infrastructure.

## 5. Proposed changes

### 5.1 Schema-driven struct decoding

- Parse every `/.schema/struct:<Name>` entry at load time into a `StructSchema`: ordered fields with name, type,
  fixed array length, enum map, and bit-field width. Support WPILib's full grammar — `double arr[4]`,
  `enum {A=0, B=1} int32 kind`, `int32 flags:4` — and resolve nested struct types recursively against the other
  schemas.
- Decode only from the schema. Check that each record's length equals the schema size (or a multiple of it for
  arrays), and fail loudly on a mismatch.
- Emit the schema's own field names, nested
  (`{"translation": {"x": ..., "y": ...}, "rotation": {"value": ...}}`). Emit enum values with their labels.
- Keep today's decoders as **enrichers** that add convenience fields (degrees, yaw from a quaternion) **after**
  schema decoding, and only when the schema's field list matches the layout they expect. If a same-named struct
  doesn't match, skip enrichment and add a warning.
- `list_struct_types` becomes per-log ("structs in this log", from its schemas) plus "structs with enrichment".
- Fall back to a hardcoded layout only for a log with no schema entry, and say so in the result.

### 5.2 Field paths everywhere

- One `ValueExtractor`, used by every numeric tool, condition, alignment, and export.
- A `field` parameter, or a suffix on the entry name, such as `/RealOutputs/Drive/Pose.translation.x`. Array
  elements are addressed as `[2]`, or all elements as `[*]` (flattened rows with an `index` column). Short aliases
  derived from the schema (`x`, `y`, `heading`) are fine, provided the full path always works.
- `angle: true` (automatic for `Rotation2d.value` and yaw derived from a quaternion) unwraps angles before
  differencing and uses circular statistics.
- `list_entries` and `get_entry_info` show the numeric leaf paths of each struct entry, so an agent can discover
  them without seeing the code.

### 5.3 Signal roles and one resolver

Define the roles that tools consume, and one resolver that maps roles to entries for each log.

| Role | Resolution, in order |
|---|---|
| `robot_enabled`, `autonomous`, `test`, `fms_attached` | AdvantageKit `/DriverStation/*`; WPILib `DS:*`; NetworkTables `FMSInfo` |
| `battery_voltage` | `voltageEntryRank` (already in **WT**) |
| `brownout_flag`, `brownout_threshold` | `/SystemStats/BrownedOut`, `/SystemStats/BrownoutVoltage`; otherwise a default, stated |
| `loop_time_full`, `loop_time_user` | `LoggedRobot/FullCycleMS`, `LoggedRobot/UserCodeMS`, `*LoopTime*`; derived from `/Timestamp` |
| `robot_pose` | `Pose2d` entries, ranked: exclude single-sample and trajectory entries; prefer the highest-rate entry under a drive/odometry path |
| `module_states_measured`, `module_states_setpoint` | `SwerveModuleState[]` entries by name (`Measured`, `Setpoint*`); otherwise per-module entries |
| `chassis_speeds_measured`, `chassis_speeds_setpoint` | `ChassisSpeeds` entries |
| `gyro_yaw` | `Rotation2d` or double entries with `Yaw` under a gyro-like path (`Gyro`, `Pigeon`, `NavX`, `Canandgyro`) |
| `vision_pose_observations`, `vision_targets` | Schema-based (B4), plus Limelight/NetworkTables names |
| `can_bus_counters[bus]` | B3 |
| `console_text`, `alerts` | `string` console entries; `string[]` alert entries |

Resolution order: explicit tool parameter, then type and schema, then metadata (e.g. AdvantageKit source), then
ranked names. Ties are broken by declaration order. Every result includes
`resolved: {role: {entry, basis, candidates}}` for the roles it used. Add a `resolve_signals` tool (or a per-log
section in `get_server_guide`) so an agent can check the mapping once and override it.

### 5.4 Result contract

Adopt this for every tool, as a `ResponseBuilder` change plus a test:
- `status`: `ok` | `partial` | `not_applicable` | `no_match`. Keep `success` for compatibility, true only for `ok`
  or `partial`.
- `inputs`: the entries (with fields) and time windows used.
- `skipped`: each section not produced, with a reason.
- Every limited list has `total` and `returned`.
- No `NaN` or `Infinity` in the numbers of a successful result.
- Type errors name the actual type and the addressable fields.

### 5.5 Time model

- `get_match_phases` returns enabled and disabled segments (E1) and, when applicable, match phases.
- A shared `scope` parameter on the statistical tools: `all` | `enabled` | `disabled` | `auto` | `teleop` |
  `segment:<i>`, or explicit `windows: [[t0, t1], ...]`.
- `find_condition` returns intervals (`start`, `end`, `duration`), total time, and fraction of scope. It supports
  compound conditions across entries (`all` / `any` of `{entry, field, op, threshold}`) and carries change-only
  values forward. Its intervals can be passed as `windows` to other tools. Example from this analysis:
  `robot_enabled == false AND |chassis_speed| < 0.05`.

### 5.6 Alignment

- `align_entries` (or `sample_at`): the values of entry B at entry A's timestamps, or at a timestamp **field**
  inside A (e.g. `PoseObservation.timestamp`). Interpolation `linear` | `previous` | `nearest`, angle-aware. Output
  rows, or feed the result into `compare_entries`, `time_correlate`, or `get_statistics` (e.g. statistics of A − B).
- `compare_entries` and `time_correlate` accept `field` on either side, plus a lag search.
- Optional generic pose helpers: the difference of two pose streams, in field frame or in the first pose's frame;
  and step detection on a pose, compensated for odometry given a chassis-speeds entry.

### 5.7 Data quality

- Classify each signal's sampling pattern as periodic, change-only, or event-driven, from its timing and type.
- Exempt change-only and event signals from gap penalties.
- Normalize gaps by duration, and count them only within the analyzed scope.
- Compute jitter with the median absolute deviation.
- Return a `reasons` list for any penalty, and derive `confidence_level` from it.

### 5.8 Guidance consistency

- Update the `initialize` instructions with the escape hatch for unsupported types (G3).
- Extend the existing test that checks tool names referenced in the guidance: also check that each description's
  claimed outputs appear in fixture results (G4).

## 6. Testing for codebase independence

**Fixture corpus.** Small logs generated in tests with WPILib's `DataLogWriter`, each representing one convention,
plus a few real logs for golden values.

| Fixture | Exercises |
|---|---|
| AdvantageKit template style, FMS match | Baseline |
| AdvantageKit practice session: several enables, final segment open, autonomous never true | E1, E2 |
| Plain WPILib `DataLogManager` (`DS:` entries, `NT:/...` names, no `/RealOutputs`) | B*, E1 |
| Per-module swerve entries versus `SwerveModuleState[]` | B5 |
| Limelight via NetworkTables versus PhotonVision `PoseObservation[]` versus raw `Pose3d` | B4 |
| Custom struct with a built-in's name but a different layout (e.g. `PoseObservation` with an extra field) | C2 |
| Unknown custom struct with a nested struct, fixed-size array, enum, and bit-field | C1–C5 |
| roboRIO 2 (`BrownoutVoltage` 6.3), and roboRIO 1 without that entry | E3 |
| CANivore counters (`TEC`, `REC`, `BusOffCount`) with a TEC excursion | B3 |
| Alerts as `string[]`; batched console | F1, F2 |
| AdvantageKit `_sim` replay log, with and without divergence | A3 |
| Truncated file | Loader |

**Conformance test.** For every tool on every fixture:
1. `status` is one of `ok`, `partial`, `not_applicable`, `no_match`. If `ok`, the content is non-empty, and
   `inputs` and `resolved` are present.
2. A successful result contains no `NaN` or `Infinity`.
3. Every limited list has `total` ≥ `returned`.
4. Determinism: two runs with the entry declaration order shuffled resolve the same entries.

**Golden values.** Assert the Appendix A values against the real log. Keep that log outside the repository, or
behind an opt-in flag, if its size is a concern.

## 7. Suggested order of work

| Priority | Work | Issues |
|---|---|---|
| P0 | Result contract: `status`, `inputs`/`resolved`, `skipped`, totals, no `NaN` | A1–A7 |
| P0 | Schema-driven struct decoding with size validation | C1–C5 |
| P1 | Field paths in all numeric tools; better type errors | D1, D2 |
| P1 | Enabled segments in `get_match_phases`; carry change-only values forward | E1, E2, G3 |
| P1 | Role resolver with overrides, applied to loop timing, vision, swerve, CAN, power, and the report | B1–B10, E3 |
| P2 | Interval conditions, `scope`, alignment, export flattening and path fixes | D3, E4, G5, H |
| P2 | Data-quality recalibration | G1 |
| P2 | Strings: `string[]` and `json`; line classification everywhere | F1–F3 |
| P3 | Guidance and description consistency tests; paging; representative samples; recommendation wording | G2, G4, G6, G7 |

## Appendix A. Ground truth for the test log

`akit_26-09-30_00-10-26.wpilog`, computed independently with `wpiutil.log.DataLogReader` and numpy. These are
suitable as golden values.

**Timeline**
- Log span 8.359–1588.273 s (truncated final write).
- Enabled: 40.207–359.162, 395.210–744.929, 791.534–840.133, 995.211 s–end. `/DriverStation/Autonomous`: one
  sample, `false`.
- `/SystemStats/BrownedOut` true: 655.433–655.577 s and 708.435–708.473 s. `/SystemStats/BrownoutVoltage` = 6.75.
  Minimum battery voltage 6.618 V.
- Camera 3 disconnected (`/Vision/Camera3/Connected` false; alert "Vision camera 3 is disconnected."):
  737.68–783.86 s.

**Loop timing** (`/RealOutputs/LoggedRobot/FullCycleMS`)
- First sample 9,603.5 ms at 8.359 s (boot).
- Enabled, t > 30 s: p50 17.6, p90 38.6, p95 53.1, p99 91.9 ms; 18.5 % of loops > 25 ms.
- Disabled, t > 30 s: p50 16.0, p95 39.4 ms; 10.0 % > 25 ms.
- First enabled segment only (40.2–359.1 s, via `get_statistics`): n = 11,949, median 18.03, p95 51.39 ms. The
  tool matches.

**CAN**
- `/RealOutputs/CANBus/CANHD/TEC`: 68 samples, maximum 85 at 205.76 s. *[Corrected: maximum 215 at 650.86 s; 85 was the first of 13 excursions (ROBUSTNESS_PLAN.md, Findings).]*
- `/SystemStats/CANBus/{ReceiveErrorCount,TransmitErrorCount,OffCount,TxFullCount}`: a single sample each, all 0.

**Swerve** (`/RealOutputs/SwerveStates/Measured`, 46,839 samples × 4 modules)
- Signed mean over all elements: 0.054 m/s (what `analyze_swerve` reports).
- Mean |speed|: 0.93 m/s over the whole log, 0.97 m/s while enabled. Per module while enabled: index 0 0.971,
  index 1 0.950, index 2 0.987, index 3 0.975 m/s. Maximum |speed| 4.75 m/s. 38–52 % of samples negative, per
  module.

**Vision**
- Logged `PoseObservation`s: camera 0 4,943; camera 1 5,433; camera 2 4,132; camera 3 2,377 (16,885 total). All have
  `tagCount` = 1. The robot logged camera inputs every other loop, so this is about 45 % of frames.
- `/RealOutputs/Vision/Summary/ObservationScore`: n = 5,741, median 0.7205, p5 0.6794, p95 1.0. The tool matches.
- `/Vision/Camera3/LatencyMs`: median 61.2 ms, maximum 5,870,507 ms (a single glitch at reconnect, which makes the
  mean meaningless).
- Decode check: `/Vision/Camera3/PoseObservations` at 370.047375 s → timestamp 369.970913, pose
  (3.1805, 4.5455, 0.3667), ambiguity 0.0, tagCount 1, averageTagDistance 2.8576, type PHOTONVISION. The tool
  matches.

**Pose while disabled and stationary, 362–394 s**
- `/RealOutputs/Drive/Pose`: x range 29.0 cm, y range 59.9 cm, heading range 7.33°.
- `/Drive/Gyro/YawPosition` range over the same window: 0.034°.
- 250 loops with a pose step larger than 1 cm; steps arrive about every 80–110 ms.

## Appendix B. Test calls

The scripted sweep, run against both builds. Each call was made over stdio (`initialize`,
`notifications/initialized`, `tools/list`, then one `tools/call` per row) with `path` set to the test log, and the
parsed `result.content[0].text` was saved for comparison between builds and against Appendix A. The interactive
calls listed in [section 2](#2-method) are not repeated here.

| Tool | Arguments (besides `path`) |
|---|---|
| `analyze_loop_timing` | — |
| `get_match_phases` | — |
| `analyze_vision` | — |
| `analyze_vision` | `vision_prefix: "/Vision"`, `start_time: 362`, `end_time: 394`, `jump_threshold: 0.1` |
| `analyze_swerve` | — |
| `power_analysis` | — |
| `analyze_can_bus` | — ; and `bus_name: "CANHD"` |
| `can_health` | — |
| `predict_battery_health` | — |
| `generate_report` | — |
| `analyze_replay_drift` | — |
| `analyze_auto` | — |
| `profile_mechanism` | `mechanism_name: "/Drive/ModuleFrontLeft"` |
| `compare_matches` | `compare_path: akit_26-09-29_23-11-20.wpilog`, `name: /RealOutputs/LoggedRobot/FullCycleMS` |
| `get_statistics` | `/RealOutputs/Drive/Pose` (362–394); `/RealOutputs/Vision/Summary/ObservationScore`; `/RealOutputs/LoggedRobot/FullCycleMS`; `/Vision/Camera3/LatencyMs` |
| `time_correlate` | `/RealOutputs/Drive/Pose` vs `/Drive/Gyro/YawPosition` (362–394) |
| `rate_of_change` | `/RealOutputs/Drive/Pose` (362–363) |
| `compare_entries` | `/RealOutputs/SwerveChassisSpeeds/Setpoints` vs `.../Measured` |
| `search_strings` | `pattern: "disconnected"` |
| `get_ds_timeline` | — |
| `detect_anomalies` | `/RealOutputs/LoggedRobot/FullCycleMS`, `limit: 5` |
| `find_condition` | `/RealOutputs/LoggedRobot/FullCycleMS`, `gt 25`, `limit: 3` |
| `export_csv` | `/RealOutputs/Drive/Pose` to an absolute scratch path; and to a bare filename |
| `get_entry_info` | `/RealOutputs/Vision/Summary/RobotPosesAccepted` |
| `read_entry` | `/Vision/Camera3/PoseObservations`, `start_time: 370`, `limit: 1` |
| `list_struct_types`, `suggest_tools`, `list_available_logs` | — |
