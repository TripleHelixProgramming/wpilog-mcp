# Future Enhancement Ideas

Ideas for future versions of wpilog-mcp, grouped by theme and roughly prioritized within each section. Where part of an idea already exists, the section says so. Section numbers are kept when an idea is completed or dropped, so the code and the robustness documents can refer to them; the gaps are expected.

The agreed next Explorer/source work is specified in
[Explorer sources and mirror implementation plan](SOURCE_AND_MIRROR_PLAN.md): independent
local/remote sources with no default, server-owned visualization libraries, and mirrors
that retain collected files, pause at capacity and accept provisional peer content through
their engine. M1 to M7 are committed locally, from source contracts and server libraries to
mirror identity, peer reconciliation and discovery; M8, agent display and explicit copying of
views and templates, and M9, integrated acceptance, remain. The plan records migration,
acceptance gates and the validation limits for each checkpoint.

The [architecture hardening plan](ARCHITECTURE_HARDENING_PLAN.md) proposes changes to the
current design that the review of the rejected [columnar analytics cache](COLUMNAR_STORE_PLAN.md)
exposed: recordings that no import can stop, a disk reserve for capture, a restart for a hung
server, one rule for bucketing samples, costs measured against a season's store, a contract
for questions across many sessions (sections 2.2 and 3.5 below), and the design rules written
down. Its three runs built all seven items, the cross-session contract as `compare_sessions`.

[Recorded history in Grafana](GRAFANA_PLAN.md) plans plotting stored sessions in the Grafana
that shows the live metrics, on server installs, after live panels on the open capture
(section 9.5 below). Its decisions are recorded; stage 1's server reads, live and by UTC,
are built, and its gate and dashboard are next.
[The column file plan](COLUMN_CACHE_PLAN.md) proposes keeping each log's numeric entries as
columns beside it, so reads at any zoom and across sessions skip the per-sample cost. Its file
format, writer and reader are built, the server builds the files in the background, the
numeric reads of finished logs take their numbers from them, and a recording keeps its index
in columns as it records.

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

Today `compare_matches` compares one signal's statistics across two logs, each on its own clock, and `compare_sessions` compares one statistic per session across up to 64 finished sessions of a store, each on its own clock too. Neither aligns files on a common time base; the only alignment between files is REV log to wpilog sync.

Measured wall-clock time is the common time base for plots: the [Grafana plan](GRAFANA_PLAN.md#across-sessions) states the rules for placing many sessions on one axis and the two ways proposed to serve such panels.

### NetworkTables Receipt Time

NT4 receipt timestamps currently use the ordered loop's processing time. A backlog therefore
inflates the measured round trip even when the network is fast. Consider stamping receipt on
the network callback, while preserving message ordering on the loop. Test the mapping with a
known loop stall before changing the time-sync rules.

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

`profile_mechanism` covers one log at a time, and `compare_matches` compares one signal across two logs. `compare_sessions` gives one statistic per session across up to 64 finished sessions of a store, with each session's code version, robot and event, which is the raw material for these trends; nothing yet fits a trend or says when one is real.

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

CSV's policy for formula-leading text and non-finite numbers remains undecided. A spreadsheet
may interpret text beginning with `=`, `+`, `-`, or `@` as a formula; escaping it changes the
exported text. Decide whether to offer a spreadsheet-safe mode and how missing or
non-finite numbers render, then keep `export_csv` and the CSV data endpoint consistent.

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

### 5.6 Opt-in data store

Implemented for WPILOG and REV files, but held behind the disabled-by-default
`experimental_data_store` server flag pending further design review. When enabled, the shared
server keeps importing explicitly selected
folders into a user-chosen store, with Copy by default and guarded Move for completed-file
folders. Manifests and receipts retain provenance and duplicate origins. Default browsing uses
the store plus pending/refused source versions; All Sources preserves access to originals.
The manual one-time import and organizing commands remain. No symlink organization or `.hoot`
reader is implemented; the server does not guess a REV alignment from names.

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

Apart from well-known logging conventions, the server does not infer what an entry represents from its name or from the shape of its data. A role resolves only to an explicit entry, a convention (AdvantageKit, WPILib, CTRE, PathPlanner, YAGSL, Limelight, and PhotonVision names), or the only entry of the role's type.

Entries that match by name or content alone are candidates (`resolve_signals`: `match: heuristic`, `needs_confirmation`). The tools list them in `candidates`, `skipped`, or `no_match` with the parameter to pass: `voltage_entry`, `entry`, `pose_entry`, `chooser_entry`, `path_setpoint_entry`, `path_actual_entry`, `total_current_entry`, `measured_entry`, `setpoint_entry`, `vision_entries`, and `profile_mechanism`'s role entries. `doc/TOOLS.md` tabulates the conventions ("The server does not guess").

What an entry measures is settled by the robot code that logs it, so the server's guidance sends the agent there to confirm a candidate. When the code is not at hand, a role taken from a name is to be stated as an assumption.

REV log sync follows the same rule: names only nominate candidate pairs (by leaf name, for applied output, velocity, current, and bus voltage), correlation chooses among them, and `sync_status`'s `signal_pairs` shows the choice. A team's alias map (§5.5) would count as the team's own conventions.

---

## 7. Developer Experience

### Shared read API

Implemented locally: MCP and Arrow/CSV use the shared read admission and traversal, while
compatibility materialization preserves their established answers. Local and remote snapshots,
manifest-backed catalogs and recorded REV adapters are documented in [READ_API.md](READ_API.md),
with a standalone reader JAR and third-party examples. Domain inference stays above raw access;
Arrow encodes the shared result rather than adding a decoding hop for local tools. Frozen-build
comparison and independent samples guard further migration. Publishing a library package is a
separate release decision; automatic alignment and store mutation are outside the read API.
The remaining plan features are implemented locally: shared field projection and numeric buckets,
multi-entry queries, session unions with recorded clock proof, selective REV decoding and a
caller-polled local follower. Session members now share the server's managed/live admission;
rollover clock proof uses recorded provenance rather than session membership alone. Narrow union
windows seek before decoding, and retained session indexes have a shared budget.
Input, sort and session-merge bounds remain explicit; clients can
read individual files or signals when a larger combined view exceeds admission. Publication and
Windows validation still require their own release/CI step.

### 7.1 Plugin Architecture
Priority: Medium. Complexity: High.

Let teams add their own analysis tools and data sources (such as team-specific CAN devices), distributed as JAR plugins with a manifest, dependencies, and version checks. Custom structs need no plugin: every struct decodes from the schema the log records.

Tool construction still reaches singleton services through the no-argument `ToolBase`
constructor. Make the transport own and pass the dependencies explicitly before supporting
independent servers in one JVM. Tests should prove that their log managers, security rules and
registries cannot cross-talk.

### 7.2 Reference Test Data
Priority: High. Complexity: Low.

Mostly done. The fixture corpus (`src/test/java/org/triplehelix/wpilogmcp/fixtures/FixtureLogs.java`) generates a fixture per logging convention and failure mode:

- AdvantageKit match and practice logs, and plain WPILib logs (one with records before time zero);
- swerve arrays and per-module entries, and three vision conventions;
- custom and mismatched structs;
- brownouts on roboRIO 1 and 2, CANivore counters, and alerts;
- replay with and without divergence;
- a log without DriverStation data, and one with both `DS:` and `/DriverStation/` entries;
- a truncated log, an empty one, and a wpilog with its REV log.

The REV log tests also cover REV logs in several directories, decoy REV logs, and a REV log from a neighboring session.

Remaining:
- An end-to-end fixture in which two REV logs (for example, rio and CANivore) attach to one wpilog and both sync by data
- A real gateway socket whose peer sends fragments without FIN, with a tiny receive budget:
  refusal must arrive promptly while a second peer keeps receiving. The existing direct
  `BoundedReceiveDraft` tests do not prove admission through the socket.

### Pinned CI Actions

CI actions are pinned by major tag, so the same workflow can run different action code later.
Consider commit-SHA pins with an update mechanism, and keep the version labels readable for
review. This is a build supply-chain decision, separate from the server's runtime dependencies.

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
Done. A loaded log keeps its file's size, modification time, and identity from just before it was read, and every call compares them with the file:

- a changed file is loaded again;
- a result read across a change is discarded, with an error that says what changed;
- a faulting read of the mapping (`java.lang.InternalError`) is the same explained error, instead of the end of a stdio server;
- each session is told once that a log it used was reloaded;
- the REV log tools look again for REV logs that changed, keeping an offset set by hand.

`doc/ARCHITECTURE.md` ("Loading") has the rules.

Remaining:
- On Windows, the file cannot be replaced or deleted while it is mapped, and unloading the log does not release the mapping until it is garbage collected, so copying a newer log over a loaded one fails (`FileSystemException`). Releasing the mapping on unload needs a count of the calls still reading the log, since a closed log must keep answering a call that holds it.

### Store Durability and Move History

If a stored file is deleted outside the server, data-store sync stops for every source with
"Invalid or missing manifested log", does not resume, and writes nothing to the server log.
Define recovery from a missing manifested payload and report the failure in the server log;
this remains deferred hardening.

`MirrorSync` and its recovery still append move records, while `LogStore` and pull placement
rewrite older records to the final destination. Give mirror moves the same rule and test A to
B to C with recovery of the second move. Keep the resolver's chain and cycle checks as defence
for existing manifests.

Durable move history also needs a retention rule: partition it or expire records with an
explained policy before it can fill a manifest. `StoreFiles` currently refuses a JSON write
above 4 MiB and keeps the prior file; it does not compact the history. The refusal should name
the history that grew and explain the supported remedy.

Manifest replacement is atomic, but `StoreFiles` does not force the manifest or its directory
to durable storage. Decide the required power-loss guarantee, then force the necessary writes
in their publication order and test recovery at each boundary. Atomic replacement alone does
not establish that the newest manifest survives power loss.

### Windows Stop-Token Permissions

The stop-token file has no explicit Windows ACL. Decide and enforce an owner-only policy
there, as POSIX permissions already do on other platforms, and verify it with a second Windows
account. Do not assume the parent directory's inherited ACL makes the token private.

### Session IDs Outside Replies

An MCP session ID acts as that session for everything it owns, so visualization replies name
windows by opaque window IDs instead. Two copies remain outside the reply surface. The server log
records every session ID at creation and removal, and the standalone `http.log` is created under
the usual umask, readable by other local accounts. While a working view is attached, its state
file records the attaching session until detach or restart. Decide whether to log a window ID or
a short keyed digest instead and to create logs and visualization state owner-only, then verify
both with a second local account. The log line is released behavior.

### A Joined Start Waits Out a Failed Start

When two starts of one server overlap, as when two VS Code windows run `wpilog-mcp start` at
once, the second waits for the first. If the first gives up, for example because another
program holds the port, it fails at once with that reason. The second keeps waiting for three
start timeouts, 30 seconds by default, then reports only "Failed to start server". Let the
waiting start stop as soon as the first gives up, and report the first start's reason. A
concurrent restart test timed out this way in the second hardening run's full build, most
likely after its fake daemon made the first start give up.

### Time Limits and Cancellation for Long Tool Calls

No tool call has a time limit, and the server does not act on a client's cancellation notice:
a long call runs to completion on its request thread after the client has stopped waiting. The
multi-session tool's first version caps its work before it starts instead of adding its own
deadline. Design one mechanism for every heavy tool: a whole-call deadline, the client's
cancellation notice, checks between bounded steps of hashing, scanning, sorting and decoding,
and reply memory held until the transport has finished sending.

### Scope IDs From Another Server

A plain folder's scope ID is derived from its path; stores and mirrors use their own store IDs.
Two servers that serve the same folder path on different machines therefore report the same
scope ID for different files, and an agent that lists scopes on one and passes the ID to the
other gets that server's folder without an error. `list_source_scopes` also returns
`server_instance_id`. Decide whether scoped tools should accept it and refuse a scope ID listed
by a different server, and apply the rule to every scoped tool at once.

---

## 9. File access and visualization

### 9.2 Windowed WPILOG Mapping

Implemented: long file addresses, windows no larger than 1 GiB, copies only
for crossing records, deterministic Windows cleanup, and narrow offset storage for ordinary
files. Imports support files up to 1 TiB. Small-window tests cover the fixture corpus; `largeLogTest` is the opt-in 2.2 GB check.

The reader also resumes another process's growing file from its incomplete-tail
boundary, copying the prior index after identity and header/last-record checks. Unknown
identity or failed anchors load afresh.

### 9.4 Reusable visualization documents

[VISUALIZATION_PLAN.md](VISUALIZATION_PLAN.md) stages the replacement. Stages 1, 2a and accepted
2b are committed locally. Stage 2b implements reusable templates, explicit compatibility,
JCS sharing, statistics/raw tables, named MCP operations and revision notifications. Review
accepted the macOS gate subject to two focused fixes: deleted-view capacity and missing-template
diagnostics, both included in that commit. Its focused carry-overs are addressed: one catalog owner, the product native first-save
path, request-counted hidden/revealed views, native overview Resume/Open new view, and unavailable
older-fingerprint listings. Opening an overview across a server restart still requires reopening
it to refresh an ephemeral source reference; no silent source retargeting is attempted.

Native Save As still refuses with a Save view hint until its picker/destination/replacement gate
is proven. Stage 3 has started with the internal ordinal-follow proof: fixed bootstrap prefixes,
acknowledged exact-page retries, bounded pages, metadata/schema publication and explicit
identity/history refusal. The first-gate review is settled in the engine: oversized values
produce explicit ordinal gaps, frontiers travel once per prefix, and pages pack several entries
under global limits. The server integration now provides session merge aliases and anchored
membership, native follow routes, lease expiry, dedicated long polling, exact retry admission,
final hashes and idle mapping release. The shared broker core now has real-server coverage. Automatic panel updates, display/clock
states, histogram/scatter and historical navigation have local preview checkpoints. The
64 MiB default decoded-buffer setting and aggregate recorded/live accounting are implemented;
configured remote admission, latency instrumentation and large-log/live performance gates
remain Stage 3 work.
Explicit display requests, client selection and default-editor retirement remain Stage 4.
The preview's field projection is numeric only; richer typed projections remain with the
existing editor until the shared reader/renderer contract supports them. Template/working
receipt exhaustion for non-deleted records currently refuses and requires a new definition/view
rather than pruning acknowledged proof. Explicitly deleted working records have a separate
six-hour retry window, count/byte caps and oldest-first eviction. Native Linux/Windows checks and the manual Close-cancellation click remain
outstanding; Mac fixtures do not prove them. No performance claim is made.

The Stage 3 prerequisite fixes the Stage 2b SSE shutdown finding: streams use the existing
notification owner, optional state failure still permits pings, and shutdown drains streams and
admitted tools before closing the workspace. If workspace startup fails, preview routes stay
unavailable and this stream stays ping-only until restart; runtime workspace recovery remains
deferred. The internal engine still opens a prefix after every completed non-finalized frontier;
the production source/publication waiter now avoids repeated disk scans during unchanged idle polls.
The server integration review's four pre-broker contracts and small-page refinement are committed in `6a0524f`:
terminal pages and retained 410 acknowledgements, typed retryable byte-budget refusals, persisted
writer-incarnation proof through finalization, and acknowledged round-robin entry/field turns.
The small-page follow-up keeps packing after a byte deferral and gives the first deferred
selection the next page's first turn; the broker need not avoid small pages to prevent that
selection losing its space on every request.
Lease admission still does not reserve encoded bytes. The broker now backs off on
`retryable: true` and retries the submitted cursor; it stops polling and DELETEs after consuming
an `ended` page. Opened jobs now keep immutable queries; only pending opens coalesce. Later
joins, refreshes and subscription reveals get independent leases, preserving peers' streams.
Refresh needs spare job/byte admission before it detaches its old binding. The acquisition
fixture proves this with 1 MiB pages; retain that choice for panels and the 4 MiB protocol maximum.
Evaluate a bounded shared history cache only if the display-budget/performance gate shows the
extra leases binding. Native visibility still needs the later panel integration. Legacy missing writer proof at finalization
requires explicit rebootstrap. The body reader now classifies both malformed JSON and oversized
requests before source work. The broker core checkpoint is implemented; live UI wiring, the decoded-buffer setting and
aggregate recorded/live/webview accounting remain outstanding, explicitly within Stage 3.

The oversized-request transport defect exposed by the array-limit round is fixed by the shared
bounded refusal drain. Client requests now use the server's 64 KiB cap and oversized individual requests
refuse synchronously before changing a job; valid requests that overflow a pending union split
into another admitted job. Failing-first, plant and validation evidence is in
`/private/tmp/wpilog-visualization-stage3-refusals/validation.md`, with the immutable-job follow-up
in `/private/tmp/wpilog-visualization-stage3-immutable-jobs/validation.md`. Native Linux/Windows execution
remains outstanding; the new raw-socket tests run in the ordinary cross-platform Java suite.

Review 8's continuous-append catalog race is addressed before panel wiring: post-scan open
payload growth is accepted, other moving dependencies get one reread, and a still-unstable
catalog returns a typed retryable 409. The HTTP broker backs off on the same lease/cursor without
resetting peers. Permanent history refusals still stop. Evidence is in
`/private/tmp/wpilog-visualization-stage3-catalog-retry/validation.md`; native platform and panel
checks remain outstanding. The catalog still refreshes its inventory after later payload growth;
this fix makes no catalog-scan throughput claim. Review 9 accepts the fix and makes season-sized
inventory cost an explicit [performance-gate measurement](VISUALIZATION_PLAN.md#performance-gates-and-remaining-uncertainty).
Measure scan frequency/duration, filesystem work and follow latency across store sizes and
simultaneous/staggered leases. The five-second check is an idle fallback; changed-prefix
admission can refresh sooner. Shared concurrent scans do not bound aggregate work under
continued growth. Keep the strict cache and malformed-409 refusal contracts while measuring.

Recording-source inspection is now shared by HTTP/MCP and uses the follower's catalog admission,
not a path chosen by the editor. Member inventories keep their schemas/counts and report type or
metadata conflicts. Fixed-view clock facts distinguish the shared robot estimate, last sample,
waiting and empty-finalized states. Compatibility and recording-view metadata now build on
this inspection below. Revision-bound binding at follow admission, one displayed frontier across all panels,
held/pause/inspect/follow controls, the aggregate decoded budget and configured remote routing
still need the next integration work. Keep the agreed 1 MiB panel pages and uninterrupted peers.
Then run the full Stage 3 resource and season-sized catalog performance gates; source inspection
does not establish them. Its state is deliberately distinguished by `live_source_inspection`
and the still-false `live` capability. Repeated inspections are new prefix admissions and must
not become a per-frame polling substitute for the broker.

Review 10 accepts the inspection checkpoint, committed as `ffc1f63`. Its topic-count probe
showed that full descriptors can exceed a 1 MiB frontier at only a few thousand topics, before
any sample is sent. The owner retained 1 MiB panel pages and chose explicit queried-entry
metadata. The follow-up projects only the wire descriptors and required schemas; complete
recording history proof, strict catalog reuse and byte reservations remain. Legacy follows keep
full metadata. The gate now covers 5,000 and 10,000 topics per member across two members.

Review 11 accepts queried metadata, committed unchanged as `7a8b656`. Full overview inspection
is still capped at 4 MiB and can refuse large inventories even when queried follows fit. The
[bounded inventory design](VISUALIZATION_PLAN.md#bounded-recording-inventories)
proposes compact pages from a detached fixed snapshot, separate fresh selected-entry details,
shared HTTP/MCP ownership and bounded lifetime/allocation. Review 12 accepts it with explicit
server/session quotas, the MCP list-result contract, session-bound unguessable handles, fresh
type-change refusals and automatic visible expiry refresh. The shared paged server operations
are now implemented. Review 13's transport blockers are corrected locally: final refusals have
bounded I/O ownership, raw MCP input retains its byte count, and pressured calls keep normal
session/JSON-RPC dispatch. The inventory-containing batch reply cap is now explicit. Recheck
these corrections before committing the checkpoint and wiring the overview adapter. Its visible
absolute-expiry refresh, retained selection, hidden-view release, bounded row window and stale
reply guards still need both native-editor versions against the real server. The performance gate now also requires declaration bursts with
32 open 1 MiB leases, including the existing 16-waiter saturation and shared client byte budget.
These workloads have not run. Do not silently truncate discovery or raise caps as a substitute.
Review 12's prerequisite now shares bounded successful hash verification across workspace
source admissions; cold/evicted hashes and strict season-sized catalog scans still need
performance measurements. The cache has 256 entries and ten-minute idle expiry, does not retain
mappings or permissions, and may duplicate concurrent cold reads. Paged open/details now reuse
that owner. Improve disabled control contrast
during panel accessibility work; the current controls are functionally disabled.

Five minor review follow-ups remain:

- Replace the resolver enum exception for an unknown role with a public vocabulary hint.
- Stop asking for an import title that is discarded in favor of the envelope name.
- Consolidate the bundled Driver Station definition into the planned single four-role table.
- Drive cross-store import in the native fixture; its current same-store import proves the
  idempotent path. API tests and the independent review probe cover cross-store transfer.
- Expand the visualization tool reference's inner result fields before release.

### 9.3 Data Browser and Charts
Priority: High. Complexity: Medium.

Direct access to the data without an agent, and real data in an agent's answers.
[EXPLORER_PLAN.md](EXPLORER_PLAN.md) is the proposal and the specification, with milestones;
the notes below are what it grew from. Decided: the viewer is part of the WPILog Analyzer
extension, not a second extension, since a viewer and an analyzer that share a server, a
configuration, and a selection belong in one install, and the viewer needs no assistant to be
useful.

- **A data browser**: a webview that is an MCP client of the server it starts on loopback, or another configured server by URL.
  - It shows the logs from the listing, entries with types and counts, a plot of an entry or a struct field over a window with the match phases shaded behind it, statistics beside the plot, searchable console text, REV signals on the wpilog's clock.
  - A selection (an entry and a window) becomes a prefilled chat prompt, so a person moves from looking to asking without retyping; with no assistant configured, the browser stands alone.
  - Charts in the webview use a small time-series library bundled as a static asset; the extension's rule against runtime npm dependencies is about the Node side and should say so.
- **One server per laptop, shared by its clients**: done through explorer milestone 7. The standalone `http` server serves the viewer, VS Code’s agents, and terminal bridges. User/open-project directories are session leases with origin/team metadata; `connect` takes shared-server configuration from home or `--config`, and only directory/team leases from project YAML and flags. The extension’s private daemon and setting are removed; Claude Code uses one user-scope bridge registration. Recognized old project entries are retired conservatively. No operating-system service.
- **A team number per log directory**: done for leases, including several projects in one window. Logged team comes first, then the most specific live lease with a team, then the configured default. Per-directory teams in permanent YAML remain a possible later addition.
- **The foundation**: the extension's own MCP client over that server, the Logs and Entries views, and the custom editor that opens a `.wpilog` to its time range and entries. The second milestone, done.
- **The plot**: `read_entry` at a resolution, the data endpoint in Arrow and CSV, and the panes, timeline, and statistics in the editor. The third milestone, done.
- **Console, array elements, and the field view**: the text with its marks on the timeline, `[*]` paths as elements, and the pose on the season's field. The fourth milestone, done.
- **REV signals**: the endpoint serves them on the robot's clock with the alignment's basis, and the pane lists and plots them. The fifth milestone, done.
- **Organizing logs**: the sixth milestone is done: server import jobs and inbox batches, the command, explicit unassigned assignment, and the extension's opt-in persistent source enrollment, import into a user-chosen new or existing store, explicit in-place copy/move and robot picks, progress and result reporting, and trees by robot and session. Pure modules and transport clients have automated checks; the offer, quick pick, progress notification, and trees still need the manual real-VS-Code checks listed in EXPLORER_PLAN.md.
- **The extension installs and updates the standalone server**: done. The JAR owns the layout; shell installers, Gradle, and the extension delegate to it. The extension offers a missing install, updates older launchers without downgrading or rewriting settings, and provides an on-demand command. Scripts accept tags/pre-releases, prompt interactively, optionally install the matching `.vsix`, and support a settings-preserving refresh with a backup. Bootstrap flows from installer to extension; thereafter the extension updates the server and the Marketplace updates the extension. Real VS Code checks remain listed in EXPLORER_PLAN.md.
- **Data view and notebook:** explorer milestone 9 is done. Perspective supplies the grid, filters, pivots, quick charts and CSV on exact Arrow samples. Three-cell notebooks preserve the selection and server inputs; Python stays in the user's kernel.
- **Charts and assistant selections:** explorer milestone 10 is done. `render_chart` returns a headless PNG, versioned specification and full-window measurements with inputs. URI links open the server-validated selection; Ask about this selection uses VS Code chat with clipboard fallback. The own-specification-versus-Vega-Lite question and other assistants' prefill APIs remain open in EXPLORER_PLAN.md. MCP Apps remains a possible future interactive chat surface.

Two client boundaries remain. `ServerManager.dispose()` does not cancel an in-flight
`ensure()`, so disposal should prevent its pending work from publishing a server or client
later. `McpClient` and `DataClient` also have no client-side byte ceiling. Define limits for
responses and streams, cancel a request when it crosses them, and report the refusal without
leaving the editor waiting. Tests should dispose during startup and feed oversized responses
in small chunks.

### 9.5 Recorded History in Grafana

Plot stored sessions at full resolution in the Grafana that already shows the live metrics,
on a server install. [GRAFANA_PLAN.md](GRAFANA_PLAN.md) is the plan, its decisions answered on
2026-10-21: Grafana's Infinity data source reads the data endpoint's CSV with measured UTC,
Grafana's time range chooses the window, and a dashboard starts from a robot and a time range.
Live panels on the open capture come first. Panels across many sessions follow a measurement
of summaries the server keeps against a columnar store on server installs only, which the
[columnar plan](COLUMNAR_STORE_PLAN.md#revisited-for-server-installs-2026-10-21) reconsiders.

---

## Implementation Priority Matrix

| ID | Feature | Impact | Effort | Priority |
|----|---------|--------|--------|----------|
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

Explorer verification: milestone 11 drives real VS Code in CI at the oldest supported
version and current stable for activation, the shared server, fixture Logs listing and editor
opening. Rendered plots, interactive organizing flows and agent discovery retain the manual
checklist in DEVELOPMENT.md.


#### Paged Source Overview integration (2026-10-15)

The accepted inventory service and refusal corrections are committed as `2b9409f`. The next
checkpoint wires bounded discovery into Source Overview: paged rows, exact-type/literal filters,
fresh selected details, explicit retry, automatic visible expiry refresh and hidden/closed
cleanup. Live layout controls remain disabled. Native gates use a 20,000-name active recording
plus rollover and the real five-minute expiry; evidence is in
`/private/tmp/wpilog-visualization-stage3-overview/validation.md`.

Remaining before completing Stage 3: broker/panel integration for admitted view follows,
the configurable aggregate recorded/live decoded display budget and its capabilities, the
32-lease declaration-burst workload, season-sized catalog scan/hash measurements, and native
Linux/Windows editor runs. The current inventory accounting is a conservative bounded estimate,
not a measured process heap or sustained-throughput claim. Inspection of a finalized file opened
afresh still selects the recorded-file workflow; an already anchored recording can drain/finalize
under its existing reference. Keep these distinctions in the panel and performance gates.


Review 14's overview correction round adds single-owner initialization across native hide/reveal,
persistent known expiry through failed reopening, and an exclusive unavailable-declarations mode
that preserves ordinary filters. Evidence is under
`/private/tmp/wpilog-visualization-stage3-overview-fixes/validation.md`; the correction round is
committed with the overview as `f567d62`. The Stage 3 panel, combined-budget, performance and other-platform work
listed above remains deferred to its gates.


#### Recording compatibility (2026-10-16)

The shared HTTP/MCP compatibility operation now accepts recording references and checks complete
member inventories with bounded native evidence. It reports per-member absence, selector/unit
reasons and declaration/schema conflicts without creating a view or follow. The checkpoint is
committed as `4178768`, accepted by reviews 15 and 16; evidence is under
`/private/tmp/wpilog-visualization-stage3-bindings/validation.md`.
Recording-view metadata creation is implemented in the follow-up below. Remaining binding work
is now implemented by the revision-bound follow checkpoint below;
remaining work includes live panels, shared display budgets and the Stage 3 workload/performance gates above.
Cold hash/OS-read duration can outlast the cooperative compatibility work budget, and large
value-dependent searches can refuse; measure these costs on season-sized stores before making
latency claims or changing limits. Native Linux/Windows and the earlier manual Close/Save As
limitations remain unverified. No new editor, capture or transfer behavior is claimed here.


#### Recording-view metadata and retry advice (2026-10-16)

Create, saved-open and clone now accept anchored recordings through shared bounded admission,
then release the prefix before persisting metadata. The API retains unresolved intent, pinned
templates and existing request/revision semantics; it does not start a follow or enable live
panels. Temporary visualization read/admission refusals include the inventory service's
one-second retry advice. Recording layouts remain visible with disabled Resume/Open until
panel wiring. Evidence: `/private/tmp/wpilog-visualization-stage3-view-admission/validation.md`.

Review 15's repeated result-encoding cost near the 1 MiB cap remains to be measured/improved;
the latest-value evidence scan intentionally refuses beyond its stated history budget. Review
16's older class names in architecture prose and differing historical ledger formats are style
follow-ups, not changes to accepted behavior. Review 17 accepted this metadata checkpoint,
committed as `75c787c`. The next server checkpoint supplies revision-bound binding/follow
admission; evidence is in `/private/tmp/wpilog-visualization-stage3-bound-follows/validation.md`.
Live panels, automatic unresolved-requirement rechecks, broker adaptation to immutable view
bindings, combined budgets, display clocks and remote routing remain open. Measure the added
per-prefix compatibility work and two-slot contention alongside the 32-lease declaration burst
and season-sized store scan/hash workload. All native platform and manual gates remain open.


#### Recording panel continuation (2026-10-16)

The local preview now consumes revision-bound follows and paged in-view selection, with
completed-frontier plots, ordinal raw rows, pause and lifecycle cleanup, plus independent
server-read latest/cursor/statistics panels.
This supersedes the earlier disabled-panel notes above; evidence is under
`/private/tmp/wpilog-visualization-stage3-panel-follow/validation.md`.

The recording-aware latest/cursor/statistics server operation now exists across members, through
HTTP/MCP with bounded fresh-prefix admission and shared numerical rules; evidence is under
`/private/tmp/wpilog-visualization-stage3-recording-analysis/validation.md`. Panel integration now
uses coalesced requests, per-kind/data-generation guards, shared buffer accounting, pause/hide
cleanup and explicit independent prefix evidence; see
`/private/tmp/wpilog-visualization-stage3-panel-analysis/validation.md`. Presentation-only
revisions preserve the query. Keep Stage 3 open for pan/zoom
history, remote sources and latency/health instrumentation. The inline recording picker now accepts explicit numeric field paths and array indexes after server compatibility checks; it does not enumerate suggested paths. The next
continuation implemented shared recorded/live/inventory buffer admission, a 16–1024 MiB setting
(default 64, applied after reload) and client-reported attached-window capabilities; evidence is
under `/private/tmp/wpilog-visualization-stage3-window-budget/validation.md`. Measure actual heap
against those conservative estimates with both default and changed settings. Control/legacy
source descriptors and server caches are outside this sample allowance. Live plots still disclose
their 4,096-sample retention; neither budget is a claim that every long window is exact. Run the 32-lease declaration burst, season-sized inventory
scan/hash and sustained-throughput/resource gates before claiming full live support. Native
Linux/Windows, manual Close cancellation and Save As remain outstanding.


Recording-analysis panel fixture follow-up (2026-10-16): one intentionally cursor-broken native
run stalled at peer-close before the cursor assertion. The restored implementation passed four
final runs across both editors; no cause or fix is claimed for that isolated occurrence. Keep
its diagnostics plus the new owner/queue/request traces under
`/private/tmp/wpilog-visualization-stage3-panel-analysis/` and investigate a recurrence before CI
adoption, without masking it with a blanket retry. Linux/Windows native and sustained-load
gates remain separate work.


Recording field selection continuation (2026-10-16): explicit typed paths now work in the
paged picker, with member-qualified refusals and stale-intent cancellation. Evidence is in
`/private/tmp/wpilog-visualization-stage3-field-selection/validation.md`. Schema-derived path
suggestions remain a usability option: implement them in the shared server owner, with bounded
array evidence, rather than duplicating schema interpretation in the extension. This does not
complete the remaining Stage 3 panel, history, remote, recovery or performance gates above.


Source chart analysis continuation (2026-10-16): bounded histogram/scatter operations now share
the existing image-chart numerical owner for files and anchored recordings. The HTTP/MCP
contracts, native-writer proof and regression evidence live under
`/private/tmp/wpilog-visualization-stage3-chart-analysis/validation.md`. The chart-panel continuation now wires histogram and
scatter controls through the existing window queue, at most one update per second, acknowledged
buffer accounting and pause/hide/close cancellation. Native checks cover both source kinds; see
`/private/tmp/wpilog-visualization-stage3-chart-panels/validation.md`.
Do not compute either from plot buffers. Retained scatter paging for a live prefix is a possible
future improvement; today each call is fresh and callers compare prefix evidence. Numeric
seconds that cannot retain native microseconds, and unrepresentable histogram edges, currently
refuse. Larger-range numerical representations need shared arithmetic work before lifting those
limits. The other Stage 3 navigation, remote, recovery, platform and performance gates remain.


Chart-panel drawing follow-up: the new preview explicitly supplies per-series point bounds to
the bundled mode-2 plot and ranges histogram axes over full bin edges from a zero count baseline.
The older chart-link pane uses the library defaults that caused invisible points and clipped bins
in the new prototype. Audit its drawing natively before changing it; its measurement specification
was not at fault, and this checkpoint does not claim a native test or fix for that older pane.


Historical-navigation continuation (2026-10-16): the local preview now connects time-series drag
zoom and keyboard pan/zoom to revisioned interval reads, with Inspect History and Resume
following. Evidence is in `/private/tmp/wpilog-visualization-stage3-history/validation.md`.
At the historical-navigation checkpoint, dense recording history kept only 4,096 recently delivered points per selection; it was
not a complete min/max representation. The dense-history continuation below replaces that
limit for fixed/full history with shared server reduction. The fixed saved policy has no following
window field; the current editor remembers its resume choice in VS Code UI state, while a new
fixed-view fork defaults to 30 seconds. Remote routing, acquisition health/latency, unavailable
source recovery, Linux/Windows native runs and sustained performance remain open.

#### Health/recovery checkpoint follow-up (2026-10-17)

Source health and complete editor reattachment recovery are implemented in the current Stage 3
checkpoint. Recording-history reduction is implemented in the next local checkpoint; continue with the pending workload/platform gates.
The native recovery fixture replaces HTTP/session/workspace owners while acquisition continues
and starts a fresh Java process after finalization, restoring the same working view and exact
samples. Add a combined process-crash/restart test for live laptop capture and mirroring. Existing named
server startup and recorded-workflow process restart tests are separate evidence, not that gate.
Clock-domain timing is observable; server/host/renderer offset calibration and end-to-end p95
remain unmeasured. Season-sized catalog scans, declaration bursts with 32 leases, sustained
throughput, multi-member rollovers, Linux/Windows native runs and the manual Close cancellation
check remain outstanding. Save As still refuses with the Save view hint.

Native recovery testing exposed an inventory leak: cancelling an admitted creation destroyed the
response before its opaque handle could be closed. Creation now keeps its bounded 15-second
request alive, then closes a late handle in its original session if the view cancelled. Reads
remain immediately cancellable. The real-HTTP regression and plant reproduce that fault.
The fixture also closes its unused opening overview before peer workflows and waits for the
final frame to enable Full recording. Diagnostics preserve inventory owners, delivery state and
rendered text. Both editor versions now pass after the product fix. Network failure before the
handle response still relies on server expiry; native Linux/Windows reliability remains unproven.

Dense historical recording reads now reuse the native merge/reducer across a fresh proven
prefix. Fixed/full plots preserve whole-interval extrema and counts under explicit work and
byte caps; Follow keeps its incremental limit. This closes the trimmed-history implementation
item, with evidence in `/private/tmp/wpilog-visualization-stage3-reduction/validation.md`.
Measure its repeated historical reads during late appends, two-member sorting and shared
window pressure in the sustained gate; there is no historical reduction cache yet. Preserve
strict catalog reuse while measuring the season-sized inventory scan/hash cost, and include
the 32-lease declaration burst. Platform runs, manual Close cancellation and Save As remain
open as recorded in the visualization plan.

#### Shared bucket means at extreme dynamic range (review C1)

The shared reducer deliberately retains legacy sequential summation. For exact floating-point
inputs `[1e16, 1, -1e16]`, it reports mean 0 instead of 1/3; counts and extrema remain correct.
Consider Neumaier compensation as a separate compatibility change across native and legacy
consumers, with independent cancellation and finite-input-overflow oracles. The current review
fixes do not change this arithmetic. This remains separate from the Stage 3 workload and
platform gates above.
