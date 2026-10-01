# Future Enhancement Ideas

Ideas for future versions of wpilog-mcp, grouped by theme and roughly prioritized within each section. Where part of an idea already exists, the section says so.

---

## 1. Performance & Scalability

### 1.6 FFT-Based Cross-Correlation
Priority: Low. Complexity: High.

Revlog sync searches lags by brute force: for each lag in a ±60 s window (12,001 lags at 100 Hz) it computes a full Pearson correlation over the overlap. An FFT-based normalized cross-correlation would be O(n log n), with prefix sums for each lag's local mean and variance.

The cost is bounded today. A signal longer than 600 s at 100 Hz is trimmed to its highest-variance window, a coarse pass at a tenth of the rate ranks the candidate pairs, and at most five pairs are refined at full rate. FFT would pay off if that 600 s budget were raised or the search window widened. Short signals should keep the sliding window, where FFT overhead is not worth it.

---

## 2. Data Quality & Accuracy

### 2.2 Multi-Log Temporal Alignment
Priority: Low. Complexity: High.

Align several logs from the same event, such as practice runs, on a common time base, to compare autonomous attempts, track mechanism tuning, or find intermittent issues.

Today `compare_matches` compares one signal's statistics across two logs, each on its own clock. The only alignment between files is REV log to wpilog sync.

---

## 3. FRC Domain Features

### 3.2 Autonomous Routine Library
Priority: Medium. Complexity: Medium.

Build a library of autonomous routine signatures:
- Extract a routine's fingerprint from its pose trajectory
- Match a logged auto to a known routine
- Compare execution with previous runs
- Detect failed or aborted routines

`analyze_auto` already reports the selected routine (from the chooser) and the path-following error of each autonomous period.

### 3.3 Energy Budget Analysis
Priority: Medium. Complexity: Low.

Report how a match used the battery:
- Total energy (Wh) per match
- Energy by mechanism, where per-motor current is logged
- Peak power events
- Regenerative braking

Nothing integrates power today. `power_analysis` reports each channel's peak, minimum, maximum, and average current.

### 3.4 CAN Bus Diagnostics
Priority: Medium. Complexity: Medium.

Partly done. `can_health` splits CAN error lines by the robot's enabled state and reads the bus counters. `analyze_can_bus` reports, per bus, utilization, TEC and REC levels with error-passive excursions, and bus-off and TX-full counts, overall and while enabled. It also counts increases in other entries named with CAN and error, fault, or timeout.

Remaining:
- Identify noisy devices by error rate (console text is not parsed for device IDs)
- Bus-off recovery time (bus-off is only counted)
- Bandwidth utilization by device category

### 3.5 Mechanism Health Tracking
Priority: Low. Complexity: High.

Track mechanism health across many logs:
- Motor temperature trends
- Current draw for constant loads (motor wear)
- Encoder noise (bearing wear)
- Response time degradation

`profile_mechanism` covers one log at a time, and `compare_matches` compares one signal across two logs.

---

## 4. Integration & Ecosystem

### 4.1 PathPlanner/Choreo Integration
Priority: High. Complexity: Medium.

Read PathPlanner `.path` and Choreo `.traj` files and compare the planned path with what the robot did, overlaying the two and marking problem segments.

Following error from logged poses already exists. `analyze_auto` reports `path_following_error` between the logged setpoint and actual poses (`path_setpoint_entry`, `path_actual_entry`), and `compare_poses` gives the distance and the along-track and cross-track error between any two pose streams, with the times of the largest errors. What remains is reading the path files themselves.

### 4.2 AdvantageScope Integration
Priority: Medium. Complexity: Medium.

Hand analysis results to AdvantageScope for visualization.

#### 4.2.1 Layout File Generation

Generate AdvantageScope layout files (`.json`) that set up the views an analysis calls for.

Layout file structure (reverse-engineered from AdvantageScope source):
```json
{
  "version": "26.0.0",
  "hubs": [{
    "x": 0, "y": 0, "width": 1200, "height": 800,
    "state": {
      "sidebar": { "width": 300, "expanded": ["/Drive", "/Power"] },
      "tabs": {
        "selected": 0,
        "tabs": [{ "type": 1, "title": "Voltage", "controller": {...} }]
      }
    }
  }],
  "satellites": []
}
```

Tab types:

| Type | Visualization | Use case |
|------|---------------|----------|
| 1 | Line Graph | Voltage, current, velocity over time |
| 3 | 2D Field | Robot poses, trajectories |
| 9 | Swerve | Module states, chassis speeds |

A new tool (say, `open_in_advantagescope`) would build the layout from the analysis at hand. For example, after `power_analysis` finds brownouts, it would lay out voltage and current graphs, write the layout to a temporary file, and tell the user to import it with **File > Import Layout**.

#### 4.2.2 Application Launch

AdvantageScope registers file associations, so it can be launched with a log file:
```bash
open -a AdvantageScope /path/to/log.wpilog  # macOS
AdvantageScope.exe C:\path\to\log.wpilog     # Windows
```

There is no command-line flag to load a layout file at launch, so the user has to import it.

#### 4.2.3 Runtime Control API (Not Currently Available)

AdvantageScope runs an XR server on port 8170 (`/ws` WebSocket) to stream to VR and AR headsets, but it only broadcasts; there is no general control API.

Capabilities that would need an AdvantageScope feature request or pull request:
- `POST /open?file=/path/to/log.wpilog`: open a log file
- `POST /layout`: apply a layout
- `POST /seek?timestamp=12345`: go to a timestamp
- `POST /highlight?entry=/Power/Voltage&start=100&end=200`: highlight a time range

Until then, a file-based handoff works:
1. Write the layout to `~/.wpilog-mcp/advantagescope-layout.json`
2. Launch AdvantageScope with the log file
3. Tell the user to import the layout with **File > Import Layout**

#### 4.2.4 Data Export Formats

Export analysis results in formats AdvantageScope can open:
- MCAP: native AdvantageScope format, includes metadata
- An annotated wpilog: the original log with analysis markers as new entries
- JSON, for web-based visualization or other tools

Today `export_csv` writes one entry as CSV, or returns the rows inline.

### 4.3 Scouting System Integration
Priority: Low. Complexity: Medium.

Connect to team scouting databases:
- Import the match schedule and alliance data
- Correlate robot performance with match outcomes
- Export performance metrics to the scouting database
- Compare with alliance partners and opponents

Match results come from The Blue Alliance today (`get_tba_match_data`, and the `tba` field of `list_available_logs`).

---

## 5. User Experience

### 5.1 Analysis Presets
Priority: Medium. Complexity: Low.

Pre-configured analysis profiles for common scenarios:
- A quick check: battery, CAN, and loop timing
- A match debrief: full match analysis with a phase breakdown
- Autonomous tuning: detailed autonomous performance
- Mechanism debugging: a deep dive on one mechanism

Related pieces exist: `get_server_guide` lists `common_workflows`, `suggest_tools` proposes a workflow for a task, and `generate_report` is a one-call summary (without CAN or loop timing).

### 5.2 Natural Language Queries
Priority: Medium. Complexity: Low (the LLM does most of the work).

Make tool descriptions and errors easier for an LLM to act on.

Partly done. Every result has a `status` (`ok`, `partial`, `not_applicable`, `no_match`, or `error`), with a `reason`, `looked_for`, and a `hint` where they apply, and a misspelled entry or tool name gets suggestions.

Remaining:
- Machine-readable codes on errors (an `error` result carries only a message)
- An actionable suggestion in every error
- Example queries in tool descriptions
- Domain vocabulary in responses

### 5.3 Report Generation
Priority: Medium. Complexity: Medium.

`generate_report` returns a JSON summary. This idea is a human-readable report built from the analysis:
- Formats: Markdown, HTML with embedded charts, or PDF for printing
- Content: a summary (match outcome, key issues), findings by category, and recommendations ranked by impact, worded as evidence the way the tools word their results

### 5.4 Batch Tool Execution
Priority: Medium. Complexity: Low.

Accept a list of tool calls and return all the results in one response, to save round trips in multi-step workflows such as "voltage statistics, current statistics, correlate".

Design:
- A new tool (say, `run_workflow`) takes an ordered list of `{tool, arguments}` pairs
- Results come back as an array in the same order
- The first error stops the run, and the results so far are returned
- It complements §5.1 (Analysis Presets) but is more flexible

The HTTP transport already accepts JSON-RPC batch arrays; stdio does not.

### 5.5 Entry Name Aliasing / Normalization
Priority: Medium. Complexity: Medium.

Teams name entries very differently: `/Robot/Drive/FrontLeft/Velocity`, `/Swerve/Module0/DriveVelocity`, `/SmartDashboard/FL Drive Speed`. A configurable alias map, or fuzzy matching, would let the tools work across teams without exact entry names.

Partly covered by `resolve_signals`: built-in roles (battery voltage, robot pose, module states, gyro yaw, and others) resolve with a basis, ranked candidates, and an ambiguity flag, and most roles can be passed explicitly to the tools that use them (some, such as `brownout_flag`, cannot). Entry lookups that fail suggest names by substring. A team-configurable alias map is still open.

Approach:
- A configurable alias file mapping roles to name patterns
- Fuzzy search ranked by edit distance and structural similarity
- "Did you mean?" suggestions with confidence scores

Fuzzy or heuristic matches must be offered to the LLM as candidates to confirm, never used to pick an entry on the server's own authority (see §6.8). A team's alias map would count as the team's own conventions.

### 5.6 Auto-Organize Log Directory
Priority: Medium. Complexity: Medium.

A tool that sorts the files in a log directory into a readable structure. Robots write `.wpilog`, `.revlog`, and `.hoot` files into one flat directory, or into per-session subdirectories with opaque names.

Possible structure:
```
logdir/
├── 2026-vache/
│   ├── q42/
│   │   ├── akit_26-03-22_14-57-55_vache_q42.wpilog
│   │   └── REV_20260322_145731.revlog
│   ├── q58/
│   │   └── ...
│   └── practice/
│       └── ...
```

Features:
- Group by event and match type and number, parsed from file names and log metadata
- Put each `.wpilog` next to its `.revlog` files, matched by time
- Move files, or link them (a non-destructive mode creates symlinks)
- A dry run that shows what would change
- An `unsorted/` directory for files with no usable metadata

This would be the first tool that writes into a log directory (exports stay in the export directory). The server does not read `.hoot` files today.

---

## 6. LLM Epistemological Guardrails

LLMs tend to be overconfident when they interpret telemetry and logs. These ideas push them toward statistical reasoning and hedged answers.

### 6.3 MCP Guided Prompts
Priority: Medium. Complexity: Medium.

Offer multi-step analysis workflows as MCP prompts (`prompts/list` and `prompts/get`), so an agent is guided through an analysis with sound statistics. Today `prompts/list` returns an empty list, `prompts/get` is not implemented, and `initialize` advertises only tools. Reasoning guidance reaches agents through the `instructions` field and `get_server_guide`'s `analysis_principles` instead.

Example prompt:

```json
{
  "name": "systematic_brownout_analysis",
  "description": "Guided workflow for investigating power delivery issues",
  "arguments": [
    {"name": "match_count", "description": "Number of matches to analyze (min 3 recommended)"}
  ]
}
```

Prompt structure:
1. Data gathering: load several matches and pull the relevant entries
2. Baseline: compute normal operating ranges
3. Anomaly detection: find deviations, with confidence intervals
4. Correlation: check for relationships with other variables
5. Conclusion: state the findings with appropriate uncertainty

Such prompts would require multi-match analysis before conclusions, build statistical practice into the workflow, and keep the agent from concluding from a single data point.

### 6.6 Comparative Framing
Priority: Medium. Complexity: Low.

Where possible, give findings a baseline.

Example:
```json
{
  "brownout_count": 3,
  "context": {
    "typical_range": "0-2 per match for well-tuned robots",
    "concerning_threshold": ">5 per match suggests investigation",
    "your_percentile": "Higher than ~70% of logged matches"
  }
}
```

Implementation:
- Build baseline statistics from historical logs
- State findings relative to those norms
- Avoid absolute judgments ("bad", "good")

Nothing builds baselines today. `compare_matches` compares two logs, and `get_game_info` gives rule thresholds, not norms.

### 6.7 Uncertainty Propagation
Priority: Low. Complexity: High.

Carry uncertainty through derived results. `moi_regression` returns `J_kg_m2`, `B_Nm_s_per_rad`, `r_squared`, `rmse_nm`, and sample counts, but no standard error, confidence interval, or degrees of freedom, and `predict_battery_health`'s `load_line` has the same gap. `time_correlate`'s `effective_sample_size` is a precedent.

Example (moment of inertia):
```json
{
  "J_kg_m2": 2.34,
  "uncertainty": {
    "standard_error": 0.18,
    "confidence_interval_95": [1.99, 2.69],
    "r_squared": 0.89,
    "degrees_of_freedom": 47,
    "sources_of_error": [
      "Motor constant calibration (±5%)",
      "Friction model approximation",
      "Sensor noise in angular velocity"
    ]
  }
}
```

With this, an LLM can report bounds instead of point estimates, see which inputs limit accuracy, and say "I don't know" when it should.

### 6.8 Don't Guess What Entries Mean
Done (unreleased; see the CHANGELOG). This section records the rule, which the code and doc/ROBUSTNESS_PLAN.md refer to.

Apart from well-known logging conventions, the server does not infer what an entry represents from its name. A role resolves only to an explicit entry, a convention (AdvantageKit, WPILib, CTRE, PathPlanner names), or the only entry of the role's type. Entries that match by name alone are candidates (`resolve_signals`: `match: heuristic`, `needs_confirmation`), and the tools list them in `skipped` or `no_match` with the parameter to pass (`voltage_entry`, `entry`, `pose_entry`, `chooser_entry`, `path_setpoint_entry`, `path_actual_entry`, `total_current_entry`). `doc/TOOLS.md` tabulates the conventions ("The server does not guess").

Revlog sync follows the same rule: names only nominate candidate pairs (by leaf name, for applied output, velocity, current, and bus voltage), correlation chooses among them, and `sync_status`'s `signal_pairs` shows the choice. A team's alias map (§5.5) would count as the team's own conventions.

---

## 7. Developer Experience

### 7.1 Plugin Architecture
Priority: Medium. Complexity: High.

Let teams add their own analysis tools and data sources (such as team-specific CAN devices), distributed as JAR plugins with a manifest, dependencies, and version checks. Custom structs need no plugin: every struct decodes from the schema the log records.

### 7.2 Reference Test Data
Priority: High. Complexity: Low.

Mostly done. The fixture corpus (`src/test/java/org/triplehelix/wpilogmcp/fixtures/FixtureLogs.java`) generates 22 fixtures per run: AdvantageKit match and practice logs, plain WPILib logs (one with records before time zero), swerve arrays and per-module entries, three vision conventions, custom and mismatched structs, brownouts on roboRIO 1 and 2, CANivore counters, alerts, replay with and without divergence, a log without DriverStation data and one with both `DS:` and `/DriverStation/` entries, a truncated log, an empty one, and a wpilog with its REV log. The revlog tests also cover REV logs in several directories, decoy REV logs, and a REV log from a neighboring session.

Remaining:
- An end-to-end fixture in which two REV logs (for example, rio and CANivore) attach to one wpilog and both sync by data

---

## 8. Operational

### 8.3 Graceful Degradation
Partly done. Logs are evicted when free heap drops below 15%, a load evicts more when the file is larger than the free heap, and a call that runs out of memory returns an explained error while the server keeps running. `list_loaded_logs` and `health_check` report heap use.

Remaining:
- Disable memory-intensive tools when the heap is low
- Warn when approaching limits
- Streaming for very large logs (values are decoded per entry on demand, but a decoded entry is held whole)

### 8.4 Compressed Log Files
Priority: Medium. Complexity: Medium.

Read gzipped and zipped `.wpilog` and `.revlog` files directly. Logs compress well (gzip leaves about 40% of a wpilog and 20% of a REV log), and logs other teams publish often come zipped. Today discovery lists only `.wpilog` and `.revlog` names, and a compressed file passed by path fails as an invalid WPILOG file; the error shows its first bytes but does not name the format.

Approach:
- Detect gzip and zip by magic bytes (both are in the JDK); give an explained error for xz, zstd, bzip2, 7z, and tar.
- Decompress into the cache directory and memory-map the copy as today, rather than into heap (`DataLogReader` maps the whole file).
- Address zip members as `archive.zip!/name.wpilog` when an archive holds more than one log; skip macOS `__MACOSX/` and `._*` entries.
- Read a cut-off gzip as a truncated log. Discover `.revlog` files inside archives too, so REV sync still works.
- Code that assumes plain `.wpilog`/`.revlog` names or paths: the discovery filters in `LogDirectory`, the file-name patterns, `LogScan.of` (reads the header length from the path), and `RevLogParser.parse`.

### 8.5 Logs That Change After Loading
Priority: High. Complexity: Medium.

A loaded log keeps answering from its first load after the file changes on disk, for example when a live log is copied off the robot and copied again once it has grown. `LogManager.loadLog` never re-checks a cached path. What happens depends on how the file was replaced:
- Renamed into place (rsync's default): results stay stale.
- Overwritten in place (`cp` keeps the inode): old byte offsets are applied to new bytes, so new records are invisible, or a different log copied over the name decodes as garbage, with no warning.
- Read during an in-place copy: reading the truncated mapping throws `java.lang.InternalError`, which nothing in the call path catches; in stdio mode it ends the server loop.
- On Windows: the file cannot be replaced or deleted while it is mapped, and unloading the log does not release the mapping until it is garbage collected, so copying a newer log over a loaded one fails (`FileSystemException`). CI's Windows run hit this when test classes regenerated fixture logs an earlier class had loaded.

REV logs too: sync runs once at load, so a REV log copied in later is never found.

Approach: record each file's size, modification time, and file key at load; re-check on every `getOrLoad` and reload when they differ; re-check after each call and discard a result read while the file changed; catch `InternalError` from mapped reads as an explained error; tell each session once when a log it used was reloaded; re-run REV discovery and sync when REV files change.

---

## Implementation Priority Matrix

| ID | Feature | Impact | Effort | Priority |
|----|---------|--------|--------|----------|
| 8.5 | Logs that change after loading | High | Medium | **P1** |
| 4.1 | PathPlanner integration | High | Medium | **P2** |
| 4.2 | AdvantageScope integration | Medium | Medium | **P2** |
| 5.1 | Analysis presets | Medium | Low | **P2** |
| 5.4 | Batch tool execution | Medium | Low | **P2** |
| 5.5 | Entry name aliasing | Medium | Medium | **P2** |
| 8.4 | Compressed log files | Medium | Medium | **P2** |
| 6.3 | MCP guided prompts | Medium | Medium | **P2** |
| 3.4 | CAN bus diagnostics | Medium | Medium | **P3** |
| 3.3 | Energy budget analysis | Medium | Low | **P3** |
| 3.2 | Autonomous routine library | Medium | Medium | **P3** |

---

## Notes

- Prefer changes that keep existing tool interfaces compatible; a deliberate break goes under "Changed (breaking)" in the CHANGELOG.
- Performance improvements should include before and after benchmarks.
- New tools should follow the existing `ToolBase` and `LogRequiringTool` patterns.
- A cache format change needs a migration path or a clear invalidation.
