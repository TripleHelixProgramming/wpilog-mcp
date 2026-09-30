# wpilog-mcp Tool Response Reference

Reference for the JSON returned by every tool of **wpilog-mcp 0.8.2**, including the LLM guidance the server attaches to results.

> **Note:** these captures predate the robustness work in [ROBUSTNESS_PLAN.md](ROBUSTNESS_PLAN.md). Since then every
> result carries the result contract (`status`, and `reason`/`looked_for`/`hint`/`inputs`/`skipped`/`limits` where they
> apply — see [TOOLS.md](TOOLS.md#result-contract-success-status-and-related-fields)), and several tools changed shape
> (`get_match_phases`, `get_ds_timeline`, `analyze_auto`, `analyze_can_bus`, `can_health`, and others listed in the
> CHANGELOG). TOOLS.md is current; this file will be regenerated from a checked-in capture harness.

How this file was produced: each of the 45 tools was invoked over the stdio transport against real logs
(Team 2363, VACHE 2026 qualification 10 — `akit_26-03-21_16-29-56_vache_q10.wpilog` — with the REV log
`REV_20260321_162932.revlog` recorded alongside it, plus a second qual, an AdvantageKit `_sim` replay log, and a
2025 non-AdvantageKit log). Responses are reproduced verbatim except that:

- long arrays are cut and the cut is marked with a trailing string `"... (N more items)"`;
- `/Users/<user>/th/riologs` is shown as `<logdir>`, the CSV export directory as `<exportdir>`, and the home directory as `~`.

Shapes that could not be captured live (TBA with a key, loop-timing success, optional sub-objects that this log
does not trigger) are marked **"Shape derived from source"** and use `<placeholder>` values.
Parameter tables come from the server's `tools/list` response. For parameter semantics see [TOOLS.md](TOOLS.md).

## Table of contents

- [Transport envelope](#transport-envelope)
- [Common response fields](#common-response-fields)
- [Error responses](#error-responses)

- [Discovery Tools](#discovery-tools)
  - [`get_server_guide`](#get_server_guide)
  - [`suggest_tools`](#suggest_tools)
- [Core Tools](#core-tools)
  - [`list_available_logs`](#list_available_logs)
  - [`list_loaded_logs`](#list_loaded_logs)
  - [`list_entries`](#list_entries)
  - [`get_entry_info`](#get_entry_info)
  - [`read_entry`](#read_entry)
  - [`list_struct_types`](#list_struct_types)
  - [`health_check`](#health_check)
- [Query Tools](#query-tools)
  - [`search_entries`](#search_entries)
  - [`get_types`](#get_types)
  - [`find_condition`](#find_condition)
  - [`search_strings`](#search_strings)
- [Statistics Tools](#statistics-tools)
  - [`get_statistics`](#get_statistics)
  - [`compare_entries`](#compare_entries)
  - [`detect_anomalies`](#detect_anomalies)
  - [`find_peaks`](#find_peaks)
  - [`rate_of_change`](#rate_of_change)
  - [`time_correlate`](#time_correlate)
- [Robot Analysis Tools](#robot-analysis-tools)
  - [`get_match_phases`](#get_match_phases)
  - [`analyze_swerve`](#analyze_swerve)
  - [`power_analysis`](#power_analysis)
  - [`can_health`](#can_health)
  - [`compare_matches`](#compare_matches)
  - [`get_code_metadata`](#get_code_metadata)
  - [`moi_regression`](#moi_regression)
  - [`analyze_can_bus`](#analyze_can_bus)
- [FRC Domain Tools](#frc-domain-tools)
  - [`get_ds_timeline`](#get_ds_timeline)
  - [`analyze_vision`](#analyze_vision)
  - [`profile_mechanism`](#profile_mechanism)
  - [`analyze_auto`](#analyze_auto)
  - [`analyze_cycles`](#analyze_cycles)
  - [`analyze_replay_drift`](#analyze_replay_drift)
  - [`predict_battery_health`](#predict_battery_health)
  - [`analyze_loop_timing`](#analyze_loop_timing)
  - [`get_game_info`](#get_game_info)
- [Export Tools](#export-tools)
  - [`export_csv`](#export_csv)
  - [`generate_report`](#generate_report)
- [TBA Tools](#tba-tools)
  - [`get_tba_status`](#get_tba_status)
  - [`get_tba_match_data`](#get_tba_match_data)
- [RevLog Tools](#revlog-tools)
  - [`list_revlog_signals`](#list_revlog_signals)
  - [`get_revlog_data`](#get_revlog_data)
  - [`sync_status`](#sync_status)
  - [`set_revlog_offset`](#set_revlog_offset)
  - [`wait_for_sync`](#wait_for_sync)
- [Caveats](#caveats)

## Transport envelope

Every tool result travels inside a standard MCP `tools/call` result. The tool's JSON object is **serialized as a
string** in `content[0].text`; clients must `JSON.parse` it. The server adds `_execution_time_ms` to the object
before serializing.

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "result": {
    "content": [
      { "type": "text", "text": "{\"success\":true,\"count\":42,\"_execution_time_ms\":3}" }
    ]
  }
}
```

The same envelope is used for tool-level failures (`success: false`); `isError` is **not** set on them.
`isError: true` with `{"error": "...", "error_type": "invalid_parameter|io_error|memory_error|internal_error"}`
is only produced if a tool throws past its own error handling, which the shared tool base class prevents in practice.

`initialize` reports `protocolVersion "2025-03-26"`, `serverInfo {name: "wpilog-mcp", version}` and a `tools`
capability only (`prompts/list` and `resources/list` return empty arrays).

## Common response fields

| Field | Present on | Meaning |
|---|---|---|
| `success` | every tool result | `true`/`false`. When `false`, `error` holds the message. |
| `error` | failures | Human-readable message (validation errors, not-found, access denied, "Internal error: ..."). |
| `_execution_time_ms` | every tool result | Added by the transport layer. |
| `warnings[]` | optional | Non-fatal issues. Automatically includes `"Low data quality (score: 0.NN). Results should be treated as preliminary."` whenever `data_quality.quality_score < 0.5`. |
| `_metadata` | optional | Execution metadata (currently only revlog tools: `timing_accuracy_ms`, `confidence_description`). |
| `data_quality` | analytical tools | See below. |
| `server_analysis_directives` | analytical tools | See below. |

### `data_quality`

Computed from the tool's primary time series (which series is noted per tool below).

```json
{
  "sample_count": 11735,
  "time_span_seconds": 336.0,
  "gap_count": 119,
  "max_gap_ms": 14962.6,
  "nan_filtered": 3,
  "effective_sample_rate_hz": 48.7,
  "quality_score": 0.5
}
```

- `gap_count` — intervals > 5× the median sample interval; `max_gap_ms` only when `gap_count > 0`.
- `nan_filtered` — only when > 0.
- `quality_score` = 1 − 0.3·min(gaps/20, 1) − 0.2·(non-finite ratio) − 0.3·(n < 100 ? 1 : n < 500 ? 0.5 : 0) − 0.2·min(jitter/median dt, 1). A single sample scores 0.4; an empty series 0.0.
- Note: a full-match 50 Hz AdvantageKit series typically has 80–160 gaps (disabled periods, log start), so `quality_score` lands at 0.5 and `confidence_level` at `"low"` even for clean data. Treat the score as relative, not absolute.

### `server_analysis_directives`

```json
{
  "confidence_level": "low",
  "sample_context": "Based on 11735 samples over 336.0 seconds",
  "interpretation_guidance": ["..."],
  "suggested_followup": ["..."]
}
```

- `confidence_level` — `"high"` (score > 0.8 and gaps < 10 % of the time span), `"medium"` (> 0.5), `"low"` (> 0.2), `"insufficient"`.
- `interpretation_guidance` / `suggested_followup` are omitted when empty.

Auto-generated guidance strings (exact text, produced from `data_quality`):

| Trigger | String |
|---|---|
| `sample_count < 100` | `Low sample count (N). Statistical measures have high uncertainty.` |
| `gap_count / sample_count > 2 %` | `N data gaps detected (max X.Xms). Trend analysis may be affected by missing data.` |
| `nan_filtered > 0` | `N non-finite values were filtered. This may indicate sensor dropouts or communication errors.` |
| `time_span_seconds < 10` | `Short time span (X.Xs). Results may not be representative of full-match behavior.` |
| most analytical tools | `This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.` |

Tool-specific guidance and follow-up strings (always appended by that tool):

| Tool | `interpretation_guidance` | `suggested_followup` |
|---|---|---|
| `get_statistics` | — | `Use detect_anomalies to check for outliers that may skew these statistics`; `Use time_correlate to check relationships with other entries` |
| `compare_entries` | `RMSE is scale-dependent — compare to the entry's typical range for context` | `Use get_statistics on each entry individually for baseline context` |
| `detect_anomalies` | — | `Use find_peaks if looking for signal extrema rather than statistical outliers` |
| `find_peaks` | — | `Use get_statistics to understand baseline before interpreting peaks` |
| `rate_of_change` | `Derivatives amplify noise — increase window_size for smoother results` | — |
| `time_correlate` | `Correlation does not imply causation — consider confounding variables` | — |
| `analyze_swerve` | — | `Use power_analysis to check if module issues correlate with brownouts` |
| `power_analysis` | — | `Use predict_battery_health for comprehensive battery assessment` |
| `can_health` | `CAN errors while disabled are normal; focus on enabled-state errors` | — |
| `compare_matches` | `Cross-match comparisons require consistent logging configurations for valid comparison` (replaces the single-log caveat) | — |
| `moi_regression` | `Regression estimates depend on data quality and model assumptions` | — |
| `analyze_can_bus` | `Disabled-state CAN timeouts are normal — focus on enabled-state errors` | — |
| `profile_mechanism` | — | `Use moi_regression for mechanism inertia estimation` |
| `predict_battery_health` | `Battery health score is a heuristic — consider battery age and connector condition` | — |
| `analyze_loop_timing` | `Health score is a heuristic based on violation rate — consider context of violations` | — |
| `generate_report` | `Report is a summary — use individual tools for detailed analysis` | — |
| `get_revlog_data` | `Revlog timestamps are synchronized via cross-correlation ...` (with confidence) | — |

Every analytical tool's **description** (visible to the agent in `tools/list`) also carries an
`INTERPRETATION GUIDANCE:` paragraph; it is quoted under each tool below.

## Error responses

Tool-level errors keep the normal envelope with `success: false`:

```json
{ "success": false, "error": "Missing required parameter: path", "_execution_time_ms": 0 }
```

Observed messages:

| Situation | `error` |
|---|---|
| Missing required argument | `Missing required parameter: path` |
| Entry not found (most tools) | `Entry not found: /SystemStats/Battery. Did you mean: /SystemStats/BatteryCurrent, /SystemStats/BatteryVoltage?` |
| Entry not found (`get_entry_info`) | `Entry not found: /SystemStats/Battery` + `suggestions[]` |
| File missing | `Internal error: File not found: <logdir>/nope.wpilog` |
| Path outside allowed directories | `Internal error: Access denied: path is outside configured log directories. Configure allowed directories or use list_available_logs to find valid paths.` |
| Bad operator (`find_condition`) | `Unknown operator: ~. Valid operators: lt, <, lte, <=, gt, >, gte, >=, eq, ==` |
| `analyze_cycles` argument errors | `start_to_start mode requires cycle_start_state`; `start_to_end mode requires both cycle_start_state and cycle_end_state`; `cycle_mode must be 'start_to_start' or 'start_to_end'`; `State entry has no data` |
| `moi_regression` | `Velocity entry not found or empty: ...`; `Current entry not found or empty: ...`; `Applied volts entry not found or empty: ...`; `Too few velocity samples in window: ...`; `Insufficient samples after filtering: ...`; `Singular OLS matrix: α and ω are nearly collinear. ...` |
| `analyze_loop_timing` | `No loop time entry found. Look for entries containing 'LoopTime' or 'loop time'`; `Loop time entry found but has no data`; `No numeric loop time data found` |
| `export_csv` | `Output path not allowed. CSV files can only be written to the configured log directory or system temp directory. Path: ...` |
| `detect_anomalies` | `Not enough data`; `Not enough finite data for IQR calculation` (< 4 finite samples) |
| `get_game_info` | `No game data available for season 2019` + `available_seasons[]` |
| TBA not configured | `TBA API not configured. Set TBA_API_KEY environment variable or use -tba-key argument. Get a free API key at https://www.thebluealliance.com/account` |
| `get_revlog_data` unknown signal | `Signal not found: REV/Nope/Nope. Use list_revlog_signals to see available signals.` |
| `get_revlog_data` no revlog | `No REV log files are synchronized. Place .revlog files in the same directory as the .wpilog file to enable auto-sync.` |
| `set_revlog_offset` no revlog | `No REV log files found for this wpilog. Ensure .revlog files are present in the log directory tree with overlapping timestamps.` |
| `list_available_logs` no directory | `Log directory not configured. Start server with -logdir /path/to/logs` + `hint` |

JSON-RPC-level errors (no `result`) occur for unknown tools/methods and malformed requests:

```json
{ "jsonrpc": "2.0", "id": 11, "error": { "code": -32601, "message": "Unknown tool: no_such_tool" } }
```

Codes: `-32700` parse error, `-32600` invalid request, `-32601` method/tool not found (with "Did you mean" suggestions when close), `-32602` invalid params, `-32603` internal error.


## Discovery Tools


### `get_server_guide`

IMPORTANT: Call this tool first to understand what analysis capabilities are available. Returns a structured overview of all 45 tools organized by category, with usage guidance and anti-patterns to avoid, plus analysis_principles: how to reason about results without confabulating (method, confidence calibration, traps, report format). This server has extensive built-in analysis—don't write custom code when a tool already exists.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `category` | string | no | Filter by category: core, query, statistics, robot_analysis, frc_domain, export, tba, revlog, discovery |
| `include_examples` | boolean | no | Include example use cases for each tool (default: true) |


**Response fields**

- `overview` — `server_name`, `version`, `total_tools`, `purpose`.
- `critical_guidance` — four fixed strings: `primary_rule`, `tba_tip`, `statistics_tip`, `match_phases_tip`.
- `analysis_principles` — general reasoning guidance for the AI agent (static; the same text every call): `purpose`, `answer_first`, `method` (`applies_to`, `steps[]`, `pit_mode`), `calibration` (what `confidence_level` does and does not bound, `cause_confidence`, `never_high_when[]`, `do_not_hedge`), `user_proposed_cause`, `traps[]` (`{trap, fix}` pairs naming the tool call that avoids each), `cross_match[]`, `naming` (AdvantageKit vs WPILib DataLogManager entry names), `units`, `report_format` (`pit`, `deep_dive`, `choose`, `example`). The same guidance is sent in compact form as the MCP `instructions` field on `initialize`; see [TOOLS.md](TOOLS.md#server-instructions).
- `architecture` — `concurrency`, `transports`, `log_loading`.
- `categories[]` — one object per category (all 9 by default; exactly one when `category` is given): `name`, `description`, `anti_pattern`, `tools[]`, `tool_count`. Each tool: `name`, `description`, `requires_log`, `related_tools[]`, and `example_uses[]` unless `include_examples=false`.
- `common_workflows[]` — `{name, steps[]}`.
- The tool's `tools/list` entry carries `_meta: {"anthropic/alwaysLoad": true}` so Claude Code keeps its description loaded when other MCP tools are deferred.
- Static content; no `data_quality`.


**Examples**

**Filtered to one category, no examples**

The default call (no arguments) returns all 9 categories with `example_uses`; trimmed here. `analysis_principles.traps` has 20 entries; only the first few are shown.

Request:
```json
{
  "name": "get_server_guide",
  "arguments": {
    "category": "revlog",
    "include_examples": false
  }
}
```

Response:
```json
{
  "success": true,
  "overview": {
    "server_name": "wpilog-mcp",
    "version": "0.8.2",
    "total_tools": 45,
    "purpose": "Parse and analyze FRC robot telemetry logs (.wpilog) and REV motor controller logs (.revlog)"
  },
  "critical_guidance": {
    "primary_rule": "ALWAYS check for a built-in tool before writing custom analysis code. This server has 45 specialized tools covering statistics, power analysis, swerve diagnostics, cycle detection, battery health prediction, and more.",
    "tba_tip": "To get match scores: call list_available_logs (includes TBA data) or get_tba_match_data. TBA data includes autonomous points, final scores, and win/loss results.",
    "statistics_tip": "NEVER compute mean/std/percentiles manually—use get_statistics. NEVER compute correlation manually—use time_correlate.",
    "match_phases_tip": "NEVER manually parse timestamps to find auto/teleop—use get_match_phases."
  },
  "analysis_principles": {
    "purpose": "How to reason about this server's results without confabulating. Read once per session; apply the method to causal questions and the traps everywhere.",
    "answer_first": "Lead with the answer to the question actually asked, in one or two sentences. Evidence, alternatives, and confidence follow. For lookups they are optional.",
    "method": {
      "applies_to": "Causal or diagnostic questions ('why', 'what caused', 'is X the problem'). A lookup ('what is the loop time', 'what happened at 87s') needs discovery and a direct answer, nothing more.",
      "steps": [
        "Observe: learn what is actually logged (list_entries, search_entries) and where the phases are (get_match_phases). Confirm the event in the question actually occurred (get_ds_timeline, find_condition).",
        "Hypothesize: the candidate cause plus at least one rival. Always include 'normal for this phase or state' and 'logging or timing artifact'; for power questions add 'another load at the same instant'.",
        "Predict before testing: 'if H1, entry E exceeds X during phase P within T of the event; if H2, it does not'. A threshold chosen after seeing the data is not a test; if you change it, say so and why.",
        "Test: one tool call per prediction, scoped with start_time/end_time where the tool accepts them (get_statistics, rate_of_change, time_correlate, read_entry).",
        "... (3 more items)"
      ],
      "pit_mode": "With a match coming up, answer with the fewest tool calls that test the leading cause and one rival, then offer the deeper analysis instead of running it. Run the full loop for post-event analysis or when the answer would change a hardware decision."
    },
    "calibration": {
      "server_confidence_level": "Derived from quality_score, which starts at 1.0 and subtracts up to 0.3 for gaps (intervals over 5x the median sample interval; 20 or more gaps is the full penalty), up to 0.2 for interval jitter (long gaps inflate this too), up to 0.2 for non-finite values, and 0.3 or 0.15 for fewer than 100 or 500 samples. A full-match 50 Hz series with disabled periods typically has 100+ gaps, lands at about 0.5, and reports 'low' even when the data is good. Before quoting it, look at sample_count and gap_count: 11,000 samples with gaps only at disabled transitions is good data.",
      "what_it_bounds": "Claims built on a mean, trend, percentile, correlation, or score. It does not bound claims about discrete events.",
      "discrete_events": "A logged brownout flag, a voltage threshold crossing, a DS disable, a joystick disconnect, an enabled-state CAN error, a current peak above stall: these are observations. State them plainly with the timestamp, even from one match. The cause of the event is a separate claim with its own confidence.",
      "cause_confidence": {
        "high": "the mechanism is visible in the same time window (for example 149 A on the elevator's current entry at the voltage minimum) and no rival survived testing",
        "medium": "consistent with the data, but rivals are untested or the root cause lies outside telemetry",
        "low": "one weak signal, n below 15, or revlog alignment coarser than the interval in question"
      },
      "never_high_when": [
        "n < 100 samples",
        "gaps cover more than 10% of the window",
        "single match and the claim is a pattern or trend",
        "the claim depends on revlog timing with low sync confidence"
      ],
      "do_not_hedge": "Do not attach caveats to facts. 'The log shows a 149 A stall at 87.2 s' needs no qualifier. Reserve hedging for the cause and for generalizations."
    },
    "user_proposed_cause": "Treat it as the first hypothesis. Answer it in the first sentence (yes, no, or cannot tell from this log) with the entry and window that decides it. Do not confirm it from one consistent statistic; do not dismiss it without a tool result. Then name the strongest rival and what the data says about it. Example: a compressor draws a steady 10-20 A and rarely causes a brownout alone; check its channel's peak_current_A in power_analysis channel_analysis, its current around the voltage minimum (read_entry with start_time/end_time on a scalar current entry or on the /PowerDistribution/ChannelCurrent array), and what else was drawing then. A steady load shows near-zero correlation with voltage even when it contributes.",
    "traps": [
      {
        "trap": "Explaining an event that did not happen",
        "fix": "Check first with get_ds_timeline, find_condition, or search_strings. get_ds_timeline names the voltage entry it scanned as brownout_voltage_entry and warns when the log has none; in that case the absence of BROWNOUT events is not evidence, so use power_analysis or find_condition on a voltage entry instead. For console errors, get_ds_timeline gives exact counts (text_event_counts) and a distinct-message summary, never the messages themselves; search_strings with level=error lists them all. If the user's event is absent (no crossing below threshold, no error, no disable), say so and ask what they observed; do not explain a hypothetical."
      },
      {
        "trap": "Naming an entry or quoting a value that no tool returned",
        "fix": "Every entry name must come from list_entries or search_entries; every number from a tool result. If it is not logged, say 'not logged'."
      },
      {
        "trap": "Treating a page or a summary as the whole message log",
        "fix": "search_strings is paged: total_matches is the full count and has_more says whether another page exists, so fetch the next offset before saying how many errors there were or that a message never appeared. get_ds_timeline's text_event_summary groups messages after replacing numbers with #; its variants field says how many distinct raw texts a group covers (device 5 and device 7 are one group with variants 2), so drill into a group with search_strings (regex) before treating it as one issue. Long values are truncated (value_truncated); raise max_value_chars to see the whole text."
      },
      {
        "trap": "Treating one read_entry page or window as the whole signal",
        "fix": "Use get_statistics, find_condition, or find_peaks for whole-window claims. Use read_entry only to inspect a window already located by another tool, and state its bounds."
      },
      "... (17 more items)"
    ],
    "cross_match": [
      "One log is one sample. compare_matches shows that two matches differ, not why.",
      "compare_matches compares one scalar entry across exactly two logs with whole-log min, max, and mean; it has no start_time/end_time and its data_quality reflects only the first log. For phase-scoped or n-bearing comparisons, run get_statistics with the same name and start_time/end_time on each log (windows from get_match_phases per log) and tabulate the results.",
      "Keep only FMS-connected match logs unless asked; exclude practice, pit, and replay (_sim) logs.",
      "Check get_code_metadata on each log; a different git SHA between matches is a confounder for any behavior change. Battery, alliance partners, and field position also change.",
      "... (2 more items)"
    ],
    "naming": {
      "advantagekit": "/SystemStats/BatteryVoltage, /SystemStats/BrownedOut, /PowerDistribution/ChannelCurrent (array), /PowerDistribution/TotalCurrent, /DriverStation/Enabled, /RealOutputs/<Subsystem>/..., /AdvantageKit/...",
      "wpilib_datalog": "DS:enabled, DS:autonomous, DS:test, DS:estop, DS:joystick0/...; NetworkTables entries prefixed NT:/ (for example NT:/SmartDashboard/...); battery and PDH data only if the team logged them (typically NT:/SmartDashboard/PowerDistribution[<CAN id>]/Voltage, TotalCurrent, and per-channel Chan<N>; the id is 1 for a REV PDH, 0 for a CTRE PDP). Phase, DS, and CAN tools recognize both /DriverStation/... and DS:... names; power_analysis and get_ds_timeline both use the PowerDistribution voltage when no BatteryVoltage entry exists.",
      "when_a_tool_finds_nothing": "If analyze_swerve, profile_mechanism, or analyze_cycles reports no matching entries, list the names you searched, run search_entries with the subsystem word (swerve, module, drive, elevator), and ask the user for their naming. Do not reconstruct the analysis from raw entries by hand."
    },
    "units": "Battery voltage in V (12.0-13.2 V at rest is healthy). Currents in A; ChannelCurrent is an array indexed by channel, TotalCurrent is a scalar. analyze_loop_timing auto-detects ms vs s; a 20 ms nominal loop reported as 0.02 is seconds. Tool timestamps are the log's own clock in seconds (FPGA time, which starts at roboRIO boot, so the first sample is usually not at 0); take the real range from get_entry_info time_range_sec or get_match_phases, and do not rebase or convert by hand.",
    "report_format": {
      "pit": "Two to four sentences: what happened (event, time, entry, value); the most likely why, with confidence; the one thing to check before the next match. Caveats only if they change the action.",
      "deep_dive": "For each finding, ranked by evidence strength: Finding; Evidence (log, entry, phase or window, n, statistic); Alternatives considered and the observation that ruled them out; Confidence (high, medium, low) and why; Not determinable from telemetry (what needs physical inspection, code review, or another match); Next measurement.",
      "choose": "Use pit when the user mentions an upcoming match, time pressure, or asks a single yes/no question; otherwise deep_dive.",
      "example": "Finding: 4 of the 6 worst voltage events involved a 149 A stall on the intake or climber. Evidence: battery voltage crossings below 6.8 V from find_condition, per-channel peaks from power_analysis channel_analysis, intake current from read_entry in a 2 s window around each crossing, teleop only, n = 6 events. Alternatives: battery age (ruled out: voltage recovers fully between events); wiring (not testable from logs). Confidence: high that the stalls occur, the events are unambiguous; medium on cause, telemetry cannot distinguish mechanical binding from a control-loop issue. Not determinable from telemetry: the root cause of the stall; needs physical inspection. Next: get_statistics with the teleop window on the same entries in the other logs from this event."
    }
  },
  "architecture": {
    "concurrency": "Thread-safe. Concurrent tool calls from multiple sessions are supported. The log cache, session management, and all shared state use concurrent data structures.",
    "transports": "Stdio transport (single-client, sequential) and HTTP Streamable transport (multi-client, concurrent).",
    "log_loading": "Logs are loaded on demand when referenced by path. No 'active log' concept — each tool call is self-contained. Idle logs are evicted after 30 minutes. Under heap pressure, least-recently-used logs are evicted automatically."
  },
  "categories": [
    {
      "name": "revlog",
      "description": "REV Hardware Client log analysis with synchronized timestamps.",
      "anti_pattern": "Don't manually align REV timestamps—synchronization is automatic.",
      "tools": [
        {
          "name": "list_revlog_signals",
          "description": "List available REV motor controller signals",
          "requires_log": true,
          "related_tools": [
            "get_revlog_data",
            "sync_status"
          ]
        },
        {
          "name": "get_revlog_data",
          "description": "Query REV signal data with synchronized timestamps",
          "requires_log": true,
          "related_tools": [
            "list_revlog_signals"
          ]
        },
        {
          "name": "sync_status",
          "description": "Get REV log synchronization confidence and details",
          "requires_log": true,
          "related_tools": [
            "list_revlog_signals"
          ]
        },
        {
          "name": "set_revlog_offset",
          "description": "Manually set the REV log time offset",
          "requires_log": true,
          "related_tools": [
            "sync_status",
            "list_revlog_signals"
          ]
        },
        "... (1 more items)"
      ],
      "tool_count": 5
    }
  ],
  "common_workflows": [
    {
      "name": "Basic Match Analysis",
      "steps": [
        "1. list_available_logs - See available logs with TBA match data",
        "2. list_entries - Discover available telemetry (pass log path)",
        "3. get_match_phases - Find auto/teleop timing",
        "4. generate_report - Get overview of match health",
        "... (1 more items)"
      ]
    },
    {
      "name": "Cycle Time Analysis",
      "steps": [
        "1. list_available_logs - Find available logs",
        "2. analyze_cycles - Detect and measure cycle times (pass log path)",
        "3. get_tba_match_data - Verify against actual scored points",
        "4. compare_matches - Compare cycle times across matches"
      ]
    },
    {
      "name": "Power/Brownout Investigation",
      "steps": [
        "1. list_available_logs - Find available logs",
        "2. power_analysis - Check for brownouts and current peaks (pass log path)",
        "3. find_condition - Find exact timestamps of voltage drops",
        "4. predict_battery_health - Assess battery condition",
        "... (1 more items)"
      ]
    }
  ],
  "_execution_time_ms": 1
}
```


### `suggest_tools`

Given a natural language description of what you want to analyze, this tool recommends the most relevant tools and provides a suggested workflow. Use this when unsure which tools to use for a specific analysis task.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `task` | string | yes | Natural language description of what you want to analyze (e.g., 'check why our auto was inconsistent' or 'investigate brownout during teleop') |
| `max_suggestions` | integer | no | Maximum number of tools to suggest (default: 5) |


**Response fields**

- `task` — the request echoed back, lower-cased.
- `suggestions[]` — `{tool, description, relevance_score (int, keyword hits), category, example_uses[], related_tools[]}` ordered by relevance; `suggestion_count`.
- `suggested_workflow[]` — numbered step strings; `anti_patterns[]` — strings.


**Examples**

**Brownout question**

Request:
```json
{
  "name": "suggest_tools",
  "arguments": {
    "task": "Why did we brownout during teleop?"
  }
}
```

Response:
```json
{
  "success": true,
  "task": "why did we brownout during teleop?",
  "suggestions": [
    {
      "tool": "power_analysis",
      "description": "Analyze battery voltage and current distribution",
      "relevance_score": 5,
      "category": "robot_analysis",
      "example_uses": [
        "Check for brownouts",
        "Find peak current draw",
        "Analyze battery health"
      ],
      "related_tools": [
        "predict_battery_health",
        "can_health"
      ]
    },
    {
      "tool": "get_match_phases",
      "description": "Detect match phases (auto/teleop) from DriverStation data",
      "relevance_score": 2,
      "category": "robot_analysis",
      "example_uses": [
        "Find auto start/end times",
        "Get teleop duration",
        "Detect match structure"
      ],
      "related_tools": [
        "analyze_auto",
        "get_ds_timeline"
      ]
    },
    "... (1 more items)"
  ],
  "suggestion_count": 3,
  "suggested_workflow": [
    "1. list_available_logs - Find available logs (includes TBA match data)",
    "2. power_analysis - Analyze battery voltage and current distribution",
    "3. get_match_phases - Detect match phases (auto/teleop) from DriverStation data",
    "4. get_tba_match_data - Query match scores and results from The Blue Alliance"
  ],
  "anti_patterns": [
    "Don't manually parse timestamps for match phases—use get_match_phases",
    "Don't manually check voltage thresholds—use power_analysis"
  ],
  "_execution_time_ms": 5
}
```


## Core Tools


### `list_available_logs`

List WPILOG files available in the configured log directory with friendly names. IMPORTANT: When TBA is configured, this tool automatically enriches each log with match data including alliance scores, win/loss results, and actual match times. Check the 'tba' field in each log entry for match outcomes—don't guess from telemetry! Use this tool first to find logs and get match results, then pass the path to other tools.

**Parameters**

_No parameters._


**Response fields**

- `log_directory`, `log_count`, `metadata_cache` `{misses, hits, size}` (per-file metadata cache stats).
- `logs[]` sorted newest first: `friendly_name`, `path`, `filename`, `size_bytes`, `last_modified` (epoch **milliseconds**), plus `event`, `match_type` (`Practice` / `Qualification` / `Elimination`), `match_number`, `team_number` — each only when the log's DriverStation/FMS metadata provides it.
- When TBA is configured: top-level `tba_enrichment: true`, and eligible logs (event + match type + number + team) gain a `tba` object: `{team_number, alliance, score, won?, actual_time?, actual_time_local?, scheduled_time?, scheduled_time_local?, opponent_score?}`.
- If no log directory is configured the tool returns `success: false` with `error` and `hint` fields.


**Examples**

**Default (TBA not configured)**

Request:
```json
{
  "name": "list_available_logs",
  "arguments": {}
}
```

Response:
```json
{
  "success": true,
  "log_directory": "<logdir>",
  "log_count": 86,
  "metadata_cache": {
    "misses": 86,
    "hits": 0,
    "size": 86
  },
  "logs": [
    {
      "friendly_name": "VACHE Elimination 8",
      "path": "<logdir>/vache/session_57/akit_26-03-22_18-52-56_vache_e8.wpilog",
      "filename": "akit_26-03-22_18-52-56_vache_e8.wpilog",
      "event": "VACHE",
      "match_type": "Elimination",
      "match_number": 8,
      "team_number": 2363,
      "size_bytes": 38551552,
      "last_modified": 1774220356000
    },
    {
      "friendly_name": "VACHE Practice",
      "path": "<logdir>/vache/session_56/akit_26-03-22_18-44-53_vache.wpilog",
      "filename": "akit_26-03-22_18-44-53_vache.wpilog",
      "event": "VACHE",
      "match_type": "Practice",
      "team_number": 2363,
      "size_bytes": 1785856,
      "last_modified": 1774219502000
    },
    "... (84 more items)"
  ],
  "_execution_time_ms": 108
}
```

**Per-log `tba` object when TBA is configured**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "friendly_name": "VACHE Qualification 10",
  "path": "<logdir>/.../akit_..._vache_q10.wpilog",
  "filename": "akit_..._vache_q10.wpilog",
  "event": "VACHE",
  "match_type": "Qualification",
  "match_number": 10,
  "team_number": 2363,
  "size_bytes": "<int>",
  "last_modified": "<epoch ms>",
  "tba": {
    "team_number": 2363,
    "alliance": "blue",
    "score": "<int>",
    "won": "<bool>",
    "actual_time": "<epoch s>",
    "actual_time_local": "<string>",
    "scheduled_time": "<epoch s>",
    "scheduled_time_local": "<string>",
    "opponent_score": "<int>"
  }
}
```


### `list_loaded_logs`

List all currently cached log files and cache status.

**Parameters**

_No parameters._


**Response fields**

- `loaded_count`, `logs[]` of `{path}` for logs currently held in the in-memory cache (logs load on demand and are evicted when idle or under heap pressure).


**Examples**

**One log cached**

Request:
```json
{
  "name": "list_loaded_logs",
  "arguments": {}
}
```

Response:
```json
{
  "success": true,
  "loaded_count": 1,
  "logs": [
    {
      "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
    }
  ],
  "_execution_time_ms": 0
}
```


### `list_entries`

List all entries in a log file. Returns log metadata (time range, duration, truncation status) and entry list with types and sample counts. Optionally filter by name pattern.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pattern` | string | no | Optional pattern to filter entry names (substring match) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `log_path`, `entry_count` (after filtering), `time_range_sec` `{start, end, duration}`, `entries[]` of `{name, type, sample_count}`.
- `type` is the WPILOG type string (`double`, `boolean`, `int64`, `string`, `double[]`, `struct:Pose2d`, `struct:SwerveModuleState[]`, `structschema`, ...).
- If the log was truncated at parse time, adds `truncated: true` and `warning` (message).
- `pattern` is a case-insensitive substring match on the entry name.


**Examples**

**Filtered by pattern**

Request:
```json
{
  "name": "list_entries",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "pattern": "Module0"
  }
}
```

Response:
```json
{
  "success": true,
  "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
  "entry_count": 18,
  "time_range_sec": {
    "start": 11.897573,
    "end": 347.901903,
    "duration": 336.00433
  },
  "entries": [
    {
      "name": "/Drive/Module0/DriveAppliedVolts",
      "type": "double",
      "sample_count": 6063
    },
    {
      "name": "/Drive/Module0/DriveConnected",
      "type": "boolean",
      "sample_count": 1
    },
    {
      "name": "/Drive/Module0/DriveCurrentAmps",
      "type": "double",
      "sample_count": 12869
    },
    "... (15 more items)"
  ],
  "_execution_time_ms": 173
}
```


### `get_entry_info`

Get detailed information about a specific entry including metadata and sample values.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | The entry name (e.g., '/Vision/Summary/ObservationScore') |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `name`, `type`, `metadata` (the raw metadata **string** from the log, often JSON text such as `{"source":"AdvantageKit"}`), `sample_count`, `time_range_sec` `{start, end}`, `sample_values[]` — first, middle and last sample as `{timestamp_sec, value}`.
- Struct values are decoded into objects (e.g. `Pose2d` → `{x, y, rotation_rad, rotation_deg}`).
- Not-found response is tool-specific: `success: false`, `error`, and a `suggestions[]` array of similar entry names (other tools put the suggestions inside the error string instead).


**Examples**

**Numeric entry**

Request:
```json
{
  "name": "get_entry_info",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage"
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/SystemStats/BatteryVoltage",
  "type": "double",
  "metadata": "{\"source\":\"AdvantageKit\"}",
  "sample_count": 11735,
  "time_range_sec": {
    "start": 11.897573,
    "end": 347.901903
  },
  "sample_values": [
    {
      "timestamp_sec": 11.897573,
      "value": 12.5108115234375
    },
    {
      "timestamp_sec": 201.438134,
      "value": 9.168537109375
    },
    {
      "timestamp_sec": 347.901903,
      "value": 12.27207763671875
    }
  ],
  "_execution_time_ms": 6
}
```

**Struct entry**

Request:
```json
{
  "name": "get_entry_info",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/RealOutputs/Drive/Pose"
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/RealOutputs/Drive/Pose",
  "type": "struct:Pose2d",
  "metadata": "{\"source\":\"AdvantageKit\"}",
  "sample_count": 13357,
  "time_range_sec": {
    "start": 11.897573,
    "end": 347.901903
  },
  "sample_values": [
    {
      "timestamp_sec": 11.897573,
      "value": {
        "rotation_rad": 0.0,
        "y": 0.0,
        "x": 0.0,
        "rotation_deg": 0.0
      }
    },
    {
      "timestamp_sec": 200.381897,
      "value": {
        "rotation_rad": 0.07303924887469634,
        "y": -1.7354588860480624,
        "x": 11.748853382519204,
        "rotation_deg": 4.184840699325748
      }
    },
    {
      "timestamp_sec": 347.901903,
      "value": {
        "rotation_rad": 2.0485879930763566,
        "y": -3.2811841373787414,
        "x": -4.9938413018507,
        "rotation_deg": 117.37544596445075
      }
    }
  ],
  "_execution_time_ms": 11
}
```

**Entry not found**

Request:
```json
{
  "name": "get_entry_info",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/Battery"
  }
}
```

Response:
```json
{
  "success": false,
  "error": "Entry not found: /SystemStats/Battery",
  "suggestions": [
    "/SystemStats/BatteryCurrent",
    "/SystemStats/BatteryVoltage"
  ],
  "_execution_time_ms": 160
}
```


### `read_entry`

Read values from an entry. Supports time range filtering and pagination.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | The entry name |
| `start_time` | number | no | Start timestamp in seconds (optional) |
| `end_time` | number | no | End timestamp in seconds (optional) |
| `limit` | integer | no | Maximum number of samples to return (default: `100`) |
| `offset` | integer | no | Number of samples to skip (default: `0`) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `name`, `type`, `total_in_range` (samples inside `start_time`/`end_time` before paging), `returned_count`, `offset`, `limit`, `samples[]` of `{timestamp_sec, value}`.
- `value` is typed by entry: number, boolean, string, array (`double[]`, `boolean[]`, `string[]`, ...), decoded struct object, or array of struct objects.
- Page with `offset`/`limit`; default limit 100.


**Examples**

**Numeric with time filter and paging**

Request:
```json
{
  "name": "read_entry",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "start_time": 120,
    "limit": 5
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/SystemStats/BatteryVoltage",
  "type": "double",
  "total_in_range": 9019,
  "returned_count": 5,
  "offset": 0,
  "limit": 5,
  "samples": [
    {
      "timestamp_sec": 120.071675,
      "value": 11.624983154296874
    },
    {
      "timestamp_sec": 120.148045,
      "value": 10.374771484375
    },
    "... (3 more items)"
  ],
  "_execution_time_ms": 1
}
```

**Struct entry**

Request:
```json
{
  "name": "read_entry",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/RealOutputs/Drive/Pose",
    "start_time": 120,
    "limit": 2
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/RealOutputs/Drive/Pose",
  "type": "struct:Pose2d",
  "total_in_range": 9888,
  "returned_count": 2,
  "offset": 0,
  "limit": 2,
  "samples": [
    {
      "timestamp_sec": 120.071675,
      "value": {
        "rotation_rad": -0.11761324078747383,
        "y": 0.5862099053708911,
        "x": 4.7335431866539235,
        "rotation_deg": -6.738742311978162
      }
    },
    {
      "timestamp_sec": 120.148045,
      "value": {
        "rotation_rad": -0.13213078648754706,
        "y": 0.6071620437374313,
        "x": 4.544850002970969,
        "rotation_deg": -7.5705364094806535
      }
    }
  ],
  "_execution_time_ms": 2
}
```

**Array entry**

Request:
```json
{
  "name": "read_entry",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/PowerDistribution/ChannelCurrent",
    "start_time": 140,
    "limit": 2
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/PowerDistribution/ChannelCurrent",
  "type": "double[]",
  "total_in_range": 704,
  "returned_count": 2,
  "offset": 0,
  "limit": 2,
  "samples": [
    {
      "timestamp_sec": 140.573413,
      "value": [
        0.0,
        0.0,
        "... (22 more items)"
      ]
    },
    {
      "timestamp_sec": 140.631512,
      "value": [
        0.0,
        0.0,
        "... (22 more items)"
      ]
    }
  ],
  "_execution_time_ms": 2
}
```

**String entry**

Request:
```json
{
  "name": "read_entry",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/RealOutputs/GameState/CurrentPhase",
    "limit": 5
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/RealOutputs/GameState/CurrentPhase",
  "type": "string",
  "total_in_range": 8,
  "returned_count": 5,
  "offset": 0,
  "limit": 5,
  "samples": [
    {
      "timestamp_sec": 26.860151,
      "value": "Autonomous"
    },
    {
      "timestamp_sec": 135.642118,
      "value": "Transition"
    },
    {
      "timestamp_sec": 146.137078,
      "value": "Shift1"
    },
    "... (2 more items)"
  ],
  "_execution_time_ms": 0
}
```


### `list_struct_types`

List all supported struct types for decoding.

**Parameters**

_No parameters._


**Response fields**

- `struct_types` — object keyed by family (`geometry`, `kinematics`, `vision`) with arrays of supported WPILib struct names. Static content.


**Examples**

**Full response**

Request:
```json
{
  "name": "list_struct_types",
  "arguments": {}
}
```

Response:
```json
{
  "success": true,
  "struct_types": {
    "geometry": [
      "Pose2d",
      "Pose3d",
      "Translation2d",
      "Translation3d",
      "Rotation2d",
      "Rotation3d",
      "Transform2d",
      "Transform3d",
      "Twist2d",
      "Twist3d"
    ],
    "kinematics": [
      "ChassisSpeeds",
      "SwerveModuleState",
      "SwerveModulePosition"
    ],
    "vision": [
      "TargetObservation",
      "PoseObservation",
      "SwerveSample"
    ]
  },
  "_execution_time_ms": 0
}
```


### `health_check`

Verify server is working correctly and get system status.

**Parameters**

_No parameters._


**Response fields**

- `status` (`"OK"`), `server_version`, `loaded_logs` (count), `tba_available`, `revlog_sync_in_progress`.
- `jvm_memory` `{used_mb, total_mb, max_mb, free_mb}`, `jvm_heap_used_mb` (estimated bytes held by loaded logs).
- `disk_cache` `{enabled, directory, cached_files, total_size_mb, format_version}`; `directory`/`cached_files`/`total_size_mb` appear only when enabled, and an `error` string replaces the size fields if the directory cannot be scanned.


**Examples**

**Idle server**

Request:
```json
{
  "name": "health_check",
  "arguments": {}
}
```

Response:
```json
{
  "success": true,
  "status": "OK",
  "server_version": "0.8.2",
  "loaded_logs": 0,
  "tba_available": false,
  "revlog_sync_in_progress": false,
  "jvm_memory": {
    "used_mb": 24,
    "total_mb": 770,
    "max_mb": 4096,
    "free_mb": 745
  },
  "jvm_heap_used_mb": 24,
  "disk_cache": {
    "enabled": true,
    "directory": "~/Library/Application Support/wpilog-mcp/cache",
    "cached_files": 100,
    "total_size_mb": 1235,
    "format_version": 3
  },
  "_execution_time_ms": 2
}
```


## Query Tools


### `search_entries`

Search for entries matching various criteria.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `type` | string | no | Filter by type (substring match, e.g., 'Pose3d') |
| `pattern` | string | no | Filter by name containing this string |
| `min_samples` | integer | no | Minimum number of samples required |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `match_count`, `matches[]` — entry **names only** (strings). Filters: `type` (case-sensitive substring of the type string), `pattern` (case-insensitive substring of the name), `min_samples`.


**Examples**

**Type + pattern + min samples**

Request:
```json
{
  "name": "search_entries",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "type": "double",
    "pattern": "Module0",
    "min_samples": 100
  }
}
```

Response:
```json
{
  "success": true,
  "match_count": 9,
  "matches": [
    "/Drive/Module0/DriveAppliedVolts",
    "/Drive/Module0/DriveCurrentAmps",
    "/Drive/Module0/DrivePositionRad",
    "... (6 more items)"
  ],
  "_execution_time_ms": 26
}
```


### `get_types`

Get all data types used in the log file and which entries use each type.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `type_count`, `types[]` of `{type, entry_count, entries[]}` (entry names), sorted by type name.


**Examples**

**All types**

Request:
```json
{
  "name": "get_types",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "type_count": 25,
  "types": [
    {
      "type": "boolean",
      "entry_count": 98,
      "entries": [
        "/AllianceSelector/AgreementInAllianceInputs",
        "/AllianceSelector/AllianceChanged",
        "... (96 more items)"
      ]
    },
    {
      "type": "boolean[]",
      "entry_count": 2,
      "entries": [
        "/RealOutputs/HID/Port0/Buttons",
        "/RealOutputs/HID/Port1/Buttons"
      ]
    },
    "... (23 more items)"
  ],
  "_execution_time_ms": 3
}
```


### `find_condition`

Find timestamps where a numeric entry crosses a threshold. Useful for questions like 'When did battery voltage drop below 11V?' Returns transition points where the condition first becomes true.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name (e.g., /Robot/BatteryVoltage) |
| `operator` | string | yes | Comparison operator: lt (<), lte (<=), gt (>), gte (>=), eq (==) |
| `threshold` | number | yes | Threshold value to compare against |
| `limit` | integer | no | Maximum number of transitions to return (default: `100`) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `name`, `condition` (human-readable, e.g. `"/SystemStats/BatteryVoltage < 10.5"`), `transition_count`, `transitions[]` of `{timestamp_sec, value}`.
- Each transition is the first sample where the condition **becomes** true (edge-triggered, not every sample).
- Operators accept both word and symbol forms: `lt`/`<`, `lte`/`<=`, `gt`/`>`, `gte`/`>=`, `eq`/`==`. Unknown operator → `success: false` error listing the valid ones.


**Examples**

**Voltage below 10.5 V**

Request:
```json
{
  "name": "find_condition",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "operator": "<",
    "threshold": 10.5,
    "limit": 5
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/SystemStats/BatteryVoltage",
  "condition": "/SystemStats/BatteryVoltage < 10.5",
  "transition_count": 5,
  "transitions": [
    {
      "timestamp_sec": 111.487347,
      "value": 8.8418486328125
    },
    {
      "timestamp_sec": 112.139732,
      "value": 8.8921083984375
    },
    "... (3 more items)"
  ],
  "_execution_time_ms": 2
}
```

**Bad operator**

Request:
```json
{
  "name": "find_condition",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "operator": "~",
    "threshold": 1
  }
}
```

Response:
```json
{
  "success": false,
  "error": "Unknown operator: ~. Valid operators: lt, <, lte, <=, gt, >, gte, >=, eq, ==",
  "_execution_time_ms": 5
}
```


### `search_strings`

List or search the text logged in string entries (console output, alerts, messages), completely and in time order across all entries. Filters: pattern (case-insensitive substring, or a regex with regex=true), level (error, warning, or any; classified exactly as get_ds_timeline counts them), entry_pattern, start_time/end_time. Results are paged: total_matches is the full count, offset/limit select a page, has_more says whether more remain, so nothing is silently dropped. collapse_repeats folds runs of identical samples that are adjacent in the same entry into one match with repeat_count. Each match carries its level and the matching line (line is cut at 200 chars; value at max_value_chars, with *_truncated flags). Regex mode is case-insensitive with ^/$ anchoring to lines; a pattern that backtracks for more than a second is rejected.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pattern` | string | no | Text to search for: case-insensitive substring, or a regular expression when regex=true. Omit to list every string sample (use level/entry_pattern/time window to narrow). |
| `regex` | boolean | no | Treat pattern as a Java regular expression (case-insensitive). Default: false |
| `level` | string | no | Only messages classified as 'error' or 'warning' (same rules as get_ds_timeline), or 'any' (default) |
| `entry_pattern` | string | no | Optional: filter which entries to search (e.g., 'Console' or 'Output') |
| `start_time` | number | no | Start timestamp in seconds (optional) |
| `end_time` | number | no | End timestamp in seconds (optional) |
| `offset` | integer | no | Number of matches to skip, for paging (default: 0) |
| `limit` | integer | no | Maximum matches to return per call (default: 100, max: 1000) |
| `collapse_repeats` | boolean | no | Fold runs of identical samples that are adjacent in the same entry's stream into one match with repeat_count and last_timestamp_sec; any other sample in between (even one the filters exclude) ends the run. Default: false |
| `max_value_chars` | integer | no | Truncate each returned value to this many characters (default: 500) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- Complete and paged: `total_matches` (full count before paging; `total_after_collapse` with `collapse_repeats`), `offset`, `limit`, `returned` (= `match_count`), `has_more`.
- `matches[]` sorted by time across all string entries: `{timestamp_sec, entry, level? ("error"/"warning", classified exactly as get_ds_timeline counts them), line (the matching line, or the classified/first line without a pattern; cut at 200 chars with line_truncated: true), value (cut at max_value_chars with value_truncated: true), repeat_count?, last_timestamp_sec?}`. `collapse_repeats` folds only samples adjacent in the same entry's stream.
- Filters: `pattern` (case-insensitive substring, or a regex with `regex: true`; omit to list everything), `level`, `entry_pattern`, `start_time`/`end_time`. `pattern`, `regex`, and `level` are echoed.


**Examples**

**Every error in the log**

`level="error"` with no pattern: the four in-match errors, complete (`has_more: false`), in time order. `text_event_counts.error` from `get_ds_timeline` is 4 for the same log.

Request:
```json
{
  "name": "search_strings",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "level": "error"
  }
}
```

Response:
```json
{
  "success": true,
  "regex": false,
  "level": "error",
  "total_matches": 5,
  "offset": 0,
  "limit": 100,
  "returned": 5,
  "match_count": 5,
  "has_more": false,
  "matches": [
    {
      "timestamp_sec": 123.711426,
      "entry": "/RealOutputs/Console",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out",
      "value": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
    },
    {
      "timestamp_sec": 124.739764,
      "entry": "/RealOutputs/Console",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out",
      "value": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
    },
    {
      "timestamp_sec": 143.156946,
      "entry": "/RealOutputs/Console",
      "level": "error",
      "line": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL not found on NetworkTables. Double check that your camera ...",
      "line_truncated": true,
      "value": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL not found on NetworkTables. Double check that your camera names match!\n\tat org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512)"
    },
    {
      "timestamp_sec": 143.306515,
      "entry": "/RealOutputs/Console",
      "level": "error",
      "line": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:525): Found the following PhotonVision cameras on NetworkTables:",
      "value": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:525): Found the following PhotonVision cameras on NetworkTables:\n ==> OV2311_TH_2026_RR\n ==> OV2311_TH_2026_FR"
    },
    {
      "timestamp_sec": 143.370011,
      "entry": "/RealOutputs/Console",
      "level": "error",
      "line": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_RL not found on NetworkTables. Double check that your camera ...",
      "line_truncated": true,
      "value": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:545): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL has not reported a message interface UUID - is your coprocessor's camera started?\nCommandScheduler loop overrun\nError at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_RL not found on NetworkTables. Double check that your camera names match!\n\tat org.photonvision.PhotonCame...",
      "value_truncated": true
    }
  ],
  "_execution_time_ms": 3
}
```

**Regex with paging**

A regex over the PhotonVision lines, three per page; `total_matches` is the full count and `has_more` says to fetch `offset: 3` next.

Request:
```json
{
  "name": "search_strings",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "pattern": "PhotonCamera\\.(verifyVersion|checkTimeSyncOrWarn)",
    "regex": true,
    "collapse_repeats": true,
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "pattern": "PhotonCamera\\.(verifyVersion|checkTimeSyncOrWarn)",
  "regex": true,
  "level": "any",
  "total_matches": 10,
  "total_after_collapse": 10,
  "offset": 0,
  "limit": 3,
  "returned": 3,
  "match_count": 3,
  "has_more": true,
  "matches": [
    {
      "timestamp_sec": 127.889981,
      "entry": "/RealOutputs/Console",
      "level": "warning",
      "line": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:533): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL is not sending new data.",
      "value": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:533): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL is not sending new data."
    },
    {
      "timestamp_sec": 128.007662,
      "entry": "/RealOutputs/Console",
      "level": "warning",
      "line": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:545): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL has not reported a message interface UUID - is your copr...",
      "line_truncated": true,
      "value": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:545): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL has not reported a message interface UUID - is your coprocessor's camera started?\nCommandScheduler loop overrun"
    },
    {
      "timestamp_sec": 128.131112,
      "entry": "/RealOutputs/Console",
      "level": "warning",
      "line": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:545): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_RL has not reported a message interface UUID - is your copr...",
      "line_truncated": true,
      "value": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:545): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_RL has not reported a message interface UUID - is your coprocessor's camera started?"
    }
  ],
  "_execution_time_ms": 4
}
```

**A later page of warnings**

Paging deep into the 622 warnings. Trimmed.

Request:
```json
{
  "name": "search_strings",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "level": "warning",
    "limit": 2,
    "offset": 200
  }
}
```

Response:
```json
{
  "success": true,
  "regex": false,
  "level": "warning",
  "total_matches": 621,
  "offset": 200,
  "limit": 2,
  "returned": 2,
  "match_count": 2,
  "has_more": true,
  "matches": [
    {
      "timestamp_sec": 123.131145,
      "entry": "/RealOutputs/Console",
      "level": "warning",
      "line": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tSmartDashboard.updateValues(): 0.000185s",
      "value": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tSmartDashboard.updateValues(): 0.000185s"
    },
    {
      "timestamp_sec": 123.268582,
      "entry": "/RealOutputs/Console",
      "level": "warning",
      "line": "Warning at edu.wpi.first.wpilibj.IterativeRobotBase.printLoopOverrunMessage(IterativeRobotBase.java:436): Loop time of 0.02s overrun",
      "value": "Warning at edu.wpi.first.wpilibj.IterativeRobotBase.printLoopOverrunMessage(IterativeRobotBase.java:436): Loop time of 0.02s overrun"
    }
  ],
  "_execution_time_ms": 1
}
```


## Statistics Tools


### `get_statistics`

BUILT-IN statistics: Get min, max, mean, median, std_dev, percentiles for a numeric entry. NEVER compute these manually—always use this tool! Supports optional time range filtering (start_time, end_time). Includes data quality metrics and sample size for confidence assessment.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | The entry name |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `name`, `count`, `min`, `max`, `mean`, `median`, `std_dev` (sample, n−1), `q1`, `q3`, `iqr`, `p5`, `p95`.
- Non-finite values are excluded before computing. Includes `data_quality` + `server_analysis_directives` (see [Common fields](#common-response-fields)); `suggested_followup` always lists `detect_anomalies` and `time_correlate`.


**Examples**

**Full log**

Request:
```json
{
  "name": "get_statistics",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage"
  }
}
```

Response:
```json
{
  "success": true,
  "name": "/SystemStats/BatteryVoltage",
  "count": 11735,
  "min": 6.680678710937499,
  "max": 12.843782470703125,
  "mean": 10.647124485941994,
  "median": 11.0281484375,
  "std_dev": 1.750828844560013,
  "q1": 8.989486694335938,
  "q3": 12.265795166015625,
  "iqr": 3.2763084716796875,
  "p5": 7.765661401367187,
  "p95": 12.5108115234375,
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use detect_anomalies to check for outliers that may skew these statistics",
      "Use time_correlate to check relationships with other entries"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 10
}
```


### `compare_entries`

Compare two numeric entries using RMSE and max difference.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name1` | string | yes | First entry |
| `name2` | string | yes | Second entry |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `rmse`, `max_difference` — computed over `name1`'s timestamps with `name2` sampled at those times.
- `data_quality` is computed from **`name1`** only (note the example: `/PowerDistribution/Voltage` has a single sample, so quality is 0.4).
- If either entry is non-numeric the server emits `"rmse": NaN` (Gson lenient output — this is not strict JSON; see [Caveats](#caveats)).
- Guidance always adds "RMSE is scale-dependent — compare to the entry's typical range for context" and follow-up `get_statistics`.


**Examples**

**Two voltage entries**

Request:
```json
{
  "name": "compare_entries",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name1": "/PowerDistribution/Voltage",
    "name2": "/SystemStats/BatteryVoltage"
  }
}
```

Response:
```json
{
  "success": true,
  "rmse": 12.5108115234375,
  "max_difference": 12.5108115234375,
  "data_quality": {
    "sample_count": 1,
    "time_span_seconds": 0.0,
    "gap_count": 0,
    "effective_sample_rate_hz": 0.0,
    "quality_score": 0.4
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 1 samples over 0.0 seconds",
    "interpretation_guidance": [
      "Low sample count (1). Statistical measures have high uncertainty.",
      "Short time span (0.0s). Results may not be representative of full-match behavior.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "RMSE is scale-dependent — compare to the entry's typical range for context"
    ],
    "suggested_followup": [
      "Use get_statistics on each entry individually for baseline context"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.40). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 3
}
```


### `detect_anomalies`

Detect anomalies (outliers) in numeric data using the IQR method. Finds values that fall outside 1.5*IQR from Q1/Q3, or sudden spikes/drops.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name |
| `iqr_multiplier` | number | no | IQR multiplier (default 1.5) |
| `spike_threshold` | number | no | Spike percentage threshold |
| `limit` | integer | no | Max anomalies to return (default: `50`) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `anomaly_count`, `non_finite_count` (NaN/Infinity samples skipped), `anomalies[]` of `{timestamp_sec, value, type}` where `type` is `"below_lower_bound"` or `"above_upper_bound"` (IQR fences at `q1 − k·IQR` / `q3 + k·IQR`, `k = iqr_multiplier`).
- `spike_threshold` is accepted by the schema but **not used** by the current implementation (only the IQR test runs). `limit` caps the array (default 50). Fewer than 4 finite samples → `success: false` (`Not enough finite data for IQR calculation`). Includes `data_quality` + `server_analysis_directives` (see [Common fields](#common-response-fields)).


**Examples**

**With outliers**

Request:
```json
{
  "name": "detect_anomalies",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryCurrent",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "anomaly_count": 3,
  "non_finite_count": 0,
  "anomalies": [
    {
      "timestamp_sec": 136.409309,
      "value": 0.7486469726562501,
      "type": "above_upper_bound"
    },
    {
      "timestamp_sec": 137.115378,
      "value": 0.6571127929687501,
      "type": "above_upper_bound"
    },
    {
      "timestamp_sec": 137.134002,
      "value": 0.6446308593750001,
      "type": "above_upper_bound"
    }
  ],
  "data_quality": {
    "sample_count": 11149,
    "time_span_seconds": 336.0,
    "gap_count": 126,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.3,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11149 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use find_peaks if looking for signal extrema rather than statistical outliers"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 179
}
```


### `find_peaks`

Find local maxima and minima (peaks and valleys) in numeric data.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name |
| `type` | string | no | Type: 'max', 'min', or 'both' |
| `min_height_diff` | number | no | Minimum height difference from neighbors to count as a peak. Filters out noise |
| `limit` | integer | no | Max peaks to return (default: `20`) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `maxima[]` and/or `minima[]` (depending on `type`: `max`, `min`, `both`) of `{timestamp_sec, value, height_diff}`; `height_diff` is the prominence relative to neighbours and must exceed `min_height_diff`.
- Note `minima` uses the same `height_diff` semantics (difference from neighbours). Includes `data_quality` + `server_analysis_directives` (see [Common fields](#common-response-fields)).


**Examples**

**Both maxima and minima**

Request:
```json
{
  "name": "find_peaks",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/Drive/Module0/DriveVelocityRadPerSec",
    "type": "both",
    "min_height_diff": 5,
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "maxima": [
    {
      "timestamp_sec": 111.529604,
      "value": 13.73219601315226,
      "height_diff": 5.473243451175968
    },
    {
      "timestamp_sec": 111.815775,
      "value": 6.6881562351813955,
      "height_diff": 9.387962421860124
    },
    "... (1 more items)"
  ],
  "minima": [
    {
      "timestamp_sec": 111.79626,
      "value": -2.6998061866787286,
      "height_diff": 27.599382335638456
    },
    {
      "timestamp_sec": 114.622446,
      "value": 32.80264516814655,
      "height_diff": 5.301437602932779
    },
    "... (1 more items)"
  ],
  "data_quality": {
    "sample_count": 6470,
    "time_span_seconds": 321.04,
    "gap_count": 79,
    "max_gap_ms": 69282.9,
    "effective_sample_rate_hz": 48.5,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 6470 samples over 321.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use get_statistics to understand baseline before interpreting peaks"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 4
}
```


### `rate_of_change`

Compute rate of change (derivative) of numeric data over time.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `window_size` | integer | no | Smoothing window (default 1) |
| `limit` | integer | no | Max samples to return (default: `100`) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `statistics` `{avg_rate}`, `samples[]` of `{timestamp_sec, rate}` (units per second, finite differences over `window_size` samples, non-uniform timestamps handled). `limit` caps `samples`.
- Guidance always adds "Derivatives amplify noise — increase window_size for smoother results".


**Examples**

**Smoothed derivative**

Request:
```json
{
  "name": "rate_of_change",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/Drive/Module0/DriveVelocityRadPerSec",
    "window_size": 5,
    "limit": 5
  }
}
```

Response:
```json
{
  "success": true,
  "statistics": {
    "avg_rate": -0.4086576925310306
  },
  "samples": [
    {
      "timestamp_sec": 31.018245,
      "rate": 0.002951315266823002
    },
    {
      "timestamp_sec": 31.156702,
      "rate": 1.1687689108292074
    },
    "... (3 more items)"
  ],
  "data_quality": {
    "sample_count": 6470,
    "time_span_seconds": 321.04,
    "gap_count": 79,
    "max_gap_ms": 69282.9,
    "effective_sample_rate_hz": 48.5,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 6470 samples over 321.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Derivatives amplify noise — increase window_size for smoother results"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 1
}
```


### `time_correlate`

BUILT-IN correlation: NEVER compute correlation manually—always use this tool! Computes Pearson correlation coefficient with statistical significance (p-value). Handles timestamp alignment automatically via linear interpolation. Returns sample count for confidence assessment.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected. Correlation does not imply causation—consider confounding variables.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name1` | string | yes | First entry |
| `name2` | string | yes | Second entry |
| `start_time` | number | no | Start time |
| `end_time` | number | no | End time |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `sample_count` (overlapping samples used), `correlation` (Pearson r), `p_value` (two-tailed; **`null` when n < 15**).
- `warnings` added for n < 15 (`"Correlation computed from only N overlapping samples..."`, `"P-value cannot be reliably computed for n < 15..."`) and for zero-variance inputs (`"Correlation undefined: ..."`).
- `data_quality` is computed from `name1`'s full series, not the overlap window.
- Guidance always adds "Correlation does not imply causation — consider confounding variables".


**Examples**

**Voltage vs current, full log**

Request:
```json
{
  "name": "time_correlate",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name1": "/SystemStats/BatteryVoltage",
    "name2": "/SystemStats/BatteryCurrent"
  }
}
```

Response:
```json
{
  "success": true,
  "sample_count": 11735,
  "correlation": -0.8454880521942351,
  "p_value": 0.0,
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Correlation does not imply causation — consider confounding variables"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 9
}
```

**Too few overlapping samples**

Request:
```json
{
  "name": "time_correlate",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name1": "/SystemStats/BatteryVoltage",
    "name2": "/SystemStats/BatteryCurrent",
    "start_time": 120,
    "end_time": 120.2
  }
}
```

Response:
```json
{
  "success": true,
  "sample_count": 4,
  "correlation": 0.9090665587011777,
  "p_value": null,
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Correlation does not imply causation — consider confounding variables"
    ]
  },
  "warnings": [
    "Correlation computed from only 4 overlapping samples — insufficient for statistical significance. Results may be misleading (with 2 points, correlation is always ±1.0).",
    "P-value cannot be reliably computed for n < 15 (asymptotic approximation unreliable)",
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 11
}
```


## Robot Analysis Tools


### `get_match_phases`

ALWAYS use this tool to find match phases—NEVER manually parse timestamps! Detects autonomous/teleop/endgame phases from DriverStation/FMS mode transitions. Handles FMS disabled gaps, practice modes, and edge cases automatically. Returns start/end times for each phase based on actual DS data, not hardcoded durations. Use these timestamps to filter other analyses to specific match phases.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `log_duration`, `source` (`"DriverStation"` when mode entries were found, `"none"` otherwise), `phases` object with `autonomous`, `teleop`, `endgame` — each `{start, end, duration, description}`; `endgame` is the last 30 s of teleop (from the game knowledge base).
- Top-level `match_duration` (first enable → last disable), `auto_duration`, `teleop_duration` when the corresponding phase exists.
- If only Enabled transitions exist (no Autonomous entry) `phases.enabled` is returned instead with a warning. If no DriverStation entries exist at all: `success: true`, `source: "none"`, `warnings[]`, no `phases`.
- Auto start requires **both** Autonomous=true and Enabled=true (the FMS raises the auto flag well before the countdown ends). `data_quality` is computed from the Enabled entry (a handful of samples, so `confidence_level` is typically `"low"` — this is expected).


**Examples**

**DriverStation data present**

Request:
```json
{
  "name": "get_match_phases",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "log_duration": 336.00433,
  "phases": {
    "autonomous": {
      "start": 110.991153,
      "end": 135.642118,
      "duration": 24.650965000000014,
      "description": "Autonomous"
    },
    "teleop": {
      "start": 135.642118,
      "end": 278.380621,
      "duration": 142.738503,
      "description": "Teleop"
    },
    "endgame": {
      "start": 248.38062100000002,
      "end": 278.380621,
      "duration": 30.0,
      "description": "Endgame"
    }
  },
  "source": "DriverStation",
  "match_duration": 167.38946800000002,
  "auto_duration": 24.650965000000014,
  "teleop_duration": 142.738503,
  "data_quality": {
    "sample_count": 5,
    "time_span_seconds": 266.48,
    "gap_count": 0,
    "effective_sample_rate_hz": 0.0,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 5 samples over 266.5 seconds",
    "interpretation_guidance": [
      "Low sample count (5). Statistical measures have high uncertainty.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "_execution_time_ms": 1
}
```

**No DriverStation entries**

Request:
```json
{
  "name": "get_match_phases",
  "arguments": {
    "path": "~/FRC_20250304_014350.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "log_duration": 382.961324,
  "warnings": [
    "No DriverStation mode entries found in log. Cannot determine match phases. Look for entries containing 'DriverStation' and 'Enabled' or 'Autonomous'."
  ],
  "source": "none",
  "_execution_time_ms": 2
}
```

**Enabled-only variant (no Autonomous entry)**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "log_duration": "<s>",
  "phases": {
    "enabled": {
      "start": "<s>",
      "end": "<s>",
      "duration": "<s>",
      "description": "Enabled (mode unknown)"
    }
  },
  "source": "DriverStation",
  "match_duration": "<s>",
  "warnings": [
    "Robot enable/disable detected but autonomous/teleop mode transitions ..."
  ],
  "data_quality": "{...}",
  "server_analysis_directives": "{...}"
}
```


### `analyze_swerve`

Analyze swerve drive module performance: per-module speed statistics from SwerveModuleState entries. Returns 'no swerve modules detected' if log does not contain swerve module state entries.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Regression estimates depend on data quality and model assumptions. Physical parameters outside typical ranges (negative inertia, negative damping) indicate model or data issues, not actual physics.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `module_prefix` | string | no | Entry path prefix (e.g., '/Drive/Module') |
| `slip_threshold` | number | no | Speed difference threshold for slip detection in m/s (default: 0.5) |
| `sync_threshold_rad` | number | no | Angle threshold for sync deviation in radians (default: 0.1) |
| `odometry_entry` | string | no | Explicit odometry pose entry name |
| `vision_entry` | string | no | Explicit vision pose entry name |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `swerve_entries` — every log entry bucketed by type: `module_states` (`SwerveModuleState[]`), `module_positions` (`SwerveModulePosition[]`), `chassis_speeds` (`ChassisSpeeds`), `other` (everything else; large).
- `module_analysis[]` — one per `module_states` entry: `{entry, max_speed_mps, avg_speed_mps, sample_count}`.
- `wheel_slip` (when a setpoint/measured pair shares a prefix): `{modules: [{setpoint_entry, measured_entry, max_slip_ratio, avg_slip_ratio, slip_events, slip_event_rate, samples_compared}], pair_count}`.
- `module_sync` (when ≥2 modules): `{module_count, samples_analyzed, desync_events, max_deviation_rad, max_deviation_deg, worst_module?}`.
- `odometry_drift` (when odometry and vision pose entries are found or given): `{odometry_entry, vision_entry, avg_error_m, max_error_m, max_error_per_total_time, comparisons}`.
- `warnings[]` e.g. `"Could not analyze module '<entry>': no valid speed data found"`. `data_quality` comes from the odometry pose entry.
- Only struct entries are analysed; pointing `module_prefix` at scalar per-module entries (e.g. `/Drive/Module`) yields an empty `swerve_entries`.


**Examples**

**AdvantageKit swerve log (only struct entries analysed)**

Request:
```json
{
  "name": "analyze_swerve",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "swerve_entries": {
    "other": [
      "/SystemStats/5vRail/Voltage",
      "/SystemStats/CANBus/Utilization",
      "... (468 more items)"
    ],
    "chassis_speeds": [
      "/RealOutputs/SwerveChassisSpeeds/Measured",
      "/RealOutputs/SwerveChassisSpeeds/Setpoints"
    ],
    "module_states": [
      "/RealOutputs/SwerveStates/Measured",
      "/RealOutputs/SwerveStates/SetpointsOptimized",
      "... (1 more items)"
    ]
  },
  "module_analysis": [
    {
      "entry": "/RealOutputs/SwerveStates/Measured",
      "max_speed_mps": 4.115751448082775,
      "avg_speed_mps": 0.029974730167141007,
      "sample_count": 53428
    },
    {
      "entry": "/RealOutputs/SwerveStates/SetpointsOptimized",
      "max_speed_mps": 4.258314265497015,
      "avg_speed_mps": 0.10887284983009282,
      "sample_count": 25916
    },
    "... (1 more items)"
  ],
  "data_quality": {
    "sample_count": 13357,
    "time_span_seconds": 336.0,
    "gap_count": 83,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 49.2,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 13357 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use power_analysis to check if module issues correlate with brownouts"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 43
}
```

**Optional sub-objects**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "wheel_slip": {
    "modules": [
      {
        "setpoint_entry": "<entry>",
        "measured_entry": "<entry>",
        "max_slip_ratio": "<number>",
        "avg_slip_ratio": "<number>",
        "slip_events": "<int>",
        "slip_event_rate": "<number>",
        "samples_compared": "<int>"
      }
    ],
    "pair_count": "<int>"
  },
  "module_sync": {
    "module_count": "<int>",
    "samples_analyzed": "<int>",
    "desync_events": "<int>",
    "max_deviation_rad": "<number>",
    "max_deviation_deg": "<number>",
    "worst_module": "<int index>"
  },
  "odometry_drift": {
    "odometry_entry": "<entry>",
    "vision_entry": "<entry>",
    "avg_error_m": "<number>",
    "max_error_m": "<number>",
    "max_error_per_total_time": "<number>",
    "comparisons": "<int>"
  }
}
```


### `power_analysis`

Analyze battery and current distribution data. Reports battery voltage statistics (min/max/avg and samples below the brownout threshold; default 6.8V for roboRIO 1, set 6.3V for roboRIO 2) and, for every amperage entry, the peak current by magnitude with its timestamp, signed min/max, average, and sample count, sorted by peak. Amperage entries are named ...Current, ...CurrentAmps, ...Amps, ...Current/<sub>, or WPILib PowerDistribution[<id>]/Chan<N>; names like CurrentAngle or CurrentLimit are excluded. Per-channel arrays such as /PowerDistribution/ChannelCurrent are expanded per channel index. Warns when no voltage or current entries are found.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Voltage drops may indicate power issues, aggressive driving, worn battery, or loose connections. Single brownout events are not necessarily concerning—look for patterns across matches.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `power_prefix` | string | no | Entry path prefix (e.g., '/PDP') |
| `brownout_threshold` | number | no | Voltage threshold (default 6.8V for roboRIO 1, use 6.3V for roboRIO 2) |
| `channel_limit` | integer | no | Maximum number of current entries/channels to return, sorted by peak current (default: 30, minimum: 1) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `voltage_analysis` `{entry, min_voltage, max_voltage, avg_voltage, samples_below_threshold, brownout_threshold, brownout_risk}` on finite samples only; `brownout_risk` is `"HIGH"` if any sample < threshold, `"MODERATE"` if min < threshold + 1 V, else `"LOW"`. Absent (with a warning) when no scalar numeric voltage entry with finite samples exists.
- The voltage entry is chosen by ranking (battery > input/bus > other voltage > rails/regulators/motor outputs; ties by WPILOG declaration order), shared with `get_ds_timeline`. On this AdvantageKit log the default call now picks `/SystemStats/BatteryVoltage`; `power_prefix` narrows the candidates.
- `current_entries_analyzed` (always present) and `channel_analysis[]` sorted by `|peak_current_A|`: `{entry, peak_current_A, peak_current_time_sec, max_current_A, min_current_A, avg_current_A, sample_count}`; entries expanded from an array add `source_entry` and `channel`. `peak_current_A` is the largest-magnitude sample, signed — see `/Drive/Module2/DriveCurrentAmps` below. Only amperage-named entries qualify (`...Current`, `...Amps`, `Current/<sub>`, WPILib `PowerDistribution[<id>]/Chan<N>`).
- `warnings` when the list is truncated by `channel_limit` (default 30), when no usable voltage entry exists, or when no current entries exist.
- Follow-up always suggests `predict_battery_health`.


**Examples**

**Default call (no prefix)**

71 amperage entries/channels were found; the top 30 are listed (`channel_limit`). Shown trimmed.

Request:
```json
{
  "name": "power_analysis",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "voltage_analysis": {
    "entry": "/SystemStats/BatteryVoltage",
    "min_voltage": 6.680678710937499,
    "max_voltage": 12.843782470703125,
    "avg_voltage": 10.647124485941994,
    "samples_below_threshold": 1,
    "brownout_threshold": 6.8,
    "brownout_risk": "HIGH"
  },
  "current_entries_analyzed": 71,
  "channel_analysis": [
    {
      "entry": "/RealOutputs/PDH/TotalCurrentAmps",
      "peak_current_A": 226.0,
      "peak_current_time_sec": 137.380068,
      "max_current_A": 226.0,
      "min_current_A": 2.0,
      "avg_current_A": 96.2166586422725,
      "sample_count": 2077
    },
    {
      "entry": "/Spindexer/CurrentAmps",
      "peak_current_A": 149.3040313720703,
      "peak_current_time_sec": 180.101979,
      "max_current_A": 149.3040313720703,
      "min_current_A": 0.0,
      "avg_current_A": 9.247709647779638,
      "sample_count": 2798
    },
    {
      "entry": "/Kicker/CurrentAmps",
      "peak_current_A": 115.05494689941406,
      "peak_current_time_sec": 183.310443,
      "max_current_A": 115.05494689941406,
      "min_current_A": 0.0,
      "avg_current_A": 7.68508317515544,
      "sample_count": 3032
    },
    {
      "entry": "/Drive/Module2/DriveCurrentAmps",
      "peak_current_A": -113.54,
      "peak_current_time_sec": 131.611537,
      "max_current_A": 100.56,
      "min_current_A": -113.54,
      "avg_current_A": 14.292830071450787,
      "sample_count": 12876
    },
    {
      "entry": "/Drive/Module3/DriveCurrentAmps",
      "peak_current_A": 100.4,
      "peak_current_time_sec": 160.42963,
      "max_current_A": 100.4,
      "min_current_A": -98.9,
      "avg_current_A": 15.78436831164827,
      "sample_count": 12989
    },
    {
      "entry": "/Drive/Module0/DriveCurrentAmps",
      "peak_current_A": 100.34,
      "peak_current_time_sec": 233.646053,
      "max_current_A": 100.34,
      "min_current_A": -95.0,
      "avg_current_A": 16.759243142435295,
      "sample_count": 12869
    },
    "... (24 more items)"
  ],
  "warnings": [
    "Showing the top 30 of 71 current entries/channels by peak current; raise channel_limit to see more.",
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use predict_battery_health for comprehensive battery assessment"
    ]
  },
  "_execution_time_ms": 43
}
```

**Explicit prefix**

Restricting to `/SystemStats/Battery` leaves the battery voltage plus AdvantageKit's `/SystemStats/BatteryCurrent` (which reads ~1 A on this roboRIO — it is not the PDH total).

Request:
```json
{
  "name": "power_analysis",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "power_prefix": "/SystemStats/Battery"
  }
}
```

Response:
```json
{
  "success": true,
  "voltage_analysis": {
    "entry": "/SystemStats/BatteryVoltage",
    "min_voltage": 6.680678710937499,
    "max_voltage": 12.843782470703125,
    "avg_voltage": 10.647124485941994,
    "samples_below_threshold": 1,
    "brownout_threshold": 6.8,
    "brownout_risk": "HIGH"
  },
  "current_entries_analyzed": 1,
  "channel_analysis": [
    {
      "entry": "/SystemStats/BatteryCurrent",
      "peak_current_A": 1.0274101562500002,
      "peak_current_time_sec": 194.156238,
      "max_current_A": 1.0274101562500002,
      "min_current_A": 0.3533857421875,
      "avg_current_A": 0.5255249926072508,
      "sample_count": 11149
    }
  ],
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use predict_battery_health for comprehensive battery assessment"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 4
}
```


### `can_health`

Analyze CAN bus health by looking for timeout errors and communication issues. Returns 'no CAN data found' if log does not contain CAN bus utilization or error entries. See also: analyze_can_bus for numeric utilization analysis.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- Scans string entries for CAN error text. `error_counts_by_entry` — object keyed by entry name → `{total, while_enabled?, while_disabled?}`; `total_can_errors`; `errors_while_enabled` / `errors_while_disabled` (only when a DriverStation Enabled entry exists); `health_assessment` — `"GOOD"` (0 enabled-state errors), `"CONCERNING"` (< 50), `"POOR"`.
- `warnings[]` when no Enabled entry exists, or when disabled-state errors are present ("...are normal"). Guidance: "CAN errors while disabled are normal; focus on enabled-state errors".


**Examples**

**Console errors while enabled**

Request:
```json
{
  "name": "can_health",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "error_counts_by_entry": {
    "/RealOutputs/Console": {
      "total": 2,
      "while_enabled": 2,
      "while_disabled": 0
    }
  },
  "total_can_errors": 2,
  "errors_while_enabled": 2,
  "errors_while_disabled": 0,
  "health_assessment": "CONCERNING",
  "data_quality": {
    "sample_count": 2,
    "time_span_seconds": 1.03,
    "gap_count": 0,
    "effective_sample_rate_hz": 1.0,
    "quality_score": 0.7
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 2 samples over 1.0 seconds",
    "interpretation_guidance": [
      "Low sample count (2). Statistical measures have high uncertainty.",
      "Short time span (1.0s). Results may not be representative of full-match behavior.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "CAN errors while disabled are normal; focus on enabled-state errors"
    ]
  },
  "_execution_time_ms": 1
}
```


### `compare_matches`

Compare whole-log min/max/mean of one scalar numeric entry across two log files. Reports per log whether the entry was found and its finite sample count; array entries are not compared (use power_analysis or read_entry). For phase-scoped comparisons run get_statistics with start_time/end_time on each log instead.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the first log file |
| `compare_path` | string | yes | Path to the second log file |
| `name` | string | yes | Entry name to compare |


**Response fields**

- `entry`, `logs_compared`, `comparisons[]` — one per log (`path`, then `compare_path`): `{log_path, log_filename, entry_found, sample_count, statistics: {min, max, mean}}`; `sample_count` (finite scalar samples) and `statistics` appear only when `entry_found`, and `statistics` is omitted when the count is 0 (array entries are not compared).
- `warnings` when the entry is missing from a log or has no finite scalar values.
- `data_quality` is from the first log only. Guidance: "Cross-match comparisons require consistent logging configurations for valid comparison".


**Examples**

**Battery voltage across two quals**

Request:
```json
{
  "name": "compare_matches",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "compare_path": "<logdir>/vache/session_26/akit_26-03-21_16-54-48_vache_q13.wpilog",
    "name": "/SystemStats/BatteryVoltage"
  }
}
```

Response:
```json
{
  "success": true,
  "entry": "/SystemStats/BatteryVoltage",
  "logs_compared": 2,
  "comparisons": [
    {
      "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
      "log_filename": "akit_26-03-21_16-29-56_vache_q10.wpilog",
      "entry_found": true,
      "sample_count": 11735,
      "statistics": {
        "min": 6.680678710937499,
        "max": 12.843782470703125,
        "mean": 10.647124485941994
      }
    },
    {
      "log_path": "<logdir>/vache/session_26/akit_26-03-21_16-54-48_vache_q13.wpilog",
      "log_filename": "akit_26-03-21_16-54-48_vache_q13.wpilog",
      "entry_found": true,
      "sample_count": 9435,
      "statistics": {
        "min": 6.624136474609375,
        "max": 12.579918701171875,
        "mean": 10.770654367464891
      }
    }
  ],
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "Cross-match comparisons require consistent logging configurations for valid comparison"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 122
}
```


### `get_code_metadata`

Extract code metadata including Git SHA, branch, and build date. Returns 'no code metadata found' if log does not contain metadata entries.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `metadata` — object keyed by whichever of `GitSHA`, `GitBranch`, `GitDirty`, `BuildDate`, `ProjectName`, `Version` appear as a substring of an entry name (e.g. `/RealMetadata/GitSHA`); the value is that entry's first sample (`"unknown"` if it has none). `Version` is whatever type was logged (an int array here).


**Examples**

**AdvantageKit metadata**

Request:
```json
{
  "name": "get_code_metadata",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "metadata": {
    "GitDirty": "Uncomitted changes",
    "BuildDate": "2026-03-21 10:11:35 EDT",
    "Version": [
      0,
      0,
      4,
      1
    ],
    "ProjectName": "Rebuilt",
    "GitBranch": "main",
    "GitSHA": "378d5d47240edd0792ef8a7866bb5fb4b39960a1"
  },
  "_execution_time_ms": 3
}
```


### `moi_regression`

Estimate moment of inertia J (kg·m²) and viscous damping B (Nm·s/rad) for a DC-motor-driven mechanism using OLS regression on logged velocity and current. Model: G * motor_count * kt * I = J * α + B * ω. Supports angular (rad/s) or linear (m/s, via wheel_radius) velocity entries. Provide applied_volts_entry when current is always non-negative (TalonFX/SparkMax) so torque direction is recovered from voltage sign.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Regression estimates depend on data quality and model assumptions. Physical parameters outside typical ranges (negative inertia, negative damping) indicate model or data issues, not actual physics.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `velocity_entry` | string | yes | Entry path for mechanism velocity (rad/s, or m/s if wheel_radius is given) |
| `current_entry` | string | yes | Entry path for motor current (A) |
| `kt` | number | yes | Motor torque constant per motor (Nm/A). Kraken X60=0.01940, NEO Vortex=0.01706, NEO 550=0.0108 |
| `gear_ratio` | number | yes | Overall gear ratio from motor shaft to output shaft (G) |
| `motor_count` | integer | no | Number of motors driving the mechanism in parallel (default 1) |
| `wheel_radius` | number | no | Wheel radius (m). Provide when velocity is logged as linear (m/s) to convert to angular |
| `applied_volts_entry` | string | no | Optional: entry for applied voltage. When current is always non-negative (TalonFX/SparkMax), voltage sign is used to determine torque direction. |
| `start_time` | number | no | Analysis window start (seconds) |
| `end_time` | number | no | Analysis window end (seconds) |
| `alpha_threshold` | number | no | Min \|α\| (rad/s²) for a sample to be included in OLS. Filters near-steady-state points. Default 1.0 |
| `smooth_window` | integer | no | Moving-average half-width (samples) applied to velocity before differentiating. Default 2 |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `J_kg_m2`, `B_Nm_s_per_rad`, `r_squared`, `rmse_nm`, `n_samples_used`, `n_samples_total`, `filtered_by_alpha_threshold`, `filtered_by_zero_volts`.
- `parameters_used` `{torque_scale_Nm_per_A (= motor_count·kt·gear_ratio), wheel_radius_m?, applied_volts_used}`.
- `warnings[]` for negative J ("physically invalid ... add applied_volts_entry") or near-singular data. Errors: velocity/current/volts entry not found, too few samples, insufficient after filtering, singular OLS matrix.
- `data_quality` from the velocity entry. Guidance: "Regression estimates depend on data quality and model assumptions".


**Examples**

**Flywheel, linear velocity + volts sign**

Request:
```json
{
  "name": "moi_regression",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "velocity_entry": "/Flywheel/VelocityMetersPerSec",
    "wheel_radius": 0.0508,
    "current_entry": "/Flywheel/CurrentAmps",
    "applied_volts_entry": "/Flywheel/AppliedVolts",
    "kt": 0.0194,
    "gear_ratio": 1.0,
    "motor_count": 1
  }
}
```

Response:
```json
{
  "success": true,
  "J_kg_m2": 0.00023293301764729628,
  "B_Nm_s_per_rad": 0.0006318986005504205,
  "r_squared": 0.7285277082990497,
  "rmse_nm": 0.128329006246434,
  "n_samples_used": 6360,
  "n_samples_total": 6427,
  "filtered_by_alpha_threshold": 59,
  "filtered_by_zero_volts": 4,
  "parameters_used": {
    "torque_scale_Nm_per_A": 0.0194,
    "wheel_radius_m": 0.0508,
    "applied_volts_used": true
  },
  "data_quality": {
    "sample_count": 6427,
    "time_span_seconds": 273.04,
    "gap_count": 35,
    "max_gap_ms": 84976.6,
    "effective_sample_rate_hz": 48.4,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 6427 samples over 273.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Regression estimates depend on data quality and model assumptions"
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 13
}
```


### `analyze_can_bus`

Analyze CAN bus health: detect bus-off events, high utilization, and noisy devices. Returns 'no CAN bus data found' if log does not contain CAN utilization or error entries. See also: can_health for string-based error detection.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `bus_name` | string | no | CAN bus name (default: 'rio') |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `utilization[]` — every numeric entry containing "can" and one of utilization/busoff/tec/rec/txfull...: `{entry, avg_percent, max_percent, sample_count}` (note: counters such as `BusOffCount` are reported through the same shape).
- `errors[]` — `{entry, error_count, errors_while_enabled, errors_while_disabled, assessment?}` for error-counter entries that changed; `enabled_error_total`.
- `ds_enabled_warning` (string) when no DriverStation Enabled entry exists. Guidance: "Disabled-state CAN timeouts are normal — focus on enabled-state errors".


**Examples**

**Default bus**

Request:
```json
{
  "name": "analyze_can_bus",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "utilization": [
    {
      "entry": "/SystemStats/CANBus/Utilization",
      "avg_percent": 0.2969167176411002,
      "max_percent": 1.0,
      "sample_count": 668
    },
    {
      "entry": "/RealOutputs/CANBus/CAN2/BusOffCount",
      "avg_percent": 0.0,
      "max_percent": 0.0,
      "sample_count": 1
    },
    "... (3 more items)"
  ],
  "errors": [],
  "enabled_error_total": 0,
  "data_quality": {
    "sample_count": 668,
    "time_span_seconds": 335.69,
    "gap_count": 1,
    "max_gap_ms": 18969.7,
    "effective_sample_rate_hz": 2.3,
    "quality_score": 0.78
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 668 samples over 335.7 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Disabled-state CAN timeouts are normal — focus on enabled-state errors"
    ]
  },
  "_execution_time_ms": 6
}
```

**`errors[]` item shape**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "entry": "<entry>",
  "error_count": "<int>",
  "errors_while_enabled": "<int>",
  "errors_while_disabled": "<int>"
}
```


## FRC Domain Tools


### `get_ds_timeline`

Generate a chronological timeline of critical robot events: enable/disable, match phases, battery-voltage threshold brownouts (BROWNOUT_START/END, basis voltage_threshold), roboRIO brownout flag transitions when a flag such as /SystemStats/BrownedOut is logged (RIO_BROWNOUT_START/END, basis rio_flag), and for errors/warnings found in string entries, exact counts (text_event_counts, per source) and text_event_summary: each distinct message (numbers normalized to #) with its count, first/last time, sources, and how many distinct raw texts it covers. Individual messages are deliberately not listed here; use search_strings (level, regex, time window, offset/limit paging) for the complete list. rio_brownout_flag_logged says whether the roboRIO's own brownout state is available in this log; brownout_voltage_entry names the voltage entry scanned for threshold crossings, and a warning says when there is none.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `brownout_threshold` | number | no | Voltage threshold for brownout detection (default: 6.8V for roboRIO 1, use 6.3V for roboRIO 2) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `event_count`, `summary` (object: category → count), `events[]` sorted by time: `{timestamp, type, category, source, basis?, voltage?}`.
- `type` ∈ `ENABLED`, `DISABLED` (category `robot_state`); `AUTO_START`, `TELEOP_START` (category `match_phase`); `BROWNOUT_START`, `BROWNOUT_END` (category `power`, `basis: "voltage_threshold"`, with `voltage`; 0.2 V hysteresis on exit); `RIO_BROWNOUT_START`, `RIO_BROWNOUT_END` (category `power`, `basis: "rio_flag"`, from a logged boolean flag such as `/SystemStats/BrownedOut` — on this log the RIO flagged a brownout at 143.08 s with no threshold crossing at all).
- `brownout_voltage_entry` — the voltage entry scanned for threshold crossings (same selection as `power_analysis`); a warning replaces it when the log has none. `rio_brownout_flag_logged` / `rio_brownout_flag_entry` — whether a boolean brownout flag exists.
- Error/warning text is counted and summarized, never listed: `text_event_counts` `{error, warning, total, by_source}` are exact within the window; `text_event_summary[]` has one entry per distinct message after normalizing numbers to `#` (`{type, message, example?, count, variants, first_timestamp, last_timestamp, sources[]}`), sorted by count, at most 200 (`text_event_groups_total` is the true number). On this log the 626 matching console samples reduce to 16 patterns. `search_strings` lists the messages themselves.
- AUTO_START is deferred until the robot is enabled while the Autonomous flag is set; the initial `Autonomous=false` sample is not a transition. `data_quality` is computed from the Enabled entry.


**Examples**

**Full match**

119 events; the `events` array is trimmed here. The RIO flagged a brownout at 143.08 s with no voltage-threshold crossing, and the threshold crossing at 194.44 s coincides with another RIO flag.

Request:
```json
{
  "name": "get_ds_timeline",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "event_count": 19,
  "rio_brownout_flag_logged": true,
  "summary": {
    "robot_state": 5,
    "power": 12,
    "match_phase": 2
  },
  "events": [
    {
      "timestamp": 11.897573,
      "type": "DISABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 110.991153,
      "type": "AUTO_START",
      "category": "match_phase",
      "source": "/DriverStation/Autonomous"
    },
    {
      "timestamp": 110.991153,
      "type": "ENABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 131.611537,
      "type": "DISABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 135.642118,
      "type": "TELEOP_START",
      "category": "match_phase",
      "source": "/DriverStation/Autonomous"
    },
    {
      "timestamp": 135.642118,
      "type": "ENABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 143.079221,
      "type": "RIO_BROWNOUT_START",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    {
      "timestamp": 143.133666,
      "type": "RIO_BROWNOUT_END",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    {
      "timestamp": 194.071279,
      "type": "RIO_BROWNOUT_START",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    {
      "timestamp": 194.20445,
      "type": "RIO_BROWNOUT_END",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    {
      "timestamp": 194.444747,
      "type": "BROWNOUT_START",
      "category": "power",
      "basis": "voltage_threshold",
      "voltage": 6.680678710937499,
      "source": "/SystemStats/BatteryVoltage"
    },
    {
      "timestamp": 194.444747,
      "type": "RIO_BROWNOUT_START",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    {
      "timestamp": 194.486707,
      "type": "BROWNOUT_END",
      "category": "power",
      "basis": "voltage_threshold",
      "voltage": 7.516247314453125,
      "source": "/SystemStats/BatteryVoltage"
    },
    {
      "timestamp": 194.526719,
      "type": "RIO_BROWNOUT_END",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    "... (5 more items)"
  ],
  "rio_brownout_flag_entry": "/SystemStats/BrownedOut",
  "text_event_counts": {
    "error": 5,
    "warning": 621,
    "total": 626,
    "by_source": {
      "/RealOutputs/Console": {
        "error": 5,
        "warning": 621
      }
    }
  },
  "text_event_groups_total": 17,
  "text_event_summary": [
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.IterativeRobotBase.printLoopOverrunMessage(IterativeRobotBase.java:#): Loop time of #s overrun",
      "example": "Warning at edu.wpi.first.wpilibj.IterativeRobotBase.printLoopOverrunMessage(IterativeRobotBase.java:436): Loop time of 0.02s overrun",
      "count": 217,
      "variants": 1,
      "first_timestamp": 26.860151,
      "last_timestamp": 342.03656,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "CommandScheduler loop overrun",
      "count": 165,
      "variants": 1,
      "first_timestamp": 28.644661,
      "last_timestamp": 339.251074,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): teleopPeriodic(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tteleopPeriodic(): 0.000445s",
      "count": 87,
      "variants": 63,
      "first_timestamp": 136.431569,
      "last_timestamp": 273.473645,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): SmartDashboard.updateValues(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tSmartDashboard.updateValues(): 0.001206s",
      "count": 86,
      "variants": 55,
      "first_timestamp": 30.471613,
      "last_timestamp": 342.102357,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): Joystick Drive.execute(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tJoystick Drive.execute(): 0.012465s",
      "count": 34,
      "variants": 31,
      "first_timestamp": 137.754648,
      "last_timestamp": 275.235395,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): buttons.run(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tbuttons.run(): 0.000038s",
      "count": 15,
      "variants": 8,
      "first_timestamp": 112.619064,
      "last_timestamp": 339.140905,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): Drive.periodic(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tDrive.periodic(): 0.007679s",
      "count": 9,
      "variants": 9,
      "first_timestamp": 37.066547,
      "last_timestamp": 111.379423,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "ERROR",
      "message": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:#): HAL: CAN Receive has Timed Out",
      "example": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out",
      "count": 2,
      "variants": 1,
      "first_timestamp": 123.711426,
      "last_timestamp": 124.739764,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:#): PhotonVision coprocessor at path /photonvision/OV#_TH_#_RL has not reported a message interface UUID - is your coprocessor'...",
      "example": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:545): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_RL has not reported a message interface UUID - is your copr...",
      "count": 2,
      "variants": 1,
      "first_timestamp": 128.131112,
      "last_timestamp": 143.525108,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at org.photonvision.PhotonCamera.checkTimeSyncOrWarn(PhotonCamera.java:#): PhotonVision coprocessor at path /photonvision/OV#_TH_#_FL is not connected to the TimeSyncServer? It's been #s since...",
      "example": "Warning at org.photonvision.PhotonCamera.checkTimeSyncOrWarn(PhotonCamera.java:319): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL is not connected to the TimeSyncServer? It's been ...",
      "count": 2,
      "variants": 1,
      "first_timestamp": 130.451903,
      "last_timestamp": 145.078485,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "[KernelLogMonitor] Detected KERNEL_EVENT: kern :err : [ #] Warning: unable to open an initial console.",
      "example": "[KernelLogMonitor] Detected KERNEL_EVENT: kern  :err   : [    0.623311] Warning: unable to open an initial console.",
      "count": 1,
      "variants": 1,
      "first_timestamp": 11.897573,
      "last_timestamp": 11.897573,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:#): PhotonVision coprocessor at path /photonvision/OV#_TH_#_FL is not sending new data.",
      "example": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:533): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL is not sending new data.",
      "count": 1,
      "variants": 1,
      "first_timestamp": 127.889981,
      "last_timestamp": 127.889981,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:#): PhotonVision coprocessor at path /photonvision/OV#_TH_#_FL has not reported a message interface UUID - is your coprocessor'...",
      "example": "Warning at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:545): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL has not reported a message interface UUID - is your copr...",
      "count": 1,
      "variants": 1,
      "first_timestamp": 128.007662,
      "last_timestamp": 128.007662,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "ERROR",
      "message": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:#): PhotonVision coprocessor at path /photonvision/OV#_TH_#_FL not found on NetworkTables. Double check that your camera names ma...",
      "example": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL not found on NetworkTables. Double check that your camera ...",
      "count": 1,
      "variants": 1,
      "first_timestamp": 143.156946,
      "last_timestamp": 143.156946,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    "... (3 more items)"
  ],
  "brownout_voltage_entry": "/SystemStats/BatteryVoltage",
  "data_quality": {
    "sample_count": 5,
    "time_span_seconds": 266.48,
    "gap_count": 0,
    "effective_sample_rate_hz": 0.0,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 5 samples over 266.5 seconds",
    "interpretation_guidance": [
      "Low sample count (5). Statistical measures have high uncertainty.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 193
}
```

**Windowed**

With `start_time` inside autonomous, the auto→teleop transition is still reported (the state in force at `start_time` seeds the detector); AUTO_START itself is before the window. Trimmed.

Request:
```json
{
  "name": "get_ds_timeline",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "start_time": 120
  }
}
```

Response:
```json
{
  "success": true,
  "event_count": 16,
  "rio_brownout_flag_logged": true,
  "summary": {
    "robot_state": 3,
    "power": 12,
    "match_phase": 1
  },
  "events": [
    {
      "timestamp": 131.611537,
      "type": "DISABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 135.642118,
      "type": "TELEOP_START",
      "category": "match_phase",
      "source": "/DriverStation/Autonomous"
    },
    {
      "timestamp": 135.642118,
      "type": "ENABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 143.079221,
      "type": "RIO_BROWNOUT_START",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    {
      "timestamp": 143.133666,
      "type": "RIO_BROWNOUT_END",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    {
      "timestamp": 194.071279,
      "type": "RIO_BROWNOUT_START",
      "category": "power",
      "basis": "rio_flag",
      "source": "/SystemStats/BrownedOut"
    },
    "... (10 more items)"
  ],
  "rio_brownout_flag_entry": "/SystemStats/BrownedOut",
  "text_event_counts": {
    "error": 5,
    "warning": 426,
    "total": 431,
    "by_source": {
      "/RealOutputs/Console": {
        "error": 5,
        "warning": 426
      }
    }
  },
  "text_event_groups_total": 15,
  "text_event_summary": [
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.IterativeRobotBase.printLoopOverrunMessage(IterativeRobotBase.java:#): Loop time of #s overrun",
      "example": "Warning at edu.wpi.first.wpilibj.IterativeRobotBase.printLoopOverrunMessage(IterativeRobotBase.java:436): Loop time of 0.02s overrun",
      "count": 149,
      "variants": 1,
      "first_timestamp": 120.673326,
      "last_timestamp": 342.03656,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "CommandScheduler loop overrun",
      "count": 106,
      "variants": 1,
      "first_timestamp": 122.39957,
      "last_timestamp": 339.251074,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): teleopPeriodic(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tteleopPeriodic(): 0.000445s",
      "count": 87,
      "variants": 63,
      "first_timestamp": 136.431569,
      "last_timestamp": 273.473645,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): Joystick Drive.execute(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tJoystick Drive.execute(): 0.012465s",
      "count": 34,
      "variants": 31,
      "first_timestamp": 137.754648,
      "last_timestamp": 275.235395,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): SmartDashboard.updateValues(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tSmartDashboard.updateValues(): 0.022260s",
      "count": 31,
      "variants": 25,
      "first_timestamp": 120.794142,
      "last_timestamp": 342.102357,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    {
      "type": "WARNING",
      "message": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$#(Tracer.java:#): buttons.run(): #s",
      "example": "Warning at edu.wpi.first.wpilibj.Tracer.lambda$printEpochs$0(Tracer.java:62): \tbuttons.run(): 0.000037s",
      "count": 12,
      "variants": 8,
      "first_timestamp": 124.047292,
      "last_timestamp": 339.140905,
      "sources": [
        "/RealOutputs/Console"
      ]
    },
    "... (9 more items)"
  ],
  "brownout_voltage_entry": "/SystemStats/BatteryVoltage",
  "data_quality": {
    "sample_count": 5,
    "time_span_seconds": 266.48,
    "gap_count": 0,
    "effective_sample_rate_hz": 0.0,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 5 samples over 266.5 seconds",
    "interpretation_guidance": [
      "Low sample count (5). Statistical measures have high uncertainty.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 6
}
```


### `analyze_vision`

Analyze vision system reliability: target acquisition rate, flicker detection, pose discrepancy between vision and odometry, and sudden pose jumps.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `vision_prefix` | string | no | Entry path prefix for vision data |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `jump_threshold` | number | no | Distance threshold for jump detection (meters) (default: `0.5`) |
| `flicker_window` | number | no | Time window for flicker detection (seconds) (default: `0.5`) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `target_acquisition[]` — one per boolean "has target" entry (`hastarget`, `/tv`, `targetvalid`): `{entry, total_samples, valid_samples, acquisition_rate, flicker_events}` (flicker = transitions closer than `flicker_window`).
- `pose_jumps[]` `{timestamp, entry, distance}` and `jump_count` — from the first `Pose2d`/`Pose3d` entry whose name contains "pose" (but not "target"); `data_quality` comes from that entry. Both are omitted if no pose entry matches `vision_prefix`.
- On this log no has-target entry exists, so `target_acquisition` is empty and the pose jumps come from `/RealOutputs/Drive/Pose`.


**Examples**

**Default prefix**

Request:
```json
{
  "name": "analyze_vision",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "target_acquisition": [],
  "pose_jumps": [
    {
      "timestamp": 33.131609,
      "entry": "/RealOutputs/Drive/Pose",
      "distance": 2.3481903395368757
    },
    {
      "timestamp": 33.457217,
      "entry": "/RealOutputs/Drive/Pose",
      "distance": 1.054051815831935
    },
    "... (5 more items)"
  ],
  "jump_count": 7,
  "data_quality": {
    "sample_count": 13357,
    "time_span_seconds": 336.0,
    "gap_count": 83,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 49.2,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 13357 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "warnings": [
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 30
}
```

**`target_acquisition[]` item shape**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "entry": "<entry>",
  "total_samples": "<int>",
  "valid_samples": "<int>",
  "acquisition_rate": "<0..1>",
  "flicker_events": "<int>"
}
```


### `profile_mechanism`

Analyze closed-loop mechanism performance: following error (RMSE), settling time, stall detection, and motor temperature profiling.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Regression estimates depend on data quality and model assumptions. Physical parameters outside typical ranges (negative inertia, negative damping) indicate model or data issues, not actual physics.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `mechanism_name` | string | yes | Mechanism name or prefix |
| `start_time` | number | no | Start timestamp |
| `end_time` | number | no | End timestamp |
| `stall_current_threshold` | number | no | Current threshold for stall (default: 30A) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `mechanism` (echoed). Entries are located by name containing `mechanism_name` (case-insensitive): setpoint (`setpoint`/`goal`), measurement (`position`/`actual`), `velocity`, `current`.
- `following_error` `{rmse, settling_time_sec: {avg, max, min}?, overshoot_percent?}` — only when both a setpoint and a measurement entry exist.
- `stall_events[]` `{start_time, end_time, duration, max_current}` and `stall_count` — only when velocity and current entries exist **and** stalls (|velocity| ≈ 0 with current > `stall_current_threshold`) were found.
- `data_quality` from the measurement entry (else velocity). Follow-up: `moi_regression`. The captured log has no setpoint entries, so only the base shape appears below.


**Examples**

**Mechanism without setpoint entries**

Request:
```json
{
  "name": "profile_mechanism",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "mechanism_name": "Hood"
  }
}
```

Response:
```json
{
  "success": true,
  "mechanism": "Hood",
  "data_quality": {
    "sample_count": 13355,
    "time_span_seconds": 321.02,
    "gap_count": 82,
    "max_gap_ms": 1510.2,
    "effective_sample_rate_hz": 49.2,
    "quality_score": 0.51
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 13355 samples over 321.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use moi_regression for mechanism inertia estimation"
    ]
  },
  "_execution_time_ms": 8
}
```

**Full shape when setpoint / velocity / current entries exist**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "mechanism": "<name>",
  "following_error": {
    "rmse": "<number>",
    "settling_time_sec": {
      "avg": "<number>",
      "max": "<number>",
      "min": "<number>"
    },
    "overshoot_percent": "<number>"
  },
  "stall_events": [
    {
      "start_time": "<s>",
      "end_time": "<s>",
      "duration": "<s>",
      "max_current": "<A>"
    }
  ],
  "stall_count": "<int>",
  "data_quality": "{...}",
  "server_analysis_directives": "{...}"
}
```


### `analyze_auto`

Analyze autonomous routine: identify selected routine, path following error, completion time, and phase breakdown. Returns 'no auto period detected' if log does not contain autonomous phase data.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `auto_prefix` | string | no | Entry path prefix for auto data |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `selected_routine` — first value of the first entry containing "chooser" (absent here: the log uses `/RealOutputs/AutoSelector/SelectedAutoMode`).
- `auto_start_time`, `auto_end_time`, `auto_duration` — when an auto period is detected; `path_following_error` `{rmse_meters, max_error_meters, samples}` when trajectory + pose entries under `auto_prefix` exist.
- `data_quality`/`server_analysis_directives` from the DriverStation Enabled entry.
- **Observed:** on these logs the auto period is *not* detected (the Autonomous flag rises at 26.9 s, long before enable at 111.0 s, and the deferred-enable fallback is skipped), even though `get_match_phases` finds it. Prefer `get_match_phases` for auto timing until this is fixed.


**Examples**

**Observed on this log (auto not detected)**

Request:
```json
{
  "name": "analyze_auto",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "data_quality": {
    "sample_count": 5,
    "time_span_seconds": 266.48,
    "gap_count": 0,
    "effective_sample_rate_hz": 0.0,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 5 samples over 266.5 seconds",
    "interpretation_guidance": [
      "Low sample count (5). Statistical measures have high uncertainty.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "_execution_time_ms": 1
}
```

**Full shape when auto is detected**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "selected_routine": "<string>",
  "auto_start_time": "<s>",
  "auto_end_time": "<s>",
  "auto_duration": "<s>",
  "path_following_error": {
    "rmse_meters": "<number>",
    "max_error_meters": "<number>",
    "samples": "<int>"
  },
  "data_quality": "{...}",
  "server_analysis_directives": "{...}"
}
```


### `analyze_cycles`

Analyze game piece handling cycle times with configurable cycle detection modes (start-to-start or start-to-end), dead time tracking, and data quality warnings. Supports time filtering, case-sensitive/insensitive matching, and incomplete cycle detection.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `state_entry` | string | yes | Entry name for mechanism state |
| `cycle_mode` | string | no | Cycle detection mode: 'start_to_start' or 'start_to_end' (default: 'start_to_start') |
| `cycle_start_state` | string | no | State value that marks cycle start (e.g., 'INTAKING') |
| `cycle_end_state` | string | no | State value that marks cycle end (only for start_to_end mode, e.g., 'SCORING') |
| `idle_state` | string | no | State value for idle/dead time (e.g., 'IDLE') |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `case_sensitive` | boolean | no | Case-sensitive state matching (default: true) |
| `limit` | integer | no | Max cycles/dead periods to return (default: 10) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `sample_count`, `cycle_mode`, `cycle_times` `{count, avg_sec, min_sec, max_sec}`, `cycles[]` `{start_time, end_time, duration, incomplete}`, `cycles_truncated`, `total_cycles`.
- With `idle_state`: `dead_time` `{total_sec, period_count, avg_duration_sec}`, `dead_time_periods[]` `{start_time, end_time, duration}`, `dead_time_periods_truncated`, `total_dead_time_periods`.
- `warnings[]`: unknown states seen (`"Detected unknown states: ..."`), incomplete cycles at log end, etc. Parameter errors: missing `cycle_start_state` (start_to_start), missing end state (start_to_end), bad `cycle_mode`, entry not found / no data.
- `data_quality` from the state entry (low sample counts are normal for state strings).


**Examples**

**start_to_start with idle state**

Request:
```json
{
  "name": "analyze_cycles",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "state_entry": "/RealOutputs/Subsystems/Intake/Command",
    "cycle_start_state": "Intake",
    "idle_state": "Retract and stop",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "sample_count": 28,
  "cycle_mode": "start_to_start",
  "warnings": [
    "Detected unknown states: SequentialCommandGroup, None",
    "1 cycle(s) incomplete (log ended mid-cycle). Exclude from statistical analysis."
  ],
  "cycle_times": {
    "count": 10,
    "avg_sec": 13.777055300000004,
    "min_sec": 0.5926350000000298,
    "max_sec": 37.104118
  },
  "cycles": [
    {
      "start_time": 136.545225,
      "end_time": 140.811298,
      "duration": 4.266073000000006,
      "incomplete": false
    },
    {
      "start_time": 140.811298,
      "end_time": 151.29574,
      "duration": 10.484442000000001,
      "incomplete": false
    },
    "... (1 more items)"
  ],
  "cycles_truncated": true,
  "total_cycles": 11,
  "dead_time": {
    "total_sec": 124.99663300000009,
    "period_count": 13,
    "avg_duration_sec": 9.615125615384622
  },
  "dead_time_periods": [
    {
      "start_time": 110.991153,
      "end_time": 111.379423,
      "duration": 0.38827000000000567
    },
    {
      "start_time": 135.642118,
      "end_time": 136.545225,
      "duration": 0.9031069999999772
    },
    "... (1 more items)"
  ],
  "dead_time_periods_truncated": true,
  "total_dead_time_periods": 13,
  "data_quality": {
    "sample_count": 28,
    "time_span_seconds": 251.52,
    "gap_count": 5,
    "max_gap_ms": 84131.0,
    "effective_sample_rate_hz": 0.3,
    "quality_score": 0.43
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 28 samples over 251.5 seconds",
    "interpretation_guidance": [
      "Low sample count (28). Statistical measures have high uncertainty.",
      "5 data gaps detected (max 84131.0ms). Trend analysis may be affected by missing data.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "_execution_time_ms": 138
}
```


### `analyze_replay_drift`

Validate AdvantageKit deterministic replay by comparing RealOutputs vs ReplayOutputs. Small drift may be acceptable due to non-deterministic inputs (vision, joystick timing). Focus on large or systematic divergences rather than isolated small differences.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `divergent_count`, `divergences[]` (capped at 10) of `{entry, timestamp}` — first timestamp where a `/RealOutputs/...` entry differs from its `/ReplayOutputs/...` twin.
- Meaningful only on AdvantageKit replay logs (the `_sim` file here); on a normal log there are no `ReplayOutputs` entries and the count is 0.


**Examples**

**Replay log**

Request:
```json
{
  "name": "analyze_replay_drift",
  "arguments": {
    "path": "<logdir>/vache/session_55/akit_26-03-22_18-15-22_vache_e4_sim.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "divergent_count": 90,
  "divergences": [
    {
      "entry": "/RealOutputs/Alerts/Choreo/Warnings",
      "timestamp": 25.979594
    },
    {
      "entry": "/RealOutputs/Logger/DashboardInputsMS",
      "timestamp": 12.519009
    },
    {
      "entry": "/RealOutputs/LoggedRobot/GCTimeMS",
      "timestamp": 25.979594
    },
    "... (7 more items)"
  ],
  "data_quality": {
    "sample_count": 41,
    "time_span_seconds": 281.57,
    "gap_count": 4,
    "max_gap_ms": 117061.6,
    "effective_sample_rate_hz": 0.3,
    "quality_score": 0.44
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 41 samples over 281.6 seconds",
    "interpretation_guidance": [
      "Low sample count (41). Statistical measures have high uncertainty.",
      "4 data gaps detected (max 117061.6ms). Trend analysis may be affected by missing data.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "_execution_time_ms": 346
}
```


### `predict_battery_health`

Analyze battery voltage and current draw to predict brownout risk and estimate battery health. Returns health score (0-100), brownout risk level (MINIMAL/LOW/MODERATE/HIGH/CRITICAL), voltage statistics, and actionable recommendations.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Voltage drops may indicate power issues, aggressive driving, worn battery, or loose connections. Single brownout events are not necessarily concerning—look for patterns across matches.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `nominal_voltage` | number | no | Expected full battery voltage (default: 12.6V) |
| `brownout_threshold` | number | no | Brownout voltage threshold (default: 6.8V for roboRIO 1, use 6.3V for roboRIO 2) |
| `warning_threshold` | number | no | Warning voltage threshold (default: 9.0V) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `health_score` (0–100), `risk_level` ∈ `LOW`, `MODERATE`, `HIGH`, `CRITICAL` (CRITICAL whenever min voltage < brownout threshold).
- `voltage_stats` `{min_volts, max_volts, avg_volts, voltage_sag}`, `brownout_events`, `warning_events` (samples below `warning_threshold`), `brownout_details[]` (≤10) `{start_time, end_time, duration, min_voltage}`, `recovery_analysis` `{avg_recovery_sec, max_recovery_sec, sample_count}` (when recoveries were measurable), `recommendations[]` (fixed strings, see source list below).
- `warnings[]`: `"<n> brownout event(s) detected - immediate battery replacement recommended"`, `"Battery health is poor - replace before next match"` (score < 50).
- Recommendation strings: "URGENT: Replace battery immediately - brownouts detected", "Replace battery before next match", "Consider battery replacement - health declining", "High voltage sag detected - check connections and wire gauge", "Average voltage low - battery may be undercharged", "Close to brownout threshold - reduce current draw or replace battery", "Battery health good - continue monitoring".


**Examples**

**Match with one brownout**

Request:
```json
{
  "name": "predict_battery_health",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "health_score": 0,
  "risk_level": "CRITICAL",
  "voltage_stats": {
    "min_volts": 6.680678710937499,
    "max_volts": 12.843782470703125,
    "avg_volts": 10.647124485941994,
    "voltage_sag": 5.9193212890625
  },
  "brownout_events": 1,
  "warning_events": 179,
  "brownout_details": [
    {
      "start_time": 194.444747,
      "end_time": 194.486707,
      "duration": 0.041959999999988895,
      "min_voltage": 6.680678710937499
    }
  ],
  "recovery_analysis": {
    "avg_recovery_sec": 0.3890568746594005,
    "max_recovery_sec": 1.900466999999992,
    "sample_count": 367
  },
  "recommendations": [
    "URGENT: Replace battery immediately - brownouts detected",
    "High voltage sag detected - check connections and wire gauge",
    "Average voltage low - battery may be undercharged"
  ],
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Battery health score is a heuristic — consider battery age and connector condition"
    ]
  },
  "warnings": [
    "1 brownout event(s) detected - immediate battery replacement recommended",
    "Battery health is poor - replace before next match",
    "Low data quality (score: 0.50). Results should be treated as preliminary."
  ],
  "_execution_time_ms": 8
}
```


### `analyze_loop_timing`

Detect when robot code exceeded loop period threshold (default 20ms). Returns violations, statistics, and a health score. Auto-detects units (ms vs s) via median heuristic; assumes standard FRC loop rates.
> **Description guidance:** Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `threshold_ms` | number | no | Loop time threshold in milliseconds (default: 20) |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `unit` | string | no | Unit of loop time values: 'ms', 's', or 'auto' (default: 'auto'). Auto-detect uses median value: if median < 1.0, assumes seconds and converts to ms. |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- Requires an entry whose name contains `LoopTime` (or `loop` + `time`). None of the local AdvantageKit logs have one (they log `/RealOutputs/LoggedRobot/FullCycleMS`), so only the error was captured; the success shape below is from source.
- Success: `loop_time_entry`, `threshold_ms`, `violation_count`, `total_samples`, `violation_rate` (0–1), `health_score` (int 0–100 = 100·(1−rate)), `statistics` `{avg_ms, max_ms, min_ms, p95_ms, p99_ms}`, `violations[]` (≤50) `{timestamp, loop_time_ms, overage_ms}`, `units_note` (only when raw values were auto-detected as microseconds).
- Guidance: "Health score is a heuristic based on violation rate — consider context of violations".


**Examples**

**No loop-time entry (observed)**

Request:
```json
{
  "name": "analyze_loop_timing",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": false,
  "error": "No loop time entry found. Look for entries containing 'LoopTime' or 'loop time'",
  "_execution_time_ms": 0
}
```

**Success shape**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "loop_time_entry": "<entry>",
  "threshold_ms": 20.0,
  "violation_count": "<int>",
  "total_samples": "<int>",
  "violation_rate": "<0..1>",
  "health_score": "<int 0..100>",
  "statistics": {
    "avg_ms": "<number>",
    "max_ms": "<number>",
    "min_ms": "<number>",
    "p95_ms": "<number>",
    "p99_ms": "<number>"
  },
  "violations": [
    {
      "timestamp": "<s>",
      "loop_time_ms": "<number>",
      "overage_ms": "<number>"
    }
  ],
  "units_note": "Raw values were detected as microseconds and converted to milliseconds",
  "data_quality": "{...}",
  "server_analysis_directives": {
    "confidence_level": "<level>",
    "sample_context": "<string>",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Health score is a heuristic based on violation rate — consider context of violations"
    ]
  }
}
```


### `get_game_info`

Get year-specific FRC game information (match timing, scoring values, field geometry, game pieces, and analysis hints). Use this to understand the context of a log file: what the match phases are, what scoring actions look like, and what mechanisms to expect. Defaults to the current season if no year is specified.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `season` | integer | no | FRC season year (e.g., 2026). Defaults to current year. |


**Response fields**

- `season`, `game_name`, `match_timing`, `scoring`, `field_geometry`, `game_pieces[]`, `analysis_hints`, `typical_mechanisms[]`; the inner structure is game-specific (e.g. 2026 adds `hub_mechanics` and `match_timing.shifts`). Bundled seasons: 2024, 2025, 2026.
- Unknown season → `success: false`, `error`, `available_seasons[]`.


**Examples**

**Current season (2026)**

Request:
```json
{
  "name": "get_game_info",
  "arguments": {}
}
```

Response:
```json
{
  "success": true,
  "season": 2026,
  "game_name": "REBUILT",
  "match_timing": {
    "auto_duration_sec": 20,
    "auto_to_teleop_delay_sec": 3,
    "teleop_duration_sec": 140,
    "total_duration_sec": 160,
    "endgame_start_before_end_sec": 30,
    "endgame_duration_sec": 30,
    "shifts": {
      "auto": {
        "start_sec": 0,
        "end_sec": 20,
        "duration_sec": 20
      },
      "transition_shift": {
        "start_sec": 20,
        "end_sec": 30,
        "duration_sec": 10
      },
      "shift_1": {
        "start_sec": 30,
        "end_sec": 55,
        "duration_sec": 25
      },
      "shift_2": {
        "start_sec": 55,
        "end_sec": 80,
        "duration_sec": 25
      },
      "shift_3": {
        "start_sec": 80,
        "end_sec": 105,
        "duration_sec": 25
      },
      "shift_4": {
        "start_sec": 105,
        "end_sec": 130,
        "duration_sec": 25
      },
      "end_game": {
        "start_sec": 130,
        "end_sec": 160,
        "duration_sec": 30
      }
    }
  },
  "scoring": {
    "match_points": {
      "auto": {
        "fuel_active_hub": 1,
        "fuel_inactive_hub": 0,
        "tower_level_1": 15,
        "tower_level_1_max_robots": 2
      },
      "teleop": {
        "fuel_active_hub": 1,
        "fuel_inactive_hub": 0,
        "tower_level_1": 10,
        "tower_level_2": 20,
        "tower_level_3": 30
      }
    },
    "ranking_points": {
      "win": 3,
      "tie": 1,
      "energized_rp": {
        "description": "Amount of FUEL scored in HUB at or above threshold",
        "value": 1,
        "regional_threshold": 100,
        "district_championship_threshold": null,
        "championship_threshold": null
      },
      "supercharged_rp": {
        "description": "Amount of FUEL scored in HUB at or above threshold",
        "value": 1,
        "regional_threshold": 360,
        "district_championship_threshold": null,
        "championship_threshold": null
      },
      "traversal_rp": {
        "description": "Amount of TOWER points scored during MATCH at or above threshold",
        "value": 1,
        "regional_threshold": 50,
        "district_championship_threshold": null,
        "championship_threshold": null
      }
    },
    "fouls": {
      "minor_foul": 5,
      "major_foul": 15
    }
  },
  "field_geometry": {
    "field_length_m": 16.54,
    "field_length_in": 651.2,
    "field_width_m": 8.07,
    "field_width_in": 317.7,
    "alliance_zone_depth_m": 4.03,
    "neutral_zone_depth_m": 7.19,
    "bump_width_m": 1.854,
    "bump_depth_m": 1.128,
    "bump_height_m": 0.1654,
    "hub_size_m": 1.19,
    "hub_opening_m": 1.06,
    "tower": {
      "low_rung_height_m": 0.6858,
      "mid_rung_height_m": 1.143,
      "high_rung_height_m": 1.6
    }
  },
  "game_pieces": [
    {
      "name": "FUEL",
      "type": "foam ball",
      "diameter_m": 0.15,
      "diameter_in": 5.91,
      "preload_max_per_robot": 8,
      "preload_max_per_alliance": 48
    }
  ],
  "analysis_hints": {
    "endgame_activity": "Tower climbing attempts typically occur in the final 30 seconds (END GAME). Level 1 available in AUTO (max 2 robots).",
    "hub_strategy": "Track hub active/inactive status from FMS data. Scoring in inactive hub earns 0 points. Alliance shifts alternate every 25 seconds.",
    "fuel_context": "Each FUEL scored in active HUB = 1 point (auto and teleop). High volume scoring is key: 100 FUEL for ENERGIZED RP, 360 for SUPERCHARGED RP.",
    "cycle_time": "Fuel cycle = intake from field/depot -> score in hub. Track time between successive hub scores.",
    "tower_context": "TRAVERSAL RP requires 50+ tower points. Level 3 climb (30 pts) + Level 2 (20 pts) = 50 pts minimum for RP.",
    "auto_importance": "AUTO winner determines hub shift order. Scoring more FUEL in AUTO gives strategic advantage for SHIFT 1."
  },
  "typical_mechanisms": [
    "shooter",
    "intake",
    "climber",
    "fuel_storage"
  ],
  "hub_mechanics": {
    "description": "Both HUBs active during AUTO, TRANSITION SHIFT, and END GAME. During ALLIANCE SHIFTS, HUBs alternate active/inactive based on AUTO results.",
    "auto_winner_shift_1_hub": "inactive",
    "auto_loser_shift_1_hub": "active"
  },
  "_execution_time_ms": 0
}
```

**Unknown season**

Request:
```json
{
  "name": "get_game_info",
  "arguments": {
    "season": 2019
  }
}
```

Response:
```json
{
  "success": false,
  "error": "No game data available for season 2019",
  "available_seasons": [
    2024,
    2025,
    2026
  ],
  "_execution_time_ms": 0
}
```


## Export Tools


### `export_csv`

Export entry data to a CSV file for external analysis in Excel, Python, or MATLAB.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name to export |
| `output_path` | string | yes | Path for output CSV file |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `entry`, `output_path` (as given), `rows_exported`, `type`.
- `output_path` must resolve (symlinks followed) inside the configured export directory (`-exportdir` / `WPILOG_EXPORT_DIR`, default `{tmpdir}/wpilog-export/`); anything else is rejected with `success: false` (second example). Struct entries are flattened to one column per field.


**Examples**

**Allowed path**

Request:
```json
{
  "name": "export_csv",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "output_path": "<exportdir>/battery.csv",
    "start_time": 135,
    "end_time": 140
  }
}
```

Response:
```json
{
  "success": true,
  "entry": "/SystemStats/BatteryVoltage",
  "output_path": "<exportdir>/battery.csv",
  "rows_exported": 175,
  "type": "double",
  "_execution_time_ms": 5
}
```

**Path outside export directory**

Request:
```json
{
  "name": "export_csv",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "output_path": "battery.csv",
    "start_time": 135,
    "end_time": 140
  }
}
```

Response:
```json
{
  "success": false,
  "error": "Output path not allowed. CSV files can only be written to the configured log directory or system temp directory. Path: <cwd>/battery.csv",
  "_execution_time_ms": 1
}
```


### `generate_report`

Generate a comprehensive match summary report including duration, errors found, peak currents, minimum voltage, and other key metrics.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `log_path`, `log_filename`, `basic_info` `{duration_sec, start_timestamp, end_timestamp, entry_count, truncated, truncation_message?}`.
- `battery` `{entry, min_voltage, max_voltage, brownout_risk}` (first entry containing "voltage" — same caveat as `power_analysis`, though on this log it found `/SystemStats/BatteryVoltage`), `errors` `{total_errors, samples[]}` (strings truncated to 100 chars), `code_info` `{git_sha?, git_branch?}`, `top_data_types` (type → entry count, top 10).
- Guidance: "Report is a summary — use individual tools for detailed analysis".


**Examples**

**Match report**

Request:
```json
{
  "name": "generate_report",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
  "log_filename": "akit_26-03-21_16-29-56_vache_q10.wpilog",
  "basic_info": {
    "duration_sec": 336.00433,
    "start_timestamp": 11.897573,
    "end_timestamp": 347.901903,
    "entry_count": 475,
    "truncated": false
  },
  "battery": {
    "entry": "/SystemStats/BatteryVoltage",
    "min_voltage": 6.680678710937499,
    "max_voltage": 12.843782470703125,
    "brownout_risk": "HIGH"
  },
  "errors": {
    "total_errors": 5,
    "samples": [
      "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN...",
      "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN...",
      "... (3 more items)"
    ]
  },
  "code_info": {
    "git_sha": "378d5d47240edd0792ef8a7866bb5fb4b39960a1",
    "git_branch": "main"
  },
  "top_data_types": {
    "double": 119,
    "boolean": 98,
    "int64": 62,
    "string": 48,
    "string[]": 22,
    "struct:Pose2d[]": 20,
    "struct:Rotation2d": 18,
    "int64[]": 18,
    "double[]": 13,
    "structschema": 12
  },
  "data_quality": {
    "sample_count": 11735,
    "time_span_seconds": 336.0,
    "gap_count": 119,
    "max_gap_ms": 14962.6,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.5
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 11735 samples over 336.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Report is a summary — use individual tools for detailed analysis"
    ]
  },
  "_execution_time_ms": 2
}
```


## TBA Tools


### `get_tba_status`

Check if The Blue Alliance API is configured and available. When TBA is configured, you can: (1) Use list_available_logs to see match scores and win/loss results for each log, or (2) Use get_tba_match_data to query specific match details including autonomous points. TBA data is the authoritative source for match outcomes—don't guess from telemetry!

**Parameters**

_No parameters._


**Response fields**

- `available` (bool), `status` (`"configured"` / `"not_configured"`), `hint`.
- When configured also `cache` — object of TBA client cache statistics (key → number).


**Examples**

**Not configured (observed)**

Request:
```json
{
  "name": "get_tba_status",
  "arguments": {}
}
```

Response:
```json
{
  "success": true,
  "available": false,
  "status": "not_configured",
  "hint": "Set TBA_API_KEY environment variable or use -tba-key argument. Get a free API key at https://www.thebluealliance.com/account",
  "_execution_time_ms": 0
}
```

**Configured**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "available": true,
  "status": "configured",
  "cache": {
    "<stat>": "<number>"
  },
  "hint": "TBA data will be included in list_available_logs for logs with team number in metadata"
}
```


### `get_tba_match_data`

Query match scores and detailed results from The Blue Alliance. Use this to answer questions like 'What was our score?', 'Did we win?', 'How many autonomous points did we score?', or 'What were the match results?'. Returns alliance scores, win/loss status, and detailed score breakdown including autonomous points when available. IMPORTANT: This is the primary tool for getting match outcome data—don't guess or infer match results from telemetry when you can query TBA directly.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `year` | integer | yes | Competition year (e.g., 2024) |
| `event_code` | string | yes | TBA event code — this is NOT the abbreviation from log filenames. Find the correct code at thebluealliance.com/events/{year}. Examples: 'caph' (Poway), 'cmptx' (Houston Championship) |
| `match_type` | string | yes | Match type: 'Qualification', 'Quarterfinal', 'Semifinal', 'Final', or 'Elimination' |
| `match_number` | integer | yes | Match number within the type |
| `team_number` | integer | no | Optional: Your team number to highlight your alliance's data |


**Response fields**

- Not configured → `success: false` with the error shown. Match found → `success: true, match_found: true` plus `match_key`, `comp_level`, `match_number`, `match_time` (or `scheduled_time`), `winning_alliance` (`"red"`, `"blue"`, or `"tie_or_not_played"`), `alliances.{red,blue}` `{score, teams[], your_alliance?: true, won?}`, `score_breakdown.{red,blue}` with whichever of the known point fields TBA returned (`autoPoints`, `teleopPoints`, `endgamePoints`, `foulPoints`, `totalPoints`, plus 2024/2025 game-specific fields).
- Match not found → `success: true, match_found: false` with `message`, and either `similar_events[]` + `hint` (event code unknown) or `suggestions[]` (event exists, match doesn't).
- Generic `Elimination` with `team_number` → `lookup_method: "smart_elimination_match"`, `your_alliance` `{color, score, won}`, `match_time?`, `note`.
- `match_type` must be one of `Qualification`, `Quarterfinal`, `Semifinal`, `Final`, `Elimination`.


**Examples**

**Not configured (observed)**

Request:
```json
{
  "name": "get_tba_match_data",
  "arguments": {
    "year": 2026,
    "event_code": "vache",
    "match_type": "qm",
    "match_number": 10,
    "team_number": 2363
  }
}
```

Response:
```json
{
  "success": false,
  "error": "TBA API not configured. Set TBA_API_KEY environment variable or use -tba-key argument. Get a free API key at https://www.thebluealliance.com/account",
  "_execution_time_ms": 0
}
```

**Match found**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "match_found": true,
  "match_key": "2026vache_qm10",
  "comp_level": "qm",
  "match_number": 10,
  "match_time": "<formatted time>",
  "winning_alliance": "blue",
  "alliances": {
    "red": {
      "score": "<int>",
      "teams": [
        "<int>",
        "<int>",
        "<int>"
      ]
    },
    "blue": {
      "score": "<int>",
      "teams": [
        2363,
        "<int>",
        "<int>"
      ],
      "your_alliance": true,
      "won": true
    }
  },
  "score_breakdown": {
    "red": {
      "autoPoints": "<int>",
      "teleopPoints": "<int>",
      "endgamePoints": "<int>",
      "foulPoints": "<int>",
      "totalPoints": "<int>"
    },
    "blue": {
      "autoPoints": "<int>",
      "teleopPoints": "<int>",
      "endgamePoints": "<int>",
      "foulPoints": "<int>",
      "totalPoints": "<int>"
    }
  }
}
```

**Event exists, match not found**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "match_found": false,
  "message": "Event 'vache' exists but match Qualification 999 was not found. Check match_type and match_number.",
  "suggestions": [
    "For elimination matches, try 'Semifinal' or 'Final' instead of 'Elimination'",
    "Match numbers are 1-indexed (first qual is match 1, not 0)",
    "Verify match exists at thebluealliance.com/event/2026vache"
  ]
}
```

**Event code unknown**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "match_found": false,
  "message": "Event 'xyz' not found on TBA for 2026. The event_code must be a TBA event code (e.g., 'caph'), not an abbreviation from log filenames.",
  "similar_events": [
    "<event code>"
  ],
  "hint": "Did you mean one of these event codes?"
}
```


## RevLog Tools


### `list_revlog_signals`

List all available signals from synchronized REV log files. REV logs contain CAN bus data from SPARK MAX/Flex motor controllers. Signals are automatically synchronized with wpilog timestamps when loaded. IMPORTANT: Check sync_confidence to understand timestamp accuracy.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `device_filter` | string | no | Filter signals by device key (e.g., 'SparkMax_1') |
| `signal_filter` | string | no | Filter signals by signal name substring (e.g., 'velocity') |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `signal_count`, `signals[]` `{key, device, signal, unit, sample_count, can_bus, sync_confidence}`, `revlog_count`, `overall_sync_confidence` (`high`/`medium`/`low`/`failed`), `warnings[]`, `_metadata.timing_accuracy_ms` (string range such as `"50-5000"`).
- Signal keys are `REV/<device>/<signal>`; pass them to `get_revlog_data`.
- No matching revlog → `revlog_count: 0`, `sync_in_progress: false`, empty `signals`, warning string. Sync still running → warning asking to retry (or call `wait_for_sync`).


**Examples**

**Revlog found and synced**

Request:
```json
{
  "name": "list_revlog_signals",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "signal_count": 44,
  "signals": [
    {
      "key": "REV/SparkMax_16/AppliedOutput",
      "device": "SparkMax_16",
      "signal": "AppliedOutput",
      "unit": "duty_cycle",
      "sample_count": 34099,
      "can_bus": "rio",
      "sync_confidence": "low"
    },
    {
      "key": "REV/SparkMax_16/Faults",
      "device": "SparkMax_16",
      "signal": "Faults",
      "unit": "",
      "sample_count": 34099,
      "can_bus": "rio",
      "sync_confidence": "low"
    },
    "... (42 more items)"
  ],
  "revlog_count": 1,
  "overall_sync_confidence": "low",
  "warnings": [
    "REV log timestamps are synchronized via statistical correlation (confidence: low). Timing accuracy: ~50-5000ms. Use with caution for precise timing analysis."
  ],
  "_metadata": {
    "timing_accuracy_ms": "50-5000"
  },
  "_execution_time_ms": 0
}
```

**No revlog for this wpilog**

Request:
```json
{
  "name": "list_revlog_signals",
  "arguments": {
    "path": "<logdir>/old/akit_26-03-08_14-08-47_vaale_q64.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "signal_count": 0,
  "signals": [],
  "revlog_count": 0,
  "sync_in_progress": false,
  "warnings": [
    "No REV log files found for this wpilog. Revlog files are discovered automatically within the configured log directory tree. Ensure .revlog files are present and timestamps overlap."
  ],
  "_execution_time_ms": 0
}
```


### `get_revlog_data`

Get data from a REV log signal with timestamps converted to FPGA time. Use list_revlog_signals first to discover available signal keys. IMPORTANT: Timestamps are synchronized via statistical correlation and may have limited accuracy depending on sync confidence level.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `signal_key` | string | yes | Signal key (e.g., 'REV/SparkMax_1/appliedOutput' or 'REV/rio/SparkMax_1/velocity') |
| `start_time` | number | no | Start timestamp in seconds (FPGA time) |
| `end_time` | number | no | End timestamp in seconds (FPGA time) |
| `limit` | integer | no | Maximum number of samples to return (default: `1000`) |
| `include_stats` | boolean | no | Include basic statistics (min, max, mean) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `signal_key`, `sample_count` (returned), `total_samples`, `data[]` `{timestamp, value}` with timestamps already converted to wpilog FPGA seconds, `sync_confidence`, `warnings[]` (accuracy caveat), `_metadata.timing_accuracy_ms`.
- With `include_stats=true`: `statistics` `{min, max, mean, count}` plus `data_quality`/`server_analysis_directives` computed over the **returned** samples (so a small `limit` yields low-confidence directives).


**Examples**

**With statistics**

Request:
```json
{
  "name": "get_revlog_data",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "signal_key": "REV/SparkMax_16/AppliedOutput",
    "start_time": 140,
    "limit": 5,
    "include_stats": true
  }
}
```

Response:
```json
{
  "success": true,
  "signal_key": "REV/SparkMax_16/AppliedOutput",
  "sample_count": 5,
  "total_samples": 21112,
  "data": [
    {
      "timestamp": 140.007,
      "value": 0.5562
    },
    {
      "timestamp": 140.017,
      "value": 0.48660000000000003
    },
    "... (3 more items)"
  ],
  "sync_confidence": "low",
  "statistics": {
    "min": 0.44870000000000004,
    "max": 0.5562,
    "mean": 0.50388,
    "count": 5
  },
  "data_quality": {
    "sample_count": 5,
    "time_span_seconds": 0.04,
    "gap_count": 0,
    "effective_sample_rate_hz": 105.3,
    "quality_score": 0.66
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 5 samples over 0.0 seconds",
    "interpretation_guidance": [
      "Low sample count (5). Statistical measures have high uncertainty.",
      "Short time span (0.0s). Results may not be representative of full-match behavior.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Revlog timestamps are synchronized via cross-correlation (confidence: low). Accuracy depends on sync quality."
    ]
  },
  "warnings": [
    "Timestamps synchronized via correlation (confidence: low). Timing accuracy: ~50-5000ms."
  ],
  "_metadata": {
    "timing_accuracy_ms": "50-5000"
  },
  "_execution_time_ms": 3
}
```

**Unknown signal**

Request:
```json
{
  "name": "get_revlog_data",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "signal_key": "REV/Nope/Nope"
  }
}
```

Response:
```json
{
  "success": false,
  "error": "Signal not found: REV/Nope/Nope. Use list_revlog_signals to see available signals.",
  "_execution_time_ms": 0
}
```


### `sync_status`

Get detailed synchronization status for all synchronized REV log files. Shows confidence levels, timing offsets, and the signal pairs used for synchronization. Use this to understand the accuracy of REV log timestamps.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `include_signal_pairs` | boolean | no | Include details about which signal pairs were used for correlation |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `synchronized`, `revlog_count`, `sync_in_progress`, `overall_confidence` (label) and `overall_confidence_value` (0–1), `revlogs[]`, `warnings[]`, `_metadata` `{timing_accuracy_ms, confidence_description}`.
- Each revlog: `can_bus`, `path`, `device_count`, `signal_count`, `sync` `{method, confidence, confidence_level, offset_microseconds, offset_milliseconds, offset_seconds, explanation, successful, drift_rate_ns_per_sec?, drift_rate_ms_per_hour?, reference_time_sec?}`; `method` ∈ `CROSS_CORRELATION`, `SYSTEM_TIME_ONLY`, `USER_PROVIDED`.
- With `include_signal_pairs=true` each revlog adds `signal_pairs[]` `{wpilog_entry, revlog_signal, correlation, estimated_offset_us, samples_used}`.
- Confidence levels: high (`≥0.85`, accuracy 1–5 ms), medium (`≥0.6`, 5–50 ms), low (`≥0.3`, 50–5000 ms), failed. Warnings vary with the level.


**Examples**

**Synced (low confidence) with signal pairs**

Request:
```json
{
  "name": "sync_status",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "include_signal_pairs": true
  }
}
```

Response:
```json
{
  "success": true,
  "synchronized": true,
  "revlog_count": 1,
  "sync_in_progress": false,
  "overall_confidence": "low",
  "overall_confidence_value": 0.33,
  "revlogs": [
    {
      "can_bus": "rio",
      "path": "<logdir>/vache/REV_20260321_162932.revlog",
      "device_count": 4,
      "signal_count": 44,
      "sync": {
        "method": "SYSTEM_TIME_ONLY",
        "confidence": 0.2,
        "confidence_level": "low",
        "offset_microseconds": 0,
        "offset_milliseconds": 0.0,
        "offset_seconds": 0.0,
        "explanation": "No signal pairs achieved strong correlation. Using system time estimate only.",
        "successful": true
      },
      "signal_pairs": [
        {
          "wpilog_entry": "/RealOutputs/Drive/sampleCount",
          "revlog_signal": "SparkMax_12/AppliedOutput",
          "correlation": 0.056971190216345294,
          "estimated_offset_us": -881242,
          "samples_used": 32104
        },
        {
          "wpilog_entry": "/RealOutputs/Drive/sampleCount",
          "revlog_signal": "SparkMax_16/AppliedOutput",
          "correlation": 0.08253993185171463,
          "estimated_offset_us": 22157576,
          "samples_used": 32104
        },
        "... (3 more items)"
      ]
    }
  ],
  "warnings": [
    "Low synchronization confidence. Timestamps may be inaccurate by several hundred milliseconds. Use with caution for timing-sensitive analysis."
  ],
  "_metadata": {
    "timing_accuracy_ms": "50-5000",
    "confidence_description": "Weak correlation or significant disagreement between signals"
  },
  "_execution_time_ms": 0
}
```

**No revlog**

Request:
```json
{
  "name": "sync_status",
  "arguments": {
    "path": "<logdir>/old/akit_26-03-08_14-08-47_vaale_q64.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "synchronized": false,
  "revlog_count": 0,
  "sync_in_progress": false,
  "overall_confidence": "failed",
  "overall_confidence_value": 0.0,
  "revlogs": [],
  "warnings": [
    "Synchronization failed. REV log timestamps cannot be reliably correlated with wpilog timestamps. Consider providing CAN ID hints or checking that both logs were recorded during the same time period."
  ],
  "_metadata": {
    "timing_accuracy_ms": "unknown",
    "confidence_description": "Could not establish reliable synchronization"
  },
  "_execution_time_ms": 1
}
```


### `set_revlog_offset`

Manually set the synchronization offset for a REV log file. Use this when automatic synchronization fails or when you know the exact offset between revlog and wpilog timestamps. The offset is added to revlog timestamps to convert them to FPGA time. Example: if a revlog event appears 0.5s after the same event in wpilog, set offset_ms to -500.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `offset_ms` | number | yes | Time offset in milliseconds to add to revlog timestamps to get FPGA time |
| `can_bus` | string | no | CAN bus name to apply offset to (e.g., 'rio'). If omitted, applies to the first/only revlog. |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `can_bus`, `offset_ms`, `offset_us`, `previous_offset_ms`, `previous_method`, `new_method` (`"USER_PROVIDED"`). Affects subsequent `get_revlog_data` timestamps for that bus. Error if no revlog is associated with the wpilog.


**Examples**

**Set offset**

Request:
```json
{
  "name": "set_revlog_offset",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "offset_ms": 0.0
  }
}
```

Response:
```json
{
  "success": true,
  "can_bus": "rio",
  "offset_ms": 0.0,
  "offset_us": 0,
  "previous_offset_ms": 0.0,
  "previous_method": "SYSTEM_TIME_ONLY",
  "new_method": "USER_PROVIDED",
  "_execution_time_ms": 0
}
```

**No revlog**

Request:
```json
{
  "name": "set_revlog_offset",
  "arguments": {
    "path": "<logdir>/old/akit_26-03-08_14-08-47_vaale_q64.wpilog",
    "offset_ms": 10
  }
}
```

Response:
```json
{
  "success": false,
  "error": "No REV log files found for this wpilog. Ensure .revlog files are present in the log directory tree with overlapping timestamps.",
  "_execution_time_ms": 1
}
```


### `wait_for_sync`

Wait for background RevLog synchronization to complete. Call this before querying revlog data if synchronization may still be in progress. Returns instantly if sync is already done or no revlogs are present.

**Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `timeout_ms` | integer | no | Maximum time to wait in milliseconds (default: 30000) |
| `path` | string | yes | Path to the log file (from list_available_logs) |


**Response fields**

- `completed` (false on timeout, with a warning), `was_in_progress`, `revlog_count`, `synchronized`. Blocks up to `timeout_ms` (default 30 s) for the background sync started when the log was first loaded.


**Examples**

**Sync was running**

Request:
```json
{
  "name": "wait_for_sync",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "timeout_ms": 120000
  }
}
```

Response:
```json
{
  "success": true,
  "completed": true,
  "was_in_progress": true,
  "revlog_count": 1,
  "synchronized": true,
  "_execution_time_ms": 2273
}
```

**Nothing to wait for**

Request:
```json
{
  "name": "wait_for_sync",
  "arguments": {
    "path": "<logdir>/old/akit_26-03-08_14-08-47_vaale_q64.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "completed": true,
  "was_in_progress": false,
  "revlog_count": 0,
  "synchronized": false,
  "_execution_time_ms": 0
}
```

**Timed out**

Shape derived from source (not captured live); placeholder values in angle brackets.

```json
{
  "success": true,
  "completed": false,
  "was_in_progress": true,
  "revlog_count": 1,
  "synchronized": false,
  "warnings": [
    "RevLog synchronization did not complete within 30000ms. Try again with a longer timeout."
  ]
}
```


## Caveats

Observed while capturing these responses (server behaviour, not documentation errors):

- **`NaN` in output.** When a statistic is undefined (e.g. `compare_entries` on a non-numeric entry) the server writes the bare token `NaN` (Gson lenient mode). That is not valid JSON; strict parsers such as JavaScript `JSON.parse` will reject the whole `text` payload.
- **`power_analysis` / `generate_report` pick the first entry containing "voltage".** On AdvantageKit logs that can be `/SystemStats/5vRail/Voltage`, producing a spurious `brownout_risk: "HIGH"`. Always pass `power_prefix` (e.g. `/SystemStats/Battery`) or use `predict_battery_health`, which looks for battery-specific names.
- **`analyze_auto` did not detect the auto period** on these logs (Autonomous flag raised at 26.9 s, enable at 111.0 s). `get_match_phases` and `get_ds_timeline` handle the same data correctly.
- **`get_ds_timeline` emits an extra `TELEOP_START`** at the auto-enable timestamp (110.99 s) in addition to the correct `AUTO_START` and the real `TELEOP_START` at 135.64 s.
- **`quality_score` saturates at 0.5** for typical full-match 50 Hz series because of the gap penalty, so most directives report `confidence_level: "low"`; the score is only useful as a relative ranking.
- `detect_anomalies.spike_threshold` is declared in the schema but never read; only the IQR outlier test is applied.
- `analyze_swerve.swerve_entries.other` echoes every non-swerve entry name in the log (hundreds of strings).
