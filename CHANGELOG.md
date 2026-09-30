# Changelog

All notable changes to wpilog-mcp will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Robustness work from [doc/ROBUSTNESS_REVIEW.md](doc/ROBUSTNESS_REVIEW.md), following [doc/ROBUSTNESS_PLAN.md](doc/ROBUSTNESS_PLAN.md): tools derive their behavior from the log itself and never report success when they found nothing to analyze.

### Changed (breaking)
- **Result contract on every tool** — Every result now has `status`: `ok`, `partial` (some sections skipped, listed in `skipped` with reasons), `not_applicable` (the tool does not apply to this log), `no_match` (none of the entries it analyzes were found; `looked_for` says what was searched and `hint` how to point it at the data), or `error`. `success` is true only for `ok` and `partial`, so a tool that found nothing is no longer a success. Enforced once in `ToolBase.execute`, so it holds for every tool. Lists cut by a limit report `limits.<key>: {total, returned, limit}`.
- **No `NaN` or `Infinity` in results** — Values that cannot be computed are emitted as `null` (a bare `NaN` is not valid JSON), named in a warning and in `_metadata.non_finite_fields`. `time_correlate` reports an undefined correlation (a constant entry) as `correlation: null` with a warning.
- **`get_match_phases` returns segments** — Instead of one "enabled" span from the first enable to the last disable (which covered disabled gaps and lost a final segment running to the end of the log), the tool returns `segments`: every interval of constant state with `mode` and `end_reason` (`disabled`, `mode_change`, `log_end`, ...), plus `matches` (FMS matches, or an autonomous segment followed at once by teleop, with `basis`, `complete`, and `expected_timing`) and `season` (from the log's own clock, then build date, then file name). DriverStation values logged only on change hold until the next sample, so a practice log with `Autonomous` logged once as false reports four teleop segments and "Autonomous was never true" instead of "mode transitions not found". `phases` repeats the first match; with several enabled segments and no match it is empty. Returns `no_match` without DriverStation data.
- **`get_ds_timeline` enable/mode events** come from the same timeline: one DriverStation entry per role (a log with both `DS:` and `/DriverStation/` entries no longer gets doubled events), TELEOP_START at every teleop enable, and a warning when the log has no enabled entry.
- **`analyze_auto`** lists every autonomous period (`auto_periods`), takes the selected routine's value at each period's start from a ranked chooser entry (`.../active`, then names like `SelectedAutoMode`), reports skipped path-following analysis, and returns `not_applicable` with the reason when the log has no autonomous period (previously a success containing only a data-quality block).
- **`analyze_can_bus` reads CAN counters by field name, per bus** — WPILib `CANStatus` (`/SystemStats/CANBus`, bus `rio`) and CTRE CANivore status (`.../CANBus/<name>/{Utilization,TEC,REC,BusOffCount,TxFullCount}`); `bus_name` now selects a bus (it was ignored). TEC/REC are reported as levels (maximum, when, excursions above the error-passive threshold of 128); bus-off and TX-full counts as increases, overall and while enabled; 0–1 utilization fractions are converted to percent. Previously TEC/REC were missed entirely, `BusOffCount` was reported as utilization, fractions were labeled percent, and entries were found by the substring "can" (matching Canandgyro and scan). Returns `no_match` when the log has no CAN data.
- **`can_health`** matches "CAN" as a word (not "cannot", "scan", or "Canandgyro"; "default" is not a fault), classifies each line by the DriverStation timeline (errors before any DriverStation data are `state_unknown`, no longer counted as enabled), includes the bus counters, and bases `health_assessment` on both (`assessment_basis` says which fact decided it; `UNKNOWN` without DriverStation data).
- **`analyze_swerve` understands AdvantageKit's module arrays** — A `struct:SwerveModuleState[]` entry is N modules (`module[0..N-1]`; FL/FR/BL/BR only as a stated assumption), speeds are reported as magnitudes (`mean_abs_speed_mps`; the old signed average cancelled toward zero, 0.054 m/s instead of 0.97 m/s on the review log), setpoints pair by index (optimized setpoints preferred), and each module reports speed tracking and steer error (modulo 180°). `module_sync` is now the largest steer error against setpoints. New `measured_entry`, `setpoint_entry`, and `scope` parameters. Odometry drift discovery is deterministic and skips one-sample and array poses; every section that cannot be produced is listed in `skipped`. `swerve_entries` (with its ~400-name `other` list) and `module_analysis` are removed. Returns `no_match` without module states.
- **`analyze_replay_drift`** compares arrays and structs element by element (every array entry used to be "divergent", compared by identity), uses a numeric tolerance (`relative_tolerance`), counts `pairs_compared`, `samples_compared`, and the entries present on only one side, reports per divergent entry the first divergence, counts, and largest difference, and returns `not_applicable` on a log without `/ReplayOutputs/` (a real-robot log used to report "0 divergences").
- **Brownout threshold from the log** — `power_analysis`, `predict_battery_health`, `get_ds_timeline`, and `generate_report` use the roboRIO's own `BrownoutVoltage` setting when the log records it (6.75 V on the review log), else 6.8 V stated as an assumption (`brownout_threshold_basis`); the argument still overrides. `brownout_threshold` no longer has a fixed schema default. `power_analysis` and `generate_report` report the roboRIO's logged brownouts (`rio_brownouts`, with durations).
- **`predict_battery_health` reports evidence, not advice** — Brownouts come from the roboRIO flag when logged (else threshold crossings, with the basis stated); voltage statistics default to enabled time (`scope`); the voltage entry is the one `power_analysis` chooses; a new `load_line` regresses voltage on total current for the effective source resistance; `observations` (also in `recommendations`) state what the evidence is consistent with and what would distinguish the causes. "URGENT: Replace battery immediately" and the other replacement advice are gone. The heuristic health score remains, with its basis stated. Returns `no_match` without a voltage entry (was an error).
- **`generate_report`** uses the shared voltage choice and brownout threshold (it had its own), classifies console text per line like `get_ds_timeline` (a boot banner batch no longer counts as one error; samples show the matching line), and adds the DriverStation timeline, the three largest current peaks (the description promised them), and top error messages.
- **`get_code_metadata`** matches keys by leaf name (lowest entry id; a warning when sources disagree), names each value's `sources`, and returns `no_match` when there is no metadata (it returned an empty object).
- **`analyze_vision` reads pose observation streams** — Struct arrays whose records hold a timestamp and a pose (the AdvantageKit vision template's `/Vision/Camera<N>/PoseObservations`, from PhotonVision or Limelight) are found by content, one stream per camera, with observation counts and rate, tag-count and ambiguity distributions, latency (log time minus the observation's timestamp), and residuals against the robot pose at the observation's timestamp. `vision_prefix` limits vision entries only (case-insensitive) — it used to hide the robot pose, so a prefixed call returned `{target_acquisition: []}` and nothing else. New `pose_entry`; `pose_jumps` is always present with a true total; unreadable poses are counted, never treated as zero movement; `no_match` when there is no vision data. The description's odometry-discrepancy and latency claims are now implemented. `target_streams` report structs with yaw/pitch fields (such as `TargetObservation`: yaw, pitch, area, confidence, object ids), `pose_sets` report `Pose3d[]`/`Pose2d[]` entries (how often non-empty, poses per record), and observation streams add `fraction_with_observations` and `logged_latency` from a sibling latency entry.
- **`profile_mechanism` resolves roles by stem** — Setpoint, measurement, velocity, current, and temperature entries are grouped by the stem before the role word (`DriveVelocity` and `TurnVelocity` are different mechanisms; `other_stems` lists the rest), every role can be passed explicitly, `roles` names the entries used, and each section without its entries is in `skipped`. Following error compares each measurement with the setpoint in force (held), within the time window (RMSE ignored the window); step response measures settling into 5% of the step and overshoot as a percent of the step (overshoot used to measure the step itself); stalls still in progress at the end are reported (`open_at_end`); `stall_velocity_threshold` is a parameter; the promised temperature profile exists. `no_match` when nothing matches.
- **True totals and time windows on the statistics tools** (A7, G5) — `detect_anomalies` counts every anomaly (`anomaly_count` equaled `limit` when truncated), accepts `start_time`/`end_time` and `sort` (`time` or `severity`), implements `spike_threshold` (declared but unused; now sample-to-sample jumps in the entry's units), and reports its IQR `bounds`. `find_condition` reports true totals, accepts a time window and boolean entries, and returns `intervals` (start, end, duration, `end_reason`) with `total_true_sec` and `fraction_of_window`. `find_peaks` reports `maxima_count`/`minima_count`; `rate_of_change`, `read_entry`, and `search_strings` report `limits` for their lists. Numeric tools name the entry type in their errors instead of "No numeric data" and read boolean entries as 1/0.
- **`compare_entries`** rejects non-numeric entries (a struct pair returned success with `rmse: NaN`), reports `samples_compared` and the reference entry, and makes non-overlapping entries an error naming both spans.
- **Smaller fixes** — `moi_regression` validates `kt`, `gear_ratio`, `motor_count`, and `wheel_radius` (missing `kt` was an internal error) and reports an undefined R² as null with a warning; `get_server_guide` rejects an unknown category (it returned an empty list); `suggest_tools` returns `no_match` when nothing fits and orders ties by name; `analyze_cycles` bounds an incomplete idle period by `end_time` and counts only windowed samples.
- **`analyze_loop_timing` finds AdvantageKit's loop time** (B1) — New `entry` parameter; discovery tries `LoggedRobot/FullCycleMS` (with `LoggedRobot/UserCodeMS` reported alongside), then names containing `looptime`/`loop_time`/`cycletime`, then periods derived from `/Timestamp` (it used to require "loop" and "time" in the name and failed on every AdvantageKit log). The unit comes from the argument, the name (`...MS`), or the median, with the basis reported; the multi-second boot cycle is excluded and reported; `scope`, median/p90, `percent_over_threshold`, and the maximum's time are new. `no_match` (counting WPILib overrun messages) instead of an error when there is no loop timing.
- **`export_csv` is a usable escape hatch** (D3) — Bare and relative names resolve inside the export directory (they used to resolve against the server's working directory and be refused), `output_path` is optional, the absolute path written and the `export_directory` are returned, and a refused path's error names the real export directory (it named two directories that were not allowed). Every value is flattened into aligned columns (struct fields as dot paths, arrays one row per element with `index`; `Pose2d[]` rows used to be shifted a column and `double[]` fields written as `[D@...`). New `inline` mode returns rows in the response.
- **Server instructions** — Two rules added within the 2 KB budget: a `no_match` result means the data was not found (not that nothing is wrong), and when no tool can read a data type, export it with `export_csv`, compute externally, and cite the export (review G3).
- **Analysis principles** — New trap: a `no_match` or `not_applicable` result is not "no problem found". The window and array traps describe the tools as they now behave.
- **Declaration order** — Entries iterate in the order the robot program declared them (`LinkedHashMap` in both parsers and the disk cache), and a name started twice with the same type is one entry holding all its records (the second Start used to discard the first's records).
- **`health_check`** no longer returns `status: "OK"` (the contract's `status` is `ok`); **`get_tba_status`** reports `configuration` (`configured`/`not_configured`) instead of `status`.
- **Structs decode from the log's own schemas** (C1–C5) — Struct values are decoded by the schema each log records for its type (`/.schema/struct:<Name>`, including `NT:/.schema/struct:` copies), parsed by WPILib's `StructDescriptorDatabase`: any struct decodes — a team's own, a vendor's, nested structs, fixed-size arrays, enums, and bit-fields — and a team that edited a template struct gets its own layout (the review log's `PoseObservation` decoded by the built-in layout would have been wrong for any team that added a field). The 16 hand-written decoders are gone. **Output keys follow the schema** (clean break): a `Pose2d` is `{"translation": {"x", "y"}, "rotation": {"value", "_derived": {"degrees"}}}` instead of `{x, y, rotation_rad, rotation_deg}`; `SwerveModuleState` is `{speed, angle: {value, _derived}}`; enum fields are `{"value", "label"}` (they were bare label strings); ints are integers and floats stay single precision. `Rotation2d` and `Rotation3d` values carry `_derived` degrees (and roll/pitch/yaw) only when the log's schema for them is WPILib's. WPILib's schemas and three templates (`PoseObservation`, `TargetObservation`, `SwerveSample`) are used only for types a log records no schema for, and results say so (`source`: `logged`, `wpilib`, `assumed`, `missing`). Record size is checked exactly (an array must be a whole number of records): a record that does not fit is not decoded instead of being read with a wrong layout.
- **Undecodable records are reported, never silent** (R4) — Every tool that reads an entry some of whose records could not be decoded adds a warning (`N of M records could not be decoded (reason); results use the rest`) and `_metadata.decode_problems`; `read_entry` on an entry none of whose records decode is an error that says why (it returned an empty success).
- **`list_struct_types` describes a log** — With `path`, every struct type the log records or uses: `source`, `valid`/`error`, `size_bytes`, `schema`, `schema_entry`, `fields` (arrays, bit widths, enums), `numeric_leaf_paths`, and the entries using it, with warnings for types that cannot be decoded or rely on an assumed layout. Without `path`, the fallback schemas. It used to return a fixed list of 16 names grouped by category.
- **`get_entry_info`** adds, for struct entries, the schema, its source, fields, and `numeric_leaf_paths` (`.translation.x`, `[*].tagCount`); `decode_problem`; `non_empty_sample_count`; and representative samples taken from non-empty values (the first, middle, and last samples of a vision observation entry were often empty arrays). Long arrays in samples are cut, with `value_length`.
- **`export_csv`** writes an enum field as two columns, `field` and `field.label`.
- **Numeric tools read struct fields and array elements** (D1, D2) — `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, and `find_condition` accept a field path appended to the entry name (`/RealOutputs/Drive/Pose.translation.x`, `/PowerDistribution/ChannelCurrent[3]`, `/Vision/Camera0/PoseObservations[0].tagCount`) or passed as `field` (`field1`/`field2`); an exact entry name always wins, since names can contain dots. `get_statistics` also pools every element with `[*]`. Enum fields read as their number and booleans as 1/0. Asking for a struct or array entry without a path is an error that lists its numeric fields (it said "No numeric data" or "Not enough data"), and so is a path that leads to no number. Known angles (`Rotation2d` values and derived degrees, `Rotation3d` roll/pitch/yaw, `SwerveSample` heading) are unwrapped by the statistics, peak, anomaly, derivative, and correlation tools — `get_statistics` adds `angle` with the number of wraps and the circular mean and standard deviation — and `compare_entries` compares two angles by their shortest difference. Results name the signal (`name` is entry plus path) and record the path under `inputs.fields`. On the review log, pose wander while disabled (x 29.0 cm, y 59.9 cm, heading 7.33°) is now one call each; the heading wraps 189 times over the match.
- **Scopes and windows on the numeric tools** (Phase 3) — `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, and `find_condition` take `scope` (`enabled`, `disabled`, `auto`, `teleop`, `test`, `segment:<i>`, from the same timeline as `get_match_phases`) and `windows` (a list of `{start, end}`, half-open, e.g. `find_condition`'s own `intervals`), intersected with `start_time`/`end_time`; `find_peaks` and `compare_entries` gained a time range at all. Derivatives, spikes, peaks, and angle unwrapping stay within each window, `find_condition` searches each window on its own, and `data_quality` no longer counts the time between windows as a gap. `find_condition` reports `window_sec` (the time searched after the entry's first sample, the denominator of `fraction_of_window`). Results record `inputs.scope`. On the review log, `get_statistics` of `FullCycleMS` with `scope: enabled` matches numpy over all four enabled segments (n = 48596, median 17.60 ms, p95 53.11 ms).
- **`list_available_logs` pages and filters** (G6) — `offset`/`limit` (default 50), `name`, `event`, `match_type` (`q`/`qm`, `sf`, `f`, ...), and `since` filters, with `log_count` (matching), `total_logs`, `has_more`, and `limits.logs`; only the listed page is TBA-enriched, and ties in the newest-first order are broken by path. It returned every log at once (25.5 KB for 88 logs).
- **New `resolve_signals`; one resolver** (§5.3) — `resolve_signals` shows which entry plays each of 18 roles in a log (DriverStation state, battery voltage, brownout flag and threshold, loop time, robot pose, swerve module states, chassis speeds, gyro yaw, vision observations and targets, CAN buses, console text, alerts) with the basis, ranked candidates, `ambiguous` when another candidate ranked as well, and the tools that use it. `SignalResolver` runs the same discovery the tools do, so the mapping is the tools' own choice (`analyze_vision` now takes its robot pose from it); `power_analysis` records its voltage and flag entries under `inputs.entries` like the other tools.
- **`compare_matches` compares the same phase, robustly** (E4) — `scope` is resolved in each log's own timeline (`enabled`, `teleop`, `segment:<i>`), the name may carry a field path, and each log reports percentiles (median, p5, p25, p75, p95), `std_dev`, when its min and max occurred, and its own `data_quality`; `differences` gives second-minus-first mean, median, and p95 without a significance test (samples are autocorrelated; two logs are two samples). An extreme in the first 5 s of a log is flagged as a likely boot transient — on the review log, `FullCycleMS`'s whole-log max is the 9.6 s boot loop. A log without the signal makes the result `partial`; neither log, `no_match`. It used to compare whole-log min/max/mean only, with data quality from the first log.
- **`detect_anomalies` reports the cadence of steps** — With `spike_threshold`, `spike_interval_sec` summarizes the time between consecutive spikes within a window (n, min, median, p95, max): for example, how often vision corrections step a pose field (review section H).
- **New `align_entries`; lag search** (§5.6) — `align_entries` samples 1–8 numeric signals (field paths allowed) at common times: every record of an entry, or the timestamps stored inside one (`time_field`, e.g. a vision observation's own timestamp), with `previous`, `linear`, or `nearest` interpolation (angles along the shortest arc), scope and windows, and paged rows; with two signals, `difference_statistics` of their difference (angle-aware). `time_correlate` and `compare_entries` take `max_lag_sec` (and `lag_step_sec`) and report the lag that maximizes correlation or minimizes RMSE (`lag_search`).
- **Data quality is calibrated** (G1) — The score was 0.50 ("low") on nearly every real result: any interval over 5x the median counted as a gap, 20 gaps saturated the penalty, and one long gap maxed the jitter term. `data_quality` now classifies the series' `sampling` (`periodic`, `change_only` — logged when the value changes, as AdvantageKit does — or `event`), weighs long intervals by the fraction of time they cover (not their number) for periodic and change-only series, measures jitter by the median absolute deviation (periodic series), bases the sample-size penalty on finite values (all non-finite scores 0), and lists every penalty in `reasons`. Server directives note that long intervals in a change-only series are holds. On the review log, `FullCycleMS` now reads `high` (0.90; was 0.50) and chassis speeds `medium`, because 28% of the match is disabled holds that sample-based statistics under-weight. The `server_confidence_level` principle, instruction 4, and CLAUDE.md describe the new calibration.
- **`find_condition` combines conditions across entries** (Phase 4) — `conditions: {all: [...]}` or `{any: [...]}` of `{name, field, operator, threshold}`, each entry's value held until its next sample so change-only entries combine correctly; new operators `ne` and `abs_lt`/`abs_lte`/`abs_gt`/`abs_gte`. "Disabled and stationary" is one call: on the review log it returns the four disabled periods, matching Python. Transitions of a compound condition carry every condition's `values`, and the intervals feed `windows` on the statistics tools. The time searched starts once every entry has a value (`window_sec`).
- **Alerts and json text are read** (F1) — `search_strings`, `get_ds_timeline`, `can_health`, and `generate_report` read one text source: `string` lines, `string[]` entries such as WPILib `Alert`s (`/RealOutputs/Alerts/warnings`), and the string values of `json` entries (they read `string` entries only, so the review log's "Vision camera 3 is disconnected." alert was invisible). An alert array is state: each message is one event from when it appears to when it clears — `search_strings` returns it once with `end_sec`/`duration_sec` (or `active_at_log_end`), and `get_ds_timeline` adds `ALERT_RAISED` events with `cleared_at`. An alert's level comes from its entry name (`errors`, `warnings`, `infos`; `search_strings` accepts `level: info`); every match says its `source` (`string`, `alert`, `json`). On the review log the camera 3 alert was raised twice: 10.83–26.72 s while the camera connected at boot, and 737.68–783.80 s.
- **Server instructions and principles follow the new tools** — Rule 2 names struct and array paths (`Entry.field`, `Entry[i]`) and rule 6 the `scope` parameter, within the 2000-character budget; the whole-log, correlation, bimodal-outlier, and gap traps describe `scope`/`windows`, `find_condition` intervals as windows, and holds in change-only signals.
- **Analysis principles** — The PDH-channel trap no longer says numeric tools reject arrays, the wraparound trap describes which angles are unwrapped automatically, and a new trap covers concluding that struct data cannot be analyzed.
- **`analyze_vision` classifies structs by schema** — Observation streams (a `timestamp` field and a pose field) and target streams (`yaw` and `pitch`) are recognized from the struct's schema instead of by decoding every struct entry, which on the review log exhausted a 512 MB heap; only a struct with no known schema is judged by its content. `compare_matches` reports a log it cannot scope (no DriverStation data for `scope: enabled`) and still describes the other, instead of failing the call.
- **The last record of a log is read** — WPILib's `DataLogIterator.hasNext()` requires 16 bytes after a record's start, so a final record shorter than that (a boolean, a small number, a short struct) was silently dropped. Both parsers now walk records by their own lengths; a record cut off by the end of the file still marks the log as truncated.

### Added
- **Server-level reasoning guidance for AI agents** — The `initialize` response now includes MCP `instructions` (a compact scientific-method checklist: answer first, never invent entries or numbers, verify the premise, tiered fact/inference/hypothesis language, test rival hypotheses, scope to phase and enabled state, single-match and truncation caveats). Clients such as Claude Code and VS Code place it in the model's system prompt. `get_server_guide` now returns `analysis_principles` — the long-form method, confidence calibration, a catalogue of confabulation traps with the tool call that avoids each, cross-match rules, naming conventions, and report formats — so the guidance reaches clients that drop `instructions`. Both live in `AnalysisGuidance`; tests enforce the 2 KB client limit and that every referenced tool exists.
- **Tool `_meta` support** — `ToolRegistry.Tool.meta()` lets a tool attach an MCP `_meta` object to its `tools/list` entry. `get_server_guide` uses it to set `anthropic/alwaysLoad` so Claude Code keeps its description loaded when other MCP tools are deferred.
- **WPILib DataLogManager `DS:` entries** — `get_match_phases`, `get_ds_timeline`, `analyze_auto`, `can_health`, and `analyze_can_bus` now recognize `DS:enabled` / `DS:autonomous` (plain WPILib logs) in addition to AdvantageKit `/DriverStation/...` entries, via a shared `ToolUtils.isDsEntry`. Previously non-AdvantageKit logs reported no match phases and counted every CAN error as enabled-state.
- **`get_ds_timeline` roboRIO brownout flag events** — When the log contains a boolean brownout flag (e.g. AdvantageKit `/SystemStats/BrownedOut`), its transitions are reported as `RIO_BROWNOUT_START` / `RIO_BROWNOUT_END` with `basis: "rio_flag"`. The existing voltage-threshold events now carry `basis: "voltage_threshold"`, and the result includes `rio_brownout_flag_logged` (plus `rio_brownout_flag_entry`) so a client can tell whether the roboRIO's own brownout state is knowable from the log.
- **`get_ds_timeline` error/warning counts and summary** — Instead of listing console errors/warnings (where any cap or priority is an invisible judgment), the timeline now reports exact `text_event_counts` (per type and per source entry) and a `text_event_summary`: each distinct message after normalizing numbers to `#`, with its count, `variants` (how many different raw texts the group covers, judged on the full line), first/last time, and sources; capped at 200 groups with the true total reported. A multi-line sample containing both an error line and a warning line counts as an error regardless of order. Individual messages are listed by `search_strings`. "default" is no longer read as "fault".
- **`search_strings` is now the complete message log** — Optional `pattern` (substring or `regex=true`), `level` filter (`error`/`warning`/`any`, using the same classifier as the timeline counts), `start_time`/`end_time`, `offset`/`limit` paging with `total_matches` and `has_more`, results sorted by time across all entries, `collapse_repeats` (adjacent samples in the same entry only), per-match `level` and matching `line` (`line_truncated`), `max_value_chars` truncation with `value_truncated`, Unicode-aware case folding with `^`/`$` anchored to lines, and a one-second regex budget so a catastrophic pattern returns an error instead of hanging the server. Existing calls (`pattern` + `entry_pattern` + `limit`, `match_count`, `matches[].timestamp_sec/entry/value`) keep working; the old behavior silently truncated at 50 in hash-map order.
- **`get_ds_timeline` reports its voltage entry** — `brownout_voltage_entry` names the entry scanned for threshold crossings (selected exactly as `power_analysis` does, so WPILib `PowerDistribution[<id>]/Voltage` now works too), and a warning says when the log has none, so an empty `power` category is no longer mistakable for "no dip".
- **`get_match_phases` never-enabled warning** — When DriverStation entries exist but the robot was never enabled (pit or bench log), the tool now says so instead of silently returning no phases.
- **`power_analysis` per-channel current analysis** — New `channel_analysis` for every amperage entry: `peak_current_A` (largest magnitude, signed, with `peak_current_time_sec`), signed `max_current_A`/`min_current_A`, average, and sample count, sorted by peak magnitude; per-channel arrays (`double[]`, `float[]`, `int64[]`) such as `/PowerDistribution/ChannelCurrent` are expanded per index; `current_entries_analyzed` (always present); `channel_limit` parameter (default 30, minimum 1). Amperage entries are recognized by a documented naming rule (`...Current`, `...Amps` at a token boundary, `Current/<sub>`, WPILib `PowerDistribution[<id>]/Chan<N>`) so entries like `Current Angle Degrees`, `CurrentLimit`, or `OdometryTimestamps` are not mistaken for currents. The voltage entry is now chosen by a shared ranking (battery > input/bus > other > rails/regulators/motor outputs, finite samples required, ties by declaration order) instead of "first entry containing 'voltage'", which previously picked `/SystemStats/5vRail/Voltage` on AdvantageKit logs. Warns separately when no usable voltage entry or no current entries exist.
- **`compare_matches` per-log detail** — Adds `entry`, `logs_compared`, and per-log `log_path`, `entry_found`, `sample_count` (when found); warns when the entry is missing or has no finite scalar values; NaN samples are excluded.
- **`compare_matches` description** — Now states the scalar-only, whole-log scope, the per-log `entry_found`/`sample_count` fields, and the `get_statistics` alternative for phase-scoped comparisons.
- **`can_health` description** — Now describes what the tool does (string-entry scan, enabled/disabled split, GOOD/CONCERNING/POOR thresholds) instead of a "no CAN data found" response it never produced.

### Fixed
- **VS Code extension version floor** — `engines.vscode` raised from `^1.100.0` to `^1.101.0`. The MCP server definition provider API the extension relies on was finalized in VS Code 1.101; on 1.100 the extension could be installed but would fail on activation.
- **Packaged extension README links** — `buildExtension` and the release workflow now pass `--baseContentUrl`/`--baseImagesUrl` to `vsce package` so relative links and images in `vscode-extension/README.md` resolve to the `vscode-extension/` subdirectory. Previously vsce assumed the repository root, so the `../doc/…` links in the packaged README pointed at 404s.

### Documentation
- **`TOOL_RESPONSES.md` is generated** — The reference of real responses is captured by a checked-in harness (`ToolResponsesDoc`, calls in `src/test/resources/tool-responses/scenarios.json`) from the VACHE qualification log, its REV log, the review log, and a replay log; every tool has at least one call, several show the new capabilities (field paths, scopes, compound conditions, alerts, alignment, roles). `doc/DEVELOPMENT.md` describes the fixture corpus, conformance sweep, golden checks, and the harness.
- **Tool descriptions are checked against real output** (G4) — A test runs every tool on the fixture corpus and fails when a description names an output (a snake_case term that is not a parameter) that no result contains; the few outputs that appear only in situations the sweep does not create are listed with the test that covers each.
- **VS Code extension README** — Extension icon is now shown at the top of the README.
- **`TOOLS.md` examples matched to actual output** — `compare_matches`, `can_health`, `get_ds_timeline`, and `power_analysis` examples and field lists now reflect what the tools return (the previous `can_health` doc described a `FAIR` level and `can_entries` list that never existed; `get_ds_timeline` no longer claims joystick-disconnect events).

## [0.8.2] - 2026-03-26

### Added
- **YAML configuration support** — `ConfigLoader` now parses `servers.yaml` with 3-layer defaults merging (top-level defaults, per-server overrides, environment variable interpolation).
- **VS Code extension** — `wpilog-analyzer` extension with `McpServerDefinitionProvider` registration and `.mcp.json` generation for Claude Code compatibility. Auto-detects WPILib IDE JDK, log directory, and bundled JAR. Extension icon added.
- **VS Code extension `.mcp.json` generation** — Extension writes `.mcp.json` on activation with all settings (log directory, team number, TBA key) so Claude Code can discover the server.
- **Standalone installer scripts** — `install.ps1` (Windows) and `install.sh` (macOS/Linux) for one-line installation outside VS Code.
- **CI/CD workflows** — GitHub Actions for build, test, and release automation.
- **`buildExtension` Gradle task** — Builds the server JAR, compiles TypeScript, and packages the `.vsix` without installing. Complements `bundleExtension` (JAR only) and `installExtension` (build + install).
- **Stress test default configuration** — Stress tests now synthesize defaults (`~/riologs`, team 2363, TBA from `TBA_API_KEY` env var) when no config file is present. No `servers.yaml` entry required.

### Fixed
- **`AnalyzeSwerveTool.poseDistance` always returned zero** — Odometry drift analysis now handles flat struct layout (`{x, y}`) from Pose decoders, not just nested `{translation: {x, y}}`.
- **MoI R² computation used inconsistent torque formula** — Residual loop now uses the same torque sign convention as the OLS fit.
- **`time_correlate` included NaN/Infinity values** — Filter now requires `Double.isFinite()`. Uses worst-of-two quality scores.
- **`FrcDomainTools.extractTranslation` only handled nested layout** — Now supports both nested `{translation: {x, y}}` and flat `{x, y}` from struct decoders.
- **CSV export struct column mismatch** — Explicit field ordering for Pose2d, Pose3d, SwerveModuleState matches header row. Generic structs use deterministic alphabetical ordering.
- **2024 Crescendo auto amp scoring** — Corrected from 5 to 2 points per game manual.
- **TBA quarterfinal match key** — Added explicit `qf` comp level handling.
- **HTTP SSE response committed before executor check** — Moved `sendResponseHeaders(200)` inside the executor task so `RejectedExecutionException` returns 503.
- **LogManager shutdown lifecycle** — `shutdownNow()` + `awaitTermination()` prevents resource leaks. Disk cache shutdown waits for sync executor.
- **Cross-correlation center lag guard** — Returns `FAILED` when center lag exceeds array bounds, preventing `ArrayIndexOutOfBoundsException`.
- **`DataQuality` gap detection** — Changed from count-based to duration-based gap ratio for confidence level calculation.
- **DS timeline linear scan** — Replaced with binary search (`findFirstIndexAtOrAfter`) for teleop deferred emit.
- **Vision quality fallback** — Falls back to pose entry quality when no target entries found.
- **Overshoot detection absolute minimum** — Added `Math.01` minimum threshold to prevent false positives near zero setpoints.
- **Median calculation for even-length arrays** — Now averages two middle elements.
- **Battery voltage off-by-one** — Changed `voltageValues.size() - 10` to `voltageValues.size() - 1`.
- **`TbaClient.apiKey` not volatile** — Added `volatile` for safe publication to HTTP handler threads.
- **`health_check` misleading field name** — Renamed `cache_memory_mb` to `jvm_heap_used_mb` to accurately reflect that it measures total JVM heap, not cache-specific memory.
- **Main.java help text** — Changed stale `servers.json` reference to `servers.yaml`.
- **CHANGELOG gap threshold** — Corrected "3x-median" to "5x-median" to match code.
- **Launcher scripts hardcoded WPILib year** — Now dynamically scan for latest installed year.
- **Unix launcher missing JAVA_HOME fallback** — Added between WPILib scan and bare `java`.
- **CI extension compile restricted to Ubuntu** — Removed `if: matrix.os == 'ubuntu-latest'` from Node.js/extension steps.
- **`ExportTools` missing parameter validation** — `export_csv` now uses `getRequiredString()` for the `name` parameter.
- **Correlation guidance language** — Softened wording for moderate correlations.
- **CHANGELOG `WPILOG_BIND`** — Corrected to `WPILOG_HTTP_BIND`.

### Changed
- **Documentation restructured** — README slimmed to focus on installation and features. Detailed docs split into `vscode-extension/README.md` (extension settings, upgrading, uninstalling), `doc/STANDALONE.md` (standalone install, configuration, Docker), and `doc/DEVELOPMENT.md` (building, project structure, contributing).
- **Installation section leads README** — VS Code extension and standalone install presented as two equal paths with links to dedicated docs. Notes that both can coexist independently.
- **AI model capability caveats** — Added notes across documentation that analysis quality depends on the AI model used.
- **Default team number** — Changed from 0 to 2363 in generated config templates, extension defaults, and stress test fallbacks.
- **TOOLS.md confidence levels** — Fixed "moderate" to "medium", added missing "insufficient" level, corrected `servers.json` to `servers.yaml`.
- **`analyze_can_bus` categorization** — Moved from Robot Analysis to FRC Domain in README tool table to match code module.
- **Stress test output** — SLF4J logging reduced from `debug` to `warn`, JUnit output uses compact `tree` mode, stdout/stderr forwarded to console.

## [0.8.1] - 2026-03-24

### Added
- **Configurable HTTP bind address** — New `bind` config field / `-bind` CLI flag / `WPILOG_HTTP_BIND` env var controls which network interface the HTTP transport listens on.
- **Configurable endpoint path** — New `endpoint` config field allows customizing the HTTP endpoint path.
- **Origin allowlist** — New `origins` config field restricts CORS access to a configurable list of allowed origins.

## [0.8.0] - 2026-03-24

### Added
- **Lazy on-demand log parsing** — Log files are now memory-mapped and scanned in a single pass without decoding values. Entry metadata and lightweight `DataLogRecord` references are stashed per entry. Values are decoded on demand when tools access specific entries, and cached in a Caffeine weight-based LRU cache. This dramatically reduces memory usage and eliminates "file too large" errors for most files.
- **Caffeine dependency** — `com.github.ben-manes.caffeine:caffeine:3.1.8` for per-entry value caching with weight-based eviction.
- **`LogData` interface** — Common interface for `ParsedLog` (eager) and `LazyParsedLog` (lazy). All tools now accept `LogData` instead of `ParsedLog`.
- **`EntryDecoder`** — Extracted value decoding logic from `LogParser` into a standalone utility, shared by both eager parsing and lazy on-demand decoding.
- **Aggressive log eviction** — When loading a large file, the server now evicts cached logs to free memory rather than failing immediately.
- **Heap-pressure-based cache eviction** — In-memory log cache now evicts automatically when free heap drops below 15% of max. No configuration needed — users control capacity via `WPILOG_MAX_HEAP` environment variable (default 4g).

### Changed (Breaking)
- **Tool signatures** — `executeWithLog(ParsedLog log, ...)` changed to `executeWithLog(LogData log, ...)` across all 35 tool subclasses. `ToolBase` helper methods (`requireEntry`, `findEntryByPattern`, etc.) updated similarly.
- **`LogCache` rewritten with Caffeine** — The home-brewed `LinkedHashMap` + `ReentrantReadWriteLock` log cache is replaced by Caffeine with `expireAfterAccess` for idle eviction, synchronous removal listener for `LazyParsedLog.close()`, and `policy().expireAfterAccess().oldest()` for LRU eviction under heap pressure. Thread safety is handled by Caffeine internally.
- **`LogCache`, `LogManager`, sync classes** — All internal APIs updated from `ParsedLog` to `LogData`.
- **Wpilog disk cache bypassed** — `DiskCache` for wpilog files is no longer used in the load path (lazy loading from memory-mapped files is fast enough). `SyncDiskCache` for revlog sync results is still active.

### Fixed
- **Correlation near-zero denominator** — `time_correlate` now uses magnitude check (`< 1e-20`) instead of exact-zero check for variance, preventing silent clamping to ±1.0 on near-constant signals.
- **OLS singularity threshold** — `moi_regression` adds an absolute floor to the determinant check, preventing numerically unstable solutions on tiny datasets.
- **System.gc() in eviction loop** — Moved from per-iteration to a single call after the eviction loop completes, reducing GC pause latency during large file loads.
- **IPv6 loopback CORS** — `HttpTransport.isAllowedOrigin()` now accepts `[::1]` in addition to `localhost` and `127.0.0.1`.
- **Swerve angle extraction** — `analyze_swerve` now falls back to `radians` field when extracting angles from nested Rotation2d maps, in addition to `value`.
- **Percentile bounds validation** — `ToolUtils.percentile()` now throws `IllegalArgumentException` for values outside [0.0, 1.0].
- **Stale concurrency warnings** — Removed "NOT SAFE FOR CONCURRENT USE" warnings from `get_server_guide` tool output, TOOLS.md, and test suite. The server is thread-safe (Caffeine caches, ConcurrentHashMap, per-path load locking). Replaced with accurate `architecture` section describing thread safety and transport options.
- **Stale `health_check` documentation** — TOOLS.md now documents the actual response format (`jvm_memory`, `cache_memory_mb`, `disk_cache`) instead of the removed `memory_stats`/`estimatedMemoryMb`/`estimationAccuracy` fields.
- **Dead code cleanup** — Removed unused `LogManager` methods (`getCacheStats`, `getMemoryStats`, `setAutoSyncEnabled`, `isAutoSyncEnabled`, `testGetLoadedLogCount`), orphaned Javadoc, and duplicate comment blocks.

### Removed
- **`maxlogs` and `maxmemory` configuration options** — Removed from `servers.json`, CLI flags (`-maxlogs`, `-maxmemory`), and environment variables (`WPILOG_MAX_LOGS`, `WPILOG_MAX_MEMORY`). Memory management is now fully automatic via heap-pressure-based eviction. Users who need more cache capacity should increase `WPILOG_MAX_HEAP`.
- **`MemoryEstimator`** — Removed. Memory-based eviction is now driven by JVM heap pressure, not per-log estimation.
- **`LogIndex`** — Replaced by `LazyParsedLog` which builds its index during construction.
- **File-size-to-heap check** — The 8x multiplier guard is replaced by aggressive eviction + lazy loading.

## [0.7.2] - 2026-03-24

### Added
- **REV native binary format support** — `RevLogParser` now auto-detects and parses REV's proprietary `.revlog` binary format (in addition to WPILOG-format revlogs). Variable-length record parsing per the `REVrobotics/node-revlog-converter` specification. CAN ID translation maps native format IDs to DBC-compatible arbitration IDs. Device type detection for SPARK MAX, Servo Hub, and MAXSpline Encoder. Composite device keying prevents collisions when different device types share the same CAN ID.
- **Sync disk cache** — Caches parsed revlog data and cross-correlation sync results to disk (`SyncDiskCache`, `SyncCacheSerializer`). Reloading the same wpilog+revlog pair skips both parsing and correlation. Cache keyed by combined content fingerprints of both files.
- **Time-based revlog discovery** — Revlog files are now discovered via time overlap matching across the entire log directory tree (configurable scan depth, default 5), not just flat same-directory scanning. Supports multiple timestamp sources: systemTime entries, filename timestamps, and file modification time fallback. Files in sibling directories with unrelated names are correctly matched.
- **Configurable directory scan depth** — New `scandepth` config field / `-scandepth` CLI flag / `WPILOG_SCAN_DEPTH` env var controls how deep the server scans for log and revlog files. Default changed from 3 to 5.
- **`/health` HTTP endpoint** — Dedicated health check endpoint that returns immediately, replacing SSE-based health checks that consumed thread pool threads.
- **SSE thread pool separation** — SSE streams now run on a dedicated `CachedThreadPool` instead of the main request handler pool, preventing thread pool starvation.
- **Stdio shutdown hook** — Stdio mode now registers a shutdown hook for clean `DiskCache` termination.
- **Export directory configuration** — New `-exportdir` CLI flag / `WPILOG_EXPORT_DIR` env var / `"exportdir"` config field restricts CSV exports to a single configured directory. Default: `{tmpdir}/wpilog-export/`. Replaces the previous three-tier whitelist (log dir, temp dir, log parent dir).
- **TBA event code validation** — When a match lookup fails, the tool now validates the event code against TBA and searches for similar events by name/city/code. Provides "Did you mean?" suggestions when the event code doesn't match any TBA event.
- **No-args default startup** — Running `wpilog-mcp` with no arguments now starts the `"default"` server configuration from `servers.json`.
- **Launcher script heap auto-sizing** — The launcher script uses `WPILOG_MAX_HEAP` env var (default 4g) for JVM `-Xmx`.
- **`get_revlog_data` guardrails** — Now includes `DataQuality` and `AnalysisDirectives` when `include_stats` is true, consistent with other analysis tools.

### Fixed
- **P-value computation** — Cornish-Fisher expansion now uses higher-order correction terms (A&S 26.7.5) for improved accuracy at df=13–30. Reordered n<15 NaN guard before |r|≥1.0 check so `computePValue(1.0, 5)` correctly returns NaN instead of 0.0.
- **CSV export escaping** — String values containing commas, quotes, or newlines are now properly escaped per RFC 4180.
- **`compare_matches` crash** — No longer crashes on same-path input (`Map.of` duplicate key). Uses `LinkedHashMap` for deterministic output order.
- **Rate-of-change non-finite guard** — Both central-difference and windowed branches now filter non-finite derivatives.
- **Per-path load lock race** — Lock entries are no longer removed after use, preventing a race where concurrent threads could parse the same file on different lock objects.
- **DiskCache cleanup over-counting** — Stale files deleted during cleanup are no longer counted toward total size.
- **ContentFingerprint filename length** — Cache filenames now use 32 hex chars (128 bits) instead of 16 for better collision resistance.
- **CAN bus timestamp assumption** — `analyze_can_bus` uses `continue` instead of `break` for non-monotonic timestamps.
- **Battery report sentinel values** — `generate_report` no longer reports `Double.MAX_VALUE` when battery entry has no numeric data.
- **`SessionManager.cleanupExpired` TOCTOU** — Uses atomic `removeIf` instead of collect-then-remove.
- **`getValueAtTimeZoh` performance** — Now uses O(log n) binary search instead of O(n) linear scan.
- **SSE CORS headers** — All HTTP endpoints (including SSE and error responses) now include CORS headers.
- **PID file atomicity** — Uses `CREATE_NEW` for atomic creation plus `isAlreadyRunning` pre-check before spawning daemons.
- **`SearchEntriesTool` null params** — Added `isJsonNull()` checks for optional parameters.
- **`list_entries` case sensitivity** — Pattern filter is now case-insensitive, matching `search_entries` behavior.
- **Overshoot near-zero threshold** — Uses `Math.abs(lastSetpoint) > 0.001` instead of exact zero comparison.
- **Odometry drift scan performance** — Replaced O(n*m) linear scan with binary search via `getValueAtTimeZoh`.
- **SyncCacheSerializer null round-trip** — Path and explanation fields now correctly preserve null values.
- **SyncDiskCache write deduplication** — Added in-process `writesInProgress` guard matching `DiskCache` pattern.
- **`find_peaks` NaN/Infinity** — Now filters non-finite values before peak detection, preventing missed peaks adjacent to NaN and spurious Infinity peaks.
- **`getActualPoseAtTime` performance** — Replaced O(n) linear scan with O(log n) binary search via `getValueAtTimeZoh`.
- **PID file race** — `writePidFile` now lets `FileAlreadyExistsException` propagate; `spawnDaemon` catches it and destroys the duplicate process.
- **Export symlink protection** — `isPathAllowed` now resolves the full path via `toRealPath` for existing files and rejects symlink filenames via `Files.isSymbolicLink` for new files.
- **RevLogTools stale references** — Removed references to deleted `load_log` tool, stale "active wpilog" concept, and outdated "same directory" guidance from `wait_for_sync`, `list_revlog_signals`, and `set_revlog_offset` descriptions.

### Changed
- **`list_struct_types`** — Discovery catalog entry corrected to `requiresLog: false`.
- **`get_server_guide`** — Tool count dynamically computed from catalog size. Stale "active log" references removed.
- **`estimateSeasonYear`** — Regex pattern compiled once as static field.
- **Disk cache config naming** — Renamed for consistency: `-cachedir` → `-diskcachedir`, `-nocache` → `-diskcachedisable`, `WPILOG_CACHE_DIR` → `WPILOG_DISK_CACHE_DIR`, `WPILOG_NO_CACHE` → `WPILOG_DISK_CACHE_DISABLE`. JSON config keys: `cachedir` → `diskcachedir`, `nocache` → `diskcachedisable`.
- **Install layout** — `versions/{version}/wpilog-mcp.jar` replaced with `jars/wpilog-mcp-{version}.jar`. Versioned JARs in a flat `jars/` directory with versioned launcher scripts referencing them.
- **JDK 17 idioms** — Adopted `instanceof` pattern matching (eliminated manual casts in 9 locations across 5 files), converted `ToolDependencies` from class to record.
- **Stress test configuration** — Stress tests now load from `"stresstest"` named config in `servers.json` instead of scanning MCP client configs. Three Gradle tasks: `stressTest` (both), `stdioStressTest`, `httpStressTest`.

### Documentation
- TOOLS.md updated with `path` parameter for all log-requiring tools
- TOOLS.md `compare_matches` parameters updated (requires `path`, `compare_path`, `name`)
- TOOLS.md parameter lists corrected to match actual `toolSchema()` definitions (removed phantom parameters from `analyze_can_bus`, `profile_mechanism`, `analyze_auto`, `analyze_replay_drift`, `analyze_loop_timing`)
- README rewritten: installation via `./gradlew install`, `servers.json` configuration, no-args default startup, CLI overrides, removed legacy CLI references
- WpilogTools and RevLogTools Javadoc updated with current tool sets

## [0.7.0] - 2026-03-23

### Added
- **HTTP Streamable transport** — Multi-client MCP server via `--http` flag using `com.sun.net.httpserver.HttpServer`. Session management with idle timeout and periodic cleanup. SSE keep-alive for server-initiated messages.
- **Modular MCP architecture** — Transport-independent `McpMessageHandler` router, `SessionManager`, `SessionContext` (ThreadLocal) for per-request session isolation. `ToolRegistry` shared across transports.
- **Game data files** — Bundled JSON game data for 2024 Crescendo, 2025 Reefscape, and 2026 REBUILT seasons.

### Changed (Breaking)
- **Path-per-call architecture** — All log-requiring tools now take a required `path` parameter instead of operating on a shared "active log." Each tool call is self-contained. The server auto-loads logs on first reference and auto-evicts idle logs after 30 minutes of inactivity. This eliminates the need for explicit log lifecycle management.
- **Removed 4 lifecycle tools** — `load_log`, `set_active_log`, `unload_log`, and `unload_all_logs` have been removed. Log loading is now implicit when any tool references a path. Cache management is automatic.
- **`compare_matches` parameters** — Now takes `path` and `compare_path` parameters to identify the two logs to compare, instead of iterating over loaded logs.
- **`list_entries` enhanced** — Now returns log metadata (time range, truncation status) that was previously only available via the removed `load_log` tool.
- **Session isolation improved** — With no shared "active log" state, concurrent sessions in HTTP mode are fully isolated by design.
- **Tool count**: 49 → 45 (removed 4 lifecycle tools)

### Fixed
- **`export_csv` primitive array bug** — `double[]`, `int64[]`, `float[]`, `boolean[]`, and `string[]` entries now export as indexed rows with actual values instead of Java object reference strings (`[D@...`).
- **`GameKnowledgeBase.getGame()` NPE** — No longer throws `NullPointerException` for unsupported seasons; returns null as documented.
- **P-value computation** — Completed the Abramowitz & Stegun 26.7.4 Cornish-Fisher expansion (correction term using `b` was computed but never applied). Full-precision normalCdf coefficients.
- **`get_code_metadata` crash** — Now handles entries with empty values gracefully instead of throwing NPE/IndexOutOfBoundsException.
- **Replay drift comparison** — `analyze_replay_drift` now compares by timestamp alignment (1ms tolerance) instead of array index, preventing false divergences when sample counts differ.
- **`isPathAllowed` symlink bypass** — Now resolves symlinks via `toRealPath()` to prevent symlink-based path escape in `export_csv`.
- **HttpTransport thread pool** — Changed from unbounded `newCachedThreadPool` to bounded `newFixedThreadPool` to prevent thread exhaustion.
- **HttpTransport batch session enforcement** — Batch POST without session header now correctly rejects non-initialize requests.
- **`loadLocks` race condition** — Per-path locks are no longer removed after use, preventing a narrow race where concurrent threads could synchronize on different lock objects.
- **Brownout end-of-log** — `detectVoltageEvents` now emits an event for sustained brownouts at end of log.
- **Recovery analysis sample limit** — Removed the 10,000-sample hard cap that missed events at >50Hz logging rates.
- **Season year detection** — `analyze_auto` and `get_match_phases` now estimate the season year from the log filename instead of using the system clock, fixing wrong auto duration fallbacks for prior-season logs.
- **Drift rate metric** — Renamed `drift_rate_m_per_sec` to `max_error_per_total_time` to accurately describe the metric.
- **`computeMedianOffset` overflow** — Overflow-safe median computation for epoch-scale microsecond offset pairs.
- **Vacuous test assertion** — Fixed conditional assertion in `ToolUtilsTest.lowQualityAddsWarning`.

### Changed
- **Percentile implementation** — Deduplicated from `StatisticsTools` and `FrcDomainTools` into a single canonical `ToolUtils.percentile()` method.
- **Dead code removed** — Removed unused `calculateRmseZoh` method from `ToolUtils`.
- **`DataQuality.confidenceLevel()`** — Now returns 4 levels (`high/medium/low/insufficient`) instead of 3, with "insufficient" for quality ≤ 0.2. Terminology aligned with CLAUDE.md.
- **`get_match_phases` guardrails** — Added `GUIDANCE_UNIVERSAL`, `GUIDANCE_MATCH_ANALYSIS`, `DataQuality`, and `AnalysisDirectives` to the tool description and response.
- **`generate_report` guardrails** — Added `DataQuality` and `AnalysisDirectives` when battery voltage data is available.
- **Discovery categories** — Added `list_struct_types` and `health_check` to the core category listing.

## [0.6.1] - 2026-03-23

### Added

#### Discovery Tools for LLM Agent Discoverability
- **`get_server_guide` tool** — Comprehensive overview of all server capabilities organized by category. Returns structured JSON with tool descriptions, usage examples, anti-patterns to avoid, common workflows, and critical guidance. Includes a `limitations` section warning about concurrency constraints. Call this first when starting a new analysis session.
- **`suggest_tools` tool** — Recommendation engine that suggests relevant tools for a natural language task description. Uses keyword matching and semantic understanding to recommend tools with relevance scores, anti-patterns, and suggested workflows.
- **`get_tba_match_data` tool** — Direct access to The Blue Alliance match data. Query specific match scores, win/loss status, detailed score breakdowns (autonomous points, teleop points, etc.), and alliance compositions. Use this instead of guessing match outcomes from telemetry.

#### Enhanced Tool Descriptions ("Trojan Horse" Pattern)
- **`list_available_logs`**: Now prominently mentions TBA enrichment and match score availability
- **`get_tba_status`**: Enhanced to explain TBA capabilities and direct users to get_tba_match_data
- **`get_statistics`**: Emphasizes "NEVER compute statistics manually—always use this tool"
- **`get_match_phases`**: Emphasizes "NEVER manually parse timestamps—always use this tool"
- **`time_correlate`**: Emphasizes "NEVER compute correlation manually—always use this tool"

#### Concurrency Warning and Workarounds
- **Server limitations documented** — The `get_server_guide` tool now includes a `limitations` section explicitly warning that the server is NOT SAFE FOR CONCURRENT USE. Clients must execute tool calls sequentially. The server maintains shared state (active log, log cache) that would conflict under concurrent access.
- **Multi-instance workaround** — Documented that running multiple *separate* server instances pointing to the same log directory IS safe. The disk cache uses file locking and atomic operations to prevent corruption.
- **LLM sub-agent warning** — Added explicit warning about LLM frameworks (Claude Code, AutoGPT, LangGraph, etc.) that may spawn sub-agents to parallelize work. Users must explicitly instruct agents to operate sequentially when analyzing multiple logs.

### Changed
- **Tool count**: 46 → 49 (added get_server_guide, suggest_tools, get_tba_match_data)

### Testing
- Added `DiscoveryToolsTest` with 19 tests covering get_server_guide and suggest_tools functionality
- Extended `TbaToolsLogicTest` with 5 tests for get_tba_match_data schema, description, and behavior
- All 965 tests passing

## [0.6.0] - 2026-03-21

### Added

#### Persistent Disk Cache
- **MessagePack-based parse cache** — Parsed logs are cached to disk as MessagePack binary files, avoiding expensive reparsing on server restart. Cache files are stored in the OS-appropriate application data directory (macOS: `~/Library/Application Support/wpilog-mcp/cache/`, Linux: `~/.local/share/wpilog-mcp/cache/`, Windows: `%LOCALAPPDATA%/wpilog-mcp/cache/`). Override with `-diskcachedir <path>` or `WPILOG_DISK_CACHE_DIR` env var. Disable with `-diskcachedisable`.
- **Content fingerprinting** — Cache identity is based on file content (SHA-256 of first 64 KB + last 64 KB + file size), not file path. Identical files in different directories share a single cache entry. No collisions between different files with the same name.
- **Version-aware invalidation** — Cache files store a format version number. Format changes automatically invalidate stale cache files. Fast mtime+size validation avoids recomputing fingerprints when files haven't changed.
- **Concurrent safety** — Writes use atomic rename (temp file → `Files.move` with `ATOMIC_MOVE`). Advisory file locks prevent duplicate writes from parallel server instances. Reads are lock-free.
- **Automatic cleanup** — Expired cache files (default: >30 days) and oversized caches (default: >2 GB) are cleaned up on startup. Orphaned temp files from crashed writes are removed.
- **Background save** — Cache writes happen asynchronously on a daemon thread, never blocking MCP responses.

#### Comprehensive Swerve Analysis (§3.1)
- **Wheel slip detection** — `analyze_swerve` now discovers setpoint/measured SwerveModuleState entry pairs by naming convention and computes per-module slip (|actual - commanded|), reporting max slip, average slip, slip event count, and slip rate.
- **Module synchronization analysis** — Compares steering angles across all measured modules at each timestamp. Reports desync event count, max angle deviation (rad and deg), and identifies the worst-performing module.
- **Odometry drift measurement** — Auto-discovers odometry and vision Pose2d/3d entries and computes pose error over time, reporting average error, max error, and drift rate in m/s. Supports explicit entry name override via `odometry_entry` and `vision_entry` parameters.
- **New parameters**: `slip_threshold` (m/s, default 0.5), `sync_threshold_rad` (default 0.1), `odometry_entry`, `vision_entry`.
- **Graceful degradation** — Each analysis section only appears if the required entries are found. Basic per-module speed stats are always reported.

#### Data Quality Scoring Propagation (§2.1)
- **All 15 analytical tools** now include `data_quality` and `server_analysis_directives` in their responses. Previously only `get_statistics` had these fields.
- **Tools using ResponseBuilder**: `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `analyze_vision`, `profile_mechanism`, `predict_battery_health` — integrated via `.addDataQuality(quality).addDirectives(directives)`.
- **Tools using raw JsonObject**: `power_analysis`, `moi_regression`, `analyze_swerve` — integrated via new `ToolUtils.appendQualityToResult()` helper that merges with existing warnings arrays.
- **Tool-specific guidance**: Each tool adds contextual followup suggestions (e.g., "Use detect_anomalies to check for outliers" from `get_statistics`, "Use predict_battery_health for comprehensive assessment" from `power_analysis`).

#### Test Data Library (§7.2)
- **MockLogBuilder factory methods** for common test scenarios: `createCleanMatchLog()` (160s with DS, voltage, velocity, loop time), `createBrownoutMatchLog()` (voltage drops to 5.5V), `createSwerveModuleLog()` (4 modules with intentional slip/sync issues + odometry drift), `createLowQualityLog()` (gaps, NaN, low sample count), `createVisionLog()` (target flicker, pose jumps).
- **New builder helpers**: `addBooleanEntry()`, `addPeriodicEntry()` (generates from a function), `addStructEntry()` (for Map-typed values), `makePose2d()` (creates Pose2d struct maps).

#### Year-Specific Game Knowledge Base
- **`get_game_info` tool** — New tool that returns year-specific FRC game information (match timing, scoring values, field geometry, game pieces, analysis hints). Defaults to the current season. Enables LLMs to interpret log data in the context of the actual game.
- **2026 REBUILT game data** — Bundled JSON resource (`games/2026-rebuilt.json`) with complete game data sourced from the official game manual (TU17): 20s auto, 2:20 teleop with hub shift mechanics, 30s endgame, tower climbing (L1/L2/L3), FUEL scoring, ranking point thresholds (ENERGIZED 100, SUPERCHARGED 360, TRAVERSAL 50), field geometry, and analysis hints.
- **`GameKnowledgeBase`** — Singleton that loads game data from bundled resources or user-provided JSON files. Cached per season. Extensible format (format_version field) for future seasons.
- **`GameData`** — Typed accessor class over raw JSON with convenience methods for match timing, field dimensions, scoring, and analysis hints.

#### Background RevLog Processing
- **Async revlog synchronization** — `autoSyncRevLogs` now runs on a background daemon thread via `CompletableFuture`, no longer blocking the initial log load. A placeholder `SynchronizedLogs` (with 0 revlogs) is placed in the sync cache immediately so tools can detect the pending state.
- **`wait_for_sync` tool** — New tool that blocks until background synchronization completes (default timeout: 30s). Returns immediately if sync is already done or no revlogs are present.
- **`sync_in_progress` status field** — `sync_status` and `list_revlog_signals` now include a `sync_in_progress` boolean and contextual warnings when sync is still running.
- **Cancellation on eviction** — `clearAllLogs()` cancels any in-progress sync futures to avoid orphaned background work.

#### LLM Epistemological Guardrails
- **Trojan Horse tool descriptions (§6.1)** — All 20 analytical tools now embed interpretation guidance in their MCP `description()` strings. Five guidance constants in `ToolUtils` (`GUIDANCE_UNIVERSAL`, `GUIDANCE_STATISTICAL`, `GUIDANCE_POWER`, `GUIDANCE_MECHANISM`, `GUIDANCE_MATCH_ANALYSIS`) provide consistent, category-appropriate caveats about single-match limitations, sample size uncertainty, correlation-vs-causation, and alternative explanations. Informational tools (`list_entries`, `read_entry`, etc.) are unchanged.
- **Data quality metadata (§6.5)** — New `DataQuality` record computes quality metrics from any `List<TimestampedValue>`: sample count, time span, gap count/max (adaptive 5x-median threshold), NaN/Infinity count, effective sample rate, timing jitter, and a composite quality score (0.0–1.0). `ResponseBuilder.addDataQuality()` serializes these into a `data_quality` JSON object and auto-warns when score < 0.5. Integrated into `get_statistics` as reference implementation.
- **Output contextual framing (§6.2)** — New `AnalysisDirectives` class generates `server_analysis_directives` in tool responses. `fromQuality(DataQuality)` factory auto-generates guidance from detected issues (low sample count, gaps, NaN, short time span). Builder methods `addGuidance()`, `addFollowup()`, and `addSingleMatchCaveat()` allow tool-specific enrichment. `ResponseBuilder.addDirectives()` serializes into `confidence_level`, `sample_context`, `interpretation_guidance[]`, and `suggested_followup[]` fields.

### Fixed

#### Comprehensive code review remediation

**Critical & Major Fixes:**
- **Critical: Match phase timing completely rewritten** — `get_match_phases` no longer hardcodes phase durations (was using wrong values: 135s total instead of 150s). Now derives all phases from actual DriverStation mode transitions in the log, making it correct for any FRC game year. If DS data is absent, returns a warning instead of guessing.
- **Major: Cache eviction loop** — `LogCache.evictIfNeeded()` now loops until cache is within both count and memory limits, instead of evicting only a single entry per call. Prevents unbounded cache growth.
- **Major: Pre-parse eviction** — `LogManager.loadLog()` now evicts before parsing the new log, reducing peak memory usage and preventing OOM when the cache is full.
- **Major: `compare_matches` race condition** — No longer mutates the active log in a loop. Instead accesses logs directly from cache, eliminating a race condition under concurrent MCP requests.
- **Major: O(n) interpolation → O(log n)** — `getValueAtTimeLinear` now uses binary search instead of linear scan. Affects `compare_entries`, `time_correlate`, `moi_regression`, and all tools using signal interpolation.
- **Major: MoI gradient division by zero** — Numerical gradient now guards against zero dt (duplicate timestamps) by returning NaN, which is filtered by the existing isFinite check in the OLS loop.

**Minor Fixes:**
- **Rate of change average denominator** — `rate_of_change` now counts only valid (non-zero-dt) samples for the average divisor, preventing dilution from duplicate timestamps.
- **OLS determinant threshold** — `moi_regression` uses a relative threshold for singularity detection, working correctly for mechanisms with small angular velocities.
- **Symlink resolution in path validation** — `SecurityValidator` now resolves symlinks via `toRealPath()`, preventing symlink-based path traversal bypasses.
- **syncCache memory leak on eviction** — Added eviction callback from `LogCache` that cleans up corresponding `syncCache` entries when logs are evicted.
- **Brownout threshold corrected** — Default changed from 7.0V to 6.8V (actual roboRIO 1 threshold). Documentation updated for roboRIO 2 (6.3V).
- **Loop timing unit detection** — Added explicit `unit` parameter ("ms", "s", "auto"). Auto-detect uses median value instead of fragile per-sample heuristic.
- **NaN/Infinity filtering in numeric extraction** — `extractNumericData` now filters non-finite values, preventing silent corruption of statistics.
- **Initial maxTimestamp sentinel** — Changed from `Double.MIN_VALUE` (smallest positive) to `Double.NEGATIVE_INFINITY` for correctness.
- **Comprehensive exception handling** — `ToolBase.execute()` now catches all exceptions and returns error responses instead of propagating raw exceptions.
- **Memory estimation sampling** — `MemoryEstimator` now samples first, middle, and last values per entry, using the maximum to avoid underestimates for variable-size entries.

#### Prior code review follow-up fixes
- **LogManager syncRevLog TOCTOU race** — `syncRevLog` now uses `syncCache.compute()` for atomic read-modify-write on the sync cache, preventing concurrent sync requests from dropping revlog data
- **FindPeaksTool misleading parameter name** — Renamed `prominence` parameter to `min_height_diff` and output field to `height_diff`, since the calculation measures local height difference from neighbors, not true topographic prominence
- **getValueAtTimeLinear extrapolation** — `getValueAtTimeLinear` now returns `null` for timestamps outside the series range instead of holding the last value (ZOH extrapolation), preventing `compare_entries` and `time_correlate` from comparing against stale boundary values
- **LogSynchronizer timezone assumption** — Added configurable `filenameTimezone` parameter to `LogSynchronizer` constructor. The coarse offset estimation now uses this instead of always assuming `ZoneId.systemDefault()`, fixing incorrect sync when the MCP server runs in a different timezone than the PC that captured the REV log

#### Thread Safety & Correctness (code review findings)
- **Critical: LogCache read-under-write bug** — `get()` now uses `writeLock()` instead of `readLock()` for access-ordered LinkedHashMap, preventing `ConcurrentModificationException` or infinite loops during parallel MCP requests
- **LogManager TOCTOU race** — New atomic `LogCache.setActiveIfPresent()` method prevents race between `containsKey` check and `setActiveLogPath` in `setActiveLog()`
- **Anomaly detection NaN/Infinity corruption** — `detect_anomalies` tool now filters `NaN` and `Infinity` values before IQR computation, preventing silent corruption of Q1/Q3 percentiles
- **DbcSignal unsigned 64-bit overflow** — CAN signals that are unsigned and exactly 64 bits now correctly decode values with the MSB set as large positive doubles instead of negative
- **R² for no-intercept regression** — `moi_regression` tool now uses uncentered R² (`1 - SS_res / Σy²`) instead of centered R², which is mathematically invalid for the interceptless model `τ = Jα + Bω`
- **Time correlation sample rate warning** — `time_correlate` tool now warns when input signals have >10x sample rate mismatch, which can bias Pearson correlation via interpolation smoothing
- **TbaClient unbounded cache growth** — TBA API caches now evict expired entries and enforce a maximum of 200 entries per cache map, preventing unbounded memory growth in long-running servers
- **LogSynchronizer configurable parameters** — Sync constants (sample rate, search window, thresholds) are now configurable via constructor instead of hardcoded, enabling tuning for non-standard log formats
- **MoiRegression null current corruption** — `moi_regression` now skips samples where current or voltage interpolation returns null (e.g., when the current log starts later than velocity), instead of silently inserting 0.0 which corrupted the OLS fit
- **Removed System.gc() from hot paths** — Removed explicit `System.gc()` calls from `LogCache.evictLeastRecentlyUsed()` (which held the write lock) and `LogManager.loadLog()` memory estimation, eliminating unnecessary Stop-The-World GC pauses
- **LogCache volatile config fields** — `maxLoadedLogs` and `maxMemoryMb` in `LogCache` are now `volatile` to ensure cross-thread visibility when set during configuration

### Testing

#### New tests (disk cache + code review + guardrails)
- `ContentFingerprintTest` — 5 tests: same content/different paths, different content, stability, large files, filename format
- `DiskCacheSerializerTest` — 10 tests: round-trip for all value types (double, boolean, string, int64, struct/Map), multiple entries, truncation info, format version rejection, corrupt file handling, metadata-only read
- `DiskCacheTest` — 6 tests: save/load round-trip, cache miss, invalidation on modification, content-based sharing across paths, disabled cache, cleanup
- `DataQualityTest` — 12 tests: empty/null/single sample handling, gap detection (uniform vs interrupted data), NaN counting and scoring impact, quality score bounds, JSON serialization with conditional field omission
- `GetMatchPhasesToolTests` — 3 tests: DS-derived phases, missing DS data warning, non-standard game year durations
- `MoiRegressionToolTests.handlesDuplicateTimestamps` — verifies no NaN/Infinity from zero-dt gradient
- `RateOfChangeToolTests.avgRateDenominatorCountsOnlyValidSamples` — verifies correct average with duplicate timestamps
- `LogCacheTest.evictsMultipleEntriesUntilWithinCountLimit` — verifies eviction loop removes multiple entries
- `LogCacheTest.evictionCallbackIsInvokedOnEviction` — verifies syncCache cleanup callback
- `GetStatisticsToolTests.includesDataQualityAndDirectives` — verifies `data_quality` and `server_analysis_directives` in response

#### Prior tests
- Added new `RobotAnalysisToolsLogicTest` with 3 tests for `moi_regression`: missing current skip, missing voltage skip, and complete data regression
- Added 2 LogCache regression tests: eviction timing (no System.gc() in lock) and volatile field verification
- Added FindPeaksTool tests for `height_diff` output field and `min_height_diff` filtering
- Added `compare_entries` tests for overlapping/non-overlapping time ranges (no-extrapolation behavior)
- Added LogSynchronizer test for configurable timezone parameter
- **Test count**: 686 → 732

## [0.5.0] - 2026-03-20

### Added

#### REV Log (.revlog) Integration
- **RevLog parser** with DBC-based CAN signal decoding for SPARK MAX/Flex motor controllers
- **Two-phase timestamp synchronization**: coarse alignment from systemTime + fine alignment via Pearson cross-correlation
- **Clock drift compensation**: for recordings >15 minutes, estimates and corrects linear drift between FPGA and monotonic clocks
- **High-variance window search**: automatically finds the most active portion of long signals, solving the "2 minutes disabled at start" problem common in FRC matches
- **Auto-sync on load**: revlog files in the same directory as a wpilog are discovered and synchronized automatically
- **Multiple revlog support**: handles multi-bus robots (Rio + CANivore) with per-bus sync results
- **DBC hybrid loading**: embedded defaults with override chain (CLI → config dir → env var → embedded)

#### New Tools (4)
- **`list_revlog_signals`**: List available REV signals with sync status, confidence, and device metadata
- **`get_revlog_data`**: Query REV signal data with FPGA-synchronized timestamps, time filtering, and statistics
- **`sync_status`**: Detailed synchronization diagnostics including method, confidence, offset, signal pairs, and drift rate
- **`set_revlog_offset`**: Manually override automatic synchronization when it fails or produces incorrect results

#### Robustness Improvements
- **Binary parsing hardening**: malformed record recovery, negative timestamp rejection, truncated CAN frame handling, corrupt record counting/logging
- **Thread safety**: `autoSyncEnabled` is now volatile; sync cache uses `ConcurrentHashMap`
- **Centralized offset transformation**: `SynchronizedLogs` delegates to `SyncResult.toFpgaTime()` for drift-aware timestamp conversion

### Changed
- **`SyncResult`** record extended with `driftRateNanosPerSec` and `referenceTimeSec` fields for clock drift compensation
- **`isFlat` threshold** changed from `1e-10` to `1e-6` — more realistic for motor signals while still rejecting truly flat data
- **Resample limit** increased from 10,000 (100s) to 60,000 (10 min) samples to cover full FRC matches
- **Tool count**: 43 → 44

### Documentation
- **TOOLS.md**: Added comprehensive technical explanation of synchronization algorithm (two-phase alignment, cross-correlation math, drift compensation, confidence scoring)
- **README.md**: Updated REV Log Integration section with synchronization details and manual override instructions
- **CHANGELOG.md**: Added v0.5.0 release notes

### Testing
- New edge case tests: single-sample signals, zero-duration signals, long disabled periods, drift compensation math, user-provided offsets
- Updated tool count assertions for new `set_revlog_offset` tool
- Stress test updated to exercise all RevLog tools

## [0.4.1] - 2026-03-19

### Changed

#### Architecture: Tool Infrastructure Modernization
- **Created ToolBase Abstract Class**: Centralized common tool functionality eliminating 20-30% boilerplate across all tools:
  - Helper methods: `requireActiveLog()`, `requireEntry()` with "did you mean?" suggestions, `filterTimeRange()`, `inTimeRange()`, `extractNumericData()`, `findEntryByPattern()`
  - Template method pattern with automatic `IllegalArgumentException` → error response conversion
  - Fluent response builders: `success()` and `error()` for standardized responses
- **Created LogRequiringTool Specialized Base**: Abstract class for the 90% of tools requiring an active log
  - Guarantees non-null log parameter to `executeWithLog()`
  - Automatic "no log loaded" error responses
  - Eliminated ~40 duplicate log acquisition checks across codebase
- **Migrated 18 Tools** to new infrastructure:
  - **StatisticsTools** (6 tools): `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`
  - **QueryTools** (4 tools): `search_entries`, `get_types`, `find_condition`, `search_strings`
  - **FrcDomainTools** (8 tools): `get_ds_timeline`, `analyze_vision`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift`, `analyze_loop_timing`, `analyze_can_bus`
- **Benefits**:
  - Reduced duplicate code: ~40 log checks, ~20 entry retrievals, ~8 helper method duplicates eliminated
  - Improved error messages: Automatic suggestions for misspelled entry names
  - Better testability: LogRequiringTool enables easier testing with guaranteed non-null parameters
  - Consistent error handling: All IllegalArgumentExceptions automatically converted to proper error responses

### Added

#### Comprehensive Edge Case Testing (67 new tests)
- **ToolDependenciesTest** (20 tests): Dependency injection container verification
  - Factory method (`fromSingletons()`) behavior
  - Constructor with explicit/null dependencies
  - Getter consistency and immutability
  - Concurrent access patterns
- **LogRequiringToolTest** (21 tests): Automatic log checking infrastructure
  - Template method pattern verification
  - Error handling when no log loaded
  - Non-null log parameter guarantee
  - Exception propagation and conversion
- **MigratedToolsEdgeCaseTest** (26 tests): Boundary conditions for all migrated tools
  - Statistical edge cases: single data point, two points (Bessel's correction), 10,000 points
  - Numeric extremes: `Double.MAX_VALUE`, `Double.MIN_VALUE`, zero variance
  - Empty datasets and special characters in entry names
  - Concurrent tool execution (100 iterations)
  - Log switching and state transitions

### Testing
- **Test Suite Growth**: 417 → 478 tests (15% increase)
- **Pass Rate**: 100% (478/478 tests passing)
- **Coverage**: Maintained >80% code coverage
- **Edge Cases**: Comprehensive boundary condition testing ensures robustness

## [0.4.0] - 2026-03-19

### Changed

#### Architecture: LogManager Subsystem Extraction
- **Refactored LogManager** from 1438-line monolith into facade pattern with 6 specialized subsystems:
  - `LogCache`: LRU cache with thread-safe operations and eviction logic
  - `LogParser`: WPILOG file parsing delegating to struct decoder registry
  - `StructDecoderRegistry`: Extensible registry pattern replacing 500-line switch statement with Map-based decoder lookup
  - `SecurityValidator`: Path validation logic with traversal attack prevention
  - `MemoryEstimator`: Memory usage estimation for cache eviction decisions
  - `BinaryReader`: Binary reading utilities for struct decoding
- **Created 16 struct decoder classes** implementing `StructDecoder` interface for WPILib types:
  - Geometry: `Pose2dDecoder`, `Pose3dDecoder`, `Translation2dDecoder`, `Translation3dDecoder`, `Rotation2dDecoder`, `Rotation3dDecoder`, `Transform2dDecoder`, `Transform3dDecoder`, `Twist2dDecoder`, `Twist3dDecoder`
  - Kinematics: `ChassisSpeedsDecoder`, `SwerveModuleStateDecoder`, `SwerveModulePositionDecoder`, `DifferentialDriveWheelSpeedsDecoder`, `MecanumDriveWheelSpeedsDecoder`
  - Vision: `TargetObservationDecoder`, `PoseObservationDecoder`
  - Autonomous: `SwerveSampleDecoder`
- **Benefits**: Improved maintainability, extensibility for custom struct types, clearer separation of concerns, easier testing
- **Backward Compatibility**: All public APIs preserved - zero breaking changes

### Fixed
- **`list_loaded_logs`**: Now properly iterates cache and returns LoadedLogInfo records with memory estimates and active status
- **Tool Documentation**: Updated 6 tool descriptions to explicitly document expected "no data found" messages:
  - `analyze_swerve`: Documents "no swerve modules detected" message
  - `power_analysis`: Documents "no battery data found" message
  - `can_health`: Documents "no CAN data found" message
  - `get_code_metadata`: Documents "no code metadata found" message
  - `analyze_auto`: Documents "no auto period detected" message
  - `analyze_can_bus`: Documents "no CAN bus data found" message

### Added
- **Memory Monitoring**: New `getMemoryStats()` method in LogManager providing comprehensive heap statistics:
  - Estimated memory usage from cache (MB)
  - Actual JVM heap usage (used, max, free, utilization percentage)
  - Estimation accuracy ratio comparing heuristics to actual usage
  - Available via `health_check` tool for real-time monitoring
- **LogCache Iteration**: Added `getAllEntries()` method to LogCache for thread-safe cache enumeration
- **`health_check` tool enhancement**: Now includes estimation accuracy metric showing how well memory heuristics match actual heap usage

## [0.3.0] - 2026-03-19

### Added

#### New Tools (4)
- **`list_struct_types`**: Lists all supported WPILib struct types organized by category (geometry, kinematics, vision, autonomous)
- **`health_check`**: System health monitoring with JVM memory usage, cache memory estimate, loaded logs count, and TBA availability
- **`analyze_loop_timing`**: Real-time performance analysis detecting loop overruns > 20ms with jitter analysis and health assessment
- **`analyze_can_bus`**: CAN bus health monitoring with utilization analysis, TX/RX error tracking, and actionable recommendations

#### Enhanced Tools (4)
- **`profile_mechanism`**: Added stall detection (velocity < 0.01, current > threshold), settling time calculation, and overshoot percentage
- **`analyze_vision`**: Added pose jump detection to identify unreliable vision estimates that can cause odometry drift
- **`analyze_auto`**: Added path following RMSE calculation with max error tracking and typical value guidelines
- **`analyze_cycles`**: Added dead time analysis to identify idle periods between cycles with percentage of teleop

#### Core Improvements
- **Execution Time Tracking**: All tool responses now include `_execution_time_ms` field for performance monitoring
- **Intelligent Error Handling**:
  - Error classification with specific codes: `invalid_parameter` (IllegalArgumentException), `io_error` (IOException), `memory_error` (OutOfMemoryError)
  - "Did You Mean?" suggestions for misspelled tool names using Levenshtein distance algorithm
- **Logging**: Added structured logging for tool execution (success/failure) and error events
- **toLowerCase() Optimization**: Cached toLowerCase() results in hot loops for better performance

### Fixed
- **IQR Calculation**: Implemented proper linear percentile interpolation for accurate outlier detection (previously used simple array indexing)
- **Memory Estimation**: Improved accuracy by properly calculating struct array sizes and handling all data types
- **Incomplete Tool Implementations**:
  - `profile_mechanism`: Now fully implements stall detection, settling time, and overshoot calculations
  - `analyze_vision`: Now includes pose jump detection with configurable thresholds
  - `analyze_auto`: Now calculates path following RMSE between desired and actual poses
  - `analyze_cycles`: Now tracks dead time (idle periods) with configurable idle state

### Changed
- **Test Suite**: Added 22 new comprehensive unit tests covering all enhanced functionality
  - StatisticsTools: Percentile interpolation and IQR accuracy tests
  - FrcDomainTools: Stall detection, settling time, overshoot, pose jumps, path following, cycle analysis, loop timing, CAN bus tests
  - CoreTools: New tool tests for `list_struct_types` and `health_check`
  - McpServer: Error classification and "Did You Mean?" suggestion tests
- **Documentation**: Comprehensive updates to README.md and TOOLS.md with detailed usage examples, health assessment criteria, and troubleshooting guides
- **Tool Count**: Increased from 35 to 39 tools

### Performance
- Average tool execution time: 489ms (stress test with real robot logs)
- Concurrent operations: 1000 ops/sec throughput verified
- Cache eviction: LRU policy working correctly with configurable limits

## [0.2.1] - 2026-03-15

### Added
- Struct decoders for vision types (`TargetObservation`, `PoseObservation`)
- Struct decoders for autonomous types (`SwerveSample`)
- Expanded struct type support in LogManager

### Changed
- Enhanced README with additional usage examples
- Updated documentation for struct type decoding

### Fixed
- Team 2363 Triple Helix website link in README

## [0.2.0] - 2026-03-10

### Added
- **`moi_regression` tool**: Mechanism moment of inertia estimation from voltage/velocity/acceleration data
- Support for mechanism characterization and control system identification

### Changed
- Improved mechanism analysis capabilities

## [0.1.0] - 2026-03-01

### Added
- Initial release of wpilog-mcp MCP server
- 35 tools across 8 categories:
  - Core tools for log loading and entry reading
  - Multi-log management
  - Search and query tools
  - Statistical analysis (detect anomalies, find peaks, rate of change, correlation)
  - FRC-specific analysis (swerve, power, CAN health)
  - FRC domain tools (DS timeline, vision, mechanism profiling, auto, cycles, replay drift)
  - The Blue Alliance integration
  - Export tools (CSV, report generation)
- WPILib struct type decoding:
  - Geometry types (Pose2d/3d, Translation2d/3d, Rotation2d/3d, Transform2d/3d, Twist2d/3d)
  - Kinematics types (ChassisSpeeds, SwerveModuleState, SwerveModulePosition)
- LRU cache with configurable limits (by count or memory)
- TBA enrichment for match logs (scores, alliances, win/loss)
- Comprehensive test suite with unit and integration tests
- MCP protocol support via JSON-RPC over stdio

### Documentation
- Complete tool reference (TOOLS.md)
- Usage examples (EXAMPLE.md)
- Configuration guide for VS Code, Claude Code CLI, and Claude Desktop

[0.8.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.7.2...v0.8.0
[0.7.2]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.7.0...v0.7.2
[0.7.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/releases/tag/v0.1.0
