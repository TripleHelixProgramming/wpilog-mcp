# wpilog-mcp Tool Reference

What each wpilog-mcp tool takes, what it does, and what it returns. Every tool that reads a log takes a required `path` (from `list_available_logs`) and loads the log on first use. [TOOL_RESPONSES.md](TOOL_RESPONSES.md) shows the JSON each tool returned on real logs.

For fresh data, use `list_sessions`, `get_latest_values` and `wait_for_change`. The current
capture is the file `list_available_logs` marks `open`. The MCP resource `pit://session/current`
is discoverable without a tool call: it returns session identity/file/start/connection,
gateway status and providers, or `not_applicable` with a reason when no session is open.
It uses published snapshots and supports reads, not resource subscriptions; prompts remain empty.

Every tool with a time scope accepts `last_seconds`: a positive finite N, ending at the open
capture's estimated robot time fixed for that call, or a closed log's last record. Without an
estimate, the captured prefix's end is the anchor. It cannot be combined with `start_time` or
`end_time`; scope/windows intersect it. `inputs.last_seconds` records N and `inputs.window`
the resolved absolute start/end; `compare_matches` resolves independently for each log and
reports `inputs.windows` keyed by path. `inputs.session_time_range` still reports the captured
prefix's range, not an assertion that fresh values were published throughout that window.

## Table of Contents

- [Discovery Tools](#discovery-tools)
  - [get_server_guide](#get_server_guide)
  - [suggest_tools](#suggest_tools)
- [Live Tools](#live-tools)
  - [list_sessions](#list_sessions)
  - [get_latest_values](#get_latest_values)
  - [wait_for_change](#wait_for_change)
- [Core Tools](#core-tools)
  - [list_available_logs](#list_available_logs)
  - [list_loaded_logs](#list_loaded_logs)
  - [list_entries](#list_entries)
  - [get_entry_info](#get_entry_info)
  - [read_entry](#read_entry)
  - [render_chart](#render_chart)
  - [list_struct_types](#list_struct_types)
  - [resolve_signals](#resolve_signals)
  - [health_check](#health_check)
- [Query Tools](#query-tools)
  - [search_entries](#search_entries)
  - [get_types](#get_types)
  - [find_condition](#find_condition)
  - [search_strings](#search_strings)
  - [search_system_logs](#search_system_logs)
- [Statistics Tools](#statistics-tools)
  - [Field paths](#field-paths)
  - [Scopes and windows](#scopes-and-windows)
  - [get_statistics](#get_statistics)
  - [compare_entries](#compare_entries)
  - [detect_anomalies](#detect_anomalies)
  - [find_peaks](#find_peaks)
  - [rate_of_change](#rate_of_change)
  - [time_correlate](#time_correlate)
  - [align_entries](#align_entries)
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
  - [compare_poses](#compare_poses)
  - [pose_corrections](#pose_corrections)
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
  - [TBA enrichment](#tba-enrichment)
  - [get_tba_status](#get_tba_status)
  - [get_tba_match_data](#get_tba_match_data)
- [RevLog Tools](#revlog-tools)
  - [list_revlog_signals](#list_revlog_signals)
  - [get_revlog_data](#get_revlog_data)
  - [sync_status](#sync_status)
  - [set_revlog_offset](#set_revlog_offset)
  - [wait_for_sync](#wait_for_sync)
- [Data Types](#data-types)
  - [Primitive types](#primitive-types)
  - [Structs](#structs)
- [Server Instructions](#server-instructions)
- [Response Fields](#response-fields)
  - [Result contract](#result-contract-success-status-and-related-fields)
  - [data_quality](#data_quality)
  - [server_analysis_directives](#server_analysis_directives)

---

## Discovery Tools

These two tools tell an agent which tools exist and when to use them. The description of `get_server_guide` asks the agent to call it first.

### `get_server_guide`

`server_location` identifies this server as local or the pit server and explains that completed mirrors answer from the same bytes offline.
An overview of every tool, grouped by category, with usage guidance and the mistakes each category is meant to prevent. Its `tools/list` entry carries `_meta: {"anthropic/alwaysLoad": true}`, so Claude Code keeps the description loaded even when it defers other MCP tools.

**Parameters:**
- `category` (optional): Only this category: `core`, `query`, `statistics`, `robot_analysis`, `frc_domain`, `export`, `tba`, `revlog`, `discovery`, or `live` (capture enabled). Any other value is an error that lists the categories
- `include_examples` (optional): Include example uses for each tool (default: true)

**Returns:**
- `overview`: server name, version, total tool count, purpose
- `critical_guidance`: `primary_rule`, `tba_tip`, `statistics_tip`, `match_phases_tip` (for example, use `get_statistics` and `time_correlate` instead of computing statistics by hand)
- `analysis_principles`: general reasoning guidance for the agent (see [Server Instructions](#server-instructions)): the scientific-method loop for causal questions, what `confidence_level` does and does not bound, a list of confabulation traps with the tool call that avoids each, cross-match rules, entry naming conventions, units, and pit vs. deep-dive report formats
- `architecture`: concurrency, transports, and how logs are loaded and evicted
- `categories`: each category's `name`, `description`, `anti_pattern`, `tool_count`, and `tools` (`name`, `description`, `requires_log`, `example_uses`, `related_tools`)
- `common_workflows`: step lists for basic match analysis, cycle times, and brownout investigation

### `suggest_tools`
Recommends tools for a task described in plain language, with a suggested order. Scoring is keyword matching against each tool's keywords, example uses, and name, so phrase the task with the subsystem or symptom ("brownout", "swerve", "vision", "cycle").

**Parameters:**
- `task` (required): What you want to analyze (e.g., "check why our auto was inconsistent" or "investigate brownout during teleop")
- `max_suggestions` (optional): Maximum number of tools to suggest (default: 5)

**Returns:**
- `task` (lowercased), `suggestions` (each with `tool`, `description`, `relevance_score`, `category`, `example_uses`, and `related_tools`), and `suggestion_count`
- `suggested_workflow`: the suggested tools in order, starting with `list_available_logs`
- `anti_patterns`: mistakes to avoid for this kind of task, when the task mentions statistics, correlation, scores, auto/teleop, or battery/brownout/voltage
- `status: no_match` with a `hint` to call `get_server_guide` when nothing matches

**Example** (captured in [TOOL_RESPONSES.md](TOOL_RESPONSES.md#suggest_tools), abridged):
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
      "example_uses": ["Find auto start/end times", "Get teleop duration", "Detect match structure"],
      "related_tools": ["analyze_auto", "get_ds_timeline"]
    },
    {
      "tool": "get_tba_match_data",
      "description": "Query match scores and results from The Blue Alliance",
      "relevance_score": 2,
      "category": "tba"
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
The task said "brown out" (two words), so the keyword `brownout` did not match and `power_analysis` was not suggested.

---

## Live Tools

These three tools are registered only when capture is enabled, in the catalog's Live category.
They open no file. Every result carries `inputs.session`, the current or last capture path
(null before a session begins). Other tools read that path through the live log as usual.
These are publication facts, not statistical inferences, so they carry no data-quality score.

### `list_sessions`

Current and recent store sessions, newest first. Top-level `managed` is true only when the
server was started with `--managed`; a hand-written systemd unit must pass that flag.
An inherited `INVOCATION_ID` does not set it. Use `systemctl`, not the daemon
manager, to stop or update it. This field is present even when capture is disabled.

The top-level `gateway` reports `state` (`disabled`, `waiting`, `listening`, `stopped`), `port`,
`cause` while waiting, and `since` (UTC state-change time, null when disabled). It is the same
published view as `GET /health`; a waiting gateway does not stop capture or pulling.

**Parameters:**

- `limit` (optional): Newest sessions to return, from 1 to 100; default 20.

**Returns:** `sessions[]` with `id`, `path` (capture, or null), `started_at`, `ended_at` (null
while open), `robot` (`serial_number`, `comments`, `address`, `basis`), `connected`,
`topic_count`, `records`, `bytes`, `bytes_per_sec`, `event`, `match` (`type`, `number`),
`cost[]`, `thinned[]`, `excluded[]`, `imports[]`, `providers[]`, `end_reason` and `counts_basis`.
`cost` contains the ten topics with the greatest last-minute byte rate, each with `name`,
`records`, `bytes` and `bytes_per_sec`; ties are sorted by name. Counts cover captured value
records across rollover files, including record headers, excluding declarations, finishes, context and copied rollover schema seeds. The writer publishes counts every 250 ms. Rates divide bytes in the last minute
by 60 seconds, including at startup; closed-session rates are null. Older manifests without
recorder summaries have null counts with a reason, never a new scan of their files. Crash
recovery clears a possibly stale summary, so recovered sessions also report unknown counts.
`thinned` lists `prefix` and `period_sec`; `excluded` lists configured prefixes.
`imports` lists each non-capture file's `path`, `method`, `offset_sec` and `reason`:
correlation uses its recorded offset; time-overlap placement has a null offset.
`limits.sessions` and each row's `limits.cost` report true totals when cut. An empty store
is `not_applicable` with its reason.

`providers[]` contains `name`, `state`, `reason` (stand-down or partial-sample explanation),
`period_sec`, `last_round_trip_ms`, `robot_cpu_sec` (whole-robot processor time between samples,
not CPU attributed to this provider), `lines_per_sec` (accepted in the current second),
`dropped_lines`, `dropped_before_sync`, `records`, `bytes`, `sample_bytes` (last stats reply),
and `program_pids` (program PIDs observed in this session, used for crash-file placement).
Unknown measurements are null. Provider records/bytes cover the session across rollover files;
drop counters cover the provider's process lifetime. They are separate from NT4 topic counts.
The same provider summary is retained in the manifest; old manifests return an empty list.

### `get_latest_values`

Read the latest values by NT4 topic name, its `NT:` capture name, or a `/Daemon/` provider entry name.

**Parameters:**

- `entries` (required): Array of 1 to 2000 nonempty names; repeated names are returned once.

**Returns:** `values[]` (`name`, `value`, `timestamp_sec`, `age_ms`, `type`, `source`) and `missing[]`. `source` is `nt4`, `ssh`, or `tail`.
The timestamp is the robot's clock. Age is robot now minus that timestamp, using measured NT4
time sync, independent of the laptop's calendar clock; before sync it is null. A future
publisher timestamp can have a negative age. Type is the announce's authoritative NT4 string.
Binary values, including structs, are signed-byte arrays; use the ordinary log tools to decode
structs. Raw NaN and infinities are strings. Missing some names is `partial` with `skipped`;
all missing is `no_match` with `looked_for` and `hint`. No open session is `not_applicable`
with `last_session` and `ended_at`. A stale age means the topic stopped publishing, not that
the robot stopped. These are the latest published values, not fresh measurements on demand.

### `wait_for_change`

Wait for the first publication received after the waiter is installed, even if its value is
unchanged. NT4 topic names and `NT:` capture aliases identify the same waiter.

**Parameters:**

- `entry` (required): One nonempty topic name.
- `timeout_ms` (optional): Nonnegative integer; default 5000, capped at 30000.

**Returns:** `changed`; when true, also `name`, `value`, `type`, `timestamp_sec` and `age_ms`,
with the same meaning as `get_latest_values`. Timeout is `ok` with `changed: false`.
One outstanding wait per topic per MCP session is allowed; a second is an explained `error`.
An unknown topic is `no_match` with `looked_for` and `hint`. No open capture is
`not_applicable` with `last_session` and `ended_at`; disconnect or unannounce cancels a wait
with `not_applicable` and `reason`. Waiting blocks its HTTP request, never the NT4 listener.

---

## Core Tools

### `list_available_logs`
List WPILOG files in the configured log directories with user-friendly names, newest first.

**Parameters:**
- `name` (optional): Only logs whose file or friendly name contains this (case-insensitive)
- `event` (optional): Only logs from this event code (case-insensitive exact match, e.g. `VACHE`)
- `match_type` (optional): `p`, `q` (or `qm`), `qf`, `sf`, `f`, or `e`; anything else is an error. A log with no match (a file named for an event only) has no match type and matches none of them
- `since` (optional): Only logs from this date on (`2026-03-20`, midnight UTC), or from an ISO-8601 instant
- `offset`, `limit` (optional): Paging (default limit 50, max 500)

**Returns:** `log_directories`, `log_directory_paths`, `log_count` (logs matching the filters), `total_logs` (in all the directories), `offset`, `returned`, `has_more`, `tba_enrichment`, `metadata_cache`, `logs` (the page), and `limits.logs`. Only the listed page is enriched with TBA data. When no log matches the filters, the status is `no_match`. A directory that could not be read makes the result `partial`, with an entry in `skipped` (`section: "logs"`, `directory`, `reason`). When no directory can be read, the call is an error naming each directory and why.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "log_directories": [{"path":"/Users/team2363/Documents/FRC/logs","origin":"configured","team":2363}],
  "log_directory_paths": ["/Users/team2363/Documents/FRC/logs"],
  "log_count": 2,
  "total_logs": 2,
  "offset": 0,
  "returned": 2,
  "has_more": false,
  "tba_enrichment": {"available": true},
  "metadata_cache": {
    "size": 2,
    "hits": 1,
    "misses": 1
  },
  "logs": [
    {
      "friendly_name": "VADC Qualification 42",
      "path": "/Users/team2363/Documents/FRC/logs/akit_24-03-16_15-20-00_vadc_q42.wpilog",
      "filename": "akit_24-03-16_15-20-00_vadc_q42.wpilog",
      "event": "VADC",
      "match_type": "Qualification",
      "match_number": 42,
      "team_number": 2363,
      "size_bytes": 15234567,
      "last_modified": 1710523456000,
      "tba": {
        "team_number": 2363,
        "match_key": "2024vadc_qm42",
        "lookup_method": "direct",
        "alliance": "red",
        "score": 85,
        "won": true,
        "opponent_score": 72,
        "actual_time": 1710432000,
        "scheduled_time": 1710431700
      }
    },
    {
      "friendly_name": "VADC",
      "path": "/Users/team2363/Documents/FRC/logs/akit_24-03-15_10-02-11_vadc.wpilog",
      "filename": "akit_24-03-15_10-02-11_vadc.wpilog",
      "event": "VADC",
      "team_number": 2363,
      "size_bytes": 8765432,
      "last_modified": 1710512345000
    }
  ],
  "limits": {"logs": {"total": 2, "returned": 2, "limit": 50}}
}
```

**Response Fields:**
- `log_directories`: Every configured or leased directory as `{path, origin: "configured" | "leased", team}` (team may be null), permanent configuration first; a duplicate configured path keeps its configured origin.
- `logs[].matching_reason`: For a pulled file kept in its own session, why it was not automatically matched (insufficient correlation, an offset beyond 250 ms, ambiguous sessions, or clock/identity disagreement). The file remains retrievable by `path`.
- `log_directory_paths`: The same directories as plain path strings for consumers needing paths. A log reached from two of them (nested directories, or one directory under two names) is listed once
- `skipped`: Present when a directory could not be read (it does not exist, is not a directory, or could not be read: a drive not mounted, no permission). That directory's logs are missing from the list, not from the disk, and the status is `partial`
- `tba_enrichment`: `{"available": true}` when The Blue Alliance answered for this page; `{"available": false, "reason": ...}` when the key is not configured, TBA could not be reached, or the key was rejected. In those cases no log carries a `tba` field, and that says nothing about whether TBA has data for it
- `metadata_cache`: Cache statistics for log file metadata (`size`, `hits`, `misses`)
- `team_number`: From the log's `SystemStats/TeamNumber` entry (AdvantageKit records it), else the configured default team (`-team`, `WPILOG_TEAM`, or `team` in the server configuration)
- `tba`: TBA data for the match (see [TBA enrichment](#tba-enrichment)): `team_number`, `match_key`, `lookup_method`, `alliance`, `score`, `won`, `opponent_score`, `actual_time` and `scheduled_time` (epoch seconds, each with a `_local` form in the event's time zone when known). `match_key` is the TBA match the data came from and `lookup_method` how it was found: `direct` (a key built from the match type and number), `double_elimination_bracket` (a Driver Station "Elimination N" read as bracket match N, TBA's `sfNm1`, for 2023 and later), `nearest_time` (the team's playoff match nearest the log's file-name time, for the finals, which carry no bracket number), or `play_order` (before 2023: playoff match number N in the order the team played, a heuristic). The last three carry a `lookup_basis` sentence.

**Log directories:** at least one configured directory or live session lease is required: `-logdir` (repeatable), `WPILOG_DIR`, or `logdir` in the server configuration (a path or a list). Without one, the call is an error that says how to set it. Local clients may register directories through `POST /directories` (not a tool); see [Directories by lease](STANDALONE.md#directories-by-lease). A logged team wins, then the matching lease team, then the configured default.

**Event, match, and team:** read from the entries that carry them by convention, among the log's first records, and otherwise from the file name.

- Entries: a string `DriverStation/EventName` or `FMSInfo/EventName`; integer `MatchType` (1 practice, 2 qualification, 3 elimination) and `MatchNumber` in the same tables; an integer `SystemStats/TeamNumber`. These are AdvantageKit's tables and the NetworkTables table WPILib's DataLogManager records (`NT:/FMSInfo/...`). An entry of a team's own with a similar name (a `GameState/MatchType` string, a scoreboard table) is not read. A match number counts only while a match type is set: with no match, the Driver Station's number can hold anything.
- File names, in the two forms the logging frameworks write. WPILib's DataLogManager: `FRC_<yyyyMMdd>_<HHmmss>.wpilog`, and `FRC_<yyyyMMdd>_<HHmmss>_<EVENT>_<P|Q|E><number>.wpilog` once the field has given a match. AdvantageKit: `<prefix>_<yy-MM-dd>_<HH-mm-ss>[_<event>][_<type><number>][_sim].wpilog`.

The robot program starts logging before the Driver Station connects, so the first records usually hold no event or match, and the name the framework gave the file later is what carries them. The match type and number are taken together, from the records or from the name.

- `FRC_20260321_162956_VACHE_Q10.wpilog` → "VACHE Qualification 10"
- `akit_26-03-21_16-29-56_vache_q10.wpilog` → "VACHE Qualification 10"
- `akit_26-03-22_18-44-53_vache.wpilog` → "VACHE", with no match type: a name with an event and no match says the Driver Station gave an event name and no match, which it does off the field too. A practice match is named `_p3`
- `akit_26-03-22_18-44-53.wpilog` and `FRC_20260322_184453.wpilog` → listed under the file name, with the time read from it
- `frc_25-03-15_10-30-00_vadc_qm42_sim.wpilog` → "VADC Qualification (sim) 42"

Match type codes are `p`, `q` or `qm`, `qf`, `sf`, `f`, and `e`. A file whose name is in neither form (a renamed log, a copy with a suffix) keeps its time when the name still holds one, and nothing else is read from it. The match in a file name is what the framework read from the Driver Station when it named the file. Off the field the Driver Station can report a match type with a number in the tens of thousands for a moment, and a file named then keeps it (`..._p63036.wpilog`); such a number is not a match.

The file-name time is read as UTC (the roboRIO's default zone, and the zone DataLogManager always uses), or in the server's local zone for a `_sim` log. It orders the listing (newest first; the file's modification time when the name carries no time), and it is the time the `since` filter and TBA's `nearest_time` lookup use.

For a store (a configured directory with `store.json`), `logs` comes from session manifests, including files beyond the ordinary directory scan depth. The `stores` array names each store (`path`, `robots` with the same robot fields). Each log row carries `store` (its root), `revlogs` (its correlated companions, each with `path`, `filename`, and `size_bytes`), `robot` (`id`, `name`, `serial_number`, `comments` when known, `basis`: `logged`, `device`, `stated`, or `address` (known only by its connection address)) and `session` (`id`, `path`, `started_at`, `ended_at`, `start_basis`). A captured file also carries `session.open`: its event and match come from the manifest even while Windows defers the directory rename. Two directories with the same serial describe one robot but remain separate histories. Store facts override filenames. Plain-directory logs also carry `robot` when `/SystemStats/SerialNumber` and `/SystemStats/Comments`
(or their `NT:` forms) supply it within the first 2000 records. Import inspection can read later identity. This logged identity wins over the store's device identity.
A store robot can also carry `contacts` (`address`, `host_key_fingerprint`, `seen_at`) documenting SSH
key history. Store rows without a serial can carry `robot_candidates`: each has `serial_number` and
`evidence` (`kind`, `value`). Kinds are `logged_team_number`, `entry_set`, and `rev_can_inventory`;
the latter two values are hashes of exact sorted fingerprints, persisted by import inspection in
the manifest. Listing never scans a file for candidate evidence. Plain files and older store
manifests without fingerprints have no candidates. Each kind must match exactly one
known serial; conflicting hints are omitted. This never assigns the file. Unassigned REV rows can
carry the same candidates. With `capture.pull` enabled, partial transfers stay out of the log listing; verified copies appear under their session with device identity unless the file logs its own serial. A logged/device disagreement is kept in the session manifest and server log. Moved paths remain usable by tools after the seven-day listing notice. Windowed mapping supports WPILOG files through 1 TiB. A file beyond that bound remains in `logs` with an explained `read_error`.

Store listings additionally return:

- `inbox`: files in a store's drop folder, each with `store`, `path`, `size` in bytes, `stated_robot` (null for loose files), and `state` (`waiting`, `importing`, or `refused` with `reason`). They belong to no session until imported. Receipts are in `inbox/imported.log`; the receipt file, batch sidecars, and dot-prefixed inbox entries are not listed. A malformed `batch.json` refuses the batch with a reason and receipt; a changed sidecar permits retry. Abandoned hidden transfers are removed with a receipt on the next poll. Store discovery runs at watcher startup, during listing scans, and otherwise at most once a minute; known inboxes are polled every three seconds.
- `unmanaged`: files outside the inbox absent from every manifest, each with `store`, `path`, and `reason`; they are never indexed into a session, even if placed inside its directory.
- `unassigned`: imported files awaiting robot assignment or a unique REV pairing, each with `store`, `path`, `kind`, and `sha256`.
- `moved_to`: notices with `original_path`, `moved_to`, and `moved_at`, retained in the listing for seven days after a move. Copy imports create no notice.

For a mirrored log, `session` also reports `origin` (the origin URL), `complete`, `growing`,
`last_sync` and `age_sec`. The last successful synchronization stays visible offline. The
`stores` summary marks `mirror` and includes its origin, pins and freshness. A mirror refuses
imports and leaves unmanaged files untouched; pinning is a local HTTP UI control, not a tool.

A REV companion with corrupt recorded alignment carries `read_error` naming its manifest.
It stays unsynchronized: `sync_status` explains the failure, and REV value tools cannot use its
clock until a known offset is supplied. Other companions retain their own recorded alignments.

An unsupported store format is reported as an unavailable directory with its reason; when no directory can be read the result is `error`. The standalone [import command and inbox](STANDALONE.md#importing-logs) and [HTTP jobs](STANDALONE.md#the-import-endpoint) organize files; the extension's [organizing controls](../vscode-extension/README.md#organizing-your-logs) offer the same pipeline and explicit assignment.

### `list_loaded_logs`
List the log files currently loaded in the server's cache, and the cache status. Logs load on demand, so an empty list is normal.

**Parameters:** None

**Returns:** `loaded_count`; `logs` (path order), each with `path`, `entry_count`, and `duration_sec`; and `cache` with `loaded_count`, `heap_used_mb`, and `heap_max_mb`. Logs are evicted after 30 minutes idle, or sooner when the heap runs short.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "loaded_count": 2,
  "logs": [
    { "path": "/Users/team2363/logs/akit_26-03-21_16-29-56_vache_q10.wpilog", "entry_count": 412, "duration_sec": 163.2 },
    { "path": "/Users/team2363/logs/akit_26-03-21_18-02-14_vache_q22.wpilog", "entry_count": 409, "duration_sec": 158.9 }
  ],
  "cache": { "loaded_count": 2, "heap_used_mb": 612, "heap_max_mb": 4096 }
}
```

### `list_entries`
List the entries in a log, optionally only those whose name contains `pattern`. Returns `no_match`, naming the pattern, when it matches no entry (or the log has none).

**Parameters:**
- `path` (required): Path to the log file
- `pattern` (optional): Only entries whose name contains this (case-insensitive)

**Returns:** `log_path`, `entry_count`, `time_range_sec` (`start`, `end`, `duration` of the whole log), `truncated` and a `warning` when the file is truncated or damaged, and `entries` in name order, each with `name`, `type`, and `sample_count`. When the list includes struct or array entries, `note` says how numeric tools address their fields ([Field paths](#field-paths)).

### `get_entry_info`
Describe one entry: what it is, how it decodes, and what it looks like. An unknown name is an error, with up to five `suggestions` whose names contain it.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): The entry name (e.g., `/Drive/Odometry/Pose`)

**Returns:**
- `type`, `metadata`, `sample_count`, and `time_range_sec` (when the entry has samples)
- `sample_values`: three representative samples, the first, middle, and last among the non-empty values (an empty struct array or string is not representative). An array longer than 20 elements is cut, with `value_length` and `value_truncated`
- `non_empty_sample_count` (array, string, json, and raw entries): how many values are not empty, for example how many `PoseObservations` records held at least one observation
- `struct` (struct entries): `name`, `is_array`, `source` (`logged`: the log's own `/.schema/struct:` entry; `wpilib`: WPILib's schema, used because the log records none; `assumed`: a template layout that may not match the team's struct; `missing`: no schema at all), `source_note`, `size_bytes`, `schema`, `schema_entry`, and `fields` (`name`, `type`, and when present `array_size`, `bit_width`, `enum`)
- `numeric_leaf_paths`: the numeric fields inside the entry's values, relative to the entry (`.translation.x`, `.currents[1]`, `[*].tagCount` for each element of an array; enum fields and booleans count as numbers). Numeric tools accept these appended to the entry name
- `decode_problem`: `{failed_records, total_records, reason}` when some records could not be decoded

**Example Response (struct array):**
```json
{
  "success": true,
  "status": "ok",
  "name": "/Vision/Camera0/PoseObservations",
  "type": "struct:PoseObservation[]",
  "sample_count": 5000,
  "struct": {
    "name": "PoseObservation",
    "source": "logged",
    "source_note": "decoded by the schema this log records",
    "valid": true,
    "size_bytes": 88,
    "schema": "double timestamp;Pose3d pose;double ambiguity;int32 tagCount;double averageTagDistance;enum {MEGATAG_1=0, MEGATAG_2=1, PHOTONVISION=2} int32 type;",
    "schema_entry": "/.schema/struct:PoseObservation",
    "fields": [
      {"name": "timestamp", "type": "double"},
      {"name": "pose", "type": "Pose3d"},
      {"name": "ambiguity", "type": "double"},
      {"name": "tagCount", "type": "int32"},
      {"name": "averageTagDistance", "type": "double"},
      {"name": "type", "type": "int32", "enum": {"MEGATAG_1": 0, "MEGATAG_2": 1, "PHOTONVISION": 2}}
    ],
    "is_array": true
  },
  "numeric_leaf_paths": ["[*].timestamp", "[*].pose.translation.x", "[*].pose.translation.y",
    "[*].pose.translation.z", "[*].pose.rotation.q.w", "...", "[*].pose.rotation._derived.yaw_deg",
    "[*].ambiguity", "[*].tagCount", "[*].averageTagDistance", "[*].type"],
  "non_empty_sample_count": 4943,
  "sample_values": ["..."]
}
```

### `read_entry`
Read an entry's values in time order, one page at a time, optionally within a time range. A NaN or infinite value is returned as the string `NaN`, `Infinity`, or `-Infinity` (JSON has no such numbers, and `null` would read as missing). One page is not the whole signal: use `get_statistics`, `find_condition`, or `find_peaks` for claims about a window.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): The entry name
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `limit` (optional): Max samples to return (default 100; larger values are cut to 10000; zero or less is an error)
- `offset` (optional): Samples to skip (default 0; negative is an error)
- `max_points` (optional): Read at a resolution: at most this many buckets over the window (see below; 1 to 10000, zero or less is an error)

**Returns:** `name`, `type`, `total_in_range` (the true count), `returned_count`, `offset`, `limit`, `has_more`, `samples` (each `timestamp_sec` and `value`), and `limits.samples` (total after `offset` vs returned). An unknown entry name is an error with suggestions.

**At a resolution (`max_points`):** for a numeric entry, or an entry with a [field path](#field-paths) appended as `get_statistics` takes it. When the window holds no more samples than `max_points`, the read is exact, as above, with `bucketed: false`. When it holds more, the window (`start_time` to `end_time`, or the samples' own span where a bound is not given) is divided into `max_points` buckets of equal duration, `bucket_sec` long, the last one including the window's end, and `samples` holds one object per bucket that has samples: `timestamp_sec` (the bucket's start), `count`, `min`, `max`, `mean` (over the finite samples; `null` when none is finite), `first`, and `last` (the first and last samples as logged). The extremes are kept because a spike one sample wide is what a person looks for, and a mean would hide it; `first` and `last` let a change-only entry's holds be drawn across a bucket. Buckets with no samples are left out: a gap in a change-only entry is a hold, not missing data. `bucketed: true`, `bucket_count` is how many buckets have samples, `total_in_range` stays the true sample count, and `offset`/`limit` page the buckets (`limit` defaults to `max_points`). A non-numeric entry is read exactly, with `max_points` in `skipped` and the status `partial`. The data endpoint of the HTTP transport (see [STANDALONE.md](STANDALONE.md#the-data-endpoint)) buckets by the same rule.

**Example Response (bucketed):**
```json
{
  "success": true,
  "status": "ok",
  "name": "/SystemStats/BatteryVoltage",
  "type": "double",
  "total_in_range": 1001,
  "max_points": 4,
  "bucketed": true,
  "bucket_sec": 5.0,
  "bucket_count": 4,
  "samples": [
    {"timestamp_sec": 20.0, "count": 250, "min": 11.08, "max": 12.2, "mean": 11.46, "first": 12.2, "last": 11.08},
    "..."
  ]
}
```

**Struct values** are decoded by the log's own schema for the type; [Structs](#structs) shows what a decoded value looks like. An entry none of whose records can be decoded is an error that says why; records that fail among others are reported in `warnings` and `_metadata.decode_problems`.

**Example Response (Pose2d):**
```json
{
  "success": true,
  "status": "ok",
  "name": "/Drive/Odometry/Pose",
  "type": "struct:Pose2d",
  "samples": [
    {
      "timestamp_sec": 0.02,
      "value": {
        "translation": {"x": 1.54, "y": 5.55},
        "rotation": {"value": 0.0, "_derived": {"degrees": 0.0}}
      }
    }
  ]
}
```

### `render_chart`
Draw a PNG beside a reproducible chart specification and full-window measurements. Use `name`
for one entry or numeric field path, or `entries` for several. Images are MCP `image` content;
the adjacent `text` block is the ordinary JSON result, with the same status and inputs contract.
A runtime without headless imaging returns the measurements with `status: partial` and an
image reason in `skipped`. No plotting dependency or native library is installed by the server.

**Parameters:**
- `path` (required): Log file, including an open capture.
- `name` (optional): One entry or numeric field path; mutually exclusive with `entries`.
- `entries` (optional): One to sixteen entry names or numeric field paths in drawing order. Pass this or `name`.
- `kind` (optional): `time_series` (default), `histogram`, `scatter` (exactly two numeric series), or `field` (Pose2d/Pose3d entries).
- `start_time` (optional): Inclusive robot-clock start in seconds.
- `end_time` (optional): Inclusive robot-clock end in seconds.
- `last_seconds` (optional): Positive recent duration resolved by the shared time-scope base; cannot accompany start/end.
- `scope` (optional): Driver Station phase scope, as `get_statistics` accepts.
- `windows` (optional): List of `{start, end}` windows, intersected with the scope and bounds.
- `limit` (optional): Explicit drawing page size, default 1000 when paging, from 1 to 10000; with neither limit nor offset the drawing covers the whole window. Summaries and histograms always cover the whole window.
- `offset` (optional): Explicit drawing samples or buckets to skip, default 0; passing this requests paging.
- `max_points` (optional): Time-series buckets, from 1 to 10000 per scope window, retaining extremes and first/last; exact samples when they fit. Applies only to `time_series`.
- `width` (optional): PNG width, default 960, from 160 to 4096.
- `height` (optional): PNG height, default 540, from 120 to 4096. Width times height cannot exceed 4,000,000 pixels.

**Returns:** `chart_spec`, `summary`, `inputs`, `data_quality`, and `server_analysis_directives`,
plus PNG content. Each summary names its series and gives `count`, `min`, `max`, `mean`,
`window`, `non_finite_count` and `data_quality`. These are the finite, sample-weighted window
statistics used by `get_statistics`, regardless of the drawing page. Empty windows return
`no_match` with `looked_for` and `hint`. The quality of sparse measurements bounds statistics,
not the existence of the recorded events; a picture is not causal evidence and one log is one sample.

**Specification version 1:** `version`, `kind`, `width`, `height`, `window`, `series`, `phases`,
`phase_basis` and `open_url`. A series names its entry/field, name-stated `unit` (null when
none is stated), and `style` (`step_after` for change-only, otherwise `line`). Time-series
`points` are `[timestamp_sec, value]`; reduced series carry `buckets` (including start/end) and `bucket_rule`. With more than 1000 samples and no explicit page, a time series uses one equal-duration min/max bucket per plot pixel column, over the entire window. `drawn` gives each series' mode (`all samples`, `buckets`, or `page`) and count. `max_points` chooses the bucket count explicitly.
Nested `limits.points` or `limits.buckets` report the true total after offset when truncated.
Separate scope windows never join across an excluded interval. Phases come only from the
shared Driver Station resolver, with no guessed-name fallback.

Histograms carry `bins` (`low`, `high`, `count`) and `histogram_rule`: equal-width
`ceil(sqrt(n))` bins capped at 64; `[low, high)` except the last includes the maximum;
constant data gets one unit-wide bin. Scatter `pairs` contain x/y names, `[x, y, timestamp_sec]`
points, `matched`, `unpaired_x`, `unpaired_y` and page limits. `alignment_rule` states exact
timestamp, one-to-one record-order pairing, without interpolation or extrapolation.
Field plots use those pairs for each pose's x/y and include `field_geometry` and `field_source`
from bundled season geometry, as the explorer does; Pose3d projects onto the floor.

`open_url` opens the selected entries and pane through the extension's server-validated URI
handler. Its start/end enclose the chosen windows; the JSON retains the individual windows.
The editor uses uPlot for numerical panes and its existing field view, with no Vega runtime.
The own-specification-versus-Vega-Lite question remains open in the explorer plan. Its mapping is:

| Version 1 field | Vega-Lite equivalent |
| --- | --- |
| Time-series points and series name | Long-form data with quantitative x=time/y=value and color=series |
| `step_after` | Line mark with `interpolate: "step-after"` |
| Bins already counted by the server | Bar mark with x=low, x2=high, y=count; no client binning |
| Scatter pairs | Point mark with quantitative x/y; no client alignment |
| Driver Station phases | Background rect layer with x=start/x2=end and mode color |
| Pose pairs and field geometry | Layered x/y path and field outline with equal spatial scales |
| Width/height | View dimensions; inputs, summary and open_url remain evidence outside the visual encoding |

### `list_struct_types`
List struct types and how they decode. Struct values are decoded from each log's own schemas (`/.schema/struct:<Name>` entries, also `NT:/.schema/struct:<Name>`), so any struct a log records a schema for decodes: WPILib's, a vendor's, or a team's own, with nested structs, fixed-size arrays, enums, and bit-fields. With a path, returns `no_match` when the log declares no struct types.

**Parameters:**
- `path` (optional): Path to the log file. Omit it to list only the fallback schemas

**Returns:**
- With `path`: `log_path`, `struct_type_count`, and `struct_types`: every struct type the log records a schema for (in declaration order) and every one its entries use, each with `source` (`logged`, `wpilib`, `assumed`, or `missing`), `source_note`, `valid`, `error` (an invalid schema, or one that references a struct with no schema), `size_bytes`, `schema`, `schema_entry`, `fields`, `numeric_leaf_paths`, `entry_count`, and `entries` (up to 20; `limits.entries` gives the total). `warnings` name struct types whose entries cannot be decoded or rely on an assumed layout
- Without `path`: a `note` and the fallback schemas, used only for struct types a log records no schema for. They are WPILib's geometry and kinematics structs (`source: wpilib`) and three template layouts (`source: assumed`): AdvantageKit vision's `PoseObservation` and `TargetObservation`, and Choreo's `SwerveSample`

**Example Response (with path, abridged):**
```json
{
  "success": true,
  "status": "ok",
  "log_path": "/logs/2026-struct_custom.wpilog",
  "struct_type_count": 4,
  "struct_types": [
    {
      "name": "ArmState",
      "source": "logged",
      "valid": true,
      "size_bytes": 30,
      "schema": "Rotation2d angle;double currents[2];enum {STOWED=0, SCORING=1, INTAKE=2} int8 mode;uint8 flags:3;bool homed:1;float temperature",
      "schema_entry": "/.schema/struct:ArmState",
      "fields": [
        {"name": "angle", "type": "Rotation2d"},
        {"name": "currents", "type": "double", "array_size": 2},
        {"name": "mode", "type": "int8", "enum": {"STOWED": 0, "SCORING": 1, "INTAKE": 2}},
        {"name": "flags", "type": "uint8", "bit_width": 3},
        {"name": "homed", "type": "bool", "bit_width": 1},
        {"name": "temperature", "type": "float"}
      ],
      "numeric_leaf_paths": ["angle.value", "angle._derived.degrees", "currents[0]", "currents[1]", "mode", "flags", "homed", "temperature"],
      "entry_count": 3,
      "entries": ["/RealOutputs/Arm/State", "/RealOutputs/Arm/Partial", "/RealOutputs/Arm/States"]
    },
    {"name": "Mystery", "source": "missing", "valid": false, "error": "no schema for struct Mystery in this log (...)", "entry_count": 1}
  ],
  "warnings": ["1 entries of struct Mystery cannot be decoded: no schema for struct Mystery in this log (...)"]
}
```

Before analyzing a team's own structs (vision observations, mechanism states), use it to check that they decode by a logged schema and to find the numeric fields to address.

### `resolve_signals`
Show which entry plays each role in a log (the same choices the tools make), with the basis for each choice and the other candidates.

#### The server does not guess
A tool uses an entry for a role only when it was passed explicitly, follows a well-known logging convention, or is the only entry of the role's type or schema. Entries that match a role by name alone are listed as candidates and not used: the role reports `match: heuristic` and `needs_confirmation`, and a tool that needs it lists the candidates in its `skipped` reason (or its `no_match` hint) with the parameter to pass.

A word in a name is not evidence of what an entry holds, and neither is the shape of its data. A `currentHeight` is the present height, not an electrical current. PhotonVision's `targetYaw` is a camera reading, not a setpoint. Real logs hold two target module-state arrays beside the measured one, which no name tells apart; a planned trajectory that is a struct array of timestamps and poses, as a camera's observations are; and a gyro's struct with yaw and pitch fields, as a camera target has. What an entry holds, and in which units, is decided by the robot code that logs it, so that is where a candidate is confirmed: find where the entry is logged in the robot project's source. Without the source, the entry's type and values (`get_entry_info`, `read_entry`) or the team are the next best evidence.

The conventions:

| Role | Chosen by convention | Override |
|---|---|---|
| DriverStation state | AdvantageKit `/DriverStation/...`, WPILib `DS:...`, NetworkTables `FMSControlData` | none |
| `battery_voltage` | a leaf named `BatteryVoltage`; `Voltage` under `PowerDistribution`, `PowerDistribution[<id>]`, `PDH`, `PDP`, or `Battery` | `voltage_entry` |
| `total_current` | a leaf named `TotalCurrent` (not AdvantageKit's `SystemStats/BatteryCurrent`, the roboRIO's own input current) | `total_current_entry` |
| `brownout_flag` | a boolean leaf named `BrownedOut` or `IsBrownedOut` | none |
| `loop_time_full` / `_user` | AdvantageKit `LoggedRobot/FullCycleMS` / `UserCodeMS`; periods from AdvantageKit `/Timestamp` | `entry` |
| `robot_pose` | `DriveState/Pose` (CTRE), `Odometry/Robot` (AdvantageKit template), `Drive/Pose`, `EstimatedPose`, `RobotPose`, `PathPlanner/currentPose`; or the only `Pose2d` outside vision paths | `pose_entry` (`odometry_entry` in `analyze_swerve`) |
| `vision_pose` | the only scalar `Pose2d`/`Pose3d` with at least two samples under a vision, camera, PhotonVision, or Limelight path | `vision_entry` |
| `auto_chooser` | the one chooser whose key contains `auto`: a WPILib `SendableChooser`'s `active` entry, or AdvantageKit's `/NetworkInputs/SmartDashboard/<key>` | `chooser_entry` |
| `path_setpoint` / `path_actual` | `PathPlanner/targetPose`, AdvantageKit `Odometry/TrajectorySetpoint` / `PathPlanner/currentPose`, else the robot pose | `path_setpoint_entry` / `path_actual_entry` |
| `module_states_measured` / `_setpoint` | AdvantageKit `SwerveStates/Measured` with `SwerveStates/SetpointsOptimized` or `Setpoints`; CTRE `DriveState/ModuleStates` with `ModuleTargets`; YAGSL `swerve/advantagescope/currentStates` with `desiredStates` (the setpoint in the measured entry's own table); or, for measured, the only `SwerveModuleState` entry when it is not named like a setpoint | `measured_entry` / `setpoint_entry` |
| `vision_pose_observations` | `struct:PoseObservation[]` entries (the AdvantageKit vision template's record: a timestamp and a pose) | `vision_entries` |
| `vision_targets` | `struct:TargetObservation` entries with yaw and pitch fields; has-target flags as Limelight's `<table>/tv` and PhotonVision's `photonvision/<camera>/hasTarget` | `vision_entries` |
| `gyro_yaw` | a yaw entry under a gyro, Pigeon, NavX, Canandgyro, IMU, or AHRS path | none |

**Parameters:**
- `path` (required): Path to the log file
- `roles` (optional): Only these roles, as an array of role names (default: all). An unknown role is an error that lists them, and so is anything but an array of names, or an empty array

The capture's `/Daemon/` convention records context alongside NT4 entries without an `NT:`
prefix. `/Daemon/roboRIO/` holds sampled operating-system measurements with units in their
names; entry metadata gives the SSH host and period. `/Daemon/Tail/<host>/<role>` holds
received text. The resolver recognizes `program_console`, `kernel`, `syslog`, and `journal`
as followed-file roles, including `program_console` in the console-text role; unknown roles
remain ordinary string entries. Pulled companions use `search_system_logs`; the tail is the timely copy, the pulled file the exact record. `search_strings`, timeline error counts and CAN text analysis
read them through the same text path as `messages` and `console` in robot logs. Tail timestamps
are receipt times, not timestamps parsed from the line; pre-session buffering is stated in
metadata. A drop notice is recorder text, not a robot message.

**Roles:** `robot_enabled`, `autonomous`, `test_mode`, `fms_attached` (DriverStation state: AdvantageKit `/DriverStation/...`, WPILib `DS:...`, or the NetworkTables `FMSControlData` word), `battery_voltage`, `total_current`, `brownout_flag`, `brownout_threshold` (a value, from the log's `BrownoutVoltage` or a stated default), `loop_time_full`, `loop_time_user`, `robot_pose`, `vision_pose`, `auto_chooser`, `path_setpoint`, `path_actual`, `module_states_measured`, `module_states_setpoint`, `chassis_speeds_measured`, `chassis_speeds_setpoint`, `gyro_yaw`, `vision_pose_observations`, `vision_targets`, `can_bus`, `console_text`, `alerts`.

**Returns:** `log_path` and `roles.<role>`: `description`, `entry` (or `entries` for per-camera, per-bus, and text roles; `null` when unresolved), `value` (for `brownout_threshold`), `match` (`explicit`, `convention`, `type`, `heuristic`, or `none`), `basis` (why), `needs_confirmation` (heuristic: candidates only, not used), `candidates` (best first, up to 10, with `candidate_count` when there are more), `ambiguous` (another candidate ranked equally; the one declared first was chosen), and `used_by` (the tools that use the role and their override parameters). `unresolved` lists roles with no entry, `needs_confirmation` the ones with name-only candidates, and `warnings` name ambiguous choices.

Each tool's result records the entries it used under `inputs.entries`. Tools with an entry parameter (`pose_entry`, `measured_entry`, `entry`, ...) accept an override when a choice is wrong.

### `health_check`
Server status: version, loaded log count, TBA configuration, whether a revlog sync is running, JVM memory, and the disk caches.

**Parameters:** None

**Returns:** `server_version`, `loaded_logs`, `tba_available` (a key is registered by a live session or configured; `get_tba_status` checks that it works), `revlog_sync_in_progress`, `jvm_memory` (`used_mb`, `total_mb`, `max_mb`, `free_mb`), `jvm_heap_used_mb`, and the two disk caches, which share one directory. Each counts only its own files:
- `sync_disk_cache`: the revlog sync-result cache, which is in use (`enabled`, `directory`, `cached_files`, `total_size_mb`)
- `parsed_log_disk_cache`: the parsed-log cache of releases before 0.8.0 (`enabled`, `directory`, `cached_files`, `total_size_mb`, `format_version`). It reports `used_by_load_path: false` because logs are now parsed lazily from memory-mapped files, but it is still configured and its directory is cleaned at startup.

---

## Query Tools

Search and filter log data. Find specific entries, types, and events.

### `search_entries`
Search for entries by type, name, and sample count. Returns `no_match`, naming the criteria, when no entry matches.

**Parameters:**
- `path` (required): Path to the log file
- `type` (optional): Only entries whose type contains this (case-sensitive, e.g., `Pose3d`, `double`)
- `pattern` (optional): Only entries whose name contains this (case-insensitive)
- `min_samples` (optional): Minimum number of samples

**Returns:** `match_count` and `matches`, the matching entry names in name order.

### `get_types`
List the data types in the log and which entries use each.

**Parameters:**
- `path` (required): Path to the log file

**Returns:** `type_count` and `types` (by type name), each with `type`, `entry_count`, and `entries` (sorted). A log with no entries is `no_match`.

### `find_condition`
Find when a numeric or boolean entry satisfies a condition, or several entries at once, and for how long. It answers questions like "When did battery voltage drop below 11V, and for how long?" or "When was the robot disabled and stationary?" `samples_evaluated` counts the samples of the condition entries in scope, so zero transitions can be read against them.

**Parameters:**
- `path` (required): Path to the log file
- `name`: Entry name (e.g., `/Robot/BatteryVoltage`); double, float, int64, or boolean (read as 1/0), or a number inside a struct or array by [field path](#field-paths) (thresholds apply to angles as logged)
- `field` (optional): The field path, instead of appending it to `name`
- `angle` (optional): `radians` or `degrees`, declaring a plain-number signal an angle as in the statistics tools; thresholds still apply to the value as logged (not unwrapped)
- `operator`: `lt` (<), `lte` (<=), `gt` (>), `gte` (>=), `eq` (==), `ne` (!=), or `abs_lt`, `abs_lte`, `abs_gt`, `abs_gte` on the absolute value. The symbols themselves (`<`, `<=`, ...) are accepted too. `eq` and `ne` allow a tolerance of 1e-6 of the threshold's magnitude (at least 1e-9)
- `threshold`: Threshold value to compare against
- `conditions` (instead of `name`/`operator`/`threshold`, not with them): `{"all": [...]}` (every condition true) or `{"any": [...]}` (at least one), each item `{name, field?, angle?, operator, threshold}`. Each entry's value holds until its next sample, so entries logged only on change (DriverStation state, AdvantageKit outputs) combine correctly. The combined condition is evaluated at every sample of every entry, and time before all entries have a value is not searched. Example, disabled and stationary:
  ```json
  {"all": [
    {"name": "/DriverStation/Enabled", "operator": "eq", "threshold": 0},
    {"name": "/RealOutputs/SwerveChassisSpeeds/Measured.vx", "operator": "abs_lt", "threshold": 0.05},
    {"name": "/RealOutputs/SwerveChassisSpeeds/Measured.vy", "operator": "abs_lt", "threshold": 0.05}
  ]}
  ```
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time`, `scope`, `windows` (optional): Time ([Scopes and windows](#scopes-and-windows)); each window is searched on its own
- `limit` (optional): Maximum transitions and intervals to return (default 100)

**Returns:**
- `name` (single condition) and `condition` (readable form, e.g. `(/DriverStation/Enabled == 0.0) AND (|/RealOutputs/SwerveChassisSpeeds/Measured.vx| < 0.05)`); for compound conditions also `combine` and `conditions`
- `transitions[]`: each time the condition becomes true (`timestamp_sec`, and `value`, or `values` with one per condition for compound conditions; `at_window_start: true` when it was already true at the window's start). `transition_count` is the true total
- `intervals[]`: `start`, `end`, `duration`, and `end_reason` (`condition_false`, or `window_end` when still true at the end of the window). Each sample's value holds until the next sample
- `interval_count`, `total_true_sec`, `window_sec` (the time searched once every condition entry has a value), `fraction_of_window` (`total_true_sec / window_sec`), `samples_evaluated`, `inputs` (entries, fields, and scope or window), and `limits` for both lists. The `intervals` can be passed as `windows` to the statistics tools
- `data_quality` and `server_analysis_directives`: of the condition's entry over the scope (the worst entry for compound conditions), scored on every sample in scope. A gap in a periodic entry is time over which the condition was assumed unchanged

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "name": "/Robot/BatteryVoltage",
  "condition": "/Robot/BatteryVoltage < 11.0",
  "transition_count": 3,
  "transitions": [
    {"timestamp_sec": 45.23, "value": 10.89},
    {"timestamp_sec": 89.45, "value": 10.95},
    {"timestamp_sec": 134.12, "value": 10.78}
  ],
  "interval_count": 3,
  "intervals": [
    {"start": 45.23, "end": 45.61, "duration": 0.38, "end_reason": "condition_false"},
    {"start": 89.45, "end": 89.52, "duration": 0.07, "end_reason": "condition_false"},
    {"start": 134.12, "end": 135.0, "duration": 0.88, "end_reason": "condition_false"}
  ],
  "total_true_sec": 1.33,
  "fraction_of_window": 0.0089,
  "limits": {"transitions": {"total": 3, "returned": 3, "limit": 100}, "intervals": {"total": 3, "returned": 3, "limit": 100}}
}
```

### `search_strings`
List or search the text a log holds, completely and in time order across all entries. This is the tool for "show me every error": nothing is prioritized or silently dropped, and results are paged with explicit totals.

**Text sources:**
- `string` entries (console output, WPILib `messages`): one match per non-blank sample.
- `string[]` entries, such as WPILib `Alert`s (`/RealOutputs/Alerts/{errors,warnings,infos}`, `/RealOutputs/PhotonAlerts/*`, NetworkTables copies) and any other string array, are **state**: the robot program logs the whole array whenever any alert changes. So each message is one match from the record in which it appears (`timestamp_sec`) to the record in which it is gone (`end_sec`, `duration_sec`), or `active_at_log_end: true`. A message that clears and returns is a new match.
- `json` entries: the string values of each sample, one per line.

**Parameters:**
- `path` (required): Path to the log file
- `pattern` (optional): Case-insensitive substring, or a Java regular expression when `regex` is true. Omit to list every string sample (narrow with `level`, `entry_pattern`, or a time window)
- `regex` (optional): Treat `pattern` as a Java regex, case-insensitive (Unicode-aware) with `^`/`$` anchoring to lines of a multi-line sample; `.` does not cross a line break. Default `false`. An invalid regex is an error; so is a pattern that backtracks for more than a second on one value (nested quantifiers on long text), which would otherwise hang the server
- `level` (optional): `error`, `warning`, `info`, or `any` (default). An alert's level comes from its entry name (`errors`, `warnings`, `infos`); other text is classified line by line by the same rule `get_ds_timeline` uses for `text_event_counts`, so the numbers agree
- `entry_pattern` (optional): Only search entries whose name contains this (case-insensitive)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` / `end_time` (optional): Time window in seconds; an alert matches when it was present in the window, whenever it appeared
- `offset` and `limit` (optional; defaults 0 and 100, `limit` at most 1000): Paging over the time-ordered result. Values outside those ranges are clamped, and the clamped values are echoed
- `collapse_repeats` (optional, default `false`): Fold runs of identical samples that are **adjacent in the same entry's stream** into one match with `repeat_count` and `last_timestamp_sec`. Any other sample in between, even one the filters exclude, ends the run, so a `repeat_count` never spans a gap
- `max_value_chars` (optional, default 500, minimum 1): Truncate each returned `value`

**Returns:**
- `total_matches`: the full number of matching samples (before paging); with `collapse_repeats`, also `total_after_collapse`
- `offset`, `limit`, `returned` (the same as `match_count`, kept for compatibility), and `has_more` (whether another page exists)
- `matches[]` sorted by time across entries (ties by entry declaration order): `{timestamp_sec, entry, source ("string", "alert", or "json"), end_sec?, duration_sec?, active_at_log_end?, level? ("error"/"warning"/"info" when known), line, value, repeat_count?, last_timestamp_sec?}`. `collapse_repeats` never folds alerts, which are already one match per appearance. `line` is the line containing the pattern match; without a pattern it is the classified line, or the first line for unclassified samples. It is cut at 200 characters (`line_truncated: true`). `value` is the whole sample cut at `max_value_chars` (`value_truncated: true`)
- `pattern` (echoed when given), `regex`, `level`, and `limits.matches`

**Example Response** (`level: "error"`, `limit: 2`):
```json
{
  "success": true,
  "status": "ok",
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
      "source": "string",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:88): ...",
      "value": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:88): ..."
    },
    {
      "timestamp_sec": 124.74,
      "entry": "/RealOutputs/Console",
      "source": "string",
      "level": "error",
      "line": "Error at frc.robot.subsystems.intake.IntakeArmIOReal.updateInputs(IntakeArmIOReal.java:88): ...",
      "value": "..."
    }
  ]
}
```

---

### `search_system_logs`

Search the session's pulled local text files listed in `session.json` and the robot's
`system/index.json`; shared syslog spans must overlap the session's calendar span or be
unknown, and an unknown session span cannot exclude a file. Legacy session receipts remain
readable. This tool never
contacts the robot. The pulled file is the exact record; `/Daemon/Tail` entries searched by
`search_strings` are the timely copy, stamped at receipt. They can contain the same text.

**Parameters:**
- `path` (required): The session's capture file
- `source` (optional): `kernel`, `syslog`, `program`, `jvm_crash`, or `all` (default)
- `pattern` (optional): Case-insensitive substring; omit to list every line
- `regex` (optional): Interpret pattern as a Java regex, default `false`; invalid patterns
  and expressions exceeding one second per line are explained errors
- `level` (optional): `error`, `warning`, `info`, or `any` (default), using the same line
  classifier as `search_strings`; ordinary file text does not carry an alert's `info` level
- `start_time` / `end_time` (optional): Inclusive robot-clock bounds in seconds
- `scope` (optional): A shared named time scope such as `all`, `enabled` or `teleop`
- `windows` (optional): Explicit `{start, end}` windows in seconds, intersected with the scope
- `last_seconds` (optional): Positive seconds before the open capture's current robot time,
  or the closed file's end; cannot combine with start/end; `inputs.window` gives the bounds
- `offset` (optional): Matching lines to skip, default 0, nonnegative
- `limit` (optional): Lines returned, default 100, range 1–1000

**Returns:** `matches[]` in file-path then line-number order, each with `file`, `source`,
`line_number`, `text`, `level`, `original_timestamp`, `timestamp_sec`, `timestamp_basis`
and `timestamp_reason`. `total_matches` and `limits.matches.total` count all matches before
paging; `offset`, `limit`, `returned` and `has_more` describe the page. `inputs.files` names
paths, committed bytes and hashes; `inputs.clock_files` and `inputs.manifest` name the clock
and membership evidence read. No companions gives `not_applicable`; no matching lines gives
`no_match`. No `data_quality`: logged lines are facts.

`kernel` means dmesg only. Kernel seconds interpolate the two nearest recorded
`/Daemon/roboRIO/uptime_sec` / FPGA pairs, with basis `uptime_pairing`; nothing is
extrapolated beyond the pairing or session. `syslog` includes either files or the whole
journal. Journal `short-unix` epochs and ISO timestamps with a year and zone map through
recorded `systemTime` (prefer the pulled log and its recorded alignment), with basis
`system_time`. A wall-clock pair supplies the measured offset within the session. A date
without a year or zone is not guessed. Unmapped lines have null `timestamp_sec` and a reason;
they remain visible even in a requested window because their membership in it is unknown.
On a journald image a kernel message can occur as both kernel/uptime_pairing and
syslog/system_time. Text files are read only to the manifest's committed length, including
gzip syslog rotations. Text travels through peer sync and mirrors with hash verification.
A receipt whose local copy is missing gives `not_applicable`, naming the collecting server
and the files to synchronize. A shortened or unsafe receipt gives an explained error.

## Statistics Tools

Statistics on numeric signals. The first two subsections describe the field paths and time scopes these tools share.

### Field paths

`get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `find_condition`, `align_entries` (each of its `names`), and `compare_matches` measure a **numeric signal**: a scalar entry (double, float, int64, or boolean read as 1/0), or a number inside a struct or array entry, addressed by a field path.

| Signal | Name |
|--------|------|
| A struct field | `/RealOutputs/Drive/Pose.translation.x` |
| A derived angle | `/RealOutputs/Drive/Pose.rotation._derived.degrees` |
| An array element | `/PowerDistribution/ChannelCurrent[3]` |
| A field of one element of a struct array | `/Vision/Camera0/PoseObservations[0].tagCount` |
| Every element (pooled; `get_statistics` and `compare_matches` only) | `/Vision/Camera0/PoseObservations[*].averageTagDistance` |

- The path can be appended to the entry name, or passed separately as `field` (`field1`/`field2` for the two-signal tools): `name: "/RealOutputs/Drive/Pose", field: "translation.x"`. `align_entries` takes paths only appended to its `names`.
- An exact entry name always wins (names can contain dots); otherwise the longest entry name followed by `.` or `[` is the entry.
- Enum fields read as their number, booleans as 1/0. Records in which the path holds no number (an empty array for `[0]`) are skipped and counted (`records_without_value` in `get_statistics`).
- A struct or array entry named without a path is an error that lists its numeric fields; so is a path that does not lead to a number. A path on a scalar entry is an error too. `get_entry_info` lists every entry's `numeric_leaf_paths`.
- Angles: a `Rotation2d`'s `value` (radians) and `_derived.degrees`, a `Rotation3d`'s `_derived` roll/pitch/yaw (radians, or degrees for the `_deg` fields), and a `SwerveSample`'s `heading` are known angles. `get_statistics`, `rate_of_change`, `find_peaks`, `detect_anomalies`, and `time_correlate` unwrap them, so crossing ±180° is not a jump. `compare_entries` and `align_entries` compare two angles by their shortest difference. `find_condition` compares thresholds with the value as logged. A heading logged as a plain double is not known to be an angle unless you pass `angle`.
- Results name the signal (`name` is the entry and path) and record it under `inputs.entries` and `inputs.fields`.

### Scopes and windows

`get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `find_condition`, `align_entries`, `compare_poses`, `pose_corrections`, and `compare_matches` take the time they measure from three optional parameters, which intersect. `analyze_swerve`, `analyze_loop_timing`, `power_analysis`, and `predict_battery_health` take `scope` and `start_time`/`end_time`, but not `windows`.

- `start_time` / `end_time`: one inclusive range.
- `scope`: `all` (default), `enabled`, `disabled`, `auto`, `teleop`, `test`, or `segment:<i>` (the i-th enabled segment of `get_match_phases`, from 0). Segments are half-open: the sample logged at a transition belongs to the new state. Any scope other than `all` on a log with no DriverStation state is an error.
- `windows`: a list of `{start, end}` (or `[start, end]`), half-open `[start, end)`; overlapping windows merge. The `intervals` returned by `find_condition` can be passed as-is ("statistics while the battery was below 11 V").

Differences, spikes, peaks, and, in the tools that measure one signal, angle unwrapping are computed within each window, never across the time between two. `time_correlate` pairs two signals, so it unwraps each angle over the whole log, which keeps both on one continuous branch; its entry says what it still measures within the windows. `find_condition` searches each window on its own (an interval still true at a window's end closes there with `end_reason: window_end`). `data_quality` counts gaps only within windows, and its time span is the sum of the windows'. Results record the scope under `inputs.scope` (`scope`, up to 50 `windows`, `window_count`, `total_sec`); plain `start_time`/`end_time` still appear as `inputs.window`.

### `get_statistics`
Statistics of a numeric entry or field over the finite samples in scope, with data quality and analysis directives. A scope with no finite sample is an error that says how many values the log and the scope hold.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): The entry name, optionally with a [field path](#field-paths)
- `field` (optional): The field path, instead of appending it to `name`
- `angle` (optional): `radians` or `degrees`: treat the values as an angle (unwrapped across ±180°, circular statistics) when it is logged as a plain number, such as a gyro yaw double. Struct angle fields are recognized without it
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` (number, optional): Start timestamp in seconds
- `end_time` (number, optional): End timestamp in seconds
- `scope`, `windows` (optional): [Scopes and windows](#scopes-and-windows)

**Returns:** `name`, `field` (when one was given), `count`, `min`, `max`, `mean`, `median`, `std_dev` (sample, n − 1), `q1`, `q3`, `iqr`, `p5`, `p95`, `inputs`, `data_quality`, and `server_analysis_directives`. With a `[*]` path, `count` is values and `records_in_window` is records; `records_without_value` counts records where the path held no number. For an angle, the linear statistics are of the angle unwrapped within each window (so `max - min` is how far it turned), and `angle` gives `unit`, `unwrapped`, `wraps` (steps of more than half a turn), `circular_mean`, `circular_std` (√(−2 ln R), same unit), and `resultant_length` R. A `[*]` pool has no order to unwrap, so it reports the circular statistics without `wraps`.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
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
  "server_analysis_directives": { "confidence_level": "high" }
}
```

### `compare_entries`
Compare two numeric entries or fields, such as a setpoint and a measurement, or `/RealOutputs` and `/ReplayOutputs` copies of one value.

**Parameters:**
- `path` (required): Path to the log file
- `name1` (required): First entry name, optionally with a [field path](#field-paths)
- `name2` (required): Second entry name, optionally with a field path
- `field1`, `field2` (optional): Field paths, instead of appending them to the names
- `angle` (optional): `radians` or `degrees`: treat both signals as angles when they are logged as plain numbers ([Field paths](#field-paths))
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time`, `scope`, `windows` (optional): Only the reference signal's samples in this time are compared ([Scopes and windows](#scopes-and-windows))
- `max_lag_sec`, `lag_step_sec` (optional): Also search for the time shift that minimizes RMSE, as in `time_correlate` (here the first signal's samples are the reference). Returns `lag_search` with `lags_evaluated`, `lag_step_sec`, `best_lag_sec`, `rmse_at_best_lag`, `samples_at_best_lag`, `rmse_at_zero_lag`, and a `note`

**Returns:** `rmse`, `max_difference` (absolute), `samples_compared`, and `reference_entry` (the signal with more samples, whose timestamps are used; the other is linearly interpolated, never extrapolated), with `inputs`, `data_quality` (of the lower-quality signal), and `server_analysis_directives`. Two angles are compared by their shortest angular difference in the first one's unit (`angle_unit`, and `difference: "shortest angular difference"`). An angle against a non-angle is compared as plain numbers, with a warning. A struct entry without a field (e.g. `struct:ChassisSpeeds`) is an error listing its numeric fields, and signals with no overlapping samples are an error naming both spans, never a success with `rmse: NaN`.

### `detect_anomalies`
Detect anomalies in a numeric entry within an optional time window: outliers outside Tukey fences (Q1 − k·IQR, Q3 + k·IQR, with linearly interpolated percentiles), and, when `spike_threshold` is given, spikes (sample-to-sample jumps larger than the threshold). Fewer than 4 finite samples in scope is an error.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name to analyze, optionally with a [field path](#field-paths) (a struct entry without one is an error listing its numeric fields)
- `field` (optional): The field path, instead of appending it to `name`
- `angle` (optional): `radians` or `degrees`: treat the values as an angle when it is logged as a plain number ([Field paths](#field-paths))
- `iqr_multiplier` (optional): Multiplier k for the IQR fences (default 1.5). Use 3.0 for extreme outliers only
- `spike_threshold` (optional): Flag consecutive samples that differ by more than this, in the entry's units (off by default; must be positive)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time`, `scope`, `windows` (optional): Time ([Scopes and windows](#scopes-and-windows)). Boot transients and disabled time count unless the scope excludes them, for example with `scope: "enabled"`. Spikes are jumps within one window
- `sort` (optional): `time` (default) or `severity` (distance beyond the fence, or jump size)
- `limit` (optional): Maximum anomalies to return (default 50)

**Returns:** `name`, `anomaly_count` (the true total), `outlier_count`, `non_finite_count`, `samples_analyzed`, `sort`, `bounds` (`q1`, `q3`, `iqr`, `lower`, `upper`), `anomalies[]`, `limits.anomalies` (total vs returned), `angle_unit` for an angle, `inputs`, `data_quality`, and `server_analysis_directives`. Each anomaly has `timestamp_sec`, `value`, `type` (`below_lower_bound`, `above_upper_bound`, `spike_up`, or `spike_down`), `severity`, and, for spikes, `jump`. With `spike_threshold`, also `spike_count` and `spike_interval_sec`: the time between consecutive spikes in one window (`n`, `min`, `median`, `p95`, `max`), the cadence of steps such as vision corrections.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "anomaly_count": 38,
  "outlier_count": 38,
  "non_finite_count": 0,
  "bounds": {"q1": 16.4, "q3": 19.1, "iqr": 2.7, "lower": 12.35, "upper": 23.15},
  "samples_analyzed": 11952,
  "sort": "severity",
  "anomalies": [
    {"timestamp_sec": 245.1, "value": 91.9, "type": "above_upper_bound", "severity": 68.75},
    {"timestamp_sec": 301.7, "value": 60.2, "type": "above_upper_bound", "severity": 37.05}
  ],
  "limits": {"anomalies": {"total": 38, "returned": 2, "limit": 2}},
  "data_quality": { "sample_count": 11952, "quality_score": 0.5 },
  "server_analysis_directives": { "confidence_level": "low", "...": "..." }
}
```

### `find_peaks`
Find local maxima and minima (peaks and valleys) in numeric data. A sample is a peak when it is strictly above (or below) both neighbors; its `height_diff` is the larger of its differences from them. Peaks are listed in time order. `samples_analyzed` counts the samples in scope the search ran over, so zero peaks can be read against them. A window needs at least 3 finite samples.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name to analyze, optionally with a [field path](#field-paths)
- `field` (optional): The field path, instead of appending it to `name`
- `angle` (optional): `radians` or `degrees`: treat the values as an angle when it is logged as a plain number ([Field paths](#field-paths))
- `type` (optional): `max` (maxima only), `min` (minima only), or `both` (default); any other value is an error
- `min_height_diff` (optional): Minimum `height_diff` to count as a peak, to filter out noise
- `limit` (optional): Maximum peaks to return per type (default 20)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time`, `scope`, `windows` (optional): Time ([Scopes and windows](#scopes-and-windows)); a peak's neighbors are in its own window

**Returns:** `name`, `samples_analyzed`, `maxima` and `maxima_count`, `minima` and `minima_count` (the counts are true totals; `limits` gives total vs returned for each list), `inputs`, `data_quality`, and `server_analysis_directives`. Angles are unwrapped first (`angle_unit`), so a wrap is not a peak. A struct or array entry without a field path is an error listing its numeric fields.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
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
  "server_analysis_directives": { "confidence_level": "high" }
}
```

### `rate_of_change`
The derivative of numeric data, dv/dt, in the signal's units per second: velocity from position, acceleration from velocity, or how fast any value changes. Each difference is divided by the actual time between its samples; a difference over zero time is skipped.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry name to analyze, optionally with a [field path](#field-paths)
- `field` (optional): The field path, instead of appending it to `name`
- `angle` (optional): `radians` or `degrees`: treat the values as an angle when it is logged as a plain number ([Field paths](#field-paths))
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `scope`, `windows` (optional): [Scopes and windows](#scopes-and-windows); derivatives never span the gap between two windows
- `window_size` (optional): Samples spanned by each difference (default 1). With 1, each sample's rate is a central difference (forward at a window's first sample, backward at its last). With n > 1, it is the difference between a sample and the one n samples earlier, which smooths noise but may hide short events
- `limit` (optional): Maximum samples to return (default 100)

**Returns:** `name`, `samples` (each `timestamp_sec` and `rate`, cut at `limit`, with `limits.samples` giving the true count), `statistics` (`avg_rate`, `rate_count`), `inputs`, `data_quality`, and `server_analysis_directives`. The status is `no_match`, with `avg_rate: null`, when no pair of consecutive finite samples has distinct timestamps. Angles are unwrapped first (`angle_unit`), so a wrap is not a spike. A window needs at least 2 finite samples, and a struct or array entry without a field path is an error listing its numeric fields.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
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
  "server_analysis_directives": { "confidence_level": "high" }
}
```

### `time_correlate`
Pearson correlation between two numeric entries, with a p-value. Each of the first signal's samples in scope is paired with the second signal linearly interpolated at its time. r ranges from −1 to +1; correlation does not establish cause, and two signals that both follow the match phase correlate for that reason alone.

A common rule of thumb for |r|: 0.9 and up is very strong, 0.7 strong, 0.5 moderate, 0.3 weak, and below 0.3 little linear relationship. Whether r differs from zero beyond chance is what `p_value` answers.

**Parameters:**
- `path` (required): Path to the log file
- `name1` (required): First entry name, optionally with a [field path](#field-paths)
- `name2` (required): Second entry name, optionally with a field path
- `field1`, `field2` (optional): Field paths, instead of appending them to the names
- `angle` (optional): `radians` or `degrees`: treat both signals as angles when they are logged as plain numbers ([Field paths](#field-paths))
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `scope`, `windows` (optional): The first signal's samples in this time are paired with the second ([Scopes and windows](#scopes-and-windows))
- `max_lag_sec` (optional): Also search for the time shift with the strongest correlation, positive or negative, from −max to +max (a positive lag means the second signal follows the first). At most 401 lags are evaluated; the step widens if needed
- `lag_step_sec` (optional): Lag search step in seconds (default: the first signal's median sample interval)

**Returns:** `correlation`, `sample_count`, `lag1_autocorrelation` (`entry1`, `entry2`), `effective_sample_size`, `p_value`, `p_value_basis`, `inputs`, `data_quality` (of the lower-quality signal), and `server_analysis_directives`. Consecutive samples of a signal are not independent, so the p-value is a two-sided t test on the correlation with the effective sample size n(1 − r1ₓr1ᵧ)/(1 + r1ₓr1ᵧ) (Bretherton et al. 1999), computed exactly (regularized incomplete beta). The lag-1 autocorrelations pair consecutive samples within a window, never the two on either side of the time between windows, and the sample rates are measured within the windows too. Warnings say when fewer than 30 samples overlap, when fewer than 30 effective samples remain, and when the two sample rates differ more than tenfold. When either entry is constant over the window (near-zero variance), correlation is undefined: `correlation` and `p_value` are `null`, and a warning names the constant entry.

With `max_lag_sec`, `lag_search` gives `lags_evaluated`, `lag_step_sec`, `max_lag_sec`, `best_lag_sec`, `correlation_at_best_lag`, `samples_at_best_lag`, `correlation_at_zero_lag`, and a `note`. The best lag is the one where the correlation is strongest in either direction, and `correlation_at_best_lag` keeps its sign: two signals that move oppositely, such as battery voltage and a motor's current, have a negative correlation at every lag, and the strongest is the most negative. Among equally strong lags, the one nearest zero is reported. If the sign at the best lag differs from the sign at zero lag, the relationship reverses with the shift, as oscillating signals do half a period apart; check that before reading the lag as a delay. A best lag at the edge of the range may lie beyond it.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "sample_count": 5432,
  "correlation": -0.81,
  "lag1_autocorrelation": {"entry1": 0.97, "entry2": 0.95},
  "effective_sample_size": 104.6,
  "p_value": 1.2e-25,
  "p_value_basis": "two-sided t test on the correlation with the effective sample size ...",
  "data_quality": { "sample_count": 7716, "quality_score": 0.95 },
  "server_analysis_directives": { "confidence_level": "high" }
}
```

**Common correlations in FRC:**
- Battery voltage vs motor current: strong negative (voltage drops as current rises)
- Drive velocity vs motor power: strong positive
- Arm position vs arm motor current: depends on the mechanism

### `align_entries`
Sample several numeric signals at common times, to read them side by side or to measure one against another. Returns `no_match` when no sample time falls in scope.

**Parameters:**
- `path` (required): Path to the log file
- `names` (required): 1 to 8 signals, each an entry name optionally with a [field path](#field-paths) appended (no `[*]`)
- `at` (optional): The entry whose record times are the sample times (default: the first signal's own samples)
- `time_field` (optional): A path inside `at` (or the first signal's entry) whose values are timestamps in seconds. For example, `[*].timestamp` of a `PoseObservation[]` entry samples the robot pose when the camera saw the target rather than when its result arrived
- `interpolation` (optional): `previous` (default: the value in force, right for values logged when they change), `linear` (between the samples around the time; no extrapolation), or `nearest`. Angles interpolate along the shortest arc
- `difference` (optional): With exactly two signals, `difference_statistics` of signal 1 minus signal 2 (two angles by their shortest difference, in the first one's unit)
- `angle` (optional): `radians` or `degrees`: treat every signal as an angle when logged as a plain number ([Field paths](#field-paths))
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time`, `scope`, `windows` (optional): Which sample times to use ([Scopes and windows](#scopes-and-windows))
- `offset`, `limit` (optional): Row paging (default limit 100, max 2000)

**Returns:** `interpolation`, `time_source`, `columns` (`timestamp_sec` and each signal's name), `total_rows`, `rows` (`[t, v1, v2, ...]`, with `null` where a signal had no value), `limits.rows`, `unaligned` (per signal, how many sample times had no value), and `inputs` (signals and scope). With `difference`: `difference_statistics` (`count`, `mean`, `std_dev`, `min`, `max`, `median`, `p5`, `p95`, `mean_abs`, `rmse`, and `angle_unit` for angles), with `data_quality` and `server_analysis_directives` of the difference series.

**Example request**, how far the pose heading and the gyro differ while enabled:
```json
{"names": ["/RealOutputs/Drive/Pose.rotation.value", "/Drive/Gyro/YawPosition.value"], "difference": true, "scope": "enabled"}
```

---

## Robot Analysis Tools

Robot-specific analysis: match phases, swerve, power, CAN, cross-match comparison, code metadata, and mechanism inertia.

### `get_match_phases`
Find when the robot was enabled, in which mode, and, when the log holds a match, its autonomous, teleop, and endgame phases. Everything is derived from the log's DriverStation state entries; nothing is assumed about the log being a match.

**How it works:**
- Reads one DriverStation entry per role, by leaf name: `Enabled`, `Autonomous`, `Test`, `FMSAttached` under AdvantageKit `/DriverStation/` or WPILib DataLogManager `DS:` (AdvantageKit wins when both exist, then the lowest entry id; the others are named in `notes`). Logs with only NetworkTables data use the `FMSInfo/FMSControlData` control word.
- Values are logged only on change, so each holds until the next sample (sample-and-hold). A single `Autonomous=false` sample means the robot was never in autonomous.
- `segments` tiles the whole log: every interval of constant state (`enabled`, `disabled`, or `unknown` before the first DriverStation sample), with `mode` (`auto`/`teleop`/`test`/`unknown`) while enabled and `end_reason` (`disabled`, `enabled`, `mode_change`, `ds_data`, `log_end`). A disable logged at the log's last timestamp ends the final segment with `disabled`; `log_end` means the robot was still in that state when the log stopped.
- `matches` lists each FMS match found: an enabled autonomous segment followed, within the season's auto-to-teleop delay plus 5 s, by an enabled teleop segment. Its `basis` is `fms_attached` (FMS attached at the start) or `mode_sequence` (no FMS, but the autonomous segment lasted 50–150% of the season's autonomous time). A teleop segment within 5 s of the season's teleop length with FMS attached is a match even if the robot was disabled through autonomous. `complete` says whether teleop ended in a disable within 5 s of the season's teleop length; `endgame` is derived from the season's timing (`basis: game_timing`) only for complete matches. `expected_timing` gives the season values used.
- `phases` repeats the first match (compatibility). With no match and exactly one enabled segment, it holds that segment as `enabled`; with several enabled segments it is empty, so use `segments`.
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
Analyze swerve modules from `SwerveModuleState` entries: speed magnitudes per module and, when setpoints are logged, how well each module tracks them.

**How module states are found:**
- A `struct:SwerveModuleState[]` array is one module per index, labeled `module[0]` to `module[N-1]`. For four modules, `assumed_position` gives the AdvantageKit template's order (front-left, front-right, back-left, back-right). The log does not record that order, and `module_order_note` says so.
- The measured and setpoint entries are taken from a published naming, with the setpoint in the same table as the measured entry and paired with it by index:

  | Source | Measured | Setpoint |
  |---|---|---|
  | AdvantageKit swerve template | `SwerveStates/Measured` | `SwerveStates/SetpointsOptimized`, else `SwerveStates/Setpoints` |
  | CTRE swerve telemetry | `DriveState/ModuleStates` | `DriveState/ModuleTargets` |
  | YAGSL telemetry | `swerve/advantagescope/currentStates` | `swerve/advantagescope/desiredStates` |

  Any prefix may come before these names (`/RealOutputs/`, `NT:/`). When two tables follow a naming, as a replay log's `/RealOutputs/` and `/ReplayOutputs/` do, the one declared first is used and a warning names both; `module_prefix` or `measured_entry` chooses the other.
- With no published naming, the only `SwerveModuleState` entry in the log is used, unless its leaf name contains `setpoint`, `desired`, `target`, `commanded`, `goal`, or `reference`. The log does not say whether that entry holds measured or commanded states, and a warning says so.
- Entries under a team's own names are not interpreted. Words such as `Actual` and `Target` are not taken as evidence, and two target arrays beside the measured states cannot be told apart by name at all. The result is then `no_match` with `needs_confirmation` and `candidates`; when only the setpoint is unresolved, the tracking sections are skipped and the reason lists the candidates. Pass `measured_entry` and `setpoint_entry`. The robot's source code, where each entry is logged, says which is which.
- Per-module `struct:SwerveModuleState` entries are analyzed one module per call, by passing that module's `measured_entry` and `setpoint_entry`.

**Parameters:**
- `path` (required): Path to the log file
- `module_prefix` (optional): Only consider module state entries under this prefix
- `measured_entry`, `setpoint_entry` (optional): The module state entries (an array, or one module's entry; both of the same shape). An entry named here that is missing or not module states is an error
- `slip_threshold` (optional): Speed tracking error, in m/s, counted as an event (default: 0.5)
- `sync_threshold_rad` (optional): Steer error, in radians, counted as an event (default: 0.1)
- `odometry_entry`, `vision_entry` (optional): Scalar pose entries for the drift comparison. An entry named here that is missing or not a `Pose2d`/`Pose3d` is an error, as with `measured_entry`. By default these are the `robot_pose` and `vision_pose` roles (a conventional name, or the only candidate); several name-only candidates are listed in `skipped` to confirm, never guessed
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `scope` (optional): `all` (default), `enabled`, `disabled`, `auto`, `teleop`, `test`, or `segment:<i>`; combined with `start_time`/`end_time`
- `start_time`, `end_time` (optional): Clip the scope to a time range (seconds)

**Returns (per module in `modules[]`):**
- `module`, `index` and `assumed_position` (array layout), `measured_entry`, `setpoint_entry`
- `mean_abs_speed_mps`, `max_abs_speed_mps`, `samples`: magnitudes, because measured speeds are signed (negative about half the time as modules flip direction) and a signed average cancels toward zero
- `speed_tracking_error` (`samples`, `mean_mps`, `p95_mps`, `max_mps`, `events_over_threshold`): `| |measured| − |setpoint| |` at each measured sample, against the setpoint logged at most 0.1 s earlier
- `steer_error` (`samples`, `mean_rad`, `p95_rad`, `max_rad`, `max_deg`, `events_over_threshold`): angle difference modulo 180° (an optimized setpoint may flip the wheel), only while the setpoint speed exceeds 0.05 m/s

**Also returns:** `measured_basis` and `setpoint_basis` (how each entry was chosen), `layout` (`array` or `per_module`), `module_count`, `module_sync` (`basis`, `samples_analyzed`, `desync_events`, `max_deviation_rad`, `max_deviation_deg`, and `worst_module`: the largest steer error across modules), `odometry_drift` (`avg_error_m`, `max_error_m`, `max_error_per_total_time`, `comparisons` between the robot pose and a vision pose at the vision timestamps, with `odometry_entry` and `vision_entry`, and with `odometry_basis` and `vision_basis` saying how each was chosen), `scope`, `inputs.entries`, `data_quality` of the measured entry, and `server_analysis_directives`. Sections that cannot be produced (no setpoints, no scalar vision pose) are listed in `skipped` with the reason (status `partial`).

**Status:** `no_match` when the log has no `SwerveModuleState` entries, and `no_match` with `needs_confirmation` and `candidates` when it has some under names the server does not interpret.

**Example Response (abridged):**
```json
{
  "success": true,
  "status": "partial",
  "layout": "array",
  "module_count": 4,
  "inputs": {"entries": {"measured": "/RealOutputs/SwerveStates/Measured", "setpoint": "/RealOutputs/SwerveStates/SetpointsOptimized"}},
  "measured_basis": "SwerveStates/Measured (AdvantageKit swerve template)",
  "setpoint_basis": "SwerveStates/SetpointsOptimized beside the measured entry (AdvantageKit swerve template)",
  "scope": {"scope": "enabled", "windows": [[40.207, 359.162], "..."], "total_sec": 1311.36},
  "modules": [
    {"module": "module[0]", "index": 0, "assumed_position": "front_left",
     "measured_entry": "/RealOutputs/SwerveStates/Measured", "samples": 40010,
     "mean_abs_speed_mps": 0.971, "max_abs_speed_mps": 4.75,
     "speed_tracking_error": {"samples": 24180, "mean_mps": 0.08, "p95_mps": 0.31, "max_mps": 2.9, "events_over_threshold": 412},
     "steer_error": {"samples": 20114, "mean_rad": 0.03, "p95_rad": 0.09, "max_rad": 1.2, "max_deg": 68.8, "events_over_threshold": 900}}
  ],
  "module_sync": {"basis": "steer angle vs setpoint, modulo 180 deg, ...", "max_deviation_rad": 1.2, "worst_module": "module[2]"},
  "skipped": [{"section": "odometry_drift", "reason": "Needs a scalar odometry pose and a scalar vision pose ..."}]
}
```

### `power_analysis`
Battery voltage statistics and brownout risk, plus the peak current for every amperage entry in the log, sorted by peak magnitude. Per-channel arrays such as AdvantageKit's `/PowerDistribution/ChannelCurrent` are expanded per channel index, so every PDH/PDP channel's peak is reported in one call (the statistics tools read one channel by [field path](#field-paths), e.g. `/PowerDistribution/ChannelCurrent[3]`).

**Voltage entry selection** (the `battery_voltage` role of [The server does not guess](#the-server-does-not-guess), shared with `predict_battery_health`, `get_ds_timeline`, and `generate_report`): `voltage_entry` when given. Otherwise the entry the convention names, with at least one finite sample (ties go to the entry declared first): a numeric leaf named `BatteryVoltage` (AdvantageKit `/SystemStats/BatteryVoltage`), else `Voltage` whose parent is `PowerDistribution`, `PowerDistribution[<id>]`, `PDH`, `PDP`, or `Battery` (AdvantageKit `/PowerDistribution/Voltage`, WPILib `NT:/SmartDashboard/PowerDistribution[1]/Voltage`). Any other entry named `voltage` (an input or bus voltage, say) is never used. When the log has only those, `voltage_analysis` is skipped and the reason lists them as candidates to confirm and pass as `voltage_entry`; rail, regulator, and motor-output voltages are not even candidates. `inputs.entries.voltage` records the entry used. `power_prefix` restricts the search to one subtree.

**Current entry selection:** an entry counts as amperage when:
- its name ends in `Amps`/`Amperes` at a token boundary (`CurrentAmps`, `StatorAmps`, `stator_amps`, but not `OdometryTimestamps` or `SlewRamps`);
- the text after the last `Current` is empty or a unit, plural, or draw suffix (`OutputCurrent`, `Current_A`, `CurrentDraw`, `Currents`, `Current(A)`);
- it is `Current/<sub-path>` and the sub-path is not a non-amperage quantity (`Current/Stator` yes, `Current/Setpoint` no); or
- it is a WPILib PowerDistribution sendable channel (`PowerDistribution[<id>]/Chan<N>`).

Names such as `Current Angle Degrees`, `CurrentLimit`, or `CurrentState` are excluded, and so is anything containing "voltage". `power_prefix` narrows the candidates but does not bypass the rule.

**Brownout threshold** (shared with `predict_battery_health`, `get_ds_timeline`, and `generate_report`): the `brownout_threshold` argument when given; otherwise the roboRIO's own setting when the log records it (a numeric entry named `BrownoutVoltage`, e.g. AdvantageKit `/SystemStats/BrownoutVoltage`); otherwise 6.8 V, the roboRIO 1 default, with `brownout_threshold_basis` saying that a roboRIO 2 (6.3 V) cannot be ruled out.

**roboRIO brownouts:** when the log has the roboRIO's brownout flag (a boolean named `BrownedOut` or `IsBrownedOut`, e.g. `/SystemStats/BrownedOut`), `rio_brownouts` lists each interval it was true, with start, end, and duration. Those are the times the roboRIO actually disabled outputs. Voltage statistics against the threshold are a separate, weaker signal.

**Brownout risk** (`brownout_risk`, with its evidence in `brownout_risk_basis`; one rule shared with `generate_report`):
- **HIGH**: the roboRIO's logged brownout flag was true in scope (outputs were disabled); or, when the log has no such flag, the voltage crossed below the threshold (unconfirmed, because whether outputs were disabled is then unknown)
- **MODERATE**: the voltage crossed below the threshold but the logged flag stayed false (the roboRIO did not disable outputs); or it never crossed, but the minimum came within 1 V of the threshold
- **LOW**: the minimum stayed more than 1 V above the threshold

**Parameters:**
- `path` (required): Path to the log file
- `power_prefix` (optional): Entry path prefix for power data (e.g., `/PDP`, `/PDH`, `/PowerDistribution`)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `scope` (optional): `all`, `enabled`, `disabled`, `auto`, `teleop`, `test`, or `segment:<i>` ([Scopes and windows](#scopes-and-windows)); default `enabled` when the log records enabled state, else `all`
- `start_time`, `end_time` (optional): Clip the scope to a time range (seconds)
- `voltage_entry` (optional): Battery voltage entry (default: `BatteryVoltage`, or `Voltage` under `PowerDistribution`, `PDH`, `PDP`, or `Battery`; see [The server does not guess](#the-server-does-not-guess))
- `brownout_threshold` (optional): Voltage threshold (default: the logged `BrownoutVoltage`, else 6.8V)
- `channel_limit` (optional): Maximum number of current entries/channels to return, sorted by peak (default: 30; values below 1 are treated as 1)

**Status:** `no_match` (with `looked_for`) when the log has no voltage, current, or brownout flag entries. `no_match` with `scope` when it has them but there is nothing to measure in the scope: the scope holds no time (`enabled` on a log where the robot was never enabled, or a `start_time`/`end_time` outside the data), or no finite voltage or current sample falls in it and no brownout flag is logged. A logged flag holds its value, so it covers any time the scope has. `partial` with `skipped` when either the voltage or the current section cannot be produced; a skipped current section says whether the log has no amperage entries or has them without finite samples in the scope.

**Returns:**
- `scope`: the time scope analyzed (`scope` parameter; default `enabled` when the log records enabled state, else `all`), so idle and boot time do not dilute averages or peaks
- `voltage_analysis`: `{entry, samples, min_voltage, min_voltage_time_sec, max_voltage, avg_voltage, samples_below_threshold, threshold_crossings, seconds_below_threshold, brownout_threshold, brownout_threshold_basis, brownout_threshold_entry (when logged), brownout_risk, brownout_risk_basis}` over the scope, finite samples only. A crossing starts below the threshold and ends when the voltage recovers 0.2 V above it. `brownout_risk` follows the rule above. Absent when no battery voltage entry is found or none of its samples fall in scope (`skipped` says why)
- `rio_brownouts`: `{flag_entry, count, total_sec, events: [{start, end, duration_sec, open_at_log_end?}]}` in scope, when the brownout flag is logged
- `current_entries_analyzed`: number of current entries/channels found (always present; 0 when none)
- `channel_analysis`: present when at least one current entry exists, cut at `channel_limit` with `limits.channel_analysis` (total vs returned); sorted by `|peak_current_A|` descending, over the scope: `{entry, peak_current_A, peak_current_time_sec, max_current_A, min_current_A, avg_current_A, sample_count}`. `peak_current_A` is the sample with the largest magnitude, signed (a −150 A stall on a direction-signed torque current is reported as −150); `max_current_A`/`min_current_A` are the signed extremes. Entries expanded from an array (`double[]`, `float[]`, `int64[]`) add `source_entry` and `channel` (the index) and are named `<entry>[<index>]`; ragged arrays yield per-channel sample counts. Non-finite samples are ignored.
- `warnings`: when no usable voltage entry exists (distinguishing "no voltage-named entry" from "voltage entries exist but none has finite scalar samples"), when no current entries are found, or when the list was truncated by `channel_limit`
- `data_quality` / `server_analysis_directives`: computed from the voltage entry, or from the first scalar current entry (declaration order) when there is no voltage entry; absent for array-only logs

**Example Response** (captured from a real AdvantageKit match log; abridged, and the `channel_analysis` array is trimmed):
```json
{
  "success": true,
  "status": "ok",
  "voltage_analysis": {
    "entry": "/SystemStats/BatteryVoltage",
    "min_voltage": 6.681,
    "max_voltage": 12.844,
    "avg_voltage": 10.647,
    "samples_below_threshold": 1,
    "brownout_threshold": 6.75,
    "brownout_threshold_basis": "logged",
    "brownout_threshold_entry": "/SystemStats/BrownoutVoltage",
    "brownout_risk": "HIGH"
  },
  "rio_brownouts": {
    "flag_entry": "/SystemStats/BrownedOut",
    "count": 1,
    "total_sec": 0.04,
    "events": [{"start": 137.38, "end": 137.42, "duration_sec": 0.04}]
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
CAN bus health from two sources: console and message text that reports CAN failures, and the structured bus counters `analyze_can_bus` reads. Returns `not_applicable` when the log has neither text entries nor CAN counters, because absence of evidence is not GOOD. `health_assessment` is UNKNOWN when CAN error lines exist but the log has no DriverStation state.

**How it works:**
- Text: every line of every text entry is checked (string entries, `string[]` alerts once per appearance, and the string values of json entries). A line is a CAN failure when "CAN" appears as a word (or as CANbus, CANivore, CANcoder; not "cannot", "scan", "Canandgyro", or "cancel") together with timeout, timed out, error, or fault ("default" is not a fault).
- Each line is classified by the robot's state at that moment from the DriverStation timeline `get_match_phases` uses: `while_enabled`, `while_disabled`, or `state_unknown` (before the first DriverStation sample, or no DriverStation data at all).
- Counters: TEC/REC maxima (overall and while enabled) and bus-off increases per bus, from the same analysis as `analyze_can_bus`.

**Health Levels** (errors while disabled are normal, as devices boot and time out, and never count):
- **POOR**: a bus-off count rose while enabled, or 50 or more CAN error lines while enabled
- **CONCERNING**: at least one CAN error line while enabled, or TEC/REC reached 128 (error-passive) while enabled
- **UNKNOWN**: CAN error lines exist but the log has no DriverStation state
- **GOOD**: none of the above

`assessment_basis` states the fact that decided the level.

**Parameters:**
- `path` (required): Path to the log file

**Returns:** `error_counts_by_entry` (per text entry: `total`, `while_enabled`, `while_disabled`, `state_unknown`), `total_can_errors`, `errors_while_enabled`, `errors_while_disabled` (when DriverStation data exists), `errors_state_unknown` (when any), `first_errors_while_enabled` (up to 5 lines with time and entry), `bus_counters[]` (`bus`, `tec_max`, `tec_max_time_sec`, `tec_max_while_enabled`, the same for `rec`, `bus_off_increase`, `bus_off_increase_while_enabled`), `health_assessment`, `assessment_basis`, `inputs.entries`, and `warnings`.

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
Compare one numeric signal across two log files, over the same phase of each.

**Parameters:**
- `path` (required): Path to the first log file
- `compare_path` (required): Path to the second log file (must differ from `path`)
- `name` (required): Entry name to compare, optionally with a [field path](#field-paths) (`/PowerDistribution/ChannelCurrent[3]`; `[*]` pools elements)
- `field` (optional): The field path, instead of appending it to `name`
- `angle` (optional): `radians` or `degrees` for an angle logged as a plain number ([Field paths](#field-paths))
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.windows` records each log’s resolved bounds, keyed by path.
- `scope` (optional): `enabled`, `teleop`, `segment:<i>`, ..., resolved in **each log's own timeline**, so the same phase is compared. A log that cannot be scoped (no DriverStation data) is reported with a `reason` and the other is still compared
- `start_time`, `end_time` (optional): On each log's own clock
- `windows` (optional): Explicit `{start, end}` windows ([Scopes and windows](#scopes-and-windows)), on each log's own clock

**Returns:**
- `entry`, `inputs` (`logs`, `entry`), `logs_compared`
- `comparisons[]`, one per log in argument order: `{log_path, log_filename, log_truncation?, entry_found, signal, scope?, sample_count, statistics: {min, min_at_sec, max, max_at_sec, mean, std_dev, median, p5, p25, p75, p95, angle_unit?}, max_likely_boot_transient?, min_likely_boot_transient?, data_quality}`. When the signal cannot be read, `reason` says why (for example an array entry named without an index, with the element form to use)
- `differences`: second log minus first, for `mean`, `median`, and `p95`, with a note. Two logs are two samples, and samples within a log are autocorrelated, so no significance test is made
- `warnings`: a missing entry, no finite values, or an extreme within 5 s of a log's start (likely a boot transient: compare `scope: "enabled"`)
- `status`: `partial` when only one log has values (`skipped: differences`), `no_match` when neither does
- `server_analysis_directives` from the lower-quality log

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "entry": "/RealOutputs/LoggedRobot/FullCycleMS",
  "logs_compared": 2,
  "comparisons": [
    {
      "log_path": "<logdir>/akit_26-09-30_00-10-26.wpilog",
      "entry_found": true,
      "scope": {"scope": "enabled", "window_count": 4, "total_sec": 1310.34, "...": "..."},
      "sample_count": 48596,
      "statistics": {"min": 7.679, "max": 267.882, "max_at_sec": 1130.51, "mean": 22.19,
                     "std_dev": 15.87, "median": 17.603, "p5": 11.205, "p95": 53.108, "...": "..."},
      "data_quality": {"...": "..."}
    },
    {"log_path": "<logdir>/vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog", "...": "..."}
  ],
  "differences": {"...": "..."},
  "inputs": {"logs": ["...", "..."], "entry": "/RealOutputs/LoggedRobot/FullCycleMS"}
}
```
(Trimmed from the response captured in [TOOL_RESPONSES.md](TOOL_RESPONSES.md#compare_matches).)

### `get_code_metadata`
Extract code metadata from string entries whose leaf name is `GitSHA`, `GitBranch`, `GitDirty`, `GitDate`, `BuildDate`, `ProjectName`, or `Version` (case-insensitive; `Version` only under a path containing "metadata"). AdvantageKit records these from the generated `BuildConstants`, for example `/RealMetadata/GitSHA`. When several entries hold the same key (e.g. `/RealMetadata/` and `/ReplayMetadata/`), the lowest entry id wins and a warning says when their values differ.

**Parameters:**
- `path` (required): Path to the log file

**Returns:** `metadata` (key → the entry's first value; `"unknown"` when the entry has no samples) and `sources` (key → entry name).

**Status:** `no_match` (with `looked_for` and a `hint`) when the log has no metadata entries.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "metadata": {
    "GitSHA": "a1b2c3d4e5f6",
    "GitBranch": "main",
    "GitDirty": "All changes committed",
    "BuildDate": "2024-03-15 10:30:00 EDT"
  },
  "sources": {
    "GitSHA": "/RealMetadata/GitSHA",
    "GitBranch": "/RealMetadata/GitBranch",
    "GitDirty": "/RealMetadata/GitDirty",
    "BuildDate": "/RealMetadata/BuildDate"
  }
}
```

### `moi_regression`
Estimate moment of inertia J (kg·m²) and viscous damping B (Nm·s/rad) for a DC-motor-driven mechanism by ordinary least squares on logged velocity and current.

**Physics model:** `G × motor_count × kt × I = J × α + B × ω`

**Parameters:**
- `path` (required): Path to the log file
- `velocity_entry` (required): Entry path for mechanism velocity (rad/s, or m/s if `wheel_radius` given)
- `current_entry` (required): Entry path for motor current (A)
- `kt` (required): Motor torque constant per motor (Nm/A), positive. Kraken X60=0.01940, NEO Vortex=0.01706, NEO 550=0.0108
- `gear_ratio` (required): Overall gear ratio from motor shaft to output shaft, positive
- `motor_count` (optional): Number of motors driving the mechanism in parallel (default 1)
- `wheel_radius` (optional): Wheel radius (m), positive, for converting linear velocity to angular
- `applied_volts_entry` (optional): Entry for applied voltage, used to recover the torque's sign when current is always non-negative (TalonFX/SparkMax)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` / `end_time` (optional): Analysis time window
- `alpha_threshold` (optional): Minimum |α| (rad/s²) for a sample to enter the fit, which drops near-steady-state samples (default 1.0)
- `smooth_window` (optional): Moving-average half-width applied to velocity before differentiating; must be non-negative, and 0 disables smoothing (default 2)

**Returns:**
- `J_kg_m2`: Estimated moment of inertia
- `B_Nm_s_per_rad`: Estimated viscous damping coefficient
- `r_squared`: Uncentered R² (`null` when undefined), and `rmse_nm`, the fit's RMS residual torque (N·m)
- `n_samples_used`, `n_samples_total`, `filtered_by_alpha_threshold`, and `filtered_by_zero_volts` (when any)
- `parameters_used`: `torque_scale_Nm_per_A`, and `wheel_radius_m`, `applied_volts_used`, `start_time`, `end_time` when given
- `data_quality` of the velocity samples, and `server_analysis_directives`
- `warnings`: negative J, undefined R², R² below 0.2, fewer than 20 samples used, or extreme values (|J| > 1000 or |B| > 100)

**Notes:**
- R² is uncentered (`1 - SS_res / Σy²`) because the physics model has no intercept term; the centered R² does not apply to regression through the origin.
- Samples where current or voltage interpolation returns null (e.g., when the current entry starts later than the velocity entry) are skipped rather than zero-filled, so they cannot distort the fit.

### `analyze_can_bus`
Analyze CAN bus health from the counters the log records, per bus: utilization, the transmit and receive error counters, and bus-off and TX-full counts. Run it when you see intermittent motor controller disconnects, sensor reading timeouts, or "CAN timeout" errors in the Driver Station.

**Parameters:**
- `path` (required): Path to the log file
- `bus_name` (optional): Bus to analyze: `"rio"`, a CANivore name such as `"CANHD"`, or a path prefix (default: every bus found)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds

**How buses are found:** by exact field name, never by a substring such as "can" (which would also match Canandgyro or scan). A bus is a path prefix holding numeric entries named `Utilization`/`BusUtilization`/`PercentBusUtilization`, `BusOffCount`/`OffCount`, `TxFullCount`, `REC`/`ReceiveErrorCount`, or `TEC`/`TransmitErrorCount`. That covers WPILib's `CANStatus` as AdvantageKit logs it under `/SystemStats/CANBus` (named `rio`) and CTRE CANivore status such as `/RealOutputs/CANBus/CANHD/...` (named by the last path segment). The prefix must contain "can", or the group must hold at least two of the counters other than utilization.

**Per bus (`buses[]`):**
- `utilization`: `mean_percent`, `p95_percent`, `max_percent`, `samples`, `unit_detected` (0–1 fractions are detected from the range and converted), and `while_enabled`
- `tec`, `rec`: levels, not counts, so they rise and fall. `max`, `max_time_sec` (first time reached), `error_passive_excursions` (rises to 128 or above; the controller is error-passive at 128 and goes bus-off when TEC passes 255), `time_error_passive_sec` (values held until the next sample), and `while_enabled`
- `bus_off`, `tx_full`: counts that only grow: `first`, `last`, `increase`, `increase_while_enabled` (a decrease is a counter reset, reported as `resets`)

**Other CAN error entries (`errors[]`):** numeric or boolean entries named with CAN (as a word, or CANbus/CANivore/CANcoder) and error, fault, or timeout that are not bus fields. `error_count` is how much the entry increased (each false→true for a boolean), split into `errors_while_enabled`, `errors_while_disabled`, and `errors_state_unknown`.

**Also returns:** `utilization[]` (one row per bus in percent, for compatibility), `enabled_error_total` (increases of bus-off, TX-full, and other error entries while enabled), `inputs`, and `data_quality` and `server_analysis_directives` of the first bus's first counter entry (utilization first) in the window. CAN status entries are often logged at a low rate, which bounds the utilization statistics, not the counter maxima and increases.

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

**Utilization guidelines** (rules of thumb, not something the tool applies):
- Below 50%: healthy, plenty of bandwidth
- 50–70%: fine; watch it when adding devices
- 70–85%: concerning; reduce status frame rates
- Above 85%: high risk of timeouts and errors

To bring utilization down: reduce motor controller status frame rates, move devices to a CAN FD bus where the hardware supports it, remove devices you do not need, and lower PDH/PDP reporting rates.

---

## FRC Domain Tools

### `get_ds_timeline`
A chronological timeline of robot events: enable/disable transitions, match phase changes, battery-voltage threshold crossings, and roboRIO brownout flag transitions (when the robot logs one). Errors and warnings in text entries are **counted and summarized, not listed**: the timeline reports exact counts and a distinct-message summary, and `search_strings` gives the complete, paged listing, so no heuristic decides which messages you see. DriverStation entries are recognized under both the `/DriverStation/...` (AdvantageKit) and `DS:...` (WPILib DataLogManager) naming conventions. Returns `not_applicable` when the log has none of the entries a timeline is built from (DriverStation state, a battery voltage entry, a roboRIO brownout flag, text entries). The result carries no `data_quality` block: its fields are observed events and exact counts, not statistics.

**Parameters:**
- `path` (required): Path to the log file
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` (optional): Start timestamp in seconds
- `end_time` (optional): End timestamp in seconds
- `voltage_entry` (optional): Battery voltage entry (default: `BatteryVoltage`, or `Voltage` under `PowerDistribution`, `PDH`, `PDP`, or `Battery`; see [The server does not guess](#the-server-does-not-guess))
- `brownout_threshold` (optional): Voltage threshold for BROWNOUT_START/END crossings (default: the log's `BrownoutVoltage` entry when logged, else 6.8V for roboRIO 1; reported as `brownout_threshold` with `brownout_threshold_basis`)

**Returns:** `event_count`; chronologically sorted `events`, each with `category`, `type`, `timestamp`, and `source` entry; `summary` (count per category); `inputs.entries` (the DriverStation, voltage, and brownout flag entries used); `brownout_threshold` and `brownout_threshold_basis`; `brownout_voltage_entry` (the voltage entry scanned for threshold crossings, selected exactly as `power_analysis` selects it, or a warning instead when the log has none); `rio_brownout_flag_logged` (whether the log contains a boolean roboRIO brownout flag entry) and, when it does, `rio_brownout_flag_entry`; `text_event_counts`, `text_event_summary`, and `text_event_groups_total` (see below); and `warnings`.

**Event categories:**
- `robot_state`: ENABLED, DISABLED. These are the transitions of the same DriverStation timeline `get_match_phases` uses (one entry per role, AdvantageKit first; a log with both `DS:` and `/DriverStation/` entries gets one set of events and a warning naming the ignored entries). The state at the start of the log is reported once with `initial: true`. A warning says when the log has no DriverStation enabled entry.
- `match_phase`: AUTO_START, TELEOP_START, TEST_START, at the start of each enabled segment in that mode and at a mode change while enabled. A practice session with `Autonomous` held false has a TELEOP_START at every enable.
- `power`: BROWNOUT_START and BROWNOUT_END (`basis: "voltage_threshold"`: the battery voltage crossed `brownout_threshold`, with 0.2 V exit hysteresis; includes `voltage`), and RIO_BROWNOUT_START and RIO_BROWNOUT_END (`basis: "rio_flag"`: a logged boolean brownout flag such as AdvantageKit `/SystemStats/BrownedOut` changed state, the roboRIO's own brownout state). A voltage crossing does not by itself mean the roboRIO cut outputs; when `rio_brownout_flag_logged` is false, that cannot be determined from the log.
- `alert`: ALERT_RAISED, each message of a `string[]` alert entry (WPILib `Alert`s, e.g. `/RealOutputs/Alerts/warnings`) when it appears, with `entry`, `level` (from the entry name), `message`, and `cleared_at`/`duration_sec`, or `active_at_log_end: true`. At most 100 are listed, with a warning when there are more; `search_strings` lists every one.

**Error and warning text** (string entries such as `/RealOutputs/Console` or WPILib `messages`, alerts, and json strings): a sample is an ERROR when any of its lines contains "error", "exception", or "fault" ("default" does not count); otherwise it is a WARNING when any line contains "warning", "overrun", or "watchdog". Errors win regardless of line order, and the first matching line of the winning kind is the message. `search_strings` uses the same rule for its `level` filter, so the two agree (a test enforces it).
- `text_event_counts`: `{error, warning, total, by_source: {<entry>: {error, warning}}}`, exact counts within the time window over string samples, alerts (once per appearance, at their entry's level), and json string values. Never capped, and always present
- `text_event_summary`: one entry per distinct message, where "distinct" is judged after normalizing numbers to `#` and collapsing whitespace, so `Loop time of 0.023s overrun` and `... 0.031s ...` are one group. Each entry is `{type, message, example, count, variants, variants_capped?, first_timestamp, last_timestamp, sources[]}`: `message` is the normalized pattern, `example` the first actual text (when it differs), and `variants` how many different raw texts the group covers (`CAN timeout on device #` with `variants: 2` hides two devices). Variants are judged on the full line, while `message` and `example` are cut at 200 characters for display; `variants_capped: true` marks a group that exceeded 10,000 distinct texts. Sorted by count. At most 200 groups are shown; `text_event_groups_total` is the true number, and a warning says when the summary was cut. Absent when the log has no error or warning text
- Individual messages are not placed on the timeline. Use `search_strings` (optionally `level=error`, a regex, a time window) to list them completely with paging totals

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
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
Analyze vision data: pose observation streams, target streams, pose sets, has-target flags, and pose jumps.

**Which entries it reads:** the ones the AdvantageKit vision template and the vision libraries publish under their own names, and the ones passed as `vision_entries`. Entries that only have the content or the name of vision data are listed in `candidates`, by kind, with `needs_confirmation`, and are neither analyzed nor decoded. Content is not enough: a planned trajectory is also a struct array of timestamps and poses (and, logged every loop, millions of them), a gyro's struct also has yaw and pitch fields, and a robot's own `HasTargetLock` need not be a camera's. The robot's source code says what each one is.

| Kind | Read by convention | Listed as a candidate |
|---|---|---|
| `observation_streams` | `struct:PoseObservation[]` whose records hold a `timestamp` and a pose (the vision template's `/Vision/Camera<N>/PoseObservations`) | other struct arrays whose records hold a timestamp and a pose |
| `target_streams` | `struct:TargetObservation` with `yaw` and `pitch` fields | other structs with `yaw` and `pitch` fields |
| `pose_sets` | the template's pose arrays: `Vision/Summary/` and `Vision/Camera<N>/` `TagPoses`, `RobotPoses`, `RobotPosesAccepted`, `RobotPosesRejected` | other `Pose2d[]` and `Pose3d[]` entries under a vision, camera, PhotonVision, or Limelight path |
| `has_target` | Limelight's `<table>/tv`, PhotonVision's `photonvision/<camera>/hasTarget` | other boolean or numeric entries named `hasTarget`, `targetValid`, or `tv` |
| `pose_estimates` | the only scalar pose under a vision, camera, PhotonVision, or Limelight path | those poses, when there are several |

**Parameters:**
- `path` (required): Path to the log file
- `vision_prefix` (optional): Only vision entries under this prefix (case-insensitive). It limits vision entries only; the robot pose can live elsewhere, and so can entries passed as `vision_entries`
- `vision_entries` (optional): Entries to analyze besides the conventional ones. Each is analyzed by its shape: a boolean or a number as a has-target flag (above 0.5 means a target), a `Pose2d[]` or `Pose3d[]` as a pose set, a scalar pose as a pose estimate checked for jumps, a struct array holding a timestamp and a pose as an observation stream, a struct with yaw and pitch as a target stream. An entry that is missing or has another shape is an error
- `pose_entry` (optional): Robot pose entry (`struct:Pose2d` or `Pose3d`) for residuals and jump detection. Default: the `robot_pose` role (a conventional name, or the only `Pose2d` outside vision paths; several others are listed in `skipped` to confirm, not guessed)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time` (optional): Time window
- `jump_threshold` (optional): Distance threshold for pose jump detection in meters (default: 0.5)
- `flicker_window` (optional): Time window for flicker detection in seconds (default: 0.5)

**observation_streams:** one stream per camera. Per stream: `entry`, `camera`, `records`, `records_with_observations`, `fraction_with_observations`, `observation_count`, `observations_per_second`, `tag_count_distribution`, `ambiguity` (`n`, `median`, `p95`, `max`), `latency` (log timestamp minus the observation's own timestamp: `median_ms`, `p95_ms`, `max_ms`), `latency_candidates` (numeric entries beside the stream whose name contains `latency`, e.g. `/Vision/Camera0/LatencyMs`; they are listed, not analyzed, because the name does not say what the entry times or in which units: `get_statistics` reads one once the robot code has settled what it times and in which units), and `residual_vs_robot_pose` (`median_m`, `p95_m`, `max_m` of the planar distance between each observation and the robot pose linearly interpolated at the observation's timestamp). The robot pose may itself include vision corrections.

**target_streams:** per stream: `camera`, `records`, `observation_count`, `yaw` and `pitch` distributions (median, p95, max; with a `_deg` suffix when they are WPILib `Rotation2d`s), `area`, `confidence`, and `object_ids` (counts per id).

**pose_sets:** `records`, `records_non_empty`, `fraction_non_empty`, `pose_count`, `mean_poses_per_non_empty_record`, `max_poses_per_record`.

**target_acquisition:** `total_samples`, `valid_samples`, `acquisition_rate`, `flicker_events` per has-target entry. Values logged only on change make the per-sample rate approximate.

**candidates:** present, with `needs_confirmation` and `candidates_note`, when the log has entries that only look like vision data: an object with the candidates of each kind (`observation_streams`, `target_streams`, `pose_sets`, `has_target`, `pose_estimates`), at most 20 of each, with `candidate_counts` when a kind has more. A kind that has candidates and nothing analyzed is also listed in `skipped`.

**pose_jumps:** steps larger than `jump_threshold` between consecutive samples of the robot pose and of the vision pose estimates (`pose_entries_checked`). Always present (empty when none), with `jump_count` the true total and `limits.pose_jumps` (at most 100 listed). Samples whose pose cannot be read are counted in `unreadable_pose_samples`, never treated as zero movement. A jump within 0.5 s of an enable has `near_enable_sec` (seconds from the enable). Odometry is often reset there, for example when an autonomous routine sets its starting pose, so such a jump is not by itself evidence of a vision correction.

**Also returns:** `inputs.entries.robot_pose`, and `data_quality` and `server_analysis_directives` of the first observation stream (else the first target stream, else the first pose checked for jumps).

**Status:** `no_match` (with `looked_for`, and with `candidates` and `needs_confirmation` when there are any) when the log has no conventional or passed vision entry and no scalar pose; `partial` when only pose jumps could be checked (for example a `vision_prefix` that matches nothing), or when a kind has candidates and nothing analyzed.

Pose jumps can point to ambiguous AprilTag detections, tag misidentification, poorly tuned vision standard deviations, or exposure problems.

**Example Response (abridged):**
```json
{
  "success": true,
  "status": "ok",
  "inputs": {"entries": {"robot_pose": "/RealOutputs/Drive/Pose"}},
  "observation_streams": [
    {
      "entry": "/Vision/Camera3/PoseObservations",
      "camera": "Camera3",
      "records": 4225,
      "records_with_observations": 2377,
      "observation_count": 2377,
      "observations_per_second": 1.5,
      "tag_count_distribution": {"1": 2377},
      "ambiguity": {"n": 2377, "median": 0.0, "p95": 0.12, "max": 0.31},
      "latency": {"n": 2377, "median_ms": 61.2, "p95_ms": 84.0, "max_ms": 180.0, "basis": "log timestamp minus the observation's own timestamp"},
      "residual_vs_robot_pose": {"n": 2377, "median_m": 0.04, "p95_m": 0.21, "max_m": 1.3, "robot_pose_entry": "/RealOutputs/Drive/Pose", "basis": "..."}
    }
  ],
  "target_acquisition": [],
  "pose_jumps": [{"timestamp": 370.1, "entry": "/RealOutputs/Drive/Pose", "distance": 0.61}],
  "jump_count": 1,
  "limits": {"pose_jumps": {"total": 1, "returned": 1, "limit": 100}},
  "pose_entries_checked": ["/RealOutputs/Drive/Pose"]
}
```

### `compare_poses`
The difference between two pose streams (`struct:Pose2d` or `Pose3d`, the latter projected on the floor): pose minus reference, sampled at the records of `pose_entry` with `reference_entry` interpolated there.

**Parameters:**
- `path` (required): Path to the log file
- `reference_entry` (required): The pose measured against, e.g. a path setpoint (`/PathPlanner/targetPose`), another estimator, or a camera's pose estimate
- `pose_entry` (optional): The pose measured; default: the `robot_pose` role (a conventional name, else the only `Pose2d`; several others are listed to confirm, not guessed)
- `frame` (optional): `field` (default: `dx_m`, `dy_m` in field coordinates) or `reference` (`along_m`, positive when the pose is ahead of the reference along its heading, and `cross_m`, positive to its left: a path-following error as it is usually read)
- `interpolation` (optional): `linear` (default; heading along the shortest arc) or `previous` (for a reference logged when it changes)
- `max_gap_sec` (optional): Longest reference gap to interpolate across (default 0.25 s); records without a reference value are counted in `unaligned`
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time`, `scope`, `windows` (optional): Time scope, as for the statistics tools

**Returns:** `pose_entry`, `reference_entry`, `frame`, `interpolation`, `count`, `unaligned`; `distance_m` (count, mean, median, p95, max, rmse); `heading_difference_rad` (median, p95, and max of its size, and `mean_signed`); the two components (count, mean, std_dev, p5, p95); `largest` (the times of the five largest distances, with `limits.largest`); `inputs`; `data_quality` of the pose entry and `server_analysis_directives`. When `pose_entry` was not passed, `robot_pose` shows how it was chosen. `no_match` when no record in scope has a reference value. To measure each camera observation at its own timestamp, use `analyze_vision` (`residual_vs_robot_pose`).

**Example** (the turret's pose in the robot's frame, VACHE q10, enabled; abridged): the turret sits 0.058 m behind and 0.126 m to the right of the robot's center (p5 and p95 are equal), except around an odometry reset at the start of autonomous, where the largest distances fall:
```json
{
  "status": "ok",
  "pose_entry": "/RealOutputs/Launcher/TurretPose",
  "reference_entry": "/RealOutputs/Drive/Pose",
  "frame": "reference",
  "count": 6681,
  "unaligned": 0,
  "distance_m": {"count": 6681, "mean": 0.1625, "median": 0.1385, "p95": 0.1385, "max": 4.69, "rmse": 0.34},
  "heading_difference_rad": {"count": 6681, "median": 1.746, "p95": 2.99, "max": 3.14, "mean_signed": -0.093},
  "along_m": {"count": 6681, "mean": -0.0625, "std_dev": 0.0605, "p5": -0.0577, "p95": -0.0577},
  "cross_m": {"count": 6681, "mean": -0.1087, "std_dev": 0.3102, "p5": -0.1260, "p95": -0.1260},
  "largest": [{"timestamp_sec": 112.008, "distance_m": 4.69}, "..."],
  "limits": {"largest": {"total": 6681, "returned": 5, "limit": 5}}
}
```

### `pose_corrections`
How much a pose changed beyond what odometry predicts. For each pair of consecutive `pose_entry` records (in scope, at most `max_interval_sec` apart): the pose's change minus the predicted change. The prediction is the change of `odometry_pose_entry` (a pose from wheel odometry alone, rotated into the pose's frame), or else `chassis_speeds_entry` integrated over the interval (trapezoidal, linear between speed samples; robot-relative speeds rotated by the pose's heading).

**Parameters:**
- `path` (required): Path to the log file
- `pose_entry` (optional): The pose, e.g. a pose estimator's output; default: the `robot_pose` role
- `odometry_pose_entry` (optional): A pose from wheel odometry alone; when given, its change is the prediction
- `chassis_speeds_entry` (optional): `struct:ChassisSpeeds` to integrate when no odometry pose is given; default: the measured chassis speeds role (a name containing "measured", or the only `ChassisSpeeds` not named like a setpoint; several are listed to confirm)
- `speeds_frame` (optional): `robot` (default, as kinematics produce them) or `field`
- `threshold_m` (optional): Residual translation that counts as a correction (default 0.05 m)
- `heading_threshold_rad` (optional): Residual heading that also counts (default: none)
- `max_interval_sec` (optional): Longest interval between pose records to compare (default 0.1 s)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time`, `scope`, `windows` (optional): Time scope; both records of an interval must fall in the same window
- `limit` (optional): Corrections listed (default 50, max 500)

**Returns:**
- `odometry`: the source (`chassis_speeds` or `odometry_pose`), its entry, and for speeds the frame used and `frame_check`: the median residual read as robot-relative and as field-relative. A warning says when the other frame fits better (a field-relative speeds entry read as robot-relative predicts the wrong direction in every interval).
- `intervals`: `analyzed`, `longer_than_max`, `without_odometry` (speed samples do not bracket the interval, or the odometry pose has a gap), `unreadable_pose_records`
- `residual_translation_m` (count, mean, median, p95, p99, max) and `residual_heading_rad` (sizes)
- `correction_count`, `total_translation_m`, and `corrections` in time order (`timestamp_sec`, `interval_sec`, `dx_m`, `dy_m`, `translation_m`, `heading_rad`, `speed_mps`, and `near_enable_sec` within 0.5 s of an enable, where odometry is often reset), with `limits.corrections`
- `correction_interval_sec`: the time between consecutive corrections (n, min, median, p95, max), for example the cadence of vision updates
- `inputs`, `data_quality` of the pose entry, and `server_analysis_directives`

A residual is the pose estimator's change beyond odometry: vision corrections, but also wheel slip, collisions, pose resets, and timing differences between the entries. Compare with the vision entries (`analyze_vision`) before attributing a residual to vision, and use `find_condition` to limit the scope to driving. In a simulated CTRE swerve log with no vision, where the pose is odometry, the residual median was 0.3 mm and the p99 1 cm.

**Example** (the log of the [robustness review](ROBUSTNESS_REVIEW.md), while the robot sat disabled; abridged): the robot is still (`speed_mps` ~0.0004) while its pose steps about 0.3 m back and forth every 0.1–0.2 s:
```json
{
  "status": "ok",
  "pose_entry": "/RealOutputs/Drive/Pose",
  "odometry": {"source": "chassis_speeds", "entry": "/RealOutputs/SwerveChassisSpeeds/Measured", "speeds_frame": "robot",
               "frame_check": {"robot_median_m": 0.000008, "field_median_m": 0.000008}},
  "intervals": {"analyzed": 10465, "longer_than_max": 103, "without_odometry": 0, "unreadable_pose_records": 0},
  "residual_translation_m": {"count": 10465, "mean": 0.0136, "median": 0.000008, "p95": 0.109, "p99": 0.296, "max": 0.559},
  "correction_count": 712,
  "total_translation_m": 117.8,
  "corrections": [
    {"timestamp_sec": 359.326, "interval_sec": 0.020, "dx_m": 0.140, "dy_m": -0.274, "translation_m": 0.308, "heading_rad": -0.034, "speed_mps": 0.0004},
    {"timestamp_sec": 359.429, "interval_sec": 0.021, "dx_m": -0.141, "dy_m": 0.290, "translation_m": 0.323, "heading_rad": 0.039, "speed_mps": 0.0004}
  ],
  "limits": {"corrections": {"total": 712, "returned": 3, "limit": 3}},
  "correction_interval_sec": {"n": 711, "min": 0.096, "median": 0.135, "p95": 0.904, "max": 368.7}
}
```

### `profile_mechanism`
Profile one closed-loop mechanism from the entries passed for its roles: following error, step response, stalls, and motor temperature.

**Parameters:**
- `path` (required): Path to the log file
- `setpoint_entry`, `measurement_entry`, `velocity_entry`, `current_entry`, `temperature_entry` (optional): The mechanism's entries (scalar numbers). Only entries passed here are analyzed. The setpoint and the measurement must be in the same units
- `mechanism_name` (optional if role entries are given): Text the mechanism's entry names contain (case-insensitive, anywhere in the name; e.g. `Elevator`, or `ModuleFrontLeft/Drive`). It finds candidates; it does not choose entries
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time` (optional): Time window (applies to every section)
- `stall_current_threshold` (optional): Current above which a stopped mechanism counts as stalled (default: 30 A)
- `stall_velocity_threshold` (optional): `|velocity|` below this counts as stopped, in the velocity entry's units (default: 0.01)

**Why the entries are passed:** nothing in a log says that an entry is a mechanism's setpoint or its measurement, or what its units are. Names do not settle it: a `currentHeight` is the present height, PhotonVision's `targetYaw` is a camera reading and not a setpoint, and a setpoint in rotations beside a position in meters gives a following error that means nothing. The robot's source code, where each entry is logged, does settle it.

**Candidates:** with `mechanism_name`, `candidates` lists, per role, the scalar numeric entries that contain the name and whose leaf suggests the role (at most 10 each, with `candidate_counts` when there are more):
- setpoint: `setpoint`, `goal`, `target`, `reference`, `desired`, `commanded`
- temperature: `temp`, `temperature`, `celsius`
- current: the amperage rule `power_analysis` uses
- velocity: `velocity`, `speed`, `rpm`, `rps`
- measurement: `position`, `actual`, `measured`, `angle`, `height`, `distance`, `rotations`, but not velocity, current, or voltage names

With `mechanism_name` alone, the result is `no_match` with `needs_confirmation` and the candidates. With some roles passed, the analysis uses those, `candidates` covers the roles not passed, and each skipped section names its candidates. `roles` names every entry used (null when not passed).

**Returns:**
- `following_error` (setpoint and measurement): `rmse`, `mean_error` (bias), `max_abs_error`, and `samples`, of the measurement minus the setpoint **in force** (held until the next setpoint sample). Then `steps` (setpoint changes larger than 5% of the previous setpoint, and at least 0.01), `settled_steps`, `settling_time_sec` (`avg`, `max`, `min`: time until the measurement enters and stays within 5% of the step size, before the next step), `overshoot_percent` (average over steps of the overshoot beyond the new setpoint as a percent of the step size), `max_overshoot_percent`, and `step_details` (the first 20 steps, with `limits.step_details`)
- `stall_events` (velocity and current): intervals of `|velocity|` below the velocity threshold with `|current|` above the current threshold (`start_time`, `end_time`, `duration`, `max_current`, `open_at_end` when still stalled at the end of the data); `stall_count` is the true total
- `temperature` (temperature entry): `max`, `max_time_sec`, `first`, `last`
- `data_quality` and `server_analysis_directives` of the measurement entry (else the velocity entry) in the window
- `skipped`: each section whose entries were not passed, with the parameter to pass and its candidates (status `partial`)

**Status:** `no_match` when no role entry was passed: with `needs_confirmation` and `candidates` when `mechanism_name` matches entries, without them when it matches none.

**Example Response (abridged):**
```json
{
  "success": true,
  "status": "partial",
  "mechanism": "Elevator",
  "roles": {"setpoint": "/RealOutputs/Elevator/GoalMeters", "measurement": "/Elevator/PositionMeters",
    "velocity": "/Elevator/VelocityMetersPerSec", "current": "/Elevator/CurrentAmps", "temperature": null},
  "following_error": {
    "rmse": 0.015, "mean_error": -0.004, "max_abs_error": 0.089, "samples": 7500,
    "steps": 14, "settled_steps": 13,
    "settling_time_sec": {"avg": 0.45, "max": 0.9, "min": 0.3},
    "overshoot_percent": 12.5, "max_overshoot_percent": 21.0,
    "step_details": [{"time": 45.0, "from": 0.1, "to": 1.2, "overshoot_percent": 12.0, "settling_time_sec": 0.42}]
  },
  "stall_events": [{"start_time": 45.23, "end_time": 45.78, "duration": 0.55, "max_current": 38.2}],
  "stall_count": 1,
  "skipped": [{"section": "temperature", "reason": "Needs a temperature entry: temperature_entry was not passed."}]
}
```

**Reading the results for tuning** (general control advice, not something the tool computes):
- High RMSE or overshoot: try more D or less P
- Slow settling: try more P, or add feedforward
- Stall events: check for mechanical binding, too little power, or wrong current limits
- High overshoot with fast settling: aggressive tuning, acceptable for many mechanisms

### `analyze_auto`
Analyze every autonomous period in the log: when it started and ended, which routine was selected, and how closely the robot followed its path.

**Parameters:**
- `path` (required): Path to the log file
- `auto_prefix` (optional): Entry name prefix to search for the path setpoint and actual pose entries
- `chooser_entry` (optional): String entry holding the selected routine (default: the one chooser whose key contains `auto`)
- `path_setpoint_entry` (optional): Path-following setpoint pose (default: `PathPlanner/targetPose` or `Odometry/TrajectorySetpoint`)
- `path_actual_entry` (optional): Actual pose for path following (default: `PathPlanner/currentPose`, else the robot pose)

**How it works:**
- Autonomous periods are the enabled `auto` segments of the same DriverStation timeline `get_match_phases` reports (a log can hold several; all are listed in `auto_periods`, and the top-level `auto_*` fields describe the first).
- Selected routine: the value, at each period's start, of the `auto_chooser` role ([The server does not guess](#the-server-does-not-guess)). That is `chooser_entry` when given, else the one chooser whose key contains `auto`: a WPILib `SendableChooser`'s `active` entry (its sibling `.type` is `String Chooser`, or it has `options`) or an AdvantageKit dashboard input (`/NetworkInputs/SmartDashboard/<key>`, or `/DashboardInputs/...`). With no such chooser, or more than one, nothing is chosen. The choosers and the string entries named like a selected auto mode (a name containing `auto` together with `selected`, `mode`, `routine`, `choice`, or `chooser`; e.g. `/RealOutputs/AutoSelector/SelectedAutoMode`) are listed in `skipped` as candidates to confirm and pass as `chooser_entry`. Chooser metadata (`.type`, `default`, `options`) is never read as the selection.
- Path following: the `path_setpoint` and `path_actual` roles, chosen among `struct:Pose2d`/`struct:Pose3d` entries with at least two samples (under `auto_prefix` when given). Setpoint: `path_setpoint_entry`, else `PathPlanner/targetPose` or AdvantageKit `Odometry/TrajectorySetpoint`; other poses named like a setpoint (`setpoint`, `target`, `desired`) are candidates listed in `skipped`, not used. Actual: `path_actual_entry`, else `PathPlanner/currentPose`, else the robot pose as `resolve_signals` chooses it (the `robot_pose` role). The result is the RMSE and maximum distance between them, sampled at each setpoint time with the actual pose held (zero-order hold). Samples whose pose layout cannot be read are counted in `unreadable_samples`, never treated as zero error.

**Returns:** `auto_periods[]` (`start`, `end`, `duration`, `end_reason`, `selected_routine`, `path_following_error` with `rmse_meters`, `max_error_meters`, `samples`), `auto_start_time`/`auto_end_time`/`auto_duration`/`selected_routine`/`path_following_error` for the first period, `expected_auto_sec` (season timing), `inputs.entries` (DriverStation, chooser, and pose entries used), and `skipped` entries for sections that could not be produced (status `partial`).

**Status:** `not_applicable` when the log has no autonomous period, with a `reason` that says why, e.g. "No autonomous period: /DriverStation/Autonomous has 1 sample(s), all false"; `no_match` when the log has no DriverStation state entries.

**Path following error:** lower RMSE means closer path following. Rough guide:
- Below 0.05 m: excellent
- 0.05 to 0.15 m: good, acceptable for most games
- Above 0.15 m: poor; check controller tuning or wheel slip

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
Game piece cycle times from a mechanism's state entry: complete and incomplete cycles, their statistics, and optionally the time spent idle.

**Cycle detection modes:**
- `start_to_start` (default): from one change into `cycle_start_state` to the next. Suits a regular repeating pattern. The state entry is read as a state: a value repeated every loop (as periodic logging writes it) is one state, not a new cycle per sample.
- `start_to_end`: from `cycle_start_state` to the next `cycle_end_state`. Suits a workflow with distinct start and end states.

**Parameters:**
- `path` (required): Path to the log file
- `state_entry` (required): The mechanism's state entry
- `cycle_mode` (optional, default `start_to_start`): `start_to_start` or `start_to_end`
- `cycle_start_state` (optional in the schema, but required by both modes): State value marking a cycle's start (e.g., `"INTAKING"`)
- `cycle_end_state` (optional): State value marking a cycle's end (e.g., `"SCORING"`); required for `start_to_end`
- `idle_state` (optional): State value for idle (dead) time (e.g., `"IDLE"`)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time` (optional): Time window in seconds
- `case_sensitive` (optional, default `true`): Whether state matching is case-sensitive
- `limit` (optional, default 10): Maximum cycles and dead periods to list

**Returns:**
- `sample_count`: state samples in the window
- `cycle_mode`
- `warnings`: data quality warnings, if any (below)
- `cycle_times`: `count`, `avg_sec`, `min_sec`, `max_sec` of complete cycles
- `cycles`: `start_time`, `end_time`, `duration`, and `incomplete` for each cycle, cut at `limit`
- `dead_time` (with `idle_state`): `total_sec` (complete idle periods), `period_count`, `avg_duration_sec`
- `dead_time_periods` (with `idle_state`): `start_time`, `end_time`, `duration`, and `incomplete: true` for an idle period still open at the end, cut at `limit`
- `limits.cycles`, `limits.dead_time_periods`: `{total, returned, limit}` for the two lists
- `data_quality` and `server_analysis_directives` of the state entry

Returns `no_match`, listing the states seen (up to 10), when `cycle_start_state` never occurs. A missing state value for the chosen mode is an error.

**Data quality warnings:**
- More than 5 state transitions less than 0.1 s apart, which may mean an unstable state machine or noise
- States that match none of `cycle_start_state`, `cycle_end_state`, and `idle_state` (listed when there are at most 5), which may mean unexpected behavior or a typo in a state name
- Incomplete cycles, which should be left out of statistics

**Example request (start_to_end)**
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

**Example request (start_to_start)**
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
  "status": "ok",
  "sample_count": 1250,
  "cycle_mode": "start_to_end",
  "warnings": [
    "Detected unknown states: ERROR_STATE, UNKNOWN",
    "1 cycle(s) incomplete (log ended mid-cycle). Exclude from statistical analysis."
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
    {"start_time": 24.8, "end_time": 27.0, "duration": 2.2},
    {"start_time": 39.4, "end_time": 42.0, "duration": 2.6}
  ]
}
```

**Incomplete cycles:** a cycle marked `incomplete: true` had started but not ended when the data ran out: the log stopped mid-cycle, the match ended during a cycle, or `end_time` cut it off. Its `end_time` is the state entry's last sample, or the `end_time` argument when that comes first. Incomplete cycles are listed but left out of `cycle_times`.

### `analyze_replay_drift`
Validate AdvantageKit deterministic replay. Run it on a replay output log (the `_sim` log), which holds both `/RealOutputs/<name>` (what the robot computed) and `/ReplayOutputs/<name>` (what replay computed from the same inputs).

**How it compares:** every `/RealOutputs/X` entry with a `/ReplayOutputs/X` counterpart, sample by sample, with timestamps matched within 1 ms. Numbers are equal within `relative_tolerance` (default 1e-9 of their magnitude, and 1e-12 absolute); arrays and structs are compared element by element; strings and booleans exactly.

**Parameters:**
- `path` (required): Path to the replay output log
- `relative_tolerance` (optional): Relative tolerance for numbers (default 1e-9)
- `limit` (optional): Maximum divergent entries to list (default 20)

**Returns:** `pairs_compared`, `samples_compared`, `divergent_count` (entries with at least one divergent sample), `divergences[]` sorted by first divergence (`entry`, `type`, `first_divergence_time`, `divergent_samples`, `compared_samples`, `max_abs_difference` for numbers, and `first_divergence` with both values), `limits.divergences` (total vs returned), `real_only_count`/`real_only_entries` and `replay_only_count`/`replay_only_entries` (up to 50 names each; a warning says when real outputs went uncompared), `samples_without_counterpart`, and `relative_tolerance`.

**Status:** `not_applicable` on a log with no `/ReplayOutputs/` entries (a real-robot log); `no_match` when no names pair up.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "pairs_compared": 45,
  "samples_compared": 331200,
  "divergent_count": 2,
  "relative_tolerance": 1e-9,
  "divergences": [
    {
      "entry": "/RealOutputs/Drive/Odometry/Pose",
      "type": "struct:Pose2d",
      "first_divergence_time": 12.34,
      "divergent_samples": 150,
      "compared_samples": 7500,
      "max_abs_difference": 0.0031,
      "first_divergence": {"timestamp": 12.34, "real": "{...}", "replay": "{...}"}
    }
  ],
  "limits": {"divergences": {"total": 2, "returned": 2, "limit": 20}},
  "real_only_count": 0,
  "replay_only_count": 0
}
```

When replay outputs do not match real outputs, the entries that diverge first usually lead to the non-deterministic code. Common causes are `Timer.getFPGATimestamp()`, `Math.random()`, network data, and sensor reads outside AdvantageKit inputs.

### `predict_battery_health`
Battery and power-delivery evidence, with a heuristic health score and risk level. The facts come first; the score, kept by design for decisions in the pit at competition, summarizes them and is reported beside them.

**Parameters:**
- `path` (required): Path to the log file
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `scope` (optional): `all`, `enabled`, `disabled`, `auto`, `teleop`, `test`, or `segment:<i>` (default: `enabled` when the log records enabled state, else `all`, so averages do not mix in idle time); combined with `start_time`/`end_time`
- `start_time`, `end_time` (optional): Clip the scope to a time range (seconds)
- `nominal_voltage` (optional): Expected full battery voltage (default: 12.6V)
- `brownout_threshold` (optional): Brownout threshold (default: the logged `BrownoutVoltage`, else 6.8V; see `power_analysis`)
- `warning_threshold` (optional): Voltage below which a dip is reported (default: 9.0V)
- `voltage_entry` (optional): Battery voltage entry (default: `BatteryVoltage`, or `Voltage` under `PowerDistribution`, `PDH`, `PDP`, or `Battery`; see [The server does not guess](#the-server-does-not-guess))
- `total_current_entry` (optional): Total robot current for the load line (default: a leaf named `TotalCurrent`)

**Evidence returned:**
- `voltage_stats`: `min_volts` (with `min_time_sec`), `max_volts`, `avg_volts`, `voltage_sag` (nominal − min), `samples`, all over the scope. The voltage entry is chosen as `power_analysis` chooses it (`inputs.entries.voltage`)
- `brownout_threshold`, `brownout_threshold_basis`, `brownout_events`, and `brownout_basis`: when the roboRIO's brownout flag is logged, brownouts are its true intervals (`rio_brownouts`, with start and duration), the times outputs were actually disabled. Otherwise they are crossings below the threshold, and the basis says the roboRIO state cannot be determined
- `threshold_crossings` and `brownout_details`: voltage crossings below the threshold (0.2 V exit hysteresis), whether or not the roboRIO browned out. `brownout_details` lists the first 10, with `limits.brownout_details`
- `warning_events`: dips below `warning_threshold`
- `load_line` (when a `TotalCurrent` entry exists, or `total_current_entry` names one, and the scope has at least 30 samples spanning 10 A): battery voltage regressed on total current. Fields: `current_entry`, `resistance_ohm` (effective source resistance: battery internal resistance plus wiring and connectors), `open_circuit_voltage`, `r_squared`, `samples`, `current_range_a`. Otherwise listed in `skipped`
- `recovery_analysis`: `avg_recovery_sec`, `max_recovery_sec`, and `sample_count` for the time to recover 90% of drops larger than 0.5 V
- `health_score` with `health_score_basis`, and `risk_level` with `risk_level_basis` (rules below)
- `observations` (also returned as `recommendations`): what the evidence is consistent with and what would distinguish the candidate causes. One log cannot tell a weak battery from high current draw or a high-resistance connection, so no replacement advice is given.

Threshold crossings, dips, and recovery times are found within each window of the scope, never across the time between two. `data_quality` is of the voltage entry over the scope, scored before non-finite samples are dropped.

**Health score (0–100, heuristic):** starts at 100 and deducts:

| Factor | Penalty |
|--------|---------|
| Average voltage below 88% of nominal (≈11.1 V) | (0.88 − average/nominal) × 150 |
| Each brownout (as counted above) | 20 |
| Each dip below `warning_threshold` beyond the brownout count | 5 |
| Slow recovery (average over 0.5 s) | (average − 0.5 s) × 20 |
| Minimum voltage below 10 V | (10 V − minimum) × 10 |

**Risk levels:** CRITICAL when the roboRIO's logged brownout flag shows it disabled outputs; HIGH for crossings below the threshold with no flag logged (unconfirmed), a minimum below `warning_threshold`, or a score below 30; MODERATE below 60; LOW below 80; otherwise MINIMAL.

**Status:** `no_match` when no battery voltage entry exists, or no voltage sample falls in the scope.

**Example Response (abridged):**
```json
{
  "success": true,
  "status": "ok",
  "scope": {"scope": "enabled", "windows": [[40.207, 359.162], "..."], "total_sec": 1311.36},
  "inputs": {"entries": {"voltage": "/SystemStats/BatteryVoltage", "total_current": "/SystemStats/BatteryCurrent",
    "rio_brownout_flag": "/SystemStats/BrownedOut"}},
  "health_score": 38,
  "health_score_basis": "heuristic: 100, minus 20 per brownout, ...",
  "risk_level": "CRITICAL",
  "risk_level_basis": "CRITICAL when the roboRIO's logged brownout flag was set in scope; HIGH for a threshold crossing without a flag, ...",
  "voltage_stats": {"min_volts": 6.618, "min_time_sec": 655.45, "max_volts": 12.61, "avg_volts": 11.72, "voltage_sag": 5.98, "samples": 42110},
  "brownout_threshold": 6.75,
  "brownout_threshold_basis": "logged",
  "brownout_events": 2,
  "brownout_basis": "rio_flag: intervals where /SystemStats/BrownedOut was true (the roboRIO disabled outputs)",
  "rio_brownouts": {"flag_entry": "/SystemStats/BrownedOut", "count": 2, "total_sec": 0.181, "events": ["..."]},
  "threshold_crossings": 2,
  "warning_events": 5,
  "load_line": {"current_entry": "/SystemStats/BatteryCurrent", "resistance_ohm": 0.021, "open_circuit_voltage": 12.4, "r_squared": 0.71, "samples": 40211, "current_range_a": 260},
  "observations": [
    "2 roboRIO brownout(s) (655.43 s for 0.143 s; 708.44 s for 0.038 s). Candidate causes: high current draw at those moments (check power_analysis channel peaks in the same windows), a weak or undercharged battery, or high-resistance connections. One log cannot distinguish them: compare this battery across logs and inspect connectors.",
    "Minimum voltage 6.62 V at 655.45 s, below the 9.0 V warning threshold.",
    "Load line: voltage falls 21.0 mV per amp of total current ..."
  ]
}
```

### `analyze_loop_timing`
How often robot code exceeded the loop period, and the distribution of loop times.

**Parameters:**
- `path` (required): Path to the log file
- `entry` (optional): The loop time entry (default: discovered, below)
- `threshold_ms` (optional): Loop time threshold for violations in milliseconds (default: 20 ms, the standard 50 Hz period)
- `unit` (optional): `ms`, `s`, `us`, or `auto`. By default the unit comes from the entry name (ending in `MS`, `Ms`, `_ms`, or `Millis`; `US`, `Us`, `_us`, or `Micros`; `Sec`, `Seconds`, or `_s`), else from the median: 0.001 to 1 looks like seconds, above 500 like microseconds
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `scope` (optional): `all` (default), `enabled`, `disabled`, `auto`, `teleop`, `test`, or `segment:<i>`; combined with `start_time`/`end_time`
- `start_time`, `end_time` (optional): Clip the scope to a time range (seconds)

**Entry discovery, in order:** the `entry` argument; AdvantageKit's `LoggedRobot/FullCycleMS` (the whole cycle, including logging), reported with `LoggedRobot/UserCodeMS` alongside as `user_code`; loop periods derived from consecutive AdvantageKit `/Timestamp` values; `UserCodeMS` alone. Other entries named like a loop time (`looptime`, `cycletime`) are not guessed at: `no_match` lists them in `candidates` to confirm and pass as `entry`. A first sample more than 10× the median (the slow boot cycle, often several seconds) is excluded and reported as `excluded_boot_cycle`.

**Returns:** `loop_time_entry`, `unit` (`value` and `basis`), `scope`, `threshold_ms`, `violation_count` (true total), `total_samples`, `violation_rate`, `percent_over_threshold`, `health_score` (100 minus the percent over threshold, a heuristic kept by design) with `health_score_basis`, `statistics` (`avg_ms`, `median_ms`, `p90_ms`, `p95_ms`, `p99_ms`, `max_ms` with `max_time_sec`, `min_ms`), `violations[]` (first 50, each `timestamp`, `loop_time_ms`, `overage_ms`, with `limits.violations`), `user_code` (median, p95, percent over threshold), and `excluded_boot_cycle`. `data_quality` and `server_analysis_directives` are of the loop-time entry over the scope.

**Status:** `no_match` when the log has no loop timing; `overrun_messages` then counts WPILib's "loop overrun" console messages, which `search_strings` lists.

**Example Response** (the robustness review's log, `scope: "enabled"`, `threshold_ms: 25`):
```json
{
  "success": true,
  "status": "ok",
  "loop_time_entry": "/RealOutputs/LoggedRobot/FullCycleMS",
  "unit": {"value": "ms", "basis": "name (FullCycleMS)"},
  "scope": {"scope": "enabled", "windows": [[40.207, 359.162], "..."], "total_sec": 1311.36},
  "threshold_ms": 25.0,
  "violation_count": 8986,
  "total_samples": 48596,
  "violation_rate": 0.1849,
  "percent_over_threshold": 18.49,
  "health_score": 81,
  "statistics": {"avg_ms": 23.4, "median_ms": 17.60, "p90_ms": 38.62, "p95_ms": 53.11, "p99_ms": 91.92, "max_ms": 412.0, "max_time_sec": 406.2, "min_ms": 9.8},
  "violations": [{"timestamp": 40.3, "loop_time_ms": 31.2, "overage_ms": 6.2}, "..."],
  "limits": {"violations": {"total": 8986, "returned": 50, "limit": 50}},
  "user_code": {"entry": "/RealOutputs/LoggedRobot/UserCodeMS", "basis": "robot code only; ...", "median_ms": 14.1, "p95_ms": 45.0, "percent_over_threshold": 12.0}
}
```

**Common causes of loop overruns:**
- Vision processing on the roboRIO
- Excessive logging or NetworkTables writes
- Blocking I2C/SPI sensor reads
- Slow algorithms in periodic code (O(n²) loops)
- Garbage collection pauses (check JVM memory)

### `get_game_info`
Year-specific FRC game information: match timing, scoring values and ranking-point thresholds by event tier, field geometry, game pieces, robot constraints, and analysis hints, as context for reading a log.

**Parameters:**
- `season` (optional): FRC season year (e.g., 2026). Defaults to the current year. A season with no game data is an error that lists `available_seasons`

**Bundled game data:** 2024 CRESCENDO, 2025 REEFSCAPE, and 2026 REBUILT, each transcribed from the final revision of that season's game manual (Team Updates 21, 21, and 22). Ranking-point thresholds are given per event tier (`regional_threshold`, `district_championship_threshold`, `championship_threshold`, plus `*_with_coopertition` where the Coopertition Bonus lowers them), because FIRST raises them for championship events during the season.

**Provenance:** the result is a knowledge base, not a measurement, and says so:
- `source`: `bundled knowledge base, not the log; verify against the current manual`, or `user-provided game file <path>, not the log; ...` for a file loaded through `GameKnowledgeBase.loadFromFile()`
- `manual_version`: the manual revision the file was transcribed from (`unknown` when a user file does not record one)
- `manual_url`: that manual
- `basis`: says that `match_timing`, `scoring`, `field_geometry`, `game_pieces`, and `robot_constraints` all come from it

Quote values as "per the bundled game data (manual_version ...)" and check the current manual before relying on a threshold. `get_match_phases` labels its `expected_timing.source` as `game_data` when it uses these numbers.

**Returns:** `season`, `game_name`, `source`, `manual_version`, `manual_url`, `basis`, `match_timing` (auto, teleop, and endgame durations, the auto-to-teleop delay, and the shift breakdown in 2026), `scoring`, `field_geometry`, `robot_constraints`, `game_pieces`, `analysis_hints`, `typical_mechanisms`, and, for 2026, `hub_mechanics`.

**Example Response:**
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
    "teleop_duration_sec": 140,
    "total_duration_sec": 160,
    "endgame_duration_sec": 30,
    "shifts": { "auto": {"start_sec": 0, "end_sec": 20, "duration_sec": 20}, "..." : "..." }
  },
  "scoring": {
    "match_points": { "auto": {"fuel_active_hub": 1, "tower_level_1": 15}, "..." : "..." },
    "ranking_points": { "win": 3, "tie": 1, "energized_rp": {"regional_threshold": 100, "district_championship_threshold": 240, "championship_threshold": 360}, "..." : "..." }
  },
  "field_geometry": { "field_length_m": 16.54, "field_width_m": 8.07, "..." : "..." },
  "robot_constraints": { "max_starting_perimeter_in": 110.0, "max_starting_height_in": 30.0, "max_weight_lbs": 115.0, "max_weight_with_bumpers_lbs": 135.0, "..." : "..." },
  "analysis_hints": {
    "endgame_activity": "Tower climbing attempts typically occur in the final 30 seconds (END GAME). Level 1 available in AUTO (max 2 robots).",
    "fuel_context": "Each FUEL scored in active HUB = 1 point (auto and teleop). High volume scoring is key: 100 FUEL for ENERGIZED RP and 360 for SUPERCHARGED RP at Regional/District events ...",
    "...": "..."
  }
}
```

**Custom game data:** a JSON file in the bundled format can be loaded with the Java API `GameKnowledgeBase.loadFromFile()`; its `source` (the manual's URL) and `manual_version` fields are reported as `manual_url` and `manual_version`. The server itself has no option that loads one, so this is for code that embeds it.

---

## Export Tools

### `export_csv`
Export an entry to CSV for external analysis (Python, Excel, MATLAB), or return its rows inline. Use it when no tool can compute what you need: export, compute, and cite the export. Returns `no_match`, and writes nothing, when the window holds no samples.

**Parameters:**
- `path` (required): Path to the log file
- `name` (required): Entry to export
- `output_path` (optional): File name or path **inside the export directory**. A bare name (`pose.csv`) or relative path (`run1/pose.csv`) is resolved inside it, and subdirectories are created; an absolute path must already lie inside it. Default: a name generated from the log and entry (`<log>__<entry>.csv`)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time`, `end_time` (optional): Time window
- `inline` (optional): Return the rows in the response instead of writing a file (default false), for agents that cannot read the export directory
- `max_rows` (optional): Rows returned inline (default 500; 1 to 5000, anything else is an error)

**Export directory:** `{java.io.tmpdir}/wpilog-export` by default, or `-exportdir`, the `WPILOG_EXPORT_DIR` environment variable, or `exportdir` in the server config. Every file result names it (`export_directory`), and so does the error for a refused path. Symlinks cannot escape it.

**Columns:** every value is flattened. A scalar is one `value` column. A struct is one column per field, sorted by name, with nested fields as dot paths (`translation.x`), array fields as `field[i]`, and an enum field as two columns, `field` (the number) and `field.label`. A struct array or primitive array is one row per element with an `index` column. Header and rows always align.

**Returns:** `entry`, `type`, `columns`, `rows_exported`, and either `output_path` (absolute) and `export_directory`, or (inline) `rows` with `limits.rows` (total vs returned; `rows_exported` is then the rows returned).

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "entry": "/RealOutputs/Drive/Pose",
  "output_path": "/private/var/folders/.../T/wpilog-export/pose.csv",
  "export_directory": "/private/var/folders/.../T/wpilog-export",
  "rows_exported": 59138,
  "type": "struct:Pose2d",
  "columns": ["timestamp_sec", "rotation._derived.degrees", "rotation.value", "translation.x", "translation.y"]
}
```

### `generate_report`
Generate a one-call summary of a log. Each section uses the same entry choice and rules as the tool that covers it in depth, and names its source entries.

**Report sections** (after `log_path` and `log_filename`):
- `basic_info`: `duration_sec`, `start_timestamp`, `end_timestamp`, `entry_count`, `truncated` (with `truncation_message` when it is)
- `timeline`: enabled segments, enabled time, FMS matches, season, and the enabled entry used (`source`), as `get_match_phases` derives them
- `battery`: the voltage entry `power_analysis` would choose, over enabled time when the log records it (`scope`). It carries the same fields as `power_analysis`'s `voltage_analysis` (min with its time, max, average, samples below the threshold, crossings, seconds below), the brownout threshold with its basis, `rio_brownouts` in scope when the roboRIO flag is logged, and `brownout_risk` with its basis by the same rule
- `peak_currents`: the three largest current peaks (`entry`, signed `peak_current_A`, `peak_current_time_sec`) in the same scope: the top of `power_analysis`'s `channel_analysis`, each channel of an array separately. When there are none, `skipped` says whether the log has no amperage entries or has them without finite samples in the scope
- `errors`: `total_errors` and `total_warnings` (samples classified by the same line rule as `get_ds_timeline` and `search_strings`: a multi-line console batch counts once, by its most severe line, and "default" is not a fault), `distinct_error_messages`, `top_messages` (the five most frequent, numbers normalized), `samples` (the first five error lines with time and entry), and a `note`
- `code_info`: `git_sha`, `git_branch`, `git_dirty`, `git_date`, `build_date`, `project_name`, `version` (whichever the log has; the entries `get_code_metadata` reads)
- `top_data_types`: the ten most common data types with their entry counts (ties by name), and `type_count`, the number of types
- `data_quality` and `server_analysis_directives`: of the battery voltage entry, when there is one

Sections that cannot be produced are listed in `skipped` (status `partial`); an empty log is `no_match`.

**Parameters:**
- `path` (required): Path to the log file
- `voltage_entry` (optional): Battery voltage entry (default: `BatteryVoltage`, or `Voltage` under `PowerDistribution`, `PDH`, `PDP`, or `Battery`; see [The server does not guess](#the-server-does-not-guess))

**Example Response (abridged):**
```json
{
  "success": true,
  "status": "ok",
  "log_filename": "akit_26-09-30_00-10-26.wpilog",
  "basic_info": {"duration_sec": 1579.91, "entry_count": 371, "truncated": true, "...": "..."},
  "timeline": {"enabled_segments": 4, "enabled_time_sec": 1311.36, "matches": 0, "season": 2026, "source": "/DriverStation/Enabled"},
  "battery": {
    "entry": "/SystemStats/BatteryVoltage",
    "scope": {"scope": "enabled", "...": "..."},
    "samples": 51234, "min_voltage": 6.618, "min_voltage_time_sec": 655.45, "max_voltage": 12.9, "avg_voltage": 11.84,
    "samples_below_threshold": 7, "threshold_crossings": 2, "seconds_below_threshold": 0.18,
    "brownout_threshold": 6.75, "brownout_threshold_basis": "logged", "brownout_threshold_entry": "/SystemStats/BrownoutVoltage",
    "rio_brownouts": {"flag_entry": "/SystemStats/BrownedOut", "count": 2, "total_sec": 0.181, "events": ["..."]},
    "brownout_risk": "HIGH", "brownout_risk_basis": "2 roboRIO brownout(s) in scope (/SystemStats/BrownedOut true: outputs were disabled)"
  },
  "peak_currents": [{"entry": "/SystemStats/BatteryCurrent", "peak_current_A": 262.0, "peak_current_time_sec": 655.44}, "..."],
  "errors": {
    "total_errors": 41, "total_warnings": 2598, "distinct_error_messages": 7,
    "top_messages": [{"message": "Error at frc.robot... line #", "example": "...", "count": 20, "first_timestamp": 101.2}],
    "samples": [{"timestamp_sec": 8.36, "entry": "/RealOutputs/Console", "line": "..."}]
  },
  "code_info": {"git_sha": "a1b2c3d4e5f6", "git_branch": "main", "git_dirty": "All changes committed"},
  "top_data_types": {"double": 87, "boolean": 68, "int64": 65, "string": 40}
}
```

---

## TBA Tools

These tools need an API key for The Blue Alliance (the hint in each result says how to set one).

### TBA enrichment

When a key is configured, `list_available_logs` adds a `tba` field to each listed log it can match: the team number, alliance (red/blue), alliance score and opponent score, win/loss, the actual and scheduled match times, and which TBA match was used (`match_key`, `lookup_method`). See [list_available_logs](#list_available_logs) for the fields.

A log is enriched only when it has an event code, a match number, a team number (from the log, or the configured default team), and a match type of Qualification, Quarterfinal, Semifinal, Final, or Elimination. Practice matches, simulations, and replays are not enriched.

### `get_tba_status`
Whether The Blue Alliance API is configured and the key works, with cache statistics.

**Parameters:** None

**Returns:**
- `available`: Whether TBA can be used now: a key is configured and The Blue Alliance accepted it (its `/status` endpoint is asked on every call)
- `configuration`: `configured` or `not_configured`
- `key_check` (when configured): `valid`, `detail` (accepted; rejected with HTTP 401; could not be reached), and TBA's `current_season`, `max_season`, `datafeed_down`
- `cache` (when configured): counts of cached `events`, `matches`, and `eventMatches`
- `hint`: what TBA adds to `list_available_logs`, or how to set or change the key

**Example Response (Configured):**
```json
{
  "success": true,
  "status": "ok",
  "available": true,
  "configuration": "configured",
  "key_check": {"valid": true, "detail": "accepted by The Blue Alliance", "current_season": 2026, "max_season": 2026, "datafeed_down": false},
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
  "hint": "In VS Code, run 'WPILog Analyzer: Set The Blue Alliance API Key'; for the standalone server, set tba_key in ~/.wpilog-mcp/servers.yaml (or pass -tba-key, or set TBA_API_KEY). Get a free API key at https://www.thebluealliance.com/account"
}
```

### `get_tba_match_data`
Query match scores and detailed results directly from The Blue Alliance. **Use this tool to answer questions about match outcomes**; the telemetry does not record the score.

**When to use it:**
- "What was our score?"
- "Did we win?"
- "How many autonomous points did we score?"
- "What were the match results?"

**Parameters:**
- `year` (required): Competition year (e.g., 2024, 2025, 2026)
- `event_code` (required): TBA event code (e.g., "caph" for Poway, "cmptx" for Houston Championship), in upper or lower case. It is TBA's code, not necessarily the abbreviation in a log's file name; thebluealliance.com/events/{year} lists them
- `match_type` (required): `Qualification`, `Quarterfinal`, `Semifinal`, `Final`, or `Elimination` (the names `list_available_logs` reports), or TBA's codes `qm` (or `q`), `qf`, `sf`, `f`. "Elimination" N, as the Driver Station names playoff matches, is read as double-elimination bracket match N (TBA's `sfNm1`) for 2023 and later. The finals carry no bracket number, so query them as `f` with the finals match number. Before 2023, with `team_number`, Elimination N is read as playoff match number N in the order the team played (a heuristic, `lookup_method: play_order`)
- `match_number` (required): Match number within the type (1-indexed)
- `team_number` (optional): Your team number, to mark your alliance

**Returns:**
- `match_found`: Whether the match was found in TBA
- `match_key`, `comp_level`, `match_number`, and `lookup_method` (`direct` or `double_elimination_bracket`, with a `lookup_basis` sentence for the bracket reading)
- `match_time` (the actual time) or `scheduled_time`, formatted in the server's time zone
- `winning_alliance`: `red`, `blue`, or `tie_or_not_played`
- `alliances`: Score and team list for each alliance (team numbers; a B team such as `frc1234B` as the string `"1234B"`), with `your_alliance` and `won` on the team's alliance when `team_number` is given
- `score_breakdown`: every points subtotal of each alliance's breakdown, meaning the numeric fields TBA names `...Points` in every season (`autoPoints`, `teleopPoints`, `foulPoints`, `totalPoints`, and the game's own), when TBA has one

A pre-2023 Elimination N read in play order returns a shorter result: `match_found`, `lookup_method: play_order`, `match_key`, `your_alliance` (`color`, `score`, `won`, `opponent_score`), `match_time`, and a `note` on how to query the match itself.

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
  "status": "ok",
  "match_found": true,
  "lookup_method": "direct",
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

**Errors:**
- TBA not configured: an error saying how to set the key (the VS Code command, or `tba_key` in the standalone server's `servers.yaml`)
- Match or event not found: `status: no_match` with `match_found: false` and a `reason`. For an unknown event, `similar_events` (or a `hint` to search TBA); for an event without that match, `suggestions`
- TBA cannot answer (a rejected API key, a server error, no network): `status: error` saying so, never reported as a missing match

**Game-specific scoring:** each season's breakdown names its own point subtotals (2024: `autoAmpNotePoints`, ...; 2025: `autoCoralPoints`, `bargePoints`, ...). All of them are passed through, so a new season needs no update.

---

## RevLog Tools

REV log (`.revlog`) files hold CAN status frames from SPARK MAX and SPARK Flex motor controllers. They are usually recorded on the roboRIO by REVLib in the robot program, and can also be captured by the REV Hardware Client on a connected laptop. These tools read that data on the wpilog's clock. Synchronization runs in the background after a wpilog is first loaded; `wait_for_sync` waits for it.

REV logs are found by recording time in the configured log directory that holds the wpilog and in the wpilog's own folder, each down to the scan depth (5 levels by default). Other configured directories are not searched, so another team's REV logs from the same event are never matched to yours. Sync results are cached on disk, so loading the same wpilog and REV logs again skips both parsing and correlation. When no REV log was found, every revlog tool returns `status: not_applicable` with the same `reason`. While synchronization is still running, the reason says so and the `hint` points to `wait_for_sync`.

### How timestamp synchronization works

`.wpilog` files timestamp data on the **roboRIO's FPGA clock**, while `.revlog` files timestamp each CAN frame in milliseconds on the clock of whatever recorded them: robot code (REVLib's status logger, 2026 and later) on the roboRIO, or a laptop running the REV Hardware Client. In the robot-code REV logs tested so far, the two clocks agreed within about 20 ms, but the server does not assume it: it estimates the offset and then measures it.

Synchronization has two phases:

#### Phase 1: coarse alignment (seconds)

The wpilog's wall-clock entry (WPILib's `systemTime` or AdvantageKit's `/SystemStats/EpochTimeMicros`) maps FPGA timestamps to UTC. The revlog filename encodes its start time (e.g., `REV_20260320_143052.revlog`) in the zone of the clock that named it: the roboRIO names files in UTC unless a team changes its zone, and a desktop running simulation names them in its local zone. The server reads REV log names in the zone that the wpilog's own file name reveals when compared with its wall-clock entry (the same clock is taken to have named both), or in UTC when the wpilog's name carries no time. `sync_status` reports this as `revlog_filename_zone`. The same zone decides which REV logs belong to a wpilog.

Only readings taken after the clock was set count. Until the Driver Station sets it, a roboRIO's clock reads 1970 or a fixed default date (2024-12-18 in real logs) that every boot shares. So the server uses the readings after the clock was set, and matches REV logs to a wpilog by time only when the wpilog's clock is known to have been set: either the log records the clock being set, or the time in the log's file name agrees with the clock (AdvantageKit and DataLogManager name a log with its time once the clock is set). A log whose clock reads one date throughout under a placeholder name such as `akit_cfb6568c35d66529.wpilog` gets no REV logs, and the revlog tools say why. REV logs are candidates when their name's time falls within 5 minutes of the log (30 minutes when only file modification times are available). A candidate that, once synchronized, neither correlates with the log nor overlaps it (recorded in the session before or after) is not attached. The estimate is only as good as the roboRIO's clock when the file was named: in a real 2026 log the REV log's name was 15 s earlier than its first frame. Without a wall-clock entry there is no estimate, and the search is centered on an offset of 0.

#### Phase 2: fine alignment by cross-correlation (milliseconds)

Both logs record overlapping physical quantities. For example, the robot code logs a motor's applied output to the wpilog, and the SPARK independently records its applied output in the revlog: the same signal seen through two clocks.

The algorithm:
1. Candidates, then data. Names only nominate pairs: a numeric wpilog entry whose *leaf* name fits the REV signal's kind (`/Turret/AppliedVolts` for `AppliedOutput`, `.../VelocityRadPerSec` for `Velocity`, `.../CurrentAmps` for `OutputCurrent`, `BatteryVoltage` for `BusVoltage`). Positions (running totals that correlate with any trend) and temperatures (too slow to carry timing) nominate none. Every candidate is then ranked by its best correlation at 10 Hz over the whole search window, and the best five are cross-correlated at full resolution, so the data, not the names, choose which pairs are used.
2. Resampling. Both signals are resampled to a uniform 100 Hz rate by linear interpolation. A long recording is trimmed to its highest-variance window, which matters when a log starts with minutes of the robot disabled.
3. Cross-correlation (at least 10 s of overlapping data; a peak in a few seconds is not evidence). For each candidate pair, the [Pearson correlation coefficient](https://en.wikipedia.org/wiki/Pearson_correlation_coefficient) is computed at every integer sample lag within ±60 s of the coarse estimate. Pearson correlation ignores scale and DC offset, so duty cycle can be compared against voltage or velocity.
4. Sub-sample refinement. Parabolic interpolation on the correlation peak refines the offset below one 10 ms sample.
5. Consensus. Among the strong pairs (correlation > 0.7, else > 0.5), of the groups of pairs whose offsets agree within 50 ms, the one with the most correlation behind it gives the estimate (its median). Pairs that correlate at a contradictory offset are set aside and named in the explanation, not averaged in. Confidence is scored from average correlation (0–0.4), the number of agreeing pairs (0–0.3), and their offset standard deviation (0–0.3), and the level never claims more agreement than the pairs show (below).

#### Clock drift compensation (recordings over 15 minutes)

Over a long recording the two clocks may drift apart, typically 10–50 ms per hour, even when both run on the same roboRIO. The synchronizer splits the signal into halves, computes an offset for each, and fits a linear drift rate (nanoseconds per second). An estimate above 1,000,000 ns/s (1000 ppm) is rejected as implausible, and one below 1 ns/s is ignored. When drift is detected, all timestamp conversions apply a correction:

```
fpga_time = revlog_time + offset + (revlog_time − reference_time) × drift_rate
```

`sync_status` reports the drift rate when one is detected.

### Confidence levels

| Confidence level | Estimated accuracy | How it is determined |
|-----------------|-------------------|---------------------|
| **HIGH** | 1–5 ms | Two or more pairs whose offsets have a standard deviation of 5 ms or less, with strong correlation |
| **MEDIUM** | 5–50 ms | Pairs agree within 50 ms, or a single strong pair (nothing to check it against) |
| **LOW** | 50 ms to seconds | Weak correlation, pairs spread more than 50 ms, or the filename-time estimate alone (which can be off by seconds) |
| **FAILED** | Unknown | No pair correlated and no wall-clock estimate exists; set a known offset with `set_revlog_offset` |

The explanation gives the used pairs' offset range and standard deviation.

Check `sync_confidence` before using REV log data for precise timing; `set_revlog_offset` replaces a poor automatic result.

### Limitations

- Correlation needs a signal that varies in both logs at the same time. Flat or disabled-only data lowers the confidence; so does a short log or steady running.
- A REV log named by another clock (the REV Hardware Client names files in the laptop's local time) may be missed or misaligned. `set_revlog_offset` corrects the offset of one that was found.

### Binary parsing robustness

The revlog parser guards against corrupt or truncated files:
- It stops after 10 million records, so a corrupt file cannot exhaust memory
- A corrupt record is skipped without aborting the parse
- Records with negative timestamps are discarded
- CAN frames shorter than 8 bytes are skipped

### REV signals and device keys

**Available Signals** (SPARK firmware 25 and later, whose status frames REVLib 2026 records; layouts from REV's published SPARK frame specification, spark-frames 2.1.0). A signal appears when its frame was logged; REVLib enables the frames a program reads.
- Status 0 (10 ms): `AppliedOutput` (duty cycle, −1 to 1), `BusVoltage` (V), `OutputCurrent` (A), `MotorTemperature` (°C), `HardForwardLimitReached`, `HardReverseLimitReached`, `SoftForwardLimitReached`, `SoftReverseLimitReached`, `IsInverted`, `PrimaryHeartbeatLock` (0/1)
- Status 1 (250 ms), each 0/1: faults `OtherFault`, `MotorTypeFault`, `SensorFault`, `CanFault`, `TemperatureFault`, `DrvFault`, `EscEepromFault`, `FirmwareFault`; warnings `BrownoutWarning`, `OvercurrentWarning`, `EscEepromWarning`, `ExtEepromWarning`, `SensorWarning`, `StallWarning`, `HasResetWarning`, `OtherWarning`; the sticky version of each (`OtherStickyFault`, ..., `BrownoutStickyWarning`, ...); `IsFollower`
- Status 2: `Velocity`, `Position` (primary encoder; RPM and rotations unless a conversion factor is configured, which the unit says)
- Status 3: `AnalogVoltage` (V), `AnalogVelocity`, `AnalogPosition`; status 4: `ExternalEncoderVelocity`, `ExternalEncoderPosition` (the alternate encoder on a SPARK MAX); status 5: `DutyCycleEncoderVelocity`, `DutyCycleEncoderPosition`; status 6: `UnadjustedDutyCycle` (0–1), `DutyCyclePeriod` (µs), `DutyCycleNoSignal`; status 7: `IAccum`; status 8: `Setpoint`, `IsAtSetpoint`, `SelectedPidSlot`; status 9: `MaxMotionPositionSetpoint`, `MaxMotionVelocitySetpoint`

Device keys are `SparkMax_<CAN id>` or `SparkFlex_<CAN id>` by the model the SPARK's status 0 frames report (the `SparkModel` signal: 1 = Flex, 2 = MAX, the codes of REVLib's `SparkModel`), or `Spark_<CAN id>` when no frame carried the field (device type 2 in the CAN ID covers both models). A REV log named `REV_YYYYMMDD_HHMMSS_<bus>.revlog` is reported under that bus name; one without a suffix is `rio` (then `can1`, `can2`, ...). Firmware 25+ also sends the legacy status 0 frame once a second for old followers, with zero output and every fault set; it carries no data and is not decoded.

A custom DBC file replaces the built-in signal definitions: `rev_spark.dbc` in `~/Library/Application Support/wpilog-mcp/` (macOS), `%APPDATA%\wpilog-mcp\` (Windows), or `~/.config/wpilog-mcp/` (Linux), or a file named by the `WPILOG_REV_DBC` environment variable. Keep the built-in signal names so synchronization still finds its candidate pairs.

### Troubleshooting low confidence

1. Make sure the wpilog and the revlog were recorded over the same period
2. Check that matching signals exist in both (e.g., motor outputs logged in the wpilog)
3. If sync fails, check that the motor controllers were connected and reporting data
4. If you know the offset, set it with `set_revlog_offset`

### Example workflow

```
1. sync_status(path="/logs/match.wpilog")    # Check sync confidence (loads the log)
2. list_revlog_signals(path="/logs/match.wpilog")  # See available signals
3. get_revlog_data(path="/logs/match.wpilog", signal_key="REV/SparkMax_1/AppliedOutput", start_time=15.0, end_time=30.0)
4. compare with read_entry(path="/logs/match.wpilog", name="/drive/frontLeft/output", start_time=15.0, end_time=30.0)
```

### `list_revlog_signals`
List the signals in the wpilog's REV logs, with device, unit, sample count, and synchronization status. Returns `not_applicable`, listing the buses for `set_revlog_offset`, when no REV log could be synchronized, and `no_match` when the filters match no signal.

**Parameters:**
- `path` (required): Path to the log file
- `device_filter` (optional): Only devices whose key contains this (case-insensitive, e.g., "SparkMax_1")
- `signal_filter` (optional): Only signals whose name contains this (case-insensitive, e.g., "velocity")

**Returns:** `signal_count`, `revlog_count`, `overall_sync_confidence`, and `signals`, each with `key` (what `get_revlog_data` takes: `REV/<device>/<signal>`, or `REV/<bus>/<device>/<signal>` when the wpilog has several REV logs), `device`, `signal`, `unit`, `sample_count`, `can_bus`, `sync_method` (`CROSS_CORRELATION`, `SYSTEM_TIME_ONLY`, `USER_PROVIDED`, or `FAILED`), `timestamps_aligned`, `offset_seconds` (when aligned), and `sync_confidence`. A warning says how a bus was aligned when that bounds its accuracy. `_metadata.timing_accuracy_ms` is the overall accuracy range, or `unknown` when any bus has a user offset or failed.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "signal_count": 12,
  "revlog_count": 1,
  "overall_sync_confidence": "high",
  "signals": [
    {
      "key": "REV/SparkMax_1/AppliedOutput",
      "device": "SparkMax_1",
      "signal": "AppliedOutput",
      "unit": "duty_cycle",
      "sample_count": 7500,
      "can_bus": "rio",
      "sync_confidence": "high"
    },
    {
      "key": "REV/SparkMax_1/Velocity",
      "device": "SparkMax_1",
      "signal": "Velocity",
      "unit": "rpm unless converted",
      "sample_count": 7500,
      "can_bus": "rio",
      "sync_confidence": "high"
    },
    {
      "key": "REV/SparkMax_5/OutputCurrent",
      "device": "SparkMax_5",
      "signal": "OutputCurrent",
      "unit": "A",
      "sample_count": 5000,
      "can_bus": "rio",
      "sync_confidence": "high"
    }
  ],
  "_metadata": {
    "timing_accuracy_ms": "1-5"
  }
}
```

### `get_revlog_data`
Read a REV log signal with its timestamps converted to FPGA time, like `read_entry` for REV motor controller data. A signal whose REV log could not be synchronized returns `not_applicable` until `set_revlog_offset` provides an offset, because its timestamps are on the REV log's own clock. An unknown key is an error.

**When to use it:**
- Compare a motor's commanded output (wpilog) with its applied output (revlog)
- Look at motor velocity and position response
- Check PID tuning against the motor controller's own data
- Debug motor controller communication issues

**Parameters:**
- `path` (required): Path to the log file
- `signal_key` (required): Signal key from `list_revlog_signals` (e.g., `REV/SparkMax_1/AppliedOutput`, or `REV/rio/SparkMax_1/Velocity` when the wpilog has several REV logs)
- `last_seconds` (optional): Last N positive seconds; current robot time for an open capture, log end for a closed file. Replaces start/end bounds; `inputs.window` records the resolved bounds.
- `start_time` (optional): Start timestamp in seconds (FPGA time)
- `end_time` (optional): End timestamp in seconds (FPGA time)
- `limit` (optional): Maximum samples to return (default: 1000)
- `include_stats` (optional): Include basic statistics (min, max, mean)

**Returns:** `signal_key`, `can_bus`, `sample_count` (returned), `total_samples` (in range), `data` (each `timestamp` and `value`, with `limits.data`), and for the signal's own REV log `sync_method`, `timestamps_aligned`, `offset_seconds`, `sync_confidence`, and `_metadata.timing_accuracy_ms` (`unknown` for a user-provided offset). A warning names the alignment method when it bounds the accuracy. With `include_stats`, `statistics` (`min`, `max`, `mean`, `count`) covers every sample in range, with `data_quality` and `server_analysis_directives`.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "signal_key": "REV/SparkMax_1/Velocity",
  "can_bus": "rio",
  "sample_count": 100,
  "total_samples": 7500,
  "sync_method": "CROSS_CORRELATION",
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
    "count": 7500
  },
  "_metadata": {
    "timing_accuracy_ms": "1-5"
  }
}
```

### `sync_status`
Synchronization status for each REV log of the wpilog: confidence, offset, drift, and optionally the signal pairs used.

**Parameters:**
- `path` (required): Path to the log file
- `include_signal_pairs` (optional): Include the signal pairs used for correlation

**Returns:** `synchronized` (any REV log synchronized), `revlog_count`, `sync_in_progress`, `overall_confidence` and `overall_confidence_value`, `revlog_filename_zone` (how REV log file names were read), and `revlogs`. Each REV log has `can_bus`, `path`, `device_count`, `signal_count`, and `sync` (`method`, `confidence`, `confidence_level`, `offset_microseconds`, `offset_milliseconds`, `offset_seconds`, `explanation`, `successful`, and the drift fields when drift was detected), plus `signal_pairs` when requested. `_metadata` gives `timing_accuracy_ms` and `confidence_description`. Warnings flag a sync still in progress and medium, low, or failed confidence.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
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
          "revlog_signal": "SparkMax_1/AppliedOutput",
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

**Sync methods:**
- `CROSS_CORRELATION`: aligned by cross-correlating signal pairs (best accuracy)
- `SYSTEM_TIME_ONLY`: the wall-clock estimate alone, when no pair correlated (can be off by seconds)
- `USER_PROVIDED`: an offset set with `set_revlog_offset`
- `FAILED`: no synchronization could be established

### `set_revlog_offset`
Set the synchronization offset for one REV log by hand, replacing the automatic result. Use it when automatic sync fails or is wrong, or when you know the offset another way (e.g., by aligning a distinctive event in both logs). `offset_ms` is required: omitting it is an error and leaves the synchronization unchanged.

**When to use it:**
- Automatic synchronization reports LOW or FAILED confidence
- You know the offset from a distinctive event visible in both logs (e.g., a motor stall, a sudden stop)
- The automatic offset leaves corresponding wpilog and revlog signals visibly misaligned
- The recording started with the robot disabled for a long time and correlation was poor

**Parameters:**
- `path` (required): Path to the log file
- `offset_ms` (required): Milliseconds to add to revlog timestamps to convert them to FPGA time. Example: if a revlog event appears 500 ms after the same event in the wpilog, set `offset_ms` to -500
- `can_bus` (optional): The bus whose REV log gets the offset (e.g., "rio"). If omitted, the first REV log. An unknown bus is an error that lists the buses

**Returns:** `can_bus`, `offset_ms`, `offset_us`, `previous_offset_ms`, `previous_method`, and `new_method` (`USER_PROVIDED`).

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "can_bus": "rio",
  "offset_ms": -523.5,
  "offset_us": -523500,
  "previous_offset_ms": -480.2,
  "previous_method": "CROSS_CORRELATION",
  "new_method": "USER_PROVIDED"
}
```

### `wait_for_sync`
Wait for background REV log synchronization to finish. Synchronization runs in the background after a log is first loaded, so revlog data may not be available at once. Returns immediately if sync is already done, and `not_applicable` when the wpilog has no REV logs.

**When to use it:**
- After loading a log, when you need revlog signals right away
- When `sync_status` shows `sync_in_progress: true`, or a revlog tool's reason says synchronization is still running

**Parameters:**
- `path` (required): Path to the log file
- `timeout_ms` (optional): Maximum time to wait in milliseconds (default: 30000, capped at 120000)

**Returns:** `completed`, `was_in_progress`, `revlog_count`, and `synchronized` (any REV log synchronized). A warning says when the wait timed out.

**Example Response:**
```json
{
  "success": true,
  "status": "ok",
  "completed": true,
  "was_in_progress": true,
  "revlog_count": 2,
  "synchronized": true
}
```

---

## Sampled metrics beside the tools

`GET /metrics` is an HTTP view, not an MCP tool. It serves current numeric NT4 values,
robot-clock ages, recorder and pull counters, and server JVM measurements in Prometheus
format. Struct fields use the same paths as the analysis tools, but only published schemas;
strings are omitted. The [standalone guide](STANDALONE.md#metrics-and-a-starter-dashboard)
lists the fields, configuration and starter Grafana dashboard. A scrape can miss changes:
the capture is the record, and tools reading it analyze a fixed prefix of that record.

## Data Types

What the server reads from a WPILOG file, and what a value looks like in a result.

### Primitive types

`boolean`, `int64`, `float`, `double`, `string`, `json`, and `raw`, and arrays of `boolean`, `int64`, `float`, `double`, and `string`. A struct schema entry (`structschema`) is returned as its text. An entry of any other type (Protobuf, for example) is returned as its bytes in hex, or as its size when it is longer than 100 bytes.

### Structs

Struct entries (`struct:Name` and `struct:Name[]`) are decoded from the schema each log records for its struct types (`/.schema/struct:Name`, also `NT:/.schema/struct:Name`), so any struct decodes: WPILib geometry and kinematics, vendor structs, and a team's own, including nested structs, fixed-size arrays, enums, and bit-fields. A team that edits a template struct (adding a field to `PoseObservation`, say) gets its own layout decoded, not the template's.

A decoded value is a nested object with the schema's field names, in schema order. Fixed-size array fields are arrays (a `char` array is a string), and an enum field is its number with its label (`label` is null for a number the schema does not name):

| Schema | Decoded value |
|--------|---------------|
| `Pose2d` | `{"translation": {"x", "y"}, "rotation": {"value", "_derived": {"degrees"}}}` |
| `Pose3d` | `{"translation": {"x", "y", "z"}, "rotation": {"q": {"w", "x", "y", "z"}, "_derived": {"roll", "pitch", "yaw", "roll_deg", "pitch_deg", "yaw_deg"}}}` |
| `SwerveModuleState` | `{"speed", "angle": {"value", "_derived": {"degrees"}}}` |
| enum field, e.g. `PoseObservation.type` | `{"value": 2, "label": "PHOTONVISION"}` |

`_derived` values are computed by the server from WPILib's `Rotation2d` and `Rotation3d`, wherever they are nested, and only when the log's schema for them is WPILib's.

The numeric tools read struct fields and array elements by [field path](#field-paths), such as `/RealOutputs/Drive/Pose.translation.x`, `/PowerDistribution/ChannelCurrent[3]`, or `/Vision/Camera0/PoseObservations[0].tagCount`. `get_entry_info` lists an entry's numeric fields.

When a log records no schema for a struct type, WPILib's own schema is used for WPILib types, and a template layout for AdvantageKit vision's `PoseObservation` and `TargetObservation` and Choreo's `SwerveSample`. [`list_struct_types`](#list_struct_types) and `get_entry_info` say which source each type used. A record whose size does not fit its schema is not decoded, and tools that read the entry say how many records failed and why.

---

## Server Instructions

Besides the guidance in each tool's description, the server gives the agent general reasoning guidance in two places:

- **MCP `instructions`**: returned in the `initialize` response. Clients such as Claude Code, VS Code Copilot, and Gemini CLI place it in the model's system prompt (Claude Desktop currently does not). It is a short, ordered checklist, kept under 2 KB because Claude Code truncates longer instructions: answer the question asked first; never name an entry or quote a number that no tool returned, and treat a `no_match` result as missing data, not missing problems; an entry's name does not prove what it measures, so read the robot source code that logs it (which mechanism, which units, measured or commanded) or call the mapping an assumption; never compute statistics by hand (when no tool can read the data, `export_csv` it and compute outside) and call `get_match_phases` before reasoning about time; verify the premise before explaining an event; use three tiers of language (observed event = fact, statistic = inference bounded by `confidence_level`, cause outside the telemetry = hypothesis to check physically); test a user-proposed cause against a rival; scope statistics to the phase and enabled state; one log is one sample; and notes on truncated logs, revlog sync, and TBA-sourced scores.
- **`get_server_guide` → `analysis_principles`**: the long form, returned as a tool result so it reaches the model in every client. Its `tools/list` entry is marked always-loaded (see `get_server_guide`).

Both come from one place in the code. Tests check that every tool name they mention exists and that the instructions stay under the size limit.

## Response Fields

These are not tools. They are fields in tool results that help an agent judge how far to trust a result. [TOOL_RESPONSES.md](TOOL_RESPONSES.md) has full captured responses from every tool.

### Result contract (`success`, `status`, and related fields)

The server normalizes every tool result, however the tool built it, so these fields mean the same in every result:

| Field | Meaning |
|-------|---------|
| `success` | `true` exactly when `status` is `ok` or `partial` |
| `status` | `ok` (full result), `partial` (some sections could not be produced; see `skipped`), `not_applicable` (the tool does not apply to this log, e.g. no autonomous period), `no_match` (the tool found none of the entries it analyzes), or `error` (invalid arguments, missing entry, unreadable file). `success` and `status` come first in every result |
| `reason` | For `not_applicable` and `no_match`: what was missing, in terms of the log's own data |
| `looked_for` | For `no_match`: the naming rules, types, or schemas that were searched |
| `hint` | How to point the tool at the right data (usually a parameter to pass) |
| `error` | For `error`: the message |
| `inputs` | What a log-reading result was computed from. Tools that choose entries by role give `inputs.entries` (by role), `inputs.fields` (field paths, by role), and the time as `inputs.scope` (a named scope or windows) or `inputs.window` (`start_time`/`end_time`). Other tools give `inputs.log` and `inputs.entries_read` (the entries read, up to 10, with `entries_read_total` beyond that). An open capture adds `inputs.session_time_range` (`start_sec`, `end_sec`): the complete fixed prefix of that capture file available to the call, independent of its requested analysis scope. A rolled session has several files; each call and its range describe one file. `compare_matches` instead adds `inputs.session_time_ranges`, keyed by each live log's path. Later calls can see a longer prefix |
| `skipped` | Sections not produced, each `{section, reason}` |
| `limits` | For each list cut short by a limit: `{total, returned, limit}` |
| `warnings` | Anything the caller should know that does not change the status |
| `_metadata.non_finite_fields` | Fields whose value could not be computed (NaN or infinite). They are emitted as `null`, never as a bare `NaN` (which is not valid JSON), and named in a warning |
| `_metadata.log_truncation` | Present on every result computed from a log that was not read to its end: what was not read, and the time range that was. Most robot logs end inside their last record, because the robot is switched off while logging; that alone raises no warning. A log that lost more (garbage or unreadable records at its end, records set aside for their timestamps) gets the same text as a warning too. `compare_matches` gives each log's as `comparisons[].log_truncation` |
| `inputs.file_size_bytes` | On a result from the shared log-reading base for a disk-backed log, the file size of the snapshot admitted to the call. A later call after growth reports the new size; a call crossing growth is discarded. Writer-owned live captures instead describe their fixed prefix with `inputs.session_time_range` |
| `_metadata.log_reloaded` | Present once per session, on the first result from a log that was loaded again because its file changed on disk: `at` and `change` (what changed, such as the size or the modification time), with a warning, since results the session holds from earlier calls came from the file as it was. A result read while the file changed is not returned at all: the call is an error that says what changed, and the next call reads the file as it is now |
| `_metadata.decode_problems` | Entries the tool read whose records could not all be decoded, each `{entry, failed_records, total_records, reason}` (e.g. a struct with no schema, or a record whose size does not fit its schema). Each also gets a warning; the result uses the records that did decode |

`not_applicable` and `no_match` are answers, not server failures. They tell the agent that finding nothing is not evidence that nothing is wrong, and how to find the right data.

### `data_quality`

Computed from the values a result rests on, within its scope (the time between windows is not a gap). These tools always carry it: `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `find_condition` (the condition's entry, or the worst one for compound conditions), `compare_poses`, `pose_corrections`, `analyze_cycles`, `analyze_loop_timing`, `predict_battery_health`, and `moi_regression`, plus `compare_matches` inside each log's comparison.

These carry it when the entry it is computed from exists:
- `align_entries`, with `difference` (the difference series)
- `power_analysis` (the voltage entry, else the first scalar current entry; none for array-only logs)
- `generate_report` (the battery voltage entry)
- `analyze_vision` (the first observation stream, target stream, or checked pose), `profile_mechanism` (the measurement entry, else the velocity entry), and `analyze_swerve` (the measured module states, with samples in scope)
- `analyze_can_bus` (the first bus counter entry, else the first other CAN error entry)
- `get_revlog_data`, with `include_stats`

Tools that report discrete events, counts, or catalog data carry none, because an observed event needs no statistic: `get_match_phases`, `get_ds_timeline`, `search_strings`, `can_health`, `analyze_auto`, `analyze_replay_drift`, `get_code_metadata`, `export_csv`, the other core and query tools, and the discovery, TBA, and other revlog tools.

| Field | Description |
|-------|-------------|
| `sample_count` | Number of data points |
| `time_span_seconds` | Duration of the data (summed over windows) |
| `sampling` | `periodic` (logged every loop: at least 80% of intervals within half a median interval of the median), `change_only` (irregular timing and no two consecutive values equal: logged when the value changes, as AdvantageKit and NetworkTables logging do, so a long interval is a hold), or `event` (irregular otherwise, or too few samples to tell) |
| `gap_count` | Intervals longer than 5x the median interval |
| `max_gap_ms` | Longest of them, in milliseconds (only present if gaps > 0) |
| `nan_filtered` | Count of NaN/Infinity values (only present if > 0) |
| `effective_sample_rate_hz` | One over the median interval |
| `quality_score` | Composite score 0.0–1.0 (see below) |
| `reasons` | One line per penalty, e.g. `"28.0% of the time span is in 530 gaps longer than 5x the median interval (longest 85468 ms)"` (only present when the score is below 1) |

**Quality score** (each penalty listed in `reasons`):
```
score = 1.0
  - 0.3 x min(time_in_long_intervals / time_span / 0.2, 1)  // periodic and change_only series
  - 0.2 x min(MAD(intervals) / median_interval / 0.5, 1)     // jitter, periodic series
  - 0.2 x non_finite / total                                 // NaN and infinities
  - 0.3 if fewer than 100 finite samples, 0.15 if fewer than 500
(0 when no value is finite)
```
Long intervals are weighed by the time they cover, not by their number: a full-match 50 Hz series with a few hundred loop stalls scores about 0.9. For a `change_only` series they are holds, not missing data, but statistics weigh samples, not time, so a series that sat unchanged for a quarter of the match still reads `medium`. Event series are not penalized for irregular timing.

**Confidence levels** derived from the quality score:
- `"high"` (> 0.8, and at most 10% of the time span in long intervals, except for event series): reliable data; results can be stated with confidence
- `"medium"` (> 0.5): usable data; note caveats in the analysis
- `"low"` (> 0.2): poor data; treat results as preliminary
- `"insufficient"` (0.2 or less): too little data for meaningful analysis

The level bounds statistics (means, trends, correlations), not directly observed events: 68 samples of a CAN error counter read `low`, while its peak of 215 at 650.86 s is a fact. A score below 0.5 also adds a warning that says the same.

### `server_analysis_directives`

Guidance generated from the data quality, for the agent. Included alongside `data_quality` by the same tools (`compare_matches` gives it once, from the lower-quality log).

| Field | Description |
|-------|-------------|
| `confidence_level` | `"high"`, `"medium"`, `"low"`, or `"insufficient"` |
| `sample_context` | A readable summary (e.g., "Based on 4500 samples over 150.0 seconds"). It counts the samples the statistics rest on: when some are NaN or infinite it reads "Based on 25 finite samples of 300 (275 NaN or infinite) over 6.0 seconds" |
| `interpretation_guidance` | Notes on the data quality issues found, plus tool-specific caveats (most tools add one that a single log may not generalize) |
| `suggested_followup` | Tools to call next |

Guidance is added for:
- fewer than 100 finite samples ("Low sample count")
- a periodic series with gaps in more than 2% of its samples ("data gaps detected")
- a change-only series (long intervals are holds, not missing data, and a sample count is a count of changes)
- NaN or infinite values ("non-finite values were filtered")
- a time span under 10 seconds ("Short time span")
