# WPILog Explorer: Looking at the Data Without an Agent

A proposal for a second VS Code extension in this repository: a viewer for the logs wpilog-mcp reads, for the people who want to look at the data themselves. The first half is for anyone on the team; the second half specifies the work for the developers who will build it, in the form [PIT_SERVER_PLAN.md](PIT_SERVER_PLAN.md) uses.

## Part I: The Idea

### What it is

WPILog Analyzer gives an AI assistant the tools to analyze a robot's logs. WPILog Explorer gives a person the same view, in the same editor, with no assistant in between. Open a `.wpilog` file in VS Code and it opens as a log: the entries on the left, a plot in the middle, the match phases shaded behind the plot, the statistics of what is on screen beside it, and the robot's console below. Click an entry and it plots; drag across the plot and the statistics follow the selection; type in the console pane and the matching lines appear with their time on the plot.

It is built from the same server the analyzer bundles, and it calls the same tools the assistant calls. So the number a student reads off the statistics panel is the number the assistant would have reported, computed by the same code from the same file, with the same data quality beside it. A question that starts by looking can continue by asking: with the analyzer installed, a selection becomes a prompt, already naming the file, the entry, and the time window, so nothing has to be retyped.

It reads REV logs beside the robot's log, on the robot's clock, as the server already synchronizes them. With a pit server on the team's network (see the pit server plan), it lists the team's sessions, follows a live session as it is recorded, and shows which sessions are mirrored on this laptop for the trip home.

### Why

- **Not everyone wants to ask.** A mentor who has read logs for years wants to see the trace, not describe it. A student learning what a brownout looks like learns it from the plot. The analyzer serves the question; the explorer serves the look.
- **Looking and asking belong together.** Today the look happens in another program and the question in VS Code, and the file, the entry, and the time window are carried between them by hand. Here the plot and the prompt share a selection.
- **One set of numbers.** A viewer with its own statistics and its own notion of a match phase disagrees with the assistant in small ways that cost an afternoon. The explorer shows what the server computes, so there is one answer.
- **The code is in the same window.** The log explains the code and the code explains the log, and VS Code already holds the code.

### What it is not

- **Not AdvantageScope.** No 3D field, no video synchronization, no live connection to the robot of its own, no replay. AdvantageScope is excellent at those and stays the tool for them. The explorer is a log reader that lives where the code and the assistant live, and that shows the assistant's numbers.
- **Not a dashboard.** It reads logs. It does not talk to the robot and cannot change anything on it.
- **Not a second analyzer.** It adds no analysis of its own. Every number it shows comes from a tool the assistant can call, and anything it needs that the tools lack is added to the server as a tool, so the assistant gains it too.

### How it works, in one picture

```
  VS Code
  ┌───────────────────────────────────────────────────────────────────────┐
  │  WPILog Explorer (extension host, TypeScript)                         │
  │    tree views: logs, entries, pit server sessions                     │
  │    custom editor for .wpilog and .revlog: the webview                 │
  │    the MCP client: calls tools, hands results to the webview          │
  │        │ stdio                              │ HTTP (extension host)   │
  │        ▼                                    ▼                         │
  │  wpilog-mcp server (bundled JAR)       pit server (by URL)            │
  │                                                                       │
  │  WPILog Analyzer (optional, beside it): "ask about this selection"    │
  └───────────────────────────────────────────────────────────────────────┘
```

The webview draws; it never opens a network connection. The extension host is the only MCP client, and it talks to the bundled server over stdio exactly as the analyzer does, or to a pit server over HTTP.

### Where it stands

The server has nearly everything the viewer needs: the listing, entries with struct field paths, values over a time window, statistics with data quality, match phases, the Driver Station timeline, console search, and REV signals synchronized to the robot's clock. Two things are missing on the server side and are added as tools, so the assistant gets them too: reading an entry at screen resolution (a bucketed read, with the extremes kept), and rendering a chart. The extension itself is new, and the build, the version check, CI, and the release workflow each learn that the repository holds two extensions.

## Part II: Specification

What follows is for the developers. It follows `CLAUDE.md` and the design principles in [ARCHITECTURE.md](ARCHITECTURE.md), and the extension conventions the analyzer established: TypeScript against the VS Code API, no runtime npm dependencies, logic that needs no VS Code API in pure modules with tests on Node's test runner, and `extension.ts` as the glue.

### 1. Decisions

1. **Two extensions, one repository, one version.** The explorer lives in `vscode-explorer/` beside `vscode-extension/` (the analyzer), with its own `package.json`, README, icon, and Marketplace listing (`TripleHelixProgramming.wpilog-explorer`, display name "WPILog Explorer"). Both carry the project version; `syncExtensionVersion` writes it into both, `ExtensionVersionTest` checks both, and the release workflow packages and publishes both. Each bundles the server JAR (under 3 MB) and stands alone: neither declares the other as a dependency.
2. **The webview never touches the network.** The extension host is the MCP client. It launches the bundled server over stdio, as the analyzer does, and reaches a pit server over HTTP. The webview receives results by `postMessage` and sends requests the same way. This keeps the server's `Origin` check as it is (a webview's origin is `vscode-webview://<id>`, which the check rightly refuses), needs no CORS beyond what exists, and keeps the server unchanged for the viewer.
3. **Every number on screen comes from a tool.** The explorer computes no statistic, no phase, no synchronization of its own. What the tools lack is added to the server as a tool, with the tool checklist, the description tests, the conformance sweep, and the differential check, so the assistant can call it too. The first two are `read_entry` at screen resolution and `render_chart` (§6).
4. **Shared code is shared source.** TypeScript the two extensions both need (finding Java, finding the JAR, log directories, the stdio MCP client) lives in `vscode-common/src/` and is compiled into each extension by its own `tsconfig`, which includes the common directory. No shared package, no runtime dependency, no copying step. The analyzer's modules move there in the same change, with their tests.
5. **Settings are the explorer's, with the analyzer's as the fallback.** The explorer contributes `wpilog-explorer.*` settings for the log directories, team number, Java path, WPILib year, heap, and pit server URL. Where one is unset and the analyzer is installed, the analyzer's `wpilog-mcp.*` value is used, so a laptop with both set up once has the explorer configured. The resolution is a pure function with tests.
6. **One server process per explorer, for now.** The explorer runs its own stdio server for the life of the extension, as the analyzer runs one per MCP client. A shared daemon for every client on a laptop (IDEAS 9.2) is a later milestone (§10), once the explorer exists to share it.
7. **A chart is drawn by a library bundled as a static asset.** The rule against runtime npm dependencies is about the Node side of the extension, which stays dependency-free; the webview bundles one charting library as a file under `media/`, with its license, chosen for size and for drawing hundreds of thousands of points without strain (§5). Its version is pinned in the repository, not fetched at build.
8. **Opening a log opens the explorer.** The extension registers a read-only custom editor for `*.wpilog` and `*.revlog`, so a double-click in the Explorer pane opens the viewer; "Open With" still offers the hex editor. Nothing the explorer does writes to a log or to a log directory.

### 2. Layout and wiring

| Path | What it holds |
|---|---|
| `vscode-explorer/package.json` | `wpilog-explorer`: the custom editor, the views, the commands, the settings with their `order` |
| `vscode-explorer/src/extension.ts` | The VS Code glue: activation, the server process, the views, the editor provider, the commands |
| `vscode-explorer/src/*.ts` | Pure modules: settings resolution, the tree models, the request planner for the plot, the chart specification, the prompt builder, CSV formatting |
| `vscode-explorer/media/` | The webview: its HTML, CSS, scripts, and the bundled charting library with its license |
| `vscode-explorer/server/` | The bundled JAR, copied by `bundleExtension`; ignored by git as the analyzer's is |
| `vscode-common/src/` | Shared modules: `javaFinder`, `jarManager`, `logDirectories`, the stdio MCP client, result parsing |
| `vscode-common/src/test/` | Their tests, run by either extension's `npm test` |

Gradle: `syncExtensionVersion` edits both package files; `bundleExtension` copies the JAR into both `server/` directories; `buildExtension` packages both `.vsix` files, each named by its extension; `installExtension` installs both. The CI workflow runs `npm ci && npm test` in both extension directories on Linux and Windows. The release workflow packages both, attaches both to the release, and publishes both to the Marketplace with the one `VSCE_PAT`, under the same rules (never a suffixed version, never a version twice). `doc/DEVELOPMENT.md` gains the second extension in its extension, test, and release sections.

### 3. The MCP client in the extension host

A small stdio client in `vscode-common`: start the JVM with the arguments the analyzer builds (`-Xmx`, the JAR, the log directories, `-team`, `-diskcachedir` under the explorer's own global storage), send `initialize`, then `tools/call` requests with ids, read newline-delimited responses, and surface a tool's result as the parsed JSON of its text content, with `isError` turned into a rejected promise. Requests are serialized per connection, as the stdio transport expects one message at a time; the client queues. The server is started on first use and restarted on crash with backoff, and its stderr goes to an output channel "WPILog Explorer". For a pit server, the same interface over HTTP with the `Mcp-Session-Id` header, from the extension host, where the response headers are readable.

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
- **Status**: the server's state in the status bar (starting, ready, restarting), and for a pit server its reachability and the mirror's age, as the pit server plan's extension section specifies.

### 5. The plot and its data

**Resolution.** A 50 Hz entry over a ten-minute shop session is thirty thousand points, which a canvas draws without trouble; a 1 kHz entry over an hour is not. So the webview asks for what it can show: for each series and the visible window it requests `read_entry` with `max_points` set to a few times the pane's width in pixels (§6), re-requested on zoom and pan after a short debounce, and cached by entry, window, and resolution, so panning back costs nothing. A bucketed response is drawn as a band (minimum to maximum) with the mean as the line, and the readout says the cursor is on a bucket and gives its range. An exact response is drawn as points joined by lines, or by steps for change-only sampling.

**The library.** uPlot (MIT, about 50 KB, canvas, built for large time series) is the proposal: it draws steps, bands, multiple y axes, and a synchronized cursor across panes, and it has no dependencies. It is bundled under `media/vendor/` with its license file; the webview's content security policy allows scripts only from the extension's own resources by nonce, and no remote content. The field view and the timeline are drawn directly on a canvas, which is simpler than bending a plotting library to them.

**Theme.** Colors come from VS Code's theme variables, so the viewer follows light, dark, and high contrast themes; series colors are a fixed palette chosen to stay distinct on both backgrounds, with the phase shading at low opacity behind.

**Time.** The x axis is seconds from the log's start, as every tool reports time, so a value a person reads off the plot is the value they would pass to a tool. Where the log carries wall-clock time (`systemTime`), a toggle labels the axis with it as well.

### 6. Server additions

Two tools, each through the tool checklist in `doc/DEVELOPMENT.md`, documented in `doc/TOOLS.md`, called in the responses scenarios file, and added to the stress tests.

**`read_entry` at screen resolution.** A new optional parameter `max_points`. When the samples in the window number no more than it, the result is as today, exact. When they do, the window is divided into that many buckets of equal duration, and each bucket with samples returns `timestamp_sec` (its start), `count`, `min`, `max`, `mean`, `first`, and `last`; the result says `bucketed: true` with `bucket_sec`, and `total_in_range` stays the true count. The extremes are kept because a spike that a mean would hide is the thing a person is looking for. For a change-only entry, `first` and `last` let the viewer draw the holds correctly across a bucket. Struct fields and array elements go through the field paths as the exact read does; a non-numeric entry is read exactly and `max_points` is reported in `skipped` with the reason. The differential check recomputes `min`, `max`, and `mean` per bucket from the raw records of the entry the result names.

**`render_chart`.** Takes what `read_entry` takes, plus `entries[]` for several series, a `kind` (`time_series`, `histogram`, `scatter`, `field`), and a `width` and `height`. Returns MCP image content (a PNG drawn with the JDK's headless imaging and written by `ImageIO`, with no dependency; skipped with a note where the runtime lacks the headless toolkit), the chart specification as JSON (series, axes with units from the names, the phases, the window), and the numeric summary of what was drawn (per series the count, min, max, mean, and the window) with `inputs`, so a model that describes the chart describes numbers the tool returned. A change-only series is drawn as steps; the phases come from the Driver Station data; axes carry the unit the name states and nothing the name does not. The specification is what the explorer draws from, so the picture the assistant gets and the picture a person sees come from the same description; the specification carries a `vscode://TripleHelixProgramming.wpilog-explorer/open?...` link that the explorer's URI handler opens as the same chart, interactive, when the explorer is installed.

### 7. Looking to asking

With the analyzer installed (`vscode.extensions.getExtension` finds it), the editor offers "Ask about this selection": the current log's path, the selected entries, the window or selection, and the pane's kind become a prompt that names them and the tool the assistant would call first, and the prompt opens in the chat the user has. VS Code's own chat accepts a prefilled query through `workbench.action.chat.open`; for other assistants the prompt is copied to the clipboard with a message saying so. Which assistants can be opened with a prompt is an open question (§11); the clipboard always works. Without the analyzer, the command is absent, and the viewer stands alone.

### 8. Pit server and live sessions

With `wpilog-explorer.pitServerUrl` set (falling back to the analyzer's), the Logs view lists the pit server's sessions and the editor opens one by path through the pit server's tools, as it opens a local log through the local server's. An open session has a **follow** toggle: the window tracks the latest time, and the webview polls `read_entry` for each visible series from the last timestamp it holds, once a second, appending; the console pane polls `search_strings` the same way. Mirrored sessions (pit server plan §11) open from the mirror when the pit server is unreachable, and the editor says which copy it is reading. Nothing here subscribes to the robot: the pit server is the one listener, by its own decision 6.

### 9. Testing

- **Pure modules**, on Node's test runner, in both the common directory and the explorer: settings resolution with and without the analyzer's values; the tree models from recorded tool results, with struct leaves and filters; the request planner (window and pixel width to `max_points`, debounce and cache keys, the step rule from the sampling class); the chart specification builder; the prompt builder's text; CSV formatting with NaN and infinities as the tools report them; the manifest test pinning the settings' `order`, that every command a description links to exists, and the custom editor's selectors.
- **The stdio client** against the real server JAR: initialize, a listing, a read, an error result, a crash and restart, on Linux and Windows in CI, since the JAR is built there first.
- **Server tools**: `read_entry` with `max_points` against fixtures whose values are functions of time, so each bucket's min, max, and mean are known exactly, at the boundaries (a window shorter than one bucket, a bucket with one sample, `max_points` of 1), on change-only fixtures for `first` and `last`, and through the conformance sweep and the differential check; `render_chart` for a decodable PNG of the requested size, a specification that names every series drawn, and a summary equal to `get_statistics` over the same window.
- **The webview in a real VS Code**: a smoke test with `@vscode/test-electron` (a development dependency), run in CI on Linux under `xvfb`, on the oldest supported VS Code and the current one: open a fixture log through the custom editor, wait for the webview to report ready, check that the entries tree lists the fixture's entries and that plotting one produces a request with the expected `max_points`. This is the first automated check of either extension inside VS Code; `doc/DEVELOPMENT.md` describes it and the `F5` development host beside it.
- **Windows**: paths built with the path API, the JAR found under both layouts, and the smoke test run on Windows once it is stable on Linux.

### 10. Milestones

Each leaves the repository building, tested, and releasable.

1. **Scaffold** (§1, §2, §3): the second extension, the common directory with the analyzer's modules moved into it, the version sync and its test, CI, and the release workflow for two extensions; the settings and their resolution; the stdio client; the Logs and Entries trees; a custom editor that opens a log and shows its entries and time range. Published as a pre-release.
2. **Plot** (§5, §6): `read_entry` with `max_points` in the server; the plot panes with the bundled library, the timeline with phases and events, the cursor and readout, zoom and pan, and the statistics panel.
3. **Console, structs, field** (§4): the console pane with marks on the timeline; struct fields and array elements in the tree and the panes; the field view.
4. **REV** (§4): the REV pane and signals in the panes, with synchronization shown.
5. **Pit server** (§8): sessions in the Logs view, opening by path through the pit server, follow for an open session, the mirror's state.
6. **Charts for the assistant** (§6, §7): `render_chart`, the URI handler that opens its specification, and "Ask about this selection".
7. **A real VS Code in CI** (§9): the smoke test under `xvfb`, on both supported versions.
8. **One server per laptop** (decision 6): the analyzer and the explorer share one server process when both are installed, through an API the analyzer exports or the server's named daemon, chosen when the explorer's use has shown which fits.

### 11. Open questions

- Which assistants accept a prefilled prompt (§7). VS Code's chat does through its command; whether the Claude Code extension exposes an equivalent, and what Cursor's is, are to be checked, with the clipboard as the floor.
- uPlot against drawing on a canvas directly for the plot panes (§5). The library's cursor, bands, and axes are a lot to rewrite; a hand-drawn plot would be smaller and entirely ours. The proposal is the library, revisited if its bundle or its API gets in the way.
- Whether the custom editor should claim `.revlog` on its own, or only open one beside its wpilog (§1, decision 8). The proposal claims both and shows a REV log alone with its signals on its own clock, marked as not synchronized.
- Whether the explorer's disk cache should be shared with the analyzer's, so a REV synchronization computed by one serves the other. The cache is keyed by content, so sharing is safe; the directory is the question. The proposal is separate caches until milestone 8 brings one server.
- How much of the pit server's live data belongs here (§8) against in the pit server's own future web view. The proposal is follow and the console only, with the latest values and `wait_for_change` left to the assistant.
