# Future Enhancement Ideas

Ideas for future versions of wpilog-mcp, grouped by theme and roughly prioritized within each section. Where part of an idea already exists, the section says so. Section numbers are kept when an idea is completed or dropped, so the code and the robustness documents can refer to them; the gaps are expected.

---

## 1. Performance & Scalability

### 1.6 FFT-Based Cross-Correlation
Priority: Low. Complexity: High.

REV log sync searches lags by brute force: for each lag in a ±60 s window (12,001 lags at 100 Hz) it computes a full Pearson correlation over the overlap. An FFT-based normalized cross-correlation would be O(n log n), with prefix sums for each lag's local mean and variance.

The cost is bounded today. A signal longer than 600 s at 100 Hz is trimmed to its highest-variance window, a coarse pass at a tenth of the rate ranks the candidate pairs, and at most five pairs are refined at full rate. FFT would pay off if that 600 s budget were raised or the search window widened. Short signals should keep the sliding window, where FFT overhead is not worth it.

---

## 2. Data Quality & Accuracy

### 2.2 Multi-Log Temporal Alignment
Priority: Low. Complexity: High.

Align several logs from the same event (the practice runs, say) on a common time base, to compare autonomous attempts, track mechanism tuning, or find intermittent issues.

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

Nothing integrates power today. `power_analysis` reports each channel's minimum, maximum, and average current, with the times of the extremes.

### 3.4 CAN Bus Diagnostics
Priority: Medium. Complexity: Medium.

Partly done. `can_health` splits CAN error lines by the robot's enabled state and reads the bus counters. `analyze_can_bus` reports, per bus, utilization, TEC and REC levels with error-passive excursions, and bus-off and TX-full counts, overall and while enabled. It also counts increases in any other entry whose name contains CAN together with error, fault, or timeout.

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

Following error from logged poses already exists. `analyze_auto` reports `path_following_error` between the logged setpoint and actual poses (`path_setpoint_entry`, `path_actual_entry`), and `compare_poses` gives, for any two pose streams, their distance, their along-track and cross-track error, and the times of the largest errors. What remains is reading the path files themselves.

### 4.2 AdvantageScope Integration
Priority: Medium. Complexity: Medium.

Hand analysis results to AdvantageScope for visualization.

#### 4.2.1 Layout File Generation

Generate AdvantageScope layout files (`.json`) that set up the views an analysis calls for.

Layout file structure (reverse-engineered from the AdvantageScope source):
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

Until then, the file-based handoff of §4.2.1 and §4.2.2 works: write the layout (say, to `~/.wpilog-mcp/advantagescope-layout.json`), launch AdvantageScope with the log file, and tell the user to import the layout.

#### 4.2.4 Data Export Formats

Export analysis results in formats AdvantageScope can open:
- MCAP, AdvantageScope's native format, with metadata
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

Partly done. Every result has a `status` (`ok`, `partial`, `not_applicable`, `no_match`, or `error`), with `reason`, `looked_for`, and `hint` where they apply; a misspelled entry or tool name gets suggestions.

Remaining:
- Machine-readable codes on errors (an `error` result carries only a message)
- An actionable suggestion in every error
- Example queries in tool descriptions
- Domain vocabulary in responses

### 5.3 Report Generation
Priority: Medium. Complexity: Medium.

`generate_report` returns a JSON summary. This idea is a human-readable report built from the analysis:
- Formats: Markdown, HTML with embedded charts, or PDF for printing
- Content: a summary (match outcome, key issues), findings by category, and recommendations ranked by impact; all of it worded as evidence, as the tools word their results

### 5.4 Batch Tool Execution
Priority: Medium. Complexity: Low.

Accept a list of tool calls and return all the results in one response, to save round trips in multi-step workflows such as "voltage statistics, current statistics, correlate".

Design:
- A new tool (say, `run_workflow`) takes an ordered list of `{tool, arguments}` pairs
- Results come back as an array in the same order
- The first error stops the run and returns the results so far
- It complements §5.1 (Analysis Presets): a preset is a fixed workflow, a batch is whatever the caller lists

The HTTP transport already accepts JSON-RPC batch arrays; stdio does not.

### 5.5 Entry Name Aliasing / Normalization
Priority: Medium. Complexity: Medium.

Teams name entries very differently: `/Robot/Drive/FrontLeft/Velocity`, `/Swerve/Module0/DriveVelocity`, `/SmartDashboard/FL Drive Speed`. A configurable alias map, or fuzzy matching, would let the tools work across teams without exact entry names.

Partly covered by `resolve_signals`: built-in roles (battery voltage, robot pose, module states, gyro yaw, and others) resolve with a basis, ranked candidates, and an ambiguity flag; most roles can be passed explicitly to the tools that use them (some, such as `brownout_flag`, cannot). A failed entry lookup suggests names that contain the one asked for. A team-configurable alias map is still open.

Approach:
- A configurable alias file mapping roles to name patterns
- Fuzzy search ranked by edit distance and structural similarity
- "Did you mean?" suggestions with confidence scores

Fuzzy or heuristic matches must be offered to the LLM as candidates to confirm, never used to pick an entry on the server's own authority (§6.8, which also says how a team's alias map fits the rule).

### 5.6 Auto-Organize Log Directory
Priority: Medium. Complexity: Medium.

Robots write `.wpilog`, `.revlog`, and `.hoot` files into one flat directory, or into per-session subdirectories with opaque names. This idea is a tool that sorts the files in a log directory into a readable structure.

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
- Group by event, then by match type and number, both parsed from file names and log metadata
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

Such prompts would build statistical practice into the workflow and require several matches before any conclusion.

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

Carry uncertainty through derived results. `moi_regression` returns `J_kg_m2`, `B_Nm_s_per_rad`, `r_squared`, `rmse_nm`, and sample counts, but no standard error, confidence interval, or degrees of freedom; `predict_battery_health`'s `load_line` has the same gap. `time_correlate`'s `effective_sample_size` is a precedent.

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
Done in 0.9.0 (see the CHANGELOG). This section records the rule, which the code and `doc/ROBUSTNESS_PLAN.md` refer to.

Apart from well-known logging conventions, the server does not infer what an entry represents from its name or from the shape of its data. A role resolves only to an explicit entry, a convention (AdvantageKit, WPILib, CTRE, PathPlanner, YAGSL, Limelight, and PhotonVision names), or the only entry of the role's type. Entries that match by name or content alone are candidates (`resolve_signals`: `match: heuristic`, `needs_confirmation`), and the tools list them in `candidates`, `skipped`, or `no_match` with the parameter to pass (`voltage_entry`, `entry`, `pose_entry`, `chooser_entry`, `path_setpoint_entry`, `path_actual_entry`, `total_current_entry`, `measured_entry`, `setpoint_entry`, `vision_entries`, and `profile_mechanism`'s role entries). `doc/TOOLS.md` tabulates the conventions ("The server does not guess").

What an entry measures is settled by the robot code that logs it, so the server's guidance sends the agent there to confirm a candidate. When the code is not at hand, a role taken from a name is to be stated as an assumption.

REV log sync follows the same rule: names only nominate candidate pairs (by leaf name, for applied output, velocity, current, and bus voltage), correlation chooses among them, and `sync_status`'s `signal_pairs` shows the choice. A team's alias map (§5.5) would count as the team's own conventions.

---

## 7. Developer Experience

### 7.1 Plugin Architecture
Priority: Medium. Complexity: High.

Let teams add their own analysis tools and data sources (such as team-specific CAN devices), distributed as JAR plugins with a manifest, dependencies, and version checks. Custom structs need no plugin: every struct decodes from the schema the log records.

### 7.2 Reference Test Data
Priority: High. Complexity: Low.

Mostly done. The fixture corpus (`src/test/java/org/triplehelix/wpilogmcp/fixtures/FixtureLogs.java`) generates a fixture per logging convention and failure mode: AdvantageKit match and practice logs, plain WPILib logs (one with records before time zero), swerve arrays and per-module entries, three vision conventions, custom and mismatched structs, brownouts on roboRIO 1 and 2, CANivore counters, alerts, replay with and without divergence, a log without DriverStation data and one with both `DS:` and `/DriverStation/` entries, a truncated log, an empty one, and a wpilog with its REV log. The REV log tests also cover REV logs in several directories, decoy REV logs, and a REV log from a neighboring session.

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
- Read a cut-off gzip as a truncated log.
- Discover `.revlog` files inside archives too, so REV log sync still works.

Code that assumes plain `.wpilog` and `.revlog` names or paths: the discovery filters in `LogDirectory`, the file-name patterns, `LogScan.of` (which reads the header length from the path), and `RevLogParser.parse`.

### 8.5 Logs That Change After Loading
Done (see the CHANGELOG's Unreleased section). A loaded log keeps its file's size, modification time, and identity from just before it was read, and every call compares them with the file: a changed file is loaded again, a result read across a change is discarded with an error that says what changed, a faulting read of the mapping (`java.lang.InternalError`) is the same explained error instead of the end of a stdio server, each session is told once that a log it used was reloaded, and the REV log tools look again for REV logs that changed, keeping an offset set by hand. `doc/ARCHITECTURE.md` ("Loading") has the rules.

Remaining:
- On Windows, the file cannot be replaced or deleted while it is mapped, and unloading the log does not release the mapping until it is garbage collected, so copying a newer log over a loaded one fails (`FileSystemException`). Releasing the mapping on unload needs a count of the calls still reading the log, since a closed log must keep answering a call that holds it.

---

## 9. The Pit Server

### 9.1 Live Capture, Gateway, and the Long-Term Record
Priority: High. Complexity: High.

Milestones 1 through 8 are implemented: capture and its live index, identity/pulling, import,
live tools, store sync/mirroring, metrics and the optional read-only NT4 gateway. The shop harness
now checks a separate ntcore client's gateway observations against its timeline. Real dashboards,
AdvantageScope and roboRIO shop behavior remain manual checks.
[PIT_SERVER_PLAN.md](PIT_SERVER_PLAN.md#15-milestones) records the remaining work.

A daemon in the shop and the pit that subscribes once to the robot's NetworkTables, records every change of every topic as a `.wpilog` capture per robot boot, re-publishes the stream as a read-only NetworkTables gateway so the robot has one client, pulls the robot's own log files and the roboRIO's system logs whenever it sits disabled, follows configured files such as the program's console into the capture as they are written, answers the existing tools and a few live ones over the HTTP MCP transport on the team's private network, records vision coprocessor settings, the roboRIO's system stats, and the robot program's garbage collection and profile beside the data, serves its latest values for Prometheus and Grafana, maps every session and pulled file to a robot by the roboRIO's serial number, and keeps all of it in a store it owns, by robot and session, that files enter only by capture, pull, or import; the extension keeps a synchronized mirror of the sessions a laptop wants, so analysis continues offline from the same files. The process that writes a capture is the process that answers questions about it, so an open session is served from the index and values the writer builds as it writes, never by reading the file back. [PIT_SERVER_PLAN.md](PIT_SERVER_PLAN.md) is the proposal and the specification, with milestones.

The store's HTTP door and laptop-to-laptop peer sync now exist (milestone 6, first half): catalog reads, Range and prefix hashes, content-checked resume, overlapping same-serial session union, provenance, human conflicts and remembered peers. Both daemon jobs and the offline command own the local store lock. The second half now adds the local server’s scoped mirror, pins/cap, growing capture prefixes, recorded alignment, offline age and id-based moves; the extension registers the pit server, follows remote logs, selects mirrored copies offline, and offers mirror and remembered peer-sync controls. Real VS Code verification remains the manual checklist in DEVELOPMENT.md.

### 9.2 Windowed WPILOG Mapping
Priority: High. Complexity: High. Planned after the gateway in [PIT_SERVER_PLAN.md](PIT_SERVER_PLAN.md#15-milestones).

The reader currently maps a file into one int-indexed buffer and refuses files over 2 GB. Replace that with windows under 2 GB, long offsets everywhere, and a small extra mapping or copy for a record straddling a window boundary. Tests must be able to set a small window size and cross boundaries without writing gigabyte fixtures. Capture rollover keeps each file below the limit until that milestone. Oversized imports are refused with the original untouched, and the plain-directory listing reports the same reason.

### 9.3 Data Browser and Charts
Priority: High. Complexity: Medium.

Direct access to the data without an agent, and real data in an agent's answers. [EXPLORER_PLAN.md](EXPLORER_PLAN.md) is the proposal and the specification, with milestones; the notes below are what it grew from. Decided: the viewer is part of the WPILog Analyzer extension, not a second extension, since a viewer and an analyzer that share a server, a configuration, and a selection belong in one install, and the viewer needs no assistant to be useful.

- **A data browser**: a webview that is an MCP client of the server it starts on loopback, or of a pit server by URL. It shows the logs from the listing, entries with types and counts, a plot of an entry or a struct field over a window with the match phases shaded behind it, statistics beside the plot, searchable console text, REV signals on the wpilog's clock, and a pit server's live session as a tail that follows. A selection (an entry and a window) becomes a prefilled chat prompt, so a person moves from looking to asking without retyping; with no assistant configured, the browser stands alone. Charts in the webview use a small time-series library bundled as a static asset; the extension's rule against runtime npm dependencies is about the Node side and should say so.
- **One server per laptop, shared by its clients**: done through explorer milestone 7. The standalone `http` server serves the viewer, VS Code’s agents, and terminal bridges. User/open-project directories are session leases with origin/team metadata; `connect` takes shared-server configuration from home or `--config`, and only directory/team leases from project YAML and flags. The extension’s private daemon and setting are removed; Claude Code uses one user-scope bridge registration. Recognized old project entries are retired conservatively. No operating-system service.
- **A team number per log directory**: done for leases, including several projects in one window. Logged team comes first, then the most specific live lease with a team, then the configured default. Per-directory teams in permanent YAML remain a possible later addition.
- **The foundation**: the extension's own MCP client over that server, the Logs and Entries views, and the custom editor that opens a `.wpilog` to its time range and entries. The second milestone, done.
- **The plot**: `read_entry` at a resolution, the data endpoint in Arrow and CSV, and the panes, timeline, and statistics in the editor. The third milestone, done.
- **Console, array elements, and the field view**: the text with its marks on the timeline, `[*]` paths as elements, and the pose on the season's field. The fourth milestone, done.
- **REV signals**: the endpoint serves them on the robot's clock with the alignment's basis, and the pane lists and plots them. The fifth milestone, done.
- **Organizing logs**: the sixth milestone is done: server import jobs and inbox batches, the command, explicit unassigned assignment, and the extension's per-folder offer, move/copy and robot picks, progress and result reporting, and trees by robot and session. Pure modules and transport clients have automated checks; the offer, quick pick, progress notification, and trees still need the manual real-VS-Code checks listed in EXPLORER_PLAN.md.
- **The extension installs and updates the standalone server**: done. The JAR owns the layout; shell installers, Gradle, and the extension delegate to it. The extension offers a missing install, updates older launchers without downgrading or rewriting settings, and provides an on-demand command. Scripts accept tags/pre-releases, prompt interactively, optionally install the matching `.vsix`, and support a settings-preserving refresh with a backup. Bootstrap flows from installer to extension; thereafter the extension updates the server and the Marketplace updates the extension. Real VS Code checks remain listed in EXPLORER_PLAN.md.
- **A `render_chart` tool**, in the server so the browser, the agent, and the pit server draw the same picture from the same code: takes what `read_entry` takes plus a chart kind (time series, overlaid series, histogram, scatter, pose on the field from the bundled field geometry) and returns the chart as MCP image content (a PNG, rasterized with the JDK's headless imaging; skipped with a note where a runtime lacks it), a chart specification the browser renders interactively with a link that opens it there, and the numeric summary of what was plotted with `inputs`, so a model describing the chart describes numbers the tool returned. Axes carry the unit from the entry's name; a change-only series is drawn as steps, never interpolated; phases come from the Driver Station data. The MCP Apps extension, where a server returns an interactive view the host renders, is the longer-term path for the chat side; image content works everywhere today.

---

The pit import milestone is complete: existing command and inbox grouping now have bulk USB
and capture-enabled coverage, and the extension can upload verified copies to the pit store.
The server accepts only file bytes at its network write surface; server-path operations remain
local. Capture-only session/latest/wait tools now exist, with recorder costs and matched imports.
The remaining §14 proxy login uses SecretStorage and local credential leases; its interactive
credential flows and hardware validation remain on the manual checklist.

The metrics milestone is complete: `/metrics` serves latest numeric topics with ages,
recorded struct fields, capture/pull counters and JVM MBeans. A Compose setup and starter
Grafana dashboard live in `doc/metrics`. The optional gateway now serves dashboards through
the pit computer and reports its client count; provider startup remains later work.
The capture remains the full record behind the sampled dashboard.

The roboRIO stats and followed-file providers are implemented on one shared SSH connection.
They are enabled with SSH configuration for shop testing, with cost reports and bounded tails;
measure the defaults and NI-image command support before recommending them to teams. JVM/JFR,
vision providers and the second-pass system-log pull remain planned in the pit plan.

### 9.4 Shop Harness

Step 1 is implemented: a scripted WPILib 2026 headless robot, real SSH/SFTP from a synthetic
roboRIO, and a packaged pit server checked over HTTP MCP. Timelines pin capture fidelity,
identity, boots, match renames, pulling and the disabled gate. It runs separately from the ordinary
suite; see [DEVELOPMENT.md](DEVELOPMENT.md#the-shop-harness). Step 2 is an NI-image container
and PhotonVision. Actual roboRIO permissions, installed commands, radio behavior and hash cost
still need the shop test; desktop simulation cannot establish them.

Real-log replay extends step 1: the independent reader feeds the loopback gateway on every
platform; the separate robot can publish the same file through native ntcore. Generated fixtures
exercise both paths in CI. A local `conformanceLogDir` enables stratified record comparison by default, with
`conformanceSample=full` for releases and changes to the recording or matching path. One shared
selector covers logger kinds, file sizes and the largest file, REV companions, damaged tails,
calendar evidence and a two-boot pair; the runtime report explains every choice. Both modes
check metadata, cost accounting, signed offsets, pull-placement refusals, clock resets,
and REV companion comparisons. Reports stay under `build/`; logs and telemetry stay outside git.
The source robot clock is preserved, while an injected capture calendar clock keeps the overlap
filter meaningful. A log ending mid-record remains unverified by the puller; replay compares its
complete records and reports that limitation. See the replay commands in the development guide.

## Implementation Priority Matrix

| ID | Feature | Impact | Effort | Priority |
|----|---------|--------|--------|----------|
| 9.1 | The pit server (see PIT_SERVER_PLAN.md) | High | High | **P1** |
| 9.2 | WPILog Explorer: data browser and charts (see EXPLORER_PLAN.md) | High | Medium | **P1** |
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


Explorer verification: milestone 11 now drives real VS Code in CI at the oldest supported
version and current stable for activation, the shared server, fixture Logs listing, editor
opening and pit command arguments. Rendered plots, interactive credential/organizer/mirror
flows and agent discovery remain the manual/future automated coverage in DEVELOPMENT.md.
