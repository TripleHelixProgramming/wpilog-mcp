# wpilog-mcp Tool Response Reference

The JSON every tool of **wpilog-mcp 0.9.0-dev** returns, captured from real logs by running the calls in `src/test/resources/tool-responses/scenarios.json`. To regenerate (see [DEVELOPMENT.md](DEVELOPMENT.md#changing-or-adding-a-tool)):

```
./gradlew test --tests '*.docs.*' -PtoolResponsesLogDir=/path/to/riologs
```

The logs: Team 2363 at VACHE 2026 (qualification 10, with its REV log), a practice session (the robustness review's log), and an AdvantageKit replay (`_sim`) log. Responses are verbatim except that arrays longer than 5 items keep their first 3 and end with `"... (N more items)"`, strings longer than 400 characters are cut the same way, and paths are shown as `<logdir>`, `<exportdir>`, and `~`. What each field means is in [TOOLS.md](TOOLS.md); this file shows what the fields look like on real data.

## Transport envelope

Each result travels as the text of an MCP `tools/call` result (`result.content[0].text`, a JSON string to parse); the server adds `_execution_time_ms` before serializing (not shown below). Every result follows the [result contract](TOOLS.md#result-contract-success-status-and-related-fields): `success` and `status` first, with `reason`, `looked_for`, `hint`, `inputs`, `skipped`, `limits`, and `warnings` where they apply.

## Table of contents

- [Discovery Tools](#discovery-tools)
  - [`get_server_guide`](#get_server_guide)
  - [`suggest_tools`](#suggest_tools)
- [Core Tools](#core-tools)
  - [`list_available_logs`](#list_available_logs)
  - [`list_entries`](#list_entries)
  - [`get_entry_info`](#get_entry_info)
  - [`read_entry`](#read_entry)
  - [`list_loaded_logs`](#list_loaded_logs)
  - [`resolve_signals`](#resolve_signals)
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
  - [`align_entries`](#align_entries)
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
  - [`compare_poses`](#compare_poses)
  - [`pose_corrections`](#pose_corrections)
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

## Discovery Tools

### `get_server_guide`

IMPORTANT: Call this tool first to understand what analysis capabilities are available. Returns a structured overview of all 49 tools organized by category, with usage guidance and anti-patterns to avoid, plus analysis_principles: how to reason about results without confabulating (method, confidence calibration, traps, report format). This server has extensive built-in analysis—don't write custom code when a tool already exists.

**Parameters** ([TOOLS.md](TOOLS.md#get_server_guide))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `category` | string | no | Filter by category: core, query, statistics, robot_analysis, frc_domain, export, tba, revlog, discovery |
| `include_examples` | boolean | no | Include example use cases for each tool (default: true) |

**Example: Overview**

Request:
```json
{
  "name": "get_server_guide",
  "arguments": {}
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "overview": {
    "server_name": "wpilog-mcp",
    "version": "0.9.0-dev",
    "total_tools": 49,
    "purpose": "Parse and analyze FRC robot telemetry logs (.wpilog) and REV motor controller logs (.revlog)"
  },
  "critical_guidance": {
    "primary_rule": "ALWAYS check for a built-in tool before writing custom analysis code. This server has 49 specialized tools covering statistics, power analysis, swerve diagnostics, cycle detection, battery health prediction, and more.",
    "tba_tip": "To get match scores: call list_available_logs (includes TBA data) or get_tba_match_data. TBA data includes autonomous points, final scores, and win/loss results.",
    "statistics_tip": "Use get_statistics (mean, std, percentiles) and time_correlate rather than computing them by hand; for data they cannot read, export_csv and compute externally, citing the export.",
    "match_phases_tip": "NEVER manually parse timestamps to find auto/teleop—use get_match_phases.",
    "source_code_tip": "An entry's name does not establish what it measures. When the robot project's source code is available (it is often the workspace you are working in), read where an entry is logged before attributing it to a mechanism: that code says which mechanism, which units, and whether the value is measured or commanded. Without the code, state the mapping as an assumption or ask the user."
  },
  "analysis_principles": {
    "purpose": "How to reason about this server's results without confabulating. Read once per session; apply the method to causal questions and the traps everywhere.",
    "answer_first": "Lead with the answer to the question actually asked, in one or two sentences. Evidence, alternatives, and confidence follow. For lookups they are optional.",
    "method": {
      "applies_to": "Causal or diagnostic questions ('why', 'what caused', 'is X the problem'). A lookup ('what is the loop time', 'what happened at 87s') needs discovery and a direct answer, nothing more.",
      "steps": [
        "Observe: learn what is actually logged (list_entries, search_entries) and where the phases are (get_match_phases). When the robot project's source code is at hand (the workspace is often that project), find where each entry you will rely on is logged: that code, not the entry's name, says which mechanism it belongs to, its units, and whether it is measured or commanded. Confirm the event in the qu... (59 more characters)",
        "Hypothesize: the candidate cause plus at least one rival. Always include 'normal for this phase or state' and 'logging or timing artifact'; for power questions add 'another load at the same instant'.",
        "Predict before testing: 'if H1, entry E exceeds X during phase P within T of the event; if H2, it does not'. A threshold chosen after seeing the data is not a test; if you change it, say so and why.",
        "... (4 more items)"
      ],
      "pit_mode": "With a match coming up, answer with the fewest tool calls that test the leading cause and one rival, then offer the deeper analysis instead of running it. Run the full loop for post-event analysis or when the answer would change a hardware decision."
    },
    "calibration": {
      "server_confidence_level": "Derived from quality_score, which starts at 1.0 and subtracts, each with a line in data_quality.reasons: up to 0.3 for time in intervals over 5x the median (20% of the span is the full penalty; for a change_only series these are holds, not missing data, but statistics weigh samples, not time), up to 0.2 for timing jitter (median absolute deviation, periodic series), up to 0.2 for non-finite values... (318 more characters)",
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
    "user_proposed_cause": "Treat it as the first hypothesis. Answer it in the first sentence (yes, no, or cannot tell from this log) with the entry and window that decides it. Do not confirm it from one consistent statistic; do not dismiss it without a tool result. Then name the strongest rival and what the data says about it. Example: a compressor draws a steady 10-20 A and rarely causes a brownout alone; check its channel... (320 more characters)",
    "traps": [
      {
        "trap": "Explaining an event that did not happen",
        "fix": "Check first with get_ds_timeline, find_condition, or search_strings. get_ds_timeline names the voltage entry it scanned as brownout_voltage_entry and warns when the log has none; in that case the absence of BROWNOUT events is not evidence, so use power_analysis or find_condition on a voltage entry instead. For console errors, get_ds_timeline gives exact counts (text_event_counts), a distinct-messa... (348 more characters)"
      },
      {
        "trap": "Naming an entry or quoting a value that no tool returned",
        "fix": "Every entry name must come from list_entries or search_entries; every number from a tool result. If it is not logged, say 'not logged'."
      },
      {
        "trap": "Taking an entry's name as proof of what it measures",
        "fix": "A name is a label a programmer chose: currentHeight is the present height, not an electrical current; PhotonVision's targetYaw is a camera reading, not a setpoint; and real logs hold two target module-state arrays beside the measured one, which no name tells apart. Content misleads too: a planned trajectory is a struct array of timestamps and poses, like a camera's observations, and a gyro's struc... (739 more characters)"
      },
      "... (21 more items)"
    ],
    "cross_match": [
      "One log is one sample. compare_matches shows that two matches differ, not why.",
      "compare_matches compares one signal across two logs with percentiles, and resolves scope (enabled, teleop, segment:<i>) in each log's own timeline, so pass scope 'enabled' rather than comparing whole logs: a whole-log max is often the multi-second boot loop, which it flags. For more than two logs, run get_statistics with the same name and scope on each and tabulate the results.",
      "Keep only FMS-connected match logs unless asked; exclude practice, pit, and replay (_sim) logs.",
      "... (3 more items)"
    ],
    "naming": {
      "advantagekit": "/SystemStats/BatteryVoltage, /SystemStats/BrownedOut, /PowerDistribution/ChannelCurrent (array), /PowerDistribution/TotalCurrent, /DriverStation/Enabled, /RealOutputs/<Subsystem>/..., /AdvantageKit/...",
      "wpilib_datalog": "DS:enabled, DS:autonomous, DS:test, DS:estop, DS:joystick0/...; NetworkTables entries prefixed NT:/ (for example NT:/SmartDashboard/...); battery and PDH data only if the team logged them (typically NT:/SmartDashboard/PowerDistribution[<CAN id>]/Voltage, TotalCurrent, and per-channel Chan<N>; the id is 1 for a REV PDH, 0 for a CTRE PDP). Phase, DS, and CAN tools recognize both /DriverStation/... a... (127 more characters)",
      "when_a_tool_finds_nothing": "If analyze_swerve, profile_mechanism, or analyze_cycles reports no matching entries, list the names you searched, run search_entries with the subsystem word (swerve, module, drive, elevator), and find the team's naming in the robot code or ask the user for it. Do not reconstruct the analysis from raw entries by hand.",
      "the_server_does_not_guess": "Tools pick an entry for a role (battery voltage, loop time, robot pose, vision pose, auto chooser, path poses, total current, swerve module states, has-target flags) only when it follows a well-known convention (AdvantageKit, WPILib, CTRE, PathPlanner, YAGSL, Limelight, PhotonVision names), is the only entry of its type, or is passed explicitly. profile_mechanism uses only entries passed explicitl... (678 more characters)"
    },
    "units": "Battery voltage in V (12.0-13.2 V at rest is healthy). Currents in A; ChannelCurrent is an array indexed by channel, TotalCurrent is a scalar. analyze_loop_timing auto-detects ms vs s; a 20 ms nominal loop reported as 0.02 is seconds. Tool timestamps are the log's own clock in seconds (FPGA time, which starts at roboRIO boot, so the first sample is usually not at 0); take the real range from get_e... (83 more characters)",
    "report_format": {
      "pit": "Two to four sentences: what happened (event, time, entry, value); the most likely why, with confidence; the one thing to check before the next match. Caveats only if they change the action.",
      "deep_dive": "For each finding, ranked by evidence strength: Finding; Evidence (log, entry, phase or window, n, statistic); Alternatives considered and the observation that ruled them out; Confidence (high, medium, low) and why; Not determinable from telemetry (what needs physical inspection, code review, or another match); Next measurement.",
      "choose": "Use pit when the user mentions an upcoming match, time pressure, or asks a single yes/no question; otherwise deep_dive.",
      "example": "Finding: 4 of the 6 worst voltage events involved a 149 A stall on the intake or climber. Evidence: battery voltage crossings below 6.8 V from find_condition, per-channel peaks from power_analysis channel_analysis, intake current from read_entry in a 2 s window around each crossing, teleop only, n = 6 events. Alternatives: battery age (ruled out: voltage recovers fully between events); wiring (not... (369 more characters)"
    }
  },
  "architecture": {
    "concurrency": "Thread-safe. Concurrent tool calls from multiple sessions are supported. The log cache, session management, and all shared state use concurrent data structures.",
    "transports": "Stdio transport (single-client, sequential) and HTTP Streamable transport (multi-client, concurrent).",
    "log_loading": "Logs are loaded on demand when referenced by path. No 'active log' concept — each tool call is self-contained. Idle logs are evicted after 30 minutes. Under heap pressure, least-recently-used logs are evicted automatically."
  },
  "categories": [
    {
      "name": "core",
      "description": "Log loading and data access. Start here to discover available data.",
      "anti_pattern": "Don't manually parse log files—use list_entries and read_entry.",
      "tools": [
        {
          "name": "list_available_logs",
          "description": "List WPILOG files with TBA match data enrichment",
          "requires_log": false,
          "example_uses": [
            "Find available log files",
            "Get match scores from TBA",
            "See which matches we won/lost"
          ],
          "related_tools": [
            "list_entries",
            "get_tba_status"
          ]
        },
        {
          "name": "list_entries",
          "description": "List the entries in a log, with types and sample counts",
          "requires_log": true,
          "example_uses": [
            "See what data is logged",
            "Find entry names",
            "Discover available telemetry"
          ],
          "related_tools": [
            "get_entry_info",
            "search_entries"
          ]
        },
        {
          "name": "get_entry_info",
          "description": "Describe an entry: type, samples, struct schema and source, numeric field paths",
          "requires_log": true,
          "example_uses": [
            "Check entry data type",
            "See sample count for an entry",
            "Find the numeric fields of a struct entry",
            "Check why an entry did not decode"
          ],
          "related_tools": [
            "read_entry",
            "list_entries",
            "list_struct_types"
          ]
        },
        "... (5 more items)"
      ],
      "tool_count": 8
    },
    {
      "name": "query",
      "description": "Search and filter log data. Find specific entries, types, and events.",
      "anti_pattern": "Don't iterate through all entries manually—use search_entries or find_condition.",
      "tools": [
        {
          "name": "search_entries",
          "description": "Search entries by type, name pattern, or sample count",
          "requires_log": true,
          "example_uses": [
            "Find all Pose2d entries",
            "Find entries with enough data",
            "Search by name pattern"
          ],
          "related_tools": [
            "list_entries",
            "get_types"
          ]
        },
        {
          "name": "get_types",
          "description": "List all data types in the log",
          "requires_log": true,
          "example_uses": [
            "See what types of data are logged",
            "Find struct types"
          ],
          "related_tools": [
            "search_entries"
          ]
        },
        {
          "name": "find_condition",
          "description": "Find timestamps where values cross thresholds",
          "requires_log": true,
          "example_uses": [
            "When did voltage drop below 11V?",
            "Find brownout events",
            "Detect threshold crossings"
          ],
          "related_tools": [
            "power_analysis",
            "detect_anomalies"
          ]
        },
        {
          "name": "search_strings",
          "description": "Search string entries for text patterns",
          "requires_log": true,
          "example_uses": [
            "Find error messages",
            "Search console output",
            "Find specific warnings"
          ],
          "related_tools": [
            "can_health",
            "generate_report"
          ]
        }
      ],
      "tool_count": 4
    },
    {
      "name": "statistics",
      "description": "Statistical analysis on numeric data. Compute stats, find correlations, detect anomalies.",
      "anti_pattern": "Use get_statistics and time_correlate rather than computing statistics or correlations by hand; for data they cannot read, export_csv and compute externally, citing the export.",
      "tools": [
        {
          "name": "get_statistics",
          "description": "Compute comprehensive statistics on numeric entries",
          "requires_log": true,
          "example_uses": [
            "Get battery voltage statistics",
            "Analyze motor current distribution",
            "Compute percentiles"
          ],
          "related_tools": [
            "compare_entries",
            "detect_anomalies"
          ]
        },
        {
          "name": "compare_entries",
          "description": "Compare two entries (e.g., setpoint vs actual)",
          "requires_log": true,
          "example_uses": [
            "Compare commanded vs actual velocity",
            "Validate replay outputs",
            "Find tracking error"
          ],
          "related_tools": [
            "get_statistics",
            "time_correlate"
          ]
        },
        {
          "name": "detect_anomalies",
          "description": "Find outliers using IQR method",
          "requires_log": true,
          "example_uses": [
            "Find current spikes",
            "Detect unusual sensor readings",
            "Identify outliers"
          ],
          "related_tools": [
            "get_statistics",
            "find_peaks"
          ]
        },
        "... (4 more items)"
      ],
      "tool_count": 7
    },
    "... (6 more items)"
  ],
  "common_workflows": [
    {
      "name": "Basic Match Analysis",
      "steps": [
        "1. list_available_logs - See available logs with TBA match data",
        "2. list_entries - Discover available telemetry (pass log path)",
        "3. get_match_phases - Find auto/teleop timing",
        "4. generate_report - Get overview of match health",
        "5. Use specialized tools as needed (power_analysis, analyze_swerve, etc.)"
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
        "5. can_health - Check for CAN errors (often accompany brownouts)"
      ]
    }
  ]
}
```

### `suggest_tools`

Given a natural language description of what you want to analyze, this tool recommends the most relevant tools and provides a suggested workflow. Use this when unsure which tools to use for a specific analysis task.

**Parameters** ([TOOLS.md](TOOLS.md#suggest_tools))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `task` | string | yes | Natural language description of what you want to analyze (e.g., 'check why our auto was inconsistent' or 'investigate brownout during teleop') |
| `max_suggestions` | integer | no | Maximum number of tools to suggest (default: 5) |

**Example: A diagnosis task**

Request:
```json
{
  "name": "suggest_tools",
  "arguments": {
    "task": "why did the robot brown out during teleop",
    "max_suggestions": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "task": "why did the robot brown out during teleop",
  "suggestions": [
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
    {
      "tool": "get_tba_match_data",
      "description": "Query match scores and results from The Blue Alliance",
      "relevance_score": 2,
      "category": "tba",
      "example_uses": [
        "Get match scores",
        "Check autonomous points",
        "Find alliance results"
      ],
      "related_tools": [
        "list_available_logs",
        "get_tba_status"
      ]
    }
  ],
  "suggestion_count": 2,
  "suggested_workflow": [
    "1. list_available_logs - Find available logs (includes TBA match data)",
    "2. get_match_phases - Detect match phases (auto/teleop) from DriverStation data",
    "3. get_tba_match_data - Query match scores and results from The Blue Alliance"
  ],
  "anti_patterns": [
    "Don't manually parse timestamps for match phases—use get_match_phases"
  ]
}
```

## Core Tools

### `list_available_logs`

List WPILOG files available in the configured log directories with friendly names, newest first, paged: log_count is the number matching the filters, offset/limit select a page (default 50), has_more says whether another page exists. log_directories names the directories searched; one that could not be read (a drive not mounted, no permission) is listed in skipped with the reason, and the result is partial: its logs are missing from the list, not absent. Filters: name (substring of the file or friendly name), event (event code, e.g. VACHE), match_type (qm, sf, f, p, ...), since (a date like 2026-03-20: logs from then on). IMPORTANT: When TBA is configured, this tool automatically enriches each listed log with match data including alliance scores, win/loss results, and actual match times. Check the 'tba' field in each log entry for match outcomes—don't guess from telemetry! tba_enrichment.available says whether The Blue Alliance answered for this page; when false, its reason (not configured, an outage, a rejected key) is why no log carries a tba field. A tba field's match_key and lookup_method say which TBA match it came from: an 'Elimination N' log is read as double-elimination bracket match N (sfNm1) since 2023, and a finals log by the log's time (nearest_time). Use this tool first to find logs and get match results, then pass the path to other tools.

**Parameters** ([TOOLS.md](TOOLS.md#list_available_logs))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | no | Only logs whose file or friendly name contains this (case-insensitive) |
| `event` | string | no | Only logs from this event code (case-insensitive) |
| `match_type` | string | no | Only this match type (qm, sf, f, p, e, ...) |
| `since` | string | no | Only logs from this date on: 2026-03-20, or an ISO-8601 instant |
| `offset` | integer | no | Logs to skip |
| `limit` | integer | no | Maximum logs to return (max 500) |

**Example: First page, one event**

Request:
```json
{
  "name": "list_available_logs",
  "arguments": {
    "event": "vache",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "log_directories": [
    "<logdir>"
  ],
  "log_count": 37,
  "total_logs": 96,
  "offset": 0,
  "returned": 3,
  "has_more": true,
  "tba_enrichment": {
    "available": false,
    "reason": "not configured. In VS Code, run 'WPILog Analyzer: Set The Blue Alliance API Key'; for the standalone server, set tba_key in ~/.wpilog-mcp/servers.yaml (or pass -tba-key, or set TBA_API_KEY)"
  },
  "metadata_cache": {
    "size": 96,
    "hits": 0,
    "misses": 96
  },
  "logs": [
    {
      "friendly_name": "VACHE Elimination (sim) 4",
      "path": "<logdir>/vache/session_55/akit_26-03-22_18-15-22_vache_e4_sim.wpilog",
      "filename": "akit_26-03-22_18-15-22_vache_e4_sim.wpilog",
      "event": "VACHE",
      "match_type": "Elimination (sim)",
      "match_number": 4,
      "team_number": 2363,
      "size_bytes": 36375831,
      "last_modified": 1774240859575
    },
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
      "friendly_name": "VACHE",
      "path": "<logdir>/vache/session_56/akit_26-03-22_18-44-53_vache.wpilog",
      "filename": "akit_26-03-22_18-44-53_vache.wpilog",
      "event": "VACHE",
      "team_number": 2363,
      "size_bytes": 1785856,
      "last_modified": 1774219502000
    }
  ],
  "limits": {
    "logs": {
      "total": 37,
      "returned": 3,
      "limit": 3
    }
  }
}
```

### `list_entries`

List all entries in a log file. Returns log metadata (time range, duration, truncation status) and entry list with types and sample counts. Optionally filter by name pattern; no_match, naming the pattern, when it matches no entry. Struct and array entries hold numeric fields that numeric tools address by path (see get_entry_info).

**Parameters** ([TOOLS.md](TOOLS.md#list_entries))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pattern` | string | no | Optional pattern to filter entry names (substring match) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Filtered by pattern**

Request:
```json
{
  "name": "list_entries",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "pattern": "SystemStats/Battery"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
  "entry_count": 2,
  "time_range_sec": {
    "start": 11.897573,
    "end": 347.901903,
    "duration": 336.00433
  },
  "truncated": true,
  "warning": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered.",
  "entries": [
    {
      "name": "/SystemStats/BatteryCurrent",
      "type": "double",
      "sample_count": 11149
    },
    {
      "name": "/SystemStats/BatteryVoltage",
      "type": "double",
      "sample_count": 11735
    }
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

### `get_entry_info`

Describe one entry: type, metadata, sample count, time range, and three representative samples (first, middle, last among non-empty values; non_empty_sample_count says how many values are not empty arrays or strings). For a struct entry: its schema, where the schema came from (logged by this log, WPILib's, or an assumed template), fields, and numeric_leaf_paths, the numeric fields that other tools can address as entry + path (e.g. /Vision/Camera0/PoseObservations[*].tagCount). decode_problem reports records that could not be decoded and why.

**Parameters** ([TOOLS.md](TOOLS.md#get_entry_info))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | The entry name (e.g., '/Vision/Summary/ObservationScore') |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: A struct array: schema, source, leaf paths**

Request:
```json
{
  "name": "get_entry_info",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "name": "/Vision/Camera3/PoseObservations"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "name": "/Vision/Camera3/PoseObservations",
  "type": "struct:PoseObservation[]",
  "metadata": "{\"source\":\"AdvantageKit\"}",
  "sample_count": 4225,
  "numeric_leaf_paths": [
    "[*].timestamp",
    "[*].pose.translation.x",
    "[*].pose.translation.y",
    "... (15 more items)"
  ],
  "struct": {
    "name": "PoseObservation",
    "source": "logged",
    "source_note": "decoded by the schema this log records",
    "valid": true,
    "size_bytes": 88,
    "schema": "double timestamp;Pose3d pose;double ambiguity;int32 tagCount;double averageTagDistance;enum {MEGATAG_1=0, MEGATAG_2=1, PHOTONVISION=2} int32 type;",
    "schema_entry": "/.schema/struct:PoseObservation",
    "fields": [
      {
        "name": "timestamp",
        "type": "double"
      },
      {
        "name": "pose",
        "type": "Pose3d"
      },
      {
        "name": "ambiguity",
        "type": "double"
      },
      "... (3 more items)"
    ],
    "is_array": true
  },
  "time_range_sec": {
    "start": 12.658122,
    "end": 1581.224901
  },
  "non_empty_sample_count": 2377,
  "sample_values": [
    {
      "timestamp_sec": 29.47904,
      "value": [
        {
          "timestamp": 29.380902,
          "pose": {
            "translation": {
              "x": 2.4888400730684945,
              "y": 6.490135622176096,
              "z": 0.03916949986882143
            },
            "rotation": {
              "q": {
                "w": 0.6981147061342652,
                "x": -0.007706172118600557,
                "y": -0.007957029222134024,
                "z": 0.7159002428245888
              },
              "_derived": {
                "roll": -0.022154274755881342,
                "pitch": -7.613725238884171E-5,
                "yaw": 1.5959519238585596,
                "roll_deg": -1.2693464416852231,
                "pitch_deg": -0.004362343225602975,
                "yaw_deg": 91.44130954287958
              }
            }
          },
          "ambiguity": 0.01793597238512702,
          "tagCount": 1,
          "averageTagDistance": 3.3327902215729983,
          "type": {
            "value": 2,
            "label": "PHOTONVISION"
          }
        }
      ]
    },
    {
      "timestamp_sec": 468.346144,
      "value": [
        {
          "timestamp": 468.257249,
          "pose": {
            "translation": {
              "x": 2.8045258264355564,
              "y": 3.421340843183166,
              "z": -0.0159595118070619
            },
            "rotation": {
              "q": {
                "w": -0.7463074510244032,
                "x": 0.0033692345381648174,
                "y": -0.01576061557959452,
                "z": 0.6654062216425671
              },
              "_derived": {
                "roll": -0.02601104100455118,
                "pitch": 0.01904186115023119,
                "yaw": -1.4565550011774147,
                "roll_deg": -1.4903228703025078,
                "pitch_deg": 1.0910182779823743,
                "yaw_deg": -83.45445419613851
              }
            }
          },
          "ambiguity": 0.0052213632261841145,
          "tagCount": 1,
          "averageTagDistance": 1.4970702222957084,
          "type": {
            "value": 2,
            "label": "PHOTONVISION"
          }
        }
      ]
    },
    {
      "timestamp_sec": 1581.131149,
      "value": [
        {
          "timestamp": 1581.078339,
          "pose": {
            "translation": {
              "x": 2.4507293938415318,
              "y": 3.749131507377399,
              "z": 0.41835692899251314
            },
            "rotation": {
              "q": {
                "w": 0.993296415284395,
                "x": -0.015794386435016235,
                "y": -0.08335457073552235,
                "z": 0.078516140237589
              },
              "_derived": {
                "roll": -0.04508523771348965,
                "pitch": -0.16384343464879986,
                "yaw": 0.16146639312960517,
                "roll_deg": -2.5831938393270066,
                "pitch_deg": -9.387537306303749,
                "yaw_deg": 9.25134285952653
              }
            }
          },
          "ambiguity": 0.0,
          "tagCount": 1,
          "averageTagDistance": 2.0894358451532193,
          "type": {
            "value": 2,
            "label": "PHOTONVISION"
          }
        }
      ]
    }
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "entries_read": [
      "/Vision/Camera3/PoseObservations"
    ]
  }
}
```

### `read_entry`

Read values from an entry, in time order, with optional time range and paging: total_in_range is the true count, offset/limit select a page, has_more says whether another page exists, and limits.samples gives total (after offset) and returned. Struct values are decoded by the log's own schema: nested objects with the schema's field names, enum fields as {value, label}, rotations with a _derived block (degrees; roll, pitch, yaw). A NaN or infinite value is returned as the string 'NaN', 'Infinity', or '-Infinity'. Records that could not be decoded are reported in warnings. One page is not the whole signal: use get_statistics, find_condition, or find_peaks for claims about a window.

**Parameters** ([TOOLS.md](TOOLS.md#read_entry))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | The entry name |
| `start_time` | number | no | Start timestamp in seconds (optional) |
| `end_time` | number | no | End timestamp in seconds (optional) |
| `limit` | integer | no | Maximum number of samples to return (default: 100, max: 10000) |
| `offset` | integer | no | Number of samples to skip |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: A schema-decoded PoseObservation**

Request:
```json
{
  "name": "read_entry",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "name": "/Vision/Camera3/PoseObservations",
    "start_time": 370,
    "limit": 1
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "name": "/Vision/Camera3/PoseObservations",
  "type": "struct:PoseObservation[]",
  "total_in_range": 2781,
  "returned_count": 1,
  "offset": 0,
  "limit": 1,
  "has_more": true,
  "samples": [
    {
      "timestamp_sec": 370.047375,
      "value": [
        {
          "timestamp": 369.970913,
          "pose": {
            "translation": {
              "x": 3.1804531628245463,
              "y": 4.545514780485817,
              "z": 0.3667373536507883
            },
            "rotation": {
              "q": {
                "w": 0.744332272405248,
                "x": -0.05028579830371463,
                "y": -0.05119802447605093,
                "z": 0.6639424440678179
              },
              "_derived": {
                "roll": -0.143340490539234,
                "pitch": -0.009443072488214159,
                "yaw": 1.4574302248222912,
                "roll_deg": -8.212805141233014,
                "pitch_deg": -0.5410481992107721,
                "yaw_deg": 83.50460081712
              }
            }
          },
          "ambiguity": 0.0,
          "tagCount": 1,
          "averageTagDistance": 2.8575662739829073,
          "type": {
            "value": 2,
            "label": "PHOTONVISION"
          }
        }
      ]
    }
  ],
  "limits": {
    "samples": {
      "total": 2781,
      "returned": 1,
      "limit": 1
    }
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "entries_read": [
      "/Vision/Camera3/PoseObservations"
    ]
  }
}
```

### `list_loaded_logs`

List the log files currently loaded in the server's cache (path, entry count, duration) and the cache status: how many are loaded and the JVM heap they share (logs are evicted when idle or when the heap runs short). Logs load on demand, so an empty list is normal.

**Parameters** ([TOOLS.md](TOOLS.md#list_loaded_logs))

None.

**Example: After loading**

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
  "status": "ok",
  "loaded_count": 0,
  "logs": [],
  "cache": {
    "loaded_count": 0,
    "heap_used_mb": 31,
    "heap_max_mb": 512
  }
}
```

### `resolve_signals`

Show which entry plays each role in this log: robot_enabled, autonomous, test_mode, fms_attached, battery_voltage, total_current, brownout_flag, brownout_threshold, loop_time_full, loop_time_user, robot_pose, vision_pose, auto_chooser, path_setpoint, path_actual, module_states_measured, module_states_setpoint, chassis_speeds_measured, chassis_speeds_setpoint, gyro_yaw, vision_pose_observations, vision_targets, can_bus, console_text, alerts. For each: the entry (or entries, or a value); match, how it was chosen (explicit, convention: a well-known AdvantageKit/WPILib/CTRE/PathPlanner/YAGSL/vision-library name, type: the only entry of its type or schema, heuristic, or none); the basis; the other candidates best first; ambiguous when another candidate ranked as well (the one declared first wins); and the tools that use it. The server does not guess: a heuristic role has no entry, needs_confirmation, and candidates, and the tools skip it. A word in a name is not evidence of what an entry holds. Establish which candidate is right from the robot's source code, where the entry is logged (else get_entry_info, read_entry, or the user), and pass it with the tool's parameter (voltage_entry, entry, pose_entry, chooser_entry, measured_entry, ...). needs_confirmation lists those roles. These are the choices the tools make; each tool's result records the entries it used under inputs.entries.

**Parameters** ([TOOLS.md](TOOLS.md#resolve_signals))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `roles` | array | no | Only these roles (default: all) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Every role**

Request:
```json
{
  "name": "resolve_signals",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
  "roles": {
    "robot_enabled": {
      "description": "DriverStation enabled state",
      "entry": "/DriverStation/Enabled",
      "match": "convention",
      "basis": "DriverStation enabled entry (AdvantageKit /DriverStation/..., or WPILib DS:...)",
      "candidates": [
        "/DriverStation/Enabled"
      ],
      "used_by": "get_match_phases, get_ds_timeline, every scope"
    },
    "autonomous": {
      "description": "DriverStation autonomous mode",
      "entry": "/DriverStation/Autonomous",
      "match": "convention",
      "basis": "DriverStation autonomous entry (AdvantageKit /DriverStation/..., or WPILib DS:...)",
      "candidates": [
        "/DriverStation/Autonomous"
      ],
      "used_by": "get_match_phases, analyze_auto"
    },
    "test_mode": {
      "description": "DriverStation test mode",
      "entry": "/DriverStation/Test",
      "match": "convention",
      "basis": "DriverStation test entry (AdvantageKit /DriverStation/..., or WPILib DS:...)",
      "candidates": [
        "/DriverStation/Test"
      ],
      "used_by": "get_match_phases (scope 'test')"
    },
    "fms_attached": {
      "description": "Whether the FMS was attached",
      "entry": "/DriverStation/FMSAttached",
      "match": "convention",
      "basis": "DriverStation FMS-attached entry (AdvantageKit /DriverStation/..., or WPILib DS:...)",
      "candidates": [
        "/DriverStation/FMSAttached"
      ],
      "used_by": "get_match_phases (match detection)"
    },
    "battery_voltage": {
      "description": "Battery voltage",
      "entry": "/SystemStats/BatteryVoltage",
      "match": "convention",
      "basis": "a battery voltage by convention (BatteryVoltage; Voltage under PowerDistribution, PDH, PDP, or Battery)",
      "candidates": [
        "/SystemStats/BatteryVoltage",
        "/PowerDistribution/Voltage",
        "/RealOutputs/PDH/Voltage"
      ],
      "used_by": "power_analysis, get_ds_timeline, predict_battery_health, generate_report (voltage_entry)"
    },
    "total_current": {
      "description": "Total robot current",
      "entry": "/PowerDistribution/TotalCurrent",
      "match": "convention",
      "basis": "TotalCurrent (AdvantageKit /PowerDistribution/TotalCurrent, WPILib power distribution)",
      "candidates": [
        "/PowerDistribution/TotalCurrent"
      ],
      "used_by": "predict_battery_health (total_current_entry)"
    },
    "brownout_flag": {
      "description": "The roboRIO's brownout flag",
      "entry": "/SystemStats/BrownedOut",
      "match": "convention",
      "basis": "a boolean named BrownedOut or IsBrownedOut (AdvantageKit /SystemStats/BrownedOut)",
      "candidates": [
        "/SystemStats/BrownedOut"
      ],
      "used_by": "power_analysis, get_ds_timeline, predict_battery_health, generate_report"
    },
    "brownout_threshold": {
      "description": "The roboRIO's brownout voltage setting",
      "entry": "/SystemStats/BrownoutVoltage",
      "value": 6.75,
      "match": "convention",
      "basis": "logged",
      "used_by": "power_analysis (brownout_threshold), get_ds_timeline, predict_battery_health, generate_report"
    },
    "loop_time_full": {
      "description": "Robot loop time (whole cycle)",
      "entry": "/RealOutputs/LoggedRobot/FullCycleMS",
      "match": "convention",
      "basis": "AdvantageKit LoggedRobot/FullCycleMS",
      "candidates": [
        "/RealOutputs/LoggedRobot/FullCycleMS"
      ],
      "used_by": "analyze_loop_timing (entry)"
    },
    "loop_time_user": {
      "description": "Robot loop time (user code)",
      "entry": "/RealOutputs/LoggedRobot/UserCodeMS",
      "match": "convention",
      "basis": "AdvantageKit LoggedRobot/UserCodeMS",
      "candidates": [
        "/RealOutputs/LoggedRobot/UserCodeMS"
      ],
      "used_by": "analyze_loop_timing"
    },
    "robot_pose": {
      "description": "Robot pose (odometry or estimator)",
      "entry": "/RealOutputs/Drive/Pose",
      "match": "convention",
      "basis": "a robot pose by convention (DriveState/Pose, Odometry/Robot, Drive/Pose, EstimatedPose, RobotPose, PathPlanner/currentPose)",
      "candidates": [
        "/RealOutputs/Launcher/TurretPose",
        "/RealOutputs/Drive/Pose",
        "/RealOutputs/AutoSelector/AutonomousInitialPose"
      ],
      "used_by": "analyze_vision (pose_entry), compare_poses (pose_entry), pose_corrections (pose_entry), analyze_swerve (odometry_entry)"
    },
    "vision_pose": {
      "description": "Vision pose estimate (a scalar pose under a vision path)",
      "entry": null,
      "match": "none",
      "basis": "no scalar struct:Pose2d or struct:Pose3d with at least two samples under a vision, camera, PhotonVision, or Limelight path",
      "used_by": "analyze_swerve (vision_entry)"
    },
    "auto_chooser": {
      "description": "The selected autonomous routine",
      "entry": null,
      "match": "heuristic",
      "basis": "no entry follows a known convention for this role; choosers and strings named like a selected auto routine (by name only, not chosen)",
      "needs_confirmation": true,
      "candidates": [
        "/RealOutputs/AutoSelector/SelectedAutoMode"
      ],
      "used_by": "analyze_auto (chooser_entry)"
    },
    "path_setpoint": {
      "description": "Path-following setpoint pose",
      "entry": null,
      "match": "none",
      "basis": "no PathPlanner/targetPose or Odometry/TrajectorySetpoint",
      "used_by": "analyze_auto (path_setpoint_entry)"
    },
    "path_actual": {
      "description": "Path-following actual pose",
      "entry": "/RealOutputs/Drive/Pose",
      "match": "convention",
      "basis": "the robot pose: a robot pose by convention (DriveState/Pose, Odometry/Robot, Drive/Pose, EstimatedPose, RobotPose, PathPlanner/currentPose)",
      "candidates": [
        "/RealOutputs/Launcher/TurretPose",
        "/RealOutputs/Drive/Pose",
        "/RealOutputs/AutoSelector/AutonomousInitialPose"
      ],
      "used_by": "analyze_auto (path_actual_entry)"
    },
    "module_states_measured": {
      "description": "Measured swerve module states",
      "entry": "/RealOutputs/SwerveStates/Measured",
      "match": "convention",
      "basis": "SwerveStates/Measured (AdvantageKit swerve template)",
      "candidates": [
        "/RealOutputs/SwerveStates/Measured",
        "/RealOutputs/SwerveStates/SetpointsOptimized",
        "/RealOutputs/SwerveStates/Setpoints"
      ],
      "used_by": "analyze_swerve (measured_entry)"
    },
    "module_states_setpoint": {
      "description": "Swerve module setpoints",
      "entry": "/RealOutputs/SwerveStates/SetpointsOptimized",
      "match": "convention",
      "basis": "SwerveStates/SetpointsOptimized beside the measured entry (AdvantageKit swerve template)",
      "candidates": [
        "/RealOutputs/SwerveStates/SetpointsOptimized",
        "/RealOutputs/SwerveStates/Setpoints"
      ],
      "used_by": "analyze_swerve (setpoint_entry)"
    },
    "chassis_speeds_measured": {
      "description": "Measured chassis speeds",
      "entry": "/RealOutputs/SwerveChassisSpeeds/Measured",
      "match": "convention",
      "basis": "struct:ChassisSpeeds, named 'measured'",
      "candidates": [
        "/RealOutputs/SwerveChassisSpeeds/Measured"
      ],
      "used_by": "pose_corrections (chassis_speeds_entry); find_condition, get_statistics (by name)"
    },
    "chassis_speeds_setpoint": {
      "description": "Chassis speed setpoints",
      "entry": "/RealOutputs/SwerveChassisSpeeds/Setpoints",
      "match": "convention",
      "basis": "struct:ChassisSpeeds, named 'setpoint'",
      "candidates": [
        "/RealOutputs/SwerveChassisSpeeds/Setpoints"
      ],
      "used_by": "compare_entries (by name)"
    },
    "gyro_yaw": {
      "description": "Gyro yaw",
      "entry": "/Drive/Gyro/YawPosition",
      "match": "convention",
      "basis": "a yaw entry (Rotation2d first) under a gyro-like path (gyro, pigeon, navx, canandgyro, imu) (address the angle as /Drive/Gyro/YawPosition.value)",
      "candidates": [
        "/Drive/Gyro/YawPosition",
        "/Drive/Gyro/YawVelocityRadPerSec"
      ],
      "used_by": "no tool resolves it; compare_entries and align_entries take it by name"
    },
    "vision_pose_observations": {
      "description": "Vision pose observation streams",
      "entries": [
        "/Vision/Camera0/PoseObservations",
        "/Vision/Camera3/PoseObservations",
        "/Vision/Camera1/PoseObservations",
        "/Vision/Camera2/PoseObservations"
      ],
      "match": "convention",
      "basis": "struct:PoseObservation[] entries (the AdvantageKit vision template's record: a timestamp and a pose), one per camera",
      "candidates": [
        "/Vision/Camera0/PoseObservations",
        "/Vision/Camera3/PoseObservations",
        "/Vision/Camera1/PoseObservations",
        "/Vision/Camera2/PoseObservations",
        "/RealOutputs/AutoSelector/AutonomousInitialTrajectory"
      ],
      "used_by": "analyze_vision"
    },
    "vision_targets": {
      "description": "Vision target streams and has-target entries",
      "entries": [
        "/Vision/Camera2/LatestTargetObservation",
        "/Vision/Camera1/LatestTargetObservation",
        "/Vision/Camera0/LatestTargetObservation",
        "/Vision/Camera3/LatestTargetObservation"
      ],
      "match": "convention",
      "basis": "struct:TargetObservation entries with yaw and pitch fields (the AdvantageKit vision template's record), and has-target entries by convention (Limelight's <table>/tv, PhotonVision's photonvision/<camera>/hasTarget)",
      "candidates": [
        "/Vision/Camera2/LatestTargetObservation",
        "/Vision/Camera1/LatestTargetObservation",
        "/Vision/Camera0/LatestTargetObservation",
        "/Vision/Camera3/LatestTargetObservation"
      ],
      "used_by": "analyze_vision"
    },
    "can_bus": {
      "description": "CAN bus counters",
      "entries": [
        "/SystemStats/CANBus",
        "/RealOutputs/CANBus/CANHD",
        "/RealOutputs/CANBus/CAN2"
      ],
      "match": "convention",
      "basis": "CAN counter entries by field name, one bus per parent path",
      "candidates": [
        "/SystemStats/CANBus",
        "/RealOutputs/CANBus/CANHD",
        "/RealOutputs/CANBus/CAN2"
      ],
      "used_by": "analyze_can_bus, can_health"
    },
    "console_text": {
      "description": "Console and message text (string entries)",
      "entries": [
        "/SystemStats/NTClients/photonvision@2/IPAddress",
        "/SystemStats/NTClients/photonvision@1/IPAddress",
        "/RealMetadata/GitBranch",
        "... (45 more items)"
      ],
      "match": "type",
      "basis": "every string entry",
      "candidates": [
        "/SystemStats/NTClients/photonvision@2/IPAddress",
        "/SystemStats/NTClients/photonvision@1/IPAddress",
        "/RealMetadata/GitBranch",
        "... (7 more items)"
      ],
      "candidate_count": 48,
      "used_by": "search_strings, get_ds_timeline, can_health, generate_report"
    },
    "alerts": {
      "description": "Alerts (string[] entries)",
      "entries": [
        "/RealOutputs/PathPlanner/warnings",
        "/RealOutputs/PhotonAlerts/warnings",
        "/RealOutputs/Alerts/infos",
        "... (19 more items)"
      ],
      "match": "type",
      "basis": "every string[] entry",
      "candidates": [
        "/RealOutputs/PathPlanner/warnings",
        "/RealOutputs/PhotonAlerts/warnings",
        "/RealOutputs/Alerts/infos",
        "... (7 more items)"
      ],
      "candidate_count": 22,
      "used_by": "search_strings, get_ds_timeline, can_health, generate_report"
    }
  },
  "unresolved": [
    "vision_pose",
    "auto_chooser",
    "path_setpoint"
  ],
  "needs_confirmation": [
    "auto_chooser"
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/DriverStation/Autonomous",
      "/DriverStation/Enabled",
      "/DriverStation/FMSAttached",
      "... (6 more items)"
    ]
  }
}
```

### `list_struct_types`

List struct types and how they decode. Struct values are decoded from each log's own schemas (/.schema/struct:<Name> entries), so any struct the log records a schema for decodes, including a team's own. With path: every struct type the log records or uses, with source (logged; wpilib or assumed when the log has no schema for it: assumed layouts may not match the team's struct), size, schema, fields, numeric leaf paths, and the entries that use it. Without path: the fallback schemas used when a log records none.

**Parameters** ([TOOLS.md](TOOLS.md#list_struct_types))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | no | Path to the log file (from list_available_logs); omit to list only the fallback schemas |

**Example: A log's struct types**

Request:
```json
{
  "name": "list_struct_types",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  },
  "struct_type_count": 12,
  "struct_types": [
    {
      "name": "Rotation2d",
      "source": "logged",
      "source_note": "decoded by the schema this log records",
      "valid": true,
      "size_bytes": 8,
      "schema": "double value",
      "schema_entry": "/.schema/struct:Rotation2d",
      "fields": [
        {
          "name": "value",
          "type": "double"
        }
      ],
      "numeric_leaf_paths": [
        "value",
        "_derived.degrees"
      ],
      "entry_count": 23,
      "entries": [
        "/RealOutputs/Launcher/HorizontalAimAngle",
        "/RealOutputs/Drive/Heading",
        "/Drive/Module1/TurnPosition",
        "... (17 more items)"
      ],
      "limits": {
        "entries": {
          "total": 23,
          "returned": 20,
          "limit": 20
        }
      }
    },
    {
      "name": "SwerveModuleState",
      "source": "logged",
      "source_note": "decoded by the schema this log records",
      "valid": true,
      "size_bytes": 16,
      "schema": "double speed;Rotation2d angle",
      "schema_entry": "/.schema/struct:SwerveModuleState",
      "fields": [
        {
          "name": "speed",
          "type": "double"
        },
        {
          "name": "angle",
          "type": "Rotation2d"
        }
      ],
      "numeric_leaf_paths": [
        "speed",
        "angle.value",
        "angle._derived.degrees"
      ],
      "entry_count": 3,
      "entries": [
        "/RealOutputs/SwerveStates/Measured",
        "/RealOutputs/SwerveStates/SetpointsOptimized",
        "/RealOutputs/SwerveStates/Setpoints"
      ],
      "limits": {
        "entries": {
          "total": 3,
          "returned": 3,
          "limit": 20
        }
      }
    },
    {
      "name": "Pose2d",
      "source": "logged",
      "source_note": "decoded by the schema this log records",
      "valid": true,
      "size_bytes": 24,
      "schema": "Translation2d translation;Rotation2d rotation",
      "schema_entry": "/.schema/struct:Pose2d",
      "fields": [
        {
          "name": "translation",
          "type": "Translation2d"
        },
        {
          "name": "rotation",
          "type": "Rotation2d"
        }
      ],
      "numeric_leaf_paths": [
        "translation.x",
        "translation.y",
        "rotation.value",
        "rotation._derived.degrees"
      ],
      "entry_count": 23,
      "entries": [
        "/RealOutputs/Field/Regions/RedLeftBump",
        "/RealOutputs/Field/Regions/BlueLeftTrench",
        "/RealOutputs/Launcher/TurretPose",
        "... (17 more items)"
      ],
      "limits": {
        "entries": {
          "total": 23,
          "returned": 20,
          "limit": 20
        }
      }
    },
    "... (9 more items)"
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `health_check`

Verify server is working correctly and get system status.

**Parameters** ([TOOLS.md](TOOLS.md#health_check))

None.

**Example: Server status**

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
  "status": "ok",
  "server_version": "0.9.0-dev",
  "loaded_logs": 2,
  "tba_available": false,
  "revlog_sync_in_progress": true,
  "jvm_memory": {
    "used_mb": 242,
    "total_mb": 340,
    "max_mb": 512,
    "free_mb": 97
  },
  "jvm_heap_used_mb": 242,
  "sync_disk_cache": {
    "enabled": true,
    "directory": "~/th/wpilog-mcp/build/test-disk-cache",
    "cached_files": 0,
    "total_size_mb": 0
  },
  "parsed_log_disk_cache": {
    "used_by_load_path": false,
    "enabled": true,
    "directory": "~/th/wpilog-mcp/build/test-disk-cache",
    "cached_files": 0,
    "total_size_mb": 0,
    "format_version": 4
  }
}
```

## Query Tools

### `search_entries`

Search for entries by type (substring of the type, e.g. 'Pose3d' or 'double'), name (case-insensitive substring), and minimum sample count. Returns the matching entry names in name order, or no_match with the criteria when none match.

**Parameters** ([TOOLS.md](TOOLS.md#search_entries))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `type` | string | no | Filter by type (substring match, e.g., 'Pose3d') |
| `pattern` | string | no | Filter by name containing this string |
| `min_samples` | integer | no | Minimum number of samples required |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Voltage entries**

Request:
```json
{
  "name": "search_entries",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "pattern": "Voltage",
    "min_samples": 100
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "match_count": 5,
  "matches": [
    "/RealOutputs/PDH/Voltage",
    "/SystemStats/3v3Rail/Voltage",
    "/SystemStats/5vRail/Voltage",
    "/SystemStats/6vRail/Voltage",
    "/SystemStats/BatteryVoltage"
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

### `get_types`

Get all data types used in the log file and which entries use each type.

**Parameters** ([TOOLS.md](TOOLS.md#get_types))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Entry types**

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
  "status": "ok",
  "type_count": 25,
  "types": [
    {
      "type": "boolean",
      "entry_count": 98,
      "entries": [
        "/AllianceSelector/AgreementInAllianceInputs",
        "/AllianceSelector/AllianceChanged",
        "/Drive/Gyro/Calibrated",
        "... (95 more items)"
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
    {
      "type": "double",
      "entry_count": 119,
      "entries": [
        "/Drive/Gyro/YawVelocityRadPerSec",
        "/Drive/Module0/DriveAppliedVolts",
        "/Drive/Module0/DriveCurrentAmps",
        "... (116 more items)"
      ]
    },
    "... (22 more items)"
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

### `find_condition`

Find when a numeric or boolean entry satisfies a condition (value <op> threshold; booleans read as 1/0), or when several do at once: conditions {all: [...]} or {any: [...]} of {name, field, operator, threshold} (e.g. disabled AND stationary: /DriverStation/Enabled eq 0 with a chassis speed abs_lt 0.05). Each value holds until the entry's next sample, so entries logged only on change combine correctly. Operators: lt, lte, gt, gte, eq, ne, and abs_lt/abs_lte/abs_gt/abs_gte on the absolute value. Returns transitions (each time the condition becomes true) and intervals (start, end, duration; an interval still true at the end of a window ends with end_reason window_end), plus total_true_sec and fraction_of_window. transition_count and interval_count are true totals; lists are cut at limit, with limits giving total and returned. Useful for questions like 'When did battery voltage drop below 11V, and for how long?' The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'. Thresholds on an angle apply to the value as logged (not unwrapped). scope and windows restrict the time (each window is searched on its own; window_sec is the time searched once every entry has a value), and the intervals returned can be passed as windows to the statistics tools.

**Parameters** ([TOOLS.md](TOOLS.md#find_condition))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | no | Entry name (e.g., /Robot/BatteryVoltage), optionally with a field path (e.g. /RealOutputs/Drive/Pose.translation.x); not with conditions |
| `field` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name) |
| `angle` | string | no | Declare the values an angle in 'radians' or 'degrees', as in the statistics tools; thresholds still apply to the value as logged (not unwrapped) |
| `operator` | string | no | Comparison operator: lt (<), lte (<=), gt (>), gte (>=), eq (==), ne (!=), or abs_lt, abs_lte, abs_gt, abs_gte (on the absolute value) |
| `threshold` | number | no | Threshold value to compare against |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `limit` | integer | no | Maximum number of transitions and intervals to return |
| `conditions` | object | no | Compound condition instead of name/operator/threshold: {"all": [...]} (every condition true) or {"any": [...]} (at least one), each item {name, field?, angle?, operator, threshold} |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Battery below 7.5 V while enabled**

Request:
```json
{
  "name": "find_condition",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "operator": "lt",
    "threshold": 7.5,
    "scope": "enabled",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "name": "/SystemStats/BatteryVoltage",
  "condition": "/SystemStats/BatteryVoltage < 7.5",
  "transition_count": 97,
  "transitions": [
    {
      "timestamp_sec": 137.115378,
      "value": 7.42201025390625
    },
    {
      "timestamp_sec": 137.284257,
      "value": 7.239818603515625
    },
    {
      "timestamp_sec": 142.966994,
      "value": 7.4722700195312495
    }
  ],
  "limits": {
    "transitions": {
      "total": 97,
      "returned": 3,
      "limit": 3
    },
    "intervals": {
      "total": 97,
      "returned": 3,
      "limit": 3
    }
  },
  "interval_count": 97,
  "intervals": [
    {
      "start": 137.115378,
      "end": 137.134002,
      "duration": 0.01862400000001685,
      "end_reason": "condition_false"
    },
    {
      "start": 137.284257,
      "end": 137.380068,
      "duration": 0.09581099999999765,
      "end_reason": "condition_false"
    },
    {
      "start": 142.966994,
      "end": 143.079221,
      "duration": 0.11222699999999008,
      "end_reason": "condition_false"
    }
  ],
  "total_true_sec": 8.16930099999982,
  "window_sec": 163.358887,
  "samples_evaluated": 6584,
  "inputs": {
    "entries": {
      "entry": "/SystemStats/BatteryVoltage"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    }
  },
  "fraction_of_window": 0.05000830472112496,
  "data_quality": {
    "sample_count": 6584,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6584 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Each entry's value is held until its next sample: for a change-only entry that is what the log means; for a periodic entry, a gap reported in data_quality is time over which the condition was assumed unchanged."
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

**Example: Disabled and stationary (compound)**

Request:
```json
{
  "name": "find_condition",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "conditions": {
      "all": [
        {
          "name": "/DriverStation/Enabled",
          "operator": "eq",
          "threshold": 0
        },
        {
          "name": "/RealOutputs/SwerveChassisSpeeds/Measured.vx",
          "operator": "abs_lt",
          "threshold": 0.05
        },
        {
          "name": "/RealOutputs/SwerveChassisSpeeds/Measured.vy",
          "operator": "abs_lt",
          "threshold": 0.05
        }
      ]
    }
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "condition": "(/DriverStation/Enabled == 0.0) AND (|/RealOutputs/SwerveChassisSpeeds/Measured.vx| < 0.05) AND (|/RealOutputs/SwerveChassisSpeeds/Measured.vy| < 0.05)",
  "combine": "all",
  "conditions": [
    "/DriverStation/Enabled == 0.0",
    "|/RealOutputs/SwerveChassisSpeeds/Measured.vx| < 0.05",
    "|/RealOutputs/SwerveChassisSpeeds/Measured.vy| < 0.05"
  ],
  "transition_count": 4,
  "transitions": [
    {
      "timestamp_sec": 10.82707,
      "values": [
        0.0,
        0.0,
        0.0
      ]
    },
    {
      "timestamp_sec": 359.161586,
      "values": [
        0.0,
        0.0,
        0.0
      ]
    },
    {
      "timestamp_sec": 744.929426,
      "values": [
        0.0,
        0.0,
        0.0
      ]
    },
    {
      "timestamp_sec": 840.133016,
      "values": [
        0.0,
        0.0,
        0.0
      ]
    }
  ],
  "limits": {
    "transitions": {
      "total": 4,
      "returned": 4,
      "limit": 100
    },
    "intervals": {
      "total": 4,
      "returned": 4,
      "limit": 100
    }
  },
  "interval_count": 4,
  "intervals": [
    {
      "start": 10.82707,
      "end": 40.207135,
      "duration": 29.380065000000002,
      "end_reason": "condition_false"
    },
    {
      "start": 359.161586,
      "end": 395.209541,
      "duration": 36.047955,
      "end_reason": "condition_false"
    },
    {
      "start": 744.929426,
      "end": 791.534465,
      "duration": 46.60503899999992,
      "end_reason": "condition_false"
    },
    {
      "start": 840.133016,
      "end": 995.211072,
      "duration": 155.07805599999995,
      "end_reason": "condition_false"
    }
  ],
  "total_true_sec": 267.11111499999987,
  "window_sec": 1577.446349,
  "samples_evaluated": 87720,
  "inputs": {
    "entries": {
      "condition0": "/DriverStation/Enabled",
      "condition1": "/RealOutputs/SwerveChassisSpeeds/Measured",
      "condition2": "/RealOutputs/SwerveChassisSpeeds/Measured"
    },
    "fields": {
      "condition1": ".vx",
      "condition2": ".vy"
    }
  },
  "fraction_of_window": 0.16933134693888716,
  "data_quality": {
    "sample_count": 8,
    "time_span_seconds": 986.85,
    "sampling": "change_only",
    "gap_count": 2,
    "max_gap_ms": 349719.9,
    "effective_sample_rate_hz": 0.0,
    "quality_score": 0.4,
    "reasons": [
      "67.8% of the time span is in 2 intervals longer than 5x the median with no new value (longest 349720 ms): the value held or was not logged, and statistics weigh samples, not time",
      "only 8 finite samples (fewer than 100)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 8 samples over 986.9 seconds",
    "interpretation_guidance": [
      "Low sample count (8). Statistical measures have high uncertainty.",
      "Timing is consistent with values logged only when they change (sampling change_only): a long interval between samples means the value held, not missing data; a sample count is a count of changes.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Each entry's value is held until its next sample: for a change-only entry that is what the log means; for a periodic entry, a gap reported in data_quality is time over which the condition was assumed unchanged."
    ]
  },
  "warnings": [
    "Low data quality (score: 0.40): statistics in this result should be treated as preliminary; directly observed events (a logged flag, a threshold crossing, an error line) are not affected."
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  }
}
```

### `search_strings`

List or search the text a log holds, completely and in time order across all entries: string entries (console output, messages); string[] entries such as WPILib Alerts (/RealOutputs/Alerts/warnings), where each message is one match from when it appeared (timestamp_sec) to when it cleared (end_sec, duration_sec; active_at_log_end when it never did); and the string values of json entries. Each match says its source (string, alert, json). Filters: pattern (case-insensitive substring, or a regex with regex=true), level (error, warning, info, or any; an alert's level comes from its entry name, other text is classified exactly as get_ds_timeline counts it), entry_pattern, start_time/end_time (an alert matches when it was present in the range). Results are paged: total_matches is the full count, offset/limit select a page, has_more says whether more remain, so nothing is silently dropped. collapse_repeats folds runs of identical samples that are adjacent in the same entry into one match with repeat_count. Each match carries its level and the matching line (line is cut at 200 chars; value at max_value_chars, with *_truncated flags). Regex mode is case-insensitive with ^/$ anchoring to lines; a pattern that backtracks for more than a second on one value is rejected.

**Parameters** ([TOOLS.md](TOOLS.md#search_strings))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pattern` | string | no | Text to search for: case-insensitive substring, or a regular expression when regex=true. Omit to list every string sample (use level/entry_pattern/time window to narrow). |
| `regex` | boolean | no | Treat pattern as a Java regular expression (case-insensitive). Default: false |
| `level` | string | no | Only messages classified as 'error' or 'warning' (same rules as get_ds_timeline), 'info' (info alerts), or 'any' (default) |
| `entry_pattern` | string | no | Optional: filter which entries to search (e.g., 'Console' or 'Output') |
| `start_time` | number | no | Start timestamp in seconds (optional) |
| `end_time` | number | no | End timestamp in seconds (optional) |
| `offset` | integer | no | Number of matches to skip, for paging (default: 0) |
| `limit` | integer | no | Maximum matches to return per call (default: 100, max: 1000) |
| `collapse_repeats` | boolean | no | Fold runs of identical samples that are adjacent in the same entry's stream into one match with repeat_count and last_timestamp_sec; any other sample in between (even one the filters exclude) ends the run. Default: false |
| `max_value_chars` | integer | no | Truncate each returned value to this many characters (default: 500) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: An alert, as one appearance**

Request:
```json
{
  "name": "search_strings",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "pattern": "camera 3 is disconnected"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "pattern": "camera 3 is disconnected",
  "regex": false,
  "level": "any",
  "total_matches": 2,
  "offset": 0,
  "limit": 100,
  "returned": 2,
  "match_count": 2,
  "has_more": false,
  "matches": [
    {
      "timestamp_sec": 10.82707,
      "entry": "/RealOutputs/Alerts/warnings",
      "source": "alert",
      "end_sec": 26.719375,
      "duration_sec": 15.892304999999999,
      "level": "warning",
      "line": "Vision camera 3 is disconnected.",
      "value": "Vision camera 3 is disconnected."
    },
    {
      "timestamp_sec": 737.677712,
      "entry": "/RealOutputs/Alerts/warnings",
      "source": "alert",
      "end_sec": 783.804327,
      "duration_sec": 46.1266149999999,
      "level": "warning",
      "line": "Vision camera 3 is disconnected.",
      "value": "Vision camera 3 is disconnected."
    }
  ],
  "limits": {
    "matches": {
      "total": 2,
      "returned": 2,
      "limit": 100
    }
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "entries_read": [
      "/AllianceSelector/AllianceFromSwitch",
      "/DriverStation/EventName",
      "/DriverStation/GameSpecificMessage",
      "... (7 more items)"
    ],
    "entries_read_total": 60
  }
}
```

**Example: Errors, first page**

Request:
```json
{
  "name": "search_strings",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "level": "error",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "regex": false,
  "level": "error",
  "total_matches": 5,
  "offset": 0,
  "limit": 3,
  "returned": 3,
  "match_count": 3,
  "has_more": true,
  "matches": [
    {
      "timestamp_sec": 123.711426,
      "entry": "/RealOutputs/Console",
      "source": "string",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out",
      "value": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
    },
    {
      "timestamp_sec": 124.739764,
      "entry": "/RealOutputs/Console",
      "source": "string",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out",
      "value": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
    },
    {
      "timestamp_sec": 143.156946,
      "entry": "/RealOutputs/Console",
      "source": "string",
      "level": "error",
      "line": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL not found on NetworkTables. Double check that your camera ...",
      "line_truncated": true,
      "value": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL not found on NetworkTables. Double check that your camera names match!\n\tat org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512)"
    }
  ],
  "limits": {
    "matches": {
      "total": 5,
      "returned": 3,
      "limit": 3
    }
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/AllianceSelector/AllianceFromSwitch",
      "/DriverStation/EventName",
      "/DriverStation/GameSpecificMessage",
      "... (7 more items)"
    ],
    "entries_read_total": 71
  }
}
```

## Statistics Tools

### `get_statistics`

BUILT-IN statistics: Get min, max, mean, median, std_dev, percentiles for a numeric entry or field. NEVER compute these manually—always use this tool! Supports optional time range filtering (start_time, end_time). Includes data quality metrics and sample size for confidence assessment. The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'. A [*] path pools every element (count is values, records_in_window is records). For an angle, min/max/mean/percentiles are of the unwrapped angle within the window (so max - min is how far it turned) and angle gives the circular mean and standard deviation, and the number of wraps when the signal is single-valued (a [*] pool has no order to unwrap). Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters** ([TOOLS.md](TOOLS.md#get_statistics))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | The entry name, optionally with a field path (e.g. /RealOutputs/Drive/Pose.translation.x) |
| `field` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name) |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Battery while enabled**

Request:
```json
{
  "name": "get_statistics",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "scope": "enabled"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "name": "/SystemStats/BatteryVoltage",
  "count": 6584,
  "min": 6.680678710937499,
  "max": 12.655308349609374,
  "mean": 9.307719026401005,
  "median": 9.18110205078125,
  "std_dev": 1.165562482750581,
  "q1": 8.458617919921874,
  "q3": 10.142320068359375,
  "iqr": 1.6837021484375008,
  "p5": 7.503682373046875,
  "p95": 11.348554443359374,
  "inputs": {
    "entries": {
      "entry": "/SystemStats/BatteryVoltage"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    }
  },
  "data_quality": {
    "sample_count": 6584,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6584 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use detect_anomalies to check for outliers that may skew these statistics",
      "Use time_correlate to check relationships with other entries"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

**Example: Heading wander while disabled (field path, angle)**

Request:
```json
{
  "name": "get_statistics",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "name": "/RealOutputs/Drive/Pose.rotation._derived.degrees",
    "start_time": 362,
    "end_time": 394
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "name": "/RealOutputs/Drive/Pose.rotation._derived.degrees",
  "field": ".rotation._derived.degrees",
  "angle": {
    "resultant_length": 0.9996581121128535,
    "circular_mean": 91.30692936006177,
    "circular_std": 1.4983615250584292,
    "unit": "degrees",
    "unwrapped": true,
    "wraps": 0
  },
  "count": 1324,
  "min": 86.73580462096726,
  "max": 94.06973425133883,
  "mean": 91.30687006886761,
  "median": 91.44618049359703,
  "std_dev": 1.498904305085374,
  "q1": 90.19054233707965,
  "q3": 92.5544378038906,
  "iqr": 2.363895466810945,
  "p5": 88.89432883711288,
  "p95": 93.5595033802367,
  "inputs": {
    "entries": {
      "entry": "/RealOutputs/Drive/Pose"
    },
    "fields": {
      "entry": ".rotation._derived.degrees"
    },
    "window": {
      "start": 362.0,
      "end": 394.0
    }
  },
  "data_quality": {
    "sample_count": 1324,
    "time_span_seconds": 31.98,
    "sampling": "periodic",
    "gap_count": 1,
    "max_gap_ms": 125.4,
    "effective_sample_rate_hz": 48.6,
    "quality_score": 0.98,
    "reasons": [
      "0.4% of the time span is in 1 gap longer than 5x the median interval (longest 125 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 4% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 1324 samples over 32.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use detect_anomalies to check for outliers that may skew these statistics",
      "Use time_correlate to check relationships with other entries"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  }
}
```

### `compare_entries`

Compare two numeric entries or fields: RMSE and maximum absolute difference, evaluated at the denser signal's timestamps with the other linearly interpolated (no extrapolation), plus the number of compared samples. Two angles (e.g. a pose heading and a gyro's Rotation2d) are compared by their shortest angular difference, in the first one's unit. With a scope or window, only the reference signal's samples inside it are compared. The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'. Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters** ([TOOLS.md](TOOLS.md#compare_entries))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name1` | string | yes | First entry (optionally with a field path) |
| `name2` | string | yes | Second entry (optionally with a field path) |
| `field1` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name), for name1 |
| `field2` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name), for name2 |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it (both signals) |
| `max_lag_sec` | number | no | Also search for the time shift that best aligns the two signals, from -max_lag_sec to +max_lag_sec (a positive lag means the second signal follows the first) |
| `lag_step_sec` | number | no | Lag search step (default: the first signal's median sample interval) |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Pose heading against the gyro (angles)**

Request:
```json
{
  "name": "compare_entries",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "name1": "/RealOutputs/Drive/Pose.rotation.value",
    "name2": "/Drive/Gyro/YawPosition.value",
    "scope": "enabled"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "rmse": 1.6505281631977604,
  "max_difference": 1.904312119286189,
  "samples_compared": 48566,
  "reference_entry": "/RealOutputs/Drive/Pose.rotation.value",
  "angle_unit": "radians",
  "difference": "shortest angular difference",
  "inputs": {
    "entries": {
      "entry1": "/RealOutputs/Drive/Pose",
      "entry2": "/Drive/Gyro/YawPosition"
    },
    "fields": {
      "entry1": ".rotation.value",
      "entry2": ".value"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          40.207135,
          359.161586
        ],
        [
          395.209541,
          744.929426
        ],
        [
          791.534465,
          840.133016
        ],
        [
          995.211072,
          1588.273419
        ]
      ],
      "window_count": 4,
      "total_sec": 1310.3352340000001
    }
  },
  "data_quality": {
    "sample_count": 48423,
    "time_span_seconds": 1310.27,
    "sampling": "periodic",
    "gap_count": 421,
    "max_gap_ms": 287.6,
    "effective_sample_rate_hz": 47.3,
    "quality_score": 0.91,
    "reasons": [
      "4.3% of the time span is in 421 gaps longer than 5x the median interval (longest 288 ms)",
      "irregular timing: intervals deviate from the 21.1 ms median by 6% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 48423 samples over 1310.3 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "RMSE is scale-dependent — compare to the entry's typical range for context"
    ],
    "suggested_followup": [
      "Use get_statistics on each entry individually for baseline context"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  }
}
```

### `detect_anomalies`

Detect anomalies in a numeric entry within an optional time window: outliers outside iqr_multiplier x IQR beyond Q1/Q3 (Tukey fences), and, when spike_threshold is given, spikes: sample-to-sample jumps larger than spike_threshold (in the entry's units), with spike_interval_sec, the time between consecutive spikes (median, p95; the cadence of steps such as vision corrections). anomaly_count is the true total; the list is sorted by time (default) or severity (distance beyond the fence, or jump size) and cut at limit, with limits.anomalies giving total and returned. Boot transients and disabled periods count unless the window excludes them: pass scope 'enabled' or windows from get_match_phases. The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'. Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters** ([TOOLS.md](TOOLS.md#detect_anomalies))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name (optionally with a field path) |
| `field` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name) |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it |
| `iqr_multiplier` | number | no | IQR multiplier (default 1.5) |
| `spike_threshold` | number | no | Flag sample-to-sample jumps larger than this, in the entry's units (off by default) |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `sort` | string | no | 'time' (default) or 'severity' |
| `limit` | integer | no | Max anomalies to return |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Loop time while enabled, by severity**

Request:
```json
{
  "name": "detect_anomalies",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/RealOutputs/LoggedRobot/FullCycleMS",
    "scope": "enabled",
    "sort": "severity",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "anomaly_count": 672,
  "outlier_count": 672,
  "non_finite_count": 0,
  "bounds": {
    "q1": 15.397,
    "q3": 20.868,
    "iqr": 5.470999999999998,
    "lower": 7.190500000000002,
    "upper": 29.074499999999997
  },
  "samples_analyzed": 6678,
  "sort": "severity",
  "anomalies": [
    {
      "timestamp_sec": 110.991153,
      "value": 387.862,
      "type": "above_upper_bound",
      "severity": 358.7875
    },
    {
      "timestamp_sec": 144.253715,
      "value": 161.961,
      "type": "above_upper_bound",
      "severity": 132.8865
    },
    {
      "timestamp_sec": 128.239852,
      "value": 149.792,
      "type": "above_upper_bound",
      "severity": 120.7175
    }
  ],
  "limits": {
    "anomalies": {
      "total": 672,
      "returned": 3,
      "limit": 3
    }
  },
  "name": "/RealOutputs/LoggedRobot/FullCycleMS",
  "inputs": {
    "entries": {
      "entry": "/RealOutputs/LoggedRobot/FullCycleMS"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    }
  },
  "data_quality": {
    "sample_count": 6678,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.5 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6678 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use find_peaks if looking for signal extrema rather than statistical outliers"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `find_peaks`

Find local maxima and minima (peaks and valleys) in numeric data, in time order. maxima_count and minima_count are the true totals; each list is cut at limit (limits gives total and returned). A flat signal has none. The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'. Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters** ([TOOLS.md](TOOLS.md#find_peaks))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name (optionally with a field path) |
| `field` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name) |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it |
| `type` | string | no | Type: 'max', 'min', or 'both' |
| `min_height_diff` | number | no | Minimum height difference from neighbors to count as a peak. Filters out noise |
| `limit` | integer | no | Max peaks to return |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Voltage dips while enabled**

Request:
```json
{
  "name": "find_peaks",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/SystemStats/BatteryVoltage",
    "type": "min",
    "min_height_diff": 0.3,
    "scope": "enabled",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "name": "/SystemStats/BatteryVoltage",
  "inputs": {
    "entries": {
      "entry": "/SystemStats/BatteryVoltage"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    }
  },
  "samples_analyzed": 6584,
  "minima_count": 581,
  "minima": [
    {
      "timestamp_sec": 111.487347,
      "value": 8.8418486328125,
      "height_diff": 3.1412353515625
    },
    {
      "timestamp_sec": 111.589762,
      "value": 10.506703369140626,
      "height_diff": 0.4900327148437498
    },
    {
      "timestamp_sec": 112.139732,
      "value": 8.8921083984375,
      "height_diff": 1.9664133300781241
    }
  ],
  "limits": {
    "minima": {
      "total": 581,
      "returned": 3,
      "limit": 3
    }
  },
  "data_quality": {
    "sample_count": 6584,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6584 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use get_statistics to understand baseline before interpreting peaks"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `rate_of_change`

Compute rate of change (derivative) of numeric data over time, in the signal's units per second. The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'. Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters** ([TOOLS.md](TOOLS.md#rate_of_change))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name (optionally with a field path) |
| `field` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name) |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `window_size` | integer | no | Smoothing window (default 1) |
| `limit` | integer | no | Max samples to return |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Pose x velocity**

Request:
```json
{
  "name": "rate_of_change",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "name": "/RealOutputs/Drive/Pose.translation.x",
    "start_time": 362,
    "end_time": 363,
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "name": "/RealOutputs/Drive/Pose.translation.x",
  "statistics": {
    "avg_rate": 0.09540196696033469,
    "rate_count": 41
  },
  "samples": [
    {
      "timestamp_sec": 362.021551,
      "rate": 0.0
    },
    {
      "timestamp_sec": 362.041733,
      "rate": 3.5342542305745908
    },
    {
      "timestamp_sec": 362.062527,
      "rate": 3.5358074454823165
    }
  ],
  "limits": {
    "samples": {
      "total": 41,
      "returned": 3,
      "limit": 3
    }
  },
  "inputs": {
    "entries": {
      "entry": "/RealOutputs/Drive/Pose"
    },
    "fields": {
      "entry": ".translation.x"
    },
    "window": {
      "start": 362.0,
      "end": 363.0
    }
  },
  "data_quality": {
    "sample_count": 41,
    "time_span_seconds": 0.98,
    "sampling": "periodic",
    "gap_count": 0,
    "effective_sample_rate_hz": 48.6,
    "quality_score": 0.68,
    "reasons": [
      "irregular timing: intervals deviate from the 20.6 ms median by 4% (median absolute deviation)",
      "only 41 finite samples (fewer than 100)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 41 samples over 1.0 seconds",
    "interpretation_guidance": [
      "Low sample count (41). Statistical measures have high uncertainty.",
      "Short time span (1.0s). Results may not be representative of full-match behavior.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Derivatives amplify noise — increase window_size for smoother results"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  }
}
```

### `time_correlate`

BUILT-IN correlation: NEVER compute correlation manually—always use this tool! Computes Pearson correlation coefficient with statistical significance (p-value, from a t test on the effective sample size: consecutive samples are autocorrelated, so n is reduced by their lag-1 autocorrelations, lag1_autocorrelation, reported as effective_sample_size). Handles timestamp alignment automatically via linear interpolation. Returns sample count for confidence assessment. The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'. Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected. Correlation does not imply causation—consider confounding variables.

**Parameters** ([TOOLS.md](TOOLS.md#time_correlate))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name1` | string | yes | First entry (optionally with a field path) |
| `name2` | string | yes | Second entry (optionally with a field path) |
| `field1` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name), for name1 |
| `field2` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name), for name2 |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it (both signals) |
| `max_lag_sec` | number | no | Also search for the time shift that best aligns the two signals, from -max_lag_sec to +max_lag_sec (a positive lag means the second signal follows the first) |
| `lag_step_sec` | number | no | Lag search step (default: the first signal's median sample interval) |
| `start_time` | number | no | Start time |
| `end_time` | number | no | End time |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Voltage against drive current, with lag search**

Request:
```json
{
  "name": "time_correlate",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name1": "/SystemStats/BatteryVoltage",
    "name2": "/Drive/Module0/DriveCurrentAmps",
    "scope": "enabled",
    "max_lag_sec": 0.2
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "sample_count": 6584,
  "inputs": {
    "entries": {
      "entry1": "/SystemStats/BatteryVoltage",
      "entry2": "/Drive/Module0/DriveCurrentAmps"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    }
  },
  "lag_search": {
    "lags_evaluated": 19,
    "lag_step_sec": 0.020553000000006705,
    "max_lag_sec": 0.18497700000006034,
    "best_lag_sec": 0.18497700000006034,
    "correlation_at_best_lag": -0.4460458742281736,
    "samples_at_best_lag": 6584,
    "correlation_at_zero_lag": -0.6278741197591584,
    "note": "Positive lag: the second signal follows the first (the second at t + lag pairs with the first at t). A best lag at the edge of the range may lie beyond it. Shared timing (both follow the match phase) also aligns signals."
  },
  "correlation": -0.6278741197591579,
  "lag1_autocorrelation": {
    "entry1": 0.9442714751659614,
    "entry2": 0.8729479520997179
  },
  "effective_sample_size": 634.1116479239121,
  "p_value": 7.952059357346021E-71,
  "p_value_basis": "two-sided t test on the correlation with the effective sample size n(1 - r1x r1y)/(1 + r1x r1y) (Bretherton et al. 1999), since consecutive samples are autocorrelated; still assumes the pairing is otherwise independent, so treat it as a rough guide",
  "data_quality": {
    "sample_count": 6644,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.6% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.5 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6644 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Correlation does not imply causation — consider confounding variables"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `align_entries`

Sample several numeric signals at common times, to read them side by side or to measure one against another. Times: every record of at (default: the first signal's own samples), or the timestamps stored inside it (time_field, e.g. [*].timestamp of a PoseObservation[] entry: sample the robot pose when the camera saw the target, not when the result arrived), within start_time/end_time, scope, and windows. interpolation: previous (the value in force; default, right for values logged when they change), linear (no extrapolation), or nearest; angles interpolate along the shortest arc. Rows [timestamp_sec, v1, v2, ...] are paged (offset/limit; limits.rows gives the total); a value is null where a signal had none, and unaligned counts those per signal. With difference=true and two signals, difference_statistics summarizes signal 1 minus signal 2 (two angles: their shortest difference): count, mean, std_dev, min, max, median, p5, p95, mean_abs, rmse. Returns no_match when no sample time falls in scope. The name is an entry, or an entry with a field path appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element (/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so crossing +-180 degrees is not a jump; for an angle logged as a plain number (a gyro yaw double), pass angle: 'radians' or 'degrees'.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation.

**Parameters** ([TOOLS.md](TOOLS.md#align_entries))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `names` | array | yes | The signals to sample: entry names, optionally with field paths (1-8) |
| `at` | string | no | Entry whose record times (or time_field values) are the sample times; default: the first signal |
| `time_field` | string | no | Path inside at whose values are timestamps in seconds (e.g. '[*].timestamp') |
| `interpolation` | string | no | 'previous' (default), 'linear', or 'nearest' |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it (every signal) |
| `difference` | boolean | no | With two signals: statistics of signal 1 minus signal 2 |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `offset` | integer | no | Rows to skip |
| `limit` | integer | no | Maximum rows to return (max 2000) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Robot pose at each camera observation's own time**

Request:
```json
{
  "name": "align_entries",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "names": [
      "/RealOutputs/Drive/Pose.translation.x",
      "/RealOutputs/Drive/Pose.translation.y"
    ],
    "at": "/Vision/Camera3/PoseObservations",
    "time_field": "[*].timestamp",
    "interpolation": "linear",
    "start_time": 370,
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "interpolation": "linear",
  "time_source": "values of /Vision/Camera3/PoseObservations[*].timestamp",
  "columns": [
    "timestamp_sec",
    "/RealOutputs/Drive/Pose.translation.x",
    "/RealOutputs/Drive/Pose.translation.y"
  ],
  "total_rows": 1551,
  "rows": [
    [
      370.222919,
      3.049737056063155,
      4.808017982274853
    ],
    [
      370.350862,
      3.0398357338758006,
      4.859571507965534
    ],
    [
      370.850907,
      2.897195404422489,
      5.082699343930449
    ]
  ],
  "limits": {
    "rows": {
      "total": 1551,
      "returned": 3,
      "limit": 3
    }
  },
  "unaligned": {
    "/RealOutputs/Drive/Pose.translation.x": 0,
    "/RealOutputs/Drive/Pose.translation.y": 0
  },
  "inputs": {
    "entries": {
      "signal1": "/RealOutputs/Drive/Pose",
      "signal2": "/RealOutputs/Drive/Pose"
    },
    "fields": {
      "signal1": ".translation.x",
      "signal2": ".translation.y"
    },
    "window": {
      "start": 370.0
    }
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  }
}
```

## Robot Analysis Tools

### `get_match_phases`

ALWAYS use this tool to find when the robot was enabled and in which mode—NEVER manually parse timestamps! Returns segments: every interval of constant robot state (enabled/disabled/unknown) with its mode (auto/teleop/test) while enabled and why it ended (disabled, mode_change, log_end, ...). A log can hold any number of enabled segments (practice sessions); DriverStation values logged only on change hold until the next sample. When a segment pattern is an FMS match (FMS attached, or an autonomous segment followed within seconds by teleop), matches lists its autonomous/teleop/endgame phases and phases repeats the first one; endgame comes from the season's timing (basis game_timing). Use segment or phase bounds as start_time/end_time for other tools.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#get_match_phases))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Segments and match**

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
  "status": "ok",
  "source": "DriverStation",
  "log_start": 11.897573,
  "log_end": 347.901903,
  "log_duration": 336.00433,
  "inputs": {
    "entries": {
      "enabled": "/DriverStation/Enabled",
      "autonomous": "/DriverStation/Autonomous",
      "test": "/DriverStation/Test",
      "fms_attached": "/DriverStation/FMSAttached"
    }
  },
  "season": {
    "year": 2026,
    "basis": "log_clock:/SystemStats/EpochTimeMicros"
  },
  "segments": [
    {
      "start": 11.897573,
      "end": 110.991153,
      "duration": 99.09358,
      "state": "disabled",
      "end_reason": "enabled"
    },
    {
      "start": 110.991153,
      "end": 131.611537,
      "duration": 20.620384,
      "state": "enabled",
      "mode": "auto",
      "end_reason": "disabled"
    },
    {
      "start": 131.611537,
      "end": 135.642118,
      "duration": 4.030581000000012,
      "state": "disabled",
      "end_reason": "enabled"
    },
    {
      "start": 135.642118,
      "end": 278.380621,
      "duration": 142.738503,
      "state": "enabled",
      "mode": "teleop",
      "end_reason": "disabled"
    },
    {
      "start": 278.380621,
      "end": 347.901903,
      "duration": 69.52128199999999,
      "state": "disabled",
      "end_reason": "log_end"
    }
  ],
  "enabled_segment_count": 2,
  "enabled_time_sec": 163.358887,
  "matches": [
    {
      "autonomous": {
        "start": 110.991153,
        "end": 131.611537,
        "duration": 20.620384
      },
      "teleop": {
        "start": 135.642118,
        "end": 278.380621,
        "duration": 142.738503
      },
      "endgame": {
        "start": 248.38062100000002,
        "end": 278.380621,
        "duration": 30.0,
        "basis": "game_timing"
      },
      "basis": "fms_attached",
      "complete": true,
      "expected_timing": {
        "season": 2026,
        "auto_sec": 20.0,
        "teleop_sec": 140.0,
        "source": "game_data"
      }
    }
  ],
  "match_duration": 167.38946800000002,
  "auto_duration": 20.620384,
  "teleop_duration": 142.738503,
  "phases": {
    "autonomous": {
      "start": 110.991153,
      "end": 131.611537,
      "duration": 20.620384,
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
      "basis": "game_timing",
      "description": "Endgame"
    }
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `analyze_swerve`

Analyze swerve modules from SwerveModuleState entries: per module, mean and maximum |speed| (magnitude; measured speeds are signed and negative about half the time), and, with setpoints, speed tracking error (| |measured| - |setpoint| |, m/s, events above slip_threshold) and steer error (angle difference modulo 180 deg, while the setpoint speed is above 0.05 m/s, events above sync_threshold_rad). A struct:SwerveModuleState[] array is one module per index (module[0..N-1]; the FL, FR, BL, BR labels are the AdvantageKit template's order, an assumption). The measured and setpoint entries are taken from a published naming, paired within one table: AdvantageKit SwerveStates/Measured with SetpointsOptimized or Setpoints, CTRE DriveState/ModuleStates with ModuleTargets, YAGSL currentStates with desiredStates. Otherwise the only SwerveModuleState entry is used (the log does not say whether it is measured or commanded: a warning says so). Entries under other names are not interpreted: the result is no_match with needs_confirmation and candidates, or the tracking sections are skipped with the candidates; pass measured_entry and setpoint_entry (one module's entries when each module has its own). Which entry is which is in the robot's source code, not in its name. measured_basis and setpoint_basis say how each was chosen. Odometry drift compares the robot pose with a vision pose (as resolve_signals reports them; candidates are listed in skipped, never guessed; pass odometry_entry/vision_entry). An entry passed that is missing or of the wrong type is an error. Use scope (e.g. 'enabled') to exclude disabled time. Returns no_match when the log has no SwerveModuleState entries.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Regression estimates depend on data quality and model assumptions. Physical parameters outside typical ranges (negative inertia, negative damping) indicate model or data issues, not actual physics.

**Parameters** ([TOOLS.md](TOOLS.md#analyze_swerve))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `module_prefix` | string | no | Only consider module state entries under this prefix (e.g. '/RealOutputs/SwerveStates') |
| `measured_entry` | string | no | Measured module states: a SwerveModuleState[] entry, or one module's SwerveModuleState entry |
| `setpoint_entry` | string | no | Setpoint module states of the same shape as the measured entry, paired by index |
| `slip_threshold` | number | no | Speed tracking error, in m/s, counted as an event (default: 0.5) |
| `sync_threshold_rad` | number | no | Steer error, in radians, counted as an event (default: 0.1) |
| `odometry_entry` | string | no | Explicit odometry pose entry (struct:Pose2d or Pose3d) |
| `vision_entry` | string | no | Explicit vision pose entry (struct:Pose2d or Pose3d) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: While enabled**

Request:
```json
{
  "name": "analyze_swerve",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "scope": "enabled"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "partial",
  "scope": {
    "scope": "enabled",
    "windows": [
      [
        110.991153,
        131.611537
      ],
      [
        135.642118,
        278.380621
      ]
    ],
    "window_count": 2,
    "total_sec": 163.358887
  },
  "inputs": {
    "entries": {
      "measured": "/RealOutputs/SwerveStates/Measured",
      "setpoint": "/RealOutputs/SwerveStates/SetpointsOptimized"
    }
  },
  "measured_basis": "SwerveStates/Measured (AdvantageKit swerve template)",
  "setpoint_basis": "SwerveStates/SetpointsOptimized beside the measured entry (AdvantageKit swerve template)",
  "layout": "array",
  "module_count": 4,
  "module_order_note": "Indices are the order the robot code logs its modules. The AdvantageKit template logs front-left, front-right, back-left, back-right; that labeling is an assumption, not recorded in the log.",
  "modules": [
    {
      "module": "module[0]",
      "index": 0,
      "assumed_position": "front_left",
      "measured_entry": "/RealOutputs/SwerveStates/Measured",
      "samples": 6681,
      "mean_abs_speed_mps": 1.388971666817831,
      "max_abs_speed_mps": 4.115751448082775,
      "setpoint_entry": "/RealOutputs/SwerveStates/SetpointsOptimized",
      "speed_tracking_error": {
        "samples": 6502,
        "mean_mps": 1.2283959774961903,
        "p95_mps": 2.8811814080518894,
        "max_mps": 4.192295353180699,
        "events_over_threshold": 4746
      },
      "steer_error": {
        "samples": 5702,
        "mean_rad": 0.3552968438807168,
        "p95_rad": 1.0027900443588937,
        "max_rad": 1.558669892377086,
        "events_over_threshold": 4691,
        "max_deg": 89.30520648731728
      }
    },
    {
      "module": "module[1]",
      "index": 1,
      "assumed_position": "front_right",
      "measured_entry": "/RealOutputs/SwerveStates/Measured",
      "samples": 6681,
      "mean_abs_speed_mps": 1.6279404750181654,
      "max_abs_speed_mps": 4.048423190525529,
      "setpoint_entry": "/RealOutputs/SwerveStates/SetpointsOptimized",
      "speed_tracking_error": {
        "samples": 6502,
        "mean_mps": 1.0895396504590344,
        "p95_mps": 2.610684846975101,
        "max_mps": 4.201512133851005,
        "events_over_threshold": 4580
      },
      "steer_error": {
        "samples": 5700,
        "mean_rad": 0.3155327615416909,
        "p95_rad": 0.9102651468574149,
        "max_rad": 1.542558158905294,
        "events_over_threshold": 4462,
        "max_deg": 88.38207215874392
      }
    },
    {
      "module": "module[2]",
      "index": 2,
      "assumed_position": "back_left",
      "measured_entry": "/RealOutputs/SwerveStates/Measured",
      "samples": 6681,
      "mean_abs_speed_mps": 1.4880940103988112,
      "max_abs_speed_mps": 4.176845607718055,
      "setpoint_entry": "/RealOutputs/SwerveStates/SetpointsOptimized",
      "speed_tracking_error": {
        "samples": 6502,
        "mean_mps": 1.091934898147582,
        "p95_mps": 2.6882881891900214,
        "max_mps": 4.218406391506481,
        "events_over_threshold": 4467
      },
      "steer_error": {
        "samples": 5703,
        "mean_rad": 0.32177370873775946,
        "p95_rad": 0.9288714220336927,
        "max_rad": 1.5540776245207155,
        "events_over_threshold": 4450,
        "max_deg": 89.04208892075366
      }
    },
    {
      "module": "module[3]",
      "index": 3,
      "assumed_position": "back_right",
      "measured_entry": "/RealOutputs/SwerveStates/Measured",
      "samples": 6681,
      "mean_abs_speed_mps": 1.4526845021615682,
      "max_abs_speed_mps": 4.103906662031038,
      "setpoint_entry": "/RealOutputs/SwerveStates/SetpointsOptimized",
      "speed_tracking_error": {
        "samples": 6502,
        "mean_mps": 1.1732265206497399,
        "p95_mps": 2.7466415519897427,
        "max_mps": 4.20511800108898,
        "events_over_threshold": 4708
      },
      "steer_error": {
        "samples": 5693,
        "mean_rad": 0.33340847364511456,
        "p95_rad": 0.9454037181563173,
        "max_rad": 1.5567939602128575,
        "events_over_threshold": 4560,
        "max_deg": 89.19772349165414
      }
    }
  ],
  "module_sync": {
    "basis": "steer angle vs setpoint, modulo 180 deg, while the setpoint speed exceeds 0.05 m/s",
    "samples_analyzed": 22798,
    "desync_events": 18163,
    "max_deviation_rad": 1.558669892377086,
    "max_deviation_deg": 89.30520648731728,
    "worst_module": "module[0]"
  },
  "skipped": [
    {
      "section": "odometry_drift",
      "reason": "Needs a scalar odometry pose and a scalar vision pose (struct:Pose2d or struct:Pose3d, at least 2 samples). Odometry: /RealOutputs/Drive/Pose Vision: No vision pose estimate (a scalar pose under a vision path) entry found (no scalar struct:Pose2d or struct:Pose3d with at least two samples under a vision, camera, PhotonVision, or Limelight path); if the log has one under another name, pass it as vi... (11 more characters)"
    }
  ],
  "data_quality": {
    "sample_count": 6681,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.5 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6681 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use power_analysis to check if module issues correlate with brownouts"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `power_analysis`

Analyze battery and current distribution data over a scope (default: enabled time when the log records it, so idle and boot time do not dilute averages). Reports battery voltage statistics (min with its time, max, avg, samples below the brownout threshold, threshold crossings with 0.2 V hysteresis and the seconds spent below; the threshold comes from the log's BrownoutVoltage entry when logged, else 6.8V for roboRIO 1, with the basis stated), brownout_risk with its basis (HIGH only from the roboRIO's logged brownout flag, or from crossings when no flag is logged; MODERATE for crossings the logged flag did not confirm, or a minimum within 1 V; LOW otherwise), the roboRIO's own brownouts in scope when its flag is logged (rio_brownouts: start and duration of each), and, for every amperage entry, the peak current by magnitude with its timestamp, signed min/max, average, and sample count in scope, sorted by peak. Amperage entries are named ...Current, ...CurrentAmps, ...Amps, ...Current/<sub>, or WPILib PowerDistribution[<id>]/Chan<N>; names like CurrentAngle or CurrentLimit are excluded. Per-channel arrays such as /PowerDistribution/ChannelCurrent are expanded per channel index. Warns when no voltage or current entries are found. The battery voltage entry is BatteryVoltage (e.g. /SystemStats/BatteryVoltage) or Voltage under PowerDistribution, PDH, PDP, or Battery; the server does not guess among other voltage entries: it lists them in the skipped reason, and voltage_entry names the one to use.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Voltage drops may indicate power issues, aggressive driving, worn battery, or loose connections. Single brownout events are not necessarily concerning—look for patterns across matches.

**Parameters** ([TOOLS.md](TOOLS.md#power_analysis))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `power_prefix` | string | no | Entry path prefix (e.g., '/PDP') |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. Default: 'enabled' when the log records enabled state, else 'all'. |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `voltage_entry` | string | no | Battery voltage entry to use (default: BatteryVoltage, or Voltage under PowerDistribution/PDH/PDP/Battery; other voltage entries are never guessed: when the log has only those, they are listed to confirm and pass here) |
| `brownout_threshold` | number | no | Voltage threshold (default: the log's BrownoutVoltage entry when logged, else 6.8V for roboRIO 1; roboRIO 2 is 6.3V) |
| `channel_limit` | integer | no | Maximum number of current entries/channels to return, sorted by peak current (default: 30, minimum: 1) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Top channels**

Request:
```json
{
  "name": "power_analysis",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "channel_limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "scope": {
    "scope": "enabled",
    "windows": [
      [
        110.991153,
        131.611537
      ],
      [
        135.642118,
        278.380621
      ]
    ],
    "window_count": 2,
    "total_sec": 163.358887
  },
  "rio_brownouts": {
    "flag_entry": "/SystemStats/BrownedOut",
    "count": 5,
    "total_sec": 0.45058100000008494,
    "events": [
      {
        "start": 143.079221,
        "end": 143.133666,
        "duration_sec": 0.0544450000000154
      },
      {
        "start": 194.071279,
        "end": 194.20445,
        "duration_sec": 0.13317100000000437
      },
      {
        "start": 194.444747,
        "end": 194.526719,
        "duration_sec": 0.08197200000000748
      },
      {
        "start": 264.272604,
        "end": 264.393662,
        "duration_sec": 0.121058000000005
      },
      {
        "start": 264.588604,
        "end": 264.648539,
        "duration_sec": 0.059935000000052696
      }
    ]
  },
  "voltage_analysis": {
    "entry": "/SystemStats/BatteryVoltage",
    "samples": 6584,
    "min_voltage": 6.680678710937499,
    "min_voltage_time_sec": 194.444747,
    "max_voltage": 12.655308349609374,
    "avg_voltage": 9.307719026401001,
    "samples_below_threshold": 1,
    "threshold_crossings": 1,
    "seconds_below_threshold": 0.021850999999998066,
    "brownout_threshold": 6.75,
    "brownout_threshold_basis": "logged",
    "brownout_threshold_entry": "/SystemStats/BrownoutVoltage",
    "brownout_risk": "HIGH",
    "brownout_risk_basis": "5 roboRIO brownout(s) in scope (/SystemStats/BrownedOut true: outputs were disabled)"
  },
  "current_entries_analyzed": 70,
  "channel_analysis": [
    {
      "entry": "/RealOutputs/PDH/TotalCurrentAmps",
      "peak_current_A": 226.0,
      "peak_current_time_sec": 137.380068,
      "max_current_A": 226.0,
      "min_current_A": 2.0,
      "avg_current_A": 106.17057569296375,
      "sample_count": 1876
    },
    {
      "entry": "/Spindexer/CurrentAmps",
      "peak_current_A": 149.3040313720703,
      "peak_current_time_sec": 180.101979,
      "max_current_A": 149.3040313720703,
      "min_current_A": 0.0,
      "avg_current_A": 14.140657746665829,
      "sample_count": 1827
    },
    {
      "entry": "/Kicker/CurrentAmps",
      "peak_current_A": 115.05494689941406,
      "peak_current_time_sec": 183.310443,
      "max_current_A": 115.05494689941406,
      "min_current_A": 0.0,
      "avg_current_A": 8.133700923908155,
      "sample_count": 2858
    }
  ],
  "limits": {
    "channel_analysis": {
      "total": 70,
      "returned": 3,
      "limit": 3
    }
  },
  "warnings": [
    "Showing the top 3 of 70 current entries/channels by peak current; raise channel_limit to see more."
  ],
  "inputs": {
    "entries": {
      "voltage": "/SystemStats/BatteryVoltage",
      "brownout_flag": "/SystemStats/BrownedOut"
    }
  },
  "data_quality": {
    "sample_count": 6584,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6584 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use predict_battery_health for comprehensive battery assessment"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `can_health`

CAN bus health overview from two sources: console and message text (string lines, string[] alerts, json strings) with CAN timeout/error/fault lines, each classified by the robot's enabled state at that moment from the DriverStation timeline, and the structured bus counters that analyze_can_bus reads (TEC/REC error counters, bus-off and TX-full counts). health_assessment: POOR if a bus-off count rose while enabled or 50+ CAN text errors occurred while enabled; CONCERNING if any CAN text error occurred while enabled or TEC/REC reached 128 (error-passive) while enabled; UNKNOWN when CAN error lines exist but the log has no DriverStation state to tell whether the robot was enabled; otherwise GOOD. assessment_basis says which fact decided it. Errors while disabled are normal (for example devices booting) and do not count; errors before the first DriverStation sample are reported separately. Returns not_applicable when the log has neither text entries nor CAN counters: absence of evidence is not GOOD. See analyze_can_bus for per-bus detail.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#can_health))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Text and counters**

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
  "status": "ok",
  "error_counts_by_entry": {
    "/RealOutputs/Console": {
      "total": 2,
      "while_enabled": 2,
      "while_disabled": 0
    }
  },
  "total_can_errors": 2,
  "errors_while_enabled": 2,
  "health_assessment": "CONCERNING",
  "assessment_basis": "2 CAN error line(s) while enabled",
  "bus_counters": [
    {
      "bus": "rio",
      "tec_max": 0.0,
      "tec_max_time_sec": 11.897573,
      "rec_max": 0.0,
      "rec_max_time_sec": 11.897573,
      "bus_off_increase": 0.0,
      "bus_off_increase_while_enabled": 0.0
    },
    {
      "bus": "CANHD",
      "tec_max": 0.0,
      "tec_max_time_sec": 26.860151,
      "rec_max": 0.0,
      "rec_max_time_sec": 26.860151,
      "bus_off_increase": 0.0,
      "bus_off_increase_while_enabled": 0.0
    },
    {
      "bus": "CAN2",
      "tec_max": 0.0,
      "tec_max_time_sec": 26.860151,
      "rec_max": 6.0,
      "rec_max_time_sec": 140.349441,
      "rec_max_while_enabled": 6.0,
      "bus_off_increase": 0.0,
      "bus_off_increase_while_enabled": 0.0
    }
  ],
  "inputs": {
    "entries": {
      "enabled": "/DriverStation/Enabled"
    }
  },
  "errors_while_disabled": 0,
  "first_errors_while_enabled": [
    {
      "timestamp_sec": 123.711426,
      "entry": "/RealOutputs/Console",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
    },
    {
      "timestamp_sec": 124.739764,
      "entry": "/RealOutputs/Console",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
    }
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `compare_matches`

Compare one numeric signal across two logs: per log, sample_count, min and max (with min_at_sec and max_at_sec), mean, std_dev, median, p5, p25, p75, p95, and data_quality; and the differences (second minus first) of mean, median, and p95. scope ('enabled', 'teleop', 'segment:<i>', ...) is resolved in each log's own timeline, so the same phase is compared; start_time/end_time apply to each log's own clock. The name may carry a field path (/RealOutputs/Drive/Pose.translation.x, ChannelCurrent[3]). A maximum or minimum in the first 5 s of a log is flagged as a likely boot transient: compare scope 'enabled' instead. Samples within a log are autocorrelated, so no significance test is made; two logs are two samples.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Sample sizes below 100 have high uncertainty. Correlations below |0.7| are moderate to weak — interpret cautiously, especially with small samples. Consider whether a physical relationship is expected.

**Parameters** ([TOOLS.md](TOOLS.md#compare_matches))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the first log file |
| `compare_path` | string | yes | Path to the second log file |
| `name` | string | yes | Entry name to compare, optionally with a field path |
| `field` | string | no | Field path inside the entry's values, e.g. 'translation.x', '[3]', '[0].tagCount' (or append it to the name) |
| `angle` | string | no | Treat the values as an angle in 'radians' or 'degrees' (unwrapped across +-180 degrees, circular statistics), for an angle logged as a plain number such as a gyro yaw double; struct angle fields are recognized without it |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. Resolved in each log's own timeline. |
| `start_time` | number | no | Start timestamp (s), on each log's clock |
| `end_time` | number | no | End timestamp (s), on each log's clock |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. On each log's clock. |

**Example: Loop time while enabled, two logs**

Request:
```json
{
  "name": "compare_matches",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "compare_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/RealOutputs/LoggedRobot/FullCycleMS",
    "scope": "enabled"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "entry": "/RealOutputs/LoggedRobot/FullCycleMS",
  "inputs": {
    "logs": [
      "<logdir>/akit_26-09-30_00-10-26.wpilog",
      "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
    ],
    "entry": "/RealOutputs/LoggedRobot/FullCycleMS"
  },
  "logs_compared": 2,
  "comparisons": [
    {
      "log_path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
      "log_filename": "akit_26-09-30_00-10-26.wpilog",
      "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered.",
      "entry_found": true,
      "signal": "/RealOutputs/LoggedRobot/FullCycleMS",
      "scope": {
        "scope": "enabled",
        "windows": [
          [
            40.207135,
            359.161586
          ],
          [
            395.209541,
            744.929426
          ],
          [
            791.534465,
            840.133016
          ],
          [
            995.211072,
            1588.273419
          ]
        ],
        "window_count": 4,
        "total_sec": 1310.3352340000001
      },
      "sample_count": 48596,
      "statistics": {
        "min": 7.679,
        "min_at_sec": 999.841727,
        "max": 267.882,
        "max_at_sec": 1130.514642,
        "mean": 22.18834731253601,
        "std_dev": 15.867858887414958,
        "median": 17.603,
        "p5": 11.205,
        "p25": 14.34,
        "p75": 22.191250000000004,
        "p95": 53.108
      },
      "data_quality": {
        "sample_count": 48596,
        "time_span_seconds": 1310.19,
        "sampling": "periodic",
        "gap_count": 420,
        "max_gap_ms": 287.6,
        "effective_sample_rate_hz": 47.4,
        "quality_score": 0.91,
        "reasons": [
          "4.3% of the time span is in 420 gaps longer than 5x the median interval (longest 288 ms)",
          "irregular timing: intervals deviate from the 21.1 ms median by 6% (median absolute deviation)"
        ]
      }
    },
    {
      "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
      "log_filename": "akit_26-03-21_16-29-56_vache_q10.wpilog",
      "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered.",
      "entry_found": true,
      "signal": "/RealOutputs/LoggedRobot/FullCycleMS",
      "scope": {
        "scope": "enabled",
        "windows": [
          [
            110.991153,
            131.611537
          ],
          [
            135.642118,
            278.380621
          ]
        ],
        "window_count": 2,
        "total_sec": 163.358887
      },
      "sample_count": 6678,
      "statistics": {
        "min": 9.535,
        "min_at_sec": 244.329295,
        "max": 387.862,
        "max_at_sec": 110.991153,
        "mean": 20.910544474393532,
        "std_dev": 12.994592460495303,
        "median": 17.809,
        "p5": 12.503,
        "p25": 15.397,
        "p75": 20.868,
        "p95": 43.73879999999996
      },
      "data_quality": {
        "sample_count": 6678,
        "time_span_seconds": 163.2,
        "sampling": "periodic",
        "gap_count": 31,
        "max_gap_ms": 388.3,
        "effective_sample_rate_hz": 48.7,
        "quality_score": 0.95,
        "reasons": [
          "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
          "irregular timing: intervals deviate from the 20.5 ms median by 3% (median absolute deviation)"
        ]
      }
    }
  ],
  "differences": {
    "mean": -1.2778028381424775,
    "median": 0.20599999999999952,
    "p95": -9.369200000000035,
    "note": "Second log minus first. Two logs are two samples: a difference may reflect battery, field, opponents, or code changes (get_code_metadata), not only the robot."
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 48596 samples over 1310.2 seconds",
    "interpretation_guidance": [
      "Cross-match comparisons require consistent logging configurations for valid comparison"
    ]
  }
}
```

### `get_code_metadata`

Extract code metadata (Git SHA, branch, dirty flag, Git date, build date, project name, version) from string entries with those leaf names, e.g. AdvantageKit's /RealMetadata/GitSHA ('Version' only under a path containing 'metadata'). sources names the entry each value came from (lowest entry id when several exist; a warning says when they disagree). Returns no_match when the log has no metadata entries.

**Parameters** ([TOOLS.md](TOOLS.md#get_code_metadata))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Build metadata**

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
  "status": "ok",
  "metadata": {
    "GitBranch": "main",
    "ProjectName": "Rebuilt",
    "GitSHA": "378d5d47240edd0792ef8a7866bb5fb4b39960a1",
    "GitDirty": "Uncomitted changes",
    "GitDate": "2026-03-20 22:42:12 EDT",
    "BuildDate": "2026-03-21 10:11:35 EDT"
  },
  "sources": {
    "GitBranch": "/RealMetadata/GitBranch",
    "ProjectName": "/RealMetadata/ProjectName",
    "GitSHA": "/RealMetadata/GitSHA",
    "GitDirty": "/RealMetadata/GitDirty",
    "GitDate": "/RealMetadata/GitDate",
    "BuildDate": "/RealMetadata/BuildDate"
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/RealMetadata/BuildDate",
      "/RealMetadata/GitBranch",
      "/RealMetadata/GitDate",
      "... (3 more items)"
    ]
  }
}
```

### `moi_regression`

Estimate moment of inertia J (kg·m²) and viscous damping B (Nm·s/rad) for a DC-motor-driven mechanism using OLS regression on logged velocity and current. Model: G * motor_count * kt * I = J * α + B * ω. Supports angular (rad/s) or linear (m/s, via wheel_radius) velocity entries. Provide applied_volts_entry when current is always non-negative (TalonFX/SparkMax) so torque direction is recovered from voltage sign.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Regression estimates depend on data quality and model assumptions. Physical parameters outside typical ranges (negative inertia, negative damping) indicate model or data issues, not actual physics.

**Parameters** ([TOOLS.md](TOOLS.md#moi_regression))

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

**Example: Front-left drive module**

Request:
```json
{
  "name": "moi_regression",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "velocity_entry": "/Drive/Module0/DriveVelocityRadPerSec",
    "current_entry": "/Drive/Module0/DriveCurrentAmps",
    "applied_volts_entry": "/Drive/Module0/DriveAppliedVolts",
    "kt": 0.0194,
    "gear_ratio": 6.75
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "J_kg_m2": 0.006002196533613358,
  "B_Nm_s_per_rad": 0.12191742714078571,
  "r_squared": 0.5101709181643017,
  "rmse_nm": 4.212555979822467,
  "n_samples_used": 6079,
  "n_samples_total": 6470,
  "filtered_by_alpha_threshold": 252,
  "filtered_by_zero_volts": 33,
  "parameters_used": {
    "torque_scale_Nm_per_A": 0.13095,
    "applied_volts_used": true
  },
  "data_quality": {
    "sample_count": 6470,
    "time_span_seconds": 321.04,
    "sampling": "periodic",
    "gap_count": 79,
    "max_gap_ms": 69282.9,
    "effective_sample_rate_hz": 48.5,
    "quality_score": 0.69,
    "reasons": [
      "51.4% of the time span is in 79 gaps longer than 5x the median interval (longest 69283 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 4% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 6470 samples over 321.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Regression estimates depend on data quality and model assumptions"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/Drive/Module0/DriveAppliedVolts",
      "/Drive/Module0/DriveCurrentAmps",
      "/Drive/Module0/DriveVelocityRadPerSec"
    ]
  }
}
```

### `analyze_can_bus`

Analyze CAN bus health from the counters the log records, per bus: utilization (percent; 0-1 fractions are detected and converted), transmit/receive error counters TEC and REC (maximum, when, excursions to the error-passive threshold of 128 or above, time spent at or above it; bus-off is TEC above 255), and bus-off and TX-full count increases, each overall and while enabled. Buses are found by the standard field names (WPILib CANStatus as AdvantageKit logs it under /SystemStats/CANBus, named 'rio'; CTRE CANivore status such as <prefix>/CANHD/{Utilization,TEC,REC,BusOffCount,TxFullCount}, named by the last path segment); bus_name selects one. Other numeric/boolean entries named with CAN and error/fault/timeout are reported under errors by how much they increased. Returns no_match when the log has no CAN counters. See also can_health (console messages plus these counters).



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#analyze_can_bus))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `bus_name` | string | no | Bus to analyze: 'rio', a CANivore name such as 'CANHD', or a path prefix (default: every bus found) |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: All buses**

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
  "status": "ok",
  "buses": [
    {
      "bus": "rio",
      "prefix": "/SystemStats/CANBus",
      "entries": {
        "utilization": "/SystemStats/CANBus/Utilization",
        "bus_off": "/SystemStats/CANBus/OffCount",
        "tx_full": "/SystemStats/CANBus/TxFullCount",
        "rec": "/SystemStats/CANBus/ReceiveErrorCount",
        "tec": "/SystemStats/CANBus/TransmitErrorCount"
      },
      "utilization": {
        "samples": 668,
        "unit_detected": "fraction (0-1), converted to percent",
        "mean_percent": 29.69167176411002,
        "p95_percent": 49.219091683626175,
        "max_percent": 100.0,
        "while_enabled": {
          "samples": 334,
          "mean_percent": 31.565787567349965,
          "max_percent": 100.0
        }
      },
      "bus_off": {
        "samples": 1,
        "first": 0.0,
        "last": 0.0,
        "increase": 0.0,
        "increase_while_enabled": 0.0
      },
      "tx_full": {
        "samples": 1,
        "first": 2.0,
        "last": 2.0,
        "increase": 0.0,
        "increase_while_enabled": 0.0
      },
      "rec": {
        "samples": 1,
        "max": 0.0,
        "max_time_sec": 11.897573,
        "error_passive_excursions": 0,
        "time_error_passive_sec": 0.0
      },
      "tec": {
        "samples": 1,
        "max": 0.0,
        "max_time_sec": 11.897573,
        "error_passive_excursions": 0,
        "time_error_passive_sec": 0.0
      }
    },
    {
      "bus": "CANHD",
      "prefix": "/RealOutputs/CANBus/CANHD",
      "entries": {
        "utilization": "/RealOutputs/CANBus/CANHD/Utilization",
        "bus_off": "/RealOutputs/CANBus/CANHD/BusOffCount",
        "tx_full": "/RealOutputs/CANBus/CANHD/TxFullCount",
        "rec": "/RealOutputs/CANBus/CANHD/REC",
        "tec": "/RealOutputs/CANBus/CANHD/TEC"
      },
      "utilization": {
        "samples": 2474,
        "unit_detected": "fraction (0-1), converted to percent",
        "mean_percent": 30.914713636269564,
        "p95_percent": 33.000001311302185,
        "max_percent": 33.000001311302185,
        "while_enabled": {
          "samples": 1216,
          "mean_percent": 30.92269799426982,
          "max_percent": 33.000001311302185
        }
      },
      "bus_off": {
        "samples": 1,
        "first": 0.0,
        "last": 0.0,
        "increase": 0.0,
        "increase_while_enabled": 0.0
      },
      "tx_full": {
        "samples": 1,
        "first": 0.0,
        "last": 0.0,
        "increase": 0.0,
        "increase_while_enabled": 0.0
      },
      "rec": {
        "samples": 1,
        "max": 0.0,
        "max_time_sec": 26.860151,
        "error_passive_excursions": 0,
        "time_error_passive_sec": 0.0
      },
      "tec": {
        "samples": 1,
        "max": 0.0,
        "max_time_sec": 26.860151,
        "error_passive_excursions": 0,
        "time_error_passive_sec": 0.0
      }
    },
    {
      "bus": "CAN2",
      "prefix": "/RealOutputs/CANBus/CAN2",
      "entries": {
        "utilization": "/RealOutputs/CANBus/CAN2/Utilization",
        "bus_off": "/RealOutputs/CANBus/CAN2/BusOffCount",
        "tx_full": "/RealOutputs/CANBus/CAN2/TxFullCount",
        "rec": "/RealOutputs/CANBus/CAN2/REC",
        "tec": "/RealOutputs/CANBus/CAN2/TEC"
      },
      "utilization": {
        "samples": 13356,
        "unit_detected": "fraction (0-1), converted to percent",
        "mean_percent": 30.70267356809995,
        "p95_percent": 46.6074176132679,
        "max_percent": 100.0,
        "while_enabled": {
          "samples": 6681,
          "mean_percent": 32.01786407400991,
          "max_percent": 100.0
        }
      },
      "bus_off": {
        "samples": 1,
        "first": 0.0,
        "last": 0.0,
        "increase": 0.0,
        "increase_while_enabled": 0.0
      },
      "tx_full": {
        "samples": 1,
        "first": 2.0,
        "last": 2.0,
        "increase": 0.0,
        "increase_while_enabled": 0.0
      },
      "rec": {
        "samples": 5,
        "max": 6.0,
        "max_time_sec": 140.349441,
        "error_passive_excursions": 0,
        "time_error_passive_sec": 0.0,
        "while_enabled": {
          "max": 6.0,
          "max_time_sec": 140.349441,
          "error_passive_excursions": 0
        }
      },
      "tec": {
        "samples": 1,
        "max": 0.0,
        "max_time_sec": 26.860151,
        "error_passive_excursions": 0,
        "time_error_passive_sec": 0.0
      }
    }
  ],
  "inputs": {
    "entries": {
      "enabled": "/DriverStation/Enabled"
    }
  },
  "utilization": [
    {
      "entry": "/SystemStats/CANBus/Utilization",
      "bus": "rio",
      "avg_percent": 29.69167176411002,
      "max_percent": 100.0,
      "sample_count": 668,
      "unit_detected": "fraction (0-1), converted to percent"
    },
    {
      "entry": "/RealOutputs/CANBus/CANHD/Utilization",
      "bus": "CANHD",
      "avg_percent": 30.914713636269564,
      "max_percent": 33.000001311302185,
      "sample_count": 2474,
      "unit_detected": "fraction (0-1), converted to percent"
    },
    {
      "entry": "/RealOutputs/CANBus/CAN2/Utilization",
      "bus": "CAN2",
      "avg_percent": 30.70267356809995,
      "max_percent": 100.0,
      "sample_count": 13356,
      "unit_detected": "fraction (0-1), converted to percent"
    }
  ],
  "errors": [],
  "enabled_error_total": 0.0,
  "data_quality": {
    "sample_count": 668,
    "time_span_seconds": 335.69,
    "sampling": "periodic",
    "gap_count": 1,
    "max_gap_ms": 18969.7,
    "effective_sample_rate_hz": 2.3,
    "quality_score": 0.89,
    "reasons": [
      "5.7% of the time span is in 1 gap longer than 5x the median interval (longest 18970 ms)",
      "irregular timing: intervals deviate from the 432.2 ms median by 7% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 668 samples over 335.7 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "data_quality describes /SystemStats/CANBus/Utilization; CAN status entries are often logged at a low rate, which bounds the utilization statistics, not the counter maxima and increases."
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

## FRC Domain Tools

### `get_ds_timeline`

Generate a chronological timeline of critical robot events: enable/disable, match phases, battery-voltage threshold brownouts (BROWNOUT_START/END, basis voltage_threshold), roboRIO brownout flag transitions when a flag such as /SystemStats/BrownedOut is logged (RIO_BROWNOUT_START/END, basis rio_flag), alerts (ALERT_RAISED, category alert: each message of a string[] alert entry such as /RealOutputs/Alerts/warnings when it appears, with cleared_at and duration_sec), and for errors/warnings found in text (string lines, alerts, json strings), exact counts (text_event_counts, per source) and text_event_summary: each distinct message (numbers normalized to #) with its count, first/last time, sources, and how many distinct raw texts it covers. Individual console messages are deliberately not listed here; use search_strings (level, regex, time window, offset/limit paging) for the complete list. rio_brownout_flag_logged says whether the roboRIO's own brownout state is available in this log; brownout_voltage_entry names the voltage entry scanned for threshold crossings (BatteryVoltage, or Voltage under PowerDistribution, PDH, PDP, or Battery; voltage_entry names another), and a warning says when there is none, listing any voltage entries to confirm: the server does not guess which one is the battery. Returns not_applicable when the log has none of these inputs at all.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#get_ds_timeline))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `brownout_threshold` | number | no | Voltage threshold for BROWNOUT_START/END crossings (default: the log's BrownoutVoltage entry when logged, else 6.8V for roboRIO 1; roboRIO 2 is 6.3V) |
| `voltage_entry` | string | no | Battery voltage entry for BROWNOUT_START/END (default: BatteryVoltage, or Voltage under PowerDistribution/PDH/PDP/Battery) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Timeline**

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
  "status": "ok",
  "event_count": 119,
  "brownout_threshold": 6.75,
  "brownout_threshold_basis": "logged",
  "rio_brownout_flag_logged": true,
  "summary": {
    "robot_state": 5,
    "alert": 100,
    "power": 12,
    "match_phase": 2
  },
  "events": [
    {
      "timestamp": 11.897573,
      "type": "DISABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled",
      "initial": true
    },
    {
      "timestamp": 110.991153,
      "type": "ENABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 110.991153,
      "type": "AUTO_START",
      "category": "match_phase",
      "source": "/DriverStation/Autonomous"
    },
    "... (116 more items)"
  ],
  "rio_brownout_flag_entry": "/SystemStats/BrownedOut",
  "text_event_counts": {
    "error": 5,
    "warning": 668,
    "total": 673,
    "by_source": {
      "/RealOutputs/PhotonAlerts/warnings": {
        "error": 0,
        "warning": 17
      },
      "/RealOutputs/Console": {
        "error": 5,
        "warning": 621
      },
      "/RealOutputs/Alerts/warnings": {
        "error": 0,
        "warning": 13
      },
      "/RealOutputs/Alerts/PhotonAlerts/Warnings": {
        "error": 0,
        "warning": 17
      }
    }
  },
  "text_event_groups_total": 24,
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
    "... (21 more items)"
  ],
  "brownout_voltage_entry": "/SystemStats/BatteryVoltage",
  "inputs": {
    "entries": {
      "enabled": "/DriverStation/Enabled",
      "autonomous": "/DriverStation/Autonomous",
      "voltage": "/SystemStats/BatteryVoltage",
      "rio_brownout_flag": "/SystemStats/BrownedOut"
    }
  },
  "warnings": [
    "The timeline lists the first 100 of 148 alert appearances; search_strings lists every one."
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `analyze_vision`

Analyze vision data: what the AdvantageKit vision template and the vision libraries publish under their own names, and the entries passed as vision_entries. Entries that only look like vision data are listed in candidates by kind, with needs_confirmation, and are neither analyzed nor decoded: a planned trajectory is also a struct array of timestamps and poses, a gyro's struct also has yaw and pitch, and a robot's own HasTargetLock need not be a camera's. The robot's source code says what each is; pass the confirmed ones as vision_entries. observation_streams: struct:PoseObservation[] entries (the vision template's /Vision/Camera<N>/PoseObservations from PhotonVision or Limelight: each record holds a timestamp and a pose), one stream per camera, with record and observation counts, the fraction of records with an observation, observation rate, tag-count and ambiguity distributions, latency (log time minus the observation's own timestamp; an entry beside the stream whose name mentions latency is listed in latency_candidates and not analyzed, since its name does not say what it times or in which units: get_statistics reads it), and the residual between each observation and the robot pose at the observation's timestamp (robot_pose_entry, chosen or passed as pose_entry). target_streams: struct:TargetObservation entries with yaw and pitch fields, with yaw, pitch, area, and confidence distributions and the object ids seen. pose_sets: the template's pose arrays (Vision/Summary/ and Vision/Camera<N>/ TagPoses, RobotPoses, RobotPosesAccepted, RobotPosesRejected), with how often they are non-empty and poses per record. target_acquisition: has-target entries with acquisition rate and flicker: Limelight's <table>/tv and PhotonVision's photonvision/<camera>/hasTarget. pose_jumps: steps larger than jump_threshold in the robot pose and in a vision pose estimate (the only scalar pose under a vision path, or those passed); a jump within 0.5 s of the robot being enabled has near_enable_sec (odometry is often reset there, e.g. at the start of autonomous), so it is not by itself evidence of a vision correction. vision_prefix limits the vision entries only (case-insensitive); the robot pose may live elsewhere. Returns no_match with what was searched when none of these exist.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#analyze_vision))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `vision_prefix` | string | no | Only vision entries under this prefix (case-insensitive), e.g. '/Vision' |
| `vision_entries` | array | no | Entries to analyze besides the conventional ones, each by its shape: a has-target flag (boolean, or a number where above 0.5 means a target), a pose array, a scalar pose (checked for jumps), a struct array holding a timestamp and a pose, or a struct with yaw and pitch |
| `pose_entry` | string | no | Robot pose entry (struct:Pose2d or Pose3d) for residuals and jump detection; default: a conventional name (DriveState/Pose, Odometry/Robot, Drive/Pose, EstimatedPose, RobotPose) or the only Pose2d outside vision entries; several others are listed to confirm, not guessed |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `jump_threshold` | number | no | Distance threshold for jump detection (meters) |
| `flicker_window` | number | no | Time window for flicker detection (seconds) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Streams, targets, pose sets**

Request:
```json
{
  "name": "analyze_vision",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "inputs": {
    "entries": {
      "robot_pose": "/RealOutputs/Drive/Pose"
    }
  },
  "target_acquisition": [],
  "observation_streams": [
    {
      "entry": "/Vision/Camera0/PoseObservations",
      "camera": "Camera0",
      "records": 8794,
      "records_with_observations": 4943,
      "fraction_with_observations": 0.562087787127587,
      "observation_count": 4943,
      "observations_per_second": 3.149582375348901,
      "tag_count_distribution": {
        "1": 4943
      },
      "ambiguity": {
        "n": 4943,
        "median": 0.007167065169908414,
        "p95": 0.1271054137632548,
        "max": 0.9973417594982933
      },
      "latency": {
        "n": 4943,
        "median_ms": 78.43400000001566,
        "p95_ms": 95.95689999999875,
        "max_ms": 128.97700000002033,
        "basis": "log timestamp minus the observation's own timestamp"
      },
      "residual_vs_robot_pose": {
        "n": 4943,
        "median_m": 0.05176976324280616,
        "p95_m": 0.2074056583811134,
        "max_m": 5.0375639464492,
        "robot_pose_entry": "/RealOutputs/Drive/Pose",
        "basis": "planar distance to the robot pose interpolated at the observation's timestamp; the robot pose may itself include vision corrections"
      },
      "latency_candidates": [
        "/Vision/Camera0/LatencyMs"
      ]
    },
    {
      "entry": "/Vision/Camera3/PoseObservations",
      "camera": "Camera3",
      "records": 4225,
      "records_with_observations": 2377,
      "fraction_with_observations": 0.562603550295858,
      "observation_count": 2377,
      "observations_per_second": 1.5153961130780775,
      "tag_count_distribution": {
        "1": 2377
      },
      "ambiguity": {
        "n": 2377,
        "median": 0.022008811427399022,
        "p95": 0.6920425093222852,
        "max": 0.9969928608487769
      },
      "latency": {
        "n": 2377,
        "median_ms": 80.28200000012475,
        "p95_ms": 101.62940000000447,
        "max_ms": 127.8870000000154,
        "basis": "log timestamp minus the observation's own timestamp"
      },
      "residual_vs_robot_pose": {
        "n": 2377,
        "median_m": 0.09985291855958224,
        "p95_m": 0.6088984631705984,
        "max_m": 6.253259258616024,
        "robot_pose_entry": "/RealOutputs/Drive/Pose",
        "basis": "planar distance to the robot pose interpolated at the observation's timestamp; the robot pose may itself include vision corrections"
      },
      "latency_candidates": [
        "/Vision/Camera3/LatencyMs"
      ]
    },
    {
      "entry": "/Vision/Camera1/PoseObservations",
      "camera": "Camera1",
      "records": 9495,
      "records_with_observations": 5425,
      "fraction_with_observations": 0.5713533438651922,
      "observation_count": 5433,
      "observations_per_second": 3.448357675671836,
      "tag_count_distribution": {
        "1": 5433
      },
      "ambiguity": {
        "n": 5433,
        "median": 0.012425314917597718,
        "p95": 0.08978189182887636,
        "max": 0.9978572935327278
      },
      "latency": {
        "n": 5433,
        "median_ms": 75.86099999991802,
        "p95_ms": 94.25960000002078,
        "max_ms": 466.77100000010796,
        "basis": "log timestamp minus the observation's own timestamp"
      },
      "residual_vs_robot_pose": {
        "n": 5433,
        "median_m": 0.045161785023292954,
        "p95_m": 0.1413133352206436,
        "max_m": 2.2748085870461012,
        "robot_pose_entry": "/RealOutputs/Drive/Pose",
        "basis": "planar distance to the robot pose interpolated at the observation's timestamp; the robot pose may itself include vision corrections"
      },
      "latency_candidates": [
        "/Vision/Camera1/LatencyMs"
      ]
    },
    {
      "entry": "/Vision/Camera2/PoseObservations",
      "camera": "Camera2",
      "records": 7227,
      "records_with_observations": 4131,
      "fraction_with_observations": 0.5716064757160647,
      "observation_count": 4132,
      "observations_per_second": 2.6226051750185952,
      "tag_count_distribution": {
        "1": 4132
      },
      "ambiguity": {
        "n": 4132,
        "median": 0.01583797698626889,
        "p95": 0.2383023449499473,
        "max": 0.9996284809531948
      },
      "latency": {
        "n": 4132,
        "median_ms": 80.38049999998975,
        "p95_ms": 99.1336499999761,
        "max_ms": 127.80100000009043,
        "basis": "log timestamp minus the observation's own timestamp"
      },
      "residual_vs_robot_pose": {
        "n": 4132,
        "median_m": 0.04816150212300709,
        "p95_m": 0.2668789299526818,
        "max_m": 6.254341201227133,
        "robot_pose_entry": "/RealOutputs/Drive/Pose",
        "basis": "planar distance to the robot pose interpolated at the observation's timestamp; the robot pose may itself include vision corrections"
      },
      "latency_candidates": [
        "/Vision/Camera2/LatencyMs"
      ]
    }
  ],
  "target_streams": [
    {
      "entry": "/Vision/Camera2/LatestTargetObservation",
      "camera": "Camera2",
      "records": 10317,
      "observation_count": 10317,
      "yaw": {
        "n": 10317,
        "median_deg": -7.597144676329875,
        "p95_deg": 27.081638270581553,
        "max_deg": 39.65122473201293
      },
      "pitch": {
        "n": 10317,
        "median_deg": -1.558193437525847,
        "p95_deg": 17.872899002671474,
        "max_deg": 19.78640645856259
      },
      "area": {
        "n": 10317,
        "median": 0.22102864583333331,
        "p95": 0.6020833333333332,
        "max": 1.3688693576388888
      },
      "confidence": {
        "n": 10317,
        "median": -1.0,
        "p95": -1.0,
        "max": -1.0
      },
      "object_ids": {
        "-1": 10317
      }
    },
    {
      "entry": "/Vision/Camera0/LatestTargetObservation",
      "camera": "Camera0",
      "records": 11931,
      "observation_count": 11931,
      "yaw": {
        "n": 11931,
        "median_deg": 3.4646229706860416,
        "p95_deg": 29.58870244628376,
        "max_deg": 39.87934569236624
      },
      "pitch": {
        "n": 11931,
        "median_deg": -7.143715053392951,
        "p95_deg": 11.411414252756138,
        "max_deg": 20.468584808801037
      },
      "area": {
        "n": 11931,
        "median": 0.20930989583333334,
        "p95": 0.5545247395833334,
        "max": 1.4576822916666665
      },
      "confidence": {
        "n": 11931,
        "median": -1.0,
        "p95": -1.0,
        "max": -1.0
      },
      "object_ids": {
        "-1": 11931
      }
    },
    {
      "entry": "/Vision/Camera1/LatestTargetObservation",
      "camera": "Camera1",
      "records": 12744,
      "observation_count": 12744,
      "yaw": {
        "n": 12744,
        "median_deg": -8.730876088851256,
        "p95_deg": 28.02678691831082,
        "max_deg": 40.296659915300445
      },
      "pitch": {
        "n": 12744,
        "median_deg": 1.2122130497618087,
        "p95_deg": 18.09493564464178,
        "max_deg": 22.326475248724748
      },
      "area": {
        "n": 12744,
        "median": 0.2285698784722222,
        "p95": 0.7676513671874992,
        "max": 3.4383138020833335
      },
      "confidence": {
        "n": 12744,
        "median": -1.0,
        "p95": -1.0,
        "max": -1.0
      },
      "object_ids": {
        "-1": 12744
      }
    },
    {
      "entry": "/Vision/Camera3/LatestTargetObservation",
      "camera": "Camera3",
      "records": 6474,
      "observation_count": 6474,
      "yaw": {
        "n": 6474,
        "median_deg": 3.54749351115927,
        "p95_deg": 35.838028226449715,
        "max_deg": 38.99313676220798
      },
      "pitch": {
        "n": 6474,
        "median_deg": -4.488154029410735,
        "p95_deg": 12.925375584634953,
        "max_deg": 21.910320785307164
      },
      "area": {
        "n": 6474,
        "median": 0.18467881944444445,
        "p95": 0.687578667534722,
        "max": 1.5343967013888888
      },
      "confidence": {
        "n": 6474,
        "median": -1.0,
        "p95": -1.0,
        "max": -1.0
      },
      "object_ids": {
        "-1": 6474
      }
    }
  ],
  "pose_sets": [
    {
      "entry": "/RealOutputs/Vision/Summary/RobotPosesAccepted",
      "records": 18766,
      "records_non_empty": 12505,
      "fraction_non_empty": 0.6663647021208569,
      "pose_count": 15785,
      "mean_poses_per_non_empty_record": 1.2622950819672132,
      "max_poses_per_record": 6
    },
    {
      "entry": "/RealOutputs/Vision/Summary/RobotPosesRejected",
      "records": 2014,
      "records_non_empty": 1086,
      "fraction_non_empty": 0.5392254220456802,
      "pose_count": 1100,
      "mean_poses_per_non_empty_record": 1.0128913443830572,
      "max_poses_per_record": 2
    }
  ],
  "pose_jumps": [
    {
      "timestamp": 27.049787,
      "entry": "/RealOutputs/Drive/Pose",
      "distance": 6.897997757425444
    },
    {
      "timestamp": 119.790883,
      "entry": "/RealOutputs/Drive/Pose",
      "distance": 0.5385212577418298
    },
    {
      "timestamp": 120.791303,
      "entry": "/RealOutputs/Drive/Pose",
      "distance": 0.5138691792703741
    },
    "... (13 more items)"
  ],
  "limits": {
    "pose_jumps": {
      "total": 16,
      "returned": 16,
      "limit": 100
    }
  },
  "jump_count": 16,
  "pose_entries_checked": [
    "/RealOutputs/Drive/Pose"
  ],
  "data_quality": {
    "sample_count": 8794,
    "time_span_seconds": 1569.41,
    "sampling": "change_only",
    "gap_count": 713,
    "max_gap_ms": 24492.7,
    "effective_sample_rate_hz": 19.1,
    "quality_score": 0.7,
    "reasons": [
      "62.4% of the time span is in 713 intervals longer than 5x the median with no new value (longest 24493 ms): the value held or was not logged, and statistics weigh samples, not time"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 8794 samples over 1569.4 seconds",
    "interpretation_guidance": [
      "Timing is consistent with values logged only when they change (sampling change_only): a long interval between samples means the value held, not missing data; a sample count is a count of changes.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  }
}
```

### `compare_poses`

The difference between two pose streams (struct:Pose2d or Pose3d, the latter projected on the floor), sampled at the records of pose_entry with reference_entry interpolated there (linear, heading along the shortest arc; 'previous' for a reference logged when it changes; no value across a gap longer than max_gap_sec). pose minus reference: distance_m (count, mean, median, p95, max, rmse), heading_difference_rad (signed mean; median, p95, max of its size), and components: frame 'field' gives dx_m and dy_m in field coordinates; frame 'reference' gives along_m (positive: pose ahead of the reference along its heading) and cross_m (positive: pose to its left), as a path-following error is usually read. largest lists the times of the largest distances. Uses: path following (setpoint as reference), two pose estimators, a pose against a camera's estimate. For each camera observation at its own timestamp, use analyze_vision. pose_entry defaults to the robot pose (a conventional name, else the only Pose2d); several others are listed to confirm, not guessed. Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation.

**Parameters** ([TOOLS.md](TOOLS.md#compare_poses))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `reference_entry` | string | yes | The pose to measure against (struct:Pose2d or Pose3d), e.g. a path setpoint |
| `pose_entry` | string | no | The pose measured (struct:Pose2d or Pose3d); default: the robot pose |
| `frame` | string | no | 'field' (default: dx_m, dy_m) or 'reference' (along_m, cross_m in the reference's heading) |
| `interpolation` | string | no | 'linear' (default) or 'previous' for the reference |
| `max_gap_sec` | number | no | Longest reference gap to interpolate across (default 0.25) |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: The turret's pose in the robot's frame**

Request:
```json
{
  "name": "compare_poses",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "pose_entry": "/RealOutputs/Launcher/TurretPose",
    "reference_entry": "/RealOutputs/Drive/Pose",
    "frame": "reference",
    "scope": "enabled"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "pose_entry": "/RealOutputs/Launcher/TurretPose",
  "reference_entry": "/RealOutputs/Drive/Pose",
  "frame": "reference",
  "interpolation": "linear",
  "count": 6681,
  "unaligned": 0,
  "inputs": {
    "entries": {
      "pose": "/RealOutputs/Launcher/TurretPose",
      "reference": "/RealOutputs/Drive/Pose"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    }
  },
  "distance_m": {
    "count": 6681,
    "mean": 0.16253430926625193,
    "median": 0.13853859949313763,
    "p95": 0.1385385994931381,
    "max": 4.691519928707817,
    "rmse": 0.3399935950352601
  },
  "heading_difference_rad": {
    "count": 6681,
    "median": 1.7456877867328089,
    "p95": 2.99165940284729,
    "max": 3.141256809234619,
    "mean_signed": -0.09281073165704824
  },
  "along_m": {
    "count": 6681,
    "mean": -0.06245924541832377,
    "std_dev": 0.060486478942520835,
    "p5": -0.05768340000000048,
    "p95": -0.05768339999999955
  },
  "cross_m": {
    "count": 6681,
    "mean": -0.10866961627339125,
    "std_dev": 0.31022852962835135,
    "p5": -0.12595860000000042,
    "p95": -0.12595859999999953
  },
  "largest": [
    {
      "timestamp_sec": 112.008064,
      "distance_m": 4.691519928707817
    },
    {
      "timestamp_sec": 111.983244,
      "distance_m": 4.67069539021696
    },
    {
      "timestamp_sec": 111.962524,
      "distance_m": 4.652757101212706
    },
    {
      "timestamp_sec": 111.94121,
      "distance_m": 4.6385506302046915
    },
    {
      "timestamp_sec": 111.919729,
      "distance_m": 4.62596507430743
    }
  ],
  "limits": {
    "largest": {
      "total": 6681,
      "returned": 5,
      "limit": 5
    }
  },
  "data_quality": {
    "sample_count": 6681,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.5 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6681 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `pose_corrections`

How much a pose changed beyond what odometry predicts: for each pair of consecutive pose_entry records (in scope, at most max_interval_sec apart), its change minus the predicted change, where the prediction is the change of odometry_pose_entry (a pose from wheel odometry alone, rotated into the pose's frame), or else chassis_speeds_entry integrated over the interval (robot-relative by default, rotated by the pose's heading; speeds_frame 'field' for field-relative speeds; odometry.frame_check gives the median residual read either way, and a warning says when the other frame fits better). Returns residual_translation_m (count, mean, median, p95, p99, max) and residual_heading_rad (sizes), and the corrections: intervals whose residual is at least threshold_m (or heading_threshold_rad), in time order with dx_m, dy_m, translation_m, heading_rad, and speed_mps, and correction_count, total_translation_m, and correction_interval_sec (n, min, median, p95, max: the cadence of corrections, e.g. vision updates). A correction within 0.5 s of an enable has near_enable_sec (odometry is often reset there). A residual is not by itself a vision correction: wheel slip, collisions, a pose reset, and timing differences between the entries also make one; compare with the vision entries (analyze_vision) before attributing it. pose_entry defaults to the robot pose and chassis_speeds_entry to the measured chassis speeds (conventional names or the only candidate; others are listed to confirm, not guessed). Time: start_time/end_time, scope ('enabled', 'disabled', 'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals find_condition returns) combine; differences, peaks, and unwrapping stay within each window, and data_quality does not count the time between windows as a gap.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#pose_corrections))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pose_entry` | string | no | The pose (struct:Pose2d or Pose3d), e.g. a pose estimator's output; default: the robot pose |
| `odometry_pose_entry` | string | no | A pose from wheel odometry alone; when given, its change is the prediction |
| `chassis_speeds_entry` | string | no | struct:ChassisSpeeds to integrate when no odometry pose is given; default: the measured chassis speeds |
| `speeds_frame` | string | no | 'robot' (default, as kinematics produce them) or 'field' |
| `threshold_m` | number | no | Residual translation that counts as a correction (default 0.05) |
| `heading_threshold_rad` | number | no | Residual heading that counts as a correction (default: none) |
| `max_interval_sec` | number | no | Longest interval between pose records to compare (default 0.1) |
| `start_time` | number | no | Start timestamp (s) |
| `end_time` | number | no | End timestamp (s) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `windows` | array | no | Explicit time windows, each {start, end} in seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals returned by find_condition (whose end is the sample where the condition turned false). Intersected with scope and start_time/end_time. |
| `limit` | integer | no | Maximum corrections listed (max 500) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Pose steps while the robot sat disabled**

Request:
```json
{
  "name": "pose_corrections",
  "arguments": {
    "path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
    "scope": "disabled",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "pose_entry": "/RealOutputs/Drive/Pose",
  "inputs": {
    "entries": {
      "pose": "/RealOutputs/Drive/Pose",
      "chassis_speeds": "/RealOutputs/SwerveChassisSpeeds/Measured"
    },
    "scope": {
      "scope": "disabled",
      "windows": [
        [
          8.359178,
          40.207135
        ],
        [
          359.161586,
          395.209541
        ],
        [
          744.929426,
          791.534465
        ],
        [
          840.133016,
          995.211072
        ]
      ],
      "window_count": 4,
      "total_sec": 269.5790069999999
    }
  },
  "odometry": {
    "source": "chassis_speeds",
    "entry": "/RealOutputs/SwerveChassisSpeeds/Measured",
    "speeds_frame": "robot",
    "basis": "struct:ChassisSpeeds, named 'measured'",
    "frame_check": {
      "robot_median_m": 7.972007441756449E-6,
      "field_median_m": 8.307890446994128E-6
    }
  },
  "robot_pose": {
    "description": "Robot pose (odometry or estimator)",
    "entry": "/RealOutputs/Drive/Pose",
    "match": "convention",
    "basis": "a robot pose by convention (DriveState/Pose, Odometry/Robot, Drive/Pose, EstimatedPose, RobotPose, PathPlanner/currentPose)",
    "candidates": [
      "/RealOutputs/Drive/Pose"
    ],
    "used_by": "analyze_vision (pose_entry), compare_poses (pose_entry), pose_corrections (pose_entry), analyze_swerve (odometry_entry)"
  },
  "intervals": {
    "analyzed": 10465,
    "longer_than_max": 103,
    "without_odometry": 0,
    "unreadable_pose_records": 0
  },
  "residual_translation_m": {
    "count": 10465,
    "mean": 0.01360233527139004,
    "median": 7.972007441756449E-6,
    "p95": 0.10899482857198398,
    "p99": 0.2964007756469174,
    "max": 0.5593575667827476
  },
  "residual_heading_rad": {
    "count": 10465,
    "mean": 0.001483114253589569,
    "median": 1.6792550308688228E-5,
    "p95": 0.0056424867550949566,
    "p99": 0.0346221682038128,
    "max": 0.09734498788822235
  },
  "threshold_m": 0.05,
  "correction_count": 712,
  "total_translation_m": 117.82032679590415,
  "corrections": [
    {
      "timestamp_sec": 359.326347,
      "interval_sec": 0.020238000000006195,
      "dx_m": 0.1400796354903109,
      "dy_m": -0.27390921631123727,
      "translation_m": 0.3076500659179753,
      "heading_rad": -0.03417794870652695,
      "speed_mps": 3.853468007642586E-4
    },
    {
      "timestamp_sec": 359.428632,
      "interval_sec": 0.02127699999999777,
      "dx_m": -0.1408228860703895,
      "dy_m": 0.29025130519186715,
      "translation_m": 0.32260952466840825,
      "heading_rad": 0.038886193106626464,
      "speed_mps": 4.022230893386826E-4
    },
    {
      "timestamp_sec": 359.574583,
      "interval_sec": 0.019719000000009146,
      "dx_m": 0.13794977834096475,
      "dy_m": -0.34543778337753794,
      "translation_m": 0.37196425033745933,
      "heading_rad": -0.04198362707778409,
      "speed_mps": 4.2655606881134847E-4
    }
  ],
  "limits": {
    "corrections": {
      "total": 712,
      "returned": 3,
      "limit": 3
    }
  },
  "correction_interval_sec": {
    "n": 711,
    "min": 0.0956620000000612,
    "median": 0.13463600000000042,
    "p95": 0.904164000000037,
    "max": 368.678189
  },
  "data_quality": {
    "sample_count": 10572,
    "time_span_seconds": 269.48,
    "sampling": "periodic",
    "gap_count": 103,
    "max_gap_ms": 2467.9,
    "effective_sample_rate_hz": 49.1,
    "quality_score": 0.85,
    "reasons": [
      "8.9% of the time span is in 103 gaps longer than 5x the median interval (longest 2468 ms)",
      "irregular timing: intervals deviate from the 20.4 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 10572 samples over 269.5 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use analyze_vision to see whether corrections coincide with vision observations, and find_condition to limit the scope to driving"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 116490199; the rest of the file was not read. Data from 8.36 to 1588.27 s was recovered."
  }
}
```

**Example: Corrections while driving**

Request:
```json
{
  "name": "pose_corrections",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "scope": "enabled",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "pose_entry": "/RealOutputs/Drive/Pose",
  "inputs": {
    "entries": {
      "pose": "/RealOutputs/Drive/Pose",
      "chassis_speeds": "/RealOutputs/SwerveChassisSpeeds/Measured"
    },
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    }
  },
  "odometry": {
    "source": "chassis_speeds",
    "entry": "/RealOutputs/SwerveChassisSpeeds/Measured",
    "speeds_frame": "robot",
    "basis": "struct:ChassisSpeeds, named 'measured'",
    "frame_check": {
      "robot_median_m": 0.0014067967782685198,
      "field_median_m": 0.03313597690587128
    }
  },
  "robot_pose": {
    "description": "Robot pose (odometry or estimator)",
    "entry": "/RealOutputs/Drive/Pose",
    "match": "convention",
    "basis": "a robot pose by convention (DriveState/Pose, Odometry/Robot, Drive/Pose, EstimatedPose, RobotPose, PathPlanner/currentPose)",
    "candidates": [
      "/RealOutputs/Launcher/TurretPose",
      "/RealOutputs/Drive/Pose",
      "/RealOutputs/AutoSelector/AutonomousInitialPose"
    ],
    "used_by": "analyze_vision (pose_entry), compare_poses (pose_entry), pose_corrections (pose_entry), analyze_swerve (odometry_entry)"
  },
  "intervals": {
    "analyzed": 6645,
    "longer_than_max": 34,
    "without_odometry": 0,
    "unreadable_pose_records": 0
  },
  "residual_translation_m": {
    "count": 6645,
    "mean": 0.0032454790882450856,
    "median": 0.0014067967782685198,
    "p95": 0.010986328519861628,
    "p99": 0.024499506253424362,
    "max": 0.1372925913357526
  },
  "residual_heading_rad": {
    "count": 6645,
    "mean": 0.018053424299945973,
    "median": 0.011792607830083934,
    "p95": 0.05522784787994532,
    "p99": 0.0984758924079326,
    "max": 0.2750710763641916
  },
  "threshold_m": 0.05,
  "correction_count": 15,
  "total_translation_m": 1.0493074205500266,
  "corrections": [
    {
      "timestamp_sec": 112.213969,
      "interval_sec": 0.07423700000001077,
      "dx_m": 0.051935516703449786,
      "dy_m": 0.0022583081304100926,
      "translation_m": 0.05198459243724229,
      "heading_rad": 0.048272682914462656,
      "speed_mps": 1.708657028088
    },
    {
      "timestamp_sec": 116.489911,
      "interval_sec": 0.06414600000000803,
      "dx_m": -0.07643497493675717,
      "dy_m": -0.0012481621357073626,
      "translation_m": 0.07644516532979513,
      "heading_rad": 0.14358792276432866,
      "speed_mps": 1.5187824308101852
    },
    {
      "timestamp_sec": 128.431863,
      "interval_sec": 0.03240700000000629,
      "dx_m": 0.016542744991657494,
      "dy_m": 0.0857124306514509,
      "translation_m": 0.0872942333721924,
      "heading_rad": 0.10217955439281988,
      "speed_mps": 1.590649377502534
    }
  ],
  "limits": {
    "corrections": {
      "total": 15,
      "returned": 3,
      "limit": 3
    }
  },
  "correction_interval_sec": {
    "n": 14,
    "min": 0.06952499999999873,
    "median": 5.647325000000009,
    "p95": 21.753517399999993,
    "max": 27.094037000000014
  },
  "data_quality": {
    "sample_count": 6681,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.5 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6681 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use analyze_vision to see whether corrections coincide with vision observations, and find_condition to limit the scope to driving"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `profile_mechanism`

Profile one closed-loop mechanism from the numeric entries passed for its roles: following error (measurement_entry minus the setpoint_entry in force, as RMSE, bias, and maximum; the two must be in the same units), step response for each setpoint step (settling time into a 5% band of the step, percent overshoot of the step), stall events (|current_entry| above stall_current_threshold while |velocity_entry| is below stall_velocity_threshold, each with its signed peak current by magnitude), and motor temperature (temperature_entry: maximum and final). Only entries passed explicitly are analyzed: a name does not establish that an entry is this mechanism's setpoint or measurement, or its units (a 'currentHeight' is the present height, and PhotonVision's 'targetYaw' is a camera reading, not a setpoint). mechanism_name (a case-insensitive substring of the entry names) finds the candidates: candidates lists, per role, the entries whose leaf name suggests it. With mechanism_name alone the result is no_match with needs_confirmation and the candidates; confirm each from the robot's source code (where the entry is logged), or its values, and pass it. roles names every entry used. Sections without their entries are listed in skipped with the candidates. Returns no_match when nothing matches.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Regression estimates depend on data quality and model assumptions. Physical parameters outside typical ranges (negative inertia, negative damping) indicate model or data issues, not actual physics.

**Parameters** ([TOOLS.md](TOOLS.md#profile_mechanism))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `mechanism_name` | string | no | Text contained in the mechanism's entry names (case-insensitive), e.g. 'Elevator' or 'ModuleFrontLeft/Drive': lists candidate entries per role; none is used until passed |
| `start_time` | number | no | Start timestamp |
| `end_time` | number | no | End timestamp |
| `stall_current_threshold` | number | no | Current threshold for stall (default: 30A) |
| `stall_velocity_threshold` | number | no | \|velocity\| below this counts as stopped, in the velocity entry's units (default: 0.01) |
| `setpoint_entry` | string | no | The mechanism's setpoint entry (a scalar number) |
| `measurement_entry` | string | no | The mechanism's measurement entry (a scalar number) |
| `velocity_entry` | string | no | The mechanism's velocity entry (a scalar number) |
| `current_entry` | string | no | The mechanism's current entry (a scalar number) |
| `temperature_entry` | string | no | The mechanism's temperature entry (a scalar number) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: A name alone: candidates to confirm**

Request:
```json
{
  "name": "profile_mechanism",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "mechanism_name": "/Drive/Module0"
  }
}
```

Response:
```json
{
  "success": false,
  "status": "no_match",
  "reason": "No role entries were passed. profile_mechanism analyzes only entries passed explicitly: a name does not establish that an entry is this mechanism's setpoint, measurement, velocity, current, or temperature, or its units.",
  "hint": "candidates lists the entries containing '/Drive/Module0' whose leaf name suggests each role. Confirm each one (the robot's source code, where the entry is logged, shows what it holds and in which units; get_entry_info and read_entry show its type and values; or ask the user) and pass it: setpoint_entry, measurement_entry, velocity_entry, current_entry, temperature_entry. The setpoint and the measu... (60 more characters)",
  "mechanism": "/Drive/Module0",
  "candidates": {
    "measurement": [
      "/Drive/Module0/DrivePositionRad"
    ],
    "velocity": [
      "/Drive/Module0/TurnVelocityRadPerSec",
      "/Drive/Module0/DriveVelocityRadPerSec"
    ],
    "current": [
      "/Drive/Module0/TurnCurrentAmps",
      "/Drive/Module0/DriveCurrentAmps"
    ]
  },
  "needs_confirmation": true,
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

**Example: A drive motor, its entries passed**

Request:
```json
{
  "name": "profile_mechanism",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "velocity_entry": "/Drive/Module0/DriveVelocityRadPerSec",
    "current_entry": "/Drive/Module0/DriveCurrentAmps"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "partial",
  "roles": {
    "setpoint": null,
    "measurement": null,
    "velocity": "/Drive/Module0/DriveVelocityRadPerSec",
    "current": "/Drive/Module0/DriveCurrentAmps",
    "temperature": null
  },
  "inputs": {
    "entries": {
      "velocity": "/Drive/Module0/DriveVelocityRadPerSec",
      "current": "/Drive/Module0/DriveCurrentAmps"
    }
  },
  "skipped": [
    {
      "section": "following_error",
      "reason": "Needs a setpoint and a measurement entry: setpoint_entry was not passed; measurement_entry was not passed."
    },
    {
      "section": "temperature",
      "reason": "Needs a temperature entry: temperature_entry was not passed."
    }
  ],
  "stall_events": [],
  "limits": {
    "stall_events": {
      "total": 0,
      "returned": 0,
      "limit": 50
    }
  },
  "stall_count": 0,
  "data_quality": {
    "sample_count": 6470,
    "time_span_seconds": 321.04,
    "sampling": "periodic",
    "gap_count": 79,
    "max_gap_ms": 69282.9,
    "effective_sample_rate_hz": 48.5,
    "quality_score": 0.69,
    "reasons": [
      "51.4% of the time span is in 79 gaps longer than 5x the median interval (longest 69283 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 4% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "medium",
    "sample_context": "Based on 6470 samples over 321.0 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ],
    "suggested_followup": [
      "Use moi_regression for mechanism inertia estimation"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `analyze_auto`

Analyze autonomous periods: every enabled autonomous segment (from the same DriverStation timeline as get_match_phases) with its start, end, duration, and end_reason; the selected routine at each start (from a WPILib SendableChooser's active entry, or AdvantageKit's /NetworkInputs/SmartDashboard/<key>, when exactly one chooser's key contains 'auto'; other choosers are listed to confirm; chooser_entry names another); and path following error (RMSE and max, meters) between a setpoint pose (PathPlanner/targetPose or Odometry/TrajectorySetpoint; path_setpoint_entry) and the actual pose (PathPlanner/currentPose, else the robot pose; path_actual_entry). Other entries named like these are not guessed at: skipped lists them as candidates to confirm. Returns status not_applicable with the reason when the log has no autonomous period (for example a practice session where Autonomous was never true).



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#analyze_auto))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `auto_prefix` | string | no | Entry name prefix to search for the path setpoint and actual pose entries |
| `chooser_entry` | string | no | String entry holding the selected auto routine (default: a SendableChooser's active entry, see description) |
| `path_setpoint_entry` | string | no | Pose2d/Pose3d path-following setpoint (default: PathPlanner/targetPose or Odometry/TrajectorySetpoint) |
| `path_actual_entry` | string | no | Pose2d/Pose3d actual pose for path following (default: PathPlanner/currentPose, else the robot pose) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Autonomous**

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
  "status": "partial",
  "inputs": {
    "entries": {
      "enabled": "/DriverStation/Enabled",
      "autonomous": "/DriverStation/Autonomous"
    }
  },
  "auto_periods": [
    {
      "start": 110.991153,
      "end": 131.611537,
      "duration": 20.620384,
      "end_reason": "disabled"
    }
  ],
  "auto_start_time": 110.991153,
  "auto_end_time": 131.611537,
  "auto_duration": 20.620384,
  "expected_auto_sec": 20,
  "skipped": [
    {
      "section": "selected_routine",
      "reason": "No entry follows a known convention for the selected autonomous routine. Candidates, not used: /RealOutputs/AutoSelector/SelectedAutoMode. Confirm which one (if any) is the selected autonomous routine (the robot's source code, where the entry is logged, shows what it holds and in which units; get_entry_info and read_entry show its type and values; or ask the user) and pass it as chooser_entry; the... (23 more characters)"
    },
    {
      "section": "path_following_error",
      "reason": "No path-following setpoint pose entry found (no PathPlanner/targetPose or Odometry/TrajectorySetpoint); if the log has one under another name, pass it as path_setpoint_entry."
    }
  ],
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `analyze_cycles`

Analyze game piece handling cycle times with configurable cycle detection modes (start-to-start or start-to-end), dead time tracking, and data quality warnings. Supports time filtering, case-sensitive/insensitive matching, and incomplete cycle detection. The state entry is read as a state: a cycle starts when it changes to cycle_start_state (required in the default start_to_start mode), so a state repeated every loop is one state, not a new cycle per sample. Returns no_match, with the states seen, when cycle_start_state never occurs.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#analyze_cycles))

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

**Example: Feeder command cycles**

Request:
```json
{
  "name": "analyze_cycles",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "state_entry": "/RealOutputs/Subsystems/Feeder/Command",
    "cycle_start_state": "ParallelCommandGroup",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "sample_count": 57,
  "cycle_mode": "start_to_start",
  "warnings": [
    "Detected 7 rapid state transitions (<0.1s apart) - may indicate state machine instability",
    "Detected unknown states: SequentialCommandGroup, Reverse, Stop, Spin Forward, None",
    "1 cycle(s) incomplete (log ended mid-cycle). Exclude from statistical analysis."
  ],
  "cycle_times": {
    "count": 19,
    "avg_sec": 7.074489631578949,
    "min_sec": 0.16114400000000728,
    "max_sec": 88.40687
  },
  "cycles": [
    {
      "start_time": 139.630758,
      "end_time": 170.450767,
      "duration": 30.820009000000027,
      "incomplete": false
    },
    {
      "start_time": 170.450767,
      "end_time": 171.594356,
      "duration": 1.1435889999999915,
      "incomplete": false
    },
    {
      "start_time": 171.594356,
      "end_time": 172.397812,
      "duration": 0.8034559999999829,
      "incomplete": false
    }
  ],
  "limits": {
    "cycles": {
      "total": 20,
      "returned": 3,
      "limit": 3
    }
  },
  "data_quality": {
    "sample_count": 57,
    "time_span_seconds": 251.52,
    "sampling": "change_only",
    "gap_count": 11,
    "max_gap_ms": 84131.0,
    "effective_sample_rate_hz": 2.9,
    "quality_score": 0.4,
    "reasons": [
      "93.9% of the time span is in 11 intervals longer than 5x the median with no new value (longest 84131 ms): the value held or was not logged, and statistics weigh samples, not time",
      "only 57 finite samples (fewer than 100)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "low",
    "sample_context": "Based on 57 samples over 251.5 seconds",
    "interpretation_guidance": [
      "Low sample count (57). Statistical measures have high uncertainty.",
      "Timing is consistent with values logged only when they change (sampling change_only): a long interval between samples means the value held, not missing data; a sample count is a count of changes.",
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions."
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/RealOutputs/Subsystems/Feeder/Command"
    ]
  }
}
```

### `analyze_replay_drift`

Validate AdvantageKit deterministic replay: in a replay output log (the _sim log AdvantageKit's replay writes), compare every /RealOutputs/X entry with /ReplayOutputs/X sample by sample (timestamps matched within 1 ms). Numbers are equal within relative_tolerance (default 1e-9); arrays and structs are compared element by element. Returns pairs_compared, the entries present on only one side, and for each divergent entry the first divergence time, divergent/compared sample counts, the largest numeric difference, and the values at the first divergence. Returns not_applicable on a log without /ReplayOutputs/ entries (a real-robot log). Small drift may come from non-deterministic inputs; look for large or systematic divergence.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#analyze_replay_drift))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relative_tolerance` | number | no | Numbers within this fraction of their magnitude are equal (default 1e-9) |
| `limit` | integer | no | Maximum divergent entries to list (default 20) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: A replay log**

Request:
```json
{
  "name": "analyze_replay_drift",
  "arguments": {
    "path": "<logdir>/vache/session_55/akit_26-03-22_18-15-22_vache_e4_sim.wpilog",
    "limit": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "pairs_compared": 181,
  "samples_compared": 244233,
  "divergent_count": 62,
  "relative_tolerance": 1.0E-9,
  "divergences": [
    {
      "entry": "/RealOutputs/Logger/AlertLogMS",
      "type": "double",
      "first_divergence_time": 12.519009,
      "divergent_samples": 5020,
      "compared_samples": 5020,
      "max_abs_difference": 20.830000000000002,
      "first_divergence": {
        "timestamp": 12.519009,
        "real": "12.985",
        "replay": "0.529"
      }
    },
    {
      "entry": "/RealOutputs/Console",
      "type": "string",
      "first_divergence_time": 12.519009,
      "divergent_samples": 3,
      "compared_samples": 3,
      "first_divergence": {
        "timestamp": 12.519009,
        "real": "********** Robot program starting **********\nNT: Listening on NT3 port 1735, NT4 port 5810\nNT: Got a NT4 connection from 10.23.63.202 port 42940\nNT: CONNECTED NT4 client 'photonvision@1' (from 10.23.6...",
        "replay": "[AdvantageKit] Logging to \"~/th/vache/usb/logs/session_55/akit_26-03-22_18-15-22_vache_e4_sim.wpilog\""
      }
    },
    {
      "entry": "/RealOutputs/Logger/DashboardInputsMS",
      "type": "double",
      "first_divergence_time": 12.519009,
      "divergent_samples": 86,
      "compared_samples": 121,
      "max_abs_difference": 0.221,
      "first_divergence": {
        "timestamp": 12.519009,
        "real": "0.037",
        "replay": "0.003"
      }
    }
  ],
  "limits": {
    "divergences": {
      "total": 62,
      "returned": 3,
      "limit": 3
    },
    "real_only_entries": {
      "total": 21,
      "returned": 21,
      "limit": 50
    },
    "replay_only_entries": {
      "total": 0,
      "returned": 0,
      "limit": 50
    }
  },
  "samples_without_counterpart": 90616,
  "real_only_entries": [
    "/RealOutputs/PhotonAlerts/errors",
    "/RealOutputs/PhotonAlerts/.type",
    "/RealOutputs/PhotonAlerts/infos",
    "... (18 more items)"
  ],
  "real_only_count": 21,
  "replay_only_entries": [],
  "replay_only_count": 0,
  "warnings": [
    "21 /RealOutputs/ entries have no replay counterpart and were not compared."
  ],
  "inputs": {
    "log": "<logdir>/vache/session_55/akit_26-03-22_18-15-22_vache_e4_sim.wpilog",
    "entries_read": [
      "/RealOutputs/Alerts/.type",
      "/RealOutputs/Alerts/Choreo/Errors",
      "/RealOutputs/Alerts/Choreo/Infos",
      "... (7 more items)"
    ],
    "entries_read_total": 362
  }
}
```

### `predict_battery_health`

Battery and power-delivery evidence with a heuristic health score (0-100) and risk level (MINIMAL/LOW/MODERATE/HIGH/CRITICAL). Facts first: voltage statistics over the scope (default: enabled time when the log records it); brownouts from the roboRIO's logged flag (e.g. /SystemStats/BrownedOut, with start and duration) or, when no flag is logged, threshold crossings (basis stated); the brownout threshold from the log's BrownoutVoltage entry when logged, else 6.8 V (roboRIO 1, stated); dips below warning_threshold; and, when a total-current entry exists, the load line: battery voltage regressed on total current, giving the effective source resistance (battery internal resistance plus wiring and connectors) and open-circuit voltage. observations state what the evidence is consistent with and what would distinguish the causes; one log cannot tell a weak battery from high current draw or a bad connection, so no replacement advice is given. The voltage entry is BatteryVoltage or Voltage under PowerDistribution/PDH/PDP/Battery, the current entry TotalCurrent; the server does not guess among other names: it lists them to confirm, and voltage_entry / total_current_entry name the ones to use.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Voltage drops may indicate power issues, aggressive driving, worn battery, or loose connections. Single brownout events are not necessarily concerning—look for patterns across matches.

**Parameters** ([TOOLS.md](TOOLS.md#predict_battery_health))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. Default: 'enabled' when the log records enabled state, else 'all'. |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `nominal_voltage` | number | no | Expected full battery voltage (default: 12.6V) |
| `brownout_threshold` | number | no | Brownout threshold in volts (default: the log's BrownoutVoltage entry when logged, else 6.8 V for roboRIO 1; roboRIO 2 is 6.3 V) |
| `warning_threshold` | number | no | Voltage below which a dip is reported (default: 9.0V) |
| `voltage_entry` | string | no | Battery voltage entry (default: BatteryVoltage, or Voltage under PowerDistribution/PDH/PDP/Battery) |
| `total_current_entry` | string | no | Total robot current entry for the load line (default: TotalCurrent) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Evidence**

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
  "status": "partial",
  "scope": {
    "scope": "enabled",
    "windows": [
      [
        110.991153,
        131.611537
      ],
      [
        135.642118,
        278.380621
      ]
    ],
    "window_count": 2,
    "total_sec": 163.358887
  },
  "inputs": {
    "entries": {
      "voltage": "/SystemStats/BatteryVoltage",
      "total_current": "/PowerDistribution/TotalCurrent",
      "rio_brownout_flag": "/SystemStats/BrownedOut"
    }
  },
  "health_score": 0,
  "health_score_basis": "heuristic: 100, minus 20 per brownout, 5 per dip below warning_threshold, and penalties for a low average (below 88% of nominal), a minimum below 10 V, and slow recovery; compare batteries across logs rather than reading the number alone",
  "risk_level": "CRITICAL",
  "risk_level_basis": "CRITICAL when the roboRIO's logged brownout flag was set in scope; HIGH for a threshold crossing without a flag, a minimum below warning_threshold, or a health score below 30; MODERATE below 60; LOW below 80; MINIMAL otherwise",
  "voltage_stats": {
    "min_volts": 6.680678710937499,
    "min_time_sec": 194.444747,
    "max_volts": 12.655308349609374,
    "avg_volts": 9.307719026401001,
    "voltage_sag": 5.9193212890625,
    "samples": 6584
  },
  "brownout_threshold": 6.75,
  "brownout_threshold_basis": "logged",
  "brownout_threshold_entry": "/SystemStats/BrownoutVoltage",
  "brownout_events": 5,
  "brownout_basis": "rio_flag: intervals where /SystemStats/BrownedOut was true (the roboRIO disabled outputs)",
  "rio_brownouts": {
    "flag_entry": "/SystemStats/BrownedOut",
    "count": 5,
    "total_sec": 0.45058100000008494,
    "events": [
      {
        "start": 143.079221,
        "end": 143.133666,
        "duration_sec": 0.0544450000000154
      },
      {
        "start": 194.071279,
        "end": 194.20445,
        "duration_sec": 0.13317100000000437
      },
      {
        "start": 194.444747,
        "end": 194.526719,
        "duration_sec": 0.08197200000000748
      },
      {
        "start": 264.272604,
        "end": 264.393662,
        "duration_sec": 0.121058000000005
      },
      {
        "start": 264.588604,
        "end": 264.648539,
        "duration_sec": 0.059935000000052696
      }
    ]
  },
  "threshold_crossings": 1,
  "brownout_details": [
    {
      "start_time": 194.444747,
      "end_time": 194.466598,
      "duration": 0.021850999999998066,
      "min_voltage": 6.680678710937499
    }
  ],
  "limits": {
    "brownout_details": {
      "total": 1,
      "returned": 1,
      "limit": 10
    }
  },
  "warning_events": 179,
  "recovery_analysis": {
    "avg_recovery_sec": 0.38702562021857917,
    "max_recovery_sec": 1.900466999999992,
    "sample_count": 366
  },
  "skipped": [
    {
      "section": "load_line",
      "reason": "Too few samples or too little current variation in scope to fit voltage against /PowerDistribution/TotalCurrent."
    }
  ],
  "observations": [
    "5 roboRIO brownout(s) (143.08 s for 0.054 s; 194.07 s for 0.133 s; 194.44 s for 0.082 s; 264.27 s for 0.121 s; 264.59 s for 0.060 s). Candidate causes: high current draw at those moments (check power_analysis channel peaks in the same windows), a weak or undercharged battery, or high-resistance connections. One log cannot distinguish them: compare this battery across logs and inspect connectors.",
    "Minimum voltage 6.68 V at 194.44 s, below the 9.0 V warning threshold.",
    "Average voltage over the scope (enabled) was 9.31 V. This is consistent with a partly discharged battery or sustained high load; the charge at the start of the log and other logs with this battery would tell them apart."
  ],
  "recommendations": [
    "5 roboRIO brownout(s) (143.08 s for 0.054 s; 194.07 s for 0.133 s; 194.44 s for 0.082 s; 264.27 s for 0.121 s; 264.59 s for 0.060 s). Candidate causes: high current draw at those moments (check power_analysis channel peaks in the same windows), a weak or undercharged battery, or high-resistance connections. One log cannot distinguish them: compare this battery across logs and inspect connectors.",
    "Minimum voltage 6.68 V at 194.44 s, below the 9.0 V warning threshold.",
    "Average voltage over the scope (enabled) was 9.31 V. This is consistent with a partly discharged battery or sustained high load; the charge at the start of the log and other logs with this battery would tell them apart."
  ],
  "data_quality": {
    "sample_count": 6584,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6584 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "The health score is a heuristic; battery age, charge, connector condition, and current draw all move it. Compare the same battery across logs."
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `analyze_loop_timing`

Loop timing: how often robot code exceeded the loop period (threshold_ms, default 20 ms) and the distribution of loop times (mean, median, p90, p95, p99, max with its time), over a scope (e.g. 'enabled') or window. The entry is found in this order: the entry argument; AdvantageKit's LoggedRobot/FullCycleMS (whole cycle, including logging), reported with LoggedRobot/UserCodeMS alongside; loop periods derived from consecutive AdvantageKit /Timestamp values; UserCodeMS alone. Other entries named like a loop time (looptime, cycletime) are not guessed at: no_match lists them as candidates to confirm and pass as entry. The unit comes from the argument, the name (...MS, ...Ms, _ms), or the median (basis reported). A first sample more than 10x the median (the slow boot cycle) is excluded and reported. health_score (0-100) is 100 minus the percent of loops over the threshold. Returns no_match when the log has no loop timing, with the count of WPILib loop-overrun console messages if any.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation. Match conditions vary (battery age, field surface, alliance partners). A single match is one sample—do not generalize without cross-match data.

**Parameters** ([TOOLS.md](TOOLS.md#analyze_loop_timing))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `entry` | string | no | Loop time entry (default: discovered, see description) |
| `threshold_ms` | number | no | Loop time threshold in milliseconds (default: 20) |
| `unit` | string | no | Unit of the entry's values: 'ms', 's', 'us', or 'auto' (default: from the name, else the median) |
| `scope` | string | no | Time scope: 'all' (default), 'enabled', 'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from get_match_phases, counting from 0). Combined with start_time/end_time when both are given. |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: While enabled**

Request:
```json
{
  "name": "analyze_loop_timing",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "scope": "enabled",
    "threshold_ms": 25
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "loop_time_entry": "/RealOutputs/LoggedRobot/FullCycleMS",
  "unit": {
    "value": "ms",
    "basis": "name (FullCycleMS)"
  },
  "scope": {
    "scope": "enabled",
    "windows": [
      [
        110.991153,
        131.611537
      ],
      [
        135.642118,
        278.380621
      ]
    ],
    "window_count": 2,
    "total_sec": 163.358887
  },
  "threshold_ms": 25.0,
  "violation_count": 862,
  "total_samples": 6678,
  "violation_rate": 0.1290805630428272,
  "percent_over_threshold": 12.90805630428272,
  "health_score": 87,
  "health_score_basis": "heuristic: 100 minus the percent of loops in scope over threshold_ms (25.0 ms); the statistics and violations beside it are the measurements",
  "statistics": {
    "avg_ms": 20.910544474393532,
    "median_ms": 17.809,
    "p90_ms": 29.201200000000014,
    "p95_ms": 43.73879999999996,
    "p99_ms": 78.14191999999991,
    "max_ms": 387.862,
    "max_time_sec": 110.991153,
    "min_ms": 9.535
  },
  "violations": [
    {
      "timestamp": 110.991153,
      "loop_time_ms": 387.862,
      "overage_ms": 362.862
    },
    {
      "timestamp": 111.379423,
      "loop_time_ms": 27.417,
      "overage_ms": 2.4170000000000016
    },
    {
      "timestamp": 111.407341,
      "loop_time_ms": 49.844,
      "overage_ms": 24.844
    },
    "... (47 more items)"
  ],
  "limits": {
    "violations": {
      "total": 862,
      "returned": 50,
      "limit": 50
    }
  },
  "inputs": {
    "entries": {
      "loop_time": "/RealOutputs/LoggedRobot/FullCycleMS"
    }
  },
  "user_code": {
    "entry": "/RealOutputs/LoggedRobot/UserCodeMS",
    "basis": "robot code only; the full cycle adds logging and other overhead",
    "median_ms": 15.081,
    "p95_ms": 37.3311,
    "percent_over_threshold": 9.997002398081534
  },
  "data_quality": {
    "sample_count": 6678,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.5 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6678 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Health score is a heuristic based on violation rate — consider context of violations"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  }
}
```

### `get_game_info`

Get year-specific FRC game information (match timing, scoring values and ranking-point thresholds by event tier, field geometry, game pieces, robot_constraints, and analysis hints). Use this to understand the context of a log file: what the match phases are, what scoring actions look like, and what mechanisms to expect. Defaults to the current season if no year is specified. The values are a bundled knowledge base transcribed from the season's game manual, not anything read from a log: the result's source, manual_version, and basis say so. Quote them as 'per the bundled game data', and check the current manual before relying on a threshold, since Team Updates change thresholds during a season.

**Parameters** ([TOOLS.md](TOOLS.md#get_game_info))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `season` | integer | no | FRC season year (e.g., 2026). Defaults to current year. |

**Example: 2026**

Request:
```json
{
  "name": "get_game_info",
  "arguments": {
    "season": 2026
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "season": 2026,
  "game_name": "REBUILT",
  "source": "bundled knowledge base, not the log; verify against the current manual",
  "manual_version": "TU22 (final, 2026-04-21)",
  "manual_url": "https://firstfrc.blob.core.windows.net/frc2026/Manual/2026GameManual.pdf",
  "basis": "match_timing, scoring, field_geometry, game_pieces, and robot_constraints are transcribed from the 2026 game manual (manual_version) into the game file this result was read from; nothing here is measured from a log",
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
        "description": "Amount of FUEL scored in an active HUB at or above threshold (Tables 6-4 and 6-5)",
        "value": 1,
        "threshold_unit": "FUEL scored in an active HUB",
        "regional_threshold": 100,
        "district_championship_threshold": 240,
        "championship_threshold": 360
      },
      "supercharged_rp": {
        "description": "Amount of FUEL scored in an active HUB at or above threshold (Tables 6-4 and 6-5)",
        "value": 1,
        "threshold_unit": "FUEL scored in an active HUB",
        "regional_threshold": 360,
        "district_championship_threshold": 360,
        "championship_threshold": 500
      },
      "traversal_rp": {
        "description": "Amount of TOWER points scored during the MATCH at or above threshold (Tables 6-4 and 6-5)",
        "value": 1,
        "threshold_unit": "TOWER points",
        "regional_threshold": 50,
        "district_championship_threshold": 50,
        "championship_threshold": 50
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
      "high_rung_height_m": 1.600
    }
  },
  "robot_constraints": {
    "max_starting_perimeter_in": 110.0,
    "max_starting_height_in": 30.0,
    "max_weight_lbs": 115.0,
    "max_weight_with_bumpers_lbs": 135.0,
    "note": "Weight excludes BUMPERS, the battery, and event-provided location detection tags (R103); with BUMPERS 135 lb (R408). Perimeter and height per R104; height is also capped at 30 in during the MATCH (R107)."
  },
  "game_pieces": [
    {
      "name": "FUEL",
      "type": "foam ball",
      "diameter_m": 0.150,
      "diameter_in": 5.91,
      "preload_max_per_robot": 8,
      "preload_max_per_alliance": 24
    }
  ],
  "analysis_hints": {
    "endgame_activity": "Tower climbing attempts typically occur in the final 30 seconds (END GAME). Level 1 available in AUTO (max 2 robots).",
    "hub_strategy": "Track hub active/inactive status from FMS data. Scoring in inactive hub earns 0 points. Alliance shifts alternate every 25 seconds.",
    "fuel_context": "Each FUEL scored in active HUB = 1 point (auto and teleop). High volume scoring is key: 100 FUEL for ENERGIZED RP and 360 for SUPERCHARGED RP at Regional/District events (240 and 360 at District Championships, 360 and 500 at the FIRST Championship).",
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
  }
}
```

## Export Tools

### `export_csv`

Export an entry to CSV for external analysis (Python, Excel, MATLAB), or return the rows inline. Every value is flattened into columns: a struct becomes one column per numeric or text field (nested fields as dot paths, e.g. translation.x, arrays as field[i]), a struct array or primitive array becomes one row per element with an index column. Files are written inside the server's export directory (export_directory in every file result): pass a bare or relative output_path, which is resolved inside it, or omit it for a generated name; an absolute path must lie inside it. The result gives the absolute path written. With inline=true no file is written and the rows come back in the response (max_rows, default 500), for agents that cannot read the export directory. Returns no_match, and writes nothing, when the window holds no samples. Cite the export when you compute from it.

**Parameters** ([TOOLS.md](TOOLS.md#export_csv))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Entry name to export |
| `output_path` | string | no | CSV file name or path inside the export directory (default: generated from the log and entry names) |
| `start_time` | number | no | Start timestamp in seconds |
| `end_time` | number | no | End timestamp in seconds |
| `inline` | boolean | no | Return the rows in the response instead of writing a file (default false) |
| `max_rows` | integer | no | Rows to return inline (default 500, max 5000) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Inline rows of a struct**

Request:
```json
{
  "name": "export_csv",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "name": "/RealOutputs/Drive/Pose",
    "inline": true,
    "max_rows": 3
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "entry": "/RealOutputs/Drive/Pose",
  "type": "struct:Pose2d",
  "columns": [
    "timestamp_sec",
    "rotation._derived.degrees",
    "rotation.value",
    "translation.x",
    "translation.y"
  ],
  "rows": [
    [
      11.897573,
      0.0,
      0.0,
      0.0,
      0.0
    ],
    [
      26.860151,
      -3.360078905345804,
      -0.05864444002509117,
      1.9588044086175262E-7,
      7.766190128934374E-6
    ],
    [
      28.370381,
      -3.32805833818655,
      -0.05808557569980622,
      2.1402598834835114E-4,
      -6.4032688147233084E-6
    ]
  ],
  "limits": {
    "rows": {
      "total": 13357,
      "returned": 3,
      "limit": 3
    }
  },
  "rows_exported": 3,
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/RealOutputs/Drive/Pose"
    ]
  }
}
```

### `generate_report`

Generate a one-call summary of a log: duration and truncation; the DriverStation timeline (enabled segments, enabled time, FMS matches, as in get_match_phases); battery voltage over enabled time when the log records it (min with time, max, average, threshold crossings; entry chosen as power_analysis does, or voltage_entry) with brownout_risk and its basis as power_analysis gives them, brownouts from the roboRIO flag when logged, and the brownout threshold with its basis; the three largest current peaks in the same scope (power_analysis channel_analysis, each channel of an array separately); error and warning counts from console and message text (each text sample classified once by its most severe line, an alert once per appearance, as in get_ds_timeline and search_strings) with the most frequent messages; code metadata (get_code_metadata); and the most common data types. Each section names its source entries; use the individual tools for detail.



INTERPRETATION GUIDANCE: Results are raw data, not conclusions. Express findings as possibilities, not certainties. Single-match data cannot establish patterns—recommend cross-match comparison. Consider alternative explanations before attributing causation.

**Parameters** ([TOOLS.md](TOOLS.md#generate_report))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `voltage_entry` | string | no | Battery voltage entry (default: BatteryVoltage, or Voltage under PowerDistribution/PDH/PDP/Battery) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Report**

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
  "status": "ok",
  "log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
  "log_filename": "akit_26-03-21_16-29-56_vache_q10.wpilog",
  "basic_info": {
    "duration_sec": 336.00433,
    "start_timestamp": 11.897573,
    "end_timestamp": 347.901903,
    "entry_count": 475,
    "truncated": true,
    "truncation_message": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "timeline": {
    "enabled_segments": 2,
    "enabled_time_sec": 163.358887,
    "matches": 1,
    "season": 2026,
    "source": "/DriverStation/Enabled"
  },
  "battery": {
    "entry": "/SystemStats/BatteryVoltage",
    "scope": {
      "scope": "enabled",
      "windows": [
        [
          110.991153,
          131.611537
        ],
        [
          135.642118,
          278.380621
        ]
      ],
      "window_count": 2,
      "total_sec": 163.358887
    },
    "samples": 6584,
    "min_voltage": 6.680678710937499,
    "min_voltage_time_sec": 194.444747,
    "max_voltage": 12.655308349609374,
    "avg_voltage": 9.307719026401001,
    "samples_below_threshold": 1,
    "threshold_crossings": 1,
    "seconds_below_threshold": 0.021850999999998066,
    "brownout_threshold": 6.75,
    "brownout_threshold_basis": "logged",
    "brownout_threshold_entry": "/SystemStats/BrownoutVoltage",
    "rio_brownouts": {
      "flag_entry": "/SystemStats/BrownedOut",
      "count": 5,
      "total_sec": 0.45058100000008494,
      "events": [
        {
          "start": 143.079221,
          "end": 143.133666,
          "duration_sec": 0.0544450000000154
        },
        {
          "start": 194.071279,
          "end": 194.20445,
          "duration_sec": 0.13317100000000437
        },
        {
          "start": 194.444747,
          "end": 194.526719,
          "duration_sec": 0.08197200000000748
        },
        {
          "start": 264.272604,
          "end": 264.393662,
          "duration_sec": 0.121058000000005
        },
        {
          "start": 264.588604,
          "end": 264.648539,
          "duration_sec": 0.059935000000052696
        }
      ]
    },
    "brownout_risk": "HIGH",
    "brownout_risk_basis": "5 roboRIO brownout(s) in scope (/SystemStats/BrownedOut true: outputs were disabled)"
  },
  "peak_currents": [
    {
      "entry": "/RealOutputs/PDH/TotalCurrentAmps",
      "peak_current_A": 226.0,
      "peak_current_time_sec": 137.380068
    },
    {
      "entry": "/Spindexer/CurrentAmps",
      "peak_current_A": 149.3040313720703,
      "peak_current_time_sec": 180.101979
    },
    {
      "entry": "/Kicker/CurrentAmps",
      "peak_current_A": 115.05494689941406,
      "peak_current_time_sec": 183.310443
    }
  ],
  "errors": {
    "total_errors": 5,
    "total_warnings": 668,
    "distinct_error_messages": 4,
    "top_messages": [
      {
        "message": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:#): HAL: CAN Receive has Timed Out",
        "example": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out",
        "count": 2,
        "first_timestamp": 123.711426
      },
      {
        "message": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:#): PhotonVision coprocessor at path /photonvision/OV#_TH_#_FL not found on NetworkTables. Double check that your camera names ma...",
        "example": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL not found on NetworkTables. Double check that your camera ...",
        "count": 1,
        "first_timestamp": 143.156946
      },
      {
        "message": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:#): Found the following PhotonVision cameras on NetworkTables:",
        "example": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:525): Found the following PhotonVision cameras on NetworkTables:",
        "count": 1,
        "first_timestamp": 143.306515
      },
      {
        "message": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:#): PhotonVision coprocessor at path /photonvision/OV#_TH_#_RL not found on NetworkTables. Double check that your camera names ma...",
        "example": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_RL not found on NetworkTables. Double check that your camera ...",
        "count": 1,
        "first_timestamp": 143.370011
      }
    ],
    "limits": {
      "top_messages": {
        "total": 4,
        "returned": 4,
        "limit": 5
      },
      "samples": {
        "total": 5,
        "returned": 5,
        "limit": 5
      }
    },
    "samples": [
      {
        "timestamp_sec": 123.711426,
        "entry": "/RealOutputs/Console",
        "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
      },
      {
        "timestamp_sec": 124.739764,
        "entry": "/RealOutputs/Console",
        "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:19): HAL: CAN Receive has Timed Out"
      },
      {
        "timestamp_sec": 143.156946,
        "entry": "/RealOutputs/Console",
        "line": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_FL not found on NetworkTables. Double check that your camera ..."
      },
      {
        "timestamp_sec": 143.306515,
        "entry": "/RealOutputs/Console",
        "line": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:525): Found the following PhotonVision cameras on NetworkTables:"
      },
      {
        "timestamp_sec": 143.370011,
        "entry": "/RealOutputs/Console",
        "line": "Error at org.photonvision.PhotonCamera.verifyVersion(PhotonCamera.java:512): PhotonVision coprocessor at path /photonvision/OV2311_TH_2026_RL not found on NetworkTables. Double check that your camera ..."
      }
    ],
    "note": "Counts are text samples classified ERROR or WARNING (a multi-line sample counts once, by its most severe line; an alert once per appearance, by its entry's level); search_strings lists every message."
  },
  "code_info": {
    "git_branch": "main",
    "project_name": "Rebuilt",
    "git_sha": "378d5d47240edd0792ef8a7866bb5fb4b39960a1",
    "git_dirty": "Uncomitted changes",
    "git_date": "2026-03-20 22:42:12 EDT",
    "build_date": "2026-03-21 10:11:35 EDT"
  },
  "top_data_types": {
    "double": 119,
    "boolean": 98,
    "int64": 62,
    "string": 48,
    "string[]": 22,
    "struct:Pose2d[]": 20,
    "int64[]": 18,
    "struct:Rotation2d": 18,
    "double[]": 13,
    "structschema": 12
  },
  "type_count": 25,
  "data_quality": {
    "sample_count": 6584,
    "time_span_seconds": 163.2,
    "sampling": "periodic",
    "gap_count": 31,
    "max_gap_ms": 388.3,
    "effective_sample_rate_hz": 48.7,
    "quality_score": 0.95,
    "reasons": [
      "2.5% of the time span is in 31 gaps longer than 5x the median interval (longest 388 ms)",
      "irregular timing: intervals deviate from the 20.6 ms median by 3% (median absolute deviation)"
    ]
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 6584 samples over 163.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Report is a summary — use individual tools for detailed analysis"
    ]
  },
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/AllianceSelector/AllianceFromSwitch",
      "/Drive/Module0/DriveCurrentAmps",
      "/Drive/Module0/TurnCurrentAmps",
      "... (7 more items)"
    ],
    "entries_read_total": 106
  }
}
```

## TBA Tools

### `get_tba_status`

Check whether The Blue Alliance API is configured and the key works: the key is sent to TBA's status endpoint, and key_check says whether it was accepted (available is false for a rejected key or an unreachable TBA). When TBA is configured, you can: (1) Use list_available_logs to see match scores and win/loss results for each log, or (2) Use get_tba_match_data to query specific match details including autonomous points. TBA data is the authoritative source for match outcomes—don't guess from telemetry!

**Parameters** ([TOOLS.md](TOOLS.md#get_tba_status))

None.

**Example: Configuration**

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
  "status": "ok",
  "available": false,
  "configuration": "not_configured",
  "hint": "In VS Code, run 'WPILog Analyzer: Set The Blue Alliance API Key'; for the standalone server, set tba_key in ~/.wpilog-mcp/servers.yaml (or pass -tba-key, or set TBA_API_KEY). Get a free API key at https://www.thebluealliance.com/account"
}
```

### `get_tba_match_data`

Query match scores and detailed results from The Blue Alliance. Use this to answer questions like 'What was our score?', 'Did we win?', 'How many autonomous points did we score?', or 'What were the match results?'. Returns alliance scores, win/loss status, and detailed score breakdown including autonomous points when available. match_type 'Elimination' N, as the Driver Station names playoff matches, is read as double-elimination bracket match N (TBA's sfNm1) for 2023 and later; the finals carry no bracket number, so query them as match_type 'f' with the finals match number. Before 2023, with team_number, Elimination N is read as playoff match number N in the order the team played (a heuristic). The result's lookup_method (direct, double_elimination_bracket, play_order) and match_key say what was looked up. IMPORTANT: This is the primary tool for getting match outcome data—don't guess or infer match results from telemetry when you can query TBA directly.

**Parameters** ([TOOLS.md](TOOLS.md#get_tba_match_data))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `year` | integer | yes | Competition year (e.g., 2024) |
| `event_code` | string | yes | TBA event code — this is NOT the abbreviation from log filenames. Find the correct code at thebluealliance.com/events/{year}. Examples: 'caph' (Poway), 'cmptx' (Houston Championship) |
| `match_type` | string | yes | Match type: 'Qualification', 'Quarterfinal', 'Semifinal', 'Final', or 'Elimination', or TBA's codes 'qm' (or 'q'), 'qf', 'sf', 'f' |
| `match_number` | integer | yes | Match number within the type |
| `team_number` | integer | no | Optional: Your team number to highlight your alliance's data |

**Example: Without a TBA key**

Request:
```json
{
  "name": "get_tba_match_data",
  "arguments": {
    "year": 2026,
    "event_code": "2026vache",
    "match_type": "qm",
    "match_number": 10
  }
}
```

Response:
```json
{
  "success": false,
  "status": "error",
  "error": "TBA API not configured. In VS Code, run 'WPILog Analyzer: Set The Blue Alliance API Key'; for the standalone server, set tba_key in ~/.wpilog-mcp/servers.yaml (or pass -tba-key, or set TBA_API_KEY). Get a free API key at https://www.thebluealliance.com/account"
}
```

## RevLog Tools

### `list_revlog_signals`

List all available signals from synchronized REV log files. REV logs contain CAN bus data from SPARK MAX/Flex motor controllers (firmware 25+ status frames, decoded per REV's published specification): AppliedOutput (duty cycle), BusVoltage, OutputCurrent, MotorTemperature, limit and IsInverted flags; faults, warnings, and their sticky versions as 0/1 signals (e.g. BrownoutWarning, StallStickyWarning); Velocity and Position (RPM and rotations unless a conversion factor is configured on the SPARK: compare with the robot code's own entries before assuming a unit); and other sensors when their frames were logged. Signals are automatically synchronized with wpilog timestamps when loaded; each carries sync_method (CROSS_CORRELATION, SYSTEM_TIME_ONLY, USER_PROVIDED, or FAILED), timestamps_aligned, offset_seconds, and sync_confidence, and a warning says how a bus was aligned when that bounds its accuracy. Returns not_applicable when no REV log could be synchronized (with the buses, for set_revlog_offset) and no_match when the filters match no signal.

**Parameters** ([TOOLS.md](TOOLS.md#list_revlog_signals))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `device_filter` | string | no | Filter signals by device key (e.g., 'SparkMax_1') |
| `signal_filter` | string | no | Filter signals by signal name substring (e.g., 'velocity') |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Signals**

Request:
```json
{
  "name": "list_revlog_signals",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "signal_filter": "Velocity"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "signal_count": 4,
  "signals": [
    {
      "key": "REV/SparkMax_12/Velocity",
      "device": "SparkMax_12",
      "signal": "Velocity",
      "unit": "rpm unless converted",
      "sample_count": 16959,
      "can_bus": "rio",
      "sync_method": "CROSS_CORRELATION",
      "timestamps_aligned": true,
      "offset_seconds": -0.016616,
      "sync_confidence": "medium"
    },
    {
      "key": "REV/SparkFlex_16/Velocity",
      "device": "SparkFlex_16",
      "signal": "Velocity",
      "unit": "rpm unless converted",
      "sample_count": 16960,
      "can_bus": "rio",
      "sync_method": "CROSS_CORRELATION",
      "timestamps_aligned": true,
      "offset_seconds": -0.016616,
      "sync_confidence": "medium"
    },
    {
      "key": "REV/SparkFlex_17/Velocity",
      "device": "SparkFlex_17",
      "signal": "Velocity",
      "unit": "rpm unless converted",
      "sample_count": 16960,
      "can_bus": "rio",
      "sync_method": "CROSS_CORRELATION",
      "timestamps_aligned": true,
      "offset_seconds": -0.016616,
      "sync_confidence": "medium"
    },
    {
      "key": "REV/SparkMax_13/Velocity",
      "device": "SparkMax_13",
      "signal": "Velocity",
      "unit": "rpm unless converted",
      "sample_count": 16960,
      "can_bus": "rio",
      "sync_method": "CROSS_CORRELATION",
      "timestamps_aligned": true,
      "offset_seconds": -0.016616,
      "sync_confidence": "medium"
    }
  ],
  "revlog_count": 1,
  "overall_sync_confidence": "medium",
  "warnings": [
    "REV log 'rio': timestamps aligned by cross-correlation at medium confidence (accuracy about 5-50 ms); sync_status has the signal pairs."
  ],
  "_metadata": {
    "timing_accuracy_ms": "5-50",
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

### `get_revlog_data`

Get data from a REV log signal with timestamps converted to FPGA time. Use list_revlog_signals first to discover available signal keys. sync_method, offset_seconds, and sync_confidence say how the timestamps were aligned (a cross-correlation of signals both logs record, the wall-clock estimate alone, or a user-provided offset, whose accuracy the server cannot judge: timing_accuracy_ms is then unknown), and a warning says so when the method bounds the accuracy. A signal whose REV log could not be synchronized returns not_applicable until set_revlog_offset provides an offset: its timestamps are on the REV log's own clock.

**Parameters** ([TOOLS.md](TOOLS.md#get_revlog_data))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `signal_key` | string | yes | Signal key (e.g., 'REV/SparkMax_1/AppliedOutput' or 'REV/rio/SparkMax_1/Velocity') |
| `start_time` | number | no | Start timestamp in seconds (FPGA time) |
| `end_time` | number | no | End timestamp in seconds (FPGA time) |
| `limit` | integer | no | Maximum number of samples to return |
| `include_stats` | boolean | no | Include basic statistics (min, max, mean) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: One signal**

Request:
```json
{
  "name": "get_revlog_data",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "signal_key": "REV/SparkMax_12/Velocity",
    "limit": 3,
    "include_stats": true
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "signal_key": "REV/SparkMax_12/Velocity",
  "can_bus": "rio",
  "sample_count": 3,
  "total_samples": 16959,
  "data": [
    {
      "timestamp": 11.918384,
      "value": 0.0
    },
    {
      "timestamp": 11.938384,
      "value": 0.0
    },
    {
      "timestamp": 11.958383999999999,
      "value": 0.0
    }
  ],
  "limits": {
    "data": {
      "total": 16959,
      "returned": 3,
      "limit": 3
    }
  },
  "sync_method": "CROSS_CORRELATION",
  "timestamps_aligned": true,
  "offset_seconds": -0.016616,
  "sync_confidence": "medium",
  "statistics": {
    "min": -10.937395095825195,
    "max": 10.881987571716309,
    "mean": 0.0015677934587479134,
    "count": 16959
  },
  "data_quality": {
    "sample_count": 16959,
    "time_span_seconds": 339.16,
    "sampling": "periodic",
    "gap_count": 0,
    "effective_sample_rate_hz": 50.0,
    "quality_score": 1.0
  },
  "server_analysis_directives": {
    "confidence_level": "high",
    "sample_context": "Based on 16959 samples over 339.2 seconds",
    "interpretation_guidance": [
      "This analysis is based on a single log. Patterns should be confirmed across multiple matches before drawing conclusions.",
      "Revlog timestamps were aligned by cross-correlation of matching signals (confidence: medium, accuracy: 5-50 ms); sync_status has the details."
    ]
  },
  "warnings": [
    "REV log 'rio': timestamps aligned by cross-correlation at medium confidence (accuracy about 5-50 ms); sync_status has the signal pairs."
  ],
  "_metadata": {
    "timing_accuracy_ms": "5-50",
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

### `sync_status`

Get detailed synchronization status for all synchronized REV log files. Shows confidence levels, timing offsets, and the signal pairs used for synchronization. Use this to understand the accuracy of REV log timestamps. revlog_filename_zone says how REV log filename times were read to find the REV logs and estimate the coarse offset: in the zone the wpilog's own filename shows against its wall clock, else UTC (the roboRIO's default). A REV log named by another clock (the REV Hardware Client uses the laptop's local time) may be missed or mis-aligned; set_revlog_offset corrects an offset.

**Parameters** ([TOOLS.md](TOOLS.md#sync_status))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `include_signal_pairs` | boolean | no | Include details about which signal pairs were used for correlation |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Revlog sync**

Request:
```json
{
  "name": "sync_status",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "synchronized": true,
  "revlog_count": 1,
  "sync_in_progress": false,
  "overall_confidence": "medium",
  "overall_confidence_value": 0.67,
  "revlog_filename_zone": "UTC, the zone the wpilog's own filename time shows against its wall clock (the same clock is taken to have named the REV log)",
  "revlogs": [
    {
      "can_bus": "rio",
      "path": "<logdir>/vache/REV_20260321_162932.revlog",
      "device_count": 4,
      "signal_count": 184,
      "sync": {
        "method": "CROSS_CORRELATION",
        "confidence": 0.84,
        "confidence_level": "medium",
        "offset_microseconds": -16616,
        "offset_milliseconds": -16.616,
        "offset_seconds": -0.016616,
        "explanation": "Synchronized using 5 signal pair(s). Offset: -16.6ms. The pairs' offsets range from -17.8 to 8.6 ms (standard deviation 16.2 ms). Medium confidence - reasonable signal agreement.",
        "successful": true
      }
    }
  ],
  "warnings": [
    "Medium synchronization confidence. Timestamps are approximate (accuracy: ~5-50ms)."
  ],
  "_metadata": {
    "timing_accuracy_ms": "5-50",
    "confidence_description": "Some signals correlate well, minor disagreement",
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "entries_read": [
      "/SystemStats/EpochTimeMicros"
    ]
  }
}
```

### `set_revlog_offset`

Manually set the synchronization offset for a REV log file. Use this when automatic synchronization fails or when you know the exact offset between revlog and wpilog timestamps. The offset is added to revlog timestamps to convert them to FPGA time. Example: if a revlog event appears 0.5s after the same event in wpilog, set offset_ms to -500. offset_ms is required: omitting it is an error, not an offset of zero.

**Parameters** ([TOOLS.md](TOOLS.md#set_revlog_offset))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `offset_ms` | number | yes | Time offset in milliseconds to add to revlog timestamps to get FPGA time |
| `can_bus` | string | no | CAN bus name to apply offset to (e.g., 'rio'). If omitted, applies to the first/only revlog. |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Manual offset**

Request:
```json
{
  "name": "set_revlog_offset",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "offset_ms": 0
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "can_bus": "rio",
  "offset_ms": 0.0,
  "offset_us": 0,
  "previous_offset_ms": -16.616,
  "previous_method": "CROSS_CORRELATION",
  "new_method": "USER_PROVIDED",
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

### `wait_for_sync`

Wait for background RevLog synchronization to complete. Call this before querying revlog data if synchronization may still be in progress. Returns instantly if sync is already done; returns not_applicable when this wpilog has no revlogs. timeout_ms is capped at 120000.

**Parameters** ([TOOLS.md](TOOLS.md#wait_for_sync))

| Parameter | Type | Required | Description |
|---|---|---|---|
| `timeout_ms` | integer | no | Maximum time to wait in milliseconds (default: 30000, max: 120000) |
| `path` | string | yes | Path to the log file (from list_available_logs) |

**Example: Wait for sync**

Request:
```json
{
  "name": "wait_for_sync",
  "arguments": {
    "path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog",
    "timeout_ms": 30000
  }
}
```

Response:
```json
{
  "success": true,
  "status": "ok",
  "completed": true,
  "was_in_progress": false,
  "revlog_count": 1,
  "synchronized": true,
  "_metadata": {
    "log_truncation": "Log file is truncated or damaged: the file ends inside a record at byte 34996216; the rest of the file was not read. Data from 11.90 to 347.90 s was recovered."
  },
  "inputs": {
    "log": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog"
  }
}
```

