# wpilog-mcp Tool Reference

Complete documentation for all tools available in wpilog-mcp.

## Table of Contents

- [Discovery Tools](#discovery-tools)
  - [get_server_guide](#get_server_guide)
  - [suggest_tools](#suggest_tools)
- [Core Tools](#core-tools)
  - [list_available_logs](#list_available_logs)
  - [list_loaded_logs](#list_loaded_logs)
  - [list_entries](#list_entries)
  - [get_entry_info](#get_entry_info)
  - [read_entry](#read_entry)
  - [list_struct_types](#list_struct_types)
  - [health_check](#health_check)
- [Query Tools](#query-tools)
  - [search_entries](#search_entries)
  - [get_types](#get_types)
  - [find_condition](#find_condition)
  - [search_strings](#search_strings)
- [Statistics Tools](#statistics-tools)
  - [get_statistics](#get_statistics)
  - [compare_entries](#compare_entries)
  - [detect_anomalies](#detect_anomalies)
  - [find_peaks](#find_peaks)
  - [rate_of_change](#rate_of_change)
  - [time_correlate](#time_correlate)
- [Robot Analysis Tools](#robot-analysis-tools)
  - [get_match_phases](#get_match_phases)
  - [analyze_swerve](#analyze_swerve)
  - [power_analysis](#power_analysis)
  - [can_health](#can_health)
  - [compare_matches](#compare_matches)
  - [get_code_metadata](#get_code_metadata)
  - [moi_regression](#moi_regression)
  - [analyze_can_bus](#analyze_can_bus)
- [FRC Domain Tools](#frc-domain-tools)
  - [get_ds_timeline](#get_ds_timeline)
  - [analyze_vision](#analyze_vision)
  - [profile_mechanism](#profile_mechanism)
  - [analyze_auto](#analyze_auto)
  - [analyze_cycles](#analyze_cycles)
  - [analyze_replay_drift](#analyze_replay_drift)
  - [predict_battery_health](#predict_battery_health)
  - [analyze_loop_timing](#analyze_loop_timing)
  - [get_game_info](#get_game_info)
- [Export Tools](#export-tools)
  - [export_csv](#export_csv)
  - [generate_report](#generate_report)
- [TBA Tools](#tba-tools)
  - [get_tba_status](#get_tba_status)
  - [get_tba_match_data](#get_tba_match_data)
- [RevLog Tools](#revlog-tools)
  - [list_revlog_signals](#list_revlog_signals)
  - [get_revlog_data](#get_revlog_data)
  - [sync_status](#sync_status)
  - [set_revlog_offset](#set_revlog_offset)
  - [wait_for_sync](#wait_for_sync)
- [Response Fields](#response-fields)

---

## Discovery Tools

These tools help LLM agents discover and effectively use the server's capabilities. **Call `get_server_guide` first** when starting a new analysis session to understand what tools are available.

### `get_server_guide`
Get a comprehensive overview of all server capabilities, organized by category with usage guidance and anti-patterns to avoid.

**IMPORTANT:** Call this tool first to understand what analysis capabilities are available. This server has many specialized tools--don't write custom analysis code when a built-in tool already exists.

**Parameters:**
- `category` (optional): Filter by category: `core`, `query`, `statistics`, `robot_analysis`, `frc_domain`, `export`, `tba`, `revlog`, `discovery`
- `include_examples` (optional): Include example use cases for each tool (default: true)

**Returns:** Structured overview including:
- `overview`: Server name, version, total tools, purpose
- `critical_guidance`: Key anti-patterns to avoid (e.g., "NEVER compute statistics manually")
- `analysis_principles`: General reasoning guidance for the AI agent (see [Server Instructions](#server-instructions)): the scientific-method loop for causal questions, confidence calibration (what `confidence_level` does and does not bound), a catalogue of confabulation traps with the tool call that avoids each, cross-match rules, entry naming conventions, units, and pit vs. deep-dive report formats
- `categories`: Array of tool categories with descriptions, anti-patterns, and tool details
- `common_workflows`: Step-by-step workflows for common analysis tasks

### `suggest_tools`
Given a natural language description of what you want to analyze, this tool recommends the most relevant tools and provides a suggested workflow.

**Parameters:**
- `task` (required): Natural language description of what you want to analyze (e.g., "check why our auto was inconsistent" or "investigate brownout during teleop")
- `max_suggestions` (optional): Maximum number of tools to suggest (default: 5)

**Returns:**
- `suggestions`: Array of recommended tools with relevance scores and example uses
- `suggested_workflow`: Step-by-step workflow for the task
- `anti_patterns`: Common mistakes to avoid for this type of analysis

**Example Request:**
```json
{
  "task": "Why did we brownout during teleop?"
}
```

**Example Response:**
```json
{
  "success": true,
  "task": "why did we brownout during teleop?",
  "suggestions": [
    {
      "tool": "power_analysis",
      "description": "Analyze battery voltage and current distribution",
      "relevance_score": 8,
      "category": "robot_analysis"
    },
    {
      "tool": "find_condition",
      "description": "Find timestamps where values cross thresholds",
      "relevance_score": 4
    }
  ],
  "suggested_workflow": [
    "1. list_available_logs - Find available logs",
    "2. power_analysis - Check for brownouts and current peaks",
    "3. find_condition - Find exact timestamps of voltage drops"
  ],
  "anti_patterns": [
    "Don't manually check voltage thresholds—use power_analysis"
  ]
}
```

---

## Core Tools

### `list_available_logs`
List WPILOG files in the configured log directory with user-friendly names.

**Parameters:** None

**Returns:** List of available logs with friendly names, event info, file details, and optional TBA enrichment

**Example Response:**
```json
{
  "success": true,
  "log_directory": "/Users/team2363/Documents/FRC/logs",
  "log_count": 3,
  "tba_enrichment": true,
  "metadata_cache": {
    "size": 3,
    "hits": 2,
    "misses": 1
  },
  "logs": [
    {
      "friendly_name": "VADC Qualification 42",
      "path": "/Users/team2363/Documents/FRC/logs/2024vadc_qm42.wpilog",
      "filename": "2024vadc_qm42.wpilog",
      "event": "VADC",
      "match_type": "Qualification",
      "match_number": 42,
      "team_number": 2363,
      "size_bytes": 15234567,
      "last_modified": 1710523456000,
      "tba": {
        "team_number": 2363,
        "alliance": "red",
        "score": 85,
        "won": true,
        "opponent_score": 72,
        "actual_time": 1710432000,
        "scheduled_time": 1710431700
      }
    },
    {
      "friendly_name": "VADC Practice 3",
      "path": "/Users/team2363/Documents/FRC/logs/2024vadc_p3.wpilog",
      "filename": "2024vadc_p3.wpilog",
      "event": "VADC",
      "match_type": "Practice",
      "match_number": 3,
      "team_number": 2363,
      "size_bytes": 8765432,
      "last_modified": 1710512345000
    }
  ]
}
```

**Response Fields:**
- `tba_enrichment`: Present and `true` when TBA API is configured
- `metadata_cache`: Cache statistics for log file metadata (size, hits, misses)
- `team_number`: Team number extracted from DriverStation/FMS metadata in the log
- `tba`: TBA enrichment data (only present for qualifying competition matches when TBA is configured)

**Note:** Requires `-logdir` to be configured. Team numbers and friendly names are extracted from DriverStation metadata in the log file, or parsed from common filename patterns.

### `list_loaded_logs`
List all currently cached log files.

**Parameters:** None

**Returns:** List of loaded log paths and count

**Example Response:**
```json
{
  "success": true,
  "loaded_count": 2,
  "logs": [
    { "path": "/Users/team2363/logs/2026vadc_qm42.wpilog" },
    { "path": "/Users/team2363/logs/2026vadc_qm68.wpilog" }
  ]
}
```

**Use Case:** Check which logs are currently cached. Idle logs are automatically evicted after 30 minutes.

**Supported filename patterns:**
- Match types: `qm`/`q` (Qualification), `pm`/`p` (Practice), `sf` (Semifinal), `f` (Final), `em`/`e` (Elimination)
- Modes: `sim`/`simulation` (Simulation), `replay` (Replay)
- Event codes: 2-6 letter codes like `VADC`, `DCMP`, `CMPTX`
- Examples:
  - `2024vadc_qm42.wpilog` → "VADC Qualification 42"
  - `sim_test.wpilog` → "Simulation"
  - `replay_2024vadc_qm42.wpilog` → "VADC Qualification 42 Replay"
  - `2024dcmp_f1_sim.wpilog` → "DCMP Final 1 Simulation"

### `list_entries`
List all entries in the specified log file.

**Parameters:**
- `path` (required): Path to the log file
- `pattern` (optional): Filter entries by name pattern (substring match)

**Returns:** List of entries with name, type, and sample count

### `get_entry_info`
Get detailed information about a specific entry.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): The entry name (e.g., `/Drive/Odometry/Pose`)

**Returns:** Entry metadata, sample count, time range, and sample values

### `read_entry`
Read values from an entry with time range filtering and pagination.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): The entry name
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `limit` (optional): Max samples to return (default 100)
- `offset` (optional): Samples to skip (default 0)

**Returns:** Array of timestamped values

**Example Response (Pose2d):**
```json
{
  "success": true,
  "name": "/Drive/Odometry/Pose",
  "type": "struct:Pose2d",
  "samples": [
    {
      "timestamp_sec": 0.02,
      "value": {
        "x": 1.54,
        "y": 5.55,
        "rotation_rad": 0.0,
        "rotation_deg": 0.0
      }
    }
  ]
}
```

### `list_struct_types`
List all supported WPILib struct types organized by category. Useful for discovering what struct types can be decoded and what fields they contain.

**Parameters:** None

**Returns:** Struct types organized into categories (geometry, kinematics, vision)

**Example Response:**
```json
{
  "success": true,
  "struct_types": {
    "geometry": [
      "Pose2d", "Pose3d", "Translation2d", "Translation3d",
      "Rotation2d", "Rotation3d", "Transform2d", "Transform3d",
      "Twist2d", "Twist3d"
    ],
    "kinematics": [
      "ChassisSpeeds", "SwerveModuleState", "SwerveModulePosition"
    ],
    "vision": [
      "TargetObservation", "PoseObservation", "SwerveSample"
    ]
  }
}
```

**Use Case:** When exploring a new log file, use this tool to see what struct types are available for decoding. Each struct type is automatically decoded into its component fields when read.

### `health_check`
Get system health status including JVM memory usage, loaded log count, disk cache status, and TBA availability. Useful for monitoring server performance and resource usage.

**Parameters:** None

**Returns:** System status, JVM memory info, loaded log count, disk cache status, and TBA availability

**Use Case:** Use this tool periodically during long analysis sessions to monitor memory usage. Idle logs are automatically evicted after 30 minutes.

---

## Query Tools

Search and filter log data. Find specific entries, types, and events.

### `search_entries`
Search for entries matching criteria.

**Parameters:**
- `path` (required): Path to the log file
- `type` (optional): Filter by type (substring match, e.g., `Pose3d`, `double`)
- `pattern` (optional): Filter by name containing this string
- `min_samples` (optional): Minimum number of samples required

**Returns:** Matching entries sorted alphabetically

### `get_types`
Get all data types used in the log file.

**Parameters:**
- `path` (required): Path to the log file

**Returns:** Types with entry counts and entry names

### `find_condition`
Find timestamps where a numeric entry crosses a threshold. Useful for questions like "When did battery voltage drop below 11V?"

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name (e.g., `/Robot/BatteryVoltage`)
- `operator` (required): Comparison operator: `lt` (<), `lte` (<=), `gt` (>), `gte` (>=), `eq` (==)
- `threshold` (required): Threshold value to compare against
- `limit` (optional): Maximum transitions to return (default 100)

**Returns:** List of timestamps where the condition first becomes true (transitions)

**Example Response:**
```json
{
  "success": true,
  "name": "/Robot/BatteryVoltage",
  "condition": "/Robot/BatteryVoltage < 11.0",
  "transition_count": 3,
  "transitions": [
    {"timestamp_sec": 45.23, "value": 10.89},
    {"timestamp_sec": 89.45, "value": 10.95},
    {"timestamp_sec": 134.12, "value": 10.78}
  ]
}
```

### `search_strings`
List or search the text logged in string entries (console output, alerts, WPILib `messages`), completely and in time order across all entries. This is the tool for "show me every error": nothing is prioritized or silently dropped — results are paged with explicit totals.

**Parameters:**
- `path` (required): Path to the log file
- `pattern` (optional): Case-insensitive substring, or a Java regular expression when `regex` is true. Omit to list every string sample (narrow with `level`, `entry_pattern`, or a time window)
- `regex` (optional): Treat `pattern` as a Java regex, case-insensitive (Unicode-aware) with `^`/`$` anchoring to lines of a multi-line sample; `.` does not cross a line break. Default `false`. An invalid regex returns an error, and a pattern that backtracks for more than about a second (nested quantifiers on long text) is rejected with an error rather than hanging the server
- `level` (optional): `error`, `warning`, or `any` (default). Classification is the same rule `get_ds_timeline` uses for `text_event_counts`, so the numbers agree
- `entry_pattern` (optional): Only search entries whose name contains this substring
- `start_time` / `end_time` (optional): Time window in seconds
- `offset` (optional, default 0) and `limit` (optional, default 100, max 1000): Paging over the time-ordered result; values outside those ranges are clamped and the clamped values are echoed
- `collapse_repeats` (optional, default `false`): Fold runs of identical samples that are **adjacent in the same entry's stream** into one match with `repeat_count` and `last_timestamp_sec`. Any other sample in between — even one the filters exclude — ends the run, so a `repeat_count` never spans a gap
- `max_value_chars` (optional, default 500, minimum 1): Truncate each returned `value`

**Returns:**
- `total_matches` — the full number of matching samples (before paging); with `collapse_repeats`, also `total_after_collapse`
- `offset`, `limit`, `returned` (= `match_count`, kept for compatibility), `has_more` — whether another page exists
- `matches[]` sorted by time across entries (ties by entry declaration order): `{timestamp_sec, entry, level? ("error"/"warning" when classified), line, value, repeat_count?, last_timestamp_sec?}`. `line` is the line containing the pattern match; without a pattern it is the classified line, or the first line for unclassified samples; it is cut at 200 characters (`line_truncated: true`). `value` is the whole sample cut at `max_value_chars` (`value_truncated: true`)
- `pattern` (echoed when given), `regex`, `level`

**Example Response** (`level: "error"`, `limit: 2`):
```json
{
  "success": true,
  "regex": false,
  "level": "error",
  "total_matches": 4,
  "offset": 0,
  "limit": 2,
  "returned": 2,
  "match_count": 2,
  "has_more": true,
  "matches": [
    {
      "timestamp_sec": 123.71,
      "entry": "/RealOutputs/Console",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:88): ...",
      "value": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:88): ..."
    },
    {
      "timestamp_sec": 124.74,
      "entry": "/RealOutputs/Console",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:88): ...",
      "value": "..."
    }
  ]
}
```

### `get_statistics`
Get statistics for a numeric entry. Supports optional time range filtering. Includes data quality metrics and analysis directives for confidence assessment.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): The entry name
- `start_time` (number, optional): Start timestamp in seconds
- `end_time` (number, optional): End timestamp in seconds

**Returns:** Statistics including count, min, max, mean, median, std_dev, quartiles, and percentiles

**Example Response:**
```json
{
  "success": true,
  "name": "/Robot/BatteryVoltage",
  "count": 7716,
  "min": 11.23,
  "max": 12.89,
  "mean": 12.34,
  "median": 12.41,
  "std_dev": 0.28,
  "q1": 12.1,
  "q3": 12.6,
  "iqr": 0.5,
  "p5": 11.5,
  "p95": 12.8,
  "data_quality": { "sample_count": 7716, "quality_score": 0.95 },
  "server_analysis_directives": { "confidence": "high" }
}
```

### `compare_entries`
Compare two entries (useful for RealOutputs vs ReplayOutputs).

**Parameters:**
- `path` (required): Path to the log file
- `name1` (required): First entry name
- `name2` (required): Second entry name

**Returns:** RMSE (root mean square error) and max difference between the two entries, with data quality and analysis directives

### `detect_anomalies`
Detect anomalies (outliers) in numeric data using the IQR (Interquartile Range) method with proper linear percentile interpolation. Values outside Q1 - 1.5xIQR or Q3 + 1.5xIQR are flagged as outliers. Optionally detects sudden spikes (large percentage changes between consecutive samples).

**Note:** IQR calculation uses linear interpolation between data points for accurate percentile estimates, ensuring reliable outlier detection even with small datasets.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name to analyze (must be numeric type)
- `iqr_multiplier` (optional): Multiplier for IQR bounds (default 1.5). Use 3.0 for extreme outliers only
- `spike_threshold` (optional): Detect spikes larger than this percentage change (e.g., 50 for 50% change)
- `limit` (optional): Maximum anomalies to return (default 50)

**Returns:** Anomaly count, non-finite count, and list of anomalies with timestamps, values, and type

**Example Response:**
```json
{
  "success": true,
  "anomaly_count": 3,
  "non_finite_count": 0,
  "anomalies": [
    {
      "timestamp_sec": 45.23,
      "value": 10.5,
      "type": "below_lower_bound"
    },
    {
      "timestamp_sec": 89.1,
      "value": 9.8,
      "type": "below_lower_bound"
    }
  ],
  "data_quality": { "sample_count": 7716, "quality_score": 0.95 },
  "server_analysis_directives": { "confidence": "high" }
}
```

### `find_peaks`
Find local maxima and minima (peaks and valleys) in numeric data. Uses a simple algorithm that compares each point to its immediate neighbors. Peaks are sorted by height difference (how much they stand out from neighboring values).

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name to analyze (must be numeric type)
- `type` (optional): Type of peaks to find: `max` (maxima only), `min` (minima only), or `both` (default)
- `min_height_diff` (optional): Minimum height difference from neighbors to count as a peak. Filters out noise
- `limit` (optional): Maximum peaks to return per type (default 20)

**Returns:** Lists of maxima and/or minima with height difference from neighbors

**Example Response:**
```json
{
  "success": true,
  "maxima": [
    {
      "timestamp_sec": 2.34,
      "value": 12.89,
      "height_diff": 0.45
    }
  ],
  "minima": [
    {
      "timestamp_sec": 89.45,
      "value": 10.23,
      "height_diff": 1.2
    }
  ],
  "data_quality": { "sample_count": 7716, "quality_score": 0.95 },
  "server_analysis_directives": { "confidence": "high" }
}
```

### `rate_of_change`
Compute rate of change (derivative) of numeric data over time. Calculates dv/dt for each sample. Useful for computing velocity from position, acceleration from velocity, or detecting rapid changes in any value.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name to analyze (must be numeric type)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `window_size` (optional): Number of samples to average for smoothing (default 1 = no smoothing). Higher values reduce noise but may miss short events
- `limit` (optional): Maximum samples to return (default 100)

**Returns:** Derivative values with timestamp and statistics

**Example Response:**
```json
{
  "success": true,
  "statistics": {
    "avg_rate": 0.02
  },
  "samples": [
    {
      "timestamp_sec": 0.04,
      "rate": 25.0
    }
  ],
  "data_quality": { "sample_count": 7716, "quality_score": 0.95 },
  "server_analysis_directives": { "confidence": "high" }
}
```

### `time_correlate`
Compute Pearson correlation coefficient between two numeric entries. Aligns samples by timestamp using linear interpolation and calculates correlation with statistical significance (p-value). Values range from -1 (perfect negative correlation) to +1 (perfect positive correlation).

**Interpretation:**
- |r| >= 0.9: Very strong correlation
- |r| >= 0.7: Strong correlation
- |r| >= 0.5: Moderate correlation
- |r| >= 0.3: Weak correlation
- |r| < 0.3: No significant correlation

**Parameters:**
- `path` (required): Path to the log file
- `name1` (required): First entry name (must be numeric)
- `name2` (required): Second entry name (must be numeric)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds

**Returns:** Correlation coefficient, sample count, and p-value. When either entry is constant over the window (near-zero variance), correlation is undefined: `correlation` is `null`, `p_value` is 1, and a warning names the constant entry.

**Example Response:**
```json
{
  "success": true,
  "sample_count": 5432,
  "correlation": -0.81,
  "p_value": 0.0001,
  "data_quality": { "sample_count": 7716, "quality_score": 0.95 },
  "server_analysis_directives": { "confidence": "high" }
}
```

**Common correlations in FRC:**
- Battery voltage vs motor current: Strong negative (voltage drops as current increases)
- Drive velocity vs motor power: Strong positive
- Arm position vs arm motor current: Variable (depends on mechanism)

---

## Robot Analysis Tools

Robot-specific analysis: power, swerve, CAN health, match phases.

### `get_match_phases`
Find when the robot was enabled, in which mode, and — when the log holds a match — its autonomous, teleop, and endgame phases. Everything is derived from the log's DriverStation state entries; nothing is assumed about the log being a match.

**How it works:**
- Reads one DriverStation entry per role, by leaf name: `Enabled`, `Autonomous`, `Test`, `FMSAttached` under AdvantageKit `/DriverStation/` or WPILib DataLogManager `DS:` (AdvantageKit wins when both exist, then the lowest entry id; the others are named in `notes`). Logs with only NetworkTables data use the `FMSInfo/FMSControlData` control word.
- Values are logged only on change, so each holds until the next sample (sample-and-hold). A single `Autonomous=false` sample means the robot was never in autonomous.
- `segments` tiles the whole log: every interval of constant state (`enabled`, `disabled`, or `unknown` before the first DriverStation sample), with `mode` (`auto`/`teleop`/`test`/`unknown`) while enabled and `end_reason` (`disabled`, `enabled`, `mode_change`, `ds_data`, `log_end`). A disable logged at the log's last timestamp ends the final segment with `disabled`; `log_end` means the robot was still in that state when the log stopped.
- `matches` lists each FMS match found: an enabled autonomous segment followed within the season's auto-to-teleop delay (plus 5 s) by an enabled teleop segment, with `basis` `fms_attached` (FMS attached at the start) or `mode_sequence` (no FMS, but the autonomous segment lasted 50–150 % of the season's autonomous time). A teleop segment of about the season's teleop length with FMS attached is a match even if the robot was disabled through autonomous. `complete` says whether teleop ended in a disable at about the season's teleop length; `endgame` is derived from the season's timing (`basis: game_timing`) only for complete matches. `expected_timing` gives the season values used.
- `phases` repeats the first match (compatibility). With no match and exactly one enabled segment, it holds that segment as `enabled`; with several enabled segments it is empty — use `segments`.
- `season` is the year the log was recorded, from the log's own clock (`/SystemStats/EpochTimeMicros` or `systemTime`), then `/RealMetadata/BuildDate`, then a year in the file name, then the current year; `basis` says which.

**Parameters:**
- `path` (required): Path to the log file

**Returns:** `segments`, `enabled_segment_count`, `enabled_time_sec`, `matches`, `phases`, `match_duration`/`auto_duration`/`teleop_duration` (first match), `season`, `log_start`/`log_end`/`log_duration`, `inputs.entries` (the DriverStation entries used), `source` (`"DriverStation"`), `notes`, and `warnings` (never enabled; log ends while enabled; incomplete match).

**Status:** `no_match` (with `looked_for` and `hint`) when the log has no DriverStation state entries.

**Example Response (practice session, abridged):**
```json
{
  "success": true,
  "status": "ok",
  "source": "DriverStation",
  "log_start": 8.359,
  "log_end": 1588.273,
  "season": {"year": 2026, "basis": "log_clock:/SystemStats/EpochTimeMicros"},
  "inputs": {"entries": {"enabled": "/DriverStation/Enabled", "autonomous": "/DriverStation/Autonomous",
    "test": "/DriverStation/Test", "fms_attached": "/DriverStation/FMSAttached"}},
  "segments": [
    {"start": 8.359, "end": 40.207, "duration": 31.848, "state": "disabled", "end_reason": "enabled"},
    {"start": 40.207, "end": 359.162, "duration": 318.955, "state": "enabled", "mode": "teleop", "end_reason": "disabled"},
    "...",
    {"start": 995.211, "end": 1588.273, "duration": 593.062, "state": "enabled", "mode": "teleop", "end_reason": "log_end"}
  ],
  "enabled_segment_count": 4,
  "enabled_time_sec": 1311.36,
  "matches": [],
  "phases": {},
  "notes": [
    "Autonomous was never true: /DriverStation/Autonomous has 1 sample(s), all false. ...",
    "No FMS match pattern: ... Use segments for time windows (phases is empty because there are 4 enabled segments)."
  ],
  "warnings": ["The log ends while the robot is enabled (last segment end_reason log_end at 1588.27 s): ..."]
}
```

**Example `matches` entry (FMS match):**
```json
{
  "autonomous": {"start": 20.0, "end": 40.0, "duration": 20.0},
  "teleop": {"start": 43.0, "end": 183.0, "duration": 140.0},
  "endgame": {"start": 153.0, "end": 183.0, "duration": 30.0, "basis": "game_timing"},
  "basis": "fms_attached",
  "complete": true,
  "expected_timing": {"season": 2026, "auto_sec": 20.0, "teleop_sec": 140.0, "source": "game_data"}
}
```

### `analyze_swerve`
Analyze swerve drive module performance. Searches for entries containing SwerveModuleState, SwerveModulePosition, ChassisSpeeds, or entries with "swerve"/"module" in the name. Reports statistics for each module found.

**Parameters:**
- `path` (required): Path to the log file
- `module_prefix` (optional): Entry path prefix for swerve modules (e.g., `/Drive/Module` or `/Swerve`). If omitted, searches all entries
- `slip_threshold` (number, optional): Speed difference threshold for slip detection in m/s (default: 0.5)
- `sync_threshold_rad` (number, optional): Angle threshold for sync deviation in radians (default: 0.1)
- `odometry_entry` (string, optional): Explicit odometry pose entry name for drift analysis
- `vision_entry` (string, optional): Explicit vision pose entry name for drift analysis

**Returns:** Lists of found swerve-related entries, per-module statistics (max/avg speed), wheel slip analysis, module sync analysis, and odometry drift analysis

**Example Response:**
```json
{
  "success": true,
  "swerve_entries": {
    "module_states": [
      "/Drive/Module0/State",
      "/Drive/Module1/State",
      "/Drive/Module2/State",
      "/Drive/Module3/State"
    ],
    "module_positions": [
      "/Drive/Module0/Position",
      "/Drive/Module1/Position"
    ],
    "chassis_speeds": ["/Drive/ChassisSpeeds"],
    "other_swerve": ["/Drive/SwerveSetpoint"]
  },
  "module_analysis": [
    {
      "entry": "/Drive/Module0/State",
      "max_speed_mps": 4.2,
      "avg_speed_mps": 1.8,
      "sample_count": 7500
    }
  ],
  "hint": "Use get_statistics or find_peaks on specific entries for detailed analysis"
}
```

### `power_analysis`
Analyze battery and current distribution data. Reports battery voltage statistics and brownout risk, plus the peak current for every amperage entry in the log, sorted by peak magnitude. Per-channel arrays such as AdvantageKit's `/PowerDistribution/ChannelCurrent` are expanded per channel index, so PDH/PDP channel peaks are reported even though the statistics tools cannot read array entries.

**Voltage entry selection** (shared with `get_ds_timeline`): candidates are scalar numeric entries (`double`, `float`, `int64`) whose name contains "voltage" and that have at least one finite sample. Battery-named entries (`BatteryVoltage`) are preferred, then input/bus voltage, then any other voltage entry (e.g. WPILib `NT:/SmartDashboard/PowerDistribution[1]/Voltage` or AdvantageKit `/RealOutputs/PDH/Voltage`); rail, regulator, and motor-output voltages (`5vRail`, `3v3`, `AppliedVoltage`, ... — judged on the last two path segments, so `/RealOutputs/` does not count as an "output") are used only as a last resort. Ties are broken by WPILOG declaration order. Pass `power_prefix` to force a specific subtree.

**Current entry selection:** an entry counts as amperage when its name ends in `Amps`/`Amperes` at a token boundary (`CurrentAmps`, `StatorAmps`, `stator_amps` — but not `OdometryTimestamps` or `SlewRamps`), when the text after the last `Current` is empty or a unit/plural/draw suffix (`OutputCurrent`, `Current_A`, `CurrentDraw`, `Currents`, `Current(A)`), when it is `Current/<sub-path>` that is not a non-amperage quantity (`Current/Stator` yes, `Current/Setpoint` no), or when it is a WPILib PowerDistribution sendable channel (`PowerDistribution[<id>]/Chan<N>`). Names such as `Current Angle Degrees`, `CurrentLimit`, or `CurrentState` are excluded; anything containing "voltage" is excluded. `power_prefix` narrows the candidates but does not bypass the rule.

**Brownout Risk Levels:**
- **HIGH**: Voltage dropped below brownout threshold
- **MODERATE**: Voltage within 1V of threshold
- **LOW**: Voltage stayed above threshold + 1V

**Parameters:**
- `path` (required): Path to the log file
- `power_prefix` (optional): Entry path prefix for power data (e.g., `/PDP`, `/PDH`, `/PowerDistribution`)
- `brownout_threshold` (optional): Voltage threshold for brownout warning (default 6.8V for roboRIO 1; set to 6.3V for roboRIO 2)
- `channel_limit` (optional): Maximum number of current entries/channels to return, sorted by peak (default: 30; values below 1 are treated as 1)

**Returns:**
- `voltage_analysis`: `{entry, min_voltage, max_voltage, avg_voltage, samples_below_threshold, brownout_threshold, brownout_risk}` — statistics and the below-threshold count use finite samples only; absent when no usable voltage entry exists (a warning says why)
- `current_entries_analyzed`: number of current entries/channels found (always present; 0 when none)
- `channel_analysis`: present when at least one current entry exists; sorted by `|peak_current_A|` descending: `{entry, peak_current_A, peak_current_time_sec, max_current_A, min_current_A, avg_current_A, sample_count}`. `peak_current_A` is the sample with the largest magnitude, signed (a −150 A stall on a direction-signed torque current is reported as −150); `max_current_A`/`min_current_A` are the signed extremes. Entries expanded from an array (`double[]`, `float[]`, `int64[]`) add `source_entry` and `channel` (the index) and are named `<entry>[<index>]`; ragged arrays yield per-channel sample counts. Non-finite samples are ignored.
- `warnings`: when no usable voltage entry exists (distinguishing "no voltage-named entry" from "voltage entries exist but none has finite scalar samples"), when no current entries are found, or when the list was truncated by `channel_limit`
- `data_quality` / `server_analysis_directives`: computed from the voltage entry, or from the first scalar current entry (declaration order) when there is no voltage entry; absent for array-only logs

**Example Response** (captured from a real AdvantageKit match log; the `channel_analysis` array is trimmed):
```json
{
  "success": true,
  "voltage_analysis": {
    "entry": "/SystemStats/BatteryVoltage",
    "min_voltage": 6.681,
    "max_voltage": 12.844,
    "avg_voltage": 10.647,
    "samples_below_threshold": 1,
    "brownout_threshold": 6.8,
    "brownout_risk": "HIGH"
  },
  "current_entries_analyzed": 71,
  "channel_analysis": [
    {
      "entry": "/RealOutputs/PDH/TotalCurrentAmps",
      "peak_current_A": 226.0,
      "peak_current_time_sec": 137.38,
      "max_current_A": 226.0,
      "min_current_A": 2.0,
      "avg_current_A": 96.217,
      "sample_count": 2077
    },
    {
      "entry": "/Spindexer/CurrentAmps",
      "peak_current_A": 149.304,
      "peak_current_time_sec": 180.102,
      "max_current_A": 149.304,
      "min_current_A": 0.0,
      "avg_current_A": 9.248,
      "sample_count": 2798
    },
    {
      "entry": "/Kicker/CurrentAmps",
      "peak_current_A": 115.055,
      "peak_current_time_sec": 183.31,
      "max_current_A": 115.055,
      "min_current_A": 0.0,
      "avg_current_A": 7.685,
      "sample_count": 3032
    },
    "... (27 more items)"
  ],
  "warnings": [
    "Showing the top 30 of 71 current entries/channels by peak current; raise channel_limit to see more."
  ],
  "data_quality": {
    "...": "..."
  },
  "server_analysis_directives": {
    "...": "..."
  }
}
```

### `can_health`
CAN bus health overview from two sources: console and message text with CAN failures, and the structured bus counters `analyze_can_bus` reads.

**How it works:**
- Text: every line of every string entry is checked. A line is a CAN failure when "CAN" appears as a word (or as CANbus, CANivore, CANcoder — not "cannot", "scan", "Canandgyro", or "cancel") together with timeout, timed out, error, or fault ("default" is not a fault).
- Each line is classified by the robot's state at that moment from the DriverStation timeline `get_match_phases` uses: `while_enabled`, `while_disabled`, or `state_unknown` (before the first DriverStation sample, or no DriverStation data at all).
- Counters: TEC/REC maxima (overall and while enabled) and bus-off increases per bus, from the same analysis as `analyze_can_bus`.

**Health Levels** (disabled-state errors are normal — devices boot and time out — and never count):
- **POOR**: a bus-off count rose while enabled, or 50 or more CAN error lines while enabled
- **CONCERNING**: at least one CAN error line while enabled, or TEC/REC reached 128 (error-passive) while enabled
- **UNKNOWN**: CAN error lines exist but the log has no DriverStation state
- **GOOD**: none of the above

`assessment_basis` states the fact that decided the level.

**Parameters:**
- `path` (required): Path to the log file

**Returns:** `error_counts_by_entry`, `total_can_errors`, `errors_while_enabled`, `errors_while_disabled` (when DriverStation data exists), `errors_state_unknown` (when any), `first_errors_while_enabled` (up to 5 lines with time and entry), `bus_counters[]` (`bus`, `tec_max`, `tec_max_time_sec`, `tec_max_while_enabled`, the same for `rec`, `bus_off_increase`, `bus_off_increase_while_enabled`), `health_assessment`, `assessment_basis`, `inputs.entries`, and warnings.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "error_counts_by_entry": {
    "/RealOutputs/Console": {"total": 3, "while_enabled": 2, "while_disabled": 1}
  },
  "total_can_errors": 3,
  "errors_while_enabled": 2,
  "errors_while_disabled": 1,
  "first_errors_while_enabled": [
    {"timestamp_sec": 88.4, "entry": "/RealOutputs/Console", "line": "CAN frame timeout: device 12"}
  ],
  "bus_counters": [
    {"bus": "CANHD", "tec_max": 215, "tec_max_time_sec": 650.86, "tec_max_while_enabled": 215}
  ],
  "health_assessment": "CONCERNING",
  "assessment_basis": "2 CAN error line(s) while enabled",
  "warnings": ["CAN errors while disabled (1) are normal and excluded from health assessment."]
}
```

### `compare_matches`
Compare statistics for one scalar numeric entry across two log files. Useful as a quick whole-log comparison of robot performance across matches; for phase-scoped comparisons run `get_statistics` with `start_time`/`end_time` on each log instead.

**Parameters:**
- `path` (required): Path to the first log file
- `compare_path` (required): Path to the second log file (must differ from `path`)
- `name` (required): Entry name to compare across logs

**Returns:**
- `entry`, `logs_compared`
- `comparisons[]` — one per log, in argument order: `{log_path, log_filename, entry_found, sample_count, statistics: {min, max, mean}}`. `sample_count` (finite scalar samples) and `statistics` are present only when `entry_found`; `statistics` is also omitted when `sample_count` is 0 (array entries such as `/PowerDistribution/ChannelCurrent` are not compared — use `power_analysis` or `read_entry`)
- `warnings` — when the entry is missing from a log or has no finite scalar values
- `data_quality` / `server_analysis_directives` — computed from the **first** log only

**Example Response:**
```json
{
  "success": true,
  "entry": "/SystemStats/BatteryVoltage",
  "logs_compared": 2,
  "comparisons": [
    {
      "log_path": "/logs/akit_26-03-21_16-29-56_vache_q10.wpilog",
      "log_filename": "akit_26-03-21_16-29-56_vache_q10.wpilog",
      "entry_found": true,
      "sample_count": 11735,
      "statistics": {
        "min": 6.68,
        "max": 12.84,
        "mean": 10.65
      }
    },
    {
      "log_path": "/logs/akit_26-03-21_16-54-48_vache_q13.wpilog",
      "log_filename": "akit_26-03-21_16-54-48_vache_q13.wpilog",
      "entry_found": true,
      "sample_count": 11702,
      "statistics": {
        "min": 6.62,
        "max": 12.58,
        "mean": 10.77
      }
    }
  ],
  "data_quality": { "...": "..." },
  "server_analysis_directives": { "...": "..." }
}
```

### `get_code_metadata`
Extract code metadata from the log. WPILib and AdvantageKit typically log Git information at startup. This tool searches for entries containing GitSHA, GitBranch, GitDirty, BuildDate, RuntimeType, ProjectName, MavenGroup, MavenName, and Version.

**Parameters:**
- `path` (required): Path to the log file

**Returns:** Found metadata values and list of all metadata-related entries

**Example Response:**
```json
{
  "success": true,
  "log_path": "/logs/2024vadc_qm42.wpilog",
  "metadata": {
    "GitSHA": "a1b2c3d4e5f6",
    "GitBranch": "main",
    "GitDirty": false,
    "BuildDate": "2024-03-15T10:30:00Z"
  },
  "metadata_entries_found": 4,
  "all_metadata_entries": [
    "/RealMetadata/GitSHA",
    "/RealMetadata/GitBranch",
    "/RealMetadata/GitDirty",
    "/RealMetadata/BuildDate"
  ]
}
```

---

### `moi_regression`
Estimate moment of inertia J (kg·m²) and viscous damping B (Nm·s/rad) for a DC-motor-driven mechanism using OLS regression on logged velocity and current.

**Physics model:** `G × motor_count × kt × I = J × α + B × ω`

**Parameters:**
- `velocity_entry` (string, **required**): Entry path for mechanism velocity (rad/s, or m/s if `wheel_radius` given)
- `current_entry` (string, **required**): Entry path for motor current (A)
- `kt` (number, **required**): Motor torque constant (Nm/A). Kraken X60=0.01940, NEO Vortex=0.01706, NEO 550=0.0108
- `gear_ratio` (number, **required**): Overall gear ratio from motor to output shaft
- `motor_count` (integer, default 1): Number of motors in parallel
- `wheel_radius` (number): Wheel radius (m) for converting linear velocity to angular
- `applied_volts_entry` (string): Entry for applied voltage, used to recover torque sign when current is always non-negative (TalonFX/SparkMax)
- `start_time` / `end_time` (number): Analysis time window
- `alpha_threshold` (number, default 1.0): Min |α| (rad/s²) to include in OLS
- `smooth_window` (integer, default 2): Moving-average half-width for velocity smoothing

**Returns:**
- `J_kg_m2`: Estimated moment of inertia
- `B_Nm_s_per_rad`: Estimated viscous damping coefficient
- `r_squared`: Uncentered R² goodness-of-fit (appropriate for no-intercept model)
- `n_samples_used` / `n_samples_total`: Sample counts
- `warnings`: Diagnostic warnings (negative J, low R², few samples)

**Notes:**
- Uses uncentered R² (`1 - SS_res / Σy²`) since the physics model has no intercept term. Standard centered R² is mathematically invalid for regression through the origin.
- Samples where current or voltage interpolation returns null (e.g., when a log starts later than the velocity log) are skipped rather than zero-filled, preventing silent corruption of the OLS fit.
- Provide `applied_volts_entry` when using motor controllers that report unsigned current (TalonFX, SparkMax) so torque direction can be recovered from voltage sign.

### `analyze_can_bus`
Analyze CAN bus health from the counters the log records, per bus: utilization, the transmit and receive error counters, and bus-off and TX-full counts.

**Parameters:**
- `path` (required): Path to the log file
- `bus_name` (optional): Bus to analyze — `"rio"`, a CANivore name such as `"CANHD"`, or a path prefix (default: every bus found)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds

**How buses are found:** by exact field name, never by a substring such as "can" (which would also match Canandgyro or scan). A bus is a path prefix holding numeric entries named `Utilization`/`BusUtilization`/`PercentBusUtilization`, `BusOffCount`/`OffCount`, `TxFullCount`, `REC`/`ReceiveErrorCount`, or `TEC`/`TransmitErrorCount` — WPILib's `CANStatus` as AdvantageKit logs it under `/SystemStats/CANBus` (named `rio`) and CTRE CANivore status such as `/RealOutputs/CANBus/CANHD/...` (named by the last path segment). The prefix must contain "can", or the group must hold at least two of the counters.

**Per bus (`buses[]`):**
- `utilization`: `mean_percent`, `p95_percent`, `max_percent`, `samples`, `unit_detected` (0–1 fractions are detected from the range and converted), and `while_enabled`
- `tec`, `rec`: levels, not counts — they rise and fall. `max`, `max_time_sec` (first time reached), `error_passive_excursions` (rises to 128 or above; the controller is error-passive at 128 and goes bus-off when TEC passes 255), `time_error_passive_sec` (values held until the next sample), and `while_enabled`
- `bus_off`, `tx_full`: counts that only grow — `first`, `last`, `increase`, `increase_while_enabled` (a decrease is a counter reset, reported as `resets`)

**Other CAN error entries (`errors[]`):** numeric or boolean entries named with CAN (as a word, or CANbus/CANivore/CANcoder) and error, fault, or timeout that are not bus fields. `error_count` is how much the entry increased (each false→true for a boolean), split into `errors_while_enabled`, `errors_while_disabled`, and `errors_state_unknown`.

**Also returns:** `utilization[]` (one row per bus in percent, for compatibility), `enabled_error_total` (increases of bus-off, TX-full, and other error entries while enabled), `inputs`.

**Status:** `no_match` with `looked_for` when the log has no CAN counters or CAN error entries; `no_match` with `available_buses` when `bus_name` matches no bus.

**Example Response (abridged):**
```json
{
  "success": true,
  "status": "ok",
  "buses": [
    {
      "bus": "CANHD",
      "prefix": "/RealOutputs/CANBus/CANHD",
      "entries": {"utilization": "/RealOutputs/CANBus/CANHD/Utilization", "tec": "/RealOutputs/CANBus/CANHD/TEC", "...": "..."},
      "utilization": {"samples": 10041, "unit_detected": "fraction (0-1), converted to percent",
        "mean_percent": 41.2, "p95_percent": 55.0, "max_percent": 100.0},
      "tec": {"samples": 68, "max": 215, "max_time_sec": 650.86, "error_passive_excursions": 9,
        "time_error_passive_sec": 0.41, "while_enabled": {"max": 215, "max_time_sec": 650.86, "error_passive_excursions": 9}},
      "bus_off": {"samples": 1, "first": 0, "last": 0, "increase": 0, "increase_while_enabled": 0}
    }
  ],
  "utilization": [{"entry": "/RealOutputs/CANBus/CANHD/Utilization", "bus": "CANHD", "avg_percent": 41.2, "max_percent": 100.0, "sample_count": 10041}],
  "errors": [],
  "enabled_error_total": 0
}
```

**Utilization Guidelines:**
- **< 50%**: Healthy, plenty of bandwidth
- **50-70%**: Good, monitor if adding devices
- **70-85%**: Concerning, reduce status frame rates
- **> 85%**: Critical, high risk of timeouts and errors

**Common Solutions for High Utilization:**
- Reduce motor controller status frame rates (default is often too high)
- Use CAN FD bus (if supported by hardware)
- Minimize unnecessary CAN devices
- Optimize PDH/PDP current monitoring rates

**Use Case:** Run this tool if experiencing:
- Intermittent motor controller disconnects
- Sensor reading timeouts
- "CAN timeout" errors in Driver Station
- Unreliable device communication

---

## TBA Tools

### `get_tba_status`
Get The Blue Alliance API integration status, including configuration and cache statistics.

**Parameters:** None

**Returns:**
- `available`: Whether TBA API is available
- `status`: "configured" or "not_configured"
- `cache`: Cache statistics (events, matches, eventMatches counts)
- `hint`: Helpful message about TBA features

**Example Response (Configured):**
```json
{
  "success": true,
  "status": "ok",
  "available": true,
  "configuration": "configured",
  "cache": {
    "events": 2,
    "matches": 15,
    "eventMatches": 1
  },
  "hint": "TBA data will be included in list_available_logs for logs with team number in metadata"
}
```

**Example Response (Not Configured):**
```json
{
  "success": true,
  "status": "ok",
  "available": false,
  "configuration": "not_configured",
  "hint": "Set TBA_API_KEY environment variable or use -tba-key argument. Get a free API key at https://www.thebluealliance.com/account"
}
```

**TBA Enrichment:**

When TBA is configured and `list_available_logs` is called, logs that have FRC event metadata are enriched with additional data:
- Team number (from log metadata)
- Match alliance (red/blue)
- Alliance score and opponent score
- Win/loss result
- Actual match start time (corrects midnight timestamp bug)

Only logs with valid event codes, match types (Qualification, Semifinal, Final), and team numbers in their metadata are enriched. Practice matches, simulations, replays, and logs without FMS metadata are not enriched.

### `get_tba_match_data`
Query match scores and detailed results directly from The Blue Alliance. **Use this tool to answer questions about match outcomes**—don't guess or infer match results from telemetry.

**Use Cases:**
- "What was our score?"
- "Did we win?"
- "How many autonomous points did we score?"
- "What were the match results?"

**Parameters:**
- `year` (required): Competition year (e.g., 2024, 2025, 2026)
- `event_code` (required): TBA event code (e.g., "caph" for Poway, "cmptx" for Houston Championship). Must be lowercase.
- `match_type` (required): Match type: "Qualification", "Quarterfinal", "Semifinal", "Final", or "Elimination"
- `match_number` (required): Match number within the type (1-indexed)
- `team_number` (optional): Your team number to highlight your alliance's data

**Returns:**
- `match_found`: Whether the match was found in TBA
- `winning_alliance`: "red", "blue", or "tie_or_not_played"
- `alliances`: Score and team list for each alliance, with `your_alliance` and `won` flags if team_number provided
- `score_breakdown`: Detailed scoring (autoPoints, teleopPoints, endgamePoints, etc.) when available

**Example Request:**
```json
{
  "year": 2024,
  "event_code": "caph",
  "match_type": "Qualification",
  "match_number": 42,
  "team_number": 2363
}
```

**Example Response:**
```json
{
  "success": true,
  "match_found": true,
  "match_key": "2024caph_qm42",
  "comp_level": "qm",
  "match_number": 42,
  "winning_alliance": "red",
  "alliances": {
    "red": {
      "score": 85,
      "teams": [2363, 1234, 5678],
      "your_alliance": true,
      "won": true
    },
    "blue": {
      "score": 72,
      "teams": [9012, 3456, 7890]
    }
  },
  "score_breakdown": {
    "red": {
      "autoPoints": 18,
      "teleopPoints": 52,
      "endgamePoints": 15,
      "totalPoints": 85
    },
    "blue": {
      "autoPoints": 12,
      "teleopPoints": 48,
      "endgamePoints": 12,
      "totalPoints": 72
    }
  }
}
```

**Error Handling:**
- If TBA is not configured: Returns error with instructions to set `TBA_API_KEY`
- If match not found: Returns `match_found: false` with suggestions (verify event code, try specific elimination type)

**Game-Specific Scoring:**
The score breakdown includes game-specific fields that vary by year:
- **2024 Crescendo**: `autoLeavePoints`, `autoAmpNotePoints`, `autoSpeakerNotePoints`, `teleopAmpNotePoints`, `teleopSpeakerNotePoints`
- **2025 Reefscape**: `autoCoralPoints`, `autoAlgaePoints`, `teleopCoralPoints`, `teleopAlgaePoints`, `netAlgaePoints`, `bargePoints`

---

## Export Tools

### `export_csv`
Export entry data to a CSV file for external analysis in Excel, Python, MATLAB, or other tools. Handles special types like Pose2d, Pose3d, and SwerveModuleState with appropriate column headers.

**CSV Headers by Type:**
- **Pose2d**: `timestamp_sec,x,y,rotation_rad,rotation_deg`
- **Pose3d**: `timestamp_sec,x,y,z,qw,qx,qy,qz`
- **SwerveModuleState**: `timestamp_sec,speed_mps,angle_rad,angle_deg`
- **Other types**: `timestamp_sec,value`

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name to export
- `output_path` (required): Path for output CSV file (must be within the configured export directory). Default export directory: `{tmpdir}/wpilog-export/`. Configure with `-exportdir`, `WPILOG_EXPORT_DIR`, or `"exportdir"` in `servers.yaml`.
- `start_time` (optional): Start timestamp in seconds (filters data)
- `end_time` (optional): End timestamp in seconds (filters data)

**Returns:** Confirmation with row count and output path

**Example Response:**
```json
{
  "success": true,
  "entry": "/Drive/Odometry/Pose",
  "output_path": "/tmp/pose_data.csv",
  "rows_exported": 7716,
  "type": "struct:Pose2d"
}
```

### `generate_report`
Generate a comprehensive match summary report. Collects key metrics from the log including duration, battery health, error count, code metadata, and data type distribution.

**Report Sections:**
- **basic_info**: Duration, timestamps, entry count, truncation status
- **battery**: Min/max voltage, brownout risk assessment
- **errors**: Total error count and sample error messages
- **code_info**: Git SHA and branch (if available)
- **top_data_types**: Most common data types in the log

**Parameters:**
- `path` (required): Path to the log file

**Returns:** Comprehensive JSON report with all sections

**Example Response:**
```json
{
  "success": true,
  "log_path": "/logs/2024vadc_qm42.wpilog",
  "log_filename": "2024vadc_qm42.wpilog",
  "basic_info": {
    "duration_sec": 154.32,
    "start_timestamp": 0.0,
    "end_timestamp": 154.32,
    "entry_count": 156,
    "truncated": false
  },
  "battery": {
    "entry": "/Robot/BatteryVoltage",
    "min_voltage": 10.23,
    "max_voltage": 12.89,
    "brownout_risk": "LOW"
  },
  "errors": {
    "total_errors": 3,
    "samples": [
      "Error: Vision target not found",
      "CAN timeout on device 5"
    ]
  },
  "code_info": {
    "git_sha": "a1b2c3d4e5f6",
    "git_branch": "main"
  },
  "top_data_types": {
    "double": 45,
    "struct:Pose2d": 12,
    "struct:SwerveModuleState[]": 8,
    "boolean": 15,
    "string": 10
  }
}
```

---

## FRC Domain Tools

### `get_ds_timeline`
Generate a chronological timeline of critical robot events. Detects enable/disable transitions, match phase changes, battery-voltage threshold brownouts, and roboRIO brownout flag transitions (when the robot logs one). Errors and warnings found in string entries are **counted and summarized, not listed**: the timeline reports exact counts and a distinct-message summary, and `search_strings` provides the complete, paged listing — so no heuristic decides which messages you see. DriverStation entries are recognized under both the `/DriverStation/...` (AdvantageKit) and `DS:...` (WPILib DataLogManager) naming conventions.

**Parameters:**
- `path` (required): Path to the log file
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `brownout_threshold` (optional): Voltage threshold for brownout detection (default: 6.8V for roboRIO 1; use 6.3V for roboRIO 2)

**Returns:** Chronologically sorted `events` with category, type, timestamp, and source entry; `summary` (count per category); `inputs.entries` (the DriverStation, voltage, and brownout flag entries used); `brownout_voltage_entry` (the voltage entry scanned for threshold crossings — selected exactly as `power_analysis` does — with a warning instead when the log has none); `rio_brownout_flag_logged` (whether the log contains a boolean roboRIO brownout flag entry) and, when it does, `rio_brownout_flag_entry`; `text_event_counts` and `text_event_summary` / `text_event_groups_total` (see below); `warnings`

**Event Categories:**
- `robot_state`: ENABLED, DISABLED — transitions of the same DriverStation timeline `get_match_phases` uses (one entry per role, AdvantageKit first; a log with both `DS:` and `/DriverStation/` entries gets one set of events and a warning naming the ignored entries). The state at the start of the log is reported once with `initial: true`. A warning says when the log has no DriverStation enabled entry.
- `match_phase`: AUTO_START, TELEOP_START, TEST_START — at the start of each enabled segment in that mode, and at a mode change while enabled. A practice session with `Autonomous` held false has a TELEOP_START at every enable.
- `power`: BROWNOUT_START, BROWNOUT_END (`basis: "voltage_threshold"` — the battery voltage crossed `brownout_threshold`, with 0.2 V exit hysteresis; includes `voltage`) and RIO_BROWNOUT_START, RIO_BROWNOUT_END (`basis: "rio_flag"` — a logged boolean brownout flag such as AdvantageKit `/SystemStats/BrownedOut` changed state; this is the roboRIO's own brownout state). A voltage crossing does not by itself mean the roboRIO cut outputs; when `rio_brownout_flag_logged` is false, that cannot be determined from the log.
**Error/warning text** (string entries such as `/RealOutputs/Console`, alerts, or WPILib `messages`): a sample is an ERROR when any of its lines contains "error", "exception", or "fault" ("default" does not count); otherwise a WARNING when any line contains "warning", "overrun", or "watchdog" — errors dominate regardless of line order, and the first matching line of the winning kind is the message. This is the same rule `search_strings` uses for its `level` filter, so the two agree (a test enforces it).
- `text_event_counts`: `{error, warning, total, by_source: {<entry>: {error, warning}}}` — exact sample counts within the time window; never capped
- `text_event_summary`: one entry per distinct message, where "distinct" is judged after normalizing numbers to `#` and collapsing whitespace, so `Loop time of 0.023s overrun` and `... 0.031s ...` are one group. Each entry: `{type, message (the normalized pattern), example (the first actual text, when it differs), count, variants (how many different raw texts the group covers — `CAN timeout on device #` with `variants: 2` hides two devices; judged on the full line, while `message`/`example` are cut at 200 characters for display; `variants_capped: true` if a group exceeded 10,000 distinct texts), first_timestamp, last_timestamp, sources[]}`, sorted by count. At most 200 groups are shown; `text_event_groups_total` is the true number and a warning says when the summary was cut. Absent when the log has no error/warning text (`text_event_counts` is always present)
- Individual messages are not placed on the timeline. Use `search_strings` (optionally `level=error`, a regex, a time window) to list them completely with paging totals

**Example Response:**
```json
{
  "success": true,
  "event_count": 8,
  "rio_brownout_flag_logged": false,
  "text_event_counts": {
    "error": 3,
    "warning": 1,
    "total": 4,
    "by_source": { "/RealOutputs/Console": { "error": 3, "warning": 1 } }
  },
  "text_event_groups_total": 2,
  "text_event_summary": [
    {
      "type": "ERROR",
      "message": "CAN timeout on device #",
      "example": "CAN timeout on device 5",
      "count": 3,
      "variants": 2,
      "first_timestamp": 34.5,
      "last_timestamp": 41.2,
      "sources": ["/RealOutputs/Console"]
    },
    {
      "type": "WARNING",
      "message": "Loop time of #s overrun",
      "example": "Loop time of 0.023s overrun",
      "count": 1,
      "variants": 1,
      "first_timestamp": 28.4,
      "last_timestamp": 28.4,
      "sources": ["/RealOutputs/Console"]
    }
  ],
  "brownout_voltage_entry": "/Robot/BatteryVoltage",
  "summary": {
    "robot_state": 4,
    "match_phase": 2,
    "power": 2
  },
  "events": [
    {
      "timestamp": 0.02,
      "type": "ENABLED",
      "category": "robot_state",
      "source": "/DriverStation/Enabled"
    },
    {
      "timestamp": 0.05,
      "type": "AUTO_START",
      "category": "match_phase",
      "source": "/DriverStation/Autonomous"
    },
    {
      "timestamp": 45.23,
      "type": "BROWNOUT_START",
      "category": "power",
      "basis": "voltage_threshold",
      "voltage": 6.79,
      "source": "/Robot/BatteryVoltage"
    }
  ]
}
```

### `analyze_vision`
Analyze vision system reliability and pose estimation quality. Detects target acquisition rate, flicker (rapid loss/reacquisition), and sudden pose jumps ("teleportation"). Enhanced with pose jump detection to identify unreliable vision estimates that can cause odometry drift.

**Parameters:**
- `path` (required): Path to the log file
- `vision_prefix` (optional): Entry path prefix for vision data (auto-detect if not specified)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `jump_threshold` (optional): Distance threshold for pose jump detection in meters (default: 0.5). Lower values detect smaller jumps
- `flicker_window` (optional): Time window for flicker detection in seconds (default: 0.5)

**Searches for entries containing:**
- Target valid: `hasTarget`, `tv`, `targetValid`, `hasResult`
- Vision pose: `visionPose`, `estimatedPose`, `botPose`, `robotPose`
- Odometry: `odometry` + `pose`

**Returns:** Target acquisition analysis, flicker detection, and pose jump analysis with specific jump locations

**Pose Jump Detection:** Identifies sudden position changes that exceed the threshold. Useful for diagnosing:
- Ambiguous AprilTag detections causing incorrect pose estimates
- Tag ID misidentification
- Poorly tuned vision standard deviations
- Lighting or camera exposure issues

**Example Response:**
```json
{
  "success": true,
  "entries_found": {
    "target_valid_entries": 2,
    "vision_pose_entries": 1,
    "odometry_pose_entries": 1
  },
  "target_acquisition": [
    {
      "entry": "/Vision/HasTarget",
      "total_samples": 7500,
      "valid_samples": 6200,
      "acquisition_rate": 0.827,
      "flicker_events": 12
    }
  ],
  "pose_jumps": [
    {
      "entry": "/Vision/EstimatedPose",
      "jump_count": 3,
      "jumps": [
        {
          "timestamp": 45.23,
          "distance_m": 0.82,
          "from_x": 2.1,
          "from_y": 5.5,
          "to_x": 2.9,
          "to_y": 5.4
        }
      ]
    }
  ]
}
```

### `profile_mechanism`
Analyze closed-loop mechanism performance including following error RMSE, stall detection, settling time, overshoot calculations, and temperature profiling. Enhanced with advanced control system metrics for PID tuning and mechanism health monitoring.

**Parameters:**
- `path` (required): Path to the log file
- `mechanism_name` (required): Name or prefix of mechanism to analyze (e.g., "Elevator", "Arm", "Shooter")
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `stall_current_threshold` (optional): Current threshold for stall detection in amperes (default: 30A)

**Searches for entries containing the mechanism name plus:**
- Setpoint: `setpoint`, `goal`, `target`, `desired`
- Measurement: `position`, `measurement`, `actual`
- Velocity: `velocity`, `speed`
- Output: `output`, `voltage`, `dutyCycle`
- Current: `current`
- Temperature: `temp`, `temperature`

**Returns:** Comprehensive mechanism performance analysis including:
- **Following Error**: RMSE, max error, mean error - indicates control loop accuracy
- **Stall Detection**: Detects when velocity is near zero (< 0.01) while current exceeds threshold - indicates mechanical binding or overload
- **Settling Time**: Time to reach and stay within threshold of setpoint - measures control response speed
- **Overshoot**: Maximum overshoot percentage beyond setpoint - indicates control aggressiveness
- **Temperature Analysis**: Max/avg temperature with overheat warnings

**Example Response:**
```json
{
  "success": true,
  "mechanism": "Elevator",
  "entries": {
    "setpoint": "/Elevator/Setpoint",
    "measurement": "/Elevator/Position",
    "velocity": "/Elevator/Velocity",
    "current": "/Elevator/Current",
    "temperature": "/Elevator/Temperature"
  },
  "following_error": {
    "rmse": 0.015,
    "max_error": 0.089,
    "mean_error": 0.012,
    "sample_count": 7500
  },
  "settling_time_sec": 0.45,
  "overshoot_percent": 12.5,
  "stall_events": [
    {
      "start_time": 45.23,
      "end_time": 45.78,
      "duration": 0.55,
      "max_current": 38.2
    },
    {
      "start_time": 89.12,
      "end_time": 89.34,
      "duration": 0.22,
      "max_current": 35.5
    }
  ],
  "temperature": {
    "max_temperature_c": 58.2,
    "avg_temperature_c": 42.1,
    "overheat_warning": false
  },
  "_execution_time_ms": 125
}
```

**Use Case for Control Tuning:**
- **High RMSE or overshoot**: Increase D gain or decrease P gain
- **Slow settling time**: Increase P gain or add feedforward
- **Stall events**: Check for mechanical binding, insufficient power, or incorrect current limits
- **High overshoot with fast settling**: Well-tuned but aggressive - acceptable for many mechanisms

### `analyze_auto`
Analyze every autonomous period in the log: when it started and ended, which routine was selected, and how closely the robot followed its path.

**Parameters:**
- `path` (required): Path to the log file
- `auto_prefix` (optional): Entry name prefix to search for the path setpoint and actual pose entries

**How it works:**
- Autonomous periods are the enabled `auto` segments of the same DriverStation timeline `get_match_phases` reports (a log can hold several; all are listed in `auto_periods`, and the top-level `auto_*` fields describe the first).
- Selected routine: the value, at each period's start, of a string entry chosen by rank — a chooser's `.../active` entry, then a name containing `auto` with `selected`, `mode`, `routine`, or `choice` (e.g. `/RealOutputs/AutoSelector/SelectedAutoMode`), then any name containing `chooser`; ties by entry id. Chooser metadata (`.type`, `default`, `options`) is ignored.
- Path following: a `struct:Pose2d`/`struct:Pose3d` setpoint (named with `setpoint`, `target`, or `desired`) and actual pose (named with `actual`, `estimated`, `odometry`, or ending in `/Pose`), lowest entry id first. RMSE and maximum distance between them, sampled at each setpoint time with the actual pose held (zero-order hold). Samples whose pose layout cannot be read are counted in `unreadable_samples`, never treated as zero error.

**Returns:** `auto_periods[]` (`start`, `end`, `duration`, `end_reason`, `selected_routine`, `path_following_error`), `auto_start_time`/`auto_end_time`/`auto_duration`/`selected_routine`/`path_following_error` for the first period, `expected_auto_sec` (season timing), `inputs.entries` (DriverStation, chooser, and pose entries used), and `skipped` entries for sections that could not be produced (status `partial`).

**Status:** `not_applicable` when the log has no autonomous period — `reason` says why, e.g. "No autonomous period: /DriverStation/Autonomous has 1 sample(s), all false"; `no_match` when the log has no DriverStation state entries.

**Path Following Error:** Lower RMSE indicates better path following. Typical values:
- **< 0.05m**: Excellent path following
- **0.05-0.15m**: Good path following (acceptable for most games)
- **> 0.15m**: Poor path following - check controller tuning or wheel slippage

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "inputs": {"entries": {"enabled": "/DriverStation/Enabled", "autonomous": "/DriverStation/Autonomous",
    "selected_routine": "/RealOutputs/AutoSelector/SelectedAutoMode",
    "path_setpoint": "/Auto/TargetPose", "path_actual": "/Drive/EstimatedPose"}},
  "auto_periods": [
    {"start": 20.0, "end": 40.0, "duration": 20.0, "end_reason": "disabled",
     "selected_routine": "ThreePieceAmp",
     "path_following_error": {"rmse_meters": 0.08, "max_error_meters": 0.23, "samples": 750}}
  ],
  "auto_start_time": 20.0,
  "auto_end_time": 40.0,
  "auto_duration": 20.0,
  "selected_routine": "ThreePieceAmp",
  "path_following_error": {"rmse_meters": 0.08, "max_error_meters": 0.23, "samples": 750},
  "expected_auto_sec": 20
}
```

### `analyze_cycles`
Analyze game piece handling cycle times with flexible cycle detection modes, data quality warnings, and comprehensive analysis. Enhanced with configurable cycle definitions, time filtering, case-insensitive matching, and incomplete cycle detection.

**Cycle Detection Modes:**
- **start_to_start**: Measures from one occurrence of `cycle_start_state` to the next (default). Useful for regular repeating patterns.
- **start_to_end**: Measures from `cycle_start_state` to `cycle_end_state`. More semantically correct for workflows with distinct start and end states.

**Parameters:**
- `path` (required): Path to the log file
- `state_entry` (required): Entry name for mechanism state machine
- `cycle_mode` (optional, default: `"start_to_start"`): Cycle detection mode (`"start_to_start"` or `"start_to_end"`)
- `cycle_start_state` (optional): State value marking cycle start (e.g., `"INTAKING"`) - required for both modes
- `cycle_end_state` (optional): State value marking cycle end (e.g., `"SCORING"`) - required for `start_to_end` mode
- `idle_state` (optional): State value for idle/dead time tracking (e.g., `"IDLE"`)
- `start_time` (optional): Start timestamp in seconds for filtering
- `end_time` (optional): End timestamp in seconds for filtering
- `case_sensitive` (optional, default: `true`): Whether state matching is case-sensitive
- `limit` (optional, default: `10`): Maximum cycles/dead periods to return in details

**Returns:**
- `success`: true/false
- `sample_count`: Total state samples processed
- `cycle_mode`: The cycle detection mode used
- `warnings`: Array of data quality warnings (if any)
- `cycle_times`: Statistics object with:
  - `count`: Number of complete cycles
  - `avg_sec`: Average cycle duration
  - `min_sec`: Minimum cycle duration
  - `max_sec`: Maximum cycle duration
- `cycles`: Array of cycle details (limited by `limit` parameter):
  - `start_time`: Cycle start timestamp
  - `end_time`: Cycle end timestamp
  - `duration`: Cycle duration in seconds
  - `incomplete`: Boolean flag indicating if cycle wasn't completed
- `cycles_truncated`: true if more cycles exist than returned (optional)
- `total_cycles`: Total cycle count if truncated (optional)
- `dead_time`: Statistics if `idle_state` provided:
  - `total_sec`: Total dead time
  - `period_count`: Number of dead time periods
  - `avg_duration_sec`: Average dead time duration
- `dead_time_periods`: Array of dead time period details (limited by `limit` parameter)

**Data Quality Warnings:**
The tool automatically detects and warns about potential data quality issues:
- **Rapid state transitions**: More than 5 transitions occurring less than 0.1s apart may indicate state machine instability or sensor noise
- **Unknown states**: States that don't match any of the specified states (cycle_start_state, cycle_end_state, idle_state) may indicate unexpected behavior or typos
- Warnings help identify data collection issues, state machine bugs, or configuration problems

**Example 1: Start-to-End Mode (Semantic Cycles)**
```json
{
  "state_entry": "/Superstructure/State",
  "cycle_mode": "start_to_end",
  "cycle_start_state": "INTAKING",
  "cycle_end_state": "SCORING",
  "idle_state": "IDLE",
  "start_time": 15.0,
  "end_time": 135.0,
  "case_sensitive": false,
  "limit": 20
}
```

**Example 2: Start-to-Start Mode (Repeating Pattern)**
```json
{
  "state_entry": "/Intake/State",
  "cycle_mode": "start_to_start",
  "cycle_start_state": "HAS_PIECE",
  "idle_state": "IDLE"
}
```

**Example Response:**
```json
{
  "success": true,
  "sample_count": 1250,
  "cycle_mode": "start_to_end",
  "warnings": [
    "Detected 2 unknown states: ERROR_STATE, UNKNOWN"
  ],
  "cycle_times": {
    "count": 8,
    "avg_sec": 12.5,
    "min_sec": 9.2,
    "max_sec": 18.1
  },
  "cycles": [
    {"start_time": 15.2, "end_time": 24.8, "duration": 9.6, "incomplete": false},
    {"start_time": 27.0, "end_time": 39.4, "duration": 12.4, "incomplete": false},
    {"start_time": 42.0, "end_time": 135.0, "duration": 93.0, "incomplete": true}
  ],
  "dead_time": {
    "total_sec": 20.0,
    "period_count": 4,
    "avg_duration_sec": 5.0
  },
  "dead_time_periods": [
    {"start_time": 24.8, "end_time": 27.0, "duration": 2.2, "incomplete": false},
    {"start_time": 39.4, "end_time": 42.0, "duration": 2.6, "incomplete": false}
  ],
  "_execution_time_ms": 45
}
```

**Incomplete Cycles:**
Cycles marked with `incomplete: true` indicate the log ended before the cycle completed. This can happen when:
- Log capture stopped mid-cycle
- Match ended during a cycle
- Time filtering (start_time/end_time) cut off a cycle

Incomplete cycles are still included in the output for visibility, but excluded from cycle time statistics to avoid skewing averages.

### `analyze_replay_drift`
Validate AdvantageKit deterministic replay by comparing RealOutputs vs ReplayOutputs. Identifies entries that diverged and their first divergence timestamp.

**Parameters:**
- `path` (required): Path to the log file

**Pairs entries matching:**
- `/RealOutputs/*` with `/ReplayOutputs/*`
- `RealOutputs/*` with `ReplayOutputs/*`

**Returns:** Count of paired entries, matching vs divergent pairs, and divergence details

**Example Response:**
```json
{
  "success": true,
  "paired_entries": 45,
  "matching_pairs": 42,
  "divergent_pairs": 3,
  "divergences": [
    {
      "entry": "/Drive/Odometry/Pose",
      "first_divergence_timestamp": 12.34,
      "divergence_count": 150,
      "total_samples": 7500
    },
    {
      "entry": "/Vision/EstimatedPose",
      "first_divergence_timestamp": 12.35,
      "divergence_count": 148,
      "total_samples": 7500
    }
  ],
  "hint": "Common causes of replay divergence: Timer.getFPGATimestamp(), Math.random(), network data, non-logged sensor reads, or hardware-dependent code paths."
}
```

**Use Case:** When replay outputs don't match real outputs, this tool helps identify which subsystems broke determinism. The first divergence timestamp often points to the root cause - entries that diverge first typically contain the non-deterministic code.

### `analyze_loop_timing`
Analyze robot code loop timing performance. Detects loop overruns (> 20ms), measures jitter, and provides timing statistics. Critical for diagnosing real-time performance issues that can cause stuttering, dropped commands, or unstable control.

**Parameters:**
- `path` (required): Path to the log file
- `threshold_ms` (optional): Loop time threshold for violations in milliseconds (default: 20ms for standard 50Hz loop)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `unit` (optional): Unit of loop time values: `"ms"`, `"s"`, or `"auto"` (default). Auto-detect uses the median value: if median < 1.0, assumes seconds and converts to milliseconds.

**Searches for entries containing:**
- Loop timing: `loopTime`, `cycleTime`, `scanTime`, `dt`, `period`
- Common patterns: `/RobotCode/LoopTime`, `/Diagnostics/LoopTime`, `/Robot/LoopTime`

**Returns:** Loop timing statistics, violation count, jitter analysis, and specific violation timestamps

**Example Response:**
```json
{
  "success": true,
  "entry": "/RobotCode/LoopTime",
  "statistics": {
    "avg_ms": 18.2,
    "min_ms": 15.1,
    "max_ms": 42.7,
    "std_dev_ms": 2.8,
    "sample_count": 7500
  },
  "violations": [
    {
      "timestamp": 45.23,
      "loop_time_ms": 42.7,
      "threshold_ms": 20.0
    },
    {
      "timestamp": 89.45,
      "loop_time_ms": 25.3,
      "threshold_ms": 20.0
    }
  ],
  "violation_count": 12,
  "violation_rate": 0.0016,
  "jitter": {
    "p95_ms": 19.8,
    "p99_ms": 21.5,
    "range_ms": 27.6
  },
  "health_assessment": "FAIR - Some loop overruns detected",
  "_execution_time_ms": 35
}
```

**Health Assessment Levels:**
- **EXCELLENT**: No violations, low jitter (< 2ms std dev)
- **GOOD**: Few violations (< 1%), moderate jitter
- **FAIR**: Some violations (1-5%), higher jitter
- **POOR**: Many violations (> 5%), indicates serious performance issues

**Common Causes of Loop Overruns:**
- Vision processing on RoboRIO thread
- Excessive logging or NetworkTables writes
- Blocking I2C/SPI sensor reads
- Unoptimized algorithms (O(n²) in periodic)
- Garbage collection pauses (check JVM memory)

### `predict_battery_health`
Analyze battery voltage and current draw to predict brownout risk and estimate battery health. Returns a health score (0–100), risk level, voltage statistics, recovery analysis, and actionable recommendations.

**Parameters:**
- `path` (required): Path to the log file
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `nominal_voltage` (optional): Expected full battery voltage (default: 12.6V)
- `brownout_threshold` (optional): Brownout voltage threshold (default: 6.8V for roboRIO 1; use 6.3V for roboRIO 2)
- `warning_threshold` (optional): Warning voltage threshold (default: 9.0V)

**Health Score Formula (0–100):**
The health score starts at 100 and deducts points for detected issues:

| Factor | Penalty | Rationale |
|--------|---------|-----------|
| Avg voltage < 88% of nominal (≈11.1V) | Up to 18 pts (deficit × 150) | Normal under-load sag is 87–91%; below 88% approaches brownout territory |
| Each brownout event | 20 pts each | Indicates serious power delivery issues |
| Each warning-level sag event | 5 pts each | Cumulative wear indicator |
| Slow voltage recovery (>0.5s avg) | Up to 20 pts | Suggests high internal resistance (aging battery) |
| Min voltage < 10V | Up to 30 pts | Approaching critical failure territory |

Brownout detection uses 0.2V hysteresis — voltage must rise 0.2V above the threshold before a brownout is considered ended. This prevents noisy connections from inflating event counts.

**Note:** This score provides useful relative ranking between batteries. Absolute values should not be the sole basis for replacement decisions — also consider battery age, connector condition, and wire gauge.

**Risk Levels:** MINIMAL (score ≥ 80), LOW (60–79), MODERATE (30–59), HIGH (score < 30 or min voltage < warning threshold), CRITICAL (min voltage < brownout threshold)

**Returns:** Health score, risk level, voltage statistics, brownout event details, recovery analysis, and recommendations

**Example Response:**
```json
{
  "success": true,
  "health_score": 72,
  "risk_level": "LOW",
  "voltage_stats": {
    "min_volts": 10.2,
    "max_volts": 12.8,
    "avg_volts": 11.9,
    "voltage_sag": 2.4
  },
  "brownout_events": 0,
  "warning_events": 3,
  "recommendations": ["Consider battery replacement - health declining"],
  "data_quality": { "sample_count": 7500, "quality_score": 0.92 }
}
```

### `get_game_info`
Get year-specific FRC game information including match timing, scoring values, field geometry, game pieces, and analysis hints. Use this to understand the context of a log file. Defaults to the current season if no year is specified.

**Parameters:**
- `season` (optional): FRC season year (e.g., 2026). Defaults to current year.

**Bundled game data:** 2024 Crescendo, 2025 Reefscape, 2026 REBUILT

**Returns:** Match timing (auto/teleop/endgame durations with shift breakdown), scoring values, field geometry, game pieces, typical mechanisms, and analysis hints for LLM context.

**Example Response:**
```json
{
  "success": true,
  "season": 2026,
  "game_name": "REBUILT",
  "match_timing": {
    "auto_duration_sec": 20,
    "teleop_duration_sec": 140,
    "total_duration_sec": 160,
    "endgame_duration_sec": 30,
    "shifts": { "auto": {"start_sec": 0, "end_sec": 20}, "..." : "..." }
  },
  "scoring": {
    "match_points": { "auto": {"fuel_active_hub": 1, "tower_level_1": 15}, "..." : "..." },
    "ranking_points": { "energized_rp": {"regional_threshold": 100}, "..." : "..." }
  },
  "analysis_hints": {
    "endgame_activity": "Tower climbing attempts in final 30 seconds",
    "fuel_context": "100 FUEL for ENERGIZED RP, 360 for SUPERCHARGED RP"
  }
}
```

**Custom game data:** Place a JSON file matching the bundled format in any directory and load it via the `GameKnowledgeBase.loadFromFile()` API.

---

## RevLog Tools

REV log (.revlog) files contain CAN bus data from SPARK MAX/Flex motor controllers. These are typically recorded on the roboRIO by REV's logging library in your robot code, though they can also be captured by REV Hardware Client on a connected laptop. These tools allow you to analyze REV motor controller data synchronized with your wpilog timestamps.

### How Timestamp Synchronization Works

The fundamental challenge: `.wpilog` files timestamp data using the **roboRIO's FPGA hardware clock** (microseconds since FPGA boot), while `.revlog` files use **CLOCK_MONOTONIC** (microseconds since system boot) on whatever device recorded them — usually the roboRIO itself, or a laptop running REV Hardware Client. Even when both clocks run on the same roboRIO, the FPGA clock and the Linux monotonic clock are independent sources that start at different times and may run at slightly different rates.

wpilog-mcp solves this with a **two-phase synchronization algorithm**:

#### Phase 1: Coarse Alignment (seconds-level accuracy)

The wpilog contains periodic `systemTime` entries that map FPGA timestamps to UTC wall-clock time. The revlog filename encodes its start time (e.g., `REV_20260320_143052.revlog` → March 20, 2026 at 2:30:52 PM local time). By comparing these, we establish an initial offset estimate accurate to within a few seconds.

This step can fail if: the recording device's wall clock was significantly wrong (e.g., no NTP sync on the roboRIO or laptop), or `systemTime` entries are missing from the wpilog.

#### Phase 2: Fine Alignment via Cross-Correlation (millisecond accuracy)

Both logs record overlapping physical quantities — for example, the robot code logs motor output duty cycle to the wpilog, and the SPARK MAX independently records its applied output in the revlog. These are the same physical signal observed through different clocks.

The algorithm:
1. **Signal matching**: Identifies candidate pairs (e.g., `/drive/frontLeft/output` ↔ `SparkMax_1/appliedOutput`) using naming heuristics and optional CAN ID hints
2. **Resampling**: Both signals are resampled to a uniform 100 Hz rate using linear interpolation. For long recordings, a **high-variance window search** selects the most active portion of the signal (important when logs start with minutes of the robot disabled)
3. **Cross-correlation**: For each candidate pair, the [Pearson correlation coefficient](https://en.wikipedia.org/wiki/Pearson_correlation_coefficient) is computed at every integer sample lag within a ±60-second search window centered on the coarse estimate. Pearson correlation is invariant to signal scaling and DC offset, making it robust when comparing duty cycle against voltage or velocity
4. **Sub-sample refinement**: Parabolic interpolation on the correlation peak achieves sub-millisecond accuracy from 100 Hz data
5. **Consensus**: The median offset across all strong pairs (correlation > 0.7) is used as the final estimate. Confidence is scored from three factors: average correlation strength (0–0.4), number of agreeing pairs (0–0.3), and inter-pair agreement measured by offset standard deviation (0–0.3)

#### Clock Drift Compensation (for recordings > 15 minutes)

For long recordings, the FPGA clock and the monotonic clock may drift at different rates — typically 10–50 ms per hour, even when both run on the same roboRIO. The synchronizer detects this by splitting the signal into halves, computing independent offsets on each half, and fitting a linear drift rate (nanoseconds per second). When drift is detected, all timestamp conversions apply a correction:

```
fpga_time = revlog_time + offset + (revlog_time − reference_time) × drift_rate
```

The `sync_status` tool reports drift rate when detected.

### Confidence Levels

| Confidence Level | Estimated Accuracy | How It's Determined |
|-----------------|-------------------|---------------------|
| **HIGH** | 1–5 ms | Multiple signal pairs agree within 5 ms, correlation > 0.9 |
| **MEDIUM** | 5–50 ms | Some signals correlate well, minor disagreement between pairs |
| **LOW** | 50–5000 ms | Weak correlation or significant disagreement between signals |
| **FAILED** | Unknown | Could not establish reliable synchronization |

**Always check `sync_confidence` before using REV log data for precise timing analysis.** If automatic synchronization produces poor results, use `set_revlog_offset` to provide a known-good offset manually.

### Binary Parsing Robustness

The revlog parser includes guards against corrupted or truncated files:
- **Record limit**: Stops after 10 million records to prevent OOM on corrupt files
- **Malformed record recovery**: Individual corrupt records are skipped without aborting the parse
- **Negative timestamp rejection**: Records with invalid timestamps are discarded
- **Truncated CAN frame handling**: Frames shorter than 8 bytes are silently skipped

### `list_revlog_signals`
List all available signals from synchronized REV log files. Shows signal names, device info, sample counts, and synchronization confidence.

**Parameters:**
- `path` (required): Path to the log file
- `device_filter` (optional): Filter signals by device key substring (e.g., "SparkMax_1")
- `signal_filter` (optional): Filter signals by signal name substring (e.g., "velocity")

**Returns:** List of available signals with sync status and metadata

**Example Response:**
```json
{
  "success": true,
  "signal_count": 12,
  "revlog_count": 1,
  "overall_sync_confidence": "high",
  "signals": [
    {
      "key": "REV/SparkMax_1/appliedOutput",
      "device": "SparkMax_1",
      "signal": "appliedOutput",
      "unit": "duty_cycle",
      "sample_count": 7500,
      "can_bus": "rio",
      "sync_confidence": "high"
    },
    {
      "key": "REV/SparkMax_1/velocity",
      "device": "SparkMax_1",
      "signal": "velocity",
      "unit": "rpm",
      "sample_count": 7500,
      "can_bus": "rio",
      "sync_confidence": "high"
    },
    {
      "key": "REV/SparkFlex_5/outputCurrent",
      "device": "SparkFlex_5",
      "signal": "outputCurrent",
      "unit": "A",
      "sample_count": 5000,
      "can_bus": "rio",
      "sync_confidence": "high"
    }
  ],
  "warnings": [],
  "_metadata": {
    "timing_accuracy_ms": "1-5"
  }
}
```

**Available Signals (from DBC definitions):**
- `appliedOutput` - Motor output duty cycle (-1 to 1)
- `velocity` - Motor velocity in RPM
- `position` - Motor position in rotations
- `busVoltage` - Bus voltage in V
- `outputCurrent` - Motor current in A
- `temperature` - Motor controller temperature in °C
- `faults` / `stickyFaults` - Fault flags

### `get_revlog_data`
Get data from a REV log signal with timestamps converted to FPGA time. Similar to `read_entry` but for REV motor controller data.

**Parameters:**
- `path` (required): Path to the log file
- `signal_key` (required): Signal key from `list_revlog_signals` (e.g., "REV/SparkMax_1/appliedOutput")
- `start_time` (optional): Start timestamp in seconds (FPGA time)
- `end_time` (optional): End timestamp in seconds (FPGA time)
- `limit` (optional): Maximum samples to return (default: 1000)
- `include_stats` (optional): Include basic statistics (min, max, mean)

**Returns:** Timestamped data array with optional statistics

**Example Response:**
```json
{
  "success": true,
  "signal_key": "REV/SparkMax_1/velocity",
  "sample_count": 100,
  "total_samples": 7500,
  "sync_confidence": "high",
  "data": [
    {"timestamp": 15.02, "value": 5200.5},
    {"timestamp": 15.04, "value": 5198.3},
    {"timestamp": 15.06, "value": 5201.1}
  ],
  "statistics": {
    "min": 0.0,
    "max": 5500.2,
    "mean": 4200.3,
    "count": 100
  },
  "_metadata": {
    "timing_accuracy_ms": "1-5"
  }
}
```

**Use Cases:**
- Compare motor commanded output (wpilog) vs actual output (revlog)
- Analyze motor velocity/position response
- Validate PID controller tuning with actual motor data
- Debug motor controller communication issues

### `sync_status`
Get detailed synchronization status for all synchronized REV log files. Shows confidence levels, timing offsets, and the signal pairs used for correlation.

**Parameters:**
- `path` (required): Path to the log file
- `include_signal_pairs` (optional): Include details about which signal pairs were used for correlation

**Returns:** Detailed sync status with confidence assessment and offset information

**Example Response:**
```json
{
  "success": true,
  "synchronized": true,
  "revlog_count": 1,
  "overall_confidence": "high",
  "overall_confidence_value": 1.0,
  "revlogs": [
    {
      "can_bus": "rio",
      "path": "/logs/REV_20260320_143052.revlog",
      "device_count": 4,
      "signal_count": 24,
      "sync": {
        "method": "CROSS_CORRELATION",
        "confidence": 0.95,
        "confidence_level": "high",
        "offset_microseconds": 523450,
        "offset_milliseconds": 523.45,
        "offset_seconds": 0.52345,
        "explanation": "Good sync via cross-correlation of 3 signal pairs",
        "successful": true,
        "drift_rate_ns_per_sec": 12.5,
        "drift_rate_ms_per_hour": 45.0,
        "reference_time_sec": 150.0
      },
      "signal_pairs": [
        {
          "wpilog_entry": "/drive/frontLeft/output",
          "revlog_signal": "SparkMax_1/appliedOutput",
          "correlation": 0.95,
          "estimated_offset_us": 523000,
          "samples_used": 5000
        }
      ]
    }
  ],
  "_metadata": {
    "timing_accuracy_ms": "1-5",
    "confidence_description": "Multiple signals agree within 5ms, correlation > 0.9"
  }
}
```

**Sync Methods:**
- `CROSS_CORRELATION`: Full cross-correlation alignment (best accuracy)
- `SYSTEM_TIME_ONLY`: Coarse alignment from system time only (fallback)
- `USER_PROVIDED`: Manual offset provided by user
- `FAILED`: Could not establish synchronization

**Troubleshooting Low Confidence:**
1. Ensure wpilog and revlog were recorded during the same time period
2. Check that matching signals exist (e.g., motor outputs logged in both)
3. Try providing CAN ID hints to improve signal matching
4. If sync fails, verify motor controllers were connected and reporting data
5. Use `set_revlog_offset` to manually provide a known offset if automatic sync fails

**Example Workflow:**
```
1. sync_status(path="/logs/match.wpilog")    # Check sync confidence (auto-loads log)
2. list_revlog_signals(path="/logs/match.wpilog")  # See available signals
3. get_revlog_data(path="/logs/match.wpilog", signal_key="REV/SparkMax_1/appliedOutput", start_time=15.0, end_time=30.0)
4. compare with read_entry(path="/logs/match.wpilog", name="/drive/frontLeft/output", start_time=15.0, end_time=30.0)
```

### `set_revlog_offset`
Manually set the synchronization offset for a REV log file, overriding automatic synchronization. Use this when automatic sync fails, produces incorrect results, or when you have determined the correct offset through other means (e.g., by visually aligning a known event in both logs).

**Parameters:**
- `path` (required): Path to the log file
- `offset_ms` (required): Time offset in milliseconds to add to revlog timestamps to convert them to FPGA time. Example: if a revlog event appears 500ms after the same event in wpilog, set `offset_ms` to -500
- `can_bus` (optional): CAN bus name to apply offset to (e.g., "rio"). If omitted, applies to the first/only revlog

**Returns:** Confirmation with previous and new offset details

**Example Response:**
```json
{
  "success": true,
  "can_bus": "rio",
  "offset_ms": -523.5,
  "offset_us": -523500,
  "previous_offset_ms": -480.2,
  "previous_method": "CROSS_CORRELATION",
  "new_method": "USER_PROVIDED"
}
```

**When to use this:**
- Automatic synchronization reports LOW or FAILED confidence
- You know the exact offset from a distinctive event visible in both logs (e.g., a motor stall, a sudden stop)
- The automatic offset produces visibly misaligned data when comparing corresponding wpilog/revlog signals
- The recording started with the robot disabled for a long period and correlation was poor

### `wait_for_sync`
Wait for background RevLog synchronization to complete. RevLog synchronization runs asynchronously after a log is first loaded, so revlog data may not be immediately available. Call this tool if you need revlog data right away. Returns instantly if sync is already done or no revlogs are present.

**Parameters:**
- `path` (required): Path to the log file
- `timeout_ms` (optional): Maximum time to wait in milliseconds (default: 30000)

**Returns:** Completion status and revlog count

**Example Response:**
```json
{
  "success": true,
  "completed": true,
  "was_in_progress": true,
  "revlog_count": 2,
  "synchronized": true
}
```

**When to use this:**
- After loading a log, when you need to immediately query revlog signals
- When `sync_status` or `list_revlog_signals` shows `sync_in_progress: true`
- Not needed if you call other tools first — sync usually completes within a few seconds

---

## Server Instructions

In addition to per-tool guidance, the server sends general reasoning guidance to the AI agent through two channels:

- **MCP `instructions`** — Returned in the `initialize` response. Clients such as Claude Code, VS Code Copilot, and Gemini CLI place it in the model's system prompt (Claude Desktop currently does not). It is a compact, ordered checklist (under 2 KB, the limit at which Claude Code truncates it): answer the question asked first; never name an entry or quote a number that no tool returned; never compute statistics by hand; verify the premise before explaining an event; use three tiers of language (observed event = fact, statistic = inference bounded by `confidence_level`, cause outside the telemetry = hypothesis needing physical inspection); test a user-proposed cause against a rival; scope statistics to the phase and enabled state; one log is one sample; truncated logs, revlog sync, and TBA-sourced scores.
- **`get_server_guide` → `analysis_principles`** — The long-form version, returned as a tool result so it reaches the model in every client. The tool's `tools/list` entry carries `_meta: {"anthropic/alwaysLoad": true}` so Claude Code keeps its description in context even when other MCP tools are deferred.

Both come from `AnalysisGuidance.java`; a test verifies that every tool name they mention exists and that the instructions stay under the size limit.

## Response Fields

The following are **not callable MCP tools**. They are metadata fields embedded in the JSON responses of analytical tools to help LLMs calibrate their confidence when interpreting results. For full captured example responses from every tool, see [TOOL_RESPONSES.md](TOOL_RESPONSES.md).

### Result contract (`success`, `status`, and related fields)

Every tool result, however the tool built it, is normalized by the server so that:

| Field | Meaning |
|-------|---------|
| `success` | `true` exactly when `status` is `ok` or `partial` |
| `status` | `ok` (full result), `partial` (some sections could not be produced — see `skipped`), `not_applicable` (the tool does not apply to this log, e.g. no autonomous period), `no_match` (the tool found none of the entries it analyzes), or `error` (invalid arguments, missing entry, unreadable file) |
| `reason` | For `not_applicable` and `no_match`: what was missing, in terms of the log's own data |
| `looked_for` | For `no_match`: the naming rules, types, or schemas that were searched |
| `hint` | How to point the tool at the right data (usually a parameter to pass) |
| `error` | For `error`: the message |
| `inputs` | The entries (`inputs.entries`, by role) and time window (`inputs.window`) a result was computed from |
| `skipped` | Sections not produced, each `{section, reason}` |
| `limits` | For each list cut short by a limit: `{total, returned, limit}` |
| `_metadata.non_finite_fields` | Fields whose value could not be computed (NaN or infinite). They are emitted as `null` — never as a bare `NaN`, which is not valid JSON — and named in a warning |

`not_applicable` and `no_match` are answers, not failures of the server: they tell the agent that the absence of findings is not evidence of the absence of problems, and how to find the right data.

### `data_quality`

Computed from the primary data entry's timestamped values. Included in responses from all analytical tools (15+).

| Field | Description |
|-------|-------------|
| `sample_count` | Number of data points |
| `time_span_seconds` | Duration of the data |
| `gap_count` | Number of data gaps (intervals > 5x the median sample interval) |
| `max_gap_ms` | Largest gap in milliseconds (only present if gaps > 0) |
| `nan_filtered` | Count of NaN/Infinity values filtered (only present if > 0) |
| `effective_sample_rate_hz` | Actual sample rate based on median interval |
| `quality_score` | Composite score 0.0-1.0 (see formula below) |

**Quality Score Formula:**
```
score = 1.0
  - 0.3 x min(gap_count / 20, 1)       // Gaps: 20+ gaps = full penalty
  - 0.2 x min(nan_count / total, 1)     // NaN: ratio of non-finite values
  - 0.3 x (n<100 ? 1 : n<500 ? 0.5 : 0) // Samples: statistical confidence
  - 0.2 x min(jitter / median_dt, 1)    // Jitter: timing irregularity
```

**Confidence levels** derived from quality score:
- `"high"` (> 0.8): Reliable data, results can be stated with confidence
- `"medium"` (0.5-0.8): Usable data, note caveats in analysis
- `"low"` (0.2-0.5): Poor data, results should be treated as preliminary
- `"insufficient"` (<= 0.2): Too little data for meaningful analysis

### `server_analysis_directives`

Auto-generated LLM guidance based on data quality issues detected. Included alongside `data_quality` in analytical tool responses.

| Field | Description |
|-------|-------------|
| `confidence_level` | `"high"`, `"medium"`, `"low"`, or `"insufficient"` |
| `sample_context` | Human-readable summary (e.g., "Based on 4500 samples over 150.0 seconds") |
| `interpretation_guidance` | Array of warnings about data quality issues detected |
| `suggested_followup` | Array of recommended next tools to call |

Auto-generated guidance triggers:
- Sample count < 100 -> "Low sample count" warning
- Gap count > 5 -> "Data gaps detected" warning
- NaN values present -> "Non-finite values filtered" warning
- Time span < 10 seconds -> "Short time span" warning
