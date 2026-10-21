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
version, configuration `name`, process ID, `managed`, the `gateway` and `capture` status fields, and
`server_instance_id`/`working_state`. A named server normally reports durable local owner
identity. If its visualization state cannot be opened, it logs a warning and continues with
ephemeral identity; `working_state_reason` explains the fallback. Unnamed servers also report
ephemeral identity and a reason. This does not create a log store; the recorded visualization preview is opened explicitly. The server's log is at
`~/.wpilog-mcp/logs/http.log`.

If HTTP is healthy but recording has stopped, inspect `capture.captures[].state` and `reason`.
`low_disk` resumes automatically when free space is above the configured floor plus margin;
`startup_failed`, `admission_failed` and `write_failed` have details in the private server log.
`last_write_age_ms` is null before a completed write. A high age alone does not prove missing
data: a topic may publish only when it changes. Stopped capture keeps process health `ok` and
does not by itself activate the systemd restart handler.

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
  threshold (the log's own `BrownoutVoltage`, else 6.3 V, stated as an assumption). Only
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
   When the logs are finished sessions of one store or mirror, `compare_sessions` takes up to 64
   of them in one call: one statistic per session, each session counting once, with the code
   version, robot and event of each session listed beside the numbers.
3. `get_tba_match_data` with `year`, `event_code`, `match_type` and `match_number` for
   outcomes. The event code is TBA's, not necessarily the abbreviation in the file name.
4. `get_code_metadata` on each log before attributing a change in behaviour to anything else.

#### 4.10 REV motor controller data

1. `list_revlog_signals` with the wpilog's `path`: devices, signals, units, and each REV log's
   synchronization status. The REV log is found beside the wpilog and put on its clock by
   correlating signals both logs record. In a store, the offsets recorded when the files were
   placed are used instead, and a REV log measured against the same capture as the wpilog shows
   `sync_method` `COMBINED`.
2. `sync_status`, or `wait_for_sync` right after loading: the confidence, offset and drift, and
   for a combination the measurements it rests on and `max_error_ms`. If
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
For remote-only use, configure `wpilog-mcp.connections` with a remote endpoint and leave
mirror/live services disabled. Expand its Logs root, then its source scope. No laptop daemon
or local log copy is needed. Add a local or another remote connection as a separate root;
there is no default across multiple choices. See the [connection setup](../vscode-extension/README.md#choosing-local-and-remote-sources).

#### 5.1 Recorded visualization preview

1. Expand the chosen connection and source scope in Logs. On its WPILOG, choose **Open Source Overview (Preview)**. Source facts appear
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
5. **Save view…** asks for a title in the connected server's visualization library. It works
   for an ordinary folder or mirror without creating or modifying a log store. An ephemeral
   server explains that durable save is unavailable. Canceling leaves the draft. Promotion
   clears draft undo; later changes use native Save and Undo/Redo.
   If a save response is lost, retry Save to recover that checkpoint. Edits made after it remain
   visible and dirty; use Save again to checkpoint those newer edits. Recovery does not repeat
   the original library write.
   Save As remains unavailable and points to Save view.
6. Return to the overview to resume an Unsaved view (detached drafts expire after 30 days) or open
   a new working copy of a Saved view. Use **Next saved-view page** and **First saved-view page**
   for more results. A durable named server retains these across restart.
   Invalid/newer saved files are reported beside the readable ones and left intact. If an unmanaged
   file's fingerprint changed or the source is no longer allowed, the view reports unavailability
   rather than selecting a similar file. The fingerprint checks file attributes, not its content;
   see the [standalone reference](STANDALONE.md) for this limit and locator recovery. Older
   fingerprints are listed as unavailable; configuration remains readable through MCP.
7. Expand **Reusable templates**, search by name/description and select a built-in or server-library
   template. Use **Next template page** for more matches. Its compatibility reports exact names or resolver roles, accepted types and unit
   reasons. **Open new view** keeps the current layout; **Replace this layout…** offers Save or
   Discard. Unresolved signals remain explained and are not guessed from similar names.
8. **Save template…** stores the configuration without source files or local paths. Exact names
   are the default; keeping explicit roles is a choice. **Update selected template…** compares
   its revision; open views keep their pinned configurations. Export the definition to JSON,
   then Import it into the connected server library. Identical reimports are no-ops. Same revision/different
   content is a conflict; newer known content offers Import copy or an explicit Update existing.
   If a selected definition is missing or its revision changed, refresh the catalog or save a
   new template from an existing view. Open views keep their layout.
9. Statistics summarize original samples in the selected interval, not plot buckets. Raw tables
   retain physical ordinals and integer timestamps in bounded replacement pages. Use First/Next
   page to inspect more; duplicate or backward timestamps do not collapse records.
10. An agent can use the `visualization_*` tools to inspect and edit local views. Supply
    `api_version: 1`, UUID request IDs and expected revisions for mutations; definition arguments
    are JSON text. A lost reply retries with the same ID/arguments. A revision conflict requires
    inspection before another edit. An attached Explorer receives the accepted revision without
    a popup. Live following and explicit display handoff remain later stages.

For a currently recording, manifested WPILOG, **Open Source Overview** shows catalog facts first,
then the recording summary and at most 256 signal rows. Enter a literal name search and/or an
exact declared type and press **Apply filter** or Enter. **Previous** and **Next** replace the
row window; the count states the true matching total. **Show unavailable declarations only**
switches to bounded previews of names too large to list, with a reason; ordinary rows are excluded
and those previews cannot be selected. Name/type inputs are disabled in this mode and their
filters are not sent. Uncheck it and apply to return to the retained ordinary search and type.
Choose **Inspect** beside a signal for fresh per-member counts, metadata, schemas and problems.
Conflicting declarations stay explicit; the overview does not infer a compatible live binding.
For a server-side compatibility check, pass this source reference and a pinned `template_ref`
or `configuration_json` to `visualization_template_check`. A recording result reports each
member, absent declarations and conflict reasons, using the complete native entry set rather
than the visible page. Only `resolved` bindings are compatible; a resource refusal is not
evidence that a signal is missing. The check does not create a view or start a follow. Temporary pressure gives a one-second
retry delay; retry the same intent after that delay. See the
[standalone reference](STANDALONE.md#http-transport) for bounds and remedies.

**Refresh signals / Retry** admits a new snapshot before retiring the old one. Selection stays
with the exact name in this source and is checked again. A visible overview refreshes itself
at the five-minute hard expiry; an idle-expiry refusal also reopens it. Failed refreshes show
a reason and retain the old rows and snapshot timestamp. A known-expired snapshot stays labeled
expired, with paging, filtering and Inspect disabled, until a replacement page is admitted.
Retry becomes available after a bounded delay; it does not spin in the background. Hiding or
closing cancels pending initialization and releases the handle and rows, and reveal opens fresh. A server without paged inventory
support shows an update hint. **Blank layout**, templates and Resume/Open now enter the connected recording-panel preview. An agent can prepare a recording layout with
`visualization_view_create` using the anchored source and a configuration or pinned template,
then use the existing view edit/save tools. This stores intent even when requirements are still
unresolved. Saved reopen and clone recheck the source; previously stored metadata remains
recoverable if it is unavailable. Creating layout metadata alone does not start acquisition; opening a visible panel with selected signals does. The stored identity is retained after finalization.

To inspect a recording layout's current bindings, call `visualization_view_bind` with its
view ID and current revision, for example
`{"api_version":1,"view_id":"the-working-UUID","expected_revision":1}`. Expect exact selected
names, unresolved requirements with reasons, clock evidence and `binding.follow_ready`. If the revision conflicts, read
the current view and decide against that configuration; do not silently retry on a newer edit.
Custom local readers can open the [revision-bound follow](READ_API.md#following-a-recording-view)
directly. The editor uses this same admission, without a separate compatibility lookup racing the open.

The overview calls the same owner as `visualization_source_inspect` with `inventory_mode: "paged"`
and the [list/get/close operations](TOOLS.md#visualization_source_entries_list). Tool inspection's
default full mode is unchanged. Recording panels reuse bounded discovery; timestamps
remain the recorded robot timestamps, separate from the inspection clock evidence.

Legacy store definitions remain intact. An API 2 Save of an already-open legacy view copies
its checkpoint into the server library before saving the current configuration. For other
legacy definitions, list them through the API 1 template catalog or source-view route, then
call [visualization_library_migrate](TOOLS.md#visualization_library_migrate) with the exact
store/object/revision reference. Refresh the library catalog and open its pinned reference.
A repeated migration returns the original copy reference, which may be older than later edits;
use the catalog to obtain the current revision. An unavailable source stays unresolved.
Oversized legacy files remain at their original locations for inspection/export.

### 5.2 Recording-panel preview

Open Source Overview on a recording member in the selected connection and choose **Blank layout**.
Add a plot, value table, statistics panel or raw table, then open **Recording signals**. Search, **Inspect** the
exact signal, choose the target panel, and **Add inspected signal to target panel**. Expired,
conflicting or unavailable rows cannot be added. The picker handles large inventories one
page at a time; it does not download all topics. For a numeric struct field or array element,
enter its exact path in **Numeric field or array index**, for example `.translation.x`, `[2]`,
or `[1].speed`, then choose **Check and add field** (or press Enter in that field). Use the
recorded schema shown by Inspect; no field names or indexes are inferred. The server checks
all admitted members before the layout changes. A refused path, missing numeric evidence or
conflicting schema leaves the layout unchanged and shows the member reason beside the input.
Wildcards and nonnumeric fields are refused. **Add inspected signal to target panel** still
selects the whole entry. Reuse a field in plot, value, statistics and raw panels; they share
one exact requirement. Changing the input, selection, target or view, hiding, or closing cancels
an outstanding check. Capacity refusals need an explicit retry. Entries-sidebar routing into a
recording gives the in-panel picker remedy.

The initial interval follows the last 30 seconds in the recording clock. **Earlier time** and
**Later time** move by half the displayed window; **Zoom in** and **Zoom out** halve or double
its width about the center. These buttons support Tab/Enter. Drag horizontally in a time-series
plot to select an interval when time-range linking is enabled. Each gesture enters **Inspect
History**, saves the interval and reads that interval again. **Recorded time and cursor** /
**Apply range** also accepts explicit bounds; both Start and End are required. The controls
apply to all panels in this view. Histogram/scatter value axes do not accept time gestures.
Incoming data, including late records, cannot move a fixed interval. Navigation resumes a
paused display. **Resume following** returns to the visible 1–300-second window choice while
active; that choice survives editor restoration as UI state. A newly opened fixed view starts
with a 30-second choice. The fixed interval itself is part of the saved configuration and is
retained after finalization. **Full recording** becomes available after finalization. It starts
a fresh read of the recording even when the saved recorded policy was already full, so samples
outside the former following window are fetched too, under the display limits below.
The clock line
names the displayed completed prefix, its right edge and its evidence. An interrupted time
estimate holds the previous robot edge; otherwise the last sample is used. Neither sample
positions nor the right edge use this laptop's wall clock. A prefix can take several pages;
plot/raw panels publish it together after its selected entries are consumed.

**Pause display** freezes the visible frame and cancels pending analysis while acquisition and recording continue; **Resume
display** shows the newest completed prefix. A title, geometry or style edit reuses acquisition.
A signal/time edit replaces only that view's follow. **Duplicate and Open Beside** has an
independent lease, so closing or changing one does not restart the other. Hide cancels data
work immediately and clears its retained buffers after 30 seconds; reveal bootstraps the same
recording. Finalization drains it and ends polling. **Retry recording** reopens that same source.

Follow plots retain at most 4,096 recently delivered samples per selected signal, with explicit trimming counts.
Inspect History and Full recording instead request server min/max bands and means across the
whole admitted interval. Their notes show original-sample counts, gaps, decode problems and
the independent catalog prefix. Their bucket count fits the shared byte allowance, at most
4,096; zoom in for detail. An admission or work limit is an explained refusal, retried by
**Retry recording**. Late historical samples refresh the fixed interval without moving its axis.
Recorded files use their existing server reduction when their point allowance requires it.
Follow points sort for display and collapse equal timestamps to the last delivered point; these points
are never analysis input. Unsafe integers and non-finite numbers are plot gaps. Value tables
show the server's **latest by timestamp** with exact integer text, member and ordinal. Use
**Recorded time and cursor**, enter robot time and **Read at cursor** to read the last original
sample at/before that time; **Latest recorded values** returns to the timestamp maximum. Pause disables these
cursor controls until resumed. Raw tables retain 256 recent
rows per selection with member and ordinal identity, including duplicate/backward timestamps.
Decode problems carry a total and up to 32 recent ordinal diagnostics; their time may be unknown.
**Add statistics** uses all original samples across members in the selected interval. While
recording, choose a 1–300-second Follow window or fixed Start/End interval; a wider active
statistics interval gives an explicit remedy. After finalization **Full recording** is allowed
under the server's record/sample/memory limits. Expand **Independent analysis prefix** beside
a value or statistic to inspect its member boundaries, inputs and decode problems. These fresh
reads may be newer than the plot frontier; the UI never presents them as one atomic frame.
Automatic refreshes coalesce to at most one round per second, with one analysis active per
window and at most 32 views waiting. A cursor replaces older pending cursor work without
recomputing statistics. Hiding cancels reads; reveal waits for a fresh completed binding. Busy
server admission retries automatically. A numeric window with no finite samples displays a
hint and refreshes automatically when another prefix completes; no manual Retry is needed.
Other refusals stay visible until **Retry recording**
or a changed query; reduce the interval/selection or free buffer space as the reason directs.

For the same answer through MCP, pass the inspected `source_ref` and exact `entry` to
`visualization_source_value` (`api_version: 1`, optional inclusive `at_sec`) or
`visualization_source_statistics` (optional inclusive `start_time`/`end_time`), with scalar
`field` when needed. The value reports the timestamp, member and physical ordinal; statistics
report all original samples in the admitted range, quality and decode problems. Both admit a
fresh recording prefix, which may be newer than the displayed frame. Equal timestamps choose
the greatest original member ID then physical ordinal; they are all counted in statistics.
Inspect `prefix`, `inputs` and `problems`, and respect an explained resource refusal rather
than treating it as no data. A null cursor value is no interpolation or earlier-value fallback.
The [standalone reference](STANDALONE.md#recorded-visualization-preview) lists the bounds.

For distribution or pair data through an agent, first obtain the `source_ref` with
`visualization_source_inspect`. Call `visualization_source_histogram` with `api_version: 1`,
that reference and an exact `entry`; supply `field` separately for a numeric field or array
index. Expect bin boundaries/counts, a full-interval summary and independent prefix evidence.
For scatter, call `visualization_source_scatter` with exact `x_entry` and `y_entry` (optional
`x_field`/`y_field`). Expect only exact-time pairs, plus matched and unpaired counts. A limited
page also reports `next_offset`, `complete` and the true match total. Inspect the prefix before
combining successive pages while a recording grows, because each call reads a fresh prefix.
Use `last_seconds` 1–300 or an explicit 1–300-second interval on an active recording. Refusals
name a remedy; retryable slot pressure includes a one-second delay.

In a file or recording layout, choose **Add histogram** or **Add scatter**, select that target
panel, then add numeric signals through the same picker. A histogram accepts several signals;
each has its own server bins, overlaid with a legend and an **Exact server measurements** table
of bin edges and counts. Scatter accepts two signals in selection order: first X, then Y.
The panel labels both axes; **Swap X and Y** persists the reversed pair. Incomplete or unresolved
pairs remain placeholders, and text/composite roots require a numeric field or another panel.

Scatter shows only exact-time one-to-one pairs; duplicate times remain separate. The panel
reports full matched/unpaired totals and a bounded page of at most 256 pairs (less when the
window allowance requires it). **Next pairs** and **First pairs** replace the page, reading a
fresh prefix each time. They do not accumulate one frozen snapshot. Automatic refresh retains
the current offset, which can become empty as a rolling window changes; **First pairs** returns
to zero. The exact table includes original robot-time seconds beside X and Y. Chart axes are
values and do not participate in the linked time cursor.

Both charts use the server's original samples and show their numerical rule, quality, diagnostics
and **Independent analysis prefix**. They share the window analysis queue and at-most-one
refresh round per second with recording values/statistics. Active intervals are 1–300 seconds; finalized
recordings and files allow Full under server limits. Presentation edits keep the result; axis,
selector or interval changes re-admit it. **Pause display** freezes recording charts and disables
paging/refresh until resumed. Hide cancels work, reveal reads fresh evidence, and close releases
buffers. **Refresh chart / Retry** retries an explained refusal without restarting acquisition.

Recorded-file samples, recording panels and paged inventory share one decoded-buffer allowance
per window. In Settings, change `wpilog-mcp.visualization.decodedBufferMiB` (an integer from 16
to 1024 MiB, default 64), then run **Developer: Reload Window**. The preview shows the effective
allowance; `visualization_capabilities_get` reports the default/range and each attached window's
client-reported allowance under an opaque window ID, with `current_session` marking your own. Changing Settings alone does not resize an active window's budget.
Encoded acquisition remains separately capped at 64 MiB. Recorded plots ask the server for a
budget-sized reduction, up to 65,536 points per signal; signal count and repeated plot/table copies reduce
that allowance. Adding or removing repeated displays re-admits that view at its new allowance. Increasing it can retain more exact samples before bucketing. Follow plots retain
their 4,096-sample limit; recording history requests at most 4,096 server buckets, sized for
the same allowance and every repeated plot copy.

Exhaustion refuses further work before downloading samples, with a reason. Use fewer signals,
close another view and Retry, or increase the setting and reload. Hidden samples clear after
30 seconds; replacement and clearing release their charge only after the renderer acknowledges
that its plots and table contents were replaced. Live updates coalesce at four per second. A
renderer that fails to acknowledge within five seconds stops further delivery for that view;
close/reopen releases its charged display. These conservative buffer estimates do not bound
all process memory, control metadata or server caches. Remote recording sources and the full
Stage 3 performance gate remain pending.

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
use **Set Up Data Store…** for continuous imports from selected folders. Import policy does not select a default MCP read source: select one with `list_source_scopes`
and `scope_id`. In Explorer, expand a connection root and select its source scope. In
**Manage Data Store…**, choose **Show source status** for per-source states and refusal reasons.
The output channel shows the first 100 file receipts and, when more exist, the total and the
endpoint's paging parameters. **Sync Data Store Now** requests a scan and **Pause/Resume Data Store Sync** stops or resumes
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
The command probes the configured port and reported server name, including a systemd-managed
server without a PID record. An import from that server's configured directories is submitted
to it; the printed job line identifies the server-owned request. Other sources are copied
into the store's `inbox/` for import. An answering stranger, wrong name or failed request refuses
instead of starting another writer. Use the server's `WPILOG_HTTP_BIND` environment; a specific
non-loopback bind requires the byte-upload route or a loopback/wildcard bind, as the reference
explains. The Logs view also
offers **Import**. Reference: [Importing Logs](STANDALONE.md#importing-logs).

An import stages its inspection and copy work away from capture's mutation owner. Capture
admission takes the next turn before waiting import turns and retries a briefly held
`store.lock` within its admission deadline. A source changed after staging is refused with
its reason and left in place; retry after its writer has finished. An over-one-second store
turn produces one log notice and increments `wpilog_store_queue_over_budget_turns_total`.

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

#### 7.4 Syncing from another laptop

Run `wpilog-mcp sync http://other-laptop:2363`, or use **Sync from Laptop**, to bring missing
files into the selected local store. The owning server downloads, verifies and prepares
copies away from the store mutation worker, so capture admission does not wait for a peer
read or a payload copy. Short turns recheck and publish each file; shared filesystem latency
still affects both operations.

A copy that would consume capture's reserve is refused before payload I/O. Its reason names
`free_bytes`, `reserved_bytes`, `capture_reserve_bytes` and `requested_bytes`. Free space or
adjust the configured reserve before retrying. Interrupted transfers keep their journaled
prefixes, and the peer must prove those bytes before they resume. At the next sync, pending
placement receipts recover before any peer connection. Incomplete downloads and system-text
prefixes stay available for resume. A completed cross-filesystem telemetry copy's original
staging bytes are removed after verification, including when recovery finds an already
committed receipt; changed originals remain with a refusal. Do not delete `.sync` or its
lock file to force a retry. A second sync to the same store is refused.
See [Syncing Stores Between Laptops](pit-server/STANDALONE.md#syncing-stores-between-laptops)
for server selection, rate limits and result fields.

### 8. Maintenance

- **Upgrading.** Run the installer again ([6.6](#66-upgrade-replace-uninstall)). A background
  server is replaced at its next `start` or `connect`. For a systemd service, run
  `sudo systemctl restart wpilog-mcp-http.service`.
- **Backing up a store.** Stop the server and copy the whole store directory.
- **Disk space.** The REV synchronization cache has its own `diskcachesize` cap; imported logs
  are kept until you choose to remove them. See [Config Fields](STANDALONE.md#config-fields).
  `/health` reports `store_filesystems` with `free_bytes`, `reserved_bytes` and
  `capture_reserve_bytes`; unknown free space is null. With default capture settings, at
  256 MiB usable space or less recording closes cleanly while NT4 stays connected. Free space
  by removing or moving files you intend to retire, then leave the server running: a new
  recording starts once usable space exceeds 768 MiB. The old low-disk end reason and the
  new session's start time show the unrecorded interval. Unknown space leaves the current
  state unchanged. Import, upload, replica, known-length robot/peer downloads and their
  additional placement copies share the reserve. Unknown-length command output remains
  outside that admission policy. See [capture disk settings](pit-server/STANDALONE.md#pit-server).
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
| `[wpilog] Dropped log output` appears | The line's lost-line and byte counts; `wpilog_log_dropped_lines_total` | The terminal or journal stopped keeping up with the 1 MiB log buffer. Restore its output path; recording and requests continue, but the discarded diagnostic lines cannot be recovered. |
| A log is reported truncated | `_metadata.log_truncation` in results | Normal for a robot switched off while logging; every result says what was read |
| An imported log sits in its own session | `matching_reason` in `list_available_logs` | The data proof failed or was ambiguous; the file is still readable by path. Nothing is guessed |
| An import is refused as outside the configured directories | The command's output | Copy the files into the store's `inbox/` |
| Non-ASCII filenames are refused | The server log names the native encoding | Run the server with a UTF-8 locale (`LANG=C.UTF-8 LC_ALL=C.UTF-8`) |

More: [Troubleshooting](STANDALONE.md#troubleshooting) in the standalone guide and
[Troubleshooting](../vscode-extension/README.md#troubleshooting) in the extension guide.

### Read recording health and recover a view

In the recording preview, read acquisition status beside follow progress. A large last-value
age is normal for unchanged signals; “reconnecting” means the acquisition connection was lost.
History can be finalized during a disconnect and resume under the same robot clock. The view
keeps its anchor and does not switch to a new recording after a robot restart; open that new
recording's overview deliberately.

Leave a view open through a local server restart. Its old reads stop, its window and attachment
are restored, and fresh buffers are loaded for the same layout. A paused display stays paused;
use Resume display after recovery. A transient restoration refusal retries automatically.
A changed server identity or missing view/source remains visible with the configuration retained;
reconnect to the original owner or restore access rather than expecting a new view to be created.
The timing line measures host/render scheduling only, not network or robot-to-screen latency.
Agents can use `visualization_source_health` with the view's anchored `source_ref` for the same
acquisition/history facts without reading sample data.

## Select a server source for MCP reads

1. Call `list_source_scopes` and follow `next_offset` if present. When several choices exist,
   ask the user which source to use, even if only one is online or has a matching filename.
   An unreadable directory stays listed as unavailable; explicitly selecting a healthy source
   still works. Restore the failed directory's access to make it available again.
2. Call `list_available_logs` with that `scope_id`. Expect `source_scope` and logs from only
   that source. `SOURCE_UNAVAILABLE` means the chosen source needs attention, not permission
   to switch to another copy.
3. Use a listed absolute `path`, or pass `scope_id` with a relative path, to analysis tools.
   An already selected file or working view remains selected; do not ask again for each read.
4. For source-qualified visualization calls, use `api_version: 2` and
   `visualization_source_inspect` with the path and scope. Retain the complete returned
   `source_ref`, including its server and scope. API 1 clients cannot edit these qualified views.
   For an ordinal follow, use the API 2 working view's
   [view binding](READ_API.md#following-a-recording-view); raw `/read/v1/follows` source/query
   requests reject qualification instead of discarding it.

Explorer shows explicit connection roots and their source scopes; direct remote visualization
uses the selected server without a local copy.
Import destination preferences and old Show All Sources controls cannot override MCP selection.

### Inspecting the mirror migration checkpoint

Format-1 mirrors keep automatic downloads paused until explicit migration. Existing local copies remain readable as their own
source, and direct remote visualization continues to use the selected server.

1. Read `GET /store/mirror` on the local server. A legacy mirror reports `migration_required`.
2. For an explicit upgrade, send its exact store UUID to `POST /store/mirror/migrate`. Poll status
   until `ready` or `error`; an error leaves the recordings intact.
3. Inspect migrated records with `GET /store/mirror/files?offset=0&limit=100`. Advance by the number
   returned until `has_more` is false; `total` is the full count. Legacy copies are unconfirmed.
4. Send 1–32 replica keys to `POST /store/mirror/cleanup_preview`. The reply names exact files,
   byte totals, affected working/saved views and prospective acquisition exclusions. It reports
   historical master evidence, not a live master-presence check. An incomplete view inspection refuses.
5. To execute that selection, send `request_id` (a new UUID), `store_id`, `catalog_epoch`,
   `catalog_revision`, `policy_revision` and the same `keys` to `POST /store/mirror/cleanup`.
   The response is 202; poll status until `maintenance_complete` or `error`. Inspect `operation`:
   a stale selection changes nothing. Keep the UUID and exact request for a lost-response retry.
   Views remain saved; readers already holding a generation finish before its bytes retire.
6. List `GET /store/mirror/exclusions?offset=0&limit=100` to find excluded keys. Explicit
   re-inclusion sends `request_id`, `catalog_epoch`, `catalog_revision`, `policy_revision` and
   `keys` to `POST /store/mirror/reinclude`. It removes only those acquisition exclusions; it
   neither downloads files nor edits definitions. Poll the same operation status.

The replica summary's catalog capacity shows record and exclusion counts, each with a
4,096 limit. Its near-limit flag turns on at 3,277 of either count. This warns before
count-based admission fails; encoded metadata limits and payload space are separate.
Missing capacity facts mean unavailable, not zero. The warning does not authorize deleting
held copies or prove that every completed capture has entered the mirror's catalog.

For master migration, use the local job route described in
[Explicit replica-format migration](STANDALONE.md#explicit-replica-format-migration). The checkpoint
implements paged reconciliation; native platform and durability validation remain required before operational mirror upgrades.


For several mirrors, configure the YAML `mirrors` list or extension `wpilog-mcp.mirrors` list.
Every entry selects its own master and folder. Inspect `GET /store/mirror`, choose a `mirror_id`,
and add `?mirror=<id>` to each local control above. A missing selection is a refusal, not a
fallback. In VS Code, use Mirror Actions to inspect status/discovery, migrate a selected legacy
mirror, synchronize or include a session. Show Mirror Status includes the historical master
coverage revision/time; `partial` means no complete successful master traversal. Do not confuse
peer availability with master verification. Provisional data can be analyzed, with its hash and
basis in the result. If the master disproves it, a visible preview refreshes the same logical
source after the corrected generation publishes. Scope changes and a full cap preserve held
files. The [replica protocol reference](pit-server/STANDALONE.md#replica-protocol-and-multiple-mirrors)
explains manual peers, offline master IDs, discovery limits and service ownership.


If `list_sessions` reports `replica_publication_failure`, capture is still recording locally, but
one or more completed receipts could not enter the replica catalog. Preserve the store and its
`.replica-publication.json` markers. Check the reported capacity or I/O failure before retrying
publication. A restart may continue recording when recovery verifies the local receipts and
only catalog publication remains pending; unsafe or corrupt recovery still stops startup. The
[mirror reference](pit-server/STANDALONE.md#the-mirror) lists the metadata limits and recovery bound.

To inspect the origin from another machine, obtain a replica handshake, select its exact
`store_id`, and send `POST /replicas/v1/status` with that ID and the `Replica-Session` header.
Read `pending_total` and each pending entry's `code`, safe `message`, `first_failed_at` and
`failed_at`. A shortened summary reports its true total in `limits.pending_publications`;
select an omitted session with the optional exact `session_id`. Its retryable
`PUBLICATION_PENDING` reply carries the recorded cause. `CAUSE_NOT_RECORDED` means an older
marker has no diagnostic, not that the catalog count proves a cause. If `cause_durable` is
false, preserve the store and inspect local I/O errors: the current cause could not be saved.

Resolve the reported metadata-capacity, authority or I/O problem before retrying recovery
(including on restart); do not delete pending markers to hide it. A successful retry clears
the entry and preserves its published file identities. `BUSY` means recovery or the selected
publication changed; retry after the stated delay rather than treating the session as absent.
The local Mirror Status view still describes the local replica. Use the origin's
[remote publication status](STANDALONE.md#remote-replica-publication-status) for its pending
receipts. Status reads do not themselves trigger retry or a filesystem walk.
