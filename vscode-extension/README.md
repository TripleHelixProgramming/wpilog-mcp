<table>
<tr>
<td width="180" valign="top" align="center">
  <!-- The leading <br> balances the h1's top margin, so the icon sits midway between the cell's top edge and the title. -->
  <p align="center"><br><img src="images/icon.png" alt="WPILog Analyzer icon" width="140" align="top"></p>
  <h1 align="center">WPILog Analyzer</h1>
</td>
<td valign="middle">

AI-powered FRC robot log analysis for VS Code. The extension analyzes `.wpilog` telemetry files from the roboRIO to help diagnose brownouts, CAN errors, swerve drive issues, loop timing problems, and more.

The extension gives an AI assistant tools to analyze robot logs, and lets you explore the same data without an assistant. Copilot and other VS Code agents use the shared server directly; Claude Code uses its bridge. WPILog Explorer provides the Logs and Entries views and the log editor.

</td>
</tr>
</table>

## Quick Start

1. Install **WPILog Analyzer** and accept its offer to install the server.
2. Put your `.wpilog` files in `~/riologs`.
3. Open your robot project in VS Code. Click **WPILog Explorer** in the activity bar to browse your logs, or ask your assistant “What logs are available?”
4. With Claude Code, approve `wpilog-analyzer` when asked. Restart an existing Claude Code session after registration.

Most users need no configuration changes. Keep the robot project open: the assistant can then read the code that gives logged signals their meaning. A team number and a [Blue Alliance key](#the-blue-alliance-api-key) add match data; neither is required to read logs.

## Requirements

- Java 17 or later; the WPILib toolkit includes a suitable JDK.
- VS Code 1.101 or later. An MCP-capable assistant is optional; the explorer works without one.

## Install

Find **WPILog Analyzer** in VS Code's Extensions view, or on the [Marketplace](https://marketplace.visualstudio.com/items?itemName=TripleHelixProgramming.wpilog-analyzer). For a test build, download its `.vsix` from [Releases](https://github.com/TripleHelixProgramming/wpilog-mcp/releases) and choose **Install from VSIX…** in the Extensions view, then reload the window.

The [standalone installer](../doc/STANDALONE.md#trying-a-pre-release) can install both the server and the matching extension from one release. It installs into WPILib's VS Code, asking which season when it finds several. WPILib VS Code and a VS Code installed apart from it keep separate extensions; to install into the other one, add `--vscode system` ([details](../doc/STANDALONE.md#the-matching-extension)).

For the prepared development build, use `--tag v0.10.0-dev5 --with-extension` after that tag is
published on Releases; until this version is released, use the installer from the `development`
branch as the [pre-release instructions](../doc/STANDALONE.md#trying-a-pre-release) show.
`--pre-release --with-extension` selects the newest published build instead.
Registration remains at user scope; a pre-release does not restore project `.mcp.json` entries.

## How It Works

Selecting a local connection starts the standalone install's `http` server on demand. Activation and collapsed connection roots do not start it. VS Code's agents, the explorer, Claude Code, and other local clients share its loaded logs and cache. The launcher starts it on demand and joins it when it is already running; no operating-system service is installed.

Each window that uses a local connection registers its User log directories and open projects' directories with the server. These are **leases**: every client can read them while the window's MCP session lives. Settings, key, and folder changes replace the lease without restarting the server. Closing or reloading a window ends its session, waiting up to three seconds, which releases its lease and its views; another window or a terminal bridge can keep the same directories available. A view held by a window that closed without ending its session moves to the window asking for it two minutes later; a window that is hung but still connected keeps its views, and another window can open a copy. The client re-registers after a daemon restart and keeps its session alive while the window is open.

The server's permanent settings live in `~/.wpilog-mcp/servers.yaml`. **VS Code's Settings UI does not read or edit that file.** It supplies the window's directories and team through the lease. Edit YAML for permanent directories, port, cache, or idle policy. The installer creates an absent configuration and preserves an existing one.

**WPILog Analyzer: Show Server Log** opens `~/.wpilog-mcp/logs/http.log`; **Restart Server** restarts the shared daemon. With `idle_exit_minutes` set in YAML, it exits after that long with no client and no request; the installed configuration does not set it, so the server keeps running. An import in progress also holds the server open.

## Exploring Logs

The **Logs** view shows each directory's origin (`configured` or `leased`) and team when known. Plain directories group logs by event and date; stores group them by robot and session. Filter by name, event, or match; click a log to open it, or right-click to reveal it or copy its path. Double-clicking a `.wpilog` file opens the same editor. A file outside the server's directories is refused; add its directory in Settings or import it through a store's inbox.

The editor shows the time range, entries, sample counts, and any truncation note. The **Entries** view follows the active log. Use **Group by** to switch between **Path** (the default name hierarchy) and **Data type**. Type groups use the exact logged types, such as `double`, `boolean`, `double[]`, and `struct:Pose2d`, with entry counts and full paths beneath them; the choice is remembered for the workspace. Type in its inline filter to search full entry names, including entries inside unopened groups; matching groups open to show the results. The filter icon focuses that field, and its clear button restores the full listing. **Collapse All Entries** closes every branch without clearing the filter. Arrow keys navigate and expand the tree; Enter activates an entry. Expand a struct or array to its numeric fields and elements, then click to plot; the row's **Details** button shows its metadata and **Plot every element** plots up to sixteen elements at once.

- **Plots:** drag to zoom, roll the wheel to zoom about the cursor, shift-wheel or arrow keys to pan, and double-click or Home to reset. Panes share a cursor and readout. Change-only signals use steps. Up to four million samples per series are loaded at once; longer series use min/max buckets with a mean line until zoomed in. Chips show the server's decode warnings and statistics for the visible window.
- **Timeline:** Driver Station mode segments and events come from the log. Drag a region to select a time window.
- **Console:** filter text by pattern, level, and window. Repeats collapse with their count; clicking a line moves the shared cursor.
- **Field:** the logged pose is drawn on the season's field outline, colored by time. The server's signal resolver selects a documented convention or asks you to choose among candidates.
- **Data:** select or plot entries, then choose **Data view of selected entries**. Perspective receives the same exact Arrow samples as the plots, with struct fields as columns. Its built-in controls supply filtering, sorting, grouping, pivots, quick charts and CSV export. Narrow the window if the exact-data request exceeds the endpoint cap.
- **REV:** signals appear by bus and device with their synchronization method, confidence, and offset. They plot on the wpilog's clock; a bus that could not be synchronized is explained and not plotted.

The extension host calls the same tools as the assistant and fetches samples through the [data endpoint](../doc/STANDALONE.md#the-data-endpoint). The webview fetches only bundled extension resources for its static Perspective engine, viewer, datagrid and charts, including WebAssembly and a local-source worker. Log requests stay in the extension host; no remote scripts, fonts or data are admitted by its content security policy.

**WPILog Explorer: Open in Notebook**, in the editor title and context menu, writes an nbformat 4 notebook beside a writable local log, otherwise in the workspace. It contains three cells: Arrow requests into data frames (with a Polars alternative in a comment), plotting, and the path/entries/window with the server's inputs. Each selected entry has its own stream to preserve its type. VS Code opens its notebook editor; install Python, pyarrow, pandas and matplotlib in your chosen kernel. Nothing Python is bundled, and kernel selection and missing-kernel messages belong to VS Code.

`render_chart` returns time-series, histogram, scatter or field PNGs and an `open_url` such as `vscode://TripleHelixProgramming.wpilog-analyzer/open?path=…&entries=…&start=…&end=…&kind=…`. Repeated `entries` select several series. The handler uses the Logs route to validate the path on the server, applies the entries/window/pane, and refuses bad numbers. A link grants no directory access and executes no code.

**WPILog Explorer: Ask about this selection**, in the editor title and context menu, sends the path, selected entries, window and pane kind to VS Code chat. It names `get_statistics` first for a plot selection, `search_strings` for console text, or `render_chart` for a pane. If chat cannot be opened, the prompt is copied to the clipboard with a notice. Prefilling other assistants remains an open question; paste is the portable route.

### Recorded visualization preview

Choose **WPILog Explorer: Open Source Overview (Preview)** on a local WPILOG in Logs, or
**Reopen Editor With → WPILog Source Overview (Preview)**. Ordinary opening still uses the
existing Explorer editor. The overview shows catalog facts first, then the indexed duration,
signal inventory and completeness; unavailable facts are labeled, never guessed from signal names.
**Blank layout** opens an independent working view. Add a plot, value table, statistics panel or raw table, select signals
in **Add signals**, and use each panel's **Move, resize or remove panel** controls with Tab/Enter
or drag its header and resize its edge. Panel titles are editable. Title, size, position and style
edits keep the current samples; series/range changes and added or removed display copies start a new admission and data query. The Entries target label names the current view and panel; a click adds to that target, or creates a view from the overview.

**Recorded time and cursor** sets a shared time window or reads the last logged value at or
before a cursor. Equal timestamps choose the last physical record; values are not interpolated.
Plots use the server's existing Arrow samples or min/max/mean buckets. Their point allowance
comes from the shared window budget and number of signals and plot copies, up to 65,536 points
per signal; a larger allowance permits more exact samples before reduction. Value tables use
original typed samples and show their timestamps and skipped-record counts. The preview limits
a view to 32 series, with no hidden-view data requests and explicit cancellation of stale replies.
Hidden recorded views clear retained samples after 30 seconds.

Use **Earlier time** / **Later time** to pan by half a window, or **Zoom in** / **Zoom out**
to halve/double its width (Tab/Enter works). A horizontal drag in a linked time-series plot
also selects history. Each gesture saves and re-reads the interval; **Inspect History** shows
the pinned bounds, and arriving data cannot move them. The explicit time controls apply to the
whole view; histogram/scatter axes are values, not time. Navigation resumes a paused display.
For an active recording, **Resume following** restores the visible 1–300-second window choice.
That choice survives editor restoration as UI state; a newly opened fixed view starts at 30
seconds. Full recording is available for files and finalized recordings; requesting it after
finalization reads earlier samples even if the saved policy was already full while following. Historical recording
plots use server min/max bands and means over the whole admitted interval, with original-sample
counts and an independent prefix. The bucket count fits the shared byte allowance (at most
4,096); zoom in for detail. A limit produces an explained refusal instead of a truncated history. A statistics
window with no finite samples shows a hint and refreshes on the next completed recording
prefix. New data needs no manual Retry; invalid selectors and resource refusals still do.


An open manifested recording now has an inspection-only overview with member count, declaration
conflicts and explicit clock basis. Signal discovery uses bounded pages: enter a literal name
search or exact declared type, then **Apply filter** (Enter works). **Previous** and **Next**
replace the rows; counts include the true matching total. **Show unavailable declarations only**
switches to an exclusive, unsearchable list of bounded previews. Name/type inputs are disabled
and their filters omitted until that mode is turned off; the ordinary filters are retained.
**Inspect** reads fresh member counts, metadata and required schemas for the exact selected name.
**Refresh signals / Retry** replaces the frozen inventory and revalidates the selection; a
visible overview also refreshes at its five-minute absolute expiry. A failed refresh keeps
readable old rows with their timestamp and reason, and Retry has a bounded backoff. Known-expired
rows stay disabled until a replacement page is admitted. Hiding or closing cancels initialization
and releases the inventory and row buffers; reveal opens a new snapshot with the filter
and exact-name selection retained. No heartbeat keeps a hidden inventory alive. Older servers
without paged discovery show an update hint instead of downloading the full recording inventory.
**Blank layout**, template opening and Resume/Open now enter the connected recording-panel preview.
Add a plot, value table, statistics, raw, histogram or scatter panel, then use **Recording signals**, **Inspect** and **Add inspected
signal to target panel**. For a numeric leaf, enter an exact path such as `.translation.x` or
`[2]` in **Numeric field or array index**, then **Check and add field** (Enter also works).
The recorded schema is visible under Inspect; the picker does not infer paths. The server
checks every member before adding it, and a refusal shows the member reason without changing
the layout. Wildcards and nonnumeric fields are refused. The whole-entry button remains
separate. Field checks cancel when another gesture replaces the intent or the view hides/closes.
The server binds the exact working revision and supplies 1 MiB queried follows. Presentation edits retain data; signal/time edits affect only that view. **Pause display**
keeps acquisition running, while hiding cancels it and clears buffers after 30 seconds. Reveal
rebootstraps the same recording, and finalization drains and stops polling.

The displayed prefix and recorded clock evidence are explicit. Follow plots keep 4,096 recent samples
per selection; trimming, equal-time collapse and non-representable values are disclosed. Value
tables show the server's timestamp-latest value, or the last original sample at/before an
explicit cursor, with exact integer text, member and ordinal. **Add statistics** reads original
samples across members. Active statistics require a 1–300-second window; finalized views can
use **Full recording** under the server limits. Every answer has expandable **Independent
analysis prefix** evidence; it may be newer than the plot and is never calculated from plotted
samples. Automatic refreshes coalesce at most once per second; one analysis runs per window.
**Pause display** freezes analysis and disables cursor queries while acquisition continues.
Hide/close cancels reads; hidden retained data clears after 30 seconds. An explained permanent
refusal stays until **Retry recording** or a changed query; busy admission retries automatically.
Raw tables keep 256 recent rows with member/ordinal identity and bounded decode diagnostics.

**Add histogram** and **Add scatter** work for files and recordings. Histograms display the
server's bins with exact edges/counts and per-signal summaries; multiple signals overlay
independent distributions. Scatter takes two numeric selections, first X then Y; **Swap X and
Y** changes the persisted order. It shows exact-time pairs and full matched/unpaired counts.
**First pairs** / **Next pairs** replace a page of at most 256 pairs, reduced by the window
allowance. Each read has fresh prefix evidence; pages are not accumulated. Automatic refresh
keeps the current offset, so use First pairs if a changing window leaves it empty. Charts share
the analysis queue, one-Hz cadence, original-sample semantics and 1–300-second active interval.
The linked time cursor does not move their value axes. Pause disables chart paging/refresh;
**Refresh chart / Retry** retries errors without restarting the follow. Tables, diagnostics and
prefixes remain readable beside the bundled plots.
Recorded samples, live buffers and paged inventory share one conservative decoded allowance
per window. Set **WPILog Mcp › Visualization: Decoded Buffer MiB**
(`wpilog-mcp.visualization.decodedBufferMiB`) from 16 to 1024 MiB, default 64, then reload the
window. The preview displays the effective allowance and reports it in server capabilities as
a client-reported fact. Encoded acquisition has a separate fixed 64 MiB allowance. Admission
refuses before a sample download if it cannot fit; use fewer signals, close another view, or
increase the setting and reload. These are accounted buffer limits, not a total JavaScript heap
measurement. Remote sources and the full performance gate remain pending.
See the [recording-panel workflow](../doc/OPERATIONS.md#52-recording-panel-preview) for limits and remedies.

Draft gestures are persisted on the local server, with a local recovery journal; drafts do not
trigger native Save prompts. **Save view…** asks for a title in the connected server's library,
without selecting or creating a log store. Ephemeral servers explain why durable saves are
unavailable. It clears draft undo history;
subsequent edits use native Save/Undo/Redo. Retrying a lost save response recovers its original
checkpoint and preserves newer edits as dirty; Save again to checkpoint them. Native **Save As** still refuses with a **Save view…**
hint. **Duplicate and Open Beside** creates an independent UUID; native Split moves the same view.
The overview lists Saved views and Unsaved views, including a detached draft's 30-day expiry.
Unreadable/newer saved files are reported beside the readable views and left intact. A metadata
budget limit produces a bounded page; **Next saved-view page** advances and **First saved-view
page** restarts the listing, without blocking an already known saved view.
Saved views open as new working copies. A named server with durable state restores working UUIDs
and configurations after restart; ephemeral state shows its restart warning. Unavailable files or a different owner are explained instead
of being silently substituted. For a file outside a store, identity checks file attributes, not
content: a rewrite preserving the fingerprint is not detected. Library views retain owner-local source locators independently of working drafts. Reopening
still rechecks current directory grants and the original fingerprint.

**Reusable templates** searches built-in and server-library names/descriptions, with **Next template page** for further matches. Select one to see
compatibility reasons, then **Open new view** to keep the current view. **Replace this layout…**
offers Save or Discard before replacing it. Missing exact names stay unresolved; explicit roles
use the shared resolver. Unit mismatches refuse the binding; unknown units remain a stated
assumption and no conversion is performed. **Signal bindings and units** shows the details.
The built-ins cover Robot power, Driver Station state and User-code timing; timing uses the
published user-code duration, never the full-loop clock.

**Save template…** uses exact entry names by default; retaining role selectors is an explicit
choice. **Update selected template…** compares the selected revision. Built-ins are immutable,
and changing a library template never changes an existing view's configuration. Export/import
shares only definition JSON with an RFC 8785 digest. Reimporting identical content is a no-op;
a conflicting identity/revision is refused. For a newer known revision, **Import copy** is the
default and **Update existing** explicitly compares the current revision. Templates live in a
writable store; creating that store does not enable ingestion.

Statistics use the server's existing statistics tool over original samples in the selected
interval, with inputs/quality details beside the numbers. Raw tables show physical entry
ordinals and original integer timestamps, including duplicates/backward times. Each page has
at most 256 records in Explorer (the API permits 4,096), is bounded to 1 MiB and replaces the
previous page; First/Next page do not accumulate the entire log. Numeric struct/array projections
use the existing field-path owner; nonnumeric fields and wildcards remain unsupported in this
preview. Use the existing editor for those projections.

Local visualization MCP edits notify the attached view over the session control stream. A stale
revision refuses; a pending local gesture remains available for comparison. Notifications carry
no samples and request no popup. Hidden views accept configuration changes without requesting
data, then reload their interval on reveal. Older unmanaged fingerprints appear as unavailable
views rather than disappearing; their configuration remains inspectable through MCP.

This preview supports local recorded WPILOGs and manifested recordings; the full Stage 3 gates remain open. Keep using the existing editor
for specialized field, console, REV, notebook and Advanced Data workflows. See
[development checks](../doc/DEVELOPMENT.md#visualization-recorded-preview) for the isolated
real-service/editor fixture and its remaining platform gates.

## A Data Store That Follows Your Folders

Continuous folder imports are experimental and disabled by default. To test them, enable
`experimental_data_store` on the named server and restart it, as described in
[Keeping a data store up to date](../doc/STANDALONE.md#keeping-a-data-store-up-to-date).
The setup offer and the five data-store commands are hidden until the server enables the feature.
Disabling it preserves existing store files and the saved policy; it stops policy loading,
background imports and the preferred-store view. Manual Organize Logs and one-time imports
remain available.

When enabled and loose logs are available, the explorer offers **Set Up Data Store**, **Not now**, or
**Don't offer again**. **WPILog Explorer: Set Up Data Store…**, in the command palette and Logs
view menu, also starts setup explicitly. Choose a local destination and review the source
folders. Existing server directories and your User log-directory settings are checked initially;
project, session and temporary folders require an explicit choice. You can add another local
source with the folder picker. Folders configured later are never enrolled automatically.

For each source, choose **Copy and keep originals** or **Move finished files**. Copy is the first
choice. Move requires confirmation that the source contains only finished files and that no
program writes logs there; it removes an original only after the server verifies its placement.
Duplicate content is reported as present and the duplicate source remains. Sources cannot be
inside the destination or another store, and overlapping enrollments need one chosen owner. A
store created inside a source is excluded from that source's scan.

The final review names every source, its mode and the destination. Cancelling before **Save and
Start Syncing** changes nothing. Saving creates one persistent policy for the named shared server,
not a separate watcher for each window or profile. It runs after VS Code closes while the server
runs, resumes when the server restarts, and does not rewrite your User settings or `servers.yaml`.
Another profile sees the same setup; a conflicting edit is refused for review rather than
silently replacing it.

Logs shows separate connection roots and source scopes. MCP listings stay within the chosen
`scope_id`, and ask for a choice when several roots exist. **Manage Data Store…** reviews sources,
pauses or resumes synchronization, and requests a scan. Its **Show source status** item reports
per-source states and reasons, writes the first 100 file receipts to the output channel, and
reports the total and paging parameters when more exist. **Sync Data Store Now** and **Pause or Resume Data Store Sync**
also have their own commands. Under the local connection only, the **Folder synchronization**
group reads local-ingestion status and file receipts to report pending/refused files with their
reasons, when a destination store is configured. Remote connections have no local-ingestion controls. Existing files remain readable while synchronization is paused. Direct log
paths and the experimental reader library's explicitly supplied roots keep their usual behavior.

## Organizing Your Logs

For a one-time operation, **WPILog Explorer: Organize Logs…** remains available in the command
palette and Logs view menu. With several directories it first asks which one. It shows the count
of plain logs or unmanaged store files, then asks **Organize**, **Not now**, or **Never for this
folder**. This command does not establish ongoing synchronization; use data-store setup above
for that. Nothing moves until the choices are complete.

Choose where the organized logs should go:

- **Import into another folder…** asks you to choose a data-store folder. It copies the logs and REV companions there, leaving the originals in their current locations. Choose an existing store or an empty folder; the server creates a store on the first import. The destination is added to your User **Additional Log Directories**, so it appears in the Logs tree and remains available after restarting the editor. Importing identical content again reports it as present instead of creating another copy.
- **Copy in this folder** makes the source folder a store and creates organized copies inside it. The originals remain there too, listed as unmanaged files.
- **Move in this folder** makes the source folder a store and relocates the logs within it. The server verifies each copy before removing its original; duplicate sources stay in place.

Then choose a known robot by name and serial, **New robot…**, or **Decide later**. Cancelling any picker submits no import. Names allow letters, digits, dots, hyphens, and underscores, and must be portable filenames. The log's own identity wins when present; without one, Decide later leaves the files under Unassigned. The server imports the batch while the notification shows the phase, file, and completed/total counts. Afterwards it reports imported, present, unassigned, and refused counts; each file's result and the server's reasons go to the **WPILog Analyzer** output channel. A shared serial in two robot directories is reported with both paths for a later explicit merge.

A store's Logs tree shows robots (with their identity basis in the tooltip), dates, sessions with their times and known event/match, then each wpilog with its REV companions. A REV log appears under every log it pairs with: one measured against a capture pairs with the capture and with the robot's own log of that session. Clicking a REV companion opens the wpilog it is listed under, where the REV pane shows its signals on that log's clock. **Unassigned**, **Inbox**, and **Unmanaged** come last with import actions; Inbox files show waiting, importing, or refused and its reason. **Assign to Robot…** on an unassigned file moves it into the chosen robot's history through the server. Plain folders keep their event/date groups. A window containing both shows a store node and a directory node; Import on a plain file or directory also lets you choose an existing store or another folder for a new one, and always copies.

HTTP import sources must be inside the server's configured log directories. For a USB stick or another outside path, copy files into the store's **inbox/**, or use `wpilog-mcp import --robot practice /path/to/usb/logs` with the standalone server's name/configuration as needed. The command publishes a complete inbox batch, carrying the robot choice in `batch.json`; the daemon imports it once it stops growing and writes each outcome in `inbox/imported.log`. See the [standalone import guide](../doc/STANDALONE.md#importing-logs). Refresh the Logs view to see automatic inbox imports.

Direct imports keep original paths and filenames in the session manifest's file provenance (or `unassigned/<hash>/import.json` before assignment); the listing reports moved paths for seven days. Inbox receipts keep the inbox path and its destination. Duplicate content is reported as present and its source is left in place. Organizing runs in the same server the viewer already uses, whether owned by the extension or the standalone install.

## Using It with Claude Code

With **Enable For Claude Code** on (the default), the extension runs `claude mcp add --scope user wpilog-analyzer -- <launcher> connect http` once when it finds the Claude CLI. On Windows it registers the batch launcher through `cmd /c`. If the CLI is unavailable, it shows the exact command with **Copy Command**. **WPILog Analyzer: Register with Claude Code** repeats setup on demand. Existing Claude Code sessions need restarting after registration.

An editor or profile with no local registration history checks an existing entry through
`claude mcp get`. When its user scope, launcher and arguments match, the extension remembers
it and writes one line to Output without warning. A different entry gets a conflict warning
and manual instructions in **WPILog Analyzer** Output; the extension never replaces it. The
instructions explain how to inspect it and, if you choose, remove an obsolete user entry and
register again. **Copy Command** copies only the registration command. A real failure still
warns and offers **Copy Command**.

No project `.mcp.json` entry is written. The user-scope bridge works from a terminal with VS Code closed: it starts the same server from the home configuration and leases directories from flags or the current project's `.wpilog-mcp.yaml`.

For a project with its own Workspace directories, the extension offers once to write that YAML, with an option to add it to `.gitignore`. Nothing is written without your choice, and an existing or tracked file is left alone. The file contains only directories and an optional team:

```yaml
logdir: ["logs", "sim/logs"]
team: 1234
```

Paths are relative to the bridge's working directory. This file is yours afterwards; changing VS Code settings does not rewrite it. Its `servers` section, if any, cannot configure the shared daemon: `connect` ignores it and records that fact in the server log. See [Directories by lease](../doc/STANDALONE.md#directories-by-lease).

On upgrade, recognized extension-owned entries are removed from untracked or ignored `.mcp.json` files as projects open. Tracked, custom, and unrecognized entries are left with a note in the output; the [standalone guide](../doc/STANDALONE.md#moving-from-a-project-mcpjson) says what to remove by hand and how to check what Claude Code sees. The old `vscode-default` daemon is stopped once, then its private `servers/` and `projects/` settings are removed. A failed stop keeps those settings and is retried next activation.

## Settings

User directories remain available for the window; open projects add their own directories and team. A project's Workspace settings override inherited settings for that project, including replacing its additional-directory list. Relative paths resolve inside each applicable project; with no project open they are left out. `~/` expands to your home on every platform, and forward slashes work on Windows too.

| Setting | Purpose | Default |
|---|---|---|
| `wpilog-mcp.logDirectory` | Main directory leased by the window | auto-detect |
| `wpilog-mcp.additionalLogDirectories` | More directories; relative paths can name project simulation logs | none |
| `wpilog-mcp.teamNumber` | Fallback team for logs that record none | unset |
| `wpilog-mcp.tbaApiKey` | Write-only field; moved to secret storage and cleared | empty |
| `wpilog-mcp.enableForClaudeCode` | User-scope bridge registration and project YAML offers | on |
| `wpilog-mcp.javaPath` | Java executable for the bundled installer | auto-detect |
| `wpilog-mcp.wpiLibYear` | WPILib JDK year for the installer | latest installed |
| `wpilog-mcp.maxHeap` | Maximum JVM heap for the server, applied when the extension starts or restarts it, and for the installer | `4g` |

`useStandaloneServer` and `idleExitMinutes` are removed. Local connections use the standalone install; idle policy is `idle_exit_minutes` in YAML. The installed launcher's own Java lookup runs the daemon, not the installer's `javaPath` and `wpiLibYear`. The heap is the extension's `maxHeap` when the extension starts the daemon, passed to the launcher as `WPILOG_MAX_HEAP`; a daemon another client started keeps the heap it was given until it is restarted.

## Choosing local and remote sources

Set `wpilog-mcp.connections` in User settings to an explicit list. For a remote-only laptop:

```json
"wpilog-mcp.connections": [
  { "id": "pit", "label": "Pit server", "kind": "remote", "url": "http://pit-server:2363/mcp" }
]
```

Add `{ "id": "local", "label": "This laptop", "kind": "local" }` for the local server,
or additional named remote endpoints. A null setting preserves the compatibility choices:
This laptop and, if configured, `pitServerUrl`. There is no preferred connection or store.
Logs presents collapsed connection roots; expanding one discovers only its source scopes,
and expanding a scope lists only that source. An unused root makes no requests. A command
without an explicit source asks when more than one connection or scope is possible, including
unavailable choices. The remote server must support visualization API 2 and persistent identity.

The remote-only configuration does not need Java, a server installation or log files on the
laptop. Manual Source Overview, chart, table, history, follow and library actions use that
server directly. Samples are bounded response data in memory; the extension does not mirror
logs to make a plot. Explicit notebook/export actions can still write their requested outputs.
Mirror and local live-recording services are separate opt-in settings, both disabled by default;
leave them disabled for remote-only use. Existing explicit settings remain effective.

The connection id names a configuration choice; the server UUID names its owner. The first
successful handshake pins that UUID in extension global state. `serverInstanceId` can pin it
in configuration before first use. A same-owner restart reconnects; a replacement owner refuses.
Use a new connection id when deliberately replacing a server. Existing views keep their original
context when configuration changes and ask to reopen explicitly, without following a changed
URL. URLs must be HTTP(S), with root or `/mcp` path and no embedded credentials, query or fragment.
At most 32 connections and 64 opened source contexts are admitted per window; reload releases
unused contexts. Visualizations share the window's decoded allowance and analysis workers.

VS Code exposes one named MCP definition per connection and resolves only the chosen one.
**Register Pit Server with Claude Code** selects a remote connection and registers a native
HTTP entry, so it needs no laptop bridge. Existing different registrations are not overwritten.
Proxy credentials stay scoped to their HTTP origin in SecretStorage; Claude authentication is
configured separately, never copied into command arguments. Authentication policy and automatic
agent-to-window display handoff remain separate work. Agent selection prompts and generated
notebook data URLs carry the selected server and scope.

## The Blue Alliance API Key

Get a free read key from [The Blue Alliance](https://www.thebluealliance.com/account). Use **WPILog Analyzer: Set The Blue Alliance API Key** for a hidden input, or paste it into the User **Tba Api Key** setting. After typing stops, the extension moves it into secret storage and clears the setting; Settings Sync excludes that field.

The key goes to the server only through the window's loopback session, stays in memory there, and is never put on a command line or written to server/project configuration. **Clear The Blue Alliance API Key** removes the stored key and this window's registration. Another live registration or a key you put in `servers.yaml` can still apply. To use TBA from a terminal with VS Code closed, configure `tba_key` in the home YAML or `TBA_API_KEY` in the environment.

A key found in Workspace settings is cleared too, but never replaces an already stored key: it may belong to a teammate. If a key was ever committed or shared, revoke it and enter a new one. Custom and tracked legacy `.mcp.json` entries are not scrubbed automatically; review the migration note in the output.

## The Standalone Install Is the Server

The extension and [standalone clients](../doc/STANDALONE.md) use the same install at `~/.wpilog-mcp`. No ownership setting or second daemon is needed.

When a local connection needs a missing install, the extension offers **Install**, **Not now**, or **Open Settings**. Install runs its bundled JAR under a progress notification, reports the version and PATH hint, then starts the server. Not now lasts until the next activation. **Install Standalone Server** runs it on demand.

A new configuration is seeded with absolute User log directories and team only. Relative and Workspace directories stay leases; the TBA key is never seeded. Without seeds the default is `~/riologs`, with no team. Existing YAML or legacy `servers.json` is preserved.

When preparing a local connection, an older launcher is updated from the bundled JAR without configuration seeds. The automatic comparison uses version numbers and ignores development suffixes; use the install command to advance a suffix. The JAR's own comparison includes suffixes and never downgrades, even if a newer install appeared during the check. Installation leaves a running daemon alone; the next start replaces a different running version.

For a complete reinstall with settings preserved, use the standalone installer's explicit [`--refresh`](../doc/STANDALONE.md#replacing-an-old-install). Extension updates never refresh or bootstrap the extension itself; the Marketplace updates the extension.

## Auto-Detection

The installer uses the configured Java path, the running WPILib VS Code's JDK or selected/latest WPILib JDK, then `JAVA_HOME`, then Java 17+ on PATH. The installed launcher independently uses the newest WPILib JDK, `JAVA_HOME`, then PATH.

With no User directory settings, the window leases the first existing directory among `~/riologs`, `~/wpilib/logs`, and `~/Documents/FRC/logs`. The permanent YAML's directories also remain visible. Explicit leased directories must already exist; the server names any it refuses.

## Upgrading

Marketplace installs update automatically. Install a newer `.vsix` over an older one, then reload VS Code. The server update keeps your configuration; no reconfiguration is needed.

## Uninstalling

Before uninstalling, use **Clear The Blue Alliance API Key** if you want it removed from secret storage. Uninstall the extension in VS Code's Extensions view. Its leases end, but the shared install and Claude Code's user registration remain useful outside VS Code. Remove that registration and [uninstall the standalone server](../doc/STANDALONE.md#uninstalling) separately if no client needs them. Project YAML files, accepted `.gitignore` additions, and VS Code settings remain yours. Old extension-storage `server/` and `cache/` folders can be deleted when no retained legacy entry needs them.

## Troubleshooting

- **Server or registration failed:** select **WPILog Analyzer** in Output, then try **Show Server Log**. Errors name refused directories; create the directory or correct its setting. **Restart Server** restarts the shared daemon manually.
- **Port occupied:** change `servers.http.port` in `servers.yaml`, then restart. The extension reads the actual port from the daemon's PID file.
- **Java missing:** install the WPILib JDK or configure the installer Java path. The launcher also needs a JDK through WPILib, `JAVA_HOME`, or PATH.
- **Claude Code lacks tools:** run **Register with Claude Code**, restart the Claude session, and check `/mcp` for approval. A retained project entry can override the user registration; the output explains why it was left.
- **Large logs exhaust memory:** set `wpilog-mcp.maxHeap` to `8g` and run **WPILog Analyzer: Restart Server**. For a daemon another client starts, set `WPILOG_MAX_HEAP=8g` in that client's environment instead.

## More Information

- [Operations and usage manual](../doc/OPERATIONS.md): step by step, from the setup check to analysis and running the shared server
- [Main README](../README.md): overview and tools
- [Standalone guide](../doc/STANDALONE.md): configuration, installation, and other clients
- [Tool reference](../doc/TOOLS.md): parameters and result contracts

The [real-editor smoke](../doc/DEVELOPMENT.md#the-real-editor-smoke) runs in CI on the oldest
supported VS Code and current stable. It checks activation, shared-server startup, a generated
log in the Logs tree, its editor opening and data-view updates. Interactive plots and organizing still have a
manual checklist there.

### Recording health and reconnects

Visible recording views show acquisition separately from follow progress. Quiet change-only
signals remain connected; last-value age is informational. Health checks share one request per
visible source with a five-second cadence and client deadline. Hiding the final view stops them.
A same-clock reconnect reopens drained history using fresh buffers. A new robot recording never
replaces an existing view's source automatically.

After a same-owner server session restart, the preview restores the window allowance and the same
working view, retries failed attachment, then bootstraps fresh data. Configuration, source anchor
and pause intent remain; old replies and handles are discarded. Unavailable owners/sources are
explained without creating replacement layouts. Close cancels recovery without affecting peers.
Host-to-render timing is shown after animation-frame acknowledgement; it is not an end-to-end
latency or performance claim.

The Explorer uses qualified API 2 source references on each selected connection. Its server,
scope, inventory, data, follow and library adapters retain that context for the life of a view.
No failed remote request substitutes a mirror or starts the local daemon. To use another copy,
open that connection's source as a separate document. Later mirror-engine and display-handoff
work is tracked in [the source and mirror plan](../doc/SOURCE_AND_MIRROR_PLAN.md).

The M4 server checkpoint pauses mirror downloads while replacing the old retention writer.
Existing copies remain readable; a configured mirror reports `migration_required`, then
`writer_not_ready` after explicit format-2 migration. Direct remote visualization is independent
of this gate. Migration does not enable synchronization, cleanup or peer exchange. See
[the mirror reference](../doc/pit-server/STANDALONE.md#the-mirror) for local controls and limits.
