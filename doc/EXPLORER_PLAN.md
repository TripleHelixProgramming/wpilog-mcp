# WPILog Explorer: Looking at the Data Without an Agent

A proposal for a viewer inside the WPILog Analyzer extension: a way to look at the logs wpilog-mcp reads, for the people who want to see the data themselves. The first half is for anyone on the team; the second half specifies the work for the developers who will build it, in the form [PIT_SERVER_PLAN.md](PIT_SERVER_PLAN.md) uses.

## Part I: The Idea

### What it is

WPILog Analyzer gives an AI assistant the tools to analyze a robot's logs. WPILog Explorer, inside the same extension, gives a person the same view, in the same editor, with no assistant in between. Open a `.wpilog` file in VS Code and it opens as a log: the entries on the left, a plot in the middle, the match phases shaded behind the plot, the statistics of what is on screen beside it, and the robot's console below. Click an entry and it plots; drag across the plot and the statistics follow the selection; type in the console pane and the matching lines appear with their time on the plot.

It is built on the server the extension already bundles, and it calls the same tools the assistant calls. So the number a student reads off the statistics panel is the number the assistant would have reported, computed by the same code from the same file, with the same data quality beside it. A question that starts by looking can continue by asking: a selection becomes a prompt, already naming the file, the entry, and the time window, so nothing has to be retyped. And it needs no assistant to be useful: install the extension, open a log, and look.

It reads REV logs beside the robot's log, on the robot's clock, as the server already synchronizes them. With a pit server on the team's network (see the pit server plan), it lists the team's sessions, follows a live session as it is recorded, and shows which sessions are mirrored on this laptop for the trip home.

### Why

- **Not everyone wants to ask.** A mentor who has read logs for years wants to see the trace, not describe it. A student learning what a brownout looks like learns it from the plot. The analyzer serves the question; the explorer serves the look.
- **Looking and asking belong together.** Today the look happens in another program and the question in VS Code, and the file, the entry, and the time window are carried between them by hand. Here the plot and the prompt share a selection.
- **One set of numbers.** A viewer with its own statistics and its own notion of a match phase disagrees with the assistant in small ways that cost an afternoon. The explorer shows what the server computes, so there is one answer.
- **The code is in the same window.** The log explains the code and the code explains the log, and VS Code already holds the code.
- **One extension, not two.** A viewer and an analyzer that share a server, a configuration, and a selection belong in one install. A team member with no interest in assistants installs the same extension and uses the viewer; the MCP server it registers costs nothing until an assistant asks for it.

### What it is not

- **Not AdvantageScope.** No 3D field, no video synchronization, no live connection to the robot of its own, no replay. AdvantageScope is excellent at those and stays the tool for them. The explorer is a log reader that lives where the code and the assistant live, and that shows the assistant's numbers.
- **Not a dashboard.** It reads logs. It does not talk to the robot and cannot change anything on it.
- **Not a second analysis.** It adds no analysis of its own. Every number it shows comes from a tool the assistant can call, and anything it needs that the tools lack is added to the server as a tool, so the assistant gains it too.

### How it works, in one picture

```
  VS Code
  ┌───────────────────────────────────────────────────────────────────────┐
  │  WPILog Analyzer (extension host, TypeScript)                         │
  │    today: registers the server for assistants, writes .mcp.json       │
  │    explorer: tree views (logs, entries, pit server sessions),         │
  │      a custom editor for .wpilog and .revlog (the webview),           │
  │      the MCP client that calls tools and hands results to the webview,│
  │      and "ask about this selection"                                   │
  │        │ HTTP on loopback: MCP for tools,   │ HTTP (extension host)   │
  │        │ /data for every sample              │                         │
  │        ▼                                    ▼                         │
  │  wpilog-mcp server (bundled JAR)       pit server (by URL)            │
  └───────────────────────────────────────────────────────────────────────┘
```

The webview draws; it never opens a network connection. The extension host is the only client, and it talks to the bundled server over HTTP on the loopback address, or to a pit server over HTTP, through two doors: the MCP endpoint for the tools, and a data endpoint for the samples themselves, in bulk, which a tool result cannot carry.

### Where it stands

The server has nearly everything the viewer needs: the listing, entries with struct field paths, values over a time window, statistics with data quality, match phases, the Driver Station timeline, console search, and REV signals synchronized to the robot's clock. Three things are missing on the server side. Two are added as tools, so the assistant gets them too: reading an entry at screen resolution (a bucketed read, with the extremes kept), and rendering a chart. The third is a way to get the data itself, every sample, at a size that MCP's JSON messages are the wrong shape for: a data endpoint beside the MCP endpoint, which hands the viewer, a script, or a dashboard the full record of an entry as columns of numbers. The extension gains its first views, its first webview, and its first MCP client of its own; the build, the version check, CI, and the release workflow are unchanged, since the extension is the one they already know.

## Part II: Specification

What follows is for the developers. It follows `CLAUDE.md` and the design principles in [ARCHITECTURE.md](ARCHITECTURE.md), and the extension's conventions: TypeScript against the VS Code API, no runtime npm dependencies, logic that needs no VS Code API in pure modules with tests on Node's test runner, and `extension.ts` as the glue.

### 1. Decisions

1. **One extension.** The explorer is part of WPILog Analyzer (`TripleHelixProgramming.wpilog-analyzer`), in `vscode-extension/`, under the same version, build, tests, CI, and release. The Marketplace identifier cannot change without becoming a new listing, so it stays; the display name and the README say that the extension analyzes logs with an assistant and shows them without one. "WPILog Explorer" names the viewer inside it: the custom editor, the views, and their commands.
2. **The webview never touches the network.** The extension host is the client. It launches the bundled server with the same configuration the extension gives the assistants, and reaches it, and a pit server, over HTTP. The webview receives results and data by `postMessage` (typed arrays travel as transferable buffers, which VS Code passes without copying through JSON) and sends requests the same way. This keeps the server's `Origin` check as it is (a webview's origin is `vscode-webview://<id>`, which the check rightly refuses, while a request from the extension host carries no `Origin` at all), and needs no CORS beyond what exists.
3. **Every number on screen comes from a tool.** The explorer computes no statistic, no phase, no synchronization of its own. What the tools lack is added to the server as a tool, with the tool checklist, the description tests, the conformance sweep, and the differential check, so the assistant can call it too. The first two are `read_entry` at screen resolution and `render_chart` (§6).
4. **One configuration.** The explorer reads the settings the extension has (`wpilog-mcp.logDirectory`, the additional directories, the team number, the Java path, the WPILib year, the heap) and the pit server URL the pit server plan adds. It contributes no second set. The server it launches is built from the same resolved configuration the assistants' server gets, by the same code, so the viewer and the assistant read the same directories.
5. **The explorer's server is its own process, on HTTP, on loopback.** The extension today launches no server itself: VS Code's MCP registry and Claude Code each spawn one from the definition it provides, on stdio. The explorer needs a client of its own and a channel for bulk data that stdio's one-message-at-a-time JSON is not, so the extension starts one server for the life of the viewer as a child process in HTTP mode, bound to `127.0.0.1` on an ephemeral port the server chooses and reports on its first line of output. It is a child, not the named daemon: it dies with the extension, so an update never leaves an old JAR running. A shared daemon for every client on a laptop (IDEAS 9.2) is a later milestone (§10), and this server is the one that would be shared.
6. **Bulk data has its own door.** Every sample of an entry is available from `GET /data` on any server running the HTTP transport (§6), as columns of numbers in a small binary frame or as CSV, with the same `inputs` a tool result would carry. MCP stays the channel for tools and their results; a tool never returns a hundred thousand samples as JSON, and a viewer never pages through ten thousand at a time. The viewer, an agent's script, and a dashboard read the same door.
7. **A chart is drawn by a library bundled as a static asset.** The rule against runtime npm dependencies is about the Node side of the extension, which stays dependency-free; the webview bundles one charting library as a file under `media/`, with its license, chosen for size and for drawing hundreds of thousands of points without strain (§5). Its version is pinned in the repository, not fetched at build.
8. **Opening a log opens the explorer.** The extension registers a read-only custom editor for `*.wpilog` and `*.revlog`, so a double-click in the Explorer pane opens the viewer; "Open With" still offers the hex editor. Nothing the explorer does writes to a log or to a log directory.

### 2. Layout and wiring

Everything is under `vscode-extension/`, in the layout the extension has:

| Path | What it holds |
|---|---|
| `package.json` | Gains the custom editor, the views container and views, the explorer's commands (`wpilog-mcp.explorer.*`), and the pit server URL setting, with `order` values the manifest test pins |
| `src/extension.ts` | Gains the explorer's glue: the server process for the viewer, the views, the editor provider, the URI handler, the commands |
| `src/explorer/*.ts` | Pure modules: the tree models, the request planner for the plot, the chart specification, the prompt builder, CSV formatting |
| `src/mcpClient.ts`, `src/dataClient.ts` | The MCP client over HTTP (§3), and the data endpoint client that turns a column frame into typed arrays |
| `media/` | The webview: its HTML, CSS, scripts, and the bundled charting library with its license; `.vscodeignore` keeps it in the package |
| `src/test/` | The tests, run by `npm test` as today |

Nothing changes in Gradle, CI, or the release workflow: the one extension they build, test, and publish is the one that grows. `doc/DEVELOPMENT.md` describes the viewer's modules and the real-VS-Code test (§9) in its extension sections, and `vscode-extension/README.md` describes the viewer beside the assistant setup.

### 3. The clients in the extension host

**The server process.** The extension starts the JVM with the arguments `resolveServerConfig` already builds for the assistants (`-Xmx`, the JAR, the log directories, `-team`, `-diskcachedir` under the extension's global storage, the same cache the assistants' server uses, so a REV synchronization computed for one serves the other), plus `--http --port 0`: the server binds `127.0.0.1` on a port the operating system assigns and prints `listening on 127.0.0.1:<port>` as its first line of standard output, which the extension reads; `--port 0` is a small addition to `Main`, and the line is the contract a test pins. The process is started on first use, restarted on crash with backoff, and killed on deactivation; its standard error goes to the extension's output channel.

**The MCP client.** `initialize`, then `tools/call` over `POST /mcp` with the `Mcp-Session-Id` header, which the extension host can read where a browser could not; a tool's result is the parsed JSON of its text content, and `isError` becomes a rejected promise. Requests run in parallel, which the HTTP transport supports. The same client reaches a pit server by its URL.

**The data client.** `GET /data` (§6) for the samples of one or more entries over a window, read as a stream into typed arrays (`Float64Array` for values and for timestamps in seconds, with the integer and boolean columns widened), and handed to the webview as transferable buffers. It sends `If-None-Match` with the tag of the last response for the same request, so a window the viewer returns to costs one round trip and no bytes.

### 4. Views

- **Logs**: a tree in an activity bar container "WPILog Explorer", from `list_available_logs`, grouped by event and then by date, showing the friendly name, the match, and the size. A filter box narrows by name, event, and match. With a pit server URL set, a second root lists its sessions from `list_sessions`, newest first, with the robot's name, whether it is open, and whether the mirror holds it (pit server plan §11). Right-click: open, reveal in the file explorer, copy path, pin or unpin on the pit server.
- **Entries**: a tree for the active log, from `list_entries` and `get_entry_info`: name, type, sample count, with struct fields and array elements as children from `numeric_leaf_paths`, and a filter box with the same pattern `list_entries` takes. Click plots the entry in the active pane; drag adds it to another.
- **The editor**: one webview per open log, in the editor area, with:
  - a **timeline** across the top, the whole log, with phases from `get_match_phases` shaded (autonomous, teleop, test, disabled) and the Driver Station timeline's events as marks (`get_ds_timeline`: brownouts, enable and disable, alerts), where a drag selects the window the panes show;
  - **plot panes**, one or several, each with any number of entries, time on the x axis in seconds from the log's start, a shared cursor across panes with a readout of each series' value at the cursor, zoom and pan with the wheel and the keyboard, and a step drawing for entries whose sampling the server classes as change-only, never a line through a hold;
  - a **statistics panel** for the selected series over the visible window or the selection, from `get_statistics`: count, min, max, mean, median, standard deviation, the quartiles, and `data_quality` with its reasons, shown as the server states them, with the confidence level and never a verdict;
  - a **console pane**, from `search_strings`: a pattern, a level filter, collapsed repeats, each match with its time, where clicking a line moves the cursor to it and matches in the window are marked on the timeline;
  - a **REV pane**, from `list_revlog_signals` and `get_revlog_data`: the signals by device, each with its synchronization method and confidence, plotted on the robot's clock in the same panes as the log's entries, with the offset shown and a note when a REV log is not synchronized;
  - a **field view**, when the log has a robot pose the signal resolver finds or the user picks: a top-down plot of the pose over the window on an outline drawn from the bundled game data's field geometry (length, width, zones), with no field image, since none is bundled.
- **Status**: the viewer's server state in the status bar (starting, ready, restarting), and for a pit server its reachability and the mirror's age, as the pit server plan's extension section specifies.

### 5. The plot and its data

**Resolution.** The viewer draws from every sample where it can. For each series it fetches the whole log's samples from the data endpoint (§6) as typed arrays, up to a budget per series (default four million samples, about 64 MB of timestamps and values), and keeps them in the webview; zoom and pan then touch no server, the cursor reads the exact sample under it, and a spike one sample wide is drawn where it is. Drawing reduces the samples to the pane's pixels in the webview, by the minimum and maximum per pixel column, which keeps every extreme and is a pass over a typed array, fast enough to run on every frame. A 50 Hz entry over a ten-minute shop session is thirty thousand samples; a 1 kHz entry over an hour is 3.6 million, still within the budget.

Beyond the budget, the data endpoint buckets (`max_points`, the same semantics as `read_entry`'s), and the viewer fetches the visible window at a few times the pane's width on each zoom and pan after a short debounce, drawing a bucketed response as a band (minimum to maximum) with the mean as the line, the readout saying the cursor is on a bucket and giving its range, and refetching at full resolution once the window fits the budget. Either way an exact series is drawn as points joined by lines, or by steps for change-only sampling.

**The library.** uPlot (MIT, about 50 KB, canvas, built for large time series) is the proposal: it draws steps, bands, multiple y axes, and a synchronized cursor across panes, and it has no dependencies. It is bundled under `media/vendor/` with its license file; the webview's content security policy allows scripts only from the extension's own resources by nonce, and no remote content. The field view and the timeline are drawn directly on a canvas, which is simpler than bending a plotting library to them.

**Theme.** Colors come from VS Code's theme variables, so the viewer follows light, dark, and high contrast themes; series colors are a fixed palette chosen to stay distinct on both backgrounds, with the phase shading at low opacity behind.

**Time.** The x axis is seconds from the log's start, as every tool reports time, so a value a person reads off the plot is the value they would pass to a tool. Where the log carries wall-clock time (`systemTime`), a toggle labels the axis with it as well.

### 6. Server additions

Two tools and one endpoint. The tools go through the tool checklist in `doc/DEVELOPMENT.md`, are documented in `doc/TOOLS.md`, called in the responses scenarios file, and added to the stress tests; the endpoint is documented in `doc/STANDALONE.md` beside the HTTP transport and checked by the same claim checks against real responses.

**The data endpoint.** `GET /data/entries`, served by the HTTP transport beside `/mcp` and `/health`, with query parameters `path` (the log, validated as every tool's path is), `names` (one or more entries or field paths, comma separated), `start_time` and `end_time` (seconds, as the tools take them), `max_points` (optional; without it, every sample), and `format`: `columns` (the default) or `csv`. The response is the samples of each named entry over the window, independently timestamped, since entries are not aligned and aligning them is the reader's choice.

- **`columns`** is a small binary frame of the server's own: a magic (`WPDC`), a format version, a length-prefixed JSON header, then the columns. The header carries what a tool result would: the server version, `inputs` (the log, the entries, the window, the file's size and modification time), the time range, and per entry its name, type, sampling class (periodic, change-only, event), unit from the name where the name states one, the sample count, and the column layout. Columns are little-endian: timestamps as `int64` microseconds, exact as the log stores them; values as `float64`, `int64`, or `uint8` for booleans; a bucketed entry as `count`, `min`, `max`, `mean`, `first`, and `last` columns. String entries are not in the frame; the header lists them as skipped with the reason, and CSV or `search_strings` carries text. Counts are known from the index before a value is decoded, so the header is written first and the body streams, a few thousand records at a time, without holding an entry in memory.
- **`csv`** is one table per entry, `timestamp_sec,value` with a header line, concatenated with a blank line and a `# entry: <name>` comment between, the form the existing CSV export writes, so a script written for one reads the other.
- **Limits and errors.** A response is capped by a configured size (default 512 MB); a request over the cap is refused with a JSON body that gives the count, the size, and the hint to narrow the window or pass `max_points`, never silently cut. A missing entry is an error naming it, as a tool would. A file that changes during the stream is handled as a tool call is: the stream ends with a trailer that says so, and the reader discards it; the frame's header says a trailer may follow.
- **Caching.** `ETag` from the file's snapshot and the query, so the same request for an unchanged file is a `304`.
- **Who reads it.** The viewer, through its data client. An agent with shell access, by `curl` against the URL that `get_server_guide` reports under `data_endpoint` whenever the transport is HTTP, which gives a script every sample of an entry in one request; on a stdio server there is no endpoint, and the CSV export tool stays the way. A dashboard: this is the query endpoint the pit server plan leaves for Grafana (its §12), with `format=csv` or a `json` format added when Grafana's data source plugin asks for one.
- **Security.** The endpoint reads; it writes nothing. It is behind the `Origin` check, so no web page can fetch it, and behind the path validator, so it serves only files in the configured log directories; on the pit server it is under decision 7 of that plan, behind a team's proxy when they add one.

**`read_entry` at screen resolution.** For the assistant, which reads through MCP and cannot take a frame: a new optional parameter `max_points`. When the samples in the window number no more than it, the result is as today, exact. When they do, the window is divided into that many buckets of equal duration, and each bucket with samples returns `timestamp_sec` (its start), `count`, `min`, `max`, `mean`, `first`, and `last`; the result says `bucketed: true` with `bucket_sec`, and `total_in_range` stays the true count. The extremes are kept because a spike that a mean would hide is the thing a person is looking for. For a change-only entry, `first` and `last` let the viewer draw the holds correctly across a bucket. Struct fields and array elements go through the field paths as the exact read does; a non-numeric entry is read exactly and `max_points` is reported in `skipped` with the reason. The differential check recomputes `min`, `max`, and `mean` per bucket from the raw records of the entry the result names.

**`render_chart`.** Takes what `read_entry` takes, plus `entries[]` for several series, a `kind` (`time_series`, `histogram`, `scatter`, `field`), and a `width` and `height`. Returns MCP image content (a PNG drawn with the JDK's headless imaging and written by `ImageIO`, with no dependency; skipped with a note where the runtime lacks the headless toolkit), the chart specification as JSON (series, axes with units from the names, the phases, the window), and the numeric summary of what was drawn (per series the count, min, max, mean, and the window) with `inputs`, so a model that describes the chart describes numbers the tool returned. A change-only series is drawn as steps; the phases come from the Driver Station data; axes carry the unit the name states and nothing the name does not. The specification is what the explorer draws from, so the picture the assistant gets and the picture a person sees come from the same description; the specification carries a `vscode://TripleHelixProgramming.wpilog-analyzer/open?...` link that the extension's URI handler opens as the same chart, interactive, when the extension is installed.

### 7. Looking to asking

The editor offers "Ask about this selection": the current log's path, the selected entries, the window or selection, and the pane's kind become a prompt that names them and the tool the assistant would call first, and the prompt opens in the chat the user has. VS Code's own chat accepts a prefilled query through `workbench.action.chat.open`; for other assistants the prompt is copied to the clipboard with a message saying so. Which assistants can be opened with a prompt is an open question (§11); the clipboard always works. With no assistant configured, the command still produces the prompt, and the viewer is complete without it.

### 8. Pit server and live sessions

With `wpilog-mcp.pitServerUrl` set (the pit server plan's setting), the Logs view lists the pit server's sessions and the editor opens one by path through the pit server's tools, as it opens a local log through the local server's. An open session has a **follow** toggle: the window tracks the latest time, and the data client fetches each visible series from the last timestamp it holds, once a second, from the data endpoint, appending to the typed arrays in the webview; the console pane polls `search_strings` the same way. Mirrored sessions (pit server plan §11) open from the mirror when the pit server is unreachable, and the editor says which copy it is reading. Nothing here subscribes to the robot: the pit server is the one listener, by its own decision 6.

### 9. Testing

- **Pure modules**, on Node's test runner, beside the extension's existing tests: the tree models from recorded tool results, with struct leaves and filters; the request planner (window and pixel width to `max_points`, debounce and cache keys, the step rule from the sampling class); the chart specification builder; the prompt builder's text; CSV formatting with NaN and infinities as the tools report them; the manifest test, extended to pin the new settings' `order`, the explorer's commands, the views, and the custom editor's selectors.
- **The server process and the clients** against the real server JAR: the port line, initialize, a listing, a read, an error result, a crash and restart, a column frame read into typed arrays equal to the fixture's values, and a `304` on the second request, on Linux and Windows in CI, since the JAR is built there first.
- **The data endpoint**: the frame round-trips through a reader written from its description in the test (the header, each column type, a bucketed entry, a skipped string entry); every value and timestamp equals the fixture's, which the differential reader checks from the raw records of the entries the header names; `max_points` bucketing agrees with `read_entry`'s; a request over the size cap is refused with the count and the hint; a missing entry, a bad path, and a path outside the log directories are errors; a file rewritten mid-stream ends in the trailer and a reader discards the frame; the `ETag` changes when the file does and not otherwise; CSV matches the export tool's output for the same entry; a request carrying a browser `Origin` is refused as `/mcp` refuses it.
- **Server tools**: `read_entry` with `max_points` against fixtures whose values are functions of time, so each bucket's min, max, and mean are known exactly, at the boundaries (a window shorter than one bucket, a bucket with one sample, `max_points` of 1), on change-only fixtures for `first` and `last`, and through the conformance sweep and the differential check; `render_chart` for a decodable PNG of the requested size, a specification that names every series drawn, and a summary equal to `get_statistics` over the same window.
- **The webview in a real VS Code**: a smoke test with `@vscode/test-electron` (a development dependency), run in CI on Linux under `xvfb`, on the oldest supported VS Code and the current one: open a fixture log through the custom editor, wait for the webview to report ready, check that the entries tree lists the fixture's entries and that plotting one produces a request with the expected `max_points`. This is the first automated check of the extension inside VS Code; `doc/DEVELOPMENT.md` describes it and the `F5` development host beside it.
- **Windows**: paths built with the path API, the JAR found under both layouts, and the smoke test run on Windows once it is stable on Linux.

### 10. Milestones

Each leaves the repository building, tested, and releasable.

1. **Foundation** (§1, §2, §3): `--port 0` and the port line in the server; the server process and the MCP client over loopback HTTP from the existing server configuration; the Logs and Entries trees; a custom editor that opens a log and shows its entries and time range; the manifest test extended. Published as a pre-release.
2. **Plot** (§5, §6): the data endpoint and the data client, `read_entry` with `max_points` in the server; the plot panes with the bundled library, the timeline with phases and events, the cursor and readout, zoom and pan, and the statistics panel.
3. **Console, structs, field** (§4): the console pane with marks on the timeline; struct fields and array elements in the tree and the panes; the field view.
4. **REV** (§4): the REV pane and signals in the panes, with synchronization shown.
5. **Pit server** (§8): sessions in the Logs view, opening by path through the pit server, follow for an open session, the mirror's state.
6. **Charts for the assistant** (§6, §7): `render_chart`, the URI handler that opens its specification, and "Ask about this selection".
7. **A real VS Code in CI** (§9): the smoke test under `xvfb`, on both supported versions.
8. **One server per laptop** (decision 5): the viewer, VS Code's MCP registry, and Claude Code share one server process, through the server's named daemon or an HTTP definition the extension provides, chosen when the viewer's use has shown which fits.

### 11. Open questions

- Which assistants accept a prefilled prompt (§7). VS Code's chat does through its command; whether the Claude Code extension exposes an equivalent, and what Cursor's is, are to be checked, with the clipboard as the floor.
- A frame of the server's own against Apache Arrow's IPC format for the data endpoint (§6). Arrow is what analysis tools read natively, and its Java library is a large dependency with its own transitive set, against a format that is a hundred lines on each side and a CSV beside it for everything else. The proposal is the server's own frame, with Arrow as a second format if a reader that needs it appears.
- uPlot against drawing on a canvas directly for the plot panes (§5). The library's cursor, bands, and axes are a lot to rewrite; a hand-drawn plot would be smaller and entirely ours. The proposal is the library, revisited if its bundle or its API gets in the way.
- Whether the custom editor should claim `.revlog` on its own, or only open one beside its wpilog (§1, decision 8). The proposal claims both and shows a REV log alone with its signals on its own clock, marked as not synchronized.
- How much of the pit server's live data belongs here (§8) against in the pit server's own future web view. The proposal is follow and the console only, with the latest values and `wait_for_change` left to the assistant.
