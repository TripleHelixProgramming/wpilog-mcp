# Changelog

All notable changes to wpilog-mcp will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Opt-in `capture.pull` copies robot WPILOG/REV logs over SFTP while NT4 reports the robot disabled and settled. Content checks protect resume and rename across reused filenames, old generations are retained, and verified copies join sessions by serial and near-zero data correlation. Device identity and key history share the capture/store path. Earlier code had no robot-file transport; pulling remains off by default pending the roboRIO shop test. The robot's files are never modified or deleted.
- Robot identity: the listing reads logged serials and comments wherever a file lives, and offers evidence for unique `robot_candidates` without assigning them. Device context, serial-based placement, SSH key history, and logged/device disagreements now share the store; address promotion preserves paths already given to tools. Previously identity was limited to imported files and capture paths stayed tied to an address.
- A server with a `capture` section records the robot's NetworkTables to its store and serves the open session to every existing log tool. Previously the NT4 client was only a foundation. Captures are ordinary WPILOG files, with clock-based session resumption, explicit exclusion/thinning, and topic cost accounting; each tool call reports the fixed session prefix it read, with older values available from the file beyond the configurable hot window.
- The pit server now has an NT4 wire layer, JDK WebSocket client, and loopback gateway core exercised on every generated fixture log. Previously no live-protocol foundation existed; this first milestone adds no user-visible behavior and changes no startup, configuration, tools, capture, or extension behavior.
- Installers accept `--tag` or `--pre-release`, optionally bootstrap the matching VS Code extension with `--with-extension`, and prompt for log directories, team, and extension installation in a terminal. Explicit `--refresh` replaces an old layout with a fresh install, preserving settings and a complete recovery backup after stopping its daemons; ordinary updates remain non-disruptive.
- Local MCP sessions can lease directories and an in-memory TBA key through loopback-only registration endpoints. Every client sees the live directories with origin/team metadata; session end revokes access, including cached reads. The bridge accepts project/flag directory leases while taking shared-server configuration only from the home or explicit file, preventing the first project from choosing the daemon’s settings.
- The extension offers to install a missing standalone server from its bundled JAR, updates an older launcher on activation, and provides **Install Standalone Server** on demand. New configurations take absolute User log directories and team only; existing configuration and newer launchers are kept. Previously a missing install required a separate installer and older servers only produced a warning.
- `wpilog-mcp install` installs its own JAR and versioned launchers, optionally seeds a new configuration, and reports text or JSON. It keeps an equal or newer current launcher unless `--force` is given, preserves existing YAML or legacy JSON, and leaves running daemons alone.
- WPILog Explorer can now organize logs: per-folder offers with remembered choices, move/copy and robot picks, HTTP job progress and exact server refusal details, and trees by robot/session with REV companions, Unassigned, Inbox states, and Unmanaged files. Plain folders keep their event/date view and can supply copies to a store; unassigned files can be assigned from the tree. Previously these operations required the import command or HTTP API.
- Store assignment (`POST /store/assign`) moves unassigned files while retaining provenance. Inbox batches now carry `stated_robot` in `batch.json`, including batches from `import --robot`; malformed metadata gets an explained refusal. Store roots and correlated REV companions are exposed for the explorer.
- Imports now run as HTTP jobs (`POST /store/import` and polling), from a store's inbox with receipts in `inbox/imported.log`, or through `wpilog-mcp import`. Previously only the Java pipeline could organize files. The command posts to the named running daemon or imports offline; both take the same store file lock. HTTP sources must be inside configured log directories; outside sources go through the inbox. The listing reports waiting, importing, and refused inbox files separately from unmanaged files.
- Store manifests and a queued Java import pipeline organize existing WPILOG and REV files by robot and session, preserving names and provenance, verifying copies, deduplicating by SHA-256, and pairing REV logs by correlation before moving them. Previously a folder had only its filenames to describe its organization; `list_available_logs` now reads a store's manifests, reports robot and session facts, leaves hand-copied files unmanaged, and reports moved paths for seven days. Serial numbers promote stated robot names without merging existing histories.

### Changed
- `installExtension` delegates VS Code lookup and installation to the JAR's `install --with-extension --vsix` verb. Release scripts fall back only for a JAR without the verb, so an actual write or refresh failure cannot trigger another install.
- The shell, PowerShell, and Gradle installers now delegate the layout to the JAR's `install` verb instead of maintaining three copies of launcher scripts and configuration defaults. Release installers download to a temporary file; the Gradle task forces its development build current. Windows launchers now fall back to `JAVA_HOME` after WPILib, as the Unix launcher does.

### Changed (breaking)
- `list_available_logs` reports `log_directories` as objects (`path`, `origin` of `configured` or `leased`, and `team`, which may be null) instead of plain strings; the paths alone are in the new `log_directory_paths`. A client that read `log_directories` as strings must read `log_directory_paths`.
- The extension uses the standalone install's `http` server for every window and client. Directory, team, and key changes replace its session lease without restarting the daemon; the Logs tree shows each directory's origin. Claude Code gets a user-scope bridge registration, with an offered project YAML file for terminal use, instead of new project `.mcp.json` entries. The private daemon, `useStandaloneServer`, and `idleExitMinutes` settings are removed; permanent server settings belong in `servers.yaml`. The `maxHeap` setting still sizes the server: the extension passes it to the launcher as `WPILOG_MAX_HEAP` when it starts the daemon, never on a command line.
- On project open, the extension retires only recognized old bridge entries from untracked or ignored `.mcp.json`, preserving other contents and leaving tracked/custom files with a note. It stops `vscode-default` once and removes private `servers/` and `projects/` settings only after a successful stop; previously those entries and settings remained active.

### Fixed
- Log listing again reads only the 2000-record metadata prefix, including serial and comments. Store robot candidates use fingerprints persisted by import inspection; previously identity and candidate discovery scanned whole files after every restart. Older manifests without fingerprints provide no candidates.
- Robot pulling reuses one recursive listing across each pass and refreshes a long pass after ten seconds, instead of listing again for every 64 KiB block.
- SSH hash commands now have length-scaled deadlines and keepalives, retaining five-second connect timeouts. Previously an idle socket timeout repeatedly killed large prefix hashes.
- Pull matching filters session manifest time ranges before loading logs for correlation, avoiding scans of unrelated sessions on the store queue.
- Changed SSH host keys now refuse password or private-key authentication until explicitly accepted or the pinned fingerprint is removed. Empty-password contacts may continue automatically; previously every changed key was accepted before credentials were sent.
- Identity learned mid-session writes context in place and queues its manifest fact; directory promotion waits for session close. Previously promotion blocked NT4 on the store queue and split the capture solely to move it.
- Abandoned-capture recovery now runs after HTTP starts and starts NT4 when it completes. Previously scanning and hashing a large file in construction could time out the daemon health check before its port opened.
- Capture startup now recovers unowned `open_capture` files left by a crash or interrupted shutdown, recording the file's hash, size, scanned range, modification-time ending and recovery reason; unreadable files remain open with an explained failure. Shutdown is bounded to 30 seconds. Previously an unfinished manifest stayed open forever. Rollover schema copies now use the rollover server time instead of stretching each file's range back to the original schema timestamp.
- Capture manifest updates no longer wait on imports from the NT4 loop: snapshots coalesce, unchanged facts do not trigger writes, progress is limited to every five seconds, and shutdown waits within its bound for final hashes. Captures roll to bounded numbered files in the same session (default 1 GiB); each file has its own live index and schema definitions. Writer failures now close recording with a manifest/log reason while keeping NT4 connected, suppressing retries until a new robot clock. Cosmetic directory renames wait until close to avoid racing rollover and mapped reads.
- Capture hot-value expiry runs on the 250 ms flush tick instead of remapping on every append; tests count mapped reads to prove cold decoding. Address placeholders report basis `address` instead of claiming a stated identity. Oversized WPILOG imports keep the original and report the same 2 GB mapping-limit reason as loading and the plain-directory listing.
- The NT4 client retries the last successful address first, then the other candidates in configured order. Previously every reconnect started at the first candidate, repeating an unavailable mDNS lookup before reaching the robot's IP address.
- Standalone installation validates the complete layout against the canonical install root before writing and rechecks each destination. Symlinked directories, locks, configuration files, JARs, or launchers that escape the root are refused with the path named, instead of overwriting outside files. The current Unix launcher may still point inside the install.
- Store imports refuse symbolic-link lock files before HTTP job admission and open locks without following links. Containment checks now reject dangling links, preventing an import from creating a lock outside the store through a missing target.
- Imports refuse to move a file listed by another store’s manifest and name that store in the refusal. Copies remain allowed, preserving the source store’s readable catalog instead of leaving it with a missing payload.
- `health_check` reports a live registered TBA key as available, instead of checking only the file's key.
- Data streams now disclose undecodable records beside the surviving sample count in Arrow and CSV. The explorer shows the server’s decode warning on the affected series chip instead of silently plotting incomplete data.
- REV data ETags include the REV file snapshot and alignment state, so changing an offset or synchronization no longer leaves the viewer using cached timestamps from the previous alignment.
- Arrow data streams now preserve enum numbers absent from the schema with a null label, instead of crashing after the response starts; CSV retains the number and an empty label cell. Real-endpoint samples are checked by the extension reader and pyarrow.
- Unassigned imports keep payloads under `robot/` and reserve control filenames in hash subdirectories, preventing a log named `import.json` from being overwritten by its manifest. Existing unassigned layouts remain readable and migrate on the next import with provenance retained.
- The one-line installers now fall back to the downloaded release's tagged installer when its JAR rejects the `install` command, so releases from before the verb remain installable. Both paths report which was used and clean up temporary downloads; a repeated fallback stops with an error.
- Installer JSON checks now capture stdout separately so JVM banners on stderr do not break the suite.
- Concurrent starts during a version-mismatch restart now share one replacement daemon. The restart claims the PID file under the start lock as it checks the version and retains that live starter's claim through stopping and spawning; previously another start could decide to restart the same old daemon, overwrite a new claim with its stopping record, or discard that record as stale as soon as the old process exited.
- Inbox polling now reuses known stores, discovers at startup and from listings, and walks configured directories at most once a minute. Hidden, locked inbox staging keeps partial copies out of listings; abandoned transfers are removed with receipts. Real-transport checks now guard idle exit during both HTTP and inbox imports.
- Store moves now evict the shared manager's mapping and wait up to three seconds for active readers, so a log already viewed in the explorer can be organized without restarting the server; eviction keeps mappings alive until tools, data streams, and background synchronization finish reading them.
- Imports read the store catalog once per batch and nominate REV candidates by robot and a generous clock window before correlation, replacing repeated whole-store scans and correlations against a season's unrelated sessions; unknown REV clocks still consider every session of the stated robot, and only the signal data establishes a match.

### Testing
- Store fixtures now pin serial-first identity for new named robots, separate non-overlapping sessions, and widening only the overlapping session; mapping-lifetime and import-scaling checks guard the review gaps with synthetic files and planted production bugs.

### Documentation
- The guides now describe one shared standalone server, session leases, user-scope Claude Code registration, preserved install settings, and legacy migration. They distinguish VS Code settings from permanent YAML, replace the plan's superseded private-daemon proposals with the implementation record, and list the real-VS-Code checks still required.

## [0.9.1] - 2026-10-04

This release puts the corrected extension README on the Marketplace listing, whose Overview still said the extension was not there: the 0.9.0 package was built before the README changed, and the Marketplace shows the README inside the package. It also reorganizes the README, edits every document, and makes a loaded log follow its file.

### Added
- One background HTTP server can serve every MCP client on a machine. `wpilog-mcp connect <name>` relays a stdio client's standard input and output to the named `http` server, starting it first when it is not running, exactly as `start` would, and `connect --url <url>` relays to any MCP server by URL without starting anything; the Claude Code and Claude Desktop entries can run it in place of a stdio server. Each client gets a session of its own, deleted when the client closes its end. A request the server cannot answer (unreachable, or the session gone because the server was restarted) is answered with a JSON-RPC error carrying the request's id, and the bridge exits non-zero so the client runs it again. Before, every client started a server of its own, and a client whose configuration takes only a command could not use an HTTP server at all.
- `wpilog-mcp stop <name>` ends a background server: it finishes the calls in progress, then exits, and `stop` returns once it has. The server accepts a stop only over loopback and only with a token the `start` wrote to `~/.wpilog-mcp/run/<name>.token`, readable by the user alone, and passed to the server in its environment; a server that does not exit within the start timeout is ended as a process, and a server from before this version, which has no stop endpoint, is ended as a process straight away. Before, the documentation said to kill the process.
- An `http` server started in the background can exit on its own: `idle_exit_minutes` in its configuration is how long it runs with no MCP session and no request before it exits, counting from its start or from the end of its last session. Health checks do not count, so a start's probe cannot keep it alive. Off unless set, so a server started by hand stays until `stop`.
- `GET /health` reports the server's version and process ID.
- `read_entry` reads at a resolution: `max_points` divides the window into that many buckets of equal duration when it holds more samples than that, each bucket with its `count`, `min`, `max`, `mean` (null when no sample is finite), `first`, and `last`, so a spike one sample wide survives where a mean would hide it and a change-only entry's holds can be drawn across a bucket; with no more samples than `max_points` the read is exact. A field path inside a struct or array goes through as `get_statistics` takes it; a non-numeric entry is read exactly with `max_points` in `skipped`. The differential check recomputes every bucket from the raw records. Before, a viewer or a model had to page through every sample of a long entry to see its shape.
- `GET /data/entries` on the HTTP transport: every sample of one or more entries over a window, as an Apache Arrow IPC stream (`format=arrow`, the default: `pyarrow.ipc.open_stream` or `polars.read_ipc_stream` reads it in one line, with the timestamps as timestamps and a struct's fields as columns) or as CSV in the export tool's form, with `max_points` for `read_entry`'s buckets, the schema's metadata carrying what a tool result would (the server version, `inputs`, the time range, each entry's type, sampling class, the unit its name states, and sample count), each batch tagged with its entry, an `ETag` that turns a repeated request for an unchanged file into a `304`, a refusal with the count and size over a 512 MB cap, and an empty final batch that says when the file changed mid-stream. It is behind the path validator and the `Origin` check as the MCP endpoint is. The server writes Arrow at the format level with `arrow-format` alone, on the heap; a reader written from the specification checks every stream in the tests, and a CI job reads them with pyarrow. `get_server_guide` names the endpoint as `data_endpoint` on an HTTP server. Before, the CSV export tool was the only way to get a whole entry out, one file at a time.
- VS Code extension: one server per computer, shared by every client. The extension starts the server's named daemon (`vscode-default`, with the log directories and team number of the User settings, and every open or remembered project's own log directories joined in) in the background on the loopback address, on a port chosen once and kept in its configuration file, which the extension writes in its storage (`servers/vscode-default.json`); registers it with VS Code's agents as an HTTP server at its URL; and writes Claude Code's `.mcp.json` entry as the bridge to it (`connect vscode-default --config <file>`), so Claude Code in a terminal with VS Code closed starts the server itself and Claude Code beside VS Code shares it. A settings change, a new TBA key, or a change of the window's folders rewrites the server's configuration and restarts it, and VS Code is told to connect again; a port another program has taken is replaced by a new one; a failed start is tried again with growing pauses, up to a few times, with an error that offers the output and the server's log. Also new: the `wpilog-mcp.idleExitMinutes` setting (default 30, 0 for never), after which an unused server exits, and the **Show Server Log** and **Restart Server** commands. Before, VS Code's agents each ran a stdio server of their own and Claude Code another, from a configuration file per project.

- VS Code extension: a `wpilog-mcp.useStandaloneServer` setting (off by default) makes the standalone install's server the extension's. With it on, the extension starts the `http` server of `~/.wpilog-mcp/servers.yaml` with the install's own launcher (`start http --config`), reads its port from the PID file the start writes, and gives that server to VS Code's agents, to WPILog Explorer, and, through the install's bridge (`connect http`), to Claude Code's `.mcp.json` entries, so Claude Desktop and every other client of the install share one server with VS Code; the install's file decides the server's settings, and the extension's own log directory, team, key, Java, heap, and idle settings do not apply. A missing install, or a start that fails, is reported once with the output offered; a standalone server older than the extension is noted in the output, since the explorer needs what newer servers have, and is not replaced, the install's installer being the way to upgrade it. Before, the extension always ran a server of its own beside any standalone install's.
- `GET /data/entries` serves REV log signals too: a name of the form `REV/<device>/<signal>` (or with the bus, as `list_revlog_signals` gives it) streams the signal's samples on the wpilog's clock, exactly as `get_revlog_data` reads them, with how the timestamps got there in the entry's metadata (`rev`: the device, the signal, the bus, `sync_method`, `timestamps_aligned`, `offset_seconds`, `sync_confidence`); a REV log that could not be synchronized is refused with the reason, and one still synchronizing with a 503 and the hint to wait.
- VS Code extension: WPILog Explorer's REV pane (`doc/EXPLORER_PLAN.md`, milestone 5). It lists the REV signals by bus and device with each bus's synchronization method, confidence, and offset, waits for the synchronization that runs when a log is first opened, plots a signal in the same panes as the log's entries on the robot's clock with the alignment shown on its chip, and says when a bus could not be synchronized, plotting nothing from it.
- VS Code extension: WPILog Explorer's console, array elements, and field view (`doc/EXPLORER_PLAN.md`, milestone 4). The console pane lists the log's text through `search_strings` with a pattern, a level, and a visible-window filter, repeats collapsed with their count and alerts with when they cleared; a click moves every pane's cursor to the line, and the matches in the window are marked on the timeline by level. A `[*]` field path expands to the array's elements in the Entries view and in an entry's details, each plottable by its index, and **Plot Every Element** plots the first sixteen. The field view draws the robot's pose over the window on an outline of the season's field from the bundled game data, the pose entry being the one the signal resolver finds by convention, with its basis, or the one picked among the resolver's candidates when it finds none; the cursor's pose is drawn with its heading.
- VS Code extension: WPILog Explorer plots (`doc/EXPLORER_PLAN.md`, milestone 3). A **plot** button beside a numeric entry or field, or a click on one in the Entries view, draws it in a pane; panes hold any number of entries and share a cursor with a readout. A timeline across the top shows the whole log with the enabled periods shaded by mode from `get_match_phases` and the Driver Station's events from `get_ds_timeline`, and a drag on it chooses the window; in a pane, drag zooms, the wheel zooms about the cursor, shift+wheel and the arrow keys pan, and double-click or Home shows the whole log. A series is fetched from the server's new data endpoint as an Arrow stream, every sample up to four million per series, so zooming and panning touch no server and a spike one sample wide is drawn where it is; a longer series is fetched bucketed for the window, drawn as a band from each bucket's minimum to its maximum with the mean as the line and a readout that says so, and fetched exactly once the window fits. A change-only entry is drawn as steps. A click on a series' name shows `get_statistics` over the visible window, with the data quality, its reasons, and the confidence level as the server states them. The extension gains a data client that remembers the streams it fetched and sends `If-None-Match`, so a window returned to costs no bytes, and the webview a reader of the Arrow IPC stream of its own (a few hundred lines, tested against the server's streams that pyarrow has read), with uPlot bundled as a static asset under its license; the webview still opens no connection.
- VS Code extension: WPILog Explorer, the first of the viewer's milestones (`doc/EXPLORER_PLAN.md`, milestone 2). An activity bar container with a **Logs** view (the server's listing from `list_available_logs`, grouped by event and date, with a filter) and an **Entries** view (the open log's entries as a tree by name from `list_entries`, a struct's numeric field paths beneath it from `get_entry_info`, with a filter the server applies), and a read-only custom editor for `.wpilog` files that shows the log's time range, duration, entry count, and truncation warning, every entry with its type and sample count, and a picked entry's description. The extension has an MCP client of its own over the server's HTTP transport, one session, re-opened when the server was restarted and deleted when the extension ends; the webview never opens a connection, and its page allows only the extension's own style and script. A log outside the server's log directories is refused by the server, and its error, with its hint, is shown. Before, the extension showed nothing itself.

### Changed
- VS Code extension: an entry written by an earlier version, which started a stdio server of its own from a per-project configuration file, keeps working with that file until its project is next opened in VS Code, when the entry is rewritten to use the shared server and the file removed.
- `start <name>` (and `connect <name>`) restarts a running background server of another version: a server of the JAR's own version is left as it is, one of another version, or of a version too old to say, is stopped and started again, so an upgrade never leaves the old JAR serving. Before, `start` reported any running server as already running, and the upgrade guide said the old version served until someone killed it.
- `start` tells a stranger on the port from its own server. A port held by something that does not answer `GET /health` as wpilog-mcp is reported, and nothing is started; before, any answer on the port counted as the daemon, and a start onto a port held by another program spawned a server that died trying to bind it. A server of this version answering on the port with no PID file is recorded rather than started again.

### Documentation
- `doc/PIT_SERVER_PLAN.md` proposes the pit server: a daemon that records the robot's NetworkTables stream as captures, serves it as a read-only gateway and over the HTTP MCP transport on the team's private network, pulls the robot's own logs and the roboRIO's system logs while it sits disabled, follows the program's console and other configured files into the capture as they are written, records the roboRIO's system stats and the robot program's JVM activity beside the data, serves its latest values for Prometheus and Grafana, and keeps the long-term record in a store it owns, by robot and session, which the extension mirrors to a laptop for analysis offline. The first part is for the team, the second a specification with milestones for the developers; `doc/IDEAS.md` lists it as 9.1.
- `doc/EXPLORER_PLAN.md` proposes WPILog Explorer, a viewer inside the VS Code extension that shows the logs to a person without an assistant: a custom editor with plots, phases, statistics, console, REV signals, and a field view, every number from the same tools the assistant calls, with a selection that becomes a prompt. It specifies two server additions the assistant gains too, a bucketed `read_entry` and `render_chart`; `doc/IDEAS.md` lists it as 9.2.

## [0.9.1] - 2026-10-04

This release puts the corrected extension README on the Marketplace listing, whose Overview still said the extension was not there: the 0.9.0 package was built before the README changed, and the Marketplace shows the README inside the package. It also reorganizes the README, edits every document, and makes a loaded log follow its file.

### Added
- The VS Code extension is on the Visual Studio Marketplace; it was only a `.vsix` on the releases page. When the `VSCE_PAT` secret holds a Marketplace token, the release workflow publishes every release without a version suffix there (the Marketplace refuses suffixed versions, so test builds stay on GitHub); without the token, the workflow says how to add one. The package carries the license file; the manifest links the issue tracker and the README.

### Fixed
- A loaded log follows its file. The server keeps each loaded log's file size, modification time, and identity from just before the log was read, and compares them with the file on every call: a file that changed on disk is loaded again, and a result read while the file changed is discarded with an error that says what changed, because it may hold old data or mix old and new bytes. A read of a file truncated under its memory mapping is the same explained error, and the log is unloaded. Each session is told once, in `_metadata.log_reloaded` and a warning, when a log it used was reloaded. Before, a loaded log answered from its first load for as long as it stayed in memory, so a log copied off the robot again once it had grown kept its old contents; a file overwritten in place had the old record offsets applied to the new bytes, which hid the new records or decoded garbage; and the read of a file truncated while loaded threw an `InternalError` that nothing caught, which in stdio mode ended the server.
- The REV log tools look again for the REV logs that belong to a wpilog, at most every two seconds, and synchronize again when a REV log appeared, grew, or went: a REV log copied off the robot after the wpilog is found without reloading the wpilog, and an offset set with `set_revlog_offset` is kept for a REV log whose file did not change. Before, REV logs were found only when the wpilog was loaded.

### Testing
- CI checks that every Java file carries the license header (`./gradlew license`); the check used to run only locally, as part of `./gradlew build`. A build-file test fails if the workflow stops running it.

### Documentation
- The READMEs now send readers to the Marketplace to install the extension; the `.vsix` on the releases page remains the way to a particular build. The README's header links the Marketplace listing.
- `doc/STANDALONE.md` says why the TBA key belongs in the configuration file or the environment rather than on the command line, where the process list shows it.
- The README is reorganized. The example questions follow "How It Works" instead of repeating the header's questions a paragraph later; the installation section says once that most people want the extension; the agents the server works with are named (Claude, GitHub Copilot, Gemini, ChatGPT over the HTTP transport, and others); and a new section on supporting Triple Helix links the Intentional Innovation Foundation's donation page. The prose was edited throughout.
- The other documents got the same editorial pass: pronoun references and wrong words fixed; chained clauses broken up; the standalone guide's `-debug` flag back in its table, the HTTP transport and containerization sections placed as sections of their own, and the command-line notes gathered under the flags; the extension README's requirements moved ahead of installation and its upgrading section covering the Marketplace; the tools reference's REV log reference material, troubleshooting, and workflow gathered in the section's introduction, with one "When to use it" heading for every tool that has one; the development guide's test suites as paragraphs, its extension build beside the server build, and its releasing step split from what the workflow does; the ideas file's stale references and duplicated hand-off removed.
- `CLAUDE.md`, the guidance for AI agents working on the code, is brought up to date. It now covers the result contract, the no-guessing rule, the testing rules, the source conventions, the disk cache, and the VS Code extension; season-specific detail is left to the documents that own it.
- `doc/DEVELOPMENT.md` says how to keep tests passing on Windows, where CI runs them too, and that no value from another team's log goes into the repository unless the log's license allows it; it said neither.

## [0.9.0] - 2026-10-02

Most of this release is robustness work from [doc/ROBUSTNESS_REVIEW.md](doc/ROBUSTNESS_REVIEW.md), following [doc/ROBUSTNESS_PLAN.md](doc/ROBUSTNESS_PLAN.md): tools take their behavior from the log itself and never report success when they found nothing to analyze. It also adds several log directories and changes how the VS Code extension sets up Claude Code.

### Changed (breaking)
- VS Code extension: `wpilog-mcp.teamNumber` is empty until you set it. It defaulted to 2363, so a log that records no team number was matched with team 2363's TBA data; a team that relied on that default sets it once.
- VS Code extension: the `wpilog-analyzer` entry for Claude Code is added to `.mcp.json` only in WPILib robot projects and in folders that already have one, instead of in every workspace. The **Enable For Claude Code** setting (`wpilog-mcp.enableForClaudeCode`, on by default) turns this off, and **WPILog Analyzer: Add to Claude Code in This Folder** adds the entry to any other folder. The extension never writes into a `.mcp.json` that git tracks (it offers to add the file to `.gitignore`), leaves alone one that already runs the standalone install, and keeps the file's other servers.
- VS Code extension: the `.mcp.json` entry only starts the server, from a copy of the JAR in the extension's global storage, so an extension update no longer breaks it. The project's settings and the TBA key go into a configuration file there, one per project, that only you can read. Every server the extension starts keeps its disk cache in the extension's storage, apart from the standalone install's.
- Every result has a `status`: `ok`, `partial` (what was skipped is listed in `skipped`), `not_applicable`, `no_match` (with `looked_for` and a `hint`), or `error`. `success` is true only for `ok` and `partial`, so a tool that found nothing no longer reports success. A list cut by a limit reports `limits.<key>` with `total`, `returned`, and `limit`.
- Values that cannot be computed are `null`, not `NaN` or `Infinity` (which are not valid JSON), and are named in `_metadata.non_finite_fields`.
- `health_check` no longer returns `status: "OK"`, and its `disk_cache` block is replaced by `sync_disk_cache` and `parsed_log_disk_cache` (`used_by_load_path: false`). `get_tba_status` reports `configuration` (`configured` or `not_configured`) instead of `status`.
- Entries are listed in the order the robot program declared them, and a name started twice with the same type is one entry with all its records.
- The server no longer guesses what an entry is from its name or from the shape of its data. A role (battery voltage, total current, loop time, robot pose, auto chooser, path poses, swerve module states, vision entries, gyro yaw) resolves only to an entry you pass, an entry published under a well-known AdvantageKit, WPILib, CTRE, PathPlanner, YAGSL, Limelight, or PhotonVision name, or the only entry of its type. Anything else that looks right is listed as a candidate in `candidates`, `skipped`, or `no_match`, with the parameter to pass. New parameters to pass them: `voltage_entry` (`power_analysis`, `get_ds_timeline`, `predict_battery_health`, `generate_report`), `total_current_entry` (`predict_battery_health`), `chooser_entry`, `path_setpoint_entry`, and `path_actual_entry` (`analyze_auto`), and `vision_entries` (`analyze_vision`).
- Structs are decoded with the schema each log records (`/.schema/struct:<Name>`), so any struct decodes, including a team's own, a vendor's, nested structs, enums, and bit-fields. WPILib's schemas and three templates (`PoseObservation`, `TargetObservation`, `SwerveSample`) are used only for types the log has no schema for, and results say which applied (`source`).
- Struct output uses the schema's field names, with no aliases for the old keys: a `Pose2d` is `{"translation": {"x", "y"}, "rotation": {"value", "_derived": {"degrees"}}}` instead of `{x, y, rotation_rad, rotation_deg}`, and an enum field is `{"value", "label"}` instead of a bare label. Update any client code that reads struct values.
- Records that cannot be decoded are reported in a warning and in `_metadata.decode_problems`, and `read_entry` on an entry with no decodable records is an error that says why.
- `list_struct_types` with `path` describes the struct types in that log (source, size, fields, `numeric_leaf_paths`, and the entries using each); without `path`, the fallback schemas. It used to return a fixed list of 16 names.
- `get_entry_info` describes a struct entry's schema, fields, and `numeric_leaf_paths`, reports `decode_problem` and `non_empty_sample_count`, and takes its sample values from non-empty values.
- The numeric tools (`get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `find_condition`) read struct fields and array elements, given as a path after the entry name (`/RealOutputs/Drive/Pose.translation.x`, `/PowerDistribution/ChannelCurrent[3]`) or in `field`; `get_statistics` pools all elements with `[*]`. A struct or array entry without a path is an error that lists its numeric fields. Known angles (`Rotation2d`, `Rotation3d`, `SwerveSample` heading) are unwrapped, and `get_statistics` adds circular statistics.
- The same tools take `scope` (`enabled`, `disabled`, `auto`, `teleop`, `test`, or `segment:<i>`) and `windows` (a list of `{start, end}`, such as `find_condition`'s `intervals`), combined with `start_time` and `end_time`. Derivatives, peaks, and angle unwrapping stay within each window.
- `angle` (`radians` or `degrees`) declares a plain number, such as a gyro yaw, to be an angle: it is unwrapped across ±180° and summarized with circular statistics. The numeric tools, `compare_matches`, and `align_entries` accept it.
- `time_correlate`'s p-value accounts for autocorrelation, using an effective sample size from the two series' lag-1 autocorrelations (`effective_sample_size`, `lag1_autocorrelation`, `p_value_basis`), so smooth 50 Hz signals no longer look overwhelmingly significant.
- `data_quality` is calibrated. It classifies the `sampling` (`periodic`, `change_only`, or `event`), weighs long intervals by the share of time they cover, bases the sample-size penalty on finite values, and lists every penalty in `reasons`. Nearly every real result used to score 0.50 ("low"); long intervals in a change-only series (AdvantageKit logs values when they change) are now read as holds.
- `search_strings`, `get_ds_timeline`, `can_health`, and `generate_report` read `string[]` entries such as WPILib alerts and the string values of `json` entries, not only `string` entries. An alert is one event from when it appears until it clears (`end_sec` and `duration_sec`, or `ALERT_RAISED` with `cleared_at` in the timeline), and every match gives its `source` (`string`, `alert`, or `json`).
- `get_match_phases` returns `segments` (each interval of constant state, with `mode` and `end_reason`), `matches`, and `season`, instead of one span from the first enable to the last disable; `phases` describes the first match. A log without DriverStation data returns `no_match`, and a log whose robot was never enabled says so.
- `get_ds_timeline` builds its enable and mode events from the same timeline as `get_match_phases`, so a log with both `DS:` and `/DriverStation/` entries no longer gets doubled events, and `TELEOP_START` appears at every teleop enable.
- `analyze_auto` lists every autonomous period (`auto_periods`) and reads the selected routine from `chooser_entry`, or from the only chooser whose key contains "auto" (a WPILib SendableChooser, or AdvantageKit's `/NetworkInputs/SmartDashboard/<key>`). A log with no autonomous period returns `not_applicable`.
- `analyze_can_bus` reads CAN counters by field name, per bus: WPILib's `/SystemStats/CANBus` (bus `rio`) and CTRE CANivore status. `bus_name` now selects a bus; TEC and REC are reported as levels, with excursions to 128 or above; bus-off and TX-full counts are reported as increases, and utilization in percent.
- `can_health` matches "CAN" as a whole word, classifies each error line as enabled or disabled from the DriverStation timeline (`state_unknown` before any DriverStation data), and also reads the bus counters. `assessment_basis` says what decided `health_assessment`.
- `analyze_swerve` reads `SwerveModuleState[]` arrays as `module[0..N-1]`, reports speeds as magnitudes (`mean_abs_speed_mps`), pairs setpoints by index, and reports each module's speed tracking and steer error. The measured and setpoint entries come from a published naming, each pair from one table (AdvantageKit `SwerveStates/Measured` with `SetpointsOptimized` or `Setpoints`, CTRE `DriveState/ModuleStates` with `ModuleTargets`, YAGSL `currentStates` with `desiredStates`), or from the only module-state entry in the log, with a warning that the log does not say what it holds. Entries under other names, and one entry per module, are candidates (`no_match` with `needs_confirmation`) until passed as `measured_entry` and `setpoint_entry`; `measured_basis` and `setpoint_basis` say how each was chosen. New parameters: `measured_entry`, `setpoint_entry`, `scope`, `start_time`, and `end_time`. `swerve_entries` and `module_analysis` are removed.
- `analyze_replay_drift` compares arrays and structs element by element within `relative_tolerance` (every array entry used to be "divergent") and reports the first divergence and largest difference of each entry. A log without `/ReplayOutputs/` returns `not_applicable` instead of "0 divergences".
- `power_analysis`, `predict_battery_health`, `get_ds_timeline`, and `generate_report` use the roboRIO's logged `BrownoutVoltage` as the brownout threshold, else 6.8 V as a stated assumption (`brownout_threshold_basis`); a `brownout_threshold` argument still overrides it. `power_analysis` and `generate_report` report the roboRIO's logged brownouts (`rio_brownouts`).
- `power_analysis` (new `scope` parameter) and `generate_report` analyze enabled time when the log records it, and `generate_report`'s `avg_voltage_whole_log` is now `avg_voltage`. `brownout_risk` follows one stated rule (`brownout_risk_basis`): HIGH only from the roboRIO's logged brownout flag, or from threshold crossings when no flag is logged.
- `predict_battery_health` reports evidence, not advice: brownouts from the roboRIO's flag when it is logged, voltage statistics over enabled time (`scope`), and a `load_line` fitted against total current, with `observations` in place of the "Replace battery" recommendations. Its heuristic `health_score` remains, with `health_score_basis`, and a log without a voltage entry returns `no_match`.
- `generate_report` shares the voltage entry and brownout threshold with `power_analysis`, classifies console text as `get_ds_timeline` does, and adds the DriverStation timeline, the three largest current peaks, and the top error messages.
- `get_code_metadata` matches keys by leaf name, names each value's `sources`, and returns `no_match` when there is no metadata.
- `analyze_vision` reads what the AdvantageKit vision template and the vision libraries publish under their own names: `PoseObservation[]` streams (`/Vision/Camera<N>/PoseObservations`), `TargetObservation` entries, the template's pose arrays (`Vision/Summary/RobotPosesAccepted` and the like), Limelight's `tv`, and PhotonVision's `hasTarget`. For each camera it reports the observation rate, tag counts, ambiguity, latency, and residuals against the robot pose. Entries that only look like vision data are listed in `candidates` by kind, with `needs_confirmation`, and are not decoded; `vision_entries` passes the ones you have confirmed. `latency_candidates` lists entries named latency beside a stream, which `get_statistics` reads. `vision_prefix` limits only the vision entries (it used to hide the robot pose too), and the robot pose comes from `pose_entry` or the resolver.
- `profile_mechanism` analyzes only the entries passed for its roles (`setpoint_entry`, `measurement_entry`, `velocity_entry`, `current_entry`, `temperature_entry`). `mechanism_name` no longer chooses entries: it lists, per role, the entries whose names suggest it (`candidates`, with `needs_confirmation`), because a name does not establish that an entry is a mechanism's setpoint, or what its units are. Following error uses the setpoint in force, overshoot is a percent of the step, stalls in reverse are found, and `stall_velocity_threshold` and a temperature profile are new.
- `detect_anomalies` counts every anomaly (`anomaly_count` used to equal `limit`), reports its IQR `bounds`, takes `sort` (`time` or `severity`), and implements `spike_threshold` (jumps between samples, in the entry's units), with `spike_interval_sec` for the time between spikes.
- `find_condition` returns `intervals` (with `end_reason`), `total_true_sec`, and `fraction_of_window`, accepts boolean entries, and combines conditions on several entries with `conditions: {all: [...]}` or `{any: [...]}`. New operators: `ne`, `abs_lt`, `abs_lte`, `abs_gt`, and `abs_gte`.
- `find_peaks` reports `maxima_count` and `minima_count`. The numeric tools read booleans as 1/0, and an entry of the wrong type is an error naming its type.
- `compare_entries` rejects non-numeric entries, reports `samples_compared`, and makes entries that never overlap in time an error.
- `compare_matches` resolves `scope` in each log's own timeline, reports percentiles, `std_dev`, `sample_count`, and `data_quality` for each log, and gives `differences` (second minus first) without a significance test. An extreme in a log's first 5 s is flagged as a likely boot transient, and a log without the signal makes the result `partial`.
- `analyze_loop_timing` reads the entry passed as `entry`, else AdvantageKit's `LoggedRobot/FullCycleMS` (with `UserCodeMS` alongside), else loop periods derived from `/Timestamp`; other loop-time names are only candidates. The unit's basis is reported, the slow boot cycle is excluded, and `scope`, median, p90, and `percent_over_threshold` are new. A log without loop timing returns `no_match`.
- `export_csv` resolves a bare or relative `output_path` inside the export directory (`output_path` is optional) and returns the absolute path written. Values are flattened into columns: struct fields as dot paths, arrays one row per element with `index`, enums as `field` and `field.label`. New `inline` mode returns up to `max_rows` rows in the response.
- `list_available_logs` pages (`offset`, `limit`, default 50) and filters (`name`, `event`, `match_type`, `since`) its results and reports `log_count`, `total_logs`, and `has_more`; only the listed page is enriched from TBA. `log_directory` is replaced by `log_directories`. The event, match, and team come from the entries that carry them by convention (`EventName`, `MatchType`, and `MatchNumber` in the `DriverStation` and `FMSInfo` tables, and `SystemStats/TeamNumber`) and from the file name, now in WPILib's form (`FRC_20260321_162956_VACHE_Q10.wpilog`) as well as AdvantageKit's. A log named for an event and no match is listed with no match type; it used to be called a practice match.
- `get_tba_match_data` returns `no_match` for a match or event that does not exist, and an error, not "not found", for a rejected key or a network failure. `match_type` accepts TBA's codes (`qm`, `q`, `qf`, `sf`, `f`), B teams work, and `score_breakdown` passes through every `...Points` subtotal.
- Revlog sync reads the wall clock from WPILib's `systemTime` or AdvantageKit's `EpochTimeMicros`, using only readings taken after the roboRIO's clock was set; a log whose clock was never set is not matched to REV logs by time. A REV log's file-name time is read in the zone the wpilog's own file name shows (`revlog_filename_zone`), else UTC. A REV log from another session is not attached, and a correlation needs at least 10 s of overlap.
- The revlog tools return `not_applicable` when no `.revlog` was found for the wpilog. `get_revlog_data` computes `include_stats` over every sample in range and reports `limits.data`, and `wait_for_sync` caps `timeout_ms` at 120 s.
- `search_entries` and `get_types` return `no_match` when nothing matches.
- `analyze_cycles` reports truncation in `limits.cycles` and `limits.dead_time_periods`, replacing `cycles_truncated`, `total_cycles`, `dead_time_periods_truncated`, and `total_dead_time_periods`, and returns `no_match` when `cycle_start_state` never occurs.

### Added
- Several log directories. `logdir` in the server configuration takes a list, `-logdir` can be repeated, and `WPILOG_DIR` holds several directories separated as in `PATH` (`:`, or `;` on Windows); `-logdir` replaces the directories in `WPILOG_DIR`. `list_available_logs` lists them together, and a directory that cannot be read makes the result `partial`, named in `skipped`. REV logs are matched to a wpilog only within the configured directory that holds it and the wpilog's own folder, so another team's REV log from the same event is never synchronized with yours.
- VS Code extension: when it adds the server to a project's `.mcp.json`, it says, once per project, that a Claude Code session already running there must restart to find it (Claude Code reads `.mcp.json` only when a session starts), and how: **Reload Window**, offered when the Claude Code extension for VS Code is installed, or `claude --continue` in a terminal. **Don't Show Again** turns the notice off.
- VS Code extension: the `wpilog-mcp.additionalLogDirectories` setting. Log directories and the team number can be set in User settings for every project and overridden in a project's own settings, and a relative path is a folder inside the project. The Settings editor lists the settings in a fixed order (the log directory, the additional directories, the team number, the TBA API key, Claude Code, then Java and the heap) instead of alphabetically, which put the additional directories before the log directory.
- `resolve_signals` shows which entry plays each of 25 roles in a log (DriverStation state, battery voltage, brownout flag, loop time, robot pose, swerve module states, vision, CAN buses, alerts, and more), with the `match` tier, the basis, ranked candidates, `needs_confirmation`, and the tools that use it (`used_by`). The tools resolve their entries the same way.
- `align_entries` samples 1 to 8 numeric signals at common times (every record of one entry, or timestamps stored inside it with `time_field`), with `previous`, `linear`, or `nearest` interpolation. With two signals and `difference: true` it adds `difference_statistics`.
- `time_correlate` and `compare_entries` take `max_lag_sec` (and `lag_step_sec`) and report the best lag in `lag_search`: for `time_correlate` the lag with the strongest correlation in either direction, with its sign, and for `compare_entries` the lag with the lowest RMSE.
- `pose_corrections` measures how far a pose moved beyond what odometry or the integrated chassis speeds predict: the residual distribution, the corrections above a threshold, and their cadence (`correction_interval_sec`). It shows how big and how frequent vision corrections are.
- `compare_poses` reports the difference between two pose streams: distance, heading difference, and components in field coordinates or along and across the reference's heading (a path-following error).
- Successful results from log-reading tools say what they used: `inputs` names the entries read.
- `list_loaded_logs` reports each log's entry count and duration and, in `cache`, the number of loaded logs and the heap in use.
- MCP `instructions` in the `initialize` response, which Claude Code and VS Code put in the model's system prompt: nine rules on answering from tool results, reading `no_match` as "not found", learning what an entry measures from the robot source code that logs it and not from its name, verifying the premise, separating facts from inferences and hypotheses, testing a rival explanation, scoping statistics, and generalizing only across matches. `get_server_guide` returns the long form as `analysis_principles` (including confabulation traps and the tool call that avoids each) for clients that drop `instructions`, and asks Claude Code to keep its description loaded (`anthropic/alwaysLoad`).
- The server sends the agent to the robot's source code for what a log cannot say: which mechanism an entry belongs to, its units, and whether it is measured or commanded. The rule is in the MCP instructions, in `get_server_guide` (`critical_guidance.source_code_tip`, the first step of the method, and a trap in `analysis_principles`), in `resolve_signals`, and in every reason a tool gives for listing a candidate and not using it.
- WPILib DataLogManager logs: `DS:enabled` and `DS:autonomous` are read as well as AdvantageKit's `/DriverStation/...` entries, so match phases, scopes, and the enabled state of CAN errors work without AdvantageKit.
- `get_ds_timeline` reports the roboRIO's logged brownout flag as `RIO_BROWNOUT_START` and `RIO_BROWNOUT_END` events (`basis: "rio_flag"`), apart from voltage-threshold crossings (`basis: "voltage_threshold"`), and names the voltage entry it scanned (`brownout_voltage_entry`).
- `get_ds_timeline` counts console errors and warnings exactly (`text_event_counts`) and groups distinct messages in `text_event_summary` (count, `variants`, first and last time; up to 200 groups, with the true total), instead of listing some of them.
- `search_strings` lists all of a log's text in time order, with `pattern` (a substring, or `regex: true`), `level`, `start_time` and `end_time`, `offset` and `limit` paging with `total_matches` and `has_more`, and `collapse_repeats`. A regular expression gets one second per text value, so a catastrophic pattern returns an error instead of hanging the server. The old tool stopped silently at 50 matches.
- `power_analysis` reports `channel_analysis` for every current entry (signed peak and its time, minimum, maximum, average), expanding per-channel arrays such as `/PowerDistribution/ChannelCurrent`, capped by `channel_limit` (default 30). Current entries are recognized by a naming rule, so names like `CurrentLimit` are not taken for currents.

### Fixed
- The release workflow attaches the JAR and the `.vsix` before the release is published, and takes the release notes from the annotated tag's message. The repository's releases are immutable, and a pre-release used to be published first, so `v0.9.0-dev` was published with no files; its tag name cannot be reused, so the test build is `v0.9.0-dev2`.
- VS Code extension: the extension's version comes from `build.gradle` (`./gradlew syncExtensionVersion`, which every extension task and the release workflow run). Its `package.json` had stayed at 0.8.2, so a release would have looked already installed; a release now stops when its tag does not match `build.gradle`.
- VS Code extension: the TBA API key is kept in VS Code's secret storage instead of in plaintext settings and `.mcp.json`, where a commit could publish it. It is entered in the **Tba Api Key** setting, which the extension clears a few seconds later, once the key is in secret storage (a key still being typed is not moved, and Settings Sync never uploads the setting), or with **WPILog Analyzer: Set The Blue Alliance API Key**; **Clear The Blue Alliance API Key** removes it. A key in a project's own settings never replaces a stored key, since it may be a teammate's, and the extension tells the user to revoke it if the file was shared. On upgrade, a key found in settings is moved there, and a key an earlier version wrote into `.mcp.json` is removed with a note to revoke it if the file was shared.
- VS Code extension: requires VS Code 1.101 or later (`engines.vscode` is `^1.101.0`). On 1.100 it installed but failed on activation.
- VS Code extension: relative links and images in the packaged README work.
- VS Code extension: on Windows, a log folder named twice, such as `logs` inside the project and `C:/robot/logs`, or with a trailing slash or in another case, is passed to the server once; it was passed twice, and its logs were listed twice.
- VS Code extension: a project's configuration file, which holds the TBA key, is written only once the project's `.mcp.json` can take the entry, and **Clear The Blue Alliance API Key** removes the key from every configuration file in the extension's storage. A `.mcp.json` that was not valid JSON left a file with the key behind, which Clear did not reach.
- Revlog sync on recordings longer than 600 s no longer reports an offset off by up to tens of seconds at a correlation near 1.0. The sub-sample refinement now moves toward the correlation peak, and a clock-drift estimate beyond 1000 ppm is rejected and reported.
- Revlog sync pairs signals by data: names only nominate candidates by leaf name (applied output, velocity, current, bus voltage), correlation ranks them, and pairs that disagree are set aside instead of averaged. High confidence needs at least two pairs whose offsets have a standard deviation of 5 ms or less.
- With no correlated pair and no wall clock, revlog sync fails and says so instead of reporting an offset of 0. File-name estimates say they can be off by seconds.
- REV logs decode as REV specifies: the built-in DBC follows REV's SPARK frame specification (spark-frames 2.1.0, firmware 25 and later), so bus voltage, velocity, and applied output are no longer garbage. DBC files may declare float signals (`SIG_VALTYPE_`) and extended-frame IDs with bit 31 set.
- SPARK devices are labeled by the model they report (`SparkMax_<id>`, `SparkFlex_<id>`, or `Spark_<id>`, from a decoded `SparkModel` signal); every SPARK used to be labeled SPARK MAX. A REV log named `REV_..._<bus>.revlog` is reported under that bus, and file-name times in log listings are read as UTC, the roboRIO's zone (local time for `_sim` logs).
- The revlog sync disk cache key includes both file names and the DBC's hash, and its format version changes with any change to parsing, decoding, or sync, so results computed by older code are discarded.
- `get_revlog_data` and `list_revlog_signals` say how their timestamps were aligned (`sync_method`, `timestamps_aligned`, `offset_seconds`, `sync_confidence`), and a REV log whose sync failed returns `not_applicable` instead of its own clock. `set_revlog_offset` without `offset_ms` is an error; it was an offset of zero.
- A revlog sync that finishes after its log was unloaded no longer keeps the log in memory, a log loads even when the sync cannot start, and the revlog signal cache is safe for concurrent HTTP requests.
- TBA data for playoff logs is attached to the right match: since 2023 "Elimination N" is bracket match N (`sfNm1`), and a finals log is the team's playoff match nearest the log's time. `list_available_logs`'s `tba` object and `get_tba_match_data` report `match_key` and `lookup_method`.
- A TBA outage or a rejected key is no longer read as missing match data: `list_available_logs` reports `tba_enrichment: {available, reason}`, and `get_tba_status` checks the key (`key_check`). A `tba_key: ${VAR}` whose variable is unset counts as not configured, and log file names with the match types `qm` and `f` are enriched.
- Damaged log tails are not read as data. A log cut off mid-write can end in bytes that parse as records with absurd timestamps; the parsers stop at the damage, drop the records right before it that jump more than 60 s, and ignore any record more than a day ahead. A garbage Start record or header no longer fails the load. Negative timestamps are kept, since WPILib DataLogManager logs hold real records before time zero.
- A file that is not a readable log (empty, all zeros, another format, missing, a directory, without read permission, or outside the allowed directories) gets an error that says what the file is, instead of an "Internal error". A log over 2 GB gets an error that says so.
- Every result computed from a log that was not read to its end says so, in `_metadata.log_truncation` (`comparisons[].log_truncation` in `compare_matches`); only `list_entries` and `generate_report` used to. Most robot logs end inside their last record, so that alone is a note; a log that lost more gets a warning too.
- `analyze_vision` no longer reports a planned trajectory as a camera. A `SwerveSample[]` trajectory holds a timestamp and a pose, as a camera's observations do; where the robot logs one every loop it appeared as a camera named for its table, with a latency of minutes, and decoding its millions of samples took one call on a 688 MB log past 3 GB of heap. A gyro's struct with yaw and pitch fields is no longer a target stream, and a `Pose3d[]` outside the vision tables, such as a mechanism's component poses, is no longer a pose set.
- `analyze_swerve` pairs measured states with the setpoints of their own table. In a replay log, the replayed states could be compared with the real setpoints.
- `time_correlate` with `scope` or `windows` computes its lag-1 autocorrelations and sample rates within the windows. Pairs of samples on either side of the time between two windows lowered the autocorrelation, which overstated the effective sample size, and the sample-rate warning counted that time.
- `analyze_vision` and `analyze_cycles` score `data_quality` on the samples between `start_time` and `end_time`, not on the whole entry.
- `find_peaks` rejects a `type` other than `max`, `min`, or `both`, and `resolve_signals` rejects a `roles` that is not an array of role names. Either used to be read as the default.
- Heap pressure unloads only as many logs as needed. Free heap was measured without a garbage collection, so unloading a log changed nothing in the measure and every loaded log was unloaded, whenever the heap looked more than 85% full, even when it was full of garbage.
- The log listing no longer takes a team's own entries for the event, the match, or the team (any entry whose name contained `eventname`, `matchtype`, `matchnumber`, `stationnumber`, or `teamnumber` was read, whatever it was), reads the match type from its integer entry, and ignores a match number logged while the match type is None.
- Both parsers report malformed records in `decode_problem`, naming the record size and declared type.
- Scanning a log takes less memory: 4 bytes per record for offsets instead of about 22.
- A log evicted from the cache while a call was reading it no longer reads as empty.
- A call that exhausts the heap returns an error naming the heap size and the remedies (a time window or scope, `WPILOG_MAX_HEAP`), and the server keeps serving.
- `read_entry` returns NaN and infinite samples as the strings `NaN`, `Infinity`, and `-Infinity` instead of failing.
- `sample_context` in `server_analysis_directives` counts finite samples ("Based on 25 finite samples of 300 (275 NaN or infinite)").
- `data_quality` is scored on every sample in scope, per window. Some tools scored it after dropping NaN samples, scored the whole entry instead of the scope analyzed, or counted the time between windows as a gap. `predict_battery_health` reports `risk_level_basis` and `analyze_loop_timing` reports `health_score_basis`.
- `get_ds_timeline`, like `get_match_phases`, no longer carries `data_quality` or directives, which marked exact events as low confidence; the low-quality warning now applies to statistics only. `find_condition` and `analyze_can_bus` gain `data_quality`.
- `rate_of_change` returns `avg_rate: null` with `no_match` when no pair of samples can be differentiated, and `time_correlate` gives `p_value: null` beside a null correlation.
- No more empty successes: `can_health` and `get_ds_timeline` return `not_applicable` on a log with none of their inputs, and `list_entries`, `align_entries`, `export_csv` (which wrote a header-only file), `list_struct_types`, and `list_revlog_signals` return `no_match` when nothing matches.
- `find_condition` reports `samples_evaluated` and `find_peaks` reports `samples_analyzed`, so zero findings can be read against the samples searched.
- Every capped list reports `limits` with the true total, including `power_analysis` `channel_analysis`, `generate_report` `errors.top_messages`, and `predict_battery_health` `brownout_details`. `read_entry` caps a page at 10,000 samples.
- `analyze_swerve` takes its odometry and vision poses from the `robot_pose` and new `vision_pose` roles (or `odometry_entry` and `vision_entry`, where a missing entry or one that is not a pose is an error) and reports `odometry_basis` and `vision_basis`; it used to pick the first pose named like odometry. A number in a file name is read as the season only when it is a plausible year, not a team number.
- `analyze_cycles` in `start_to_start` mode starts a cycle on a change into `cycle_start_state`, so a state logged every loop no longer yields a stream of 20 ms cycles. An unfinished idle period ends at `end_time`.
- `moi_regression` validates `kt`, `gear_ratio`, `motor_count`, and `wheel_radius` and reports an undefined R² as null. `get_server_guide` rejects an unknown category, and `suggest_tools` returns `no_match` when nothing fits.
- The last record of a log is read; a final record shorter than 16 bytes (a boolean, a small number) was dropped.
- Bundled game data was checked against the final game manuals and corrected for 2024 CRESCENDO, 2025 REEFSCAPE (field size, ranking points; the CORAL RP is `coral_rp`, was `reef_rp`), and 2026 REBUILT. `get_game_info` returns `source`, `manual_version`, `manual_url`, `basis`, and `robot_constraints`.
- Tool descriptions match behavior: `can_health`, `power_analysis`, `analyze_auto`, `analyze_can_bus`, `get_statistics`, `generate_report`, and `export_csv`.
- Argument checks: a non-positive `limit` (`find_peaks`, `rate_of_change`, `detect_anomalies`, `find_condition`, `analyze_cycles`, `analyze_replay_drift`, `get_revlog_data`), a negative `moi_regression` `smooth_window`, a missing `get_tba_match_data` `year` or `match_number`, and a `start_time` after `end_time` are errors that say what is wrong, not internal errors or empty successes.
- Schemas declare every parameter the code reads: `angle` on `find_condition` and `compare_matches`, `windows` on `compare_matches`, and `start_time` and `end_time` on `power_analysis`.
- HTTP shutdown ends open SSE streams and no longer waits out a fixed five seconds.
- The stdio transport uses UTF-8 on every platform. On Windows with JDK 17 it mangled non-ASCII text.
- Malformed JSON-RPC requests get JSON-RPC errors (-32600 or -32602) with the request's id, and a request without an id is a notification that gets no reply.
- `start` reports a daemon running on another port (after `port` changed in `servers.yaml`) with its PID and refuses until it is stopped. Two starts no longer both spawn a daemon, whether they race over a stale PID file or the second arrives while the first one's server is still booting, when its record used to be taken for a reused process ID and deleted. On Windows, a start no longer fails, stopping the server it has just launched, when another start or another program has the PID file open at the moment the file is replaced.
- A server started in the background with `start` gets the launcher's heap: `WPILOG_MAX_HEAP`, else the `-Xmx` it was started with, else 4g. Without `WPILOG_MAX_HEAP` it used to get the JVM's default, a fraction of physical memory.
- The one-line installers (`install.sh`, `install.ps1`) install a release the way `./gradlew install` installs a build: a launcher per version and `wpilog-mcp` pointing at the newest, so an upgrade no longer rewrites an older version's launcher. They refuse a release tag that is not a version. `install.ps1` writes `servers.yaml` as written (PowerShell had expanded `${TBA_API_KEY}` in it to nothing) and works in Windows PowerShell 5.1.
- No installer writes a team number into a new `servers.yaml`: the one-line installers wrote `team: 0` and `./gradlew install` wrote `team: 2363`. A team number of 0 or less, from any source, is ignored with a warning.
- The `servers.yaml` the installers write has one place for the TBA key, the commented-out top-level `tba_key`, which says `TBA_API_KEY` works too. The `stresstest` server had its own `tba_key: ${TBA_API_KEY}`, which looked like the place for the key: a key put there reached the stress tests and no other server.
- The `measureMemory` Gradle task is gone; it passed a flag the server never had.
- `-debug`, `WPILOG_DEBUG=true`, and `debug: true` in `servers.yaml` turn on debug logging; they had no effect.
- `-tba-key` takes precedence over `TBA_API_KEY`, as the usage text says.
- A REV log synchronization read from the disk cache lists devices and signals in the order the REV log shows them, as a fresh one does. It listed them in no fixed order, so `list_revlog_signals` answered in a different order after the server restarted.
- `power_analysis` returns `no_match`, naming the scope, when there is nothing to measure in it: the scope holds no time (`enabled` on a log where the robot was never enabled, or a `start_time`/`end_time` outside the data), or no finite voltage or current sample falls in it and no brownout flag is logged. It returned a success with every section skipped. When amperage entries exist but have no finite samples in the scope, `power_analysis` and `generate_report` say so; they reported that the log had none.

### Security
- msgpack-core is 0.9.12 (was 0.9.8), which fixes CVE-2026-21452 (GHSA-cw39-r4h6-8j3x, high): a payload header could declare up to 2 GB that the library allocated before reading any of it. The server reads such payloads from its parsed-log disk cache, which is not on the load path, and only from its own checksummed files, so the exposure was small; a crafted cache file of a few dozen bytes made the reader fail with an `OutOfMemoryError` instead of rejecting the file. A test now checks that such a file is rejected while allocating almost nothing.
- The VS Code extension's development dependencies, which are used to build and package it and are not part of the `.vsix`, are updated within their existing version ranges, clearing all 42 Dependabot alerts (21 high, 16 medium, 5 low). All of them came through `@vscode/vsce`: `undici`, `fast-uri`, `js-yaml`, `brace-expansion`, `markdown-it`, `linkify-it`, `form-data`, `tmp`, `qs`, `uuid`, and `lodash`.
- CI submits the server's Gradle dependencies to GitHub's dependency graph on every push to `main`. GitHub does not read `build.gradle`, so Dependabot never saw the libraries in the server JAR, msgpack-core among them; it now alerts on them as it does on the extension's npm packages.

### Testing
- Tests check the server against an independent WPILOG reader, compare each tool's schema with the parameters its code reads and its description with real output, and cover the revlog and TBA success paths. `-PconformanceLogDir=<dir>` runs the independent-reader check, a conformance sweep, and a check of documented claims on a directory of real logs.
- VS Code extension: `npm test` runs the tests on Node 21 and later, where it failed because `node --test` no longer accepts a directory, and CI runs them on Linux and Windows (it only compiled the extension, so the failure went unseen).
- The stress tests use the configured TBA key (they ran every TBA call as "not configured"), build the test classes before running, and fail the build when a test fails.
- A tool can be added without the logs `doc/TOOL_RESPONSES.md` is generated from: the checks accept a generated file that does not hold the tool yet, and require a call for it in the scenarios file.
- The stress tests use their own disk cache, emptied before each run, instead of the user's: they read REV log synchronizations that other code had saved, and saved theirs there. `-PtestCacheDir=<folder>` gives every test task a folder that is kept between runs. The in-process stress test checks that a synchronization read from the disk cache gives the same answers as one computed with the cache off, and both stress tests run the REV log tools on a log that has a REV log; they ran them on a log without one. They list every log, page by page; they read only the first 500.
- The real-log conformance sweep searches six folder levels below the log directory, as the differential check does; it searched five. The claims check reads the simulated logs and their manifest from `-PsimLogDir`.
- The differential check recomputes domain answers from the raw records of the entries each tool names: loop-time statistics, roboRIO brownouts, CAN bus figures, swerve module speeds, and the modes of enabled segments, on the fixtures and on real logs. A second golden set (`-PgoldenMatchLog`) checks a 2026 championship elimination match that another team published under the MIT license.

### Documentation
- `doc/STANDALONE.md` registers the server with Claude Code correctly: `claude mcp add --scope user wpilog -- ~/.wpilog-mcp/bin/wpilog-mcp`, or a project `.mcp.json` written with `${HOME}`. It used to say `~/.claude/settings.json`, which Claude Code does not read for MCP servers.
- `README.md` is an overview with an index of the documentation: what the server does, which install to choose, and where each topic is covered. The reference material it carried moved to the documents that own it: data types and the REV log details to `doc/TOOLS.md`, and the rules for running the extension beside the standalone install to the extension's README, which also shows its icon. Its example questions run from broad to technical, in quoted italics, and the note after them says that the depth of the analysis depends on the model and on the context it can reach beyond the log, above all the robot code that writes the entries. Links to releases go to the releases page, where pre-releases are listed, instead of to the latest release, and `doc/STANDALONE.md` says the installers skip pre-releases.
- The icon in the header of `README.md` and of the extension's README sits midway between the top of its cell and the title (it sat high), and the icon, title, and tagline are each centered by their own `align="center"`, so a viewer whose stylesheet left-aligns table cells no longer shows the icon flush left. The intro text beside the icon is vertically centered in the row, so it no longer clusters at the top with a wide margin below when the icon column is the taller one.
- `doc/TOOLS.md` and `README.md` were matched to the current code, including the examples for `compare_matches`, `can_health`, `get_ds_timeline`, and `power_analysis`.
- `doc/TOOL_RESPONSES.md` is generated from real logs by a checked-in harness, with at least one call for every tool, grouped as `get_server_guide` and `doc/TOOLS.md` group the tools.
- `doc/DEVELOPMENT.md` covers building, every test suite and how to run it, a checklist for adding or changing a tool, and releasing.
- `doc/STANDALONE.md` documents the one-line installers.
- `doc/ARCHITECTURE.md` is new. It gives the project's goals, its design principles and the failures that led to them, a map of the code, and how the server reads logs, manages memory, caches results, synchronizes REV logs, and handles concurrent clients.

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

[0.9.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.9.0...v0.9.1
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
