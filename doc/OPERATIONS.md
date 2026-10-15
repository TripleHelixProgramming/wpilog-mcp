# Operations and Usage Manual

What to type, click and ask, step by step, to get answers from your robot's logs and to run
the server in the shop and the pit. The [standalone guide](STANDALONE.md) is the reference for
every setting, flag and route, and the [tool reference](TOOLS.md) describes every tool's
parameters and results. This manual says which to use, in what order, and what you should see.

The shared-server and pit instructions describe 0.10.0-dev5 or newer. The latest full release
can be older than this branch. Check the version before following them; if it is not published
yet, install the checkout as described in [6.1](#61-install).

- [Part I: Using the tools](#part-i-using-the-tools): the setup check, how a question becomes
  tool calls, how to read an answer, recipes for the common questions, and the explorer.
- [Part II: Running the server](#part-ii-running-the-server): the shared server on a laptop,
  local stores and maintenance.
- [Part III: When something is wrong](#part-iii-when-something-is-wrong): symptoms, the check
  that decides, and the fix.

## Part I: Using the tools

### 1. Before the first question

#### 1.1 Which setup you have

| Setup | What runs | When to choose it |
|---|---|---|
| VS Code extension | **WPILog Analyzer** installs and starts the shared server; Copilot and other VS Code agents use it directly, Claude Code through a bridge | Most people. You get the explorer too |
| Standalone server | The same server, installed by a script, used by Claude Code, Claude Desktop, Codex or any MCP client | No VS Code, or a terminal-first workflow |

Both use the same program. The extension and the standalone install share `~/.wpilog-mcp`,
one server and one cache.

#### 1.2 Check that the server is up

Extension: open **WPILog Explorer** in the activity bar. The **Logs** view lists your logs.
If it is empty or shows an error, select **WPILog Analyzer** in the Output panel and read it.

Terminal: install first ([6.1](#61-install)). If `wpilog-mcp` is not found, add its launcher
directory to this shell's PATH, then check it:

```bash
export PATH="$HOME/.wpilog-mcp/bin:$PATH"
wpilog-mcp -version
wpilog-mcp start http
curl http://127.0.0.1:2363/health
```

`start http` prints that the server is running (or already was); `/health` answers with the
version, configuration `name`, process ID, `managed`, the `gateway` status field, and
`server_instance_id`/`working_state`. A named server normally reports durable local owner
identity. If its visualization state cannot be opened, it logs a warning and continues with
ephemeral identity; `working_state_reason` explains the fallback. Unnamed servers also report
ephemeral identity and a reason. This does not create a log store; the recorded visualization preview is opened explicitly. The server's log is at
`~/.wpilog-mcp/logs/http.log`.

Startup writes one `Configuring log directory:` line per configured root to that log.
Containment checks during installation and reads do not add INFO messages of their own.

#### 1.3 Check that your assistant sees the tools

- Claude Code: type `/mcp`. `wpilog-analyzer` should be connected; approve it if asked.
  If it is missing, run the registration in [6.4](#64-register-your-assistant).
  From a terminal, `claude mcp get wpilog-analyzer` also checks the registration and connection.
- Any assistant: ask *"What logs are available?"*. The answer should list files with friendly
  names such as "VACHE Qualification 10". An assistant that answers from memory, or writes
  Python to parse the file, is not connected to the server.

#### 1.4 Put the logs where the server looks

The default directory is `~/riologs`. Copy `.wpilog` files there with the names the robot gave
them (`FRC_20260321_162956_VACHE_Q10.wpilog`, `akit_26-03-21_16-29-56_vache_q10.wpilog`):
the event and match in the name are what the listing reads when the log's first records do
not carry them. Keep a `.revlog` next to the `.wpilog` it was recorded with.

To use other directories, see [6.3](#63-configure-the-server) and [6.5](#65-project-directories).
A log outside every configured directory is refused by name, not read.

The experimental **Set Up Data Store…** offer and commands are hidden by default. If you have
[enabled them on the server](STANDALONE.md#keeping-a-data-store-up-to-date), choose a destination
and review the folders to
keep importing. Copy leaves your source logs untouched; Move requires a completed-file folder.
Nothing starts until the final confirmation. **Not now** leaves the logs as they are, and **Never**
suppresses the offer. Setup is also available from the command palette. The manual **Organize
Logs…** command still offers a one-time import or in-place Copy and Move.

#### 1.5 Team number and The Blue Alliance key (optional)

Neither is needed to read a log. The team number labels logs that record none; the key adds
match results, alliances and times to the listing and enables `get_tba_match_data`.

- Extension: the **Team Number** setting, and **WPILog Analyzer: Set The Blue Alliance API Key**.
- Standalone: `team` and `tba_key` in `~/.wpilog-mcp/servers.yaml`, or `TBA_API_KEY` in the
  environment. Never put the key on a command line.

#### 1.6 Open the robot project

Keep the robot project open in the same window. A log records names and numbers, not what
they mean. The assistant is told to read the code that logs an entry to learn which mechanism
it belongs to, its units, and whether it is measured or commanded. Without the code it must
say that a mapping is an assumption taken from the name.

### 2. How a question becomes tool calls

The server hands every connected assistant its reasoning rules on connect, and
`get_server_guide` returns the long form. You do not have to drive the tools yourself, but
knowing the shape of a good investigation lets you steer one.

1. **Find the log.** `list_available_logs` with an `event` or `name` filter; the assistant
   should name the file it chose.
2. **Learn the timeline.** `get_match_phases` gives the enabled segments, auto and teleop.
   Every statistic should be scoped to a phase or an enabled segment, because whole-log numbers
   mix in disabled time and the boot.
3. **Confirm the event happened.** `get_ds_timeline`, `find_condition` or `search_strings`
   shows the brownout, the error or the disable the question is about. An event that is not
   in the log is not explained; it is reported as absent.
4. **Test, one call per prediction.** `get_statistics`, `find_condition`, `read_entry` with a
   window, `time_correlate`, each scoped to the phase and window in question.
5. **Conclude in three tiers.** A logged event is a fact. A statistic is an inference, bounded
   by the result's `confidence_level`. A cause outside the telemetry is a hypothesis that
   needs a physical check.

How to ask so the first answer is useful:

| If you want | Say |
|---|---|
| A specific match | The event and match: "Q42 at VACHE". The assistant should state the file it used |
| A quick answer before a match | "pit mode" or "we have a match in ten minutes": two to four sentences, one thing to check |
| The full investigation | "deep dive": ranked findings with evidence, alternatives and confidence |
| A mechanism's behaviour | Its name as the code logs it, or where to find it: "the elevator, under /RealOutputs/Elevator" |
| Your own theory tested | State it: "I think the compressor caused it". It becomes the first hypothesis and gets a rival |

### 3. Reading an answer

An assistant that follows the rules cites the entry, the window, the sample count and the
statistic for every number. Ask for them when it does not. Behind each number is a tool
result, and three fields in it decide how far to trust it.

| Field | What to read from it |
|---|---|
| `status` | `ok`; `partial` (something was skipped, named in `skipped`); `not_applicable` (the tool does not apply, with a `reason`); `no_match` (nothing matched the requested entries, condition or window, with `looked_for` and a `hint`); `error`. `no_match` means not found, never "nothing wrong" |
| `data_quality` and `server_analysis_directives.confidence_level` | How much the samples can support a statistic: `high`, `medium`, `low` or `insufficient`, with penalties in `data_quality.reasons`. The confidence level is inside `server_analysis_directives`, not at the top level. A `low` level on a sparse change-only signal does not weaken an event the log plainly records |
| `inputs` | Which entries and which time range the result was computed from. If the entry is not the one you meant, say so and name the right one |

Things that are often misread:

- **Brownouts.** `BROWNOUT_START` in `get_ds_timeline` is the battery voltage crossing a
  threshold (the log's own `BrownoutVoltage`, else 6.8 V, stated as an assumption). Only
  `RIO_BROWNOUT_START`, from a logged flag such as AdvantageKit's `/SystemStats/BrownedOut`,
  means the roboRIO cut its outputs. A log without the flag cannot say whether that happened.
- **The log ends early.** A file that stops mid-phase says "log ends at X s", not "the match
  ended". Most robot logs end inside their last record because the robot was switched off; that
  alone is a note, not a warning.
- **Scores.** The telemetry does not record the score. Results come from The Blue Alliance,
  through `get_tba_match_data` or the `tba` field of the listing.
- **One log is one sample.** "Happened in 4 of 6 matches" is a pattern; "happened once" is an
  event. Ask for `compare_matches` or the same statistic on each log before accepting a trend.
- **Correlation.** A mechanism that fires only while scoring correlates with voltage because
  of timing. Ask for the mechanism's current around the voltage minimum instead.

Useful follow-ups: *"Which entry did you use, and why that one?"* *"Run it again with scope
teleop."* *"Show me the candidates."* *"What is the one thing to check before the next match?"*

### 4. Recipes

Each recipe names the tools in order with the parameters that matter. The assistant usually
runs them itself; the sequence is what to expect, and what to ask for when it stops short.

#### 4.1 The robot died or stuttered in a match

1. `get_ds_timeline` with the log's `path`: enable and disable transitions, `BROWNOUT_START`
   and `BROWNOUT_END` crossings, `RIO_BROWNOUT_START` when the log has the flag, and a
   summary of errors and warnings. Note `brownout_voltage_entry` and `brownout_threshold_basis`.
2. `power_analysis` with `scope: "enabled"`: `voltage_analysis` for the minimum and the samples
   below threshold, `channel_analysis` for every current entry's peak with its time, largest
   first. Per-channel arrays such as `/PowerDistribution/ChannelCurrent` are expanded per index.
3. `find_condition` on the voltage entry, `operator: "lt"`, `threshold` at the brownout
   level, `scope: "enabled"`: the intervals, each with its start, end and duration.
4. `read_entry` on the suspect current with `start_time` and `end_time` two seconds around a
   crossing: was the mechanism drawing current when the voltage fell?
5. `can_health`: a burst of CAN errors within about a second after the dip is a consequence of
   the brownout, not its cause; look for enabled-state errors before it.

What to look for: a current peak at the voltage minimum names the load; a steady draw such as a
compressor shows near-zero correlation even when it contributes. Which device sits on a channel
is not in the log; the team's wiring map is.

#### 4.2 CAN problems

1. `can_health`: text reports of CAN failures classified by the robot's state (an error while
   disabled is normal, an error while enabled is not), the bus counters, and an assessment with
   its basis. `not_applicable` means the log has neither text nor counters.
2. `analyze_can_bus` with `bus_name` for one bus (`rio`, or a CANivore name) and a window:
   utilization, transmit and receive errors, bus-off and TX-full counts.
3. `search_strings` with `pattern: "CAN"`, `level: "error"` and a window for the full, paged
   text; `has_more` says whether another page exists.

#### 4.3 Loop overruns

1. `analyze_loop_timing` with `scope: "enabled"`: how often the loop exceeded `threshold_ms`
   (default 20 ms) and the distribution. The unit comes from the entry name, else from the
   data, and the result says which. Pass `entry` when the log has several candidates.
2. `find_condition` on the loop time entry, `operator: "gt"`, `threshold` in the entry's unit,
   to list the overruns as intervals.
3. `search_strings` with `start_time` and `end_time` around the worst one: what the program
   printed then.
#### 4.4 Swerve

1. `analyze_swerve` with `scope: "enabled"`: speed per module, tracking against setpoints when
   they are logged, slip and steer events. Modules are numbered by array index; a position such
   as front-left is an assumption the result states. When the log has two setpoint arrays the
   tool lists them as candidates: pass `measured_entry` and `setpoint_entry`.
2. `compare_entries` on one module's measured and setpoint speed, with a `windows` list from
   `find_condition` (while driving), for RMSE and the lag that minimizes it.
3. `pose_corrections` or `compare_poses` between odometry and the vision estimate for drift.

#### 4.5 Autonomous that works in practice but not on the field

1. `analyze_auto`: every autonomous period, the routine the chooser selected, and how closely
   the robot followed its path. Pass `chooser_entry` when more than one chooser exists.
2. `compare_matches` with `scope: "auto"` and the pose or path error entry on the practice log
   and the match log; each log's phase is resolved in its own timeline.
3. `get_code_metadata` on both logs: a different git SHA is a confounder for any difference.
4. Keep only FMS-connected logs for the comparison; `list_available_logs` names the event and
   match of each.

#### 4.6 A mechanism: elevator, arm, intake

1. Find the entries: `search_entries` or `list_entries` with the mechanism's name as `pattern`,
   then the robot code for which is the setpoint, the measurement, the current.
2. `profile_mechanism` with explicit `setpoint_entry`, `measurement_entry`, `velocity_entry`,
   `current_entry` and `temperature_entry` as the log has them: following error, step response,
   stalls (`stall_current_threshold`, default 30 A) and temperature. It uses only the entries
   you pass; `mechanism_name` only finds candidates.
3. `find_condition` with `conditions: {"all": [...]}` for current above a threshold while the
   velocity is near zero: the stalls, as intervals.
4. `get_statistics` on the current with `windows` set to those intervals.

#### 4.7 Vision

1. `analyze_vision`: pose observation streams, target streams, has-target flags and pose
   jumps, for the entries the AdvantageKit template, PhotonVision and Limelight publish. Other
   entries are listed as candidates; pass them as `vision_entries` once the code confirms them.
2. `pose_corrections`: how large the corrections to the robot pose were and when.
#### 4.8 Console errors and alerts

1. `get_ds_timeline`: `text_event_counts` and `text_event_summary` group the messages, with
   one example per group and how many distinct texts it covers.
2. `search_strings` with `level: "error"` (or `warning`), a `pattern` or a `regex`, and paging
   with `offset` and `limit`: the complete list, in time order. `collapse_repeats: true` folds
   runs of the same line.
#### 4.9 Across an event

1. `list_available_logs` with `event` set to the event code: every log, newest first, with
   TBA results when the key is configured.
2. `compare_matches` for two logs, or `get_statistics` with the same `name` and `scope` on each
   log, tabulated. Use `scope: "enabled"` or `"teleop"`; a whole-log maximum is often the boot.
3. `get_tba_match_data` with `year`, `event_code`, `match_type` and `match_number` for
   outcomes. The event code is TBA's, not necessarily the abbreviation in the file name.
4. `get_code_metadata` on each log before attributing a change in behaviour to anything else.

#### 4.10 REV motor controller data

1. `list_revlog_signals` with the wpilog's `path`: devices, signals, units, and each REV log's
   synchronization status. The REV log is found beside the wpilog and put on its clock by
   correlating signals both logs record.
2. `sync_status`, or `wait_for_sync` right after loading: the confidence, offset and drift. If
   the accuracy is coarser than the interval you care about, the ordering of a REV event and a
   wpilog event cannot be determined. When you know the offset from a distinctive event in
   both logs, `set_revlog_offset` with `offset_ms` sets it by hand.
3. `get_revlog_data` with the `signal_key` from step 1, a window and `include_stats: true`.
   Stall current, temperature and fault flags are valid without precise alignment.

#### 4.11 Getting the numbers out

- `export_csv` with `name`, a window and `inline: true` for up to 5,000 rows in the answer, or
  a file under the export directory; it is the route when no tool computes what you need.
- `render_chart` with `name` or `entries`, `kind` (`time_series`, `histogram`, `scatter`,
  `field`) and a window: a PNG with the statistics of the visible window beside it, and an
  **Open in Explorer** link in VS Code.
- `generate_report`: a one-call summary of a log, each section by the same rules as the tool
  that covers it in depth.
- For a notebook or a script, the HTTP server's data endpoint streams every sample of an entry
  as Arrow or CSV: `GET /data/entries?path=<log>&names=<entry>&format=csv`
  ([the data endpoint](STANDALONE.md#the-data-endpoint)). The explorer's **Open in Notebook**
  writes a three-cell notebook that does this.

### 5. The explorer

WPILog Explorer is the extension's viewer: the same server's numbers, plotted, with no assistant
needed. The [extension guide](../vscode-extension/README.md#exploring-logs) has the details.

1. Click a log in the **Logs** view. The editor shows the time range, entries, sample counts
   and any truncation note. The **Entries** view follows the active log. Type part of a full
   entry name into its inline filter; matches inside unopened groups are included. Clear the
   field to restore all entries. Use **Collapse All Entries** to close the branches while
   keeping the filter. The filter icon focuses the inline field instead of opening a dialog.
   Choose **Group by → Data type** to collect entries under their exact logged types, with
   entry counts and full paths. **Path** restores the usual name hierarchy. This choice is
   remembered for the workspace; filtering and expandable fields work in either mode.
2. Click an entry to plot it; expand a struct or array to its numeric fields. Drag to zoom,
   wheel to zoom about the cursor, shift-wheel or arrow keys to pan, double-click to reset.
3. The **Timeline** pane shows the Driver Station segments; drag a region to select a window.
   The **Console** pane filters text by pattern, level and window. The **Field** pane draws the
   logged pose on the season's field. The **REV** pane shows REV signals by bus and device.
4. **Data view of selected entries** opens the exact samples in a grid with filtering, pivots,
   quick charts and CSV export.
5. **WPILog Explorer: Ask about this selection** sends the path, entries and window to chat.
   **Open in Notebook** writes a Python notebook that fetches the same samples.
#### 5.1 Recorded visualization preview

1. On a local WPILOG in Logs, choose **Open Source Overview (Preview)**. Source facts appear
   before signal indexing completes. Missing robot, session or match information says unavailable.
   Ordinary open still uses the existing Explorer editor during the preview.
2. Choose **Blank layout**, then **Add plot**, **Add value table**, **Add statistics** or **Add raw table**. Expand **Add signals**,
   choose a panel and select exact entry names. Entries also names the active view/panel as its
   destination. Switching to another view changes that destination; a hidden view is not a fallback.
3. Use **Move, resize or remove panel** with Tab/Enter, or drag/resize the panel.
   Title, position and size edits retain the loaded samples. Expand **Recorded time and cursor**
   for a range or latest/cursor values. Tables report the last original sample at or before the
   cursor, its robot timestamp and any skipped-record count.
4. Use **Duplicate and Open Beside** to make an independent view of the same source; open another
   source overview to work with a different recording at the same time. Native Split moves a view.
5. **Save view…** offers stores by label and path. **Create store…** asks for a local directory
   and creates metadata through an empty import, without enabling automatic ingestion. Canceling
   leaves the draft. Promotion clears draft undo; later changes use native Save and Undo/Redo.
   Save As remains unavailable and points to Save view.
6. Return to the overview to resume an Unsaved view (detached drafts expire after 30 days) or open
   a new working copy of a Saved view. A durable named server retains these across restart.
   Invalid/newer saved files are reported beside the readable ones and left intact. If an unmanaged
   file's fingerprint changed or the source is no longer allowed, the view reports unavailability
   rather than selecting a similar file. The fingerprint checks file attributes, not its content;
   see the [standalone reference](STANDALONE.md) for this limit and locator recovery. Older
   fingerprints are listed as unavailable; configuration remains readable through MCP.
7. Expand **Reusable templates**, search by name/description and select a built-in or store
   template. Its compatibility reports exact names or resolver roles, accepted types and unit
   reasons. **Open new view** keeps the current layout; **Replace this layout…** offers Save or
   Discard. Unresolved signals remain explained and are not guessed from similar names.
8. **Save template…** stores the configuration without source files or local paths. Exact names
   are the default; keeping explicit roles is a choice. **Update selected template…** compares
   its revision; open views keep their pinned configurations. Export the definition to JSON,
   then Import it into a writable store. Identical reimports are no-ops. Same revision/different
   content is a conflict; newer known content offers Import copy or an explicit Update existing.
   If the selected store template file has been removed, `TEMPLATE_UNAVAILABLE` asks you to
   refresh the catalog or save a new template from an existing view. Open views keep their layout.
9. Statistics summarize original samples in the selected interval, not plot buckets. Raw tables
   retain physical ordinals and integer timestamps in bounded replacement pages. Use First/Next
   page to inspect more; duplicate or backward timestamps do not collapse records.
10. An agent can use the `visualization_*` tools to inspect and edit local views. Supply
    `api_version: 1`, UUID request IDs and expected revisions for mutations; definition arguments
    are JSON text. A lost reply retries with the same ID/arguments. A revision conflict requires
    inspection before another edit. An attached Explorer receives the accepted revision without
    a popup. Live following and explicit display handoff remain later stages.

For a currently recording, manifested WPILOG, the preview overview now shows a detached
recording inventory and explicitly disables Blank layout and live template opening. Reopen the
overview to inspect a newer prefix; use the existing Explorer for continuous display during
this checkpoint. Local callers can separately opt into bounded recording discovery with
`visualization_source_inspect` and `inventory_mode: "paged"`, then list/get/close the inventory
through the [documented operations](TOOLS.md#visualization_source_entries_list). The overview
UI still uses full inspection. In its default mode, `visualization_source_inspect` returns the same anchored source and member
inventory, with clock evidence separate from sample timestamps. This is source admission for
the upcoming live panels, not completion of the live visualization workflow.

## Part II: Running the server

### 6. The shared server on a laptop

#### 6.1 Install

One command installs the latest full release. The installer shipped with these changes offers
log directories and a team number when run in a terminal, then the matching extension for each
WPILib VS Code that accepts it: one editor gets a yes-or-no question, several get a numbered
menu with the newest season first and the default. An editor too old for the extension is named
with its version instead, and with no WPILib VS Code the installer does not ask. It keeps
existing settings; without a terminal it takes the defaults. Older installers offer fewer
options, and the latest full release may not yet carry the commands in this manual:

```bash
curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh
```

```powershell
irm https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 | iex
```

The layout is `~/.wpilog-mcp/` with `bin/` (the `wpilog-mcp` launcher), `jars/` and
`servers.yaml`. Add `~/.wpilog-mcp/bin` to your `PATH` to run `wpilog-mcp` in a terminal; MCP
clients use the full path. Java 17 or newer is required; the WPILib JDK is found first.
Details, a pre-release install and an install from a checkout: [Install](STANDALONE.md#install).

Until this version and its installer are published, use the `development` checkout. In its
directory, run:

```bash
export JAVA_HOME="$HOME/wpilib/2026/jdk"
./gradlew install
export PATH="$HOME/.wpilog-mcp/bin:$PATH"
wpilog-mcp -version
```

Use your own JDK path if it differs. Continue only when the printed version is 0.10.0-dev5 or
newer; a completed download alone does not show that the installed launcher works.

#### 6.2 Start, stop, status

```bash
wpilog-mcp start http            # background; returns once the server answers
curl http://127.0.0.1:2363/health
wpilog-mcp stop http             # finishes calls in progress, then exits
```

`start http` while it runs reports the running server. After an upgrade, `start` stops the old
version and starts the new one. MCP clients that run `connect http` start the server themselves,
so you rarely need `start` by hand. Logs: `~/.wpilog-mcp/logs/http.log`.

#### 6.3 Configure the server

Edit `~/.wpilog-mcp/servers.yaml`, then restart (`wpilog-mcp stop http`, and reconnect).
Replace the example directories with yours; leave out the archive line unless that disk exists:

```yaml
team: 2363
logdir:
  - ~/riologs
  - /Volumes/LOGS/archive
# tba_key: your-key-here       # or TBA_API_KEY in the environment
servers:
  http:
    transport: http
    port: 2363
```

Every field, with its default: [Config Fields](STANDALONE.md#config-fields). VS Code's Settings
UI does not edit this file; its directories are temporary leases ([6.5](#65-project-directories)).

#### 6.4 Register your assistant

Claude Code, once per user account:

```bash
claude mcp add --scope user wpilog-analyzer -- "$HOME/.wpilog-mcp/bin/wpilog-mcp" connect http
```

If the command says the name already exists, check `claude mcp get wpilog-analyzer`: the
command should be this install's launcher, with arguments `connect http`. Do not add a second
name. If that existing registration is obsolete, remove just this user entry with
`claude mcp remove wpilog-analyzer --scope user`, then run the add command again.

The extension recognizes a matching user entry from another editor or profile, remembers it
locally and writes one line to Output without a warning. If the entry differs, it warns of a
conflict and leaves it unchanged, with the manual steps above in **WPILog Analyzer** Output.
**Copy Command** copies only the add command; removal is your decision. Connection health is a
separate check: an existing registration can be correct while the server is not reachable.

Restart an open Claude Code session afterwards and check `/mcp`. The extension runs this
command for you when it finds the Claude CLI (**WPILog Analyzer: Register with Claude Code**
repeats it). Claude Desktop, Codex, Antigravity and other clients take the same command form or
the URL `http://127.0.0.1:2363/mcp`: [MCP Client Setup](STANDALONE.md#mcp-client-setup).

#### 6.5 Project directories

A robot project's own logs (simulation output, a `logs/` folder) can be leased to the shared
server for the life of a session instead of configured permanently:

- In VS Code, the User and Workspace log directory settings become the window's lease.
- From a terminal, put `logdir: ["logs", "sim/logs"]` and optionally `team` in the project's
  `.wpilog-mcp.yaml` (the extension offers to write it), or register the bridge with
  `connect http --logdir logs --team 1234`.

Leases are visible to every connected client while the session lives; see
[Directories by lease](STANDALONE.md#directories-by-lease).

#### 6.6 Upgrade, replace, uninstall

Run the installer for the version you want again; a checkout install is `./gradlew install`
from that checkout, while the release script selects a published release. It keeps your
configuration. A running server keeps the old version until the next `start` or `connect`.
For a clean reinstall that preserves settings, pass
`--refresh`. To uninstall, stop the servers, delete `~/.wpilog-mcp`, remove the `PATH` line
and the client registration, and delete the disk cache:
[Upgrading](STANDALONE.md#upgrading), [Uninstalling](STANDALONE.md#uninstalling).

### 7. Stores: import and inbox

Continuous imports are experimental and off by default. After explicitly
[enabling the feature and restarting the server](STANDALONE.md#keeping-a-data-store-up-to-date),
use **Set Up Data Store…** for continuous imports from selected folders. The store is the default
place to browse, with pending or refused source files kept visible. **Show All Sources** reveals
originals. **Manage Data Store…** shows the shared policy, per-source status and refusal reasons;
**Sync Data Store Now** requests a scan and **Pause/Resume Data Store Sync** stops or resumes
future imports. The server continues working when the editor closes, while the server runs.
Temporary and project folders require explicit selection. New folders never join automatically.
See [Keeping a data store up to date](STANDALONE.md#keeping-a-data-store-up-to-date).

#### 7.1 Import from a USB stick or a folder

```bash
wpilog-mcp import --server http /media/usb/logs
wpilog-mcp import --robot practice ~/riologs/downloads     # name the robot when the log has no serial
```

Files are copied (`--move` moves them), identified by content, hashed, loaded to check them,
and placed by robot and session. The output lists each file as imported, present (a duplicate,
left at its source), unassigned (no robot or session known yet) or refused, with the reason.
If Move cannot remove the original after committing a verified copy, the result still reports
the import and explains why the original remains.
When the local server is running, an import from its configured directories is submitted
to it; other sources are copied into the store's `inbox/` for import. The Logs view also
offers **Import**. Reference: [Importing Logs](STANDALONE.md#importing-logs).

A WPILOG import of a known robot joins a session that already has a capture or a data-matched
log only on strong correlation within 250 ms. Without such an anchor it is grouped by calendar
overlap, and the manifest says so.

#### 7.2 The inbox

Drop files into `<store>/inbox/`. The owning server imports each file once its size has been
stable for three seconds, and writes one line per file to `inbox/imported.log` with the
outcome. A refused file stays there with its reason until it changes. A folder with a
`batch.json` naming `stated_robot` imports as one batch for that robot.

#### 7.3 Assigning a file to a robot

An unassigned file appears under **Unassigned** in the Logs view and under `unassigned` in
`list_available_logs`. **Assign to Robot…** moves it into the chosen robot's history through
the server; the HTTP route is `POST /store/assign`
([The Import Endpoint](STANDALONE.md#the-import-endpoint)).

### 8. Maintenance

- **Upgrading.** Run the installer again ([6.6](#66-upgrade-replace-uninstall)). A background
  server is replaced at its next `start` or `connect`. For a systemd service, run
  `sudo systemctl restart wpilog-mcp-http.service`.
- **Backing up a store.** Stop the server and copy the whole store directory.
- **Disk space.** The REV synchronization cache has its own `diskcachesize` cap; imported logs
  are kept until you choose to remove them. See [Config Fields](STANDALONE.md#config-fields).
- **Memory.** `WPILOG_MAX_HEAP` (default `4g`) sets the server's heap. Large logs may need
  `8g`; the extension's `maxHeap` setting supplies the heap when it starts the server.
- **Secrets.** Keep the TBA key in YAML, the environment or the extension's secret storage,
  never on a command line.
- **Server logs.** Read `~/.wpilog-mcp/logs/<name>.log`, the terminal for `run`, the service's
  journal, or the **WPILog Analyzer** output channel.

## Part III: When something is wrong

| Symptom | Check | Fix |
|---|---|---|
| The assistant has no wpilog tools | Claude Code: `/mcp`. Others: the client's MCP configuration | Register ([6.4](#64-register-your-assistant)); restart the assistant session; approve the server |
| The extension shows "Failed" or no logs | Output panel, **WPILog Analyzer**; **Show Server Log** | A refused directory is named: create it or fix the setting. **Restart Server** |
| `wpilog-mcp` times out or prints a Java error | `java -version`; the launcher wants Java 17 or newer | Install the WPILib JDK, or set `JAVA_HOME` |
| Port 2363 is busy | `/health` answers from something else | Change `servers.http.port` in `servers.yaml` and restart |
| Every tool answers `no_match` or an error naming the directory | `list_available_logs`: `log_directories` | The log is outside every configured or leased directory: add it ([6.3](#63-configure-the-server), [6.5](#65-project-directories)) |
| Out of memory on a large log | The server log | `WPILOG_MAX_HEAP=8g`, or the extension's `maxHeap`, then restart |
| A log is reported truncated | `_metadata.log_truncation` in results | Normal for a robot switched off while logging; every result says what was read |
| An imported log sits in its own session | `matching_reason` in `list_available_logs` | The data proof failed or was ambiguous; the file is still readable by path. Nothing is guessed |
| An import is refused as outside the configured directories | The command's output | Copy the files into the store's `inbox/` |
| Non-ASCII filenames are refused | The server log names the native encoding | Run the server with a UTF-8 locale (`LANG=C.UTF-8 LC_ALL=C.UTF-8`) |

More: [Troubleshooting](STANDALONE.md#troubleshooting) in the standalone guide and
[Troubleshooting](../vscode-extension/README.md#troubleshooting) in the extension guide.
