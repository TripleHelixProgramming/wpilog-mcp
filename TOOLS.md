# wpilog-mcp Tool Reference

Complete documentation for all 49 tools available in wpilog-mcp.

## Important: Concurrency Limitations

> **⚠️ NOT SAFE FOR CONCURRENT USE**
>
> This server maintains shared state (active log, log cache) and is designed for single-client, sequential operation. **Do not call multiple tools in parallel** from the same session—execute tool calls sequentially.
>
> Any concurrent use would require coarse-grained locking and fully reinitializing state on each request (e.g., clients cannot assume which log is currently loaded between calls).

**Workaround:** Running multiple *separate* server instances pointing to the same log directory is safe (disk cache uses file locking and atomic operations).

**⚠️ LLM Sub-Agent Warning:** Some LLM frameworks spawn sub-agents to parallelize multi-log analysis. These sub-agents may ignore sequential execution guidance. Explicitly instruct agents: *"Analyze each log file one at a time, completing all analysis before moving to the next."*

## Table of Contents

- [Discovery Tools](#discovery-tools)
  - [get_server_guide](#get_server_guide)
  - [suggest_tools](#suggest_tools)
- [Core Tools](#core-tools)
  - [list_available_logs](#list_available_logs)
  - [load_log](#load_log)
  - [list_entries](#list_entries)
  - [get_entry_info](#get_entry_info)
  - [read_entry](#read_entry)
  - [search_entries](#search_entries)
  - [get_statistics](#get_statistics)
  - [compare_entries](#compare_entries)
  - [get_types](#get_types)
  - [list_struct_types](#list_struct_types)
  - [health_check](#health_check)
  - [get_game_info](#get_game_info)
- [Multi-Log Management](#multi-log-management)
  - [list_loaded_logs](#list_loaded_logs)
  - [set_active_log](#set_active_log)
  - [unload_log](#unload_log)
  - [unload_all_logs](#unload_all_logs)
- [Search Tools](#search-tools)
  - [find_condition](#find_condition)
  - [search_strings](#search_strings)
- [Advanced Analysis Tools](#advanced-analysis-tools)
  - [detect_anomalies](#detect_anomalies)
  - [get_match_phases](#get_match_phases)
  - [find_peaks](#find_peaks)
  - [rate_of_change](#rate_of_change)
  - [time_correlate](#time_correlate)
  - [analyze_swerve](#analyze_swerve)
  - [power_analysis](#power_analysis)
  - [can_health](#can_health)
  - [compare_matches](#compare_matches)
  - [get_code_metadata](#get_code_metadata)
  - [moi_regression](#moi_regression)
- [TBA Integration](#tba-integration)
  - [get_tba_status](#get_tba_status)
  - [get_tba_match_data](#get_tba_match_data)
- [Export Tools](#export-tools)
  - [export_csv](#export_csv)
  - [generate_report](#generate_report)
- [FRC Domain-Specific Tools](#frc-domain-specific-tools)
  - [get_ds_timeline](#get_ds_timeline)
  - [analyze_vision](#analyze_vision)
  - [profile_mechanism](#profile_mechanism)
  - [analyze_auto](#analyze_auto)
  - [analyze_cycles](#analyze_cycles)
  - [analyze_replay_drift](#analyze_replay_drift)
  - [analyze_loop_timing](#analyze_loop_timing)
  - [analyze_can_bus](#analyze_can_bus)
  - [predict_battery_health](#predict_battery_health)
- [RevLog Tools](#revlog-tools)
  - [list_revlog_signals](#list_revlog_signals)
  - [get_revlog_data](#get_revlog_data)
  - [sync_status](#sync_status)
  - [set_revlog_offset](#set_revlog_offset)
  - [wait_for_sync](#wait_for_sync)

---

## Discovery Tools

These tools help LLM agents discover and effectively use the server's capabilities. **Call `get_server_guide` first** when starting a new analysis session to understand what tools are available.

### `get_server_guide`
Get a comprehensive overview of all server capabilities, organized by category with usage guidance and anti-patterns to avoid.

**IMPORTANT:** Call this tool first to understand what analysis capabilities are available. This server has 49+ specialized tools—don't write custom analysis code when a built-in tool already exists.

**Parameters:**
- `category` (optional): Filter by category: `core`, `query`, `statistics`, `robot_analysis`, `frc_domain`, `export`, `tba`, `revlog`, `discovery`
- `include_examples` (optional): Include example use cases for each tool (default: true)

**Returns:** Structured overview including:
- `overview`: Server name, version, total tools, purpose
- `critical_guidance`: Key anti-patterns to avoid (e.g., "NEVER compute statistics manually")
- `limitations`: Concurrency constraints (NOT SAFE FOR CONCURRENT USE)
- `categories`: Array of tool categories with descriptions, anti-patterns, and tool details
- `common_workflows`: Step-by-step workflows for common analysis tasks

**Example Response:**
```json
{
  "success": true,
  "overview": {
    "server_name": "wpilog-mcp",
    "version": "0.6.1",
    "total_tools": 49,
    "purpose": "Parse and analyze FRC robot telemetry logs"
  },
  "critical_guidance": {
    "primary_rule": "ALWAYS check for a built-in tool before writing custom analysis code",
    "statistics_tip": "NEVER compute mean/std/percentiles manually—use get_statistics",
    "tba_tip": "To get match scores: use list_available_logs or get_tba_match_data"
  },
  "limitations": {
    "concurrency": "NOT SAFE FOR CONCURRENT USE. Execute tool calls sequentially.",
    "single_session": "Designed for single-client use"
  },
  "categories": [...]
}
```

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
    "2. load_log - Load the specific match",
    "3. power_analysis - Check for brownouts and current peaks",
    "4. find_condition - Find exact timestamps of voltage drops"
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

**Supported filename patterns:**
- Match types: `qm`/`q` (Qualification), `pm`/`p` (Practice), `sf` (Semifinal), `f` (Final), `em`/`e` (Elimination)
- Modes: `sim`/`simulation` (Simulation), `replay` (Replay)
- Event codes: 2-6 letter codes like `VADC`, `DCMP`, `CMPTX`
- Examples:
  - `2024vadc_qm42.wpilog` → "VADC Qualification 42"
  - `sim_test.wpilog` → "Simulation"
  - `replay_2024vadc_qm42.wpilog` → "VADC Qualification 42 Replay"
  - `2024dcmp_f1_sim.wpilog` → "DCMP Final 1 Simulation"

### `load_log`
Load a WPILOG file for analysis.

**Parameters:**
- `path` (required): Path to the WPILOG file

**Returns:** Log summary including entry count and time range

**Example Response:**
```json
{
  "success": true,
  "path": "/Users/team2363/logs/match.wpilog",
  "entry_count": 156,
  "time_range_sec": {
    "start": 0.0,
    "end": 154.32,
    "duration": 154.32
  }
}
```

### `list_entries`
List all entries in the currently loaded log file.

**Parameters:**
- `pattern` (optional): Filter entries by name pattern (substring match)

**Returns:** List of entries with name, type, and sample count

### `get_entry_info`
Get detailed information about a specific entry.

**Parameters:**
- `name` (required): The entry name (e.g., `/Drive/Odometry/Pose`)

**Returns:** Entry metadata, sample count, time range, and sample values

### `read_entry`
Read values from an entry with time range filtering and pagination.

**Parameters:**
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

### `search_entries`
Search for entries matching criteria.

**Parameters:**
- `type_filter` (optional): Filter by type (e.g., `Pose3d`, `double`)
- `name_contains` (optional): Filter by name substring
- `min_samples` (optional): Minimum sample count

**Returns:** Matching entries sorted by sample count

### `get_statistics`
Get statistics for a numeric entry.

**Parameters:**
- `name` (required): The entry name

**Returns:** Statistics including min, max, mean, median, std_dev

**Example Response:**
```json
{
  "success": true,
  "name": "/Robot/BatteryVoltage",
  "type": "double",
  "statistics": {
    "count": 7716,
    "min": 11.23,
    "max": 12.89,
    "mean": 12.34,
    "median": 12.41,
    "std_dev": 0.28,
    "variance": 0.078
  }
}
```

### `compare_entries`
Compare two entries (useful for RealOutputs vs ReplayOutputs).

**Parameters:**
- `name1` (required): First entry name
- `name2` (required): Second entry name

**Returns:** Comparison of sample counts and value statistics

### `get_types`
Get all data types used in the log file.

**Returns:** Types with entry counts and entry names

### `list_struct_types`
List all supported WPILib struct types organized by category. Useful for discovering what struct types can be decoded and what fields they contain.

**Parameters:** None

**Returns:** Struct types organized into categories (geometry, kinematics, vision, autonomous)

**Example Response:**
```json
{
  "success": true,
  "struct_types": {
    "geometry": [
      "Pose2d", "Pose3d", "Transform2d", "Transform3d",
      "Translation2d", "Translation3d", "Rotation2d", "Rotation3d",
      "Twist2d", "Twist3d"
    ],
    "kinematics": [
      "ChassisSpeeds", "DifferentialDriveWheelSpeeds",
      "MecanumDriveWheelSpeeds", "SwerveModuleState",
      "SwerveModulePosition"
    ],
    "vision": [
      "TargetObservation", "PoseObservation"
    ],
    "autonomous": [
      "SwerveSample"
    ]
  },
  "_execution_time_ms": 2
}
```

**Use Case:** When exploring a new log file, use this tool to see what struct types are available for decoding. Each struct type is automatically decoded into its component fields when read.

### `health_check`
Get system health status including JVM memory usage, loaded log count, cache memory estimate, and TBA availability. Useful for monitoring server performance and resource usage.

**Parameters:** None

**Returns:** System status, comprehensive memory statistics with estimation accuracy, loaded logs count, and TBA availability

**Example Response:**
```json
{
  "success": true,
  "status": "OK",
  "loaded_logs": 3,
  "tba_available": true,
  "memory_stats": {
    "estimatedMemoryMb": 245,
    "heapUsedMb": 512,
    "heapMaxMb": 4096,
    "heapFreeMb": 1536,
    "heapUtilization": "25.0%",
    "estimationAccuracy": "0.48",
    "loadedLogCount": 3
  },
  "_execution_time_ms": 5
}
```

**Response Fields:**
- `status`: Always "OK" if the tool executes successfully
- `loaded_logs`: Number of logs currently cached in memory
- `tba_available`: Whether The Blue Alliance API is configured
- `memory_stats`: Comprehensive memory analysis including:
  - `estimatedMemoryMb`: Memory usage calculated from heuristics
  - `heapUsedMb`: Actual JVM heap memory in use
  - `heapMaxMb`: Maximum heap memory available
  - `heapFreeMb`: Free heap memory remaining
  - `heapUtilization`: Percentage of total heap currently used
  - `estimationAccuracy`: Ratio of estimated to actual usage (closer to 1.0 = more accurate)
  - `loadedLogCount`: Number of logs in cache
- `_execution_time_ms`: Tool execution time in milliseconds

**Use Case:** Use this tool periodically during long analysis sessions to monitor memory usage. The `disk_cache` section shows persistent cache status. If memory is getting low, use `unload_log` or `unload_all_logs` to free resources.

### `get_game_info`
Get year-specific FRC game information (match timing, scoring values, field geometry, game pieces, and analysis hints). Use this to understand the context of a log file: what the match phases are, what scoring actions look like, and what mechanisms to expect. Defaults to the current season if no year is specified.

**Parameters:**
- `season` (optional): FRC season year (e.g., 2026). Defaults to current year.

**Returns:** Game name, match timing with shift details, scoring values, field geometry, game pieces, typical mechanisms, and analysis hints. Returns error with available seasons list if the requested season is not found.

---

## Multi-Log Management

### `list_loaded_logs`
List all currently loaded log files with detailed metadata.

**Parameters:** None

**Returns:** List of loaded logs with entry count, duration, estimated memory usage, and active status

**Example Response:**
```json
{
  "success": true,
  "loaded_log_count": 3,
  "logs": [
    {
      "path": "/Users/team2363/logs/2026vadc_qm42.wpilog",
      "entry_count": 156,
      "duration_sec": 154.32,
      "estimated_memory_mb": 12.5,
      "is_active": true
    },
    {
      "path": "/Users/team2363/logs/2026vadc_qm68.wpilog",
      "entry_count": 148,
      "duration_sec": 149.87,
      "estimated_memory_mb": 11.8,
      "is_active": false
    }
  ]
}
```

**Use Case:** Monitor which logs are currently loaded and their memory footprint. Use with `set_active_log` to switch between loaded logs, or `unload_log` to free memory.

### `set_active_log`
Set which loaded log to use for queries.

**Parameters:**
- `path` (required): Path to a loaded log file

### `unload_log`
Unload a log file from memory to free resources. Use this when done analyzing a log to prevent memory exhaustion during long sessions.

**Parameters:**
- `path` (required): Path to the log file to unload

**Returns:** Confirmation with remaining loaded log count

### `unload_all_logs`
Unload all log files from memory. Use this to clean up memory during long analysis sessions.

**Parameters:** None

**Returns:** Count of logs unloaded

---

## Search Tools

### `find_condition`
Find timestamps where a numeric entry crosses a threshold. Useful for questions like "When did battery voltage drop below 11V?"

**Parameters:**
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
Search string entries for text patterns. Useful for finding errors, warnings, or specific messages in console output logs.

**Parameters:**
- `pattern` (required): Text pattern to search for (case-insensitive substring match)
- `entry_pattern` (optional): Filter which entries to search (e.g., `Console` or `Output`)
- `limit` (optional): Maximum matches to return (default 50)

**Returns:** Matching strings with timestamps and entry names

**Example Response:**
```json
{
  "success": true,
  "pattern": "error",
  "match_count": 2,
  "matches": [
    {
      "timestamp_sec": 12.34,
      "entry": "/SmartDashboard/Console",
      "value": "Error: CAN timeout on device 5"
    },
    {
      "timestamp_sec": 45.67,
      "entry": "/SmartDashboard/Console",
      "value": "Error: Vision target not found"
    }
  ]
}
```

---

## Advanced Analysis Tools

### `detect_anomalies`
Detect anomalies (outliers) in numeric data using the IQR (Interquartile Range) method with proper linear percentile interpolation. Values outside Q1 - 1.5xIQR or Q3 + 1.5xIQR are flagged as outliers. Optionally detects sudden spikes (large percentage changes between consecutive samples).

**Note:** IQR calculation uses linear interpolation between data points for accurate percentile estimates, ensuring reliable outlier detection even with small datasets.

**Parameters:**
- `name` (required): Entry name to analyze (must be numeric type)
- `iqr_multiplier` (optional): Multiplier for IQR bounds (default 1.5). Use 3.0 for extreme outliers only
- `spike_threshold` (optional): Detect spikes larger than this percentage change (e.g., 50 for 50% change)
- `limit` (optional): Maximum anomalies to return (default 50)

**Returns:** IQR bounds (Q1, Q3, lower/upper bounds) and list of anomalies with timestamps, values, and type

**Example Response:**
```json
{
  "success": true,
  "name": "/Robot/BatteryVoltage",
  "anomaly_count": 3,
  "iqr_bounds": {
    "q1": 11.8,
    "q3": 12.4,
    "iqr": 0.6,
    "lower_bound": 10.9,
    "upper_bound": 13.3
  },
  "anomalies": [
    {
      "timestamp_sec": 45.23,
      "value": 10.5,
      "type": "below_lower_bound",
      "bound": 10.9
    },
    {
      "timestamp_sec": 89.1,
      "value": 9.8,
      "type": "below_lower_bound",
      "bound": 10.9
    }
  ]
}
```

### `get_match_phases`
Detect match phases from DriverStation/FMS data in the log. Phases are derived from actual DS mode transitions (Enabled, Autonomous, Teleop), not hardcoded durations, so they reflect the real match regardless of game year.

**How it works:**
- Looks for DriverStation entries containing "Enabled" and "Autonomous" mode flags
- Detects auto→teleop transition from the actual boolean state change
- Match start/end come from enable/disable transitions
- If DriverStation data is not present in the log, returns a warning instead of guessing

**Parameters:** None

**Returns:** Time ranges for detected phases, match duration, and source indicator

**Data Quality Notes:**
- Phases are only reported when supported by actual log data — no assumptions about game timing
- The `source` field indicates where phase data came from ("DriverStation" or "none")
- If only enable/disable is found but not autonomous/teleop mode, reports "enabled" phase with a warning

**Example Response:**
```json
{
  "success": true,
  "log_duration": 160.5,
  "phases": {
    "autonomous": {
      "start": 3.2,
      "end": 18.2,
      "duration": 15.0,
      "description": "Autonomous"
    },
    "teleop": {
      "start": 18.2,
      "end": 153.2,
      "duration": 135.0,
      "description": "Teleop"
    }
  },
  "source": "DriverStation",
  "match_duration": 150.0,
  "auto_duration": 15.0,
  "teleop_duration": 135.0
}
```

### `find_peaks`
Find local maxima and minima (peaks and valleys) in numeric data. Uses a simple algorithm that compares each point to its immediate neighbors. Peaks are sorted by height difference (how much they stand out from neighboring values).

**Parameters:**
- `name` (required): Entry name to analyze (must be numeric type)
- `type` (optional): Type of peaks to find: `max` (maxima only), `min` (minima only), or `both` (default)
- `min_height_diff` (optional): Minimum height difference from neighbors to count as a peak. Filters out noise
- `limit` (optional): Maximum peaks to return per type (default 20)

**Returns:** Lists of maxima and/or minima with height difference from neighbors

**Example Response:**
```json
{
  "success": true,
  "name": "/Robot/BatteryVoltage",
  "total_data_points": 7716,
  "maxima_count": 5,
  "maxima": [
    {
      "timestamp_sec": 2.34,
      "value": 12.89,
      "height_diff": 0.45,
      "index": 117
    }
  ],
  "minima_count": 5,
  "minima": [
    {
      "timestamp_sec": 89.45,
      "value": 10.23,
      "height_diff": 1.2,
      "index": 4472
    }
  ]
}
```

### `rate_of_change`
Compute rate of change (derivative) of numeric data over time. Calculates dv/dt for each sample. Useful for computing velocity from position, acceleration from velocity, or detecting rapid changes in any value.

**Parameters:**
- `name` (required): Entry name to analyze (must be numeric type)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `window_size` (optional): Number of samples to average for smoothing (default 1 = no smoothing). Higher values reduce noise but may miss short events
- `limit` (optional): Maximum samples to return (default 100)

**Returns:** Derivative values with timestamp and statistics (min, max, avg rate)

**Example Response:**
```json
{
  "success": true,
  "name": "/Drive/Velocity",
  "window_size": 1,
  "samples_analyzed": 7716,
  "statistics": {
    "min_rate": -5.2,
    "max_rate": 8.1,
    "avg_rate": 0.02
  },
  "returned_count": 100,
  "samples": [
    {
      "timestamp_sec": 0.04,
      "value": 0.5,
      "rate": 25.0
    }
  ]
}
```

### `time_correlate`
Compute Pearson correlation coefficient between two numeric entries. Aligns samples by timestamp (10ms buckets) and calculates correlation. Values range from -1 (perfect negative correlation) to +1 (perfect positive correlation).

**Interpretation:**
- |r| >= 0.9: Very strong correlation
- |r| >= 0.7: Strong correlation
- |r| >= 0.5: Moderate correlation
- |r| >= 0.3: Weak correlation
- |r| < 0.3: No significant correlation

**Parameters:**
- `name1` (required): First entry name (must be numeric)
- `name2` (required): Second entry name (must be numeric)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds

**Returns:** Correlation coefficient with interpretation and sample count

**Example Response:**
```json
{
  "success": true,
  "entry1": "/Robot/BatteryVoltage",
  "entry2": "/Drive/MotorCurrent",
  "correlation": -0.81,
  "paired_samples": 5432,
  "interpretation": "Strong negative correlation"
}
```

**Common correlations in FRC:**
- Battery voltage vs motor current: Strong negative (voltage drops as current increases)
- Drive velocity vs motor power: Strong positive
- Arm position vs arm motor current: Variable (depends on mechanism)

### `analyze_swerve`
Analyze swerve drive module performance. Searches for entries containing SwerveModuleState, SwerveModulePosition, ChassisSpeeds, or entries with "swerve"/"module" in the name. Reports statistics for each module found.

**Parameters:**
- `module_prefix` (optional): Entry path prefix for swerve modules (e.g., `/Drive/Module` or `/Swerve`). If omitted, searches all entries

**Returns:** Lists of found swerve-related entries and per-module statistics (max/avg speed)

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
Analyze power distribution (PDP/PDH) data. Finds battery voltage entries, per-channel current entries, and total current. Assesses brownout risk based on minimum voltage.

**Brownout Risk Levels:**
- **HIGH**: Voltage dropped below brownout threshold
- **MODERATE**: Voltage within 1V of threshold
- **LOW**: Voltage stayed above threshold + 1V

**Parameters:**
- `power_prefix` (optional): Entry path prefix for power data (e.g., `/PDP`, `/PDH`, `/PowerDistribution`)
- `brownout_threshold` (optional): Voltage threshold for brownout warning (default 6.8V for roboRIO 1; set to 6.3V for roboRIO 2)

**Returns:** Voltage analysis (min/max/avg), per-channel current statistics sorted by max current, and brownout risk assessment

**Example Response:**
```json
{
  "success": true,
  "voltage_analysis": {
    "entry": "/Robot/BatteryVoltage",
    "min_voltage": 10.23,
    "max_voltage": 12.89,
    "avg_voltage": 11.87,
    "samples_below_threshold": 0,
    "brownout_threshold": 6.8,
    "brownout_risk": "LOW"
  },
  "channel_analysis": [
    {
      "entry": "/PDP/Channel8/Current",
      "max_current_A": 45.2,
      "avg_current_A": 12.3,
      "sample_count": 7700
    },
    {
      "entry": "/PDP/Channel12/Current",
      "max_current_A": 38.7,
      "avg_current_A": 8.9,
      "sample_count": 7700
    }
  ],
  "highest_current_channel": "/PDP/Channel8/Current",
  "highest_current_A": 45.2,
  "peak_total_current_A": 120.5
}
```

### `can_health`
Analyze CAN bus health by searching for CAN-related entries and string entries containing CAN error messages (timeout, error, fault). Provides a health assessment based on error count.

**Health Levels:**
- **GOOD**: No CAN errors detected
- **FAIR**: Few CAN errors (< 10)
- **CONCERNING**: Multiple CAN errors (10-50)
- **POOR**: Many CAN errors (> 50)

**Parameters:** None

**Returns:** List of CAN entries, error counts by entry, sample error messages, and health assessment

**Example Response:**
```json
{
  "success": true,
  "can_entries": [
    "/CAN/Utilization",
    "/CAN/BusOff",
    "/CAN/TxErrors"
  ],
  "can_entry_count": 3,
  "error_counts_by_entry": {
    "/SmartDashboard/Console": 5
  },
  "total_can_errors": 5,
  "sample_errors": [
    {
      "timestamp_sec": 34.5,
      "entry": "/SmartDashboard/Console",
      "message": "CAN timeout on device 5"
    }
  ],
  "health_assessment": "FAIR - Few CAN errors detected"
}
```

### `compare_matches`
Compare statistics for an entry across multiple loaded log files. Useful for comparing robot performance across different matches. Requires at least 2 logs to be loaded.

**Parameters:**
- `name` (required): Entry name to compare across logs

**Returns:** Statistics (min, max, mean) for each loaded log file

**Example Response:**
```json
{
  "success": true,
  "entry": "/Robot/BatteryVoltage",
  "logs_compared": 2,
  "comparisons": [
    {
      "log_path": "/logs/2024vadc_qm8.wpilog",
      "log_filename": "2024vadc_qm8.wpilog",
      "entry_found": true,
      "sample_count": 7716,
      "statistics": {
        "min": 10.5,
        "max": 12.9,
        "mean": 11.8
      }
    },
    {
      "log_path": "/logs/2024vadc_qm64.wpilog",
      "log_filename": "2024vadc_qm64.wpilog",
      "entry_found": true,
      "sample_count": 8234,
      "statistics": {
        "min": 9.8,
        "max": 12.8,
        "mean": 11.2
      }
    }
  ]
}
```

### `get_code_metadata`
Extract code metadata from the log. WPILib and AdvantageKit typically log Git information at startup. This tool searches for entries containing GitSHA, GitBranch, GitDirty, BuildDate, RuntimeType, ProjectName, MavenGroup, MavenName, and Version.

**Parameters:** None

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

---

## TBA Integration

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
  "available": true,
  "status": "configured",
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
  "available": false,
  "status": "not_configured",
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
- `name` (required): Entry name to export
- `output_path` (required): Path for output CSV file (will be created or overwritten)
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

**Parameters:** None

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

## FRC Domain-Specific Tools

### `get_ds_timeline`
Generate a chronological timeline of critical robot events. Detects enable/disable transitions, match phase changes, brownout events, joystick disconnects, and errors/warnings.

**Parameters:**
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `brownout_threshold` (optional): Voltage threshold for brownout detection (default: 6.8V for roboRIO 1; use 6.3V for roboRIO 2)

**Returns:** Chronologically sorted list of events with category, type, timestamp, and source entry

**Event Categories:**
- `robot_state`: ENABLED, DISABLED
- `match_phase`: AUTO_START, TELEOP_START
- `controller`: JOYSTICK_CONNECTED, JOYSTICK_DISCONNECTED
- `power`: BROWNOUT_START, BROWNOUT_END
- `error`: ERROR (from string entries containing "error", "exception", "fault")
- `warning`: WARNING (from entries containing "warning", "overrun", "watchdog")

**Example Response:**
```json
{
  "success": true,
  "event_count": 12,
  "summary": {
    "robot_state": 4,
    "match_phase": 2,
    "power": 2,
    "error": 4
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
      "voltage": 6.8,
      "source": "/Robot/BatteryVoltage"
    }
  ]
}
```

### `analyze_vision`
Analyze vision system reliability and pose estimation quality. Detects target acquisition rate, flicker (rapid loss/reacquisition), and sudden pose jumps ("teleportation"). Enhanced with pose jump detection to identify unreliable vision estimates that can cause odometry drift.

**Parameters:**
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
- `mechanism_name` (required): Name or prefix of mechanism to analyze (e.g., "Elevator", "Arm", "Shooter")
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `settling_threshold` (optional): Position error threshold for settling time calculation (default: 0.02 or 2%)
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
```

### `analyze_auto`
Analyze autonomous routine performance including path following error RMSE, maximum deviation, and completion timing. Enhanced with detailed path following metrics for tuning trajectory following controllers.

**Parameters:**
- `auto_prefix` (optional): Entry path prefix for auto data (auto-detect if not specified)
- `auto_duration` (optional): Expected autonomous duration in seconds (default: 15)

**Searches for entries containing:**
- Auto selector: `chooser`, `auto` + `select`
- Trajectory: `trajectory`, `targetPose`, `desiredPose`, `setpointPose`
- Actual pose: `odometry` + `pose`

**Returns:** Selected routine name, path following error RMSE, max error, and timing analysis

**Path Following Error Calculation:** Computes RMSE (Root Mean Square Error) between desired and actual robot position throughout autonomous. Lower RMSE indicates better path following. Typical values:
- **< 0.05m**: Excellent path following
- **0.05-0.15m**: Good path following (acceptable for most games)
- **> 0.15m**: Poor path following - check controller tuning or wheel slippage

**Example Response:**
```json
{
  "success": true,
  "auto_start_time": 0.02,
  "auto_end_time": 15.02,
  "selected_routine": "ThreePieceAmp",
  "entries": {
    "trajectory": "/Auto/TargetPose",
    "actual_pose": "/Drive/Odometry/Pose"
  },
  "path_following_error": {
    "rmse_meters": 0.08,
    "max_error_meters": 0.23,
    "sample_count": 750
  }
}
```

### `analyze_cycles`
Analyze game piece handling cycle times with flexible cycle detection modes, data quality warnings, and comprehensive analysis. Enhanced with configurable cycle definitions, time filtering, case-insensitive matching, and incomplete cycle detection.

**Cycle Detection Modes:**
- **start_to_start**: Measures from one occurrence of `cycle_start_state` to the next (default). Useful for regular repeating patterns.
- **start_to_end**: Measures from `cycle_start_state` to `cycle_end_state`. More semantically correct for workflows with distinct start and end states.

**Parameters:**
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
- `tolerance` (optional): Numeric tolerance for comparison (default: 1e-6)
- `max_divergences` (optional): Maximum divergent entries to report (default: 50)

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
- `loop_time_entry` (optional): Entry name for loop time (auto-detects common patterns if not specified)
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

### `analyze_can_bus`
Analyze CAN bus health, utilization, and error rates. Monitors bus loading and detects communication errors that can cause device timeouts or unreliable sensor readings.

**Parameters:**
- `utilization_threshold` (optional): Bus utilization percentage threshold for warnings (default: 80%)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds

**Searches for entries containing:**
- Bus utilization: `CANBus/Utilization`, `CAN/Usage`, `CAN/BusPercent`
- Transmit errors: `CAN/Error/Tx`, `CANBus/TxError`, `CAN/TEC`
- Receive errors: `CAN/Error/Rx`, `CANBus/RxError`, `CAN/REC`
- Bus off events: `CAN/BusOff`, `CANBus/Status`

**Returns:** Bus utilization analysis, error counts by type, health assessment, and recommendations

**Example Response:**
```json
{
  "success": true,
  "utilization": [
    {
      "entry": "/CANBus/Utilization",
      "avg_percent": 45.2,
      "max_percent": 78.5,
      "min_percent": 15.3,
      "sample_count": 7500,
      "exceeded_threshold": false
    }
  ],
  "errors": [
    {
      "entry": "/CANBus/Error/Tx",
      "type": "transmit",
      "error_count": 15,
      "first_error_timestamp": 23.4,
      "last_error_timestamp": 89.2
    },
    {
      "entry": "/CANBus/Error/Rx",
      "type": "receive",
      "error_count": 8,
      "first_error_timestamp": 34.5,
      "last_error_timestamp": 92.1
    }
  ],
  "total_errors": 23,
  "health_assessment": "GOOD - Low error rate, utilization within limits",
  "recommendations": [
    "Monitor CAN utilization if adding more devices",
    "Consider reducing status frame rates if utilization approaches 80%"
  ],
  "_execution_time_ms": 28
}
```

**Health Assessment Levels:**
- **EXCELLENT**: No errors, utilization < 50%
- **GOOD**: Few errors (< 10), utilization < 80%
- **FAIR**: Some errors (10-100), utilization 80-90%
- **POOR**: Many errors (> 100) or utilization > 90%

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

### `predict_battery_health`
Analyze battery voltage and current draw to predict brownout risk and estimate battery health. Returns a health score (0–100), risk level, voltage statistics, recovery analysis, and actionable recommendations.

**Parameters:**
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

**Bundled game data:** 2026 REBUILT (from official game manual TU17)

**Returns:** Match timing (auto/teleop/endgame durations with shift breakdown), scoring values (fuel, tower, ranking points), field geometry, game pieces, typical mechanisms, and analysis hints for LLM context.

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

## Response Metadata

All analytical tools (15+) include two metadata sections in their responses that help LLMs calibrate their confidence when interpreting results.

### `data_quality`

Computed from the primary data entry's timestamped values. Includes:

| Field | Description |
|-------|-------------|
| `sample_count` | Number of data points |
| `time_span_seconds` | Duration of the data |
| `gap_count` | Number of data gaps (intervals > 5× the median sample interval) |
| `max_gap_ms` | Largest gap in milliseconds (only present if gaps > 0) |
| `nan_filtered` | Count of NaN/Infinity values filtered (only present if > 0) |
| `effective_sample_rate_hz` | Actual sample rate based on median interval |
| `quality_score` | Composite score 0.0–1.0 (see formula below) |

**Quality Score Formula:**
```
score = 1.0
  - 0.3 × min(gap_count / 20, 1)       // Gaps: 20+ gaps = full penalty
  - 0.2 × min(nan_count / total, 1)     // NaN: ratio of non-finite values
  - 0.3 × (n<100 ? 1 : n<500 ? 0.5 : 0) // Samples: statistical confidence
  - 0.2 × min(jitter / median_dt, 1)    // Jitter: timing irregularity
```

**Confidence levels** derived from quality score:
- `"high"` (> 0.8): Reliable data, results can be stated with confidence
- `"moderate"` (0.5–0.8): Usable data, note caveats in analysis
- `"low"` (< 0.5): Poor data, results should be treated as preliminary

### `server_analysis_directives`

Auto-generated LLM guidance based on data quality issues detected:

| Field | Description |
|-------|-------------|
| `confidence_level` | "high", "moderate", or "low" |
| `sample_context` | Human-readable summary (e.g., "Based on 4500 samples over 150.0 seconds") |
| `interpretation_guidance` | Array of warnings about data quality issues detected |
| `suggested_followup` | Array of recommended next tools to call |

Auto-generated guidance triggers:
- Sample count < 100 → "Low sample count" warning
- Gap count > 5 → "Data gaps detected" warning
- NaN values present → "Non-finite values filtered" warning
- Time span < 10 seconds → "Short time span" warning

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
1. load_log("/logs/match.wpilog")           # Auto-syncs revlogs in same dir
2. sync_status()                             # Check sync confidence
3. list_revlog_signals()                     # See available signals
4. get_revlog_data(signal_key="REV/SparkMax_1/appliedOutput", start_time=15.0, end_time=30.0)
5. compare with read_entry(name="/drive/frontLeft/output", start_time=15.0, end_time=30.0)
```

### `set_revlog_offset`
Manually set the synchronization offset for a REV log file, overriding automatic synchronization. Use this when automatic sync fails, produces incorrect results, or when you have determined the correct offset through other means (e.g., by visually aligning a known event in both logs).

**Parameters:**
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
Wait for background RevLog synchronization to complete. RevLog synchronization runs asynchronously after `load_log` returns, so revlog data may not be immediately available. Call this tool if you need revlog data right away. Returns instantly if sync is already done or no revlogs are present.

**Parameters:**
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
- After `load_log`, when you need to immediately query revlog signals
- When `sync_status` or `list_revlog_signals` shows `sync_in_progress: true`
- Not needed if you call other tools first — sync usually completes within a few seconds
