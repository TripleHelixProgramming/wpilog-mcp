# wpilog-mcp Tool Reference

Complete documentation for all 34 tools available in wpilog-mcp.

## Table of Contents

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
- [TBA Integration](#tba-integration)
  - [get_tba_status](#get_tba_status)
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

---

## Multi-Log Management

### `list_loaded_logs`
List all currently loaded log files.

**Returns:** Loaded log paths and which is active

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
Detect anomalies (outliers) in numeric data using the IQR (Interquartile Range) method. Values outside Q1 - 1.5xIQR or Q3 + 1.5xIQR are flagged as outliers. Optionally detects sudden spikes (large percentage changes between consecutive samples).

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
Auto-detect FRC match phases based on standard timing. Attempts to find the actual match start from DriverStation "Enabled" entries; falls back to log timestamps if not found.

**Phases:**
- **Autonomous**: First 15 seconds (0-15s from match start)
- **Teleop**: 15-135 seconds from match start
- **Endgame**: Last 15 seconds of teleop (120-135s from match start)

**Parameters:** None

**Returns:** Time ranges for each phase with start/end timestamps and durations

**Example Response:**
```json
{
  "success": true,
  "log_start": 0.0,
  "log_end": 154.32,
  "log_duration": 154.32,
  "phases": {
    "autonomous": {
      "start": 0.0,
      "end": 15.0,
      "duration": 15.0,
      "description": "Autonomous period (first 15 seconds)"
    },
    "teleop": {
      "start": 15.0,
      "end": 135.0,
      "duration": 120.0,
      "description": "Teleop period (15-135 seconds)"
    },
    "endgame": {
      "start": 120.0,
      "end": 135.0,
      "duration": 15.0,
      "description": "Endgame period (last 15 seconds)"
    }
  },
  "hint": "Use read_entry with start_time/end_time to analyze data within a specific phase"
}
```

### `find_peaks`
Find local maxima and minima (peaks and valleys) in numeric data. Uses a simple algorithm that compares each point to its immediate neighbors. Peaks are sorted by prominence (how much they stand out from surrounding values).

**Parameters:**
- `name` (required): Entry name to analyze (must be numeric type)
- `type` (optional): Type of peaks to find: `max` (maxima only), `min` (minima only), or `both` (default)
- `prominence` (optional): Minimum prominence (height difference from neighbors) to count as a peak. Filters out noise
- `limit` (optional): Maximum peaks to return per type (default 20)

**Returns:** Lists of maxima and/or minima sorted by prominence (most prominent first)

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
      "prominence": 0.45,
      "index": 117
    }
  ],
  "minima_count": 5,
  "minima": [
    {
      "timestamp_sec": 89.45,
      "value": 10.23,
      "prominence": 1.2,
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
- **HIGH**: Voltage dropped below brownout threshold (default 7.0V)
- **MODERATE**: Voltage within 1V of threshold
- **LOW**: Voltage stayed above threshold + 1V

**Parameters:**
- `power_prefix` (optional): Entry path prefix for power data (e.g., `/PDP`, `/PDH`, `/PowerDistribution`)
- `brownout_threshold` (optional): Voltage threshold for brownout warning (default 7.0V)

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
    "brownout_threshold": 7.0,
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
- `brownout_threshold` (optional): Voltage threshold for brownout detection (default: 7.0V)

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
Analyze vision system reliability and pose estimation quality. Detects target acquisition rate, flicker (rapid loss/reacquisition), and sudden pose jumps ("teleportation").

**Parameters:**
- `vision_prefix` (optional): Entry path prefix for vision data (auto-detect if not specified)
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `jump_threshold` (optional): Distance threshold for pose jump detection in meters (default: 0.5)
- `flicker_window` (optional): Time window for flicker detection in seconds (default: 0.5)

**Searches for entries containing:**
- Target valid: `hasTarget`, `tv`, `targetValid`, `hasResult`
- Vision pose: `visionPose`, `estimatedPose`, `botPose`, `robotPose`
- Odometry: `odometry` + `pose`

**Returns:** Target acquisition analysis, flicker detection, and pose jump analysis

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
Analyze closed-loop mechanism performance including following error, stall detection, and temperature profiling.

**Parameters:**
- `mechanism_name` (required): Name or prefix of mechanism to analyze (e.g., "Elevator", "Arm")
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `settling_threshold` (optional): Position error threshold for settling (default: 0.02)
- `stall_current_threshold` (optional): Current threshold for stall detection (default: 30A)

**Searches for entries containing the mechanism name plus:**
- Setpoint: `setpoint`, `goal`, `target`, `desired`
- Measurement: `position`, `measurement`, `actual`
- Velocity: `velocity`, `speed`
- Output: `output`, `voltage`, `dutyCycle`
- Current: `current`
- Temperature: `temp`, `temperature`

**Returns:** Following error RMSE, stall events, and temperature analysis

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
  "stall_detection": {
    "stall_events": 2,
    "samples": [
      {
        "timestamp": 45.23,
        "current": 35.5,
        "velocity": 0.01
      }
    ]
  },
  "temperature": {
    "max_temperature_c": 58.2,
    "avg_temperature_c": 42.1,
    "overheat_warning": false
  }
}
```

### `analyze_auto`
Analyze autonomous routine performance including path following error and completion timing.

**Parameters:**
- `auto_prefix` (optional): Entry path prefix for auto data (auto-detect if not specified)
- `auto_duration` (optional): Expected autonomous duration in seconds (default: 15)

**Searches for entries containing:**
- Auto selector: `chooser`, `auto` + `select`
- Trajectory: `trajectory`, `targetPose`, `desiredPose`, `setpointPose`
- Actual pose: `odometry` + `pose`

**Returns:** Selected routine name, path following RMSE, and timing analysis

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
Analyze game piece handling cycle times. Counts cycles, calculates timing statistics, and breaks down time by state.

**Parameters:**
- `state_entry` (required): Entry name for superstructure/mechanism state
- `start_state` (optional): State that marks cycle start (default: first state seen)
- `start_time` (optional): Start timestamp (default: 15s for teleop start)
- `end_time` (optional): End timestamp (default: 135s for teleop end)

**Returns:** Cycle count, timing statistics, and time breakdown by state

**Example Response:**
```json
{
  "success": true,
  "state_entry": "/Superstructure/State",
  "start_state": "INTAKING",
  "cycle_count": 8,
  "cycle_times": {
    "average_sec": 12.5,
    "min_sec": 9.2,
    "max_sec": 18.1,
    "cycles_per_minute": 4.0
  },
  "cycles": [
    {"start": 15.2, "end": 24.8, "duration": 9.6},
    {"start": 24.8, "end": 37.2, "duration": 12.4}
  ],
  "time_by_state": {
    "INTAKING": {"time_sec": 25.0, "percentage": 25.0},
    "TRANSITING": {"time_sec": 30.0, "percentage": 30.0},
    "AIMING": {"time_sec": 15.0, "percentage": 15.0},
    "SHOOTING": {"time_sec": 10.0, "percentage": 10.0},
    "IDLE": {"time_sec": 20.0, "percentage": 20.0}
  }
}
```

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
